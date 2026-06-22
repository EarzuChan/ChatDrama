package me.earzuchan.chatdrama.client.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.earzuchan.chatdrama.client.data.database.AiChatMessageDao
import me.earzuchan.chatdrama.client.data.database.AiChatMessageEntity
import me.earzuchan.chatdrama.client.data.database.AiChatMessageRole
import me.earzuchan.chatdrama.client.data.database.toTempAiMessage
import me.earzuchan.chatdrama.client.data.model.TempAiMessage
import me.earzuchan.chatdrama.client.utils.currentTimeMillis

class AiChatRepository(private val dao: AiChatMessageDao) {
    val messages: Flow<List<TempAiMessage>> = dao.observeAll().map { messages -> messages.map { it.toTempAiMessage() } }

    suspend fun snapshot(): List<TempAiMessage> = dao.getAll().map { it.toTempAiMessage() }

    suspend fun addUserMessage(content: String) = dao.insert(
        AiChatMessageEntity(role = AiChatMessageRole.User.value, content = content, timestamp = currentTimeMillis())
    )

    suspend fun addLlmMessage(content: String = "", thought: String? = null, isStreaming: Boolean = false, error: String? = null) = dao.insert(
        AiChatMessageEntity(role = AiChatMessageRole.Llm.value, content = content, thought = thought, timestamp = currentTimeMillis(), isStreaming = isStreaming, error = error)
    )

    suspend fun updateLlmMessage(id: Long, content: String, thought: String? = null, isStreaming: Boolean = false, error: String? = null) = dao.updateLlmMessage(id, thought, content, isStreaming, error)

    suspend fun clear() = dao.clear()
}
