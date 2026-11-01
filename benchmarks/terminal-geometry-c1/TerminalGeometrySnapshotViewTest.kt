package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.container.TerminalFullVirtualPolicy
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalPhysicalRowHeights
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalVisibleRow
import me.rerere.rikkahub.data.container.TerminalVisibleRowSnapshot
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Real Compose1.11 layouts; checks geometry/proof sharing, not frame timing or performance. */
class TerminalGeometrySnapshotViewTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(300)
    private fun model(size: Int = 1000) = TerminalEmulator(80, 24, size).apply {
        feed("\u001b[?25l" + (0 until size + 24).joinToString("\r\n") { "row-$it 中文 e\u0301 👩‍💻" } + "\r")
    }
    private fun fixture(size: Int = 1000, terminal: TerminalEmulator = model(size), stress: Boolean = false,
        mode: TerminalRenderMode = TerminalRenderMode.FULL_VIRTUAL) = Fixture(
        stressSpans = stress, terminalOverride = terminal, initialMode = mode,
        restored = TerminalViewportState(autoScroll = true), foreground = Color(0xFF00E676),
    ).apply { tui = mode.isFullVirtual; fontSp = 12; heightDp = 240; densityOverride = Density(1f) }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val s = f.controller.state.value
            s.initialized && s.gesture == null && s.scrollEffect == null && f.activeWriters == 0 &&
                f.bound.pass.frame === f.frame && (if (f.isVirtual) f.observation()?.ready == true
                    else f.measurements.peekEager(f.bound.pass)?.frame === f.frame)
        }
        compose.waitForIdle(); assertTrue(f.maximumWriters <= 1)
        if (f.mode.isFullVirtual) {
            assertTrue(f.bound.binding.fullVirtual); assertTrue(f.rowStore.retainedRowStates <= 80)
            assertTrue(f.measurements.retainedRows <= 128); assertEquals(0, f.measurements.retainedEagerHistoryRows)
        }
    }
    private fun oracle(f: Fixture): TerminalItemViewport {
        val observation = requireNotNull(f.observation()); val info = f.lazy.layoutInfo
        assertTrue(observation.rows is TerminalVisibleRowSnapshot)
        assertTrue((observation.rows as TerminalVisibleRowSnapshot).validatedFor(f.frame.rows.size))
        val live = info.visibleItemsInfo.filter { it.index in f.frame.rows.indices }.map {
            TerminalVisibleRow(it.index, it.offset - info.viewportStartOffset, it.size)
        }
        assertEquals(live, observation.rows)
        val plain = observation.copy(rows = live)
        assertEquals(plain, observation); assertEquals(plain.capture(), observation.capture())
        assertEquals(plain.followDelta(), observation.followDelta()); assertEquals(plain.physicalScreenTop, observation.physicalScreenTop)
        assertEquals(plain.isSatisfied(TerminalItemScrollTarget.Follow), observation.isSatisfied(TerminalItemScrollTarget.Follow))
        for (i in f.frame.screenLineIds.indices) {
            val index = f.frame.historyCount + i
            assertEquals(plain.measuredRow(index), observation.measuredRow(index))
        }
        return observation
    }
    private fun record(scene: String, f: Fixture, reused: Int = 0, rejected: Int = 0) {
        val observation = f.observation()
        Log.i("TerminalGeometryC1", "GEOMETRY_C1_VIEW " + JSONObject().put("scene", scene)
            .put("history", f.frame.historyCount).put("alternate", f.frame.isAlternateScreen)
            .put("virtual", f.isVirtual).put("fullVirtual", f.bound.binding.fullVirtual)
            .put("visibleRows", observation?.rows?.size ?: 0).put("measuredRows", f.measurements.retainedRows)
            .put("maximumWriters", f.maximumWriters).put("reusedObservations", reused).put("rejectedReads", rejected)
            .put("geometryChecked", true).put("performanceMeasured", false))
    }
    @Test fun repeatedPoliciesReuseOnlyOneCompletedFullGeometry() {
        val f = fixture(); compose.setContent { f.Content() }; settle(f)
        compose.runOnIdle {
            val current = oracle(f); val policy = requireNotNull(current.fullVirtual)
            repeat(256) { assertSame(current, current.withFullVirtualPolicy(policy.copy())) }
            val otherRead = oracle(f)
            assertNotSame(current, otherRead); assertNotSame(current.rows, otherRead.rows)
            val changed = current.withFullVirtualPolicy(policy.copy(avoidIme = !policy.avoidIme))
            assertNotSame(current, changed); assertSame(current.rows, changed.rows)
            record("same-layout-policy", f, reused = 256)
        }
    }
    @Test fun synchronousLazyOffsetMutationAlwaysReadsFreshDetachedGeometry() {
        val f = fixture(); compose.setContent { f.Content() }; settle(f)
        compose.runOnIdle {
            val before = oracle(f); val stableRows = before.rows.toList()
            assertTrue(f.lazy.canScrollBackward)
            val consumed = f.lazy.dispatchRawDelta(-3f); assertTrue(consumed != 0f)
            val after = oracle(f)
            assertSame(before.frame, after.frame); assertNotSame(before.rows, after.rows)
            assertNotEquals(before.rows, after.rows); assertEquals(stableRows, before.rows)
            record("synchronous-scroll", f)
        }
        settle(f)
    }
    @Test fun invalidMetricsKeysTextAndPhysicalHeightsStillRejectLiveReads() {
        val f = fixture(); compose.setContent { f.Content() }; settle(f)
        compose.runOnIdle {
            val current = oracle(f); val policy = requireNotNull(current.fullVirtual); val binding = f.bound.binding
            fun read(pass: TerminalLazyLayoutPass = f.bound.pass, p: TerminalFullVirtualPolicy = policy,
                displayed: List<TerminalRenderedRowState> = f.rows) = f.measurements.readFull(pass, f.lazy,
                f.cellHeight, f.tailPadding, p, displayed)
            assertNull(read(TerminalLazyLayoutPass(f.frame, Any())))
            val mismatched = f.frame.copy(screenLineIds = f.frame.screenLineIds.map { it + 1_000_000_000L })
            assertNull(read(TerminalLazyLayoutPass(mismatched, f.bound.pass.metricKey)))
            val wrong = policy.copy(screen = TerminalPhysicalRowHeights(IntArray(24) { policy.screen.rowHeight(it) + 1 }))
            assertNull(read(p = wrong))
            val rows = f.rows; val target = current.rows.first { it.index >= f.frame.historyCount }.index
            val changed = TerminalRenderedRowState(rows[target].lineId, AnnotatedString("not measured yet"))
            val displayed = object : AbstractList<TerminalRenderedRowState>() {
                override val size: Int get() = rows.size
                override fun get(index: Int): TerminalRenderedRowState = if (index == target) changed else rows[index]
            }
            assertNull(read(displayed = displayed)); assertNotNull(read())
            assertTrue(binding.fullVirtual); record("rejected-live-inputs", f, rejected = 4)
        }
    }
    @Test fun mainAltAndFifoNeverReusePreviousLayoutVector() {
        val f = fixture(10000); compose.setContent { f.Content() }; settle(f)
        var previous = compose.runOnIdle { oracle(f) }
        val phases = listOf(
            "main-edit" to "\u001b[3;1H\u001b[2Kupdated 中文 👩‍💻",
            "alt-enter" to "\u001b[?1049h\u001b[?25lALT",
            "alt-redraw" to "\u001b[4;1H\u001b[2Kalternate e\u0301",
            "main-return" to "\u001b[?1049l",
            "fifo" to "\u001b[24;1H\r\narchive",
        )
        for ((scene, command) in phases) {
            val oldRows = previous.rows.toList()
            compose.runOnIdle { f.terminal.feed(command); f.publish() }; settle(f)
            compose.runOnIdle {
                val next = oracle(f); assertNotSame(previous.frame, next.frame); assertNotSame(previous.rows, next.rows)
                assertEquals(oldRows, previous.rows); record(scene, f); previous = next
            }
        }
    }
    @Test fun fontDensityDirectionAndViewportKeepActualMixedHeights() {
        val f = fixture(stress = true); compose.setContent { f.Content() }; settle(f)
        val phases = listOf<Pair<String, () -> Unit>>(
            "font-size" to { f.fontSp = 19 }, "density" to { f.densityOverride = Density(1.33f, 1.3f) },
            "rtl" to { f.rtl = true }, "custom-font" to { f.fontFamily = FontFamily.Serif },
            "viewport" to { f.heightDp = 170 }, "ime-policy" to { f.ime = true; f.avoidIme = false },
        )
        for ((scene, change) in phases) {
            val previous = compose.runOnIdle { oracle(f) }; val frozen = previous.rows.toList()
            compose.runOnIdle(change); settle(f)
            compose.runOnIdle { oracle(f); assertEquals(frozen, previous.rows); record(scene, f) }
        }
    }
    @Test fun oldHistoryBackendAndDefaultNeverGainFullSnapshotProof() {
        val f = fixture(mode = TerminalRenderMode.VIRTUAL_HISTORY)
        compose.setContent { f.Content() }; settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual); assertFalse(f.bound.binding.fullVirtual)
            assertFalse(f.observation()!!.rows is TerminalVisibleRowSnapshot); record("legacy-history", f)
            f.mode = TerminalRenderMode.DEFAULT
        }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); assertNotNull(f.bound.binding.eagerGeometry()); record("default-eager", f) }
    }
    @Test fun sessionReplacementNeverRetainsOldSnapshotOrWriter() {
        val first = fixture(); var shown by mutableStateOf(first, referentialEqualityPolicy())
        compose.setContent { shown.Content() }; settle(first)
        val old = compose.runOnIdle { oracle(first) }; val values = old.rows.toList(); val next = fixture(500)
        compose.runOnIdle { shown = next }; settle(next)
        compose.runOnIdle {
            val current = oracle(next); assertNotSame(old.frame, current.frame); assertNotSame(old.rows, current.rows)
            assertEquals(values, old.rows); assertEquals(0, first.activeWriters); assertEquals(0, first.measurements.retainedRows)
            assertFalse(first.bound.binding.widthIndex.hasRetainedState); record("session-replacement", next)
        }
    }
}
