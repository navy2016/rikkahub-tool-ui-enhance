package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant

@Serializable
data class PendingLedgerBatch(
    val id: Long = 0L,
    val conversationId: String,
    val eventId: Long,
    val startIndex: Int,
    val endIndex: Int,
    val incrementalMessages: String,
    val status: String = "pending",
    val attemptCount: Int = 0,
    val lastError: String = "",
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant = Instant.now(),
)
