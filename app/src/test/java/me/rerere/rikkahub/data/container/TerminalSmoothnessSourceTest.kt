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
    fun imeResizeKeepsAStableFrameUntilTheTuiRedraws() {
        assertTrue(processSessionSource.contains("WindowInsets.isImeVisible"))
        assertTrue(processSessionSource.contains("AtomicInteger(initialTerminalRows)"))
        assertTrue(processSessionSource.contains("pendingImeRowResizeJob.getAndSet(null)?.cancel()"))
        assertTrue(processSessionSource.contains("rows != measuredTerminalRows.getAndSet(rows)"))
        assertTrue(processSessionSource.contains("imeResizePending.set(true)"))
        assertTrue(processSessionSource.contains("delay(TERMINAL_IME_RESIZE_DEBOUNCE_MS)"))
        assertTrue(processSessionSource.contains("imeDrivenRowsChanged = rowsChanged && !columnsChanged && imeResizePending.get()"))
        assertTrue(processSessionSource.contains("renderPending.set(false)"))
        assertTrue(processSessionSource.contains("TERMINAL_RESIZE_RENDER_FALLBACK_MS"))
        assertTrue(processSessionSource.contains("resizeRenderFallbackJob.getAndSet(null)?.cancel()"))
        assertTrue(processSessionSource.contains("val renderJob = remember(processId) { AtomicReference<Job?>(null) }"))
        assertTrue(processSessionSource.contains("snapshotFlow { Triple(outputScroll.maxValue, terminalRenderedRows.size, autoScroll) }"))
        assertFalse(processSessionSource.contains("LaunchedEffect(imeVisible, terminalRows, terminalRenderedRows.size, outputScroll.maxValue"))
        assertFalse(processSessionSource.contains("LaunchedEffect(processId, terminalRenderedRows.size, outputScroll.maxValue"))
        assertTrue(processSessionSource.contains("terminalEmulator.resize(terminalColumns, terminalRows)"))
        assertTrue(processSessionSource.contains("imeStableForSizeHint"))
        assertTrue(processSessionSource.contains("bgManager.resizeInteractiveSession(processId, terminalColumns, terminalRows)"))
        assertTrue(processSessionSource.contains("val measuredCell = remember(terminalTextStyle, density)"))
        assertTrue(processSessionSource.contains("val imeInsets = WindowInsets.ime"))
        assertTrue(processSessionSource.contains("effectiveImeHeightPx"))
        assertTrue(processSessionSource.contains("shouldAvoidTerminalIme"))
        assertTrue(processSessionSource.contains("TerminalImeViewportAnchor"))
        assertTrue(processSessionSource.contains("terminalImeAnchorScrollTarget"))
        assertTrue(processSessionSource.contains("lastNonBlankRow = terminalContentBounds.lastNonBlankRow"))
        assertTrue(processSessionSource.contains("viewportHeightPx = outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("scrollTerminalContentBottomToIme()"))
        assertTrue(processSessionSource.contains("lastContentBottomPx - viewportHeightPx"))
        assertTrue(processSessionSource.contains("maxValue includes blank terminal-grid rows"))
        assertTrue(processSessionSource.contains("Input and extra-key bars\n    // are siblings of the output Box"))
        assertTrue(processSessionSource.contains("shouldFollowTerminalBottom()"))
        assertTrue(processSessionSource.contains("imeViewportAnchor.getAndSet(null)?.let { anchor ->"))
        assertFalse(processSessionSource.contains("var terminalCellWidthPx by remember"))
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
        assertTrue(processSessionSource.contains("fastFlingCount < fastFlingRequiredCount"))
        assertTrue(processSessionSource.contains("fastFlingRequiredCount: Int = 2"))
        assertTrue(processSessionSource.contains("TerminalNumberSettingDialog"))
        assertTrue(processSessionSource.contains("\"HIST\""))
        assertTrue(processSessionSource.contains("\"JUMP\""))
        assertTrue(processSessionSource.contains(".verticalScroll(outputScroll, enabled = terminalPanMode || selectionMode)"))
        assertTrue(processSessionSource.contains("if (!fastFlingEnabled) return Velocity.Zero"))
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(outputScroll.maxValue)"))
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(0)"))
        assertTrue(processSessionSource.contains(".nestedScroll(fastFlingConnection)"))
    }

    @Test
    fun outputPathRemainsImmediateAndUnbatched() {
        assertTrue(processSessionSource.contains("terminalEmulator.feed(bytes)"))
        assertTrue(processSessionSource.contains("scheduleTerminalRender()"))
        assertFalse(processSessionSource.contains("TERMINAL_OUTPUT_BATCH_WINDOW_MS"))
        assertFalse(processSessionSource.contains("delay(8L)"))
    }

    @Test
    fun imeAvoidanceCoversNewScrollRangeAndSupportsPerCommandCalibration() {
        assertTrue(processSessionSource.contains("val customImeHeightDp: Int? = null"))
        assertTrue(processSessionSource.contains("TERMINAL_IME_HEIGHT_MIN_DP = 80"))
        assertTrue(processSessionSource.contains("TERMINAL_IME_HEIGHT_MAX_DP = 800"))
        assertTrue(processSessionSource.contains("customImeHeightDp = customImeHeightDp"))
        assertTrue(processSessionSource.contains("\"IME\" -> TerminalStatusKey"))
        assertTrue(processSessionSource.contains("TerminalImeHeightSettingDialog"))
        assertTrue(processSessionSource.contains("terminalContentHeightPx > fullOutputViewportHeightPx - effectiveImeHeightPx"))
        assertTrue(processSessionSource.contains("if (!currentImeVisible || fullOutputViewportHeightPx.get() == 0)"))
        assertTrue(processSessionSource.contains("if (imeVisible && autoScroll && shouldAvoidIme)"))
        assertTrue(processSessionSource.contains("terminalTailPaddingPx"))
        assertFalse(processSessionSource.contains("contentRows * terminalCellHeightPx + terminalBottomRevealPadding"))
    }

    @Test
    fun terminalScrollbackAndFastFlingPreferencesAreBoundedAndPersisted() {
        assertTrue(processSessionSource.contains("val maxScrollbackLines: Int = TerminalEmulator.DEFAULT_MAX_SCROLLBACK_LINES"))
        assertTrue(processSessionSource.contains("val fastFlingRequiredCount: Int = 2"))
        assertTrue(processSessionSource.contains("terminalEmulator.setMaxScrollbackLines(maxScrollbackLines)"))
        assertTrue(processSessionSource.contains("maxScrollbackLines = maxScrollbackLines"))
        assertTrue(processSessionSource.contains("fastFlingRequiredCount = fastFlingRequiredCount"))
    }

    @Test
    fun mouseModeOwnsTouchDragAndSerializesPtyInput() {
        assertTrue(processSessionSource.contains("AtomicReference<MouseButton?>(null)"))
        assertTrue(processSessionSource.contains("activeButton != null || event.buttonState != 0"))
        assertTrue(processSessionSource.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(processSessionSource.contains("activeTerminalMouseButton.set(button)"))
        assertTrue(processSessionSource.contains("activeTerminalMouseButton.set(null)"))
        assertTrue(processSessionSource.contains("sequence != null || hadActivePress"))
        assertTrue(processSessionSource.contains(".verticalScroll(outputScroll, enabled = terminalPanMode || selectionMode)"))
        assertFalse(processSessionSource.contains("enabled = terminalPanMode || selectionMode || !rawInputMode"))
        assertTrue(processSessionSource.contains("Channel<String>(Channel.UNLIMITED)"))
        assertTrue(processSessionSource.contains("rawInputChannel.trySend(sequence)"))
        assertTrue(backgroundProcessManagerSource.contains("val inputMutex: Mutex = Mutex()"))
        assertTrue(backgroundProcessManagerSource.contains("record.inputMutex.withLock"))
    }

    @Test
    fun commandHistorySurvivesLeavingTerminalPage() {
        assertTrue(backgroundProcessManagerSource.contains("rememberTerminalCommand"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalCommandHistory"))
        assertTrue(processSessionSource.contains("addAll(bgManager.getTerminalCommandHistory(processId))"))
        assertTrue(processSessionSource.contains("bgManager.rememberTerminalCommand(processId, trimmed)"))
    }
}
