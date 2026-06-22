package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.earzuchan.chatdrama.client.data.repository.AiChatRepository
import me.earzuchan.chatdrama.client.data.repository.LlmSettingsRepository
import me.earzuchan.chatdrama.client.data.createLlmApi
import me.earzuchan.chatdrama.client.data.model.TempAiMessage
import me.earzuchan.chatdrama.client.data.textContent
import me.earzuchan.chatdrama.framework.llm.ContentPart
import me.earzuchan.chatdrama.framework.llm.LlmEvent
import me.earzuchan.chatdrama.framework.llm.LlmMessage
import me.earzuchan.chatdrama.framework.llm.LlmRequest
import me.earzuchan.chatdrama.framework.llm.ReasoningEffort
import me.earzuchan.chatdrama.framework.llm.ReasoningMode

class TestAiChatViewModel(private val llmSettingsRepository: LlmSettingsRepository, private val aiChatRepository: AiChatRepository) : ViewModel() {
    val messages = aiChatRepository.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val isSending = _busy.asStateFlow()

    fun setInput(input: String) {
        _input.value = input
    }

    fun sendInput() {
        val content = _input.value.trim()
        if (content.isEmpty() || _busy.value) return

        _input.value = ""
        _busy.value = true

        viewModelScope.launch {
            aiChatRepository.addUserMessage(content)
            val history = aiChatRepository.snapshot()
            val assistantId = aiChatRepository.addLlmMessage(isStreaming = true)
            var answer = ""
            var thought = ""
            var completed = false

            try {
                val settings = llmSettingsRepository.settings.first()

                val api = createLlmApi(settings)
                val request = LlmRequest(history.toLlmMessages(), reasoning = if (settings.preferReasoning) ReasoningMode.Effort(ReasoningEffort.High) else ReasoningMode.Off)

                api.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            answer += event.text
                            aiChatRepository.updateLlmMessage(assistantId, answer, thought.orNull(), isStreaming = true)
                        }

                        is LlmEvent.ReasoningDelta -> {
                            thought += event.text
                            // println("${assistantId}的思考：$thought")
                            aiChatRepository.updateLlmMessage(assistantId, answer, thought.orNull(), isStreaming = true)
                            // println("VM after update snapshot=${aiChatRepository.snapshot().lastOrNull()}")
                        }

                        is LlmEvent.Completed -> {
                            val finalText = event.response.content.textContent()
                            if (answer.isBlank() && finalText.isNotBlank()) answer = finalText
                            completed = true
                            aiChatRepository.updateLlmMessage(assistantId, answer.ifBlank { "（模型啥也没说）" }, thought.orNull())
                        }

                        is LlmEvent.ToolArgumentsDelta, is LlmEvent.ToolCallCompleted, is LlmEvent.ToolCallStarted -> Unit
                    }
                }

                if (!completed) aiChatRepository.updateLlmMessage(assistantId, answer.ifBlank { "（连接意外关闭）" }, thought.orNull())
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable

                val message = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.simpleName.orEmpty().ifBlank { "未知错误" }

                val failedContent = buildString {
                    if (answer.isNotBlank()) {
                        append(answer.trim())
                        append("\n\n")
                    }
                    append("请求失败：")
                    append(message)
                }

                println(failedContent)
                aiChatRepository.updateLlmMessage(assistantId, failedContent, thought.orNull(), error = message)
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearMessages() {
        viewModelScope.launch { aiChatRepository.clear() }
    }
}

private fun List<TempAiMessage>.toLlmMessages(): List<LlmMessage> = mapNotNull { message ->
    when (message) {
        is TempAiMessage.FromUser -> LlmMessage.User(listOf(ContentPart.Text(message.content)))

        is TempAiMessage.FromLlm -> message.content.takeIf { it.isNotBlank() && message.error == null }?.let { LlmMessage.Model(listOf(ContentPart.Text(it))) }
    }
}

private fun String.orNull(): String? = takeIf { it.isNotBlank() }
