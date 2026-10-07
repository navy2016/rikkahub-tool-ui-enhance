package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.terminalScaledItemClip
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** New opt-in policy; all scrolling/layout remains in the unchanged production binding/executor. */
class TerminalImeVirtualHistoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "imeStable lazy=true ${description.methodName}", error)
        }
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            f.controller.state.value.initialized && f.controller.state.value.scrollEffect == null && f.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("overlapping scroll writers", f.maximumWriters <= 1)
            if (f.isVirtual) {
                assertNotNull(f.observation())
                assertTrue("full history tree was retained", f.measurements.retainedRows < 128)
                assertEquals(0, f.measurements.retainedEagerHistoryRows)
                val state = f.controller.state.value
                val target = if (state.autoScroll) TerminalItemScrollTarget.Follow
                    else TerminalItemScrollTarget.Anchor(requireNotNull(state.anchor), state.anchorRowHeightPx)
                assertTrue("unsettled virtual position", f.observation()!!.isSatisfied(target))
            }
        }
    }

    private fun mount(systemIme: Boolean = false, history: Int = 1_000, styled: Boolean = false): Fixture {
        val f = Fixture(stressSpans = styled, systemIme = systemIme, historyRows = history,
            initialMode = TerminalRenderMode.VIRTUAL_HISTORY_IME)
        compose.setContent { f.Content() }
        settle(f)
        return f
    }

    private fun showKeyboard(f: Fixture) {
        compose.onNodeWithTag("system-input").performClick()
        compose.runOnIdle { f.inputFocus.requestFocus() }
        compose.waitUntil(5_000) { f.inputFocused }
        compose.runOnIdle { f.keyboard?.show() }
        compose.waitUntil(10_000) { f.ime }
        settle(f)
    }

    private fun hideKeyboard(f: Fixture) {
        compose.runOnIdle { f.keyboard?.hide() }
        compose.waitUntil(10_000) { !f.ime }
        settle(f)
    }

    @Test fun imeStableTenThousandRowsRealKeyboardRetainsTreeAnchorAndWidthProof() {
        val f = mount(systemIme = true, history = 10_000)
        val original = compose.runOnIdle { f.top() }
        val widths = compose.runOnIdle { f.bound.binding.widthIndex }
        val measured = widths.measuredHistoryRows
        val measuredScreen = widths.measuredScreenRows
        val created = f.measurements.createdRowNodes
        val height = f.viewportHeight
        repeat(2) {
            showKeyboard(f)
            compose.runOnIdle {
                assertTrue(f.isVirtual)
                assertFalse(f.policy.imeFallback)
                assertTrue(f.viewportHeight < height)
                assertEquals(original.anchor, f.top().anchor)
                assertEquals(measured, widths.measuredHistoryRows)
            }
            repeat(3) { update ->
                compose.runOnIdle { f.terminal.feed("\r\u001B[2Kkeyboard-$it-$update"); f.publish() }
                settle(f)
            }
            hideKeyboard(f)
            compose.runOnIdle {
                assertTrue("keyboard hide must not require reapply in the new mode", f.isVirtual)
                assertEquals(height, f.viewportHeight)
                assertEquals(original.anchor, f.top().anchor)
                assertEquals(measured, widths.measuredHistoryRows)
                assertEquals(0L, f.measurements.eagerHistoryBuildCount)
                assertEquals(measuredScreen + (it + 1) * 3, widths.measuredScreenRows)
                assertEquals(24, widths.retainedScreenRows)
                assertTrue("keyboard rebuilt history rows", f.measurements.createdRowNodes - created < 256)
                assertEquals(24, f.terminal.rows)
            }
        }
    }

    @Test fun imeStableRealKeyboardTailFollowsOutputAndRestoresViewportWithoutRetry() {
        val f = mount(systemIme = true)
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        showKeyboard(f)
        repeat(5) {
            compose.runOnIdle { f.append() }
            settle(f)
            compose.runOnIdle {
                assertTrue(f.isVirtual)
                assertTrue(f.controller.state.value.autoScroll)
                assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
            }
        }
        hideKeyboard(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertTrue(f.controller.state.value.autoScroll)
            assertEquals(0L, f.measurements.eagerHistoryBuildCount)
            assertEquals(24, f.terminal.rows)
        }
    }

    @Test fun imeStableMouseSelectionTuiAlternateAndNoAvoidanceStillFallBack() {
        val f = mount()
        val original = compose.runOnIdle { f.top().anchor }
        compose.runOnIdle { f.ime = true }
        settle(f)
        val gates: List<Pair<() -> Unit, () -> Unit>> = listOf(
            Pair({ f.config = f.config.copy(selectionMode = true) },
                { f.config = f.config.copy(selectionMode = false) }),
            Pair({ f.config = f.config.copy(panEnabled = false) },
                { f.config = f.config.copy(panEnabled = true) }),
            Pair({ f.avoidIme = false }, { f.avoidIme = true }),
        )
        for ((disable, enable) in gates) {
            compose.runOnIdle { disable() }
            settle(f)
            compose.runOnIdle {
                assertFalse(f.isVirtual)
                assertEquals(TerminalRenderMode.VIRTUAL_HISTORY_IME, f.mode)
                assertEquals(original, f.top().anchor)
                enable()
            }
            settle(f)
            compose.runOnIdle { assertTrue(f.isVirtual); assertEquals(original, f.top().anchor) }
        }
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()); f.tui = true }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.tui = false }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); f.terminal.feed("\u001B[?1049hAlternate"); f.publish() }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.terminal.feed("\u001B[?1049l"); f.publish() }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); assertTrue(f.controller.state.value.autoScroll) }
    }

    @Test fun imeStableNoAvoidancePreservesFollowOffsetUntilAvoidanceIsEnabled() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        val before = compose.runOnIdle { f.top().anchor }
        compose.runOnIdle { f.avoidIme = false; f.ime = true; f.heightDp = 140 }
        settle(f)
        val offset = compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertTrue(f.controller.state.value.autoScroll)
            assertEquals(before, f.top().anchor)
            f.eager.value
        }
        repeat(3) { index ->
            compose.runOnIdle { f.terminal.feed("\r\u001B[2Kno-avoid-$index"); f.publish() }
            settle(f)
            compose.runOnIdle {
                assertFalse(f.isVirtual)
                assertTrue(f.controller.state.value.autoScroll)
                assertEquals(offset, f.eager.value)
            }
        }
        compose.runOnIdle { f.avoidIme = true }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertTrue(f.controller.state.value.autoScroll)
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
        }
    }

    @Test fun imeStableFontRtlAndHeightChangesKeepScaledClippingWithoutEagerHistory() {
        val f = mount(history = 256, styled = true)
        val original = compose.runOnIdle { f.top() }
        compose.runOnIdle { assertEquals(original.capturedRowHeightPx, f.controller.state.value.anchorRowHeightPx) }
        val horizontal = compose.runOnIdle { f.horizontal.value }
        compose.runOnIdle { f.ime = true; f.heightDp = 140; f.fontSp = 21; f.rtl = true }
        settle(f)
        compose.runOnIdle {
            val resized = f.top()
            assertTrue(f.isVirtual)
            assertEquals(original.anchor.lineId, resized.anchor.lineId)
            assertEquals(terminalScaledItemClip(original.anchor.clippedTopPx, original.capturedRowHeightPx,
                resized.capturedRowHeightPx), resized.anchor.clippedTopPx)
            assertEquals(horizontal, f.horizontal.value)
            f.ime = false; f.heightDp = 220; f.fontSp = 14; f.rtl = false
        }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertEquals(original.anchor, f.top().anchor)
            assertEquals(0L, f.measurements.eagerHistoryBuildCount)
        }
    }

    @Test fun imeStableLegacyChoiceStillLatchesAndSessionReplacementReleasesOwnership() {
        val first = Fixture(stressSpans = false, historyRows = 256,
            initialMode = TerminalRenderMode.VIRTUAL_HISTORY_IME)
        var shown by mutableStateOf(first, referentialEqualityPolicy())
        compose.setContent { shown.Content() }
        settle(first)
        compose.runOnIdle { first.ime = true }
        settle(first)
        compose.runOnIdle { assertTrue(first.isVirtual); first.mode = TerminalRenderMode.VIRTUAL_HISTORY }
        settle(first)
        compose.runOnIdle { assertFalse(first.isVirtual); first.ime = false }
        settle(first)
        compose.runOnIdle { assertFalse(first.isVirtual); first.policy.reapplied(false) }
        settle(first)
        compose.runOnIdle { assertTrue(first.isVirtual); first.mode = TerminalRenderMode.VIRTUAL_HISTORY_IME; first.ime = true }
        settle(first)
        val widths = first.bound.binding.widthIndex
        val replacement = Fixture(stressSpans = false, historyRows = 128,
            initialMode = TerminalRenderMode.VIRTUAL_HISTORY_IME).apply { ime = true }
        compose.runOnIdle { shown = replacement }
        settle(replacement)
        compose.runOnIdle {
            assertTrue(replacement.isVirtual)
            assertFalse(widths.hasRetainedState)
            assertEquals(0, widths.retainedScreenRows)
            assertEquals(0, first.measurements.retainedRows)
            assertEquals(0, first.measurements.retainedEagerHistoryRows)
            assertEquals(0, first.activeWriters)
        }
    }
}
