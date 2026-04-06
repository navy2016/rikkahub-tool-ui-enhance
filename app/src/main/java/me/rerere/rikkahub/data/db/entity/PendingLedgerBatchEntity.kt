package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_ledger_batch",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["conversation_id", "status"]),
        Index(value = ["conversation_id", "event_id"], unique = true),
    ]
)
data class PendingLedgerBatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("event_id")
    val eventId: Long,
    @ColumnInfo("start_index")
    val startIndex: Int,
    @ColumnInfo("end_index")
    val endIndex: Int,
    @ColumnInfo("incremental_messages")
    val incrementalMessages: String,
    @ColumnInfo("status", defaultValue = "pending")
    val status: String = "pending",
    @ColumnInfo("attempt_count", defaultValue = "0")
    val attemptCount: Int = 0,
    @ColumnInfo("last_error", defaultValue = "")
    val lastError: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
