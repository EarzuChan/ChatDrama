package me.earzuchan.chatdrama.client.data.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiChatMessageDao {
    @Query("SELECT * FROM ai_chat_messages ORDER BY timestamp ASC, id ASC")
    fun observeAll(): Flow<List<AiChatMessageEntity>>

    @Query("SELECT * FROM ai_chat_messages ORDER BY timestamp ASC, id ASC")
    suspend fun getAll(): List<AiChatMessageEntity>

    @Insert
    suspend fun insert(message: AiChatMessageEntity): Long

    @Query("UPDATE ai_chat_messages SET thought = :thought, content = :content, isStreaming = :isStreaming, error = :error WHERE id = :id")
    suspend fun updateLlmMessage(id: Long, thought: String?, content: String, isStreaming: Boolean, error: String?)

    @Query("DELETE FROM ai_chat_messages")
    suspend fun clear()
}