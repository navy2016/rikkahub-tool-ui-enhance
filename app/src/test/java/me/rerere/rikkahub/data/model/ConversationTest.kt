package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ConversationTest {

    @Test
    fun `updateCurrentMessages with offset only rewrites the requested suffix`() {
        val preservedUser = UIMessage.user("before compression")
        val preservedAssistant = UIMessage.assistant("still selected")
        val existingTailAssistant = UIMessage.assistant("old tail reply")
        val streamedTailAssistant = existingTailAssistant.copy(parts = UIMessage.assistant("new tail reply").parts)

        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                preservedUser.toMessageNode(),
                preservedAssistant.toMessageNode(),
                existingTailAssistant.toMessageNode()
            )
        )

        val updatedConversation = conversation.updateCurrentMessages(
            messages = listOf(streamedTailAssistant),
            startIndex = 2
        )

        assertSame(preservedUser, updatedConversation.messageNodes[0].currentMessage)
        assertSame(preservedAssistant, updatedConversation.messageNodes[1].currentMessage)
        assertEquals(streamedTailAssistant.id, updatedConversation.messageNodes[2].currentMessage.id)
        assertEquals("new tail reply", updatedConversation.messageNodes[2].currentMessage.toText())
    }

    @Test
    fun `updateCurrentMessages with offset preserves earlier branch indices`() {
        val head = UIMessage.user("head")
        val preservedBranchA = UIMessage.assistant("branch a")
        val preservedBranchB = UIMessage.assistant("branch b")
        val tailUser = UIMessage.user("tail user")
        val tailAssistant = UIMessage.assistant("tail assistant")
        val streamedTailAssistant = tailAssistant.copy(parts = UIMessage.assistant("tail assistant updated").parts)

        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(
                head.toMessageNode(),
                MessageNode(messages = listOf(preservedBranchA, preservedBranchB), selectIndex = 1),
                tailUser.toMessageNode(),
                tailAssistant.toMessageNode()
            )
        )

        val updatedConversation = conversation.updateCurrentMessages(
            messages = listOf(tailUser, streamedTailAssistant),
            startIndex = 2
        )

        assertEquals(1, updatedConversation.messageNodes[1].selectIndex)
        assertEquals(2, updatedConversation.messageNodes[1].messages.size)
        assertSame(preservedBranchB, updatedConversation.messageNodes[1].currentMessage)
        assertEquals("tail assistant updated", updatedConversation.messageNodes[3].currentMessage.toText())
    }

    @Test
    fun `compression state treats dialogue summary or ledger as available summary`() {
        val dialogueOnly = ConversationCompressionState(dialogueSummaryText = "Current State:\n- Continue work")
        val ledgerOnly = ConversationCompressionState(rollingSummaryJson = """{"facts":[{"text":"Port is 6432"}]}""")

        assertTrue(dialogueOnly.hasSummary)
        assertTrue(dialogueOnly.hasDialogueSummary)
        assertTrue(ledgerOnly.hasSummary)
        assertTrue(ledgerOnly.hasLedger)
    }

    @Test
    fun `latest compression event prefers higher id when timestamps are equal`() {
        val createdAt = Instant.parse("2026-03-23T10:15:30Z")
        val olderInsertedEvent = CompressionEvent(id = 7L, boundaryIndex = 4, createdAt = createdAt)
        val newerInsertedEvent = CompressionEvent(id = 8L, boundaryIndex = 6, createdAt = createdAt)

        val latest = listOf(olderInsertedEvent, newerInsertedEvent).latestCompressionEvent()

        assertEquals(newerInsertedEvent.id, latest?.id)
    }

    @Test
    fun `latest compression event returns null for empty lists`() {
        assertNull(emptyList<CompressionEvent>().latestCompressionEvent())
    }
}
