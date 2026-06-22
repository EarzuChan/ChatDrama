package me.earzuchan.chatdrama.client.data.model

import me.earzuchan.chatdrama.framework.llm.LlmProvider

// CHECK：UI Model 应移走
data class DisplayTempFakeMessage(val content: String, val fromMe: Boolean = true, val time: String? = null)

data class LlmSettings(val provider: LlmProvider = LlmProvider.OpenAiResponses, val endpoint: String = "", val model: String = provider.defaultModelName, val apiKey: String = "", val preferReasoning: Boolean = false)

// CHECK：UI Model？
sealed interface TempAiMessage {
    val content: String
    val timestamp: Long

    data class FromLlm(val id: Long = 0, val thought: String? = null, override val content: String, override val timestamp: Long, val isStreaming: Boolean = false, val error: String? = null) : TempAiMessage

    data class FromUser(val id: Long = 0, override val content: String, override val timestamp: Long) : TempAiMessage
}

val LlmProvider.defaultModelName: String
    get() = when (this) {
        LlmProvider.OpenAiLegacy , LlmProvider.OpenAiResponses -> "gpt5.5"
        LlmProvider.Claude -> "claude-sonnet-4.6"
        LlmProvider.Gemini -> "gemini-3-flash-preview"
    }

fun LlmSettings.withProvider(provider: LlmProvider): LlmSettings {
    val currentDefault = this.provider.defaultModelName
    val nextDefault = provider.defaultModelName
    val nextModel = if (model.isBlank() || model == currentDefault) nextDefault else model

    return copy(provider = provider, model = nextModel)
}
