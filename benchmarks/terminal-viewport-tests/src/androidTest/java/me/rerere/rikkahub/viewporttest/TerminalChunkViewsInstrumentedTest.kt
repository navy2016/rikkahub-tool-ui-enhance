package me.rerere.rikkahub.viewporttest

import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.rememberTerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Count actual production transcript commits and factory calls, not just skipped Text rows. */
class TerminalChunkViewsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(90)

    private class Counts : TerminalTranscriptCompositionObserver {
        var entries = 0
        var cachedEntries = 0
        var wrappers = 0
        var slices = 0
        var live = 0
        var historyRows = 0
        var screenRows = 0
        override fun historyChunkDelta(chunks: Int, rows: Int) = Unit
        override fun activeScreenDelta(screens: Int) = Unit
        override fun transcriptComposed(cached: Boolean) { entries++; if (cached) cachedEntries++ }
        override fun chunkViewsCreated(chunks: Int, slices: Int) { wrappers += chunks; this.slices += slices }
        override fun cachedViewDelta(delta: Int) { live += delta }
    }

    private class Fixture(size: Int = 260) {
        val terminal = TerminalEmulator(80, 6, size).apply {
            feed("\u001B[?25l" + (0 until size + 6 + 125).joinToString("\r\n") { "row-$it 中文 e\u0301" })
        }
        private val initial = terminal.renderFrame()
        val rows = createTerminalRenderedRows(initial)
        private val sync = createTerminalRenderedRowsSyncState(initial, rows)
        var source by mutableStateOf(TerminalRenderSnapshot.capture(initial, rows), referentialEqualityPolicy())
        val frame get() = source.frame
        val counts = Counts()
        private var historyIds = initial.historyLineIds.toHashSet()
        val measurements = TerminalLazyItemMeasurements().apply {
            onRowComposed = { if (it in historyIds) counts.historyRows++ else counts.screenRows++ }
        }
        val controller = TerminalViewportController()
        val eager = ScrollState(Int.MAX_VALUE)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        var cached by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        var mode by mutableStateOf(TerminalRenderMode.DEFAULT)
        var selection by mutableStateOf(false)
        var ime by mutableStateOf(false)
        var font by mutableIntStateOf(12)
        var pulse by mutableIntStateOf(0)
        var height by mutableIntStateOf(0)
        lateinit var bound: TerminalBoundViewport

        fun publish() {
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100, syncState = sync)
                source = TerminalRenderSnapshot.capture(next, rows)
            }
            historyIds = next.historyLineIds.toHashSet()
        }

        @Composable fun Content() {
            if (mounted) Output()
        }

        @Composable private fun Output() {
            val currentSource = source
            val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono,
                fontSize = font.sp, lineHeight = font.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            val density = LocalDensity.current
            val cell = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            val tail = with(density) { 8.dp.roundToPx() }
            val plan = remember(currentSource.frame.historyCount, currentSource.frame.historyStartSequence) {
                terminalHistoryChunks(currentSource.frame.historyCount, currentSource.frame.historyStartSequence)
            }
            val policy = rememberTerminalVirtualHistoryPolicy(this, mode, ime)
            val wants = mode.isVirtualHistory && policy.allows(plan.isNotEmpty(), true, selection, false, ime, true)
            val current = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements,
                currentSource.frame, style, wants,
                metrics = { TerminalViewportMetrics(eager.maxValue, height, cell, tail,
                    imeVisible = ime, avoidIme = ime) },
                gestureConfig = TerminalViewportGestureConfig(true, selection, 2))
            bound = current
            // Force both arms to ENTER the real transcript once per update using a new observer
            // identity, not a changed Text/style/row list. These are work counts, not timing claims.
            val observer = remember(pulse) { object : TerminalTranscriptCompositionObserver by counts {} }
            Box(Modifier.size(320.dp, if (ime) 160.dp else 220.dp).testTag("chunk-output")
                .onSizeChanged { height = it.height }) {
                TerminalTranscriptViewport(currentSource.frame, currentSource.rows, style, plan, mode, current,
                    horizontal, true, selection, Modifier.fillMaxSize(),
                    rowSource = currentSource.takeIf { cached }, compositionObserver = observer)
            }
        }
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.bound.pass.frame === f.frame && f.controller.state.value.initialized &&
                f.controller.state.value.scrollEffect == null && f.bound.binding.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(f.bound.binding.maximumWriters <= 1) }
    }

    private fun assertBottom(f: Fixture) {
        val expected = compose.runOnIdle { f.frame.rows[requireNotNull(f.frame.contentBounds.lastNonBlankRow)].text }
        val row = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(expected)),
            useUnmergedTree = true).fetchSemanticsNode()
        val view = compose.onNodeWithTag("chunk-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - view.positionInRoot.y
        assertTrue("bottom row clipped", top >= -1f && top + row.size.height <= view.size.height + 1f)
    }

    private data class Row(val text: AnnotatedString, val x: Float, val y: Float, val width: Int, val height: Int)
    private data class Geometry(val rows: List<Row>, val vertical: Pair<Int, Int>, val horizontal: Pair<Int, Int>)

    private fun geometry(f: Fixture): Geometry {
        settle(f)
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().map {
                Row(it.config[SemanticsProperties.Text].single(), it.positionInRoot.x, it.positionInRoot.y,
                    it.size.width, it.size.height)
            }
        return compose.runOnIdle {
            assertEquals(f.frame.rows.map { it.text }, rows.map { it.text })
            Geometry(rows, f.eager.value to f.eager.maxValue, f.horizontal.value to f.horizontal.maxValue)
        }
    }

    private fun compareWithUncached(f: Fixture) {
        val before = geometry(f)
        compose.runOnIdle { f.cached = false }
        assertEquals(before, geometry(f))
        compose.runOnIdle { assertEquals(0, f.counts.live); f.cached = true }
        assertEquals(before, geometry(f))
        compose.runOnIdle { assertEquals(1, f.counts.live) }
        assertBottom(f)
    }

    private fun repeated(size: Int) {
        val f = Fixture(size).apply { cached = false }
        compose.setContent { f.Content() }
        settle(f)
        for (cached in listOf(false, true)) {
            compose.runOnIdle { f.cached = cached }
            settle(f)
            val original = compose.runOnIdle { f.source }
            val count = terminalHistoryChunks(original.frame.historyCount, original.frame.historyStartSequence).size
            val baseline = compose.runOnIdle { listOf(f.counts.entries, f.counts.wrappers, f.counts.slices,
                f.counts.historyRows, f.counts.screenRows, f.counts.cachedEntries) }
            repeat(32) { update ->
                compose.runOnIdle { f.pulse++ }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertEquals(update + 1, f.counts.entries - baseline[0])
                    assertSame(original, f.source)
                    assertEquals(if (cached) 1 else 0, f.counts.live)
                }
            }
            assertBottom(f)
            compose.runOnIdle {
                assertEquals(if (cached) 0 else 32 * count, f.counts.wrappers - baseline[1])
                assertEquals(if (cached) 0 else 32 * (count + 1), f.counts.slices - baseline[2])
                assertEquals(if (cached) 32 else 0, f.counts.cachedEntries - baseline[5])
                Log.i("TerminalChunkViews", "CHUNK_VIEWS_WORK " + JSONObject()
                    .put("size", size).put("cached", cached).put("chunks", count).put("updates", 32)
                    .put("firstSequence", requireNotNull(original.frame.historyStartSequence))
                    .put("frameRows", original.rows.size)
                    .put("entries", f.counts.entries - baseline[0])
                    .put("cachedEntries", f.counts.cachedEntries - baseline[5])
                    .put("wrappers", f.counts.wrappers - baseline[1]).put("slices", f.counts.slices - baseline[2])
                    .put("historyRowCompositions", f.counts.historyRows - baseline[3])
                    .put("screenRowCompositions", f.counts.screenRows - baseline[4])
                    .put("sourceIdentityChecks", 32))
            }
        }
    }

    @Test fun chunkViewsOneThousandRepeatedEntriesDoNotAllocateSlices() = repeated(1_000)
    @Test fun chunkViewsFiveThousandRepeatedEntriesDoNotAllocateSlices() = repeated(5_000)
    @Test fun chunkViewsTenThousandRepeatedEntriesDoNotAllocateSlices() = repeated(10_000)

    @Test fun chunkViewsActiveTextAndFontChangesKeepViewsButUpdateRealRows() {
        val f = Fixture()
        compose.setContent { f.Content() }
        settle(f)
        val original = compose.runOnIdle { f.source }
        val baseline = f.counts.wrappers
        val historyBefore = f.counts.historyRows
        val screenBefore = f.counts.screenRows
        repeat(8) { update ->
            compose.runOnIdle {
                f.terminal.feed("\r\u001B[2Kactive-$update 中文")
                f.publish()
            }
            settle(f)
            compose.runOnIdle {
                assertNotSame(original, f.source)
                assertSame(original.rows, f.source.rows)
                assertEquals(baseline, f.counts.wrappers)
            }
            assertBottom(f)
        }
        compose.runOnIdle {
            assertEquals("active output recomposed archived rows", historyBefore, f.counts.historyRows)
            assertTrue("changed active row was not observed", f.counts.screenRows > screenBefore)
        }
        compose.runOnIdle { f.font = 19 }
        settle(f)
        compose.runOnIdle { assertEquals(baseline, f.counts.wrappers) }
        compareWithUncached(f)
    }

    @Test fun chunkViewsSameIdReplacementFifoAndClearMatchUncachedGeometry() {
        val f = Fixture()
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle {
            val original = f.rows[0]
            val text = AnnotatedString("replacement 同 ID")
            val next = f.frame.copy(rows = f.frame.rows.mapIndexed { index, row ->
                if (index == 0) TerminalEmulator.RenderedRow(text) else row
            })
            Snapshot.withMutableSnapshot {
                f.rows[0] = TerminalRenderedRowState(original.lineId, text)
                f.source = TerminalRenderSnapshot.capture(next, f.rows)
            }
            assertNotSame(original, f.rows[0])
        }
        compareWithUncached(f)
        repeat(6) { update ->
            compose.runOnIdle { f.terminal.feed("\r\nappend-$update 中文"); f.publish() }
            compareWithUncached(f)
        }
        compose.runOnIdle { f.terminal.clearScrollbackOnly(); f.publish() }
        geometry(f)
        compose.runOnIdle { assertEquals(0, f.counts.live) }
        assertBottom(f)
    }

    @Test fun chunkViewsFlatVirtualDetachAndRemountReleaseTheCurrentCacheEntry() {
        val f = Fixture()
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle { assertEquals(1, f.counts.live); f.mode = TerminalRenderMode.FLAT }
        settle(f)
        compose.runOnIdle { assertEquals(0, f.counts.live); f.mode = TerminalRenderMode.DEFAULT }
        settle(f)
        compose.runOnIdle { assertEquals(1, f.counts.live); f.mode = TerminalRenderMode.VIRTUAL_HISTORY }
        settle(f)
        compose.runOnIdle { assertTrue(f.bound.virtualHistoryEnabled); assertEquals(0, f.counts.live); f.ime = true }
        settle(f)
        compose.runOnIdle {
            assertFalse(f.bound.virtualHistoryEnabled)
            assertEquals(1, f.counts.live)
            f.mounted = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, f.counts.live); assertEquals(0, f.measurements.retainedRows) }
        val before = f.counts.wrappers
        compose.runOnIdle { f.ime = false; f.mode = TerminalRenderMode.DEFAULT; f.mounted = true }
        settle(f)
        compose.runOnIdle { assertEquals(1, f.counts.live); assertTrue(f.counts.wrappers > before) }
        assertBottom(f)
    }

    @Test fun chunkViewsCachedSelectionCopiesTheSameWordThroughTheSystemMenu() {
        val f = Fixture().apply {
            terminal.feed("\r\u001B[2Kcopyword 中文 e\u0301")
            publish()
            font = 20
            selection = true
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val copyLabel = compose.activity.getString(android.R.string.copy)
        compose.setContent { f.Content() }
        for (cached in listOf(false, true)) {
            compose.runOnIdle { f.selection = false; f.cached = cached; clipboard.clearPrimaryClip() }
            settle(f)
            compose.runOnIdle { f.selection = true }
            settle(f)
            assertBottom(f)
            val matcher = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(f.frame.rows.last().text))
            val row = compose.onNode(matcher, useUnmergedTree = true)
            val node = row.fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle {
                assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            }
            val point = layouts.single().getBoundingBox(3).center
            row.performTouchInput { longClick(point) }
            checkNotNull(device.wait(Until.findObject(By.text(copyLabel)), 5_000)).click()
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyword" }
        }
    }
}
