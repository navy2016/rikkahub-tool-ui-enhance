package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.ui.pages.container.TerminalRowWidthMeasurer
import me.rerere.rikkahub.ui.pages.container.TerminalTextMetrics
import me.rerere.rikkahub.ui.pages.container.checkpointProof
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Real-font correctness and lifecycle counts, NOT test-clock latency or performance acceptance. */
class TerminalMainWidthLifecycleViewTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(360)
    private fun terminal(size: Int) = TerminalEmulator(80, 24, size).apply {
        feed("\u001b[?25l" + (0 until size + 24).joinToString("\r\n") {
            if (it == 0) "W".repeat(79) else "line-$it 中文 e\u0301 👩‍💻"
        } + "\r")
    }
    private fun fixture(terminal: TerminalEmulator, checkpoint: TerminalWidthCheckpointStore? = null) =
        Fixture(stressSpans = false, initialMode = TerminalRenderMode.FULL_VIRTUAL,
            terminalOverride = terminal, restored = TerminalViewportState(autoScroll = true),
            foreground = Color(0xFF00E676), widthCheckpoint = checkpoint).apply {
            tui = true; fontSp = 12; heightDp = 420; densityOverride = Density(1f)
        }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val state = f.controller.state.value
            state.initialized && state.gesture == null && state.scrollEffect == null && f.activeWriters == 0 &&
                f.bound.pass.frame === f.frame && f.observation()?.ready == true &&
                f.bound.binding.widthIndex.readyFor(f.frame, f.bound.pass.metricKey)
        }
        compose.waitForIdle()
        assertTrue(f.isVirtual); assertTrue(f.bound.binding.fullVirtual)
        assertTrue(f.maximumWriters <= 1); assertTrue(f.rowStore.retainedRowStates <= 80)
        assertTrue(f.measurements.retainedRows <= 128); assertEquals(0, f.measurements.retainedEagerHistoryRows)
    }
    private fun record(f: Fixture, scene: String, delta: Long) = compose.runOnIdle {
        val width = f.bound.binding.widthIndex
        Log.i("TerminalMainWidth", "MAIN_WIDTH_VIEW " + JSONObject().put("scene", scene)
            .put("historyRows", f.frame.historyCount).put("alternate", f.frame.isAlternateScreen)
            .put("measuredHistoryDelta", delta).put("totalHistoryMeasurements", width.measuredHistoryRows)
            .put("scalarCandidates", width.retainedCandidates).put("screenWidthKeys", width.retainedScreenRows)
            .put("horizontalMaximum", f.horizontal.maxValue).put("measuredRows", f.measurements.retainedRows)
            .put("rowStates", f.rowStore.retainedRowStates).put("writers", f.maximumWriters)
            .put("performanceMeasured", false))
    }
    private fun step(f: Fixture, scene: String, expected: Long, action: () -> Unit) {
        val before = compose.runOnIdle { f.bound.binding.widthIndex.measuredHistoryRows }
        compose.runOnIdle(action); settle(f)
        val delta = compose.runOnIdle { f.bound.binding.widthIndex.measuredHistoryRows - before }
        assertEquals(scene, expected, delta); record(f, scene, delta)
    }
    private fun enter(f: Fixture) {
        f.terminal.feed("\u001b[?1049h\u001b[?25l\u001b[2J\u001b[Halt\r"); f.publish()
    }
    private fun leave(f: Fixture) { f.terminal.feed("\u001b[?1049l"); f.publish() }
    private fun oracle(f: Fixture) = compose.runOnIdle {
        val metrics = f.bound.pass.metricKey as TerminalTextMetrics
        val measurer = TerminalRowWidthMeasurer(f.frame, resolveDefaults(metrics.style, metrics.direction),
            metrics.density, metrics.resolver)
        val maximum = f.frame.rows.maxOfOrNull { measurer.width(it.text) } ?: 0
        assertEquals("Natural full-scan oracle versus actual horizontal layout", (maximum - f.viewportWidth).coerceAtLeast(0),
            f.horizontal.maxValue)
    }

    @Test fun viewMainAltRoundTripsAvoidHistoryReshapingAtOneAndTenThousand() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { Box(Modifier.background(Color.Black)) { shown?.Content() } }
        for (size in listOf(1000, 10000)) {
            val f = fixture(terminal(size))
            try {
                compose.runOnIdle { shown = f }; settle(f); oracle(f)
                val width = compose.runOnIdle { f.horizontal.maxValue }; assertTrue(width > 0)
                assertEquals(size.toLong(), f.bound.binding.widthIndex.measuredHistoryRows)
                repeat(3) { round ->
                    step(f, "$size-enter-$round", 0) { enter(f) }; oracle(f)
                    assertEquals(0, f.horizontal.maxValue); assertEquals(0, f.bound.binding.widthIndex.retainedScreenRows)
                    step(f, "$size-return-$round", 0) { leave(f) }; oracle(f)
                    assertEquals(width, f.horizontal.maxValue)
                }
                step(f, "$size-fifo", 1) { f.terminal.feed("\u001b[24;1H\r\nnew\r"); f.publish() }
                oracle(f); assertTrue(f.horizontal.maxValue < width)
            } finally { compose.runOnIdle { shown = null }; compose.waitForIdle() }
            assertFalse(f.bound.binding.widthIndex.hasRetainedState); assertEquals(0, f.measurements.retainedRows)
        }
    }

    @Test fun viewFontScaleRtlAndColumnChangesWhileAlternateRevokeTheOldHistoryProof() {
        val size = 1000; val f = fixture(terminal(size)); compose.setContent { f.Content() }; settle(f)
        val edits: List<Pair<String, () -> Unit>> = listOf(
            "font" to { f.fontSp = 14 }, "scale" to { f.densityOverride = Density(1f, 1.3f) },
            "rtl" to { f.rtl = true }, "columns" to { f.terminal.resize(72, 24); f.publish() })
        for ((name, edit) in edits) {
            step(f, "$name-enter", 0) { enter(f) }; step(f, "$name-alt-edit", 0, edit)
            assertEquals(0, f.bound.binding.widthIndex.retainedCandidates)
            step(f, "$name-return", size.toLong()) { leave(f) }; oracle(f)
        }
    }

    @Test fun viewCheckpointSavedInAltSurvivesSameSessionRemountAndRejectsNewOwner() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        val model = terminal(1000); val store = TerminalWidthCheckpointStore(); val first = fixture(model, store)
        compose.runOnIdle { shown = first }; settle(first); step(first, "remount-enter", 0) { enter(first) }
        compose.runOnIdle { shown = null }; compose.waitForIdle()
        assertTrue(store.retainedRows() in 1..1000); assertFalse(first.bound.binding.widthIndex.hasRetainedState)
        val next = fixture(model, store); compose.runOnIdle { shown = next }; settle(next)
        step(next, "remount-return", 0) { leave(next) }; oracle(next)
        assertEquals(0L, next.bound.binding.widthIndex.measuredHistoryRows)
        compose.runOnIdle { shown = null }; compose.waitForIdle()
        val replaced = fixture(terminal(1000), store); compose.runOnIdle { shown = replaced }; settle(replaced)
        assertEquals(1000L, replaced.bound.binding.widthIndex.measuredHistoryRows); oracle(replaced)
        compose.runOnIdle { shown = null }; compose.waitForIdle(); store.close(); assertEquals(0, store.retainedRows())
    }

    @Test fun viewAlternateTrimAndUnpublishedSuffixUseActualSurvivingHistoryOnReturn() {
        val f = fixture(terminal(1000)); compose.setContent { f.Content() }; settle(f)
        val wide = f.horizontal.maxValue
        step(f, "unpublished-suffix-enter", 0) {
            repeat(12) { f.terminal.feed("\r\nnew-$it\r") }; enter(f)
        }
        step(f, "suffix-return", 12) { leave(f) }; oracle(f); assertTrue(f.horizontal.maxValue < wide)
        step(f, "trim-enter", 0) { enter(f) }
        step(f, "trim-hidden", 0) { f.terminal.setMaxScrollbackLines(128); f.publish() }
        step(f, "trim-return", 0) { leave(f) }; oracle(f)
        assertEquals(128, f.frame.historyCount); assertTrue(f.bound.binding.widthIndex.retainedCandidates <= 128)
    }

    @Test fun viewCustomFontKeepsCompatibilityAndSameRevisionPublicationKeepsExactReadiness() {
        val f = fixture(terminal(129)); compose.setContent { f.Content() }; settle(f)
        step(f, "same-revision", 0) { f.publish() }; oracle(f)
        step(f, "custom-main", 129) { f.fontFamily = FontFamily.Serif }; oracle(f)
        val metrics = f.bound.pass.metricKey as TerminalTextMetrics; assertNull(metrics.checkpointProof())
        step(f, "custom-enter", 0) { enter(f) }; assertEquals(0, f.bound.binding.widthIndex.retainedCandidates)
        step(f, "custom-return", 129) { leave(f) }; oracle(f)
    }
}
