package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** Fresh DEFAULT sessions, not preconditioned by visiting either virtual renderer. */
class TerminalDefaultBottomInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "defaultBottom lazy=true ${description.methodName}", error)
        }
    }

    private fun fresh(systemIme: Boolean = false, history: Int = 64, terminal: TerminalEmulator? = null) =
        Fixture(stressSpans = true, systemIme = systemIme, historyRows = history,
            initialMode = TerminalRenderMode.DEFAULT, terminalOverride = terminal,
            restored = TerminalViewportState(autoScroll = true), foreground = Color(0xFF00E676))

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.controller.state.value.initialized && f.controller.state.value.gesture == null &&
                f.controller.state.value.scrollEffect == null && f.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("multiple scroll writers", f.maximumWriters <= 1) }
    }

    /** Independent oracle: actual Text bounds against the actual viewport; no controller formula. */
    private fun assertContentBottomVisible(f: Fixture) {
        val last = compose.runOnIdle { requireNotNull(f.frame.contentBounds.lastNonBlankRow) }
        val text = compose.runOnIdle { f.frame.rows[last].text }
        val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true).fetchSemanticsNodes().single {
                it.config[SemanticsProperties.Text].singleOrNull() == text
            }
        val viewport = compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - viewport.positionInRoot.y
        val bottom = top + row.size.height
        assertTrue("content bottom clipped: bottom=$bottom viewport=${viewport.size.height} " +
            "rowHeight=${row.size.height} scroll=${f.eager.value}/${f.eager.maxValue}",
            bottom <= viewport.size.height + 1f)
        assertTrue("last content row is outside viewport: top=$top bottom=$bottom", top >= -1f && bottom > 0f)
    }

    private fun assertDefault(f: Fixture, neverVirtual: Boolean = true) = compose.runOnIdle {
        assertEquals(TerminalRenderMode.CHUNKED_LAYERS, f.mode)
        assertFalse(f.isVirtual)
        assertTrue(f.controller.state.value.autoScroll)
        assertEquals(0, f.bound.binding.widthIndex.retainedScreenRows)
        if (neverVirtual) {
            assertFalse("default started virtual width work", f.bound.binding.widthIndex.hasRetainedState)
            assertEquals(0L, f.bound.binding.widthIndex.measuredHistoryRows)
        }
    }

    @Test fun defaultBottomFreshSessionShowsFullLastRowBeforeAnyRendererSwitch() {
        val f = fresh()
        compose.setContent { f.Content() }
        settle(f)
        assertDefault(f)
        assertContentBottomVisible(f)
        val bottom = compose.runOnIdle { f.eager.value }
        compose.mainClock.advanceTimeBy(800)
        settle(f)
        compose.runOnIdle { assertEquals(bottom, f.eager.value) }
        assertContentBottomVisible(f)
    }

    @Test fun defaultBottomManualDragToRealEndDoesNotBounceUpAfterRelease() {
        val f = fresh()
        compose.setContent { f.Content() }
        settle(f)
        val node = compose.onNodeWithTag("item-output")
        val size = node.fetchSemanticsNode().size
        var reached = -1
        compose.mainClock.autoAdvance = false
        try {
            node.performTouchInput {
                down(Offset(size.width / 2f, size.height * .2f))
                moveBy(Offset(0f, size.height * .5f), delayMillis = 32)
                repeat(24) { moveBy(Offset(0f, -size.height.toFloat()), delayMillis = 32) }
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnUiThread {
                assertEquals(ViewportScrollOrigin.USER_DRAG, f.controller.state.value.gesture?.origin)
                reached = f.eager.value
                assertEquals("pointer drag did not reach physical end", f.eager.maxValue, reached)
            }
            node.performTouchInput { advanceEventTime(500); up() }
        } finally { compose.mainClock.autoAdvance = true }
        settle(f)
        compose.mainClock.advanceTimeBy(800)
        settle(f)
        compose.runOnIdle {
            assertEquals("manual bottom bounced upward after release", reached, f.eager.value)
            assertNull(f.controller.state.value.scrollEffect)
        }
        assertDefault(f)
        assertContentBottomVisible(f)
    }

    @Test fun defaultBottomRendererRoundTripAndAnotherFreshSessionHaveSameVisibility() {
        val first = fresh()
        var shown by mutableStateOf(first, referentialEqualityPolicy())
        compose.setContent { shown.Content() }
        settle(first)
        assertContentBottomVisible(first)
        val initial = compose.runOnIdle { first.eager.value }
        for (mode in listOf(TerminalRenderMode.VIRTUAL_HISTORY, TerminalRenderMode.VIRTUAL_HISTORY_IME)) {
            compose.runOnIdle { first.mode = mode }
            settle(first)
            assertContentBottomVisible(first)
            compose.runOnIdle { first.mode = TerminalRenderMode.DEFAULT }
            settle(first)
            assertDefault(first, neverVirtual = false)
            assertContentBottomVisible(first)
            compose.runOnIdle { assertEquals(initial, first.eager.value) }
        }
        val replacement = fresh()
        compose.runOnIdle { shown = replacement }
        settle(replacement)
        assertDefault(replacement)
        assertContentBottomVisible(replacement)
        compose.runOnIdle { assertEquals(0, first.measurements.retainedRows) }
    }

    @Test fun defaultBottomFontDensityRtlAndAppendsKeepTheLastRowVisible() {
        val f = fresh()
        compose.setContent { f.Content() }
        settle(f)
        for (font in listOf(12, 21, 14)) {
            compose.runOnIdle {
                f.fontSp = font
                f.densityOverride = if (font == 21) Density(1.33f, 1.3f) else Density(2.75f, 1.15f)
                f.rtl = font == 21
            }
            settle(f)
            assertDefault(f)
            assertContentBottomVisible(f)
            repeat(3) {
                compose.runOnIdle { f.append() }
                settle(f)
                assertContentBottomVisible(f)
            }
        }
    }

    @Test fun defaultBottomRealKeyboardRoundTripNeedsNoVirtualModeWarmup() {
        val f = fresh(systemIme = true)
        compose.setContent { f.Content() }
        settle(f)
        assertContentBottomVisible(f)
        compose.onNodeWithTag("system-input").performClick()
        compose.runOnIdle { f.inputFocus.requestFocus() }
        compose.waitUntil(5_000) { f.inputFocused }
        compose.runOnIdle { f.keyboard?.show() }
        compose.waitUntil(10_000) { f.ime }
        settle(f)
        assertDefault(f)
        assertContentBottomVisible(f)
        compose.runOnIdle { f.append() }
        settle(f)
        assertContentBottomVisible(f)
        compose.runOnIdle { f.keyboard?.hide() }
        compose.waitUntil(10_000) { !f.ime }
        settle(f)
        assertContentBottomVisible(f)
        compose.runOnIdle { assertEquals(24, f.terminal.rows) }
    }

    @Test fun defaultBottomScreenOnlySessionAndClearUseActualTextHeights() {
        val terminal = TerminalEmulator(80, 24, 64).apply {
            feed("\u001B[?25l" + (0 until 24).joinToString("\r\n") { "screen-$it 中文 e\u0301" })
        }
        val f = fresh(terminal = terminal)
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle { assertEquals(0, f.frame.historyCount) }
        assertDefault(f)
        assertContentBottomVisible(f)
        compose.runOnIdle { terminal.feed("\u001B[2J\u001B[Hshort"); f.publish() }
        settle(f)
        assertContentBottomVisible(f)
        compose.runOnIdle { assertEquals(0, f.eager.value) }
    }
}
