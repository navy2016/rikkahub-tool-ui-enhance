package me.rerere.rikkahub.data.container

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalUiSourceTest {
    private val processSessionSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt")
    ).first { it.isFile }.readText()
    private val chatInputSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt")
    ).first { it.isFile }.readText()
    private val conversationListSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt")
    ).first { it.isFile }.readText()

    @Test
    fun terminalKeysSupportCustomLabelsAndShiftLatch() {
        assertTrue(processSessionSource.contains("val label: String? = null"))
        assertTrue(processSessionSource.contains("SHIFT"))
        assertTrue(processSessionSource.contains("shiftLatch"))
        assertTrue(processSessionSource.contains("sequenceFor(key, shift = shiftLatch"))
        assertTrue(processSessionSource.contains("显示名"))
    }

    @Test
    fun terminalQuickCommandsIncludeOmpInstallAndTest() {
        assertTrue(processSessionSource.contains("""TerminalQuickCommandConfig("install-omp", "rikkahub-install-omp")"""))
        assertTrue(processSessionSource.contains("""TerminalQuickCommandConfig("test-omp", "rikkahub-test-omp")"""))
    }

    @Test
    fun chatInputHasTerminalButtonWithLongPress() {
        assertTrue(chatInputSource.contains("TerminalSessionButton"))
        assertTrue(chatInputSource.contains("onStartFirstTerminalQuickCommand"))
        assertTrue(chatInputSource.contains("combinedClickable(onClick = onClick, onLongClick = onLongClick)"))
    }

    @Test
    fun conversationListDisplaysRunningProcessCountInErrorColor() {
        assertTrue(conversationListSource.contains("runningProcessCount"))
        assertTrue(conversationListSource.contains("MaterialTheme.colorScheme.error"))
    }
}
