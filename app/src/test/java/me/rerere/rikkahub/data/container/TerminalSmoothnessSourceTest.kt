package me.rerere.rikkahub.data.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalSmoothnessSourceTest {
    private val processSessionSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt")
    ).first { it.isFile }.readText()
    private val chatPageSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
    ).first { it.isFile }.readText()
    private val backgroundProcessManagerSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt")
    ).first { it.isFile }.readText()

    @Test
    fun terminalViewportAndActiveSessionAreRememberedInMemory() {
        assertTrue(backgroundProcessManagerSource.contains("data class TerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("saveTerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("data class TerminalSandboxUiState"))
        assertTrue(backgroundProcessManagerSource.contains("saveTerminalSandboxUiState"))
        assertTrue(processSessionSource.contains("bgManager.getTerminalViewportState(processId)"))
        assertTrue(processSessionSource.contains("saveTerminalViewport()"))
        assertTrue(processSessionSource.contains("DisposableEffect(processId)"))
        assertTrue(processSessionSource.contains("bgManager.saveTerminalSandboxUiState(sandboxId, activeInteractiveId, terminalFullscreen)"))
    }

    @Test
    fun imeResizeKeepsUiSmoothButDebouncesPtyResize() {
        assertTrue(processSessionSource.contains("TERMINAL_PTY_RESIZE_DEBOUNCE_MS"))
        assertTrue(processSessionSource.contains("keepBottomAfterNextLayout"))
        assertTrue(processSessionSource.contains("LaunchedEffect(imeVisible)"))
        assertTrue(processSessionSource.contains("terminalEmulator.resize(terminalColumns, terminalRows)"))
        assertTrue(processSessionSource.contains("delay(TERMINAL_PTY_RESIZE_DEBOUNCE_MS)"))
        assertTrue(processSessionSource.contains("bgManager.resizeInteractiveSession(processId, terminalColumns, terminalRows)"))
        assertFalse(processSessionSource.contains("if (imeVisible) kotlinx.coroutines.delay(300)"))
    }

    @Test
    fun tuiWidthDefaultsToFitAndQuickTerminalUsesUserSizeHint() {
        assertTrue(backgroundProcessManagerSource.contains("data class TerminalSizeHint"))
        assertTrue(backgroundProcessManagerSource.contains("saveTerminalSizeHint"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalSizeHint"))
        assertTrue(backgroundProcessManagerSource.contains("refreshProcessStates()"))
        assertTrue(processSessionSource.contains("mutableStateOf(savedPreference?.forcedTerminalColumns)"))
        assertFalse(processSessionSource.contains("if (isTuiCommand(process.command, settings.terminalCustomTuiCommands)) 120 else null"))
        assertTrue(chatPageSource.contains("val sizeHint = bgManager.getTerminalSizeHint(quick.command)"))
        assertTrue(processSessionSource.contains("val sizeHint = bgManager.getTerminalSizeHint(command)"))
        assertTrue(chatPageSource.contains("columns = sizeHint?.columns ?: 80"))
        assertTrue(processSessionSource.contains("columns = sizeHint?.columns ?: 80"))
        assertTrue(chatPageSource.contains("rows = sizeHint?.rows ?: 24"))
        assertFalse(chatPageSource.contains("columns = 120"))
        assertFalse(chatPageSource.contains("rows = 40"))
    }

    @Test
    fun longPressTerminalButtonReusesExistingTerminalAndDoesNotToastOnSuccess() {
        assertTrue(backgroundProcessManagerSource.contains("findRunningInteractiveSession"))
        assertTrue(chatPageSource.contains("bgManager.findRunningInteractiveSession(sandboxId, quick.command)"))
        assertTrue(chatPageSource.contains("focusManager.clearFocus(force = true)"))
        assertTrue(chatPageSource.contains("softwareKeyboardController?.hide()"))
        assertTrue(chatPageSource.contains("navController.navigate(Screen.ProcessSessions(sandboxId, existing.processId))"))
        val longPressBlock = chatPageSource.substringAfter("onStartFirstTerminalQuickCommand = {").substringBefore("onOpenSandboxFileManager")
        assertFalse(longPressBlock.contains("ToastType.Success"))
        assertTrue(longPressBlock.contains("ToastType.Error"))
    }

    @Test
    fun repeatedFastFlingJumpsToTopOrBottomConservatively() {
        assertTrue(processSessionSource.contains("NestedScrollConnection"))
        assertTrue(processSessionSource.contains("TERMINAL_FAST_FLING_VELOCITY_PX"))
        assertTrue(processSessionSource.contains("TERMINAL_FAST_FLING_WINDOW_MS"))
        assertTrue(processSessionSource.contains("fastFlingCount < TERMINAL_FAST_FLING_REQUIRED_COUNT"))
        assertTrue(processSessionSource.contains("terminalPanMode || selectionMode || !rawInputMode"))
        assertTrue(processSessionSource.contains("if (!fastFlingEnabled) return Velocity.Zero"))
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(outputScroll.maxValue)"))
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(0)"))
        assertTrue(processSessionSource.contains(".nestedScroll(fastFlingConnection)"))
    }

    @Test
    fun commandHistorySurvivesLeavingTerminalPage() {
        assertTrue(backgroundProcessManagerSource.contains("rememberTerminalCommand"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalCommandHistory"))
        assertTrue(processSessionSource.contains("commandHistory.addAll(bgManager.getTerminalCommandHistory(processId))"))
        assertTrue(processSessionSource.contains("bgManager.rememberTerminalCommand(processId, trimmed)"))
    }
}
