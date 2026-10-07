package me.rerere.rikkahub.benchmark

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportLineLookup
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.captureViewportAnchor
import me.rerere.rikkahub.data.container.terminalImeAnchorScrollTarget
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.TerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.rememberTerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import kotlin.coroutines.resume

/**
 * Actual production renderer, compatibility policy, gesture binding and sole effect executor.
 * The harness publishes deterministic frames and waits for draws; it NEVER scrolls a backend or
 * supplies measurement/width corrections. This is not ProcessSessionPage, a PTY or app startup.
 */
class ProductionTerminalBenchmarkActivity : ComponentActivity() {
    private lateinit var terminal: TerminalEmulator
    private lateinit var status: TextView
    private lateinit var phaseToken: String
    private lateinit var output: ComposeView
    private var current by mutableStateOf<Viewport?>(null, referentialEqualityPolicy())
    private var busy = true
    private var size = 0
    private lateinit var scenario: String
    private lateinit var mode: TerminalRenderMode
    private var imeVisible = false
    private var inputFocused = false
    private var keyboard: SoftwareKeyboardController? = null
    private val focus = FocusRequester()
    private var input by mutableStateOf("")

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        size = intent.getIntExtra("history_rows", 1000)
        scenario = intent.getStringExtra("scenario") ?: "initialCompose"
        val modeId = intent.getStringExtra("renderer") ?: "chunkedLayers"
        ProductionBenchmarkSpec.validate(size, scenario, modeId)
        phaseToken = checkNotNull(intent.getStringExtra(ProductionBenchmarkSpec.PHASE_TOKEN))
        mode = TerminalRenderMode.fromId(modeId)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { id = R.id.benchmark_status; text = "preparing" }
        controls.addView(status)
        controls.addView(Button(this).apply {
            id = R.id.benchmark_mount
            text = "Mount production viewport"
            setOnClickListener { runOperation("mounted") { mount() } }
        })
        controls.addView(Button(this).apply {
            id = R.id.benchmark_run
            text = "Run production scenario"
            setOnClickListener { runOperation("done") { runScenario() } }
        })
        output = ComposeView(this).apply {
            setContent {
                val ime = WindowInsets.isImeVisible
                val keyboardController = LocalSoftwareKeyboardController.current
                SideEffect { imeVisible = ime; keyboard = keyboardController }
                MaterialTheme {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        AndroidView(factory = { controls }, modifier = Modifier.fillMaxWidth())
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)
                            // Hide only fixture accessibility traversal, not production Text/layout.
                            .clearAndSetSemantics { }) {
                            current?.let { viewport -> key(viewport) { viewport.Content(ime) } }
                        }
                        BasicTextField(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 40.dp)
                            .focusRequester(focus).onFocusChanged { inputFocused = it.isFocused })
                    }
                }
            }
        }
        setContentView(output)
        lifecycleScope.launch {
            terminal = withContext(Dispatchers.Default) { TerminalBenchmarkWorkload.prepare(size) }
            busy = false
            publishPhase("prepared")
        }
    }

    private fun runOperation(done: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status.text = "running"
        lifecycleScope.launch(AndroidUiDispatcher.Main) {
            try {
                withTimeout(120_000) { block() }
                publishPhase(done)
                Log.i("ProductionTerminalBenchmark", "validated size=$size scenario=$scenario mode=${mode.id} phase=$done")
            } catch (error: TimeoutCancellationException) {
                fail(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(error)
            } finally { busy = false }
        }
    }

    private fun fail(error: Exception) {
        Log.e("ProductionTerminalBenchmark", "Fixture failed: size=$size scenario=$scenario mode=${mode.id}", error)
        publishPhase("failed:${error.javaClass.simpleName}:${error.message}")
    }

    /** A receipt reports completed validation; it never drives or substitutes for viewport work. */
    private fun publishPhase(phase: String) {
        status.text = phase // Visible diagnostics remain useful, but accessibility is not a latch.
        sendBroadcast(Intent(ProductionBenchmarkSpec.PHASE_ACTION)
            .setPackage(ProductionBenchmarkSpec.DRIVER_PACKAGE)
            .putExtra(ProductionBenchmarkSpec.PHASE_TOKEN, phaseToken)
            .putExtra(ProductionBenchmarkSpec.PHASE_VALUE, phase), ProductionBenchmarkSpec.PHASE_PERMISSION)
    }

    private suspend fun mount() = productionAsyncTrace("Prod.mountToSettledDraw") {
        check(current == null)
        val frame = productionTrace("Prod.renderFrame") { terminal.renderFrame() }
        check(frame.historyCount == size && frame.rows.size == size + TerminalBenchmarkWorkload.SCREEN_ROWS)
        // Restore a reproducible reading position safely away from either boundary. Locking at
        // the structural tail would make a shorter last row legitimately clamp the viewport.
        // Setup is outside the timed IME/remount operations; those capture the actual settled top.
        val reading = if (scenario in listOf("imeRoundTrip", "detachRestore")) TerminalViewportState(
            autoScroll = false, atBottom = false, anchorLineId = frame.historyLineIds[size / 2],
            anchorClippedTopPx = 7, anchorHistoryGeneration = frame.historyGeneration,
        ) else null
        val viewport = productionTrace("Prod.rowSync") { Viewport(frame, reading) }
        current = viewport
        awaitSettled(viewport)
        validate(viewport)
        if (reading != null) viewport.checkSavedAnchor(reading)
    }

    private suspend fun runScenario() = productionAsyncTrace("Prod.operation") {
        // Do not spill the old Viewport into this caller's suspended continuation while a new
        // viewport is mounted. The detach path returns only scalar saved viewport state.
        if (scenario == "detachRestore") {
            detachRestore()
            return@productionAsyncTrace
        }
        val viewport = checkNotNull(current)
        val widths = viewport.bound.binding.widthIndex
        val screenWorkBefore = widths.measuredScreenRows
        val historyWorkBefore = widths.measuredHistoryRows
        val reusedBefore = widths.reusedScreenRows
        when (scenario) {
            "activeRowUpdate", "appendAndTrim" -> repeat(ProductionBenchmarkSpec.UPDATE_COUNT) { index ->
                val deadline = SystemClock.uptimeMillis() + ProductionBenchmarkSpec.UPDATE_INTERVAL_MS
                productionAsyncTrace("Prod.outputToSettledDraw") {
                    val line = TerminalBenchmarkWorkload.line(size + TerminalBenchmarkWorkload.SCREEN_ROWS + index)
                    productionTrace("Prod.feed") {
                        terminal.feed(if (scenario == "appendAndTrim") "\r\n$line" else "\r\u001b[2K$line")
                    }
                    viewport.publish()
                    check(viewport.frame.historyCount == size)
                    if (scenario == "activeRowUpdate") {
                        check(terminal.lastRenderHistoryVisits == 0)
                        check(viewport.sync.lastUsedMetadataFastPath)
                        check(viewport.sync.lastVisitedTextRows == TerminalBenchmarkWorkload.SCREEN_ROWS)
                    } else {
                        check(terminal.lastRenderHistoryVisits == 1)
                        check(viewport.sync.lastUsedFifoFastPath)
                        check(viewport.sync.lastVisitedTextRows == TerminalBenchmarkWorkload.SCREEN_ROWS + 1)
                    }
                    awaitSettled(viewport)
                    validate(viewport)
                }
                delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
            }
            "semanticJump" -> {
                productionAsyncTrace("Prod.jumpTop") {
                    viewport.controller.jumpToTop(viewport.bound.binding.currentScrollPx())
                    awaitSettled(viewport)
                    check(viewport.controller.isNearTop(viewport.bound.binding.currentScrollPx()))
                    check(!viewport.controller.state.value.autoScroll)
                    check(viewport.controller.state.value.anchor?.lineId == viewport.frame.historyLineIds.first())
                    validate(viewport)
                }
                productionAsyncTrace("Prod.jumpTail") {
                    viewport.controller.jumpToBottom(viewport.bound.binding.currentScrollPx())
                    awaitSettled(viewport)
                    check(viewport.controller.state.value.autoScroll)
                    validate(viewport)
                }
            }
            "imeRoundTrip" -> imeRoundTrip(viewport)
            else -> error("Unsupported production operation: $scenario")
        }
        if (mode.isVirtualHistory && scenario in listOf("activeRowUpdate", "appendAndTrim")) {
            val measured = widths.measuredScreenRows - screenWorkBefore
            val updates = ProductionBenchmarkSpec.UPDATE_COUNT.toLong()
            if (scenario == "activeRowUpdate") {
                check(measured == updates) { "Active row output remeasured unchanged screen widths: $measured" }
                check(widths.measuredHistoryRows == historyWorkBefore)
            } else {
                // A visible cursor can change BOTH the former and new last screen row.
                check(measured in updates..updates * 2) { "Append did not reuse surviving screen widths: $measured" }
                check(widths.measuredHistoryRows - historyWorkBefore == updates)
            }
            check(widths.reusedScreenRows - reusedBefore >= updates * (TerminalBenchmarkWorkload.SCREEN_ROWS - 2))
        }
        Log.i("ProductionTerminalBenchmark", "WIDTH_WORK scenario=$scenario size=$size mode=${mode.id} token=$phaseToken " +
            "historyMeasured=${widths.measuredHistoryRows - historyWorkBefore} " +
            "screenMeasured=${widths.measuredScreenRows - screenWorkBefore} " +
            "screenReused=${widths.reusedScreenRows - reusedBefore} retainedScreen=${widths.retainedScreenRows}")
    }

    private suspend fun imeRoundTrip(viewport: Viewport) {
        val saved = viewport.save()
        val widthIndex = viewport.bound.binding.widthIndex
        val measuredHistory = widthIndex.measuredHistoryRows
        val measuredScreen = widthIndex.measuredScreenRows
        val fullHeight = viewport.viewportHeight
        val stableIme = mode == TerminalRenderMode.VIRTUAL_HISTORY_IME
        val measurements = viewport.measurements
        val nodesBefore = measurements.createdRowNodes
        val noticesBefore = measurements.publishedNotifications
        val coalescedBefore = measurements.coalescedChanges
        productionAsyncTrace("Prod.imeShow") {
            focus.requestFocus()
            awaitCondition { inputFocused }
            checkNotNull(keyboard).show()
            awaitCondition { imeVisible && viewport.viewportHeight < fullHeight }
            awaitSettled(viewport)
            check(viewport.bound.virtualHistoryEnabled == stableIme)
            viewport.checkSavedAnchor(saved)
            validate(viewport)
        }
        repeat(ProductionBenchmarkSpec.IME_UPDATE_COUNT) { index ->
            productionAsyncTrace("Prod.outputToSettledDraw") {
                productionTrace("Prod.feed") {
                    terminal.feed("\r\u001b[2K" + TerminalBenchmarkWorkload.line(size + 24 + index))
                }
                viewport.publish()
                awaitSettled(viewport)
                viewport.checkSavedAnchor(saved)
                validate(viewport)
            }
        }
        productionAsyncTrace("Prod.imeHide") {
            checkNotNull(keyboard).hide()
            awaitCondition { !imeVisible && viewport.viewportHeight == fullHeight }
            awaitSettled(viewport)
            check(viewport.bound.virtualHistoryEnabled == stableIme) { "Incorrect renderer after IME hide" }
            if (!stableIme) {
                check(widthIndex.measuredHistoryRows == measuredHistory && widthIndex.measuredScreenRows == measuredScreen) {
                    "Compatibility fallback measured virtual widths"
                }
            }
            viewport.checkSavedAnchor(saved)
            validate(viewport)
        }
        productionAsyncTrace("Prod.explicitRetry") {
            // Same explicit Apply callback in all modes. Stable IME mode was already virtual;
            // include this no-op phase for equal action counts, not as a required user operation.
            viewport.policy.reapplied(false)
            awaitCondition { viewport.bound.virtualHistoryEnabled == mode.isVirtualHistory }
            awaitSettled(viewport)
            viewport.checkSavedAnchor(saved)
            validate(viewport)
        }
        check(viewport.bound.binding.widthIndex === widthIndex)
        check(widthIndex.measuredHistoryRows == measuredHistory) { "IME retry rescanned unchanged history widths" }
        if (mode.isVirtualHistory) {
            check(widthIndex.measuredScreenRows > measuredScreen) { "IME retry did not measure updated screen widths" }
        }
        if (stableIme) {
            check(measurements.eagerHistoryBuildCount == 0L) { "Stable IME mounted eager history" }
            check(measurements.createdRowNodes - nodesBefore < 256) { "Stable IME rebuilt the history row tree" }
            check(widthIndex.measuredScreenRows - measuredScreen == ProductionBenchmarkSpec.IME_UPDATE_COUNT.toLong()) {
                "Stable IME remeasured unchanged physical screen widths"
            }
        } else if (mode == TerminalRenderMode.VIRTUAL_HISTORY) {
            check(measurements.coalescedChanges - coalescedBefore >= size - 128) {
                "Eager fallback did not coalesce its bulk row invalidations"
            }
        }
        Log.i("ProductionTerminalBenchmark", "IME_WORK size=$size mode=${mode.id} token=$phaseToken " +
            "nodes=${measurements.createdRowNodes - nodesBefore} " +
            "notifications=${measurements.publishedNotifications - noticesBefore} " +
            "coalesced=${measurements.coalescedChanges - coalescedBefore} " +
            "heightRecords=${measurements.createdHeightRecords} measuredRows=${measurements.measuredRowCount}")
        check(terminal.rows == TerminalBenchmarkWorkload.SCREEN_ROWS)
    }

    /** Complete the old-owner continuation before creating the replacement row tree/controller. */
    private suspend fun detachAndCapture(): TerminalViewportState {
        val viewport = checkNotNull(current)
        val saved = viewport.save()
        productionAsyncTrace("Prod.detach") {
            current = null
            awaitCondition { !viewport.attached && viewport.measurements.retainedRows == 0 &&
                viewport.measurements.retainedEagerHistoryRows == 0 && viewport.bound.binding.activeWriters == 0 &&
                !viewport.bound.binding.widthIndex.hasRetainedState }
        }
        return saved
    }

    private suspend fun detachRestore() {
        val saved = detachAndCapture()
        productionTrace("Prod.feed") {
            repeat(ProductionBenchmarkSpec.BACKGROUND_LINES) { index ->
                terminal.feed("\r\n" + TerminalBenchmarkWorkload.line(size + TerminalBenchmarkWorkload.SCREEN_ROWS + index))
            }
        }
        productionAsyncTrace("Prod.restoreToSettledDraw") {
            val frame = productionTrace("Prod.renderFrame") { terminal.renderFrame() }
            val restored = productionTrace("Prod.rowSync") { Viewport(frame, saved) }
            current = restored
            awaitSettled(restored)
            restored.checkSavedAnchor(saved)
            check(restored.horizontal.value == saved.horizontalOffsetPx)
            validate(restored)
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(45_000) {
        do { output.awaitProductionDraw() } while (!condition())
    }

    private suspend fun awaitSettled(viewport: Viewport) = withTimeout(45_000) {
        var stableDraws = 0
        var effects = -1
        while (stableDraws < 2) {
            output.awaitProductionDraw()
            val ready = viewport.ready()
            val count = viewport.effectCount()
            stableDraws = if (ready && effects == count) stableDraws + 1 else 0
            effects = count
        }
    }

    private fun validate(viewport: Viewport) = productionTrace("Prod.validation") {
        check(viewport.ready())
        check(viewport.bound.binding.maximumWriters <= 1) { "Multiple production scroll writers" }
        check(viewport.frame === viewport.bound.pass.frame && viewport.drawnFrame === viewport.frame)
        val expected = mode.isVirtualHistory && !viewport.policy.imeFallback &&
            (!imeVisible || mode == TerminalRenderMode.VIRTUAL_HISTORY_IME)
        check(viewport.bound.virtualHistoryEnabled == expected) { "Incorrect renderer under benchmark label" }
        if (expected) {
            check(viewport.measurements.retainedRows < 128) { "Virtual history retained an eager row tree" }
            check(viewport.measurements.retainedEagerHistoryRows == 0)
            check(viewport.bound.binding.widthIndex.retainedScreenRows == TerminalBenchmarkWorkload.SCREEN_ROWS)
        } else if (mode == TerminalRenderMode.DEFAULT) {
            check(viewport.measurements.retainedRows == 0) { "Default eager added virtual measurements" }
            check(!viewport.bound.binding.widthIndex.hasRetainedState)
            check(viewport.bound.binding.widthIndex.measuredHistoryRows == 0L)
            check(viewport.measurements.createdRowNodes == 0L)
            check(viewport.measurements.measuredRowCount == 0L)
            check(viewport.measurements.publishedNotifications == 0L)
        }
        if (!expected) check(viewport.bound.binding.widthIndex.retainedScreenRows == 0) {
            "Compatibility fallback retained physical screen text keys"
        }
        Trace.setCounter("Prod.createdRowNodes", viewport.measurements.createdRowNodes)
        Trace.setCounter("Prod.measuredRowCount", viewport.measurements.measuredRowCount)
        Trace.setCounter("Prod.changedRowMeasurements", viewport.measurements.changedRowMeasurements)
        Trace.setCounter("Prod.createdHeightRecords", viewport.measurements.createdHeightRecords)
        Trace.setCounter("Prod.measurementNotifications", viewport.measurements.publishedNotifications)
        Trace.setCounter("Prod.coalescedMeasurements", viewport.measurements.coalescedChanges)
        Trace.setCounter("Prod.widthMeasuredHistory", viewport.bound.binding.widthIndex.measuredHistoryRows)
        Trace.setCounter("Prod.widthMeasuredScreen", viewport.bound.binding.widthIndex.measuredScreenRows)
        Trace.setCounter("Prod.widthReusedScreen", viewport.bound.binding.widthIndex.reusedScreenRows)
        Trace.setCounter("Prod.widthRetainedScreen", viewport.bound.binding.widthIndex.retainedScreenRows.toLong())
        Trace.setCounter("Prod.retainedMeasurements", viewport.measurements.retainedRows.toLong())
        Trace.setCounter("Prod.eagerHistoryRows", viewport.measurements.retainedEagerHistoryRows.toLong())
        Trace.setCounter("Prod.scrollEffects", viewport.bound.binding.effectCount.toLong())
        Trace.setCounter("Prod.maximumWriters", viewport.bound.binding.maximumWriters.toLong())
    }

    private inner class Viewport(initial: TerminalEmulator.RenderFrame, restored: TerminalViewportState? = null) {
        var frame by mutableStateOf(initial, referentialEqualityPolicy())
        val rows = createTerminalRenderedRows(initial)
        val sync = createTerminalRenderedRowsSyncState(initial, rows)
        val controller = TerminalViewportController(restored)
        val eager = ScrollState(if (restored?.autoScroll != false) Int.MAX_VALUE else restored.verticalOffsetPx)
        val lazy = LazyListState()
        val horizontal = ScrollState(restored?.horizontalOffsetPx ?: 0)
        val measurements = TerminalLazyItemMeasurements()
        val anchorLookup = TerminalViewportLineLookup()
        lateinit var bound: TerminalBoundViewport
        lateinit var policy: TerminalVirtualHistoryPolicy
        var viewportHeight by mutableIntStateOf(0)
        var cellHeight = 1
        var tailPadding = 0
        var attached = false
        var drawnFrame: TerminalEmulator.RenderFrame? = null

        @Composable fun Content(ime: Boolean) {
            val style = remember { TextStyle(color = Color(0xFF00E676), fontFamily = JetbrainsMono,
                fontSize = 14.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)) }
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            cellHeight = textMeasurer.measure("W", style).size.height.coerceAtLeast(1)
            tailPadding = with(density) { 8.dp.roundToPx() }
            val chunks = remember(frame.historyCount, frame.historyStartSequence) {
                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, usesTuiViewport = false)
            }
            policy = rememberTerminalVirtualHistoryPolicy(this, mode, ime)
            val wants = mode.isVirtualHistory &&
                policy.allows(chunks.isNotEmpty(), true, false, false, ime, avoidIme = ime)
            bound = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements, frame, style, wants,
                metrics = { TerminalViewportMetrics(eager.maxValue, viewportHeight, cellHeight, tailPadding,
                    imeVisible = ime, avoidIme = ime) },
                gestureConfig = TerminalViewportGestureConfig(true, false, 2))
            DisposableEffect(Unit) {
                attached = true
                onDispose { attached = false; drawnFrame = null }
            }
            val drawing = frame
            Box(Modifier.fillMaxSize().onSizeChanged { viewportHeight = it.height }
                .nestedScroll(bound.gestures.connection)
                .layout { measurable, constraints ->
                    productionTrace("Prod.measure") {
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }
                }.drawWithContent {
                    productionTrace("Prod.draw") { drawContent(); drawnFrame = drawing }
                }) {
                TerminalTranscriptViewport(frame, rows.toList(), style, chunks, mode, bound, horizontal,
                    panEnabled = true, selectionMode = false, modifier = Modifier.fillMaxSize())
            }
        }

        fun publish() {
            val next = productionTrace("Prod.renderFrame") { terminal.renderFrame() }
            productionTrace("Prod.rowSync") {
                Snapshot.withMutableSnapshot {
                    synchronizeTerminalRenderedRows(rows, next, false, nowMs = SystemClock.uptimeMillis(), syncState = sync)
                    frame = next
                }
            }
        }

        fun effectCount(): Int = if (::bound.isInitialized) bound.binding.effectCount else -1

        /** Read-only completion guard. Never calls updateViewport, scrollTo, or a handoff callback. */
        fun ready(): Boolean {
            if (!attached || !::bound.isInitialized || drawnFrame !== frame || bound.pass.frame !== frame ||
                viewportHeight <= 0 || !controller.state.value.initialized || controller.state.value.scrollEffect != null ||
                controller.state.value.gesture != null || bound.binding.activeWriters != 0) return false
            if (bound.virtualHistoryEnabled) {
                val observation = bound.binding.observation() ?: return false
                if (lazy.isScrollInProgress) return false
                return if (controller.state.value.autoScroll) observation.isSatisfied(TerminalItemScrollTarget.Follow)
                    else controller.state.value.anchor?.let { observation.isSatisfied(
                        TerminalItemScrollTarget.Anchor(it, controller.state.value.anchorRowHeightPx)) } == true
            }
            if (eager.maxValue == Int.MAX_VALUE || eager.isScrollInProgress) return false
            val geometry = measurements.peekEager(bound.pass)
            if (bound.eagerMeasurement != null && geometry == null) return false
            if (!controller.state.value.autoScroll) {
                val state = controller.state.value
                val anchor = state.anchor ?: return false
                if (geometry != null) {
                    val target = geometry.target(anchor, state.anchorRowHeightPx, anchorLookup) ?: return false
                    return eager.value == target.coerceIn(0, eager.maxValue)
                }
                val captured = captureViewportAnchor(frame, frame.rows.size, eager.value, cellHeight) ?: return false
                return captured.lineId == anchor.lineId && captured.clippedTopPx == anchor.clippedTopPx
            }
            val last = frame.contentBounds.lastNonBlankRow
            val target = if (geometry != null && last != null) {
                (geometry.bottom(last) + tailPadding - viewportHeight).coerceIn(0, eager.maxValue)
            } else terminalImeAnchorScrollTarget(last, cellHeight, viewportHeight, tailPadding, eager.maxValue)
            return eager.value == target
        }

        fun save(): TerminalViewportState {
            val pixels = bound.binding.currentScrollPx()
            val state = controller.state.value
            return TerminalViewportState(verticalOffsetPx = pixels, horizontalOffsetPx = horizontal.value,
                autoScroll = state.autoScroll, atBottom = controller.isNearBottom(pixels), viewportMode = state.mode,
                anchorLineId = state.anchor?.lineId, anchorClippedTopPx = state.anchor?.clippedTopPx ?: 0,
                anchorCellHeightPx = state.anchorCellHeightPx, anchorRowHeightPx = state.anchorRowHeightPx,
                anchorScreenGeneration = state.anchor?.screenGeneration, anchorHistoryGeneration = state.anchor?.historyGeneration)
        }

        fun checkSavedAnchor(saved: TerminalViewportState) {
            val state = controller.state.value
            check(!state.autoScroll && state.anchor?.lineId == saved.anchorLineId &&
                state.anchor?.clippedTopPx == saved.anchorClippedTopPx) { "Restored reading anchor drifted" }
            if (bound.virtualHistoryEnabled) {
                val actual = checkNotNull(bound.binding.observation()?.capture())
                check(actual.anchor.lineId == saved.anchorLineId && actual.anchor.clippedTopPx == saved.anchorClippedTopPx)
            } else if (bound.eagerMeasurement != null) {
                val actual = checkNotNull(measurements.peekEager(bound.pass)).capture(eager.value)
                check(actual.anchor.lineId == saved.anchorLineId && actual.anchor.clippedTopPx == saved.anchorClippedTopPx)
            } else {
                val actual = checkNotNull(captureViewportAnchor(frame, frame.rows.size, eager.value, cellHeight))
                check(actual.lineId == saved.anchorLineId && actual.clippedTopPx == saved.anchorClippedTopPx)
            }
        }
    }
}

private inline fun <T> productionTrace(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}

private suspend inline fun <T> productionAsyncTrace(name: String, block: () -> T): T {
    Trace.beginAsyncSection(name, 1)
    return try { block() } finally { Trace.endAsyncSection(name, 1) }
}

private suspend fun View.awaitProductionDraw(): Unit = suspendCancellableCoroutine { continuation ->
    var posted = false
    val listener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            if (posted) return
            posted = true
            post {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnDrawListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    viewTreeObserver.addOnDrawListener(listener)
    continuation.invokeOnCancellation { post { if (viewTreeObserver.isAlive) viewTreeObserver.removeOnDrawListener(listener) } }
    invalidate()
}
