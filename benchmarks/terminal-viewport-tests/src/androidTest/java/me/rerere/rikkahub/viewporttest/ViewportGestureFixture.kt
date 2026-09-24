package me.rerere.rikkahub.viewporttest

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.rememberTerminalViewportGestures
import me.rerere.rikkahub.ui.pages.container.runTerminalViewportScrollEffects
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * A synthetic UNIFORM-height viewport contract, not a production LazyListState geometry adapter.
 * Both arms use the actual controller/reducer, gesture helper and Text row renderer. Explicit 64px
 * row boxes make the test pixel mapping exact; variable ANSI/CJK heights are deliberately NOT solved
 * here. Real pointer input goes through nestedScroll, including the child's consumed normal fling.
 */
internal class ViewportGestureFixture(val lazyHistory: Boolean) {
    companion object {
        const val OUTPUT_TAG = "terminal-output-viewport"
        const val SCREEN_KEY = "physical-screen"
        const val TAIL_KEY = "tail"
        const val HISTORY_ROWS = 1_000
        const val SCREEN_ROWS = 24
        const val ROW_HEIGHT = 64
        const val TAIL_HEIGHT = 8
        const val INITIAL_PX = HISTORY_ROWS / 2 * ROW_HEIGHT
    }

    var frame by mutableStateOf(makeFrame())
        private set
    private val rows = createTerminalRenderedRows(frame)
    val controller = TerminalViewportController(TerminalViewportState(
        autoScroll = false, verticalOffsetPx = INITIAL_PX,
    ))
    val eagerScroll = ScrollState(INITIAL_PX)
    val lazyScroll = LazyListState(HISTORY_ROWS / 2)
    val horizontalScroll = ScrollState(0)
    var config by mutableStateOf(TerminalViewportGestureConfig(true, false, 2))
    private var viewportHeight by mutableIntStateOf(0)
    var inputTimeMs = 1_000L
    var emitOutputDuringNextJump = false
    var emittedUpdates = 0
        private set
    private val effects = mutableListOf<TerminalViewportScrollEffect>()
    private val effectEvents = mutableListOf<String>()
    val jumps: List<TerminalViewportScrollEffect> get() = effects.filter { it.origin == ViewportScrollOrigin.JUMP }
    var activeWriters = 0
        private set
    var maximumWriters = 0
        private set
    var composedHistory = 0
        private set
    var composedScreens = 0
        private set

    fun currentPx(): Int = if (lazyHistory) {
        val row = when (val item = lazyScroll.firstVisibleItemIndex) {
            in 0 until HISTORY_ROWS -> item
            HISTORY_ROWS -> HISTORY_ROWS // ONE active-screen item, not 24 lazy items.
            else -> HISTORY_ROWS + SCREEN_ROWS
        }
        row * ROW_HEIGHT + lazyScroll.firstVisibleItemScrollOffset
    } else eagerScroll.value

    private fun maximumPx(): Int = if (lazyHistory) {
        if (lazyScroll.layoutInfo.totalItemsCount != HISTORY_ROWS + 2) Int.MAX_VALUE
        else ((HISTORY_ROWS + SCREEN_ROWS) * ROW_HEIGHT + TAIL_HEIGHT - viewportHeight).coerceAtLeast(0)
    } else eagerScroll.maxValue

    fun isAtTop(): Boolean = if (lazyHistory) {
        !lazyScroll.canScrollBackward && lazyScroll.firstVisibleItemIndex == 0 &&
            lazyScroll.firstVisibleItemScrollOffset == 0
    } else eagerScroll.value == 0

    fun isAtBottom(): Boolean = if (lazyHistory) {
        !lazyScroll.canScrollForward && lazyScroll.layoutInfo.visibleItemsInfo.any {
            it.key == TAIL_KEY && it.offset + it.size <= lazyScroll.layoutInfo.viewportEndOffset
        }
    } else eagerScroll.maxValue != Int.MAX_VALUE && eagerScroll.value == eagerScroll.maxValue

    fun diagnostics(): String = "lazy=$lazyHistory px=${currentPx()} max=${maximumPx()} " +
        "busy=${if (lazyHistory) lazyScroll.isScrollInProgress else eagerScroll.isScrollInProgress} " +
        "first=${lazyScroll.firstVisibleItemIndex}:${lazyScroll.firstVisibleItemScrollOffset} " +
        "state=${controller.state.value} events=${effectEvents.takeLast(8)}"

    private fun metrics() = TerminalViewportMetrics(maximumPx(), viewportHeight, ROW_HEIGHT, TAIL_HEIGHT)

    fun appendAndTrim() {
        val next = makeFrame(firstHistoryId = frame.historyStartId + 1, revision = frame.revision + 1)
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, next, usesTuiViewport = false, nowMs = inputTimeMs)
            frame = next
        }
        emittedUpdates++
    }

    @Composable
    fun Content() {
        val scope = rememberCoroutineScope()
        val gestures = rememberTerminalViewportGestures(
            sessionKey = this,
            controller = controller,
            config = config,
            interactionSource = if (lazyHistory) lazyScroll.interactionSource else eagerScroll.interactionSource,
            currentScrollPx = { currentPx() },
            isScrollInProgress = { if (lazyHistory) lazyScroll.isScrollInProgress else eagerScroll.isScrollInProgress },
            // Input recognition has a deterministic clock; fling animations and pointer dispatch are real.
            // This avoids mistaking a busy software-GPU CI host for a >700ms human gesture interval.
            nowMs = { inputTimeMs },
        )
        LaunchedEffect(this) {
            snapshotFlow { frame.revision to metrics() }.collect {
                withFrameNanos { }
                controller.updateViewport(frame, rows.size, metrics(), currentPx())
            }
        }
        LaunchedEffect(this) {
            runTerminalViewportScrollEffects(
                controller = controller,
                currentScrollPx = { currentPx() },
                maxScrollPx = { maximumPx() },
                isScrollInProgress = {
                    if (lazyHistory) lazyScroll.isScrollInProgress else eagerScroll.isScrollInProgress
                },
            ) { effect, targetPx ->
                effects += effect
                effectEvents += "start $effect at ${currentPx()}"
                activeWriters++
                maximumWriters = maxOf(maximumWriters, activeWriters)
                try {
                    if (effect.origin == ViewportScrollOrigin.JUMP && emitOutputDuringNextJump) {
                        emitOutputDuringNextJump = false
                        scope.launch {
                            repeat(30) {
                                appendAndTrim()
                                withFrameNanos { }
                            }
                        }
                    }
                    applyScrollEffect(effect, targetPx)
                    effectEvents += "completed ${effect.id} at ${currentPx()}"
                } catch (error: CancellationException) {
                    effectEvents += "cancelled ${effect.id}: ${error.javaClass.simpleName}: ${error.message}"
                    throw error
                } finally {
                    activeWriters--
                }
            }
        }
        val density = LocalDensity.current
        MaterialTheme {
            Column(Modifier.width(320.dp)) {
                // Same fixed, non-scrollable status-strip placement as the production viewport.
                Spacer(Modifier.height(32.dp))
                Box(Modifier
                    .size(width = 320.dp, height = with(density) { 640.toDp() })
                    .testTag(OUTPUT_TAG)
                    .onSizeChanged { viewportHeight = it.height }
                    .nestedScroll(gestures.connection)
                ) {
                    val scrollEnabled = config.panEnabled || config.selectionMode
                    if (lazyHistory) {
                        LazyColumn(
                            state = lazyScroll,
                            modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll, scrollEnabled),
                            userScrollEnabled = scrollEnabled,
                            flingBehavior = gestures.flingBehavior,
                        ) {
                            items(HISTORY_ROWS, key = { rows[it].lineId }) { index -> HistoryRow(index) }
                            item(key = SCREEN_KEY) { PhysicalScreen() }
                            item(key = TAIL_KEY) { Spacer(Modifier.height(with(density) { TAIL_HEIGHT.toDp() })) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize().horizontalScroll(horizontalScroll, scrollEnabled)
                            .verticalScroll(eagerScroll, scrollEnabled, flingBehavior = gestures.flingBehavior)
                        ) {
                            for (index in 0 until HISTORY_ROWS) key(rows[index].lineId) { HistoryRow(index) }
                            PhysicalScreen()
                            Spacer(Modifier.height(with(density) { TAIL_HEIGHT.toDp() }))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HistoryRow(index: Int) {
        DisposableEffect(rows[index].lineId) {
            composedHistory++
            onDispose { composedHistory-- }
        }
        UniformRow(index)
    }

    @Composable
    private fun PhysicalScreen() {
        DisposableEffect(Unit) {
            composedScreens++
            onDispose { composedScreens-- }
        }
        Column(Modifier.testTag(SCREEN_KEY)) {
            for (index in HISTORY_ROWS until rows.size) key(rows[index].lineId) { UniformRow(index) }
        }
    }

    @Composable
    private fun UniformRow(index: Int) {
        val density = LocalDensity.current
        Box(Modifier.width(1_000.dp).height(with(density) { ROW_HEIGHT.toDp() })) {
            // The wrapper deliberately fixes row heights only in this contract fixture.
            val text: @Composable () -> Unit = {
                TerminalRenderedRows(listOf(rows[index]), TextStyle(
                    fontFamily = JetbrainsMono, fontSize = 14.sp, color = Color.Black,
                ))
            }
            if (config.selectionMode) SelectionContainer { text() } else text()
        }
    }

    private suspend fun applyScrollEffect(effect: TerminalViewportScrollEffect, targetPx: Int) {
        if (lazyHistory) {
            val index = if (targetPx < HISTORY_ROWS * ROW_HEIGHT) targetPx / ROW_HEIGHT else HISTORY_ROWS
            val offset = targetPx - index * ROW_HEIGHT
            if (effect.animated) lazyScroll.animateScrollToItem(index, offset)
            else lazyScroll.scrollToItem(index, offset)
        } else {
            if (effect.animated) eagerScroll.animateScrollTo(targetPx) else eagerScroll.scrollTo(targetPx)
        }
    }

    private fun makeFrame(firstHistoryId: Long = 100, revision: Long = 1): TerminalEmulator.RenderFrame {
        val ids = List(HISTORY_ROWS) { firstHistoryId + it }
        return TerminalEmulator.RenderFrame(
            rows = List(HISTORY_ROWS + SCREEN_ROWS) { row ->
                val text = if (row < HISTORY_ROWS) "history ${ids[row]}" else "screen $row"
                TerminalEmulator.RenderedRow(AnnotatedString(text))
            },
            contentBounds = TerminalEmulator.ContentBounds(
                0, HISTORY_ROWS + SCREEN_ROWS - 1, HISTORY_ROWS + SCREEN_ROWS,
            ),
            screenContentBounds = TerminalEmulator.ContentBounds(0, SCREEN_ROWS - 1, SCREEN_ROWS),
            screenStartRow = HISTORY_ROWS,
            historyCount = HISTORY_ROWS,
            historyStartId = ids.first(),
            historyEndId = ids.last(),
            historyLineIds = ids,
            historyGeneration = 1,
            screenLineIds = List(SCREEN_ROWS) { 100_000L + it },
            screenGeneration = 1,
            cursorRow = 0,
            cursorVisible = false,
            isAlternateScreen = false,
            modeSummary = "",
            revision = revision,
        )
    }
}
