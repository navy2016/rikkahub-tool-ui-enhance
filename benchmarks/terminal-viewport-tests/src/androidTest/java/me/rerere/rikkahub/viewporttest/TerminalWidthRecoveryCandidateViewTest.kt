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
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** P2 candidate-only generated overlay. Exact work/invalidation, not test-clock latency. */
class TerminalWidthRecoveryCandidateViewTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(360)

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val s = f.controller.state.value
            s.initialized && s.gesture == null && s.scrollEffect == null && f.activeWriters == 0 &&
                f.bound.pass.frame === f.frame && f.observation()?.ready == true &&
                f.bound.binding.widthIndex.readyFor(f.frame, f.bound.pass.metricKey)
        }
        compose.waitForIdle()
        assertTrue(f.isVirtual)
        assertTrue(f.rowStore.retainedRowStates <= 80)
        assertTrue(f.measurements.retainedRows <= 128)
        assertTrue(f.maximumWriters <= 1)
        assertEquals(0, f.measurements.retainedEagerHistoryRows)
    }

    @Test fun candidateMainAlternateWidthRecoveryCounters() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { Box(Modifier.background(Color.Black)) { shown?.Content() } }
        var records = 0
        for (size in listOf(1_000, 10_000)) {
            val terminal = TerminalEmulator(80, 24, size).apply {
                feed("\u001b[?25l" + (0 until size + 24).joinToString("\r\n") {
                    "main-${it.toString().padStart(5, '0')} 中文 e\u0301 👩‍💻"
                } + "\r")
            }
            val checkpoint = TerminalWidthCheckpointStore()
            fun fixture() = Fixture(stressSpans = false, initialMode = TerminalRenderMode.FULL_VIRTUAL,
                terminalOverride = terminal, restored = TerminalViewportState(autoScroll = true),
                foreground = Color(0xFF00E676), widthCheckpoint = checkpoint).apply {
                tui = true; fontSp = 12; heightDp = 420; densityOverride = Density(1f)
            }
            val f = fixture()
            try {
                compose.runOnIdle { shown = f }; settle(f)
                val initialHistory = f.frame.ownedRows()!!.history
                assertEquals(size, f.frame.historyCount)
                // Reflective probe keeps the shared test sources compile-compatible with baseline.
                fun parked(): Int {
                    val index = f.bound.binding.widthIndex
                    return index.javaClass.getMethod("getRetainedParkedHistoryCandidates").invoke(index) as Int
                }
                fun record(scene: String, before: Long, unchangedHistory: Boolean? = null) {
                    val width = f.bound.binding.widthIndex
                    val delta = width.measuredHistoryRows - before
                    assertTrue(delta >= 0)
                    Log.i("TerminalWidthRecovery", "WIDTH_RECOVERY_CANDIDATE " + JSONObject()
                        .put("size", size).put("scene", scene).put("historyRows", f.frame.historyCount)
                        .put("alternate", f.frame.isAlternateScreen).put("acceptedHistoryMeasurements", delta)
                        .put("totalAcceptedHistoryMeasurements", width.measuredHistoryRows)
                        .put("requiredHistoryAfterReady", width.requiredHistoryMeasurements(f.frame, f.bound.pass.metricKey))
                        .put("scalarCandidates", width.retainedCandidates).put("screenWidthKeys", width.retainedScreenRows)
                        .put("parkedCandidates", parked())
                        .put("hasHistoryCheckpoint", width.checkpoint("diagnostic-only", f.bound.pass.metricKey) != null)
                        .put("sessionCheckpointRows", checkpoint.retainedRows())
                        .put("sameInitialHistory", unchangedHistory ?: JSONObject.NULL)
                        .put("columns", terminal.columns).put("fontSp", f.fontSp)
                        .put("rowStates", f.rowStore.retainedRowStates).put("measuredRows", f.measurements.retainedRows)
                        .put("writers", f.maximumWriters).put("timingMeasured", false))
                    records++
                }
                record("initial", 0, true)
                fun step(scene: String, expected: Long? = null, action: () -> Unit) {
                    val before = compose.runOnIdle { f.bound.binding.widthIndex.measuredHistoryRows }
                    compose.runOnIdle { action() }; settle(f)
                    val same = if (f.frame.isAlternateScreen) null else f.frame.ownedRows()!!.history === initialHistory
                    if (expected != null) assertEquals("$size/$scene", expected,
                        f.bound.binding.widthIndex.measuredHistoryRows - before)
                    record(scene, before, same)
                }
                step("mainText", 0) { terminal.feed("\u001b[20;1H\u001b[2Kupdated-main\r"); f.publish() }
                repeat(2) { round ->
                    step("enterAlt$round", 0) { terminal.feed("\u001b[?1049h\u001b[?25lALT\r"); f.publish() }
                    assertEquals(0, f.bound.binding.widthIndex.retainedCandidates)
                    assertTrue(parked() in 1..size)
                    step("returnSame$round", 0) { terminal.feed("\u001b[?1049l"); f.publish() }
                    assertSame(initialHistory, f.frame.ownedRows()!!.history)
                }
                step("fontMain", size.toLong()) { f.fontSp = 13 }
                step("enterAltFont", 0) { terminal.feed("\u001b[?1049h"); f.publish() }
                step("fontAlt", 0) { f.fontSp = 14 }
                assertEquals(0, parked())
                step("returnFont", size.toLong()) { terminal.feed("\u001b[?1049l"); f.publish() }
                step("enterAltColumns", 0) { terminal.feed("\u001b[?1049h"); f.publish() }
                step("columnsAlt", 0) { terminal.resize(72, 24); f.publish() }
                assertEquals(0, parked())
                step("returnColumns", size.toLong()) { terminal.feed("\u001b[?1049l"); f.publish() }
                step("mainFifo", 1) { terminal.feed("\u001b[24;1H\r\nfifo-new\r"); f.publish() }
                step("enterAltFifo", 0) { terminal.feed("\u001b[?1049h"); f.publish() }
                step("returnFifo", 0) { terminal.feed("\u001b[?1049l"); f.publish() }
                compose.runOnIdle { terminal.feed("\u001b[?1049h"); f.publish() }; settle(f)
                val heldBeforeDispose = parked()
                assertTrue(heldBeforeDispose in 1..size)
                compose.runOnIdle { shown = null }; compose.waitForIdle()
                assertEquals(0, f.measurements.retainedRows)
                assertEquals(0, f.pointerMap.retainedRows)
                assertEquals(0, parked())
                Log.i("TerminalWidthRecovery", "WIDTH_RECOVERY_CANDIDATE_DISPOSE " + JSONObject()
                    .put("size", size).put("measuredRows", f.measurements.retainedRows)
                    .put("heldBeforeDispose", heldBeforeDispose).put("parkedAfterDispose", parked())
                    .put("checkpointRowsAfterAlternateDispose", checkpoint.retainedRows()))
                checkpoint.close(); assertEquals(0, checkpoint.retainedRows())
            } finally { compose.runOnIdle { shown = null }; compose.waitForIdle(); checkpoint.close() }
        }
        assertEquals(32, records)
        Log.i("TerminalWidthRecovery", "WIDTH_RECOVERY_CANDIDATE_DONE " + JSONObject()
            .put("records", records).put("passed", true).put("performanceMeasured", false))
    }
}
