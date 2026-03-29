package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.CompressionEventEntity

@Dao
interface CompressionEventDAO {
    @Query("SELECT * FROM compression_event WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    suspend fun getEventsOfConversation(conversationId: String): List<CompressionEventEntity>

    @Insert
    suspend fun insert(event: CompressionEventEntity): Long

    @Update
    suspend fun update(event: CompressionEventEntity)

    @Query("DELETE FROM compression_event WHERE conversation_id = :conversationId AND id = :eventId")
    suspend fun deleteByConversationAndId(conversationId: String, eventId: Long)

    @Query("DELETE FROM compression_event WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
