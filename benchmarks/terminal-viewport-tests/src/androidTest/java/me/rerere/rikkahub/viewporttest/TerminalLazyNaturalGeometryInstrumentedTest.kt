package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.rikkahub.benchmark.BenchmarkRenderer
import me.rerere.rikkahub.benchmark.LazyCompositionStats
import me.rerere.rikkahub.benchmark.TerminalBenchmarkLayout
import me.rerere.rikkahub.benchmark.TerminalBenchmarkViewport
import me.rerere.rikkahub.data.container.TerminalLazyViewportLayout
import me.rerere.rikkahub.data.container.TerminalMeasuredViewportItem
import me.rerere.rikkahub.data.container.ViewportAnchor
import me.rerere.rikkahub.data.container.terminalLazyTargetForAnchor
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.math.roundToInt

/**
 * Exact Macrobenchmark candidate vs current production chunks/layers, with real production Text.
 * No fixed row boxes or total-height estimates. These are renderer/anchor tests, not a second
 * viewport controller: the existing gesture suite owns controller/cancellation coverage.
 * Width-index work stays in the candidate and is independently measured by Macrobenchmark. These
 * cases do not approve real IME, cross-item selection/copy or a production controller switch.
 */
class TerminalLazyNaturalGeometryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(45)
    @get:Rule val probe = object : TestWatcher() {
        override fun starting(description: Description) {
            Log.i("TerminalViewportProbe", "natural start ${description.methodName}")
        }

        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "natural failed ${description.methodName}", error)
        }

        override fun succeeded(description: Description) {
            Log.i("TerminalViewportProbe", "natural passed ${description.methodName}")
        }
    }

    private data class Geometry(
        val text: AnnotatedString, val left: Float, val top: Float, val width: Int, val height: Int,
    )

    private fun idle(fixture: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { fixture.operation?.isCompleted != false }
        compose.waitForIdle()
        fixture.failure?.let { throw AssertionError("Fixture scroll operation failed", it) }
    }

    private fun mount(fixture: Fixture) {
        compose.setContent { fixture.Content() }
        idle(fixture)
    }

    private fun texts(): List<AnnotatedString> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
    ).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].single() }

    private fun geometry(fixture: Fixture): Map<Long, Geometry> {
        idle(fixture)
        val byText = compose.runOnIdle {
            val ids = fixture.frame.historyLineIds + fixture.frame.screenLineIds
            fixture.frame.rows.mapIndexed { index, row -> row.text to ids[index] }.toMap().also {
                // This data set has unique row labels. Blank-only fallback frames are checked
                // through ordered Text lists instead, never guessed from duplicate strings.
                assertEquals(fixture.frame.rows.size, it.size)
            }
        }
        return compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes().associate { node ->
            val text = node.config[SemanticsProperties.Text].single()
            val id = checkNotNull(byText[text]) { "Stale/mismatched Text: $text" }
            id to Geometry(text, node.positionInRoot.x - fixture.viewportLeft,
                node.positionInRoot.y - fixture.viewportTop, node.size.width, node.size.height)
        }
    }

    private fun use(fixture: Fixture, renderer: BenchmarkRenderer) {
        compose.runOnIdle { fixture.renderer = renderer }
        idle(fixture)
    }

    private fun assertRowsMatch(reference: Map<Long, Geometry>, actual: Map<Long, Geometry>) {
        assertTrue(actual.isNotEmpty())
        actual.forEach { (id, row) ->
            val expected = reference.getValue(id)
            assertEquals("Text/spans for $id", expected.text, row.text)
            assertEquals("height for $id", expected.height, row.height)
            assertEquals("width for $id", expected.width, row.width)
            assertEquals("viewport x for $id", expected.left, row.left, 0.1f)
            assertEquals("viewport y for $id", expected.top, row.top, 0.1f)
        }
    }

    /** Production reference is measured in full; that oracle is NEVER supplied to the lazy arm. */
    private fun pairAtAnchor(fixture: Fixture, anchor: ViewportAnchor): Map<Long, Geometry> {
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        val before = geometry(fixture)
        compose.runOnIdle {
            val measuredTop = before.getValue(anchor.lineId).top.roundToInt() + fixture.eager.value
            fixture.runScroll { fixture.eager.scrollTo(measuredTop + anchor.clippedTopPx) }
        }
        val reference = geometry(fixture)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle { fixture.restoreHistory(anchor) }
        val actual = geometry(fixture)
        assertRowsMatch(reference, actual)
        compose.runOnIdle {
            assertEquals(anchor, fixture.historyAnchor())
            assertTrue(fixture.stats.historyRows < fixture.frame.historyCount)
        }
        return reference
    }

    @Test
    fun naturalLazyAnsiCjkAndHyperlinkRowsMatchProductionAtClippedAnchor() {
        val fixture = Fixture()
        mount(fixture)
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[130], 7, null, fixture.frame.historyGeneration)
        pairAtAnchor(fixture, anchor)
        assertTrue(fixture.frame.rows.any { it.text.getStringAnnotations("URL", 0, it.text.length).isNotEmpty() })
    }

    @Test
    fun naturalLazyThirtyTrimsKeepTheLockedIdAndItsActualClipping() {
        val fixture = Fixture()
        mount(fixture)
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[130], 7, null, fixture.frame.historyGeneration)
        pairAtAnchor(fixture, anchor)
        repeat(30) { update ->
            compose.runOnIdle {
                fixture.terminal.feed("\r\n" + fixture.line(10_000 + update))
                fixture.publish() // NO scroll request: exercise LazyList stable-key retention.
            }
            idle(fixture)
            compose.runOnIdle { assertEquals("trim $update", anchor, fixture.historyAnchor()) }
        }
        pairAtAnchor(fixture, anchor)
    }

    @Test
    fun naturalLazyVariableFontSpansAndScaleUseRemeasuredRowGeometry() {
        // Synthetic font-size spans stress the renderer beyond the emulator's usual ANSI styles;
        // heights still come from Text, not fixed boxes or per-index height arithmetic.
        val fixture = Fixture(stressSpans = true)
        mount(fixture)
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[130], 9, null, fixture.frame.historyGeneration)
        val reference = pairAtAnchor(fixture, anchor)
        assertTrue(reference.values.map { it.height }.distinct().size > 1)
        compose.runOnIdle {
            fixture.fontScale = 1.3f
            fixture.fontSp = 18
            fixture.heightDp = 180 // Visual contraction, not a claim of injecting an IME.
            fixture.terminal.resize(columns = 68, rows = 20)
            fixture.publish()
        }
        pairAtAnchor(fixture, anchor)
    }

    @Test
    fun naturalLazyPhysicalScreenStaysOneItemWithMeasuredInternalOffsets() {
        val fixture = Fixture(stressSpans = true)
        fixture.heightDp = 160
        mount(fixture)
        compose.runOnIdle { fixture.bottom() }
        val reference = geometry(fixture)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle { fixture.bottom() }
        val actual = geometry(fixture)
        assertRowsMatch(reference, actual)
        compose.runOnIdle {
            assertEquals(fixture.frame.historyCount + 2, fixture.lazy.layoutInfo.totalItemsCount)
            assertEquals(1, fixture.stats.activeGrids)
            assertTrue(fixture.atBottom())
            assertTrue(actual.keys.containsAll(fixture.frame.screenLineIds))
            val id = fixture.frame.screenLineIds[2]
            val measured = actual.filterKeys { it in fixture.frame.screenLineIds }.map { (lineId, row) ->
                TerminalMeasuredViewportItem(lineId, row.top.roundToInt(), row.height,
                    screenGeneration = fixture.frame.screenGeneration)
            }
            val layout = fixture.anchorLayout().copy(measuredRows = measured,
                activeScreenItemTopPx = fixture.lazy.layoutInfo.visibleItemsInfo.single {
                    it.key == TerminalBenchmarkLayout.ACTIVE_SCREEN_KEY
                }.offset - fixture.lazy.layoutInfo.viewportStartOffset)
            val target = checkNotNull(terminalLazyTargetForAnchor(layout,
                ViewportAnchor(id, 5, fixture.frame.screenGeneration, null)))
            assertEquals(fixture.frame.historyCount, target.itemIndex)
            fixture.runScroll { fixture.lazy.scrollToItem(target.itemIndex, target.itemScrollOffsetPx) }
        }
        assertEquals(-5f, geometry(fixture).getValue(fixture.frame.screenLineIds[2]).top, 0.1f)
    }

    @Test
    fun naturalLazyStyledOutputAndArchivalKeepTheActualTailVisible() {
        val fixture = Fixture()
        mount(fixture)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle { fixture.bottom() }
        idle(fixture)
        repeat(12) { update ->
            compose.runOnIdle {
                fixture.terminal.feed((if (update % 2 == 0) "\r\u001B[2K" else "\r\n") + fixture.line(20_000 + update))
                fixture.publish(pinTail = true) // Same pre-measure tail request as Macrobenchmark.
            }
            idle(fixture)
            compose.runOnIdle {
                assertTrue("update $update", fixture.atBottom())
                assertEquals(1, fixture.stats.activeGrids)
            }
        }
        val lazy = geometry(fixture)
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        compose.runOnIdle { fixture.bottom() }
        assertRowsMatch(geometry(fixture), lazy)
    }

    @Test
    fun naturalLazyTuiAlternateFullGridAndHistoryClearKeepTheScreenComplete() {
        val fixture = Fixture()
        mount(fixture)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        for (fullGrid in listOf(false, true)) {
            compose.runOnIdle {
                fixture.configuredTui = !fullGrid
                fixture.fullGrid = fullGrid
            }
            idle(fixture)
            compose.runOnIdle { assertFalse(fixture.layout.useLazyHistory) }
            assertEquals(fixture.frame.rows.map { it.text }, texts())
        }
        compose.runOnIdle {
            fixture.configuredTui = false
            fixture.fullGrid = false
            fixture.terminal.feed("\u001B[?1049h" + (0 until 24).joinToString("\r\n") { fixture.line(30_000 + it) })
            fixture.publish()
        }
        idle(fixture)
        assertFalse(fixture.layout.useLazyHistory)
        assertEquals(24, fixture.frame.rows.size)
        assertEquals(fixture.frame.rows.map { it.text }, texts())
        compose.runOnIdle {
            fixture.terminal.feed("\u001B[?1049l")
            fixture.terminal.clearScrollbackOnly()
            fixture.publish()
        }
        idle(fixture)
        assertEquals(0, fixture.frame.historyCount)
        assertTrue(fixture.layout.useLazyHistory)
        assertEquals(2, fixture.lazy.layoutInfo.totalItemsCount)
        assertEquals(1, fixture.stats.activeGrids)
        assertEquals(fixture.frame.rows.map { it.text }, texts())
    }

    @Test
    fun naturalLazyDisjointRestoreUsesStableIdAndRejectsTrimmedOrClearedAnchors() {
        val fixture = Fixture()
        mount(fixture)
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[80], 7, null, fixture.frame.historyGeneration)
        pairAtAnchor(fixture, anchor)
        compose.runOnIdle { fixture.restoreHistory(anchor.copy(lineId = fixture.frame.historyLineIds[220])) }
        idle(fixture)
        compose.runOnIdle { fixture.restoreHistory(anchor) }
        idle(fixture)
        compose.runOnIdle {
            assertEquals(anchor, fixture.historyAnchor())
            fixture.terminal.setMaxScrollbackLines(100)
            fixture.publish()
        }
        idle(fixture)
        assertNull(terminalLazyTargetForAnchor(fixture.anchorLayout(), anchor))
        val retained = ViewportAnchor(fixture.frame.historyLineIds[50], 0, null, fixture.frame.historyGeneration)
        compose.runOnIdle {
            fixture.terminal.clearScrollbackOnly()
            fixture.publish()
        }
        idle(fixture)
        assertNull(terminalLazyTargetForAnchor(fixture.anchorLayout(), retained))
    }

    @Test
    fun naturalLazyOffscreenWidestRowRetainsTheProductionHorizontalRange() {
        val fixture = Fixture(wideFirst = true)
        mount(fixture)
        val productionRange = compose.runOnIdle { fixture.horizontal.maxValue }
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[130], 0, null, fixture.frame.historyGeneration)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle { fixture.restoreHistory(anchor) }
        idle(fixture)
        compose.runOnIdle {
            val lazyRange = fixture.horizontal.maxValue
            assertTrue(productionRange > 0)
            assertEquals("Offscreen history must still determine horizontal range", productionRange, lazyRange)
            assertTrue(fixture.stats.historyRows < fixture.frame.historyCount)
            fixture.runScroll { fixture.horizontal.scrollTo(productionRange / 2) }
        }
        idle(fixture)
        val pan = compose.runOnIdle { fixture.horizontal.value }
        compose.runOnIdle { fixture.bottom() }
        idle(fixture)
        compose.runOnIdle {
            assertEquals(productionRange, fixture.horizontal.maxValue)
            assertEquals(pan, fixture.horizontal.value)
        }
    }

    @Test
    fun naturalLazyWidestTrimmedRowNoLongerInflatesHorizontalRange() {
        val fixture = Fixture(wideFirst = true)
        mount(fixture)
        val originalRange = compose.runOnIdle { fixture.horizontal.maxValue }
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle {
            fixture.restoreHistory(ViewportAnchor(fixture.frame.historyLineIds[130], 7, null,
                fixture.frame.historyGeneration))
        }
        idle(fixture)
        compose.runOnIdle { fixture.runScroll { fixture.horizontal.scrollTo(originalRange) } }
        idle(fixture)
        compose.runOnIdle {
            fixture.terminal.feed("\r\n" + fixture.line(40_000))
            fixture.publish()
        }
        idle(fixture)
        val trimmedRange = compose.runOnIdle {
            assertTrue(fixture.horizontal.maxValue < originalRange)
            assertEquals(fixture.horizontal.maxValue, fixture.horizontal.value)
            fixture.horizontal.maxValue
        }
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        compose.runOnIdle {
            assertEquals(trimmedRange, fixture.horizontal.maxValue)
            assertEquals(trimmedRange, fixture.horizontal.value)
        }
    }

    @Test
    fun naturalLazyFontScaleAndRtlMatchHorizontalRangeAndRowPositions() {
        val fixture = Fixture(wideFirst = true, stressSpans = true)
        fixture.rtl = true
        mount(fixture)
        val anchor = ViewportAnchor(fixture.frame.historyLineIds[130], 7, null, fixture.frame.historyGeneration)
        pairAtAnchor(fixture, anchor)
        compose.runOnIdle {
            fixture.fontScale = 1.3f
            fixture.fontSp = 18
        }
        idle(fixture)
        compose.runOnIdle { fixture.runScroll { fixture.horizontal.scrollTo(53) } }
        idle(fixture)
        val lazyRange = compose.runOnIdle {
            assertEquals(53, fixture.horizontal.value)
            fixture.horizontal.maxValue
        }
        pairAtAnchor(fixture, anchor)
        compose.runOnIdle {
            assertEquals(lazyRange, fixture.horizontal.maxValue)
            assertEquals(53, fixture.horizontal.value)
        }
        // Also compare the complete physical-grid alignment, whose internal Column must use
        // the transcript width under RTL rather than its own shorter visible-screen maximum.
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        compose.runOnIdle { fixture.bottom() }
        val reference = geometry(fixture)
        val productionRange = fixture.horizontal.maxValue
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle { fixture.bottom() }
        assertRowsMatch(reference, geometry(fixture))
        assertEquals(productionRange, fixture.horizontal.maxValue)
    }

    @Test
    fun naturalLazyWidestLiveRowSurvivesArchivalAndExpiresWhenHistoryClears() {
        val fixture = Fixture()
        mount(fixture)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        val initialRange = compose.runOnIdle { fixture.horizontal.maxValue }
        compose.runOnIdle {
            fixture.terminal.feed("\r\u001B[2Kwide live " + "W".repeat(68))
            fixture.publish()
        }
        idle(fixture)
        val wideRange = compose.runOnIdle {
            assertTrue(fixture.horizontal.maxValue > initialRange)
            fixture.horizontal.maxValue
        }
        val liveId = fixture.frame.screenLineIds.last()
        val liveText = fixture.frame.rows.last().text.text
        assertEquals("wide live " + "W".repeat(68) + " ", liveText)
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        assertEquals("Live row width must match production", wideRange, fixture.horizontal.maxValue)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle {
            repeat(24) { fixture.terminal.feed("\r\n" + fixture.line(50_000 + it)) }
            fixture.publish()
        }
        idle(fixture)
        val archivedIndex = fixture.frame.historyLineIds.indexOf(liveId)
        assertTrue("The same live row must now belong to history", archivedIndex >= 0)
        // renderFrame includes the active cursor column even with an invisible cursor. Archiving
        // removes that trailing blank. Compare each phase against production, not against an
        // invalid assumption that the live/archived AnnotatedString widths are identical.
        assertEquals(liveText.dropLast(1), fixture.frame.rows[archivedIndex].text.text)
        val archivedRange = fixture.horizontal.maxValue
        assertTrue(archivedRange > initialRange)
        assertTrue(archivedRange < wideRange)
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        assertEquals("Archived row width must match production", archivedRange, fixture.horizontal.maxValue)
        use(fixture, BenchmarkRenderer.LAZY_HISTORY)
        compose.runOnIdle {
            fixture.terminal.clearScrollbackOnly()
            fixture.publish()
        }
        idle(fixture)
        val clearedRange = fixture.horizontal.maxValue
        assertTrue(clearedRange < archivedRange)
        use(fixture, BenchmarkRenderer.CHUNKED_LAYERS)
        assertEquals(clearedRange, fixture.horizontal.maxValue)
    }

    private class Fixture(private val stressSpans: Boolean = false, private val wideFirst: Boolean = false) {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 24, maxScrollbackLines = 260).apply {
            feed("\u001B[?25l" + (0 until 284).joinToString("\r\n") { line(it) })
        }
        var frame by mutableStateOf(render(), referentialEqualityPolicy())
            private set
        private val rows = createTerminalRenderedRows(frame)
        private val sync = createTerminalRenderedRowsSyncState(frame, rows)
        var renderer by mutableStateOf(BenchmarkRenderer.CHUNKED_LAYERS)
        var configuredTui by mutableStateOf(false)
        var fullGrid by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var fontSp by mutableStateOf(14)
        var rtl by mutableStateOf(false)
        var heightDp by mutableStateOf(260)
        val eager = ScrollState(0)
        val horizontal = ScrollState(0)
        val lazy = LazyListState(0)
        val stats = LazyCompositionStats()
        val layout get() = TerminalBenchmarkLayout.fromFrame(frame, renderer, configuredTui, fullGrid)
        var viewportTop = 0f
        var viewportLeft = 0f
        private lateinit var scope: CoroutineScope
        var operation: Job? = null
            private set
        var failure: Throwable? = null
            private set

        fun line(index: Int): String {
            val prefix = "r" + index.toString().padStart(5, '0') + " "
            if (wideFirst && index == 0) return prefix + "W".repeat(70)
            val content = when (index % 4) {
                0 -> "\u001B[1;32m中文\u001B[0m e\u0301 "
                1 -> "\u001B[33mASCII\u001B[0m "
                2 -> "\u001B]8;;https://example.com/$index\u001B\\link\u001B]8;;\u001B\\ "
                else -> "\u001B[3;35mtext\u001B[0m "
            }
            return prefix + content + "x".repeat(8 + index % 16)
        }

        private fun render(): TerminalEmulator.RenderFrame {
            val raw = terminal.renderFrame()
            if (!stressSpans) return raw
            val ids = raw.historyLineIds + raw.screenLineIds
            return raw.copy(rows = raw.rows.mapIndexed { index, row ->
                if (ids[index] % 7 != 0L) row else TerminalEmulator.RenderedRow(buildAnnotatedString {
                    append(row.text)
                    addStyle(SpanStyle(fontSize = 29.sp), 0, length)
                })
            })
        }

        fun publish(pinTail: Boolean = false) {
            val next = render()
            Snapshot.withMutableSnapshot {
                // Geometry observes committed frames; timed blank grace has separate host tests.
                synchronizeTerminalRenderedRows(rows, next, configuredTui || next.isAlternateScreen,
                    forcePendingGridBlanks = true, nowMs = 1_000L, syncState = sync)
                frame = next
            }
            if (pinTail) {
                check(layout.useLazyHistory)
                lazy.requestScrollToItem(layout.tailItemIndex)
            }
        }

        fun runScroll(block: suspend () -> Unit) {
            check(operation?.isActive != true) { "Overlapping fixture scroll requests" }
            operation = scope.launch {
                try { block() } catch (error: Throwable) { failure = error }
            }
        }

        fun bottom() = runScroll {
            if (layout.useLazyHistory) lazy.scrollToItem(layout.tailItemIndex) else eager.scrollTo(eager.maxValue)
        }

        fun anchorLayout() = TerminalLazyViewportLayout(frame.historyLineIds, frame.screenLineIds,
            frame.historyGeneration, frame.screenGeneration, emptyList(), tailItemHeightPx = 1, viewportHeightPx = 0)

        fun restoreHistory(anchor: ViewportAnchor) {
            val target = checkNotNull(terminalLazyTargetForAnchor(anchorLayout(), anchor))
            runScroll { lazy.scrollToItem(target.itemIndex, target.itemScrollOffsetPx) }
        }

        fun historyAnchor(): ViewportAnchor {
            val info = lazy.layoutInfo
            val row = info.visibleItemsInfo.first { it.key is Long && it.offset + it.size > info.viewportStartOffset }
            return ViewportAnchor(row.key as Long, (info.viewportStartOffset - row.offset).coerceIn(0, row.size - 1),
                null, frame.historyGeneration)
        }

        fun atBottom(): Boolean = !lazy.canScrollForward && lazy.layoutInfo.visibleItemsInfo.any {
            it.key == TerminalBenchmarkLayout.TAIL_KEY && it.offset + it.size <= lazy.layoutInfo.viewportEndOffset
        }

        @Composable
        fun Content() {
            scope = rememberCoroutineScope()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme {
                    val style = TextStyle(fontFamily = JetbrainsMono, fontSize = fontSp.sp, lineHeight = fontSp.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
                    TerminalBenchmarkViewport(frame, rows.toList(), layout, style, eager, horizontal, lazy, stats,
                        Modifier.size(320.dp, heightDp.dp).onGloballyPositioned {
                            viewportTop = it.positionInRoot().y
                            viewportLeft = it.positionInRoot().x
                        })
                }
            }
        }
    }
}
