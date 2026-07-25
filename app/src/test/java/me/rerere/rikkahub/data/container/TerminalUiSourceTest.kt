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
    private val advancedSettingsSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAdvancedPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAdvancedPage.kt")
    ).first { it.isFile }.readText()
    private val chatPageSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
    ).first { it.isFile }.readText()
    private val routeSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/RouteActivity.kt"),
        File("src/main/java/me/rerere/rikkahub/RouteActivity.kt")
    ).first { it.isFile }.readText()

    @Test
    fun terminalKeysSupportCustomLabelsAndShiftLatch() {
        assertTrue(processSessionSource.contains("val label: String? = null"))
        assertTrue(processSessionSource.contains("SHIFT"))
        assertTrue(processSessionSource.contains("shiftLatch"))
        assertTrue(processSessionSource.contains("sequenceFor(key, shift = shiftLatch"))
        assertTrue(processSessionSource.contains("""placeholder = { Text("显示名") }"""))
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
        assertTrue(chatInputSource.contains("padding(top = 2.dp)"))
    }

    @Test
    fun customTuiEntryLivesInAdvancedSettingsNotSessionToolbar() {
        assertTrue(advancedSettingsSource.contains("自定义 TUI 程序名单"))
        assertTrue(advancedSettingsSource.contains("terminalCustomTuiCommands"))
        assertTrue(!processSessionSource.contains("showCustomTuiDialog"))
        assertTrue(!processSessionSource.contains("TextButton(onClick = { showCustomTuiDialog = true })"))
    }

    @Test
    fun longPressTerminalButtonNavigatesToStartedTerminalPanel() {
        assertTrue(routeSource.contains("data class ProcessSessions(val sandboxId: String, val processId: String? = null)"))
        assertTrue(processSessionSource.contains("initialProcessId"))
        assertTrue(chatPageSource.contains("Screen.ProcessSessions(conversation.id.toString(), result.processId)"))
    }

    @Test
    fun conversationListDisplaysRunningProcessCountInErrorColor() {
        assertTrue(conversationListSource.contains("runningProcessCount"))
        assertTrue(conversationListSource.contains("MaterialTheme.colorScheme.error"))
    }
}
