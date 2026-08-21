package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.ui.pages.container.terminalEffectiveScreenBottomRow
import me.rerere.rikkahub.ui.pages.container.terminalImeAnchorScrollTarget
import me.rerere.rikkahub.ui.pages.container.terminalTuiViewportScrollTarget
import me.rerere.rikkahub.ui.pages.container.terminalWasFollowingBeforeIme
import org.junit.Assert.assertEquals
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
    fun imeUsesAVisualViewportWithoutResizingThePty() {
        assertTrue(processSessionSource.contains("WindowInsets.isImeVisible"))
        assertTrue(processSessionSource.contains("AtomicInteger(initialTerminalRows)"))
        assertTrue(processSessionSource.contains("val viewportRowResizeJob = remember(processId) { AtomicReference<Job?>(null) }"))
        assertTrue(processSessionSource.contains("val firstMeasurement = !outputViewportMeasured.getAndSet(true)"))
        assertTrue(processSessionSource.contains("delay(TERMINAL_PTY_RESIZE_DEBOUNCE_MS)"))
        assertTrue(processSessionSource.contains("if (currentFullscreen && !transitionStillActive && stableRows != terminalRows)"))
        assertTrue(processSessionSource.contains("The IME is a visual viewport overlay, not a terminal resize"))
        assertTrue(processSessionSource.contains("currentActualImeHeightPx > 0"))
        assertTrue(processSessionSource.contains("imeViewportAnchor.get() != null"))
        assertFalse(processSessionSource.contains("pendingImeRowResizeJob"))
        assertFalse(processSessionSource.contains("imeResizePending"))
        assertFalse(processSessionSource.contains("imeDrivenRowsChanged"))
        assertTrue(processSessionSource.contains("renderPending.set(false)"))
        assertTrue(processSessionSource.contains("TERMINAL_RESIZE_RENDER_FALLBACK_MS"))
        assertTrue(processSessionSource.contains("resizeRenderFallbackJob.getAndSet(null)?.cancel()"))
        assertTrue(processSessionSource.contains("val renderJob = remember(processId) { AtomicReference<Job?>(null) }"))
        assertTrue(processSessionSource.contains("TerminalScrollSnapshot("))
        assertFalse(processSessionSource.contains("LaunchedEffect(imeVisible, terminalRows, terminalRenderedRows.size, outputScroll.maxValue"))
        assertFalse(processSessionSource.contains("LaunchedEffect(processId, terminalRenderedRows.size, outputScroll.maxValue"))
        assertTrue(processSessionSource.contains("preserveBottomRows = currentUsesTuiViewport"))
        assertTrue(processSessionSource.contains("imeStableForSizeHint"))
        assertTrue(processSessionSource.contains("bgManager.resizeInteractiveSession("))
        assertTrue(processSessionSource.contains("val measuredCell = remember(terminalTextStyle, density)"))
        assertTrue(processSessionSource.contains("val imeInsets = WindowInsets.ime"))
        assertTrue(processSessionSource.contains("effectiveImeHeightPx"))
        assertTrue(processSessionSource.contains("shouldAvoidTerminalIme"))
        assertTrue(processSessionSource.contains("TerminalImeViewportAnchor"))
        assertTrue(processSessionSource.contains("terminalImeAnchorScrollTarget"))
        assertTrue(processSessionSource.contains("val currentLastNonBlankRow by rememberUpdatedState(terminalContentBounds.lastNonBlankRow)"))
        assertTrue(processSessionSource.contains("val currentActiveScreenBottomRow by rememberUpdatedState(terminalActiveScreenBottomRow)"))
        assertTrue(processSessionSource.contains("viewportHeightPx = outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("scrollTerminalContentBottomToIme()"))
        assertTrue(processSessionSource.contains("lastContentBottomPx - viewportHeightPx"))
        assertTrue(processSessionSource.contains("isConfiguredTerminalCommand("))
        assertTrue(processSessionSource.contains("settings.terminalFullGridCommands"))
        assertTrue(processSessionSource.contains("commandIsTui || terminalFrameIsAlternateScreen || shouldPreserveFullTerminalGrid"))
        assertTrue(processSessionSource.contains("terminalTuiViewportScrollTarget("))
        assertTrue(processSessionSource.contains("Only the rendered terminal tail belongs to the scroll content. Input and extra-key bars"))
        assertTrue(processSessionSource.contains("shouldFollowTerminalBottom()"))
        assertTrue(processSessionSource.contains("Auto-follow is already updated from the live viewport on every layout"))
        assertFalse(processSessionSource.contains("var terminalCellWidthPx by remember"))
    }

    @Test
    fun listAndFullscreenReuseOneTerminalAndListPreviewNeverResizesThePty() {
        assertTrue(processSessionSource.contains("movableContentOf { placement: TerminalPanelPlacement"))
        assertTrue(processSessionSource.contains("val terminalPanelContent = remember(activeInteractiveProcess?.processId)"))
        assertTrue(processSessionSource.contains("if (currentFullscreen) {"))
        assertTrue(processSessionSource.contains("LIST is a clipped preview of the live full-size grid"))
        assertTrue(processSessionSource.contains("if (!currentFullscreen) return"))
    }

    @Test
    fun gridRenderingReusesRowsAndProtectsTuiBottomChrome() {
        assertTrue(processSessionSource.contains("class TerminalRenderedRowState"))
        assertTrue(processSessionSource.contains("mutableStateListOf<TerminalRenderedRowState>()"))
        assertTrue(processSessionSource.contains("Snapshot.withMutableSnapshot"))
        assertTrue(processSessionSource.contains("if (rowState.text != next) rowState.text = next"))
        assertTrue(processSessionSource.contains("TERMINAL_GRID_STABLE_BOTTOM_ROWS"))
        assertTrue(processSessionSource.contains("TERMINAL_GRID_CLEAR_GRACE_MS"))
        assertTrue(processSessionSource.contains("holdCompleteFrameForGrid"))
        assertTrue(processSessionSource.contains("resizeAwaitingTuiRedraw.set(true)"))
        assertTrue(processSessionSource.contains("val frame = terminalEmulator.renderFrame()"))
        assertTrue(processSessionSource.contains("if (!forcePendingGridBlanks && frame.revision == terminalFrameRevision) return"))
        assertTrue(processSessionSource.contains("val rendered = frame.rows"))
        assertTrue(processSessionSource.contains("val bounds = frame.contentBounds"))
        assertTrue(processSessionSource.contains("terminalModeSummary = frame.modeSummary"))
        assertTrue(processSessionSource.contains("terminalScreenStartRow = frame.screenStartRow"))
        assertFalse(processSessionSource.contains("val rendered = terminalEmulator.renderRows()"))
        assertFalse(processSessionSource.contains("val bounds = terminalEmulator.contentBounds(includeScrollback = true)"))
        assertFalse(processSessionSource.contains("terminalRenderedRows = terminalEmulator.renderRows()"))
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
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(terminalViewportBottomScrollTarget())"))
        assertTrue(processSessionSource.contains("outputScroll.animateScrollTo(0)"))
        assertTrue(processSessionSource.contains(".nestedScroll(fastFlingConnection)"))
    }

    @Test
    fun outputPathRemainsImmediateAndUnbatched() {
        assertTrue(backgroundProcessManagerSource.contains("record.terminalEmulator.feed(data)"))
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
        assertTrue(processSessionSource.contains("terminalContentHeightPx > outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("outputViewportHeightPx = outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("terminalWasFollowingBeforeIme("))
        assertTrue(processSessionSource.contains("recordImeTransition(currentImeVisible, currentViewportHeightPx = viewportHeightPx)"))
        assertTrue(processSessionSource.contains("currentViewportHeightPx = snapshot.viewportHeightPx"))
        assertTrue(processSessionSource.contains("anchor.copy(followBottom = true)"))
        assertTrue(processSessionSource.contains("(!imeTransitionActive || fullOutputViewportHeightPx.get() == 0)"))
        assertTrue(processSessionSource.contains("shouldAvoidIme || customImeRequiresExtraAvoidance"))
        assertTrue(processSessionSource.contains("val transitionStillActive = currentImeVisible || currentActualImeHeightPx > 0"))
        assertTrue(processSessionSource.contains("val target = terminalViewportBottomScrollTarget()"))
        assertTrue(processSessionSource.contains("outputScroll.value >= target - thresholdPx"))
        assertFalse(processSessionSource.contains("contentRows * terminalCellHeightPx + terminalBottomRevealPadding"))
    }

    @Test
    fun tuiViewportUsesPhysicalScreenRowsInsteadOfTheApplicationStatusBar() {
        assertTrue(processSessionSource.contains("process.ptyMode.equals(\"raw\", ignoreCase = true)"))
        assertTrue(processSessionSource.contains("if (snapshot.usesTuiViewport)"))
        assertTrue(processSessionSource.contains("if (!snapshot.autoScroll)"))
        assertTrue(processSessionSource.contains("modifier = Modifier.zIndex(1f)"))
        assertTrue(processSessionSource.contains("Spacer(modifier = Modifier.height(terminalVisualTopPadding))"))
        assertFalse(processSessionSource.contains("LaunchedEffect(showStatusBar)"))
        assertEquals(0, terminalTuiViewportScrollTarget(
            screenStartRow = 0,
            lastActiveScreenRow = 2,
            terminalCellHeightPx = 20,
            viewportHeightPx = 200,
            terminalTailPaddingPx = 8,
            maxScrollPx = 400,
        ))
        assertEquals(388, terminalTuiViewportScrollTarget(
            screenStartRow = 10,
            lastActiveScreenRow = 12,
            terminalCellHeightPx = 20,
            viewportHeightPx = 80,
            terminalTailPaddingPx = 8,
            maxScrollPx = 400,
        ))
        assertEquals(5, terminalEffectiveScreenBottomRow(
            screenContentBounds = me.rerere.rikkahub.utils.TerminalEmulator.ContentBounds(0, 3, 2),
            cursorRow = 5,
            cursorVisible = true,
        ))
        assertEquals(3, terminalEffectiveScreenBottomRow(
            screenContentBounds = me.rerere.rikkahub.utils.TerminalEmulator.ContentBounds(0, 3, 2),
            cursorRow = 5,
            cursorVisible = false,
        ))
    }

    @Test
    fun imeFollowStateUsesThePreImeViewportAndSemanticContentBottom() {
        assertTrue(terminalWasFollowingBeforeIme(
            scrollValuePx = 0,
            currentBottomTargetPx = 400,
            fullViewportHeightPx = 1000,
            currentViewportHeightPx = 600,
            thresholdPx = 20,
        ))
        assertTrue(terminalWasFollowingBeforeIme(
            scrollValuePx = 300,
            currentBottomTargetPx = 700,
            fullViewportHeightPx = 1000,
            currentViewportHeightPx = 600,
            thresholdPx = 20,
        ))
        assertFalse(terminalWasFollowingBeforeIme(
            scrollValuePx = 100,
            currentBottomTargetPx = 900,
            fullViewportHeightPx = 1000,
            currentViewportHeightPx = 600,
            thresholdPx = 20,
        ))
        assertEquals(108, terminalImeAnchorScrollTarget(
            lastNonBlankRow = 34,
            terminalCellHeightPx = 20,
            viewportHeightPx = 600,
            terminalTailPaddingPx = 8,
            maxScrollPx = 400,
        ))
    }

    @Test
    fun fullGridAlignmentIsExposedAsAUserManagedIndependentList() {
        assertTrue(processSessionSource.contains("\"GRID\" -> TerminalStatusKey"))
        assertTrue(processSessionSource.contains("TerminalGridProgramsDialog"))
        assertTrue(processSessionSource.contains("terminalFullGridCommands = value"))
        assertTrue(processSessionSource.contains("TerminalActionPreset(\"GRID\", \"GRID/TAIL\")"))
        assertFalse(processSessionSource.contains("terminalEmulator.isAlternateScreen ||"))
        assertFalse(processSessionSource.contains("settings.terminalCustomTuiCommands,\n    )\n    val currentLastNonBlankRow"))
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
    fun terminalStateAndCommandHistorySurviveLeavingTerminalPage() {
        assertTrue(backgroundProcessManagerSource.contains("val terminalEmulator: TerminalEmulator"))
        assertTrue(backgroundProcessManagerSource.contains("getInteractiveTerminalEmulator"))
        assertTrue(backgroundProcessManagerSource.contains("record.terminalEmulator.feed(data)"))
        assertTrue(backgroundProcessManagerSource.contains("handleTerminalProtocolEvents(record)"))
        assertTrue(backgroundProcessManagerSource.contains("preserveBottomRows = preserveBottomRows"))
        assertTrue(backgroundProcessManagerSource.contains("MutableSharedFlow<ByteArray>(replay = 1"))
        assertTrue(processSessionSource.contains("bgManager.getInteractiveTerminalEmulator(processId)"))
        assertFalse(processSessionSource.contains("bgManager.readInteractiveBuffer(processId)"))
        assertTrue(processSessionSource.contains("if (restored.autoScroll) Int.MAX_VALUE else restored.verticalOffsetPx"))
        assertTrue(processSessionSource.contains("if (restored == null || restored.autoScroll)"))
        assertTrue(processSessionSource.contains("val viewportInitialized = remember(processId) { AtomicBoolean(false) }"))
        assertTrue(processSessionSource.contains("withFrameNanos { }\n        withFrameNanos { }"))
        assertTrue(backgroundProcessManagerSource.contains("rememberTerminalCommand"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalCommandHistory"))
        assertTrue(processSessionSource.contains("addAll(bgManager.getTerminalCommandHistory(processId))"))
        assertTrue(processSessionSource.contains("bgManager.rememberTerminalCommand(processId, trimmed)"))
    }
}
