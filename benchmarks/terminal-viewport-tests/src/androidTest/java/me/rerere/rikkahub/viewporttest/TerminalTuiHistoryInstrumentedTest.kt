package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportMode
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** All paths use the production gate, natural measurements, SCREEN/LOCK and single executor. */
class TerminalTuiHistoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(180)
    private val modes = listOf(TerminalRenderMode.TUI_CHUNKED, TerminalRenderMode.TUI_CHUNKED_LAYERS)
    private fun terminal(size: Int) = TerminalEmulator(80, 24, size).apply {
        feed((0 until size+23).joinToString("\r\n") { "line-$it 中文 e\u0301 👩‍💻" } + "\r\n")
    }
    private fun fixture(mode: TerminalRenderMode, size: Int = 1000, systemIme: Boolean = false,
        terminal: TerminalEmulator = terminal(size), saved: TerminalViewportState = TerminalViewportState(autoScroll = true),
        mountStreamFrames: Int = 0) =
        Fixture(stressSpans = false, systemIme = systemIme, initialMode = mode, terminalOverride = terminal,
            restored = saved, foreground = Color(0xFF00E676), mountStreamFrames = mountStreamFrames).apply {
            tui = true
            fontSp = 12
            heightDp = 240
            densityOverride = Density(1.33f, 1.3f)
        }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(20_000) {
            f.controller.state.value.initialized && f.controller.state.value.gesture == null &&
                f.controller.state.value.scrollEffect == null && f.activeWriters == 0 &&
                f.bound.pass.frame === f.frame && f.measurements.peekEager(f.bound.pass)?.frame === f.frame
        }
        compose.waitForIdle()
        assertFalse(f.isVirtual)
        assertTrue(f.maximumWriters <= 1)
        assertEquals(f.frame.rows.size, f.measurements.retainedRows)
    }

    private fun cursor(f: Fixture, case: String, phase: String) {
        val frame = f.frame
        val text = frame.rows[frame.historyCount + frame.cursorRow].text
        val row = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(text)), true)
            .fetchSemanticsNodes().single()
        val view = compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - view.positionInRoot.y
        assertTrue("TUI cursor clipped", top >= -1 && top + row.size.height <= view.size.height + 1)
        assertEquals(ViewportMode.SCREEN, f.controller.state.value.mode)
        Log.i("TerminalTuiHistory", "TUI_HISTORY_RECT " + JSONObject().put("case", case).put("phase", phase)
            .put("mode", f.mode.id).put("top", top.toDouble()).put("bottom", (top + row.size.height).toDouble())
            .put("rowHeight", row.size.height).put("viewport", view.size.height).put("historyRows", frame.historyCount)
            .put("frameRevision", frame.revision).put("tui", f.tui).put("virtual", f.isVirtual)
            .put("grouped", f.tuiHistory).put("effective", f.effectiveMode.id).put("writers", f.maximumWriters))
    }

    private fun report(name: String) {
        Log.i("TerminalTuiHistory", "TUI_HISTORY_CASE " + JSONObject().put("case", name).put("passed", true))
    }

    @Test fun tuiHistoryOneThousandContinuousOutputAndRemount() = streamAndRemount(1000)
    @Test fun tuiHistoryFiveThousandContinuousOutputAndRemount() = streamAndRemount(5000)
    @Test fun tuiHistoryTenThousandContinuousOutputAndRemount() = streamAndRemount(10000)

    private fun streamAndRemount(size: Int) {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        for (mode in modes) {
            val f = fixture(mode, size)
            compose.runOnIdle { shown = f }
            settle(f)
            assertTrue(f.tuiHistory)
            assertEquals(mode, f.effectiveMode)
            assertTrue(f.historyChunksCount > 0)
            cursor(f, "stream$size", "initial")
            val job = compose.runOnIdle { f.stream(24, 2, trailingNewline = true) { step ->
                f.ime = step in 8..15
                f.heightDp = if (f.ime) 140 else 240
            } }
            compose.waitUntil(45_000) { job.isCompleted }
            settle(f)
            assertFalse(job.isCancelled)
            assertEquals(48, f.appendCount)
            assertEquals(size, f.frame.historyCount)
            cursor(f, "stream$size", "after24")
            val saved = compose.runOnIdle { f.snapshotViewport() }
            compose.runOnIdle { shown = null }
            compose.waitForIdle()
            assertEquals(0, f.measurements.retainedRows)
            assertEquals(0, f.measurements.retainedEagerHistoryRows)
            f.terminal.feed((0..7).joinToString("\r\n", postfix = "\r\n") { "detached-$it" })
            val reentry = fixture(mode, size, terminal = f.terminal, saved = saved, mountStreamFrames = 24)
            compose.runOnIdle { shown = reentry }
            // Mount owns its finite producer; starts without waiting for a completed first draw.
            compose.waitUntil(45_000) { reentry.mountStreamJob?.isCompleted == true }
            settle(reentry)
            assertFalse(reentry.mountStreamJob!!.isCancelled)
            assertEquals(48, reentry.appendCount)
            cursor(reentry, "stream$size", "reentry24")
            assertEquals(size, reentry.frame.historyCount)
            assertTrue(reentry.tuiHistory)
            compose.runOnIdle { shown = null }
            compose.waitForIdle()
            assertEquals(0, reentry.measurements.retainedRows)
            assertEquals(0, reentry.measurements.retainedEagerHistoryRows)
            Log.i("TerminalTuiHistory", "TUI_HISTORY_STREAM " + JSONObject().put("size", size).put("mode", mode.id)
                .put("initialLines", f.appendCount).put("reentryLines", reentry.appendCount).put("detachedLines", 8)
                .put("historyRows", reentry.frame.historyCount).put("oldRowsRetained", f.measurements.retainedRows)
                .put("newRowsRetained", reentry.measurements.retainedRows)
                .put("oldHeightsRetained", f.measurements.retainedEagerHistoryRows)
                .put("newHeightsRetained", reentry.measurements.retainedEagerHistoryRows))
        }
        report("stream$size")
    }

    @Test fun tuiHistoryLockedAnchorFontColumnsAndRealImeRemainStable() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        for (mode in modes) {
            val terminal = terminal(1000)
            val initial = terminal.renderFrame()
            val saved = TerminalViewportState(autoScroll = false, anchorLineId = initial.historyLineIds[500],
                anchorClippedTopPx = 5, anchorHistoryGeneration = initial.historyGeneration)
            val f = fixture(mode, systemIme = true, terminal = terminal, saved = saved)
            compose.runOnIdle { shown = f }
            settle(f)
            val anchor = compose.runOnIdle { f.top().anchor }
            val job = compose.runOnIdle { f.stream(24, 2) }
            compose.waitUntil(45_000) { job.isCompleted }
            settle(f)
            assertEquals(anchor, f.top().anchor)
            assertEquals(ViewportMode.LOCKED, f.controller.state.value.mode)
            compose.runOnIdle { f.fontSp = 19 }
            settle(f)
            assertEquals(anchor.lineId, f.top().anchor.lineId)
            compose.runOnIdle { terminal.resize(96, 24); f.publish() }
            settle(f)
            assertEquals(anchor.lineId, f.top().anchor.lineId)
            compose.onNodeWithTag("system-input").performClick()
            compose.runOnIdle { f.inputFocus.requestFocus(); f.keyboard?.show() }
            compose.waitUntil(10_000) { f.ime }
            settle(f)
            assertTrue(f.tuiHistory)
            assertEquals(anchor.lineId, f.top().anchor.lineId)
            assertEquals(24, terminal.rows)
            compose.runOnIdle { f.keyboard?.hide(); f.controller.setFollow(true, f.inputPx()) }
            compose.waitUntil(10_000) { !f.ime }
            settle(f)
            cursor(f, "lockedFontIme", "follow")
            compose.runOnIdle { shown = null }
            compose.waitForIdle()
            assertEquals(0, f.measurements.retainedRows)
        }
        report("lockedFontIme")
    }

    @Test fun tuiHistorySelectionMouseFullGridAlternateAndDefaultKeepFallback() {
        val f = fixture(modes[0], 128)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) f.Content() }
        settle(f)
        for (mode in modes) {
            compose.runOnIdle { f.mode = mode }
            settle(f)
            assertTrue(f.tuiHistory)
            val before = f.frame.rows.map { it.text }
            for (gate in 0..4) {
                compose.runOnIdle {
                    when (gate) {
                        0 -> f.config = f.config.copy(selectionMode = true)
                        1 -> f.config = f.config.copy(panEnabled = false)
                        2 -> { f.terminal.feed("\u001b[?1000h"); f.publish() }
                        3 -> f.fullGrid = true
                        4 -> { f.terminal.feed("\u001b[?1049hALT"); f.publish() }
                    }
                }
                settle(f)
                assertFalse(f.tuiHistory)
                assertEquals(0, f.historyChunksCount)
                assertEquals(TerminalRenderMode.FLAT, f.effectiveMode)
                assertEquals(mode, f.mode)
                Log.i("TerminalTuiHistory", "TUI_HISTORY_GATE " + JSONObject().put("mode", mode.id).put("gate", gate)
                    .put("grouped", f.tuiHistory).put("effective", f.effectiveMode.id).put("chunks", f.historyChunksCount))
                compose.runOnIdle {
                    when (gate) {
                        0 -> f.config = f.config.copy(selectionMode = false)
                        1 -> f.config = f.config.copy(panEnabled = true)
                        2 -> { f.terminal.feed("\u001b[?1000l"); f.publish() }
                        3 -> f.fullGrid = false
                        4 -> { f.terminal.feed("\u001b[?1049l"); f.publish() }
                    }
                }
                settle(f)
                assertTrue(f.tuiHistory)
                assertEquals(before, f.frame.rows.map { it.text })
                cursor(f, "fallbacks", "restore$gate")
            }
        }
        compose.runOnIdle { f.mode = TerminalRenderMode.DEFAULT }
        settle(f)
        assertFalse(f.tuiHistory)
        assertEquals(0, f.historyChunksCount)
        cursor(f, "fallbacks", "default")
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(0, f.measurements.retainedRows)
        report("fallbacks")
    }
}
