package me.rerere.rikkahub.benchmark

import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * An isolated renderer harness, not a replacement terminal or a viewport-controller benchmark.
 * It has no shell, database, network, IME or production-app startup work. Native controls keep
 * UiAutomator from walking 10k Text semantics nodes inside the measured window.
 */
class TerminalBenchmarkActivity : ComponentActivity() {
    private lateinit var terminal: TerminalEmulator
    private lateinit var status: TextView
    private lateinit var output: ComposeView
    private var rows by mutableStateOf<SnapshotStateList<TerminalRenderedRowState>?>(null)
    private val verticalScroll = ScrollState(Int.MAX_VALUE)
    private val horizontalScroll = ScrollState(0)
    private lateinit var lazyScroll: LazyListState
    private var layoutState by mutableStateOf<TerminalBenchmarkLayout?>(null)
    private val compositionStats = LazyCompositionStats()
    private var renderer = BenchmarkRenderer.EAGER
    private var configuredTui = false
    private var preserveFullGrid = false
    private var busy = true
    private var historyRows = 0
    private var scenario = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyRows = intent.getIntExtra("history_rows", 1_000)
        scenario = intent.getStringExtra("scenario") ?: "initialCompose"
        renderer = BenchmarkRenderer.fromWireName(intent.getStringExtra("renderer") ?: "eager")
        configuredTui = intent.getBooleanExtra("configured_tui", false)
        preserveFullGrid = intent.getBooleanExtra("preserve_full_grid", false)
        // The last, small tail item clamps to the true bottom even if the grid exceeds the viewport.
        lazyScroll = LazyListState(firstVisibleItemIndex = historyRows + 1)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }
        status = TextView(this).apply {
            id = R.id.benchmark_status
            text = "preparing"
        }
        root.addView(status)
        root.addView(Button(this).apply {
            id = R.id.benchmark_mount
            text = "Mount terminal"
            setOnClickListener { runOperation("mounted") { mount() } }
        })
        root.addView(Button(this).apply {
            id = R.id.benchmark_run
            text = "Run workload"
            setOnClickListener { runOperation("done") { runWorkload() } }
        })
        output = ComposeView(this).apply {
            // Only accessibility traversal is disabled; actual Compose Text/layout/draw is unchanged.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                MaterialTheme {
                    val rendered = rows
                    val currentLayout = layoutState
                    if (rendered != null && currentLayout != null) {
                        val style = TextStyle(
                            color = Color(0xFF00E676),
                            fontFamily = JetbrainsMono,
                            fontSize = 14.sp,
                            lineHeight = 14.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                        )
                        TerminalBenchmarkViewport(
                            rows = rendered,
                            layout = currentLayout,
                            style = style,
                            verticalScroll = verticalScroll,
                            horizontalScroll = horizontalScroll,
                            lazyScroll = lazyScroll,
                            stats = compositionStats,
                            modifier = Modifier.fillMaxSize()
                                .background(Color.Black)
                                .layout { measurable, constraints ->
                                    traced("Terminal.measure") {
                                        val placeable = measurable.measure(constraints)
                                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                    }
                                }
                                .drawWithContent { traced("Terminal.draw") { drawContent() } }
                        )
                    }
                }
            }
        }
        root.addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        lifecycleScope.launch {
            terminal = withContext(Dispatchers.Default) {
                TerminalBenchmarkWorkload.prepare(historyRows).also {
                    if (scenario == "alternateScreenUpdate") TerminalBenchmarkWorkload.enterAlternateScreen(it)
                }
            }
            busy = false
            status.text = "prepared"
        }
    }

    private fun runOperation(completedStatus: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        status.text = "running"
        // Scroll animations need a frame clock, not just Dispatchers.Main.
        lifecycleScope.launch(AndroidUiDispatcher.Main) {
            try {
                operation()
                status.text = completedStatus
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("TerminalBenchmark", "Fixture validation failed", error)
                status.text = "failed:${error.javaClass.simpleName}: ${error.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun mount() {
        check(rows == null) { "Each measured iteration must mount a fresh renderer" }
        Trace.beginAsyncSection("Terminal.mountToDraw", 1)
        try {
            val frame = traced("Terminal.renderFrame") { terminal.renderFrame() }
            val expectedHistory = if (scenario == "alternateScreenUpdate") 0 else historyRows
            check(frame.historyCount == expectedHistory)
            check(frame.rows.size == expectedHistory + TerminalBenchmarkWorkload.SCREEN_ROWS)
            traced("Terminal.rowSync") {
                Snapshot.withMutableSnapshot {
                    rows = createTerminalRenderedRows(frame)
                    layoutState = layoutFor(frame)
                }
            }
            // Complete on actual draw callbacks, not a fixed sleep or merely a state assignment.
            output.awaitNextDraw()
            output.awaitNextDraw()
            verifyTail()
        } finally {
            Trace.endAsyncSection("Terminal.mountToDraw", 1)
        }
    }

    private suspend fun runWorkload() {
        check(rows != null)
        verifyTail()
        when (scenario) {
            "historyScroll" -> {
                // Identical ScrollableState animation and physical distance in BOTH arms.
                val scroll: ScrollableState = if (layoutState!!.useLazyHistory) lazyScroll else verticalScroll
                val distance = (output.height * 6).toFloat()
                val consumed = scroll.animateScrollBy(-distance, tween(1_000, easing = LinearEasing))
                check(abs(consumed + distance) <= 1f) { "History pan did not cover six viewports" }
                scroll.animateScrollBy(-consumed, tween(1_000, easing = LinearEasing))
                scroll.scrollBy(1f) // Clamp sub-pixel rounding at the tail equally for both backends.
                output.awaitNextDraw()
                verifyTail()
            }
            "activeRowUpdate", "appendAndTrim", "alternateScreenUpdate" -> {
                repeat(TerminalBenchmarkWorkload.UPDATE_COUNT) { index ->
                    val deadline = SystemClock.uptimeMillis() + TerminalBenchmarkWorkload.UPDATE_INTERVAL_MS
                    val line = TerminalBenchmarkWorkload.line(historyRows + TerminalBenchmarkWorkload.SCREEN_ROWS + index)
                    traced("Terminal.feed") {
                        terminal.feed(if (scenario == "appendAndTrim") "\r\n$line" else "\r\u001b[2K$line")
                    }
                    val frame = traced("Terminal.renderFrame") { terminal.renderFrame() }
                    traced("Terminal.rowSync") {
                        Snapshot.withMutableSnapshot {
                            synchronizeTerminalRenderedRows(
                                rows!!,
                                frame,
                                usesTuiViewport = frame.isAlternateScreen || configuredTui || preserveFullGrid,
                                nowMs = SystemClock.uptimeMillis(),
                            )
                            layoutState = layoutFor(frame)
                        }
                    }
                    if (scenario == "appendAndTrim" && layoutState!!.useLazyHistory) {
                        traced("Terminal.followTail") {
                            // Stable-key anchoring otherwise keeps the old first visible history row,
                            // hiding the changing last screen row and producing a false speedup.
                            lazyScroll.requestScrollToItem(layoutState!!.tailItemIndex)
                        }
                    }
                    output.awaitNextDraw()
                    verifyTail()
                    delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(0))
                }
            }
            else -> error("Unknown workload: $scenario")
        }
    }

    private fun layoutFor(frame: TerminalEmulator.RenderFrame) = TerminalBenchmarkLayout.fromFrame(
        frame, renderer, configuredTui, preserveFullGrid,
    )

    private fun verifyTail() {
        val current = checkNotNull(layoutState)
        check(current.screenRows == TerminalBenchmarkWorkload.SCREEN_ROWS)
        if (!current.useLazyHistory) {
            check(verticalScroll.value == verticalScroll.maxValue) { "Eager viewport left the tail" }
            check(compositionStats.historyRows == 0 && compositionStats.activeGrids == 0)
            return
        }
        check(!lazyScroll.canScrollForward) { "Lazy viewport left the tail" }
        val info = lazyScroll.layoutInfo
        check(info.totalItemsCount == current.lazyItemCount)
        check(info.visibleItemsInfo.any { it.key == TerminalBenchmarkLayout.TAIL_KEY })
        check(info.visibleItemsInfo.any { it.key == TerminalBenchmarkLayout.ACTIVE_SCREEN_KEY })
        check(compositionStats.activeGrids == 1) { "The active screen must remain one complete physical grid" }
        if (current.historyRows > 0) {
            check(compositionStats.historyRows in 0 until current.historyRows) {
                "The candidate unexpectedly composed all history rows"
            }
        }
    }
}

private inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private suspend fun View.awaitNextDraw(): Unit = suspendCancellableCoroutine { continuation ->
    var posted = false
    val listener = object : ViewTreeObserver.OnDrawListener {
        override fun onDraw() {
            if (posted) return
            posted = true
            // An OnDrawListener cannot be removed during onDraw dispatch.
            post {
                if (viewTreeObserver.isAlive) viewTreeObserver.removeOnDrawListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
    viewTreeObserver.addOnDrawListener(listener)
    continuation.invokeOnCancellation {
        post { if (viewTreeObserver.isAlive) viewTreeObserver.removeOnDrawListener(listener) }
    }
    invalidate()
}
