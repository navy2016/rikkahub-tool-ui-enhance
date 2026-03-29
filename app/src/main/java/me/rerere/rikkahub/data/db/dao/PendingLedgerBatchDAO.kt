package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.PendingLedgerBatchEntity

@Dao
interface PendingLedgerBatchDAO {
    @Query(
        "SELECT * FROM pending_ledger_batch WHERE conversation_id = :conversationId ORDER BY start_index ASC, created_at ASC, id ASC"
    )
    suspend fun getBatchesOfConversation(conversationId: String): List<PendingLedgerBatchEntity>

    @Query(
        "SELECT * FROM pending_ledger_batch WHERE conversation_id = :conversationId AND status IN ('pending', 'failed', 'running') ORDER BY start_index ASC, created_at ASC, id ASC"
    )
    suspend fun getProcessableBatchesOfConversation(conversationId: String): List<PendingLedgerBatchEntity>

    @Query("SELECT * FROM pending_ledger_batch WHERE conversation_id = :conversationId AND event_id = :eventId LIMIT 1")
    suspend fun getBatchByConversationAndEvent(conversationId: String, eventId: Long): PendingLedgerBatchEntity?

    @Insert
    suspend fun insert(batch: PendingLedgerBatchEntity): Long

    @Update
    suspend fun update(batch: PendingLedgerBatchEntity)

    @Query("DELETE FROM pending_ledger_batch WHERE conversation_id = :conversationId AND event_id = :eventId")
    suspend fun deleteByConversationAndEvent(conversationId: String, eventId: Long)

    @Query("DELETE FROM pending_ledger_batch WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}
