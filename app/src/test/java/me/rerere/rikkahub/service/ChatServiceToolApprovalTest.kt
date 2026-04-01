package me.rerere.rikkahub.service

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceToolApprovalTest {
    @Test
    fun `preserve pending tool node for approved tool`() {
        assertTrue(
            shouldPreservePendingToolNode(
                listOf(
                    pendingTool(approvalState = ToolApprovalState.Approved)
                )
            )
        )
    }

    @Test
    fun `preserve pending tool node for answered ask user tool`() {
        assertTrue(
            shouldPreservePendingToolNode(
                listOf(
                    pendingTool(approvalState = ToolApprovalState.Answered("yes"))
                )
            )
        )
    }

    @Test
    fun `drop pending tool node when tool is still unresolved`() {
        assertFalse(
            shouldPreservePendingToolNode(
                listOf(
                    pendingTool(approvalState = ToolApprovalState.Pending)
                )
            )
        )
    }

    private fun pendingTool(approvalState: ToolApprovalState): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = "tool-call-id",
            toolName = "ask_user",
            input = """{"question":"continue?"}""",
            approvalState = approvalState,
        )
    }
}
