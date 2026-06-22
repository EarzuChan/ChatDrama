package me.earzuchan.chatdrama.client.data.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import me.earzuchan.chatdrama.client.data.model.TempAiMessage

@Entity(tableName = "ai_chat_messages")
data class AiChatMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val thought: String? = null,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

fun AiChatMessageEntity.toTempAiMessage(): TempAiMessage = when (role) {
    AiChatMessageRole.User.value -> TempAiMessage.FromUser(id, content, timestamp)

    else -> TempAiMessage.FromLlm(id, thought, content, timestamp, isStreaming, error)
}

enum class AiChatMessageRole(val value: String) { User("user"), Llm("llm") }
