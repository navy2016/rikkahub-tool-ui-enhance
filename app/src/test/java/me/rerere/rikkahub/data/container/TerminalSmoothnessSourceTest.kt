package me.rerere.rikkahub.data.container

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
    private val renderedRowsSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt"),
    ).first { it.isFile }.readText()
    private val chatPageSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
    ).first { it.isFile }.readText()
    private val backgroundProcessManagerSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt")
    ).first { it.isFile }.readText()

    private val controllerSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalViewportController.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/TerminalViewportController.kt"),
    ).first { it.isFile }.readText()
    private val geometrySource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalViewportGeometry.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/TerminalViewportGeometry.kt"),
    ).first { it.isFile }.readText()

    private val gestureSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportGestures.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportGestures.kt"),
    ).first { it.isFile }.readText()
    private val scrollEffectsSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportScrollEffects.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportScrollEffects.kt"),
    ).first { it.isFile }.readText()
    private val fastFlingSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalFastFlingTracker.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/TerminalFastFlingTracker.kt"),
    ).first { it.isFile }.readText()
    private val itemGeometrySource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalViewportItemGeometry.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/TerminalViewportItemGeometry.kt"),
    ).first { it.isFile }.readText()
    private val viewportStateSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalViewportState.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/TerminalViewportState.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun terminalViewportAndActiveSessionAreRememberedInMemory() {
        assertTrue(viewportStateSource.contains("data class TerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("saveTerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalViewportState"))
        assertTrue(backgroundProcessManagerSource.contains("data class TerminalSandboxUiState"))
        assertTrue(backgroundProcessManagerSource.contains("saveTerminalSandboxUiState"))
        assertTrue(processSessionSource.contains("bgManager.getTerminalViewportState(processId)"))
        assertTrue(processSessionSource.contains("saveTerminalViewport()"))
        assertTrue(viewportStateSource.contains("val viewportMode: ViewportMode"))
        assertTrue(viewportStateSource.contains("val anchorLineId: Long?"))
        assertTrue(viewportStateSource.contains("anchorCellHeightPx"))
        assertTrue(backgroundProcessManagerSource.contains("anchorCellHeightPx"))
        assertTrue(viewportStateSource.contains("anchorHistoryGeneration"))
        assertTrue(backgroundProcessManagerSource.contains("anchorHistoryGeneration"))
        assertTrue(processSessionSource.contains("anchorLineId = state.anchor?.lineId"))
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
        assertTrue(processSessionSource.contains("lastImeTransitionAt.get() < TERMINAL_IME_RESIZE_DEBOUNCE_MS"))
        assertFalse(processSessionSource.contains("pendingImeRowResizeJob"))
        assertFalse(processSessionSource.contains("imeResizePending"))
        assertFalse(processSessionSource.contains("imeDrivenRowsChanged"))
        assertTrue(processSessionSource.contains("renderPending.set(false)"))
        assertTrue(processSessionSource.contains("TERMINAL_RESIZE_RENDER_FALLBACK_MS"))
        assertTrue(processSessionSource.contains("resizeRenderFallbackJob.getAndSet(null)?.cancel()"))
        assertTrue(processSessionSource.contains("val renderJob = remember(processId) { AtomicReference<Job?>(null) }"))
        assertTrue(processSessionSource.contains("TerminalViewportRenderSnapshot("))
        assertFalse(processSessionSource.contains("LaunchedEffect(imeVisible, terminalRows, terminalRenderedRows.size, outputScroll.maxValue"))
        assertFalse(processSessionSource.contains("LaunchedEffect(processId, terminalRenderedRows.size, outputScroll.maxValue"))
        assertTrue(processSessionSource.contains("preserveBottomRows = currentUsesTuiViewport"))
        assertTrue(processSessionSource.contains("imeStableForSizeHint"))
        assertTrue(processSessionSource.contains("bgManager.resizeInteractiveSession("))
        assertTrue(processSessionSource.contains("val measuredCell = remember(terminalTextStyle, density)"))
        assertTrue(processSessionSource.contains("val imeInsets = WindowInsets.ime"))
        assertTrue(processSessionSource.contains("effectiveImeHeightPx"))
        assertTrue(processSessionSource.contains("shouldAvoidTerminalIme"))
        assertTrue(processSessionSource.contains("TerminalViewportController(restoredViewportState"))
        assertTrue(controllerSource.contains("terminalImeAnchorScrollTarget"))
        assertTrue(processSessionSource.contains("val currentLastNonBlankRow by rememberUpdatedState(terminalContentBounds.lastNonBlankRow)"))
        assertTrue(itemGeometrySource.contains("captureMeasuredViewportAnchor"))
        assertTrue(itemGeometrySource.contains("resolveMeasuredViewportAnchor"))
        assertTrue(itemGeometrySource.contains("lineId: Long"))
        assertFalse(itemGeometrySource.contains("rowCount * cellHeight"))
        assertTrue(processSessionSource.contains("val currentActiveScreenBottomRow by rememberUpdatedState(terminalActiveScreenBottomRow)"))
        assertTrue(processSessionSource.contains("viewportHeightPx = outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("viewportController.updateViewport("))
        assertTrue(geometrySource.contains("lastContentBottomPx - viewportHeightPx"))
        assertTrue(processSessionSource.contains("isConfiguredTerminalCommand("))
        assertTrue(processSessionSource.contains("settings.terminalFullGridCommands"))
        assertTrue(processSessionSource.contains("commandIsTui || terminalFrameIsAlternateScreen || shouldPreserveFullTerminalGrid"))
        assertTrue(controllerSource.contains("terminalTuiViewportScrollTarget("))
        assertTrue(processSessionSource.contains("Only the rendered terminal tail belongs to the scroll content. Input and extra-key bars"))
        assertTrue(processSessionSource.contains("val autoScroll = viewportState.autoScroll"))
        assertTrue(processSessionSource.contains("The sole vertical ScrollState writer"))
        assertFalse(processSessionSource.contains("var terminalCellWidthPx by remember"))
    }

    @Test
    fun listAndFullscreenReuseOneTerminalAndListPreviewNeverResizesThePty() {
        assertTrue(processSessionSource.contains("movableContentOf { placement: TerminalPanelPlacement"))
        assertTrue(processSessionSource.contains("val terminalPanelContent = remember(activeInteractiveProcess?.processId)"))
        assertTrue(processSessionSource.contains("if (currentFullscreen) {"))
        assertTrue(processSessionSource.contains("LIST is a clipped preview of the live full-size grid"))
        assertTrue(processSessionSource.contains("if (!currentFullscreen || !state.initialized) return"))
    }

    @Test
    fun renderedRowsFollowStableIdsWhenScrollbackIsTrimmed() {
        assertTrue(processSessionSource.contains("createTerminalRenderedRows(initialTerminalRenderFrame)"))
        assertTrue(processSessionSource.contains("synchronizeTerminalRenderedRows("))
        assertTrue(processSessionSource.contains("TerminalRenderedRows(terminalRenderedRows, terminalTextStyle)"))
        assertTrue(renderedRowsSource.contains("val lineId: Long"))
        assertTrue(renderedRowsSource.contains("buildLineIdsFromFrame(frame, rendered.size)"))
        assertTrue(renderedRowsSource.contains("existing[nextLineIds[index]]"))
        assertTrue(renderedRowsSource.contains("terminalRenderedRows.indices.any"))
        assertTrue(renderedRowsSource.contains("key(row.lineId)"))
        assertFalse(renderedRowsSource.contains("TerminalRenderedRowState(it.text)"))
    }

    @Test
    fun gridRenderingReusesRowsAndProtectsTuiBottomChrome() {
        assertTrue(renderedRowsSource.contains("class TerminalRenderedRowState"))
        assertTrue(renderedRowsSource.contains("mutableStateListOf<TerminalRenderedRowState>()"))
        assertTrue(processSessionSource.contains("Snapshot.withMutableSnapshot"))
        assertTrue(renderedRowsSource.contains("if (rowState.text != next) rowState.text = next"))
        assertTrue(renderedRowsSource.contains("TERMINAL_GRID_STABLE_BOTTOM_ROWS"))
        assertTrue(processSessionSource.contains("TERMINAL_GRID_CLEAR_GRACE_MS"))
        assertTrue(processSessionSource.contains("holdCompleteFrameForGrid"))
        assertTrue(processSessionSource.contains("resizeAwaitingTuiRedraw.set(true)"))
        assertTrue(processSessionSource.contains("val frame = terminalEmulator.renderFrame()"))
        assertTrue(processSessionSource.contains("if (!forcePendingGridBlanks && frame.revision == terminalFrameRevision) return"))
        assertTrue(renderedRowsSource.contains("val rendered = frame.rows"))
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
        assertTrue(processSessionSource.contains("val viewportGestures = rememberTerminalViewportGestures("))
        assertTrue(processSessionSource.contains("TerminalViewportGestureConfig(terminalPanMode, selectionMode, fastFlingRequiredCount)"))
        assertTrue(processSessionSource.contains("currentScrollPx = { outputScroll.value }"))
        assertTrue(processSessionSource.contains("interactionSource = outputScroll.interactionSource"))
        assertTrue(processSessionSource.contains("isScrollInProgress = { outputScroll.isScrollInProgress }"))
        assertTrue(processSessionSource.contains("val viewportFlingBehavior = viewportGestures.flingBehavior"))
        assertTrue(processSessionSource.contains("val fastFlingConnection = viewportGestures.connection"))
        assertTrue(gestureSource.contains("NestedScrollConnection"))
        assertTrue(fastFlingSource.contains("TERMINAL_FAST_FLING_VELOCITY_PX = 3500f"))
        assertTrue(fastFlingSource.contains("TERMINAL_FAST_FLING_WINDOW_MS = 700L"))
        assertTrue(fastFlingSource.contains("if (count < requiredCount) return null"))
        assertTrue(processSessionSource.contains("fastFlingRequiredCount: Int = 2"))
        assertTrue(processSessionSource.contains("TerminalNumberSettingDialog"))
        assertTrue(processSessionSource.contains("viewportGestures.resetFastFling()"))
        assertTrue(processSessionSource.contains("\"HIST\""))
        assertTrue(processSessionSource.contains("\"JUMP\""))
        assertTrue(processSessionSource.contains("enabled = terminalPanMode || selectionMode,"))
        assertTrue(gestureSource.contains("panEnabled && !selectionMode"))
        assertTrue(gestureSource.contains("controller.jumpToBottom(currentPx)"))
        assertTrue(gestureSource.contains("controller.jumpToTop(currentPx)"))
        assertTrue(gestureSource.contains("if (initialVelocity == 0f) return 0f"))
        assertTrue(processSessionSource.contains(".nestedScroll(fastFlingConnection)"))
        assertTrue(processSessionSource.contains("flingBehavior = viewportFlingBehavior,"))
    }

    @Test
    fun controllerIsTheOnlyVerticalScrollOwnerAndCapturesConsumedUserDeltas() {
        assertEquals(1, Regex("runTerminalViewportScrollEffects").findAll(processSessionSource).count())
        assertTrue(scrollEffectsSource.contains("controller.state.map { it.scrollEffect }"))
        assertTrue(scrollEffectsSource.contains("controller.isCurrent(effect)"))
        assertTrue(scrollEffectsSource.contains("controller.scrollFinished(effect.id"))
        assertTrue(scrollEffectsSource.contains("TERMINAL_SCROLL_HANDOFF_MAX_FRAMES"))
        assertTrue(scrollEffectsSource.contains("handoffFrames < TERMINAL_SCROLL_HANDOFF_MAX_FRAMES"))
        assertFalse(scrollEffectsSource.contains("snapshotFlow { isScrollInProgress() }.first { !it }"))
        assertTrue(processSessionSource.contains("maxScrollPx = { outputScroll.maxValue }"))
        assertTrue(gestureSource.contains("override fun onPostScroll("))
        assertTrue(gestureSource.contains("consumed.y != 0f && userDelta"))
        assertTrue(gestureSource.contains("source == NestedScrollSource.UserInput"))
        assertTrue(gestureSource.contains("controller.userScrolled(gesture.id"))
        assertTrue(gestureSource.contains("finally {\n            controller.endUserScroll(token)"))
        assertTrue(gestureSource.contains("collectIsDraggedAsState()"))
        assertTrue(gestureSource.contains("rememberUpdatedState(currentScrollPx)"))
        assertEquals(1, Regex("""outputScroll\.scrollTo\(""").findAll(processSessionSource).count())
        assertEquals(1, Regex("""outputScroll\.animateScrollTo\(""").findAll(processSessionSource).count())
        assertFalse(Regex("""\.(scrollTo|animateScrollTo|scrollToItem|animateScrollToItem)\(""")
            .containsMatchIn(gestureSource))
        assertFalse(processSessionSource.contains("TerminalScrollOperation"))
        assertFalse(processSessionSource.contains("TerminalUserScrollSnapshot"))
        assertFalse(processSessionSource.contains("imeViewportRestoreJob"))
        assertFalse(processSessionSource.contains("var autoScroll by"))
    }

    @Test
    fun measuredLazyTargetsAreRevisionAndGenerationBound() {
        assertTrue(controllerSource.contains("setMeasuredAnchorTarget("))
        assertTrue(controllerSource.contains("frameRevision: Long"))
        assertTrue(controllerSource.contains("measured.frameRevision == frame.revision"))
        assertTrue(controllerSource.contains("measured.anchor.clippedTopPx == anchor?.clippedTopPx"))
        assertTrue(controllerSource.contains("measured.anchor.historyGeneration == anchor?.historyGeneration"))
        assertTrue(processSessionSource.contains("TerminalRenderedRows(terminalRenderedRows, terminalTextStyle)"))
        assertFalse(processSessionSource.contains("LazyColumn("))
    }

    @Test
    fun lazyMeasurementDoesNotInventAnUnknownScrollRange() {
        val measurementSource = listOf(
            File("app/src/main/java/me/rerere/rikkahub/data/container/TerminalLazyViewportMeasurement.kt"),
            File("src/main/java/me/rerere/rikkahub/data/container/TerminalLazyViewportMeasurement.kt"),
        ).first { it.isFile }.readText()
        assertTrue(measurementSource.contains("val maxScrollPx: Int?"))
        assertTrue(measurementSource.contains("tailItem"))
        assertTrue(measurementSource.contains("tailBottomPx - viewportHeightPx"))
        assertTrue(measurementSource.contains("generationChanged"))
        assertTrue(measurementSource.contains("screenRowsComplete"))
        assertTrue(measurementSource.contains("setExpectedScrollPx"))
        assertFalse(measurementSource.contains("historyLineIds.size *"))
        assertFalse(measurementSource.contains("cellHeightPx"))
    }

    @Test
    fun unverifiedLazyGeometryCannotAutoEnableInTheProductionTerminal() {
        // The isolated gesture fixture is not permission to enable a production lazy viewport.
        assertFalse(processSessionSource.contains("LazyColumn("))
        assertFalse(processSessionSource.contains("rememberLazyListState("))
        assertFalse(processSessionSource.contains("TERMINAL_LAZY_HISTORY_MIN_ROWS"))
        assertTrue(processSessionSource.contains("TerminalRenderedRows(terminalRenderedRows, terminalTextStyle)"))
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
        assertFalse(processSessionSource.contains("terminalWasFollowingBeforeIme("))
        assertTrue(processSessionSource.contains("recordImeTransition(currentImeVisible)"))
        assertTrue(processSessionSource.contains("viewportHeightPx = outputViewportHeightPx"))
        assertTrue(processSessionSource.contains("val autoScroll = viewportState.autoScroll"))
        assertTrue(processSessionSource.contains("(!imeTransitionActive || fullOutputViewportHeightPx.get() == 0)"))
        assertTrue(processSessionSource.contains("shouldAvoidIme || customImeRequiresExtraAvoidance"))
        assertTrue(processSessionSource.contains("val transitionStillActive = currentImeVisible || currentActualImeHeightPx > 0"))
        assertTrue(processSessionSource.contains("viewportController.isNearBottom(outputScroll.value)"))
        assertTrue(processSessionSource.contains("atBottom = viewportController.isNearBottom(outputScroll.value)"))
        assertFalse(processSessionSource.contains("contentRows * terminalCellHeightPx + terminalBottomRevealPadding"))
    }

    @Test
    fun tuiViewportUsesPhysicalScreenRowsInsteadOfTheApplicationStatusBar() {
        assertTrue(processSessionSource.contains("process.ptyMode.equals(\"raw\", ignoreCase = true)"))
        assertTrue(processSessionSource.contains("fun viewportMetrics() = TerminalViewportMetrics("))
        assertTrue(controllerSource.contains("else ViewportMode.LOCKED"))
        assertTrue(processSessionSource.contains("viewportController.setFollow(enabled, outputScroll.value)"))
        assertFalse(processSessionSource.contains("pendingTuiCompensationPx"))
        assertFalse(processSessionSource.contains("tuiScreenStartRowAnchor"))
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
    fun imeTargetUsesSemanticContentBottom() {
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
        assertTrue(processSessionSource.contains("enabled = terminalPanMode || selectionMode,"))
        assertFalse(processSessionSource.contains("enabled = terminalPanMode || selectionMode || !rawInputMode"))
        assertTrue(processSessionSource.contains("Channel<String>(Channel.UNLIMITED)"))
        assertTrue(processSessionSource.contains("rawInputChannel.trySend(sequence)"))
        assertTrue(backgroundProcessManagerSource.contains("val inputMutex: Mutex = Mutex()"))
        assertTrue(backgroundProcessManagerSource.contains("record.inputMutex.withLock"))
    }

    @Test
    fun semanticViewportReducerOwnsTerminalScrollReconciliation() {
        assertTrue(processSessionSource.contains("terminalViewportFrame = frame"))
        assertTrue(controllerSource.contains("ViewportInput("))
        assertTrue(controllerSource.contains("reduceViewport("))
        assertTrue(controllerSource.contains("captureViewportAnchor("))
        assertTrue(controllerSource.contains("ViewportMode.SCREEN"))
        assertTrue(controllerSource.contains("ViewportMode.LOCKED"))
        assertFalse(processSessionSource.contains("pendingTuiCompensationPx"))
        assertFalse(processSessionSource.contains("tuiScreenStartRowAnchor"))
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
        assertTrue(processSessionSource.contains("TerminalViewportController(restoredViewportState"))
        assertTrue(processSessionSource.contains("if (!currentFullscreen || !state.initialized) return"))
        assertTrue(processSessionSource.contains("withFrameNanos { }"))
        assertTrue(backgroundProcessManagerSource.contains("rememberTerminalCommand"))
        assertTrue(backgroundProcessManagerSource.contains("getTerminalCommandHistory"))
        assertTrue(processSessionSource.contains("addAll(bgManager.getTerminalCommandHistory(processId))"))
        assertTrue(processSessionSource.contains("bgManager.rememberTerminalCommand(processId, trimmed)"))
    }
}
