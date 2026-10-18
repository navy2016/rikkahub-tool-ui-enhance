package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The negative arm uses exact old production, not a copied/newly weakened scrolling formula. */
class TerminalBottomVisibilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(150)
    private val control = InstrumentationRegistry.getArguments().getString("bottomVisibilityControl") == "true"

    private fun terminal(history: Int = 64) = TerminalEmulator(80, 24, 2048).apply {
        feed((0 until history + 23).joinToString("\r\n") { "history-$it 中文 e\u0301" } + "\r\n")
    }

    private fun fixture(terminal: TerminalEmulator, tui: Boolean = false, stress: Boolean = false,
        mode: TerminalRenderMode = TerminalRenderMode.DEFAULT, ime: Boolean = false) =
        Fixture(stressSpans = stress, systemIme = ime, initialMode = mode, terminalOverride = terminal,
            restored = TerminalViewportState(autoScroll = true), foreground = Color(0xFF00E676)).apply {
            this.tui = tui
            fontSp = 12
            densityOverride = Density(1.33f, 1.3f)
        }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.controller.state.value.initialized && f.controller.state.value.gesture == null &&
                f.controller.state.value.scrollEffect == null && f.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("Only one scroll writer", f.maximumWriters <= 1) }
    }

    private data class Bounds(val top: Float, val bottom: Float, val viewport: Int, val rowHeight: Int)

    private fun bounds(f: Fixture, index: Int): Bounds {
        val expected = compose.runOnIdle { f.frame.rows[index].text }
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true)
            .fetchSemanticsNodes().single { it.config[SemanticsProperties.Text].singleOrNull() == expected }
        val viewport = compose.onNodeWithTag("item-output").fetchSemanticsNode()
        return Bounds(node.positionInRoot.y - viewport.positionInRoot.y,
            node.positionInRoot.y - viewport.positionInRoot.y + node.size.height, viewport.size.height, node.size.height)
    }

    private fun report(case: String, phase: String, f: Fixture, index: Int, clipped: Boolean = control): Bounds {
        val actual = bounds(f, index)
        val fields = JSONObject().put("case", case).put("phase", phase).put("control", control)
            .put("historyRows", f.frame.historyCount).put("row", index).put("rowHeight", actual.rowHeight)
            .put("top", actual.top.toDouble()).put("bottom", actual.bottom.toDouble()).put("viewport", actual.viewport)
            .put("scroll", f.eager.value).put("virtual", f.isVirtual).put("tui", f.tui || f.frame.isAlternateScreen)
            .put("writers", f.maximumWriters).put("clippedExpected", clipped)
        Log.i("TerminalBottomVisibility", "BOTTOM_RECT $fields")
        if (clipped) {
            assertTrue("Old-source control did not reproduce the clipping: $fields", actual.bottom > actual.viewport + 1f)
        } else {
            assertTrue("Target row still clipped: $fields", actual.top >= -1f && actual.bottom <= actual.viewport + 1f)
        }
        return actual
    }

    @Test fun bottomVisibilityBlankCursorIsEntirelyVisible() {
        val t = terminal()
        val f = fixture(t)
        compose.setContent { f.Content() }
        settle(f)
        val index = f.frame.screenStartRow + f.frame.cursorRow
        assertTrue(f.frame.cursorVisible)
        assertTrue(f.frame.rows[index].text.text.isBlank())
        assertTrue(requireNotNull(f.frame.contentBounds.lastNonBlankRow) < index)
        report("cursor", "initial", f, index)
    }

    @Test fun bottomVisibilityTuiHistoryGrowthNeverAccumulatesClipping() {
        val t = terminal(128)
        val f = fixture(t, tui = true, stress = true)
        compose.setContent { f.Content() }
        val clipping = mutableListOf<Float>()
        for ((phase, extra) in listOf("128" to 0, "512" to 384, "1024" to 512)) {
            if (extra > 0) compose.runOnIdle {
                repeat(extra) { t.feed("grow-$phase-$it 中文\r\n") }
                f.publish()
            }
            settle(f)
            val index = f.frame.screenStartRow + f.frame.cursorRow
            val actual = report("tuiGrowth", phase, f, index)
            clipping += actual.bottom - actual.viewport
            if (!control) assertNotNull(f.bound.binding.eagerGeometry())
        }
        if (control) assertTrue("Growing history must expose accumulated nominal-height drift", clipping.last() > clipping.first())
    }

    @Test fun bottomVisibilityVirtualAndImeModesKeepBlankCursorAfterRoundTrip() {
        check(!control)
        val f = fixture(terminal(), mode = TerminalRenderMode.VIRTUAL_HISTORY_IME, ime = true)
        compose.setContent { f.Content() }
        settle(f)
        report("cursorIme", "initial", f, f.frame.screenStartRow + f.frame.cursorRow)
        compose.onNodeWithTag("system-input").performClick()
        compose.runOnIdle { f.inputFocus.requestFocus(); f.keyboard?.show() }
        compose.waitUntil(10_000) { f.ime }
        settle(f)
        report("cursorIme", "open", f, f.frame.screenStartRow + f.frame.cursorRow)
        for (mode in listOf(TerminalRenderMode.DEFAULT, TerminalRenderMode.VIRTUAL_HISTORY_IME)) {
            compose.runOnIdle { f.mode = mode }
            settle(f)
            report("cursorIme", mode.id, f, f.frame.screenStartRow + f.frame.cursorRow)
        }
        compose.runOnIdle { f.keyboard?.hide() }
        compose.waitUntil(10_000) { !f.ime }
        settle(f)
        report("cursorIme", "closed", f, f.frame.screenStartRow + f.frame.cursorRow)
    }

    @Test fun bottomVisibilityTuiFontAlternateAndMainReturnKeepMeasuredTail() {
        check(!control)
        val t = terminal(256)
        val f = fixture(t, tui = true, stress = true)
        compose.setContent { f.Content() }
        settle(f)
        for (font in listOf(12, 21, 14)) {
            compose.runOnIdle { f.fontSp = font }
            settle(f)
            report("tuiFont", font.toString(), f, f.frame.screenStartRow + f.frame.cursorRow)
        }
        compose.runOnIdle { t.feed("\u001b[?1049h\u001b[24;1HALT_BOTTOM"); f.publish() }
        settle(f)
        report("tuiFont", "alt", f, f.frame.cursorRow)
        compose.runOnIdle { t.feed("\u001b[?1049l"); f.publish() }
        settle(f)
        report("tuiFont", "main", f, f.frame.screenStartRow + f.frame.cursorRow)
    }

    @Test fun bottomVisibilityHiddenCursorAndPromptDoNotFollowUnusedBlankRows() {
        check(!control)
        val t = terminal()
        val f = fixture(t)
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle { t.feed("\u001b[?25l"); f.publish() }
        settle(f)
        report("cursorState", "hidden", f, requireNotNull(f.frame.contentBounds.lastNonBlankRow))
        compose.runOnIdle { t.feed("\u001b[?25h> "); f.publish() }
        settle(f)
        report("cursorState", "prompt", f, f.frame.screenStartRow + f.frame.cursorRow)
    }

    @Test fun bottomVisibilityTuiUpdatesReuseHistoryAndDetachMeasurements() {
        check(!control)
        val t = terminal(1024)
        val f = fixture(t, tui = true)
        var mounted by mutableStateOf(true)
        compose.setContent { if (mounted) f.Content() }
        settle(f)
        val original = requireNotNull(f.bound.binding.eagerGeometry()).historyPrefix
        val visited = f.measurements.eagerVisitedHistoryRows
        repeat(8) { step ->
            compose.runOnIdle { t.feed("\u001b[23;1H\u001b[2Klive-$step\u001b[24;1H"); f.publish() }
            settle(f)
            assertSame(original, f.bound.binding.eagerGeometry()!!.historyPrefix)
            assertEquals(visited, f.measurements.eagerVisitedHistoryRows)
            report("tuiReuse", step.toString(), f, f.frame.screenStartRow + f.frame.cursorRow)
        }
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        assertEquals(0, f.measurements.retainedRows)
    }
}
