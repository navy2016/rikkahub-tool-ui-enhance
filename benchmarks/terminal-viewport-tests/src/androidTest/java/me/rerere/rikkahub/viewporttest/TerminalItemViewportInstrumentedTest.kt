package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportAnchor
import me.rerere.rikkahub.data.container.ViewportMode
import me.rerere.rikkahub.data.container.terminalScaledItemClip
import me.rerere.rikkahub.ui.pages.container.TERMINAL_LAZY_SCREEN_KEY
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.TerminalRenderModeDialog
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.rememberTerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** Real Text heights + the production controller and sole executor. Never supplies a total height. */
class TerminalItemViewportInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(45)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "item lazy=true failed ${description.methodName}", e)
        }
    }

    private fun showSystemKeyboard(f: Fixture) {
        compose.onNodeWithTag("system-input").performClick()
        compose.runOnIdle { f.inputFocus.requestFocus() }
        compose.waitUntil(5_000) { f.inputFocused }
        compose.runOnIdle { f.keyboard?.show() }
        compose.waitUntil(10_000) { f.ime }
    }

    private fun mount(f: Fixture = Fixture()): Fixture {
        compose.setContent { f.Content() }
        settle(f)
        return f
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(10_000) { f.controller.state.value.initialized && f.activeWriters == 0 }
        compose.waitForIdle()
        compose.runOnIdle {
            if (f.isVirtual) assertNotNull("missing completed lazy layout", f.observation())
            else if (f.bound.eagerMeasurement != null) assertNotNull("missing completed eager layout", f.bound.binding.eagerGeometry())
            else assertTrue("unmeasured eager viewport", f.eager.maxValue != Int.MAX_VALUE)
            assertNull("unfinished scroll effect", f.controller.state.value.scrollEffect)
            if (f.isVirtual) assertTrue("not virtualized: ${f.measurements.retainedRows}", f.measurements.retainedRows < 100)
            assertTrue("overlapping writers: ${f.maximumWriters}", f.maximumWriters <= 1)
        }
    }

    @Test fun itemNativeSavedHistoryAnchorRestoresWithVariableNaturalHeights() {
        val f = mount()
        compose.runOnIdle {
            val captured = f.observation()!!.capture()!!
            assertEquals(f.saved.anchorLineId, captured.anchor.lineId)
            assertEquals(7, captured.anchor.clippedTopPx)
            assertEquals(captured.capturedRowHeightPx, f.controller.state.value.anchorRowHeightPx)
            assertFalse(f.controller.state.value.autoScroll)
            assertTrue(f.observation()!!.rows.map { it.heightPx }.distinct().size > 1)
        }
    }

    @Test fun itemNativeThirtyTrimsPreserveIdAndActualClipping() {
        val f = mount()
        repeat(30) {
            compose.runOnIdle { f.append() }
            settle(f)
            compose.runOnIdle {
                assertEquals(f.saved.anchorLineId, f.observation()!!.capture()!!.anchor.lineId)
                assertEquals(7, f.observation()!!.capture()!!.anchor.clippedTopPx)
            }
        }
    }

    @Test fun itemNativeFontChangesUseOriginalMeasuredCaptureWithoutDrift() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(false, f.inputPx()) }
        val original = f.controller.state.value
        repeat(3) {
            compose.runOnIdle { f.fontSp = 21 }
            settle(f)
            compose.runOnIdle {
                val actual = f.observation()!!.capture()!!
                assertEquals(original.anchor!!.lineId, actual.anchor.lineId)
                assertEquals(terminalScaledItemClip(7, original.anchorRowHeightPx, actual.capturedRowHeightPx),
                    actual.anchor.clippedTopPx)
            }
            compose.runOnIdle { f.fontSp = 14 }
            settle(f)
            compose.runOnIdle { assertEquals(7, f.observation()!!.capture()!!.anchor.clippedTopPx) }
        }
    }

    @Test fun itemNativeFollowAndScreenAnchorUseOneWholeMeasuredScreenItem() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
            assertEquals(1, f.lazy.layoutInfo.visibleItemsInfo.count { it.key == TERMINAL_LAZY_SCREEN_KEY })
            val id = f.frame.screenLineIds[8]
            f.controller.restoreItemAnchor(ViewportAnchor(id, 9, f.frame.screenGeneration, null), 0)
        }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.frame.screenLineIds[8], f.observation()!!.capture()!!.anchor.lineId)
            assertEquals(9, f.observation()!!.capture()!!.anchor.clippedTopPx)
        }
    }

    @Test fun itemNativeContractionFollowsLatestOutputWithoutRestoringOldPixels() {
        val f = mount()
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()); f.heightDp = 140 }
        settle(f)
        repeat(4) { compose.runOnIdle { f.append() }; settle(f) }
        compose.runOnIdle { f.heightDp = 220 }
        settle(f)
        compose.runOnIdle { assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow)) }
    }

    @Test fun itemNativeNewDragCancelsOldAnimationAndRetainsItsFinalConsumedAnchor() {
        val f = mount()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { f.controller.jumpToBottom(f.inputPx()) }
            compose.mainClock.advanceTimeBy(32)
            lateinit var old: TerminalViewportScrollEffect
            compose.runOnUiThread { old = requireNotNull(f.controller.state.value.scrollEffect) }
            val node = compose.onNodeWithTag("item-output")
            val size = node.fetchSemanticsNode().size
            node.performTouchInput {
                down(Offset(size.width / 2f, size.height * .2f))
                repeat(3) { moveBy(Offset(0f, 70f), delayMillis = 32) }
            }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread {
                assertFalse(f.controller.isCurrent(old))
                assertEquals(ViewportMode.LOCKED, f.controller.state.value.mode)
            }
            node.performTouchInput { advanceEventTime(500); up() }
        } finally { compose.mainClock.autoAdvance = true }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.controller.state.value.anchor!!.lineId, f.observation()!!.capture()!!.anchor.lineId)
            assertFalse(f.controller.state.value.autoScroll)
        }
    }

    @Test fun itemNativeSlowSwipesAndFastTopJumpUseRealGeometryAndOneWriter() {
        val f = mount()
        val node = compose.onNodeWithTag("item-output")
        val size = node.fetchSemanticsNode().size
        val a = Offset(size.width / 2f, size.height * .2f)
        val b = Offset(size.width / 2f, size.height * .8f)
        node.performTouchInput { swipe(b, a, 600) }
        settle(f)
        compose.runOnIdle {
            assertEquals(f.controller.state.value.anchor!!.lineId, f.observation()!!.capture()!!.anchor.lineId)
            f.config = f.config.copy(fastFlingRequiredCount = 1)
        }
        node.performTouchInput { swipeWithVelocity(a, b, endVelocity = 6000f) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.atTop)
            assertFalse(f.controller.state.value.autoScroll)
            assertEquals(f.frame.historyLineIds.first(), f.controller.state.value.anchor!!.lineId)
        }
    }

    @Test fun itemNativeConcurrentOutputDuringBottomJumpReachesCurrentTail() {
        val f = mount()
        compose.runOnIdle {
            f.outputDuringJump = true
            f.controller.jumpToBottom(f.inputPx())
        }
        compose.waitUntil(10_000) { f.appendCount == 30 }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
            assertTrue(f.controller.state.value.autoScroll)
        }
    }

    @Test fun itemNativeOffscreenWidestLineAndRtlMatchEagerRangeAfterTrim() {
        val f = mount(Fixture(wideHead = true, stressSpans = false))
        val wide = compose.runOnIdle { f.horizontal.maxValue }
        assertTrue(wide > 0)
        compose.runOnIdle { f.panHorizontal(41); f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        compose.runOnIdle { assertEquals(wide, f.horizontal.maxValue) }
        for (rtl in listOf(false, true)) {
            compose.runOnIdle { f.rtl = rtl; f.mode = TerminalRenderMode.CHUNKED_LAYERS }
            settle(f)
            val reference = compose.runOnIdle { f.horizontal.maxValue }
            compose.runOnIdle { f.mode = TerminalRenderMode.VIRTUAL_HISTORY }
            settle(f)
            compose.runOnIdle { assertEquals(reference, f.horizontal.maxValue); assertEquals(41, f.horizontal.value) }
        }
        compose.runOnIdle { f.append() }
        settle(f)
        val trimmed = compose.runOnIdle { f.horizontal.maxValue }
        assertTrue("Offscreen widest row was not removed", trimmed < wide)
        compose.runOnIdle { f.mode = TerminalRenderMode.CHUNKED_LAYERS }
        settle(f)
        compose.runOnIdle { assertEquals(trimmed, f.horizontal.maxValue) }
    }

    @Test fun itemNativeModeSwitchesKeepMeasuredTopAndBothOffsets() {
        val f = mount()
        val initial = compose.runOnIdle { f.top() }
        compose.runOnIdle { f.panHorizontal(43) }
        repeat(2) {
            for (mode in listOf(TerminalRenderMode.FLAT, TerminalRenderMode.CHUNKED,
                TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.VIRTUAL_HISTORY)) {
                compose.runOnIdle { f.mode = mode }
                settle(f)
                compose.runOnIdle {
                    assertEquals("mode=$mode", initial.anchor, f.top().anchor)
                    assertEquals(43, f.horizontal.value)
                    assertFalse(f.controller.state.value.autoScroll)
                }
            }
        }
    }

    @Test fun itemNativeImeFallbackSameModeApplyRestoresVirtualWithoutResettingAnchor() {
        val f = mount()
        val initial = compose.runOnIdle { f.top() }
        compose.runOnIdle { f.ime = true; f.heightDp = 140 }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); assertEquals(initial.anchor, f.top().anchor) }
        compose.runOnIdle { f.ime = false; f.heightDp = 220 }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.showRendererDialog = true }
        compose.waitForIdle()
        compose.onNodeWithTag("terminal-render-apply").performClick()
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); assertEquals(initial.anchor, f.top().anchor) }
    }

    @Test fun itemNativeSelectionAndMouseFallbackPreserveReadingPosition() {
        val f = mount()
        val initial = compose.runOnIdle { f.top() }
        for (selection in listOf(true, false)) {
            compose.runOnIdle { f.config = f.config.copy(selectionMode = selection, panEnabled = selection) }
            settle(f)
            compose.runOnIdle { assertFalse(f.isVirtual); assertEquals(initial.anchor, f.top().anchor) }
            compose.runOnIdle { f.config = f.config.copy(selectionMode = false, panEnabled = true) }
            settle(f)
            compose.runOnIdle { assertTrue(f.isVirtual); assertEquals(initial.anchor, f.top().anchor) }
        }
    }

    @Test fun itemNativeSemanticTailCorrectsBlankScreenAndSettlesWithoutRetryLoop() {
        val f = mount(Fixture(stressSpans = false))
        compose.runOnIdle {
            f.controller.setFollow(true, f.inputPx())
            f.terminal.feed("\u001B[2J\u001B[Hshort")
            f.publish()
        }
        settle(f)
        val effects = compose.runOnIdle {
            val observation = f.observation()!!
            assertTrue(observation.isSatisfied(TerminalItemScrollTarget.Follow))
            val last = observation.rows.first { it.index == observation.followRowIndex }
            assertEquals(observation.viewportHeightPx, last.bottomPx + observation.tailPaddingPx)
            f.bound.binding.effectCount
        }
        compose.mainClock.advanceTimeBy(800)
        settle(f)
        compose.runOnIdle { assertEquals(effects, f.bound.binding.effectCount) }
    }

    @Test fun itemNativeRealKeyboardInsetsRetainLockAndPermitExplicitRetry() {
        val f = mount(Fixture(stressSpans = false, systemIme = true))
        val initial = compose.runOnIdle { f.top().anchor }
        val widths = compose.runOnIdle { f.bound.binding.widthIndex }
        val historyWork = compose.runOnIdle { widths.measuredHistoryRows }
        val screenWork = compose.runOnIdle { widths.measuredScreenRows }
        showSystemKeyboard(f)
        settle(f)
        repeat(3) { update ->
            compose.runOnIdle { f.terminal.feed("\r\u001B[2Kime-$update"); f.publish() }
            settle(f)
        }
        compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertEquals(initial, f.top().anchor)
            assertEquals(24, f.terminal.rows)
            assertTrue(widths === f.bound.binding.widthIndex)
            assertTrue(widths.hasRetainedState)
            assertEquals(historyWork, widths.measuredHistoryRows)
            assertEquals(screenWork, widths.measuredScreenRows)
            f.keyboard?.hide()
        }
        compose.waitUntil(10_000) { !f.ime }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.policy.reapplied(false) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertEquals(initial, f.top().anchor)
            assertTrue(widths === f.bound.binding.widthIndex)
            assertEquals(historyWork, widths.measuredHistoryRows)
            assertEquals(screenWork + 24, widths.measuredScreenRows)
        }
    }

    @Test fun itemNativeDormantWidthIndexTrimsHiddenMaximumAndMeasuresOnlyNewArchives() {
        val f = mount(Fixture(wideHead = true, stressSpans = false))
        val widths = compose.runOnIdle { f.bound.binding.widthIndex }
        val historyWork = compose.runOnIdle { widths.measuredHistoryRows }
        val screenWork = compose.runOnIdle { widths.measuredScreenRows }
        val wide = compose.runOnIdle { f.horizontal.maxValue }
        val anchor = compose.runOnIdle { f.top().anchor }
        compose.runOnIdle { f.ime = true }
        settle(f)
        repeat(3) { compose.runOnIdle { f.append() }; settle(f) }
        val eagerRange = compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertEquals(historyWork, widths.measuredHistoryRows)
            assertEquals(screenWork, widths.measuredScreenRows)
            assertEquals(anchor, f.top().anchor)
            f.ime = false
            f.horizontal.maxValue
        }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.policy.reapplied(false) }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertTrue(widths === f.bound.binding.widthIndex)
            assertEquals(historyWork + 3, widths.measuredHistoryRows)
            assertEquals(screenWork + 24, widths.measuredScreenRows)
            assertEquals(anchor, f.top().anchor)
            assertTrue(f.horizontal.maxValue < wide)
            assertEquals(eagerRange, f.horizontal.maxValue)
        }
    }

    @Test fun itemNativeDormantFontDensityAndDirectionChangesInvalidateBeforeRetry() {
        val f = mount(Fixture(wideHead = true, stressSpans = false, historyRows = 256))
        val widths = compose.runOnIdle { f.bound.binding.widthIndex }
        val changes: List<() -> Unit> = listOf(
            { f.fontSp = 19 }, { f.fontFamily = FontFamily.Serif },
            { f.densityOverride = Density(1.33f, 1.15f) }, { f.rtl = true },
        )
        for (change in changes) {
            val historyWork = compose.runOnIdle { widths.measuredHistoryRows }
            val screenWork = compose.runOnIdle { widths.measuredScreenRows }
            compose.runOnIdle { f.ime = true }
            settle(f)
            compose.runOnIdle { change() }
            settle(f)
            val eagerRange = compose.runOnIdle {
                assertFalse(f.isVirtual)
                assertFalse(widths.hasRetainedState)
                assertEquals(historyWork, widths.measuredHistoryRows)
                assertEquals(screenWork, widths.measuredScreenRows)
                f.ime = false
                f.horizontal.maxValue
            }
            settle(f)
            compose.runOnIdle { f.policy.reapplied(false) }
            settle(f)
            compose.runOnIdle {
                assertTrue(f.isVirtual)
                assertTrue(widths === f.bound.binding.widthIndex)
                assertEquals(historyWork + f.frame.historyCount, widths.measuredHistoryRows)
                assertEquals(eagerRange, f.horizontal.maxValue)
            }
        }
    }

    @Test fun itemNativeSessionReplacementReleasesPreviousWidthOwnership() {
        val first = Fixture(stressSpans = false, historyRows = 256)
        var shown by mutableStateOf(first, referentialEqualityPolicy())
        compose.setContent { shown.Content() }
        settle(first)
        val oldWidths = compose.runOnIdle { first.bound.binding.widthIndex }
        val replacement = Fixture(stressSpans = false, historyRows = 128)
        compose.runOnIdle { assertTrue(oldWidths.hasRetainedState); shown = replacement }
        settle(replacement)
        compose.runOnIdle {
            assertFalse(oldWidths.hasRetainedState)
            assertEquals(0, oldWidths.retainedCandidates)
            assertTrue(oldWidths !== replacement.bound.binding.widthIndex)
            assertEquals(128L, replacement.bound.binding.widthIndex.measuredHistoryRows)
        }
    }

    @Test fun itemNativeDefaultEagerRealImeUpdatesGeometryWithoutOutputOrVirtualMeasurements() {
        val f = mount(Fixture(stressSpans = false, systemIme = true, initialMode = TerminalRenderMode.DEFAULT))
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        val before = compose.runOnIdle { f.eager.value }
        showSystemKeyboard(f)
        settle(f)
        compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertEquals(0, f.measurements.retainedRows)
            assertFalse(f.bound.binding.widthIndex.hasRetainedState)
            assertEquals(0L, f.bound.binding.widthIndex.measuredHistoryRows)
            assertEquals(0L, f.bound.binding.widthIndex.measuredScreenRows)
            assertTrue("IME did not adjust the idle terminal", f.eager.value > before)
            assertTrue(f.controller.isNearBottom(f.eager.value))
            f.keyboard?.hide()
        }
        compose.waitUntil(10_000) { !f.ime }
        settle(f)
        compose.runOnIdle { assertEquals(before, f.eager.value); assertEquals(24, f.terminal.rows) }
    }

    @Test fun itemNativeTuiAlternateScreenAndFullGridFallbackReturnWithoutChangingPreference() {
        val f = mount(Fixture(stressSpans = false))
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()); f.tui = true }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); assertEquals(TerminalRenderMode.VIRTUAL_HISTORY, f.mode) }
        compose.runOnIdle { f.tui = false }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); f.terminal.feed("\u001B[?1049hAlternate"); f.publish() }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); f.terminal.feed("\u001B[?1049l"); f.publish() }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); assertTrue(f.controller.state.value.autoScroll) }
    }

    @Test fun itemNativeEagerFallbackDoesNotRecomposeArchivedHistoryOnActiveUpdates() {
        val f = mount(Fixture(wideHead = true, stressSpans = false))
        compose.runOnIdle { f.ime = true }
        settle(f)
        val history = compose.runOnIdle { f.frame.historyLineIds.toHashSet() }
        val prefix = compose.runOnIdle { requireNotNull(f.bound.binding.eagerGeometry()).historyPrefix }
        val historyVisits = compose.runOnIdle { f.measurements.eagerVisitedHistoryRows }
        val historyBuilds = compose.runOnIdle { f.measurements.eagerHistoryBuildCount }
        val screenVisits = compose.runOnIdle { f.measurements.eagerVisitedScreenRows }
        var historyCompositions = 0
        var screenCompositions = 0
        compose.runOnIdle {
            assertFalse(f.isVirtual)
            f.measurements.onRowComposed = { id ->
                if (id in history) historyCompositions++ else screenCompositions++
            }
        }
        repeat(8) { update ->
            compose.runOnIdle {
                f.terminal.feed("\r\u001B[2Klive-$update")
                f.publish()
            }
            settle(f)
        }
        compose.runOnIdle {
            assertTrue("probe did not observe updated screen rows", screenCompositions > 0)
            assertEquals("activity invalidated unchanged history chunks", 0, historyCompositions)
            assertTrue("history prefix was copied", prefix === f.bound.binding.eagerGeometry()!!.historyPrefix)
            assertEquals("activity rescanned historical heights", historyVisits, f.measurements.eagerVisitedHistoryRows)
            assertEquals("activity rebuilt historical prefix", historyBuilds, f.measurements.eagerHistoryBuildCount)
            assertTrue("activity did not validate screen heights", f.measurements.eagerVisitedScreenRows > screenVisits)
            f.measurements.onRowComposed = null
        }
    }

    internal class Fixture(
        private val wideHead: Boolean = false,
        private val stressSpans: Boolean = true,
        private val systemIme: Boolean = false,
        private val initialMode: TerminalRenderMode = TerminalRenderMode.VIRTUAL_HISTORY,
        private val historyRows: Int = 1_000,
        terminalOverride: TerminalEmulator? = null,
        restored: TerminalViewportState? = null,
        val sessionWidthIndex: TerminalTranscriptWidthIndex? = null,
    ) {
        val terminal = terminalOverride ?: TerminalEmulator(initialColumns = 80, initialRows = 24,
            maxScrollbackLines = historyRows).apply {
            feed("\u001B[?25l" + (0 until historyRows + 24).joinToString("\r\n") {
                if (wideHead && it == 0) "W".repeat(79) else "r$it 中文 e\u0301 " + "x".repeat(it % 20 + 20)
            })
        }
        private fun render(): TerminalEmulator.RenderFrame {
            val raw = terminal.renderFrame()
            if (!stressSpans) return raw
            val ids = raw.historyLineIds + raw.screenLineIds
            return raw.copy(rows = raw.rows.mapIndexed { i, row ->
                if (ids[i] % 7L != 0L) row else TerminalEmulator.RenderedRow(buildAnnotatedString {
                    append(row.text); addStyle(SpanStyle(fontSize = 29.sp), 0, length)
                })
            })
        }
        var frame by mutableStateOf(render(), referentialEqualityPolicy())
        val saved = restored ?: TerminalViewportState(autoScroll = false, anchorLineId = frame.historyLineIds[frame.historyCount / 2],
            anchorClippedTopPx = 7, anchorHistoryGeneration = frame.historyGeneration)
        val controller = TerminalViewportController(saved)
        val rows = createTerminalRenderedRows(frame)
        private val sync = createTerminalRenderedRowsSyncState(frame, rows)
        val lazy = LazyListState()
        val horizontal = ScrollState(saved.horizontalOffsetPx)
        val measurements = TerminalLazyItemMeasurements()
        val eager = ScrollState(if (saved.autoScroll) Int.MAX_VALUE else saved.verticalOffsetPx)
        var fontSp by mutableIntStateOf(14)
        var fontFamily by mutableStateOf<FontFamily>(JetbrainsMono)
        var densityOverride by mutableStateOf<Density?>(null)
        var heightDp by mutableIntStateOf(220)
        var config by mutableStateOf(TerminalViewportGestureConfig(true, false, 2))
        var mode by mutableStateOf(initialMode)
        var ime by mutableStateOf(false)
        var avoidIme by mutableStateOf(true)
        var tui by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        var showRendererDialog by mutableStateOf(false)
        var inputText by mutableStateOf("")
        var keyboard: SoftwareKeyboardController? = null
        val inputFocus = FocusRequester()
        @Volatile var inputFocused = false
        lateinit var bound: TerminalBoundViewport
        lateinit var policy: TerminalVirtualHistoryPolicy
        val isVirtual: Boolean get() = bound.virtualHistoryEnabled
        var viewportHeight by mutableIntStateOf(0)
        var viewportWidth = 0
        var cellHeight = 1
        var tailPadding = 1
        val activeWriters get() = if (::bound.isInitialized) bound.binding.activeWriters else 0
        val maximumWriters get() = bound.binding.maximumWriters
        var outputDuringJump = false
        @Volatile var appendCount = 0
        private lateinit var scope: CoroutineScope

        fun observation(): TerminalItemViewport? = bound.binding.observation()
        fun inputPx(): Int = bound.binding.currentScrollPx()
        fun top() = if (isVirtual) requireNotNull(observation()).capture()!!
            else requireNotNull(bound.binding.eagerGeometry()).capture(eager.value)
        fun panHorizontal(px: Int) { scope.launch { horizontal.scrollTo(px) } }

        fun snapshotViewport(): TerminalViewportState {
            val pixels = inputPx()
            val state = controller.state.value
            return TerminalViewportState(verticalOffsetPx = pixels, horizontalOffsetPx = horizontal.value,
                autoScroll = state.autoScroll, atBottom = controller.isNearBottom(pixels), viewportMode = state.mode,
                anchorLineId = state.anchor?.lineId, anchorClippedTopPx = state.anchor?.clippedTopPx ?: 0,
                anchorCellHeightPx = state.anchorCellHeightPx, anchorRowHeightPx = state.anchorRowHeightPx,
                anchorHistoryGeneration = state.anchor?.historyGeneration, anchorScreenGeneration = state.anchor?.screenGeneration)
        }

        fun stream(frames: Int, linesPerFrame: Int = 1, beforeFrame: (Int) -> Unit = {}) = scope.launch {
            repeat(frames) { frameIndex ->
                withFrameNanos { }
                beforeFrame(frameIndex)
                repeat(linesPerFrame) { terminal.feed("\r\nstream-${appendCount++} \u001B[32m中文\u001B[0m") }
                publish()
            }
        }

        fun append() {
            terminal.feed("\r\nappend-${appendCount++} 中文")
            publish()
        }

        fun publish() {
            val next = render()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, forcePendingGridBlanks = true,
                    nowMs = 1_000L, syncState = sync)
                frame = next
            }
        }

        @Composable fun Content() {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides (densityOverride ?: LocalDensity.current),
            ) {
                Output()
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        @Composable private fun Output() {
            val density = LocalDensity.current
            val actualIme = WindowInsets.isImeVisible
            keyboard = LocalSoftwareKeyboardController.current
            SideEffect { if (systemIme) ime = actualIme }
            val style = TextStyle(fontFamily = fontFamily, fontSize = fontSp.sp, lineHeight = fontSp.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            val textMeasurer = rememberTextMeasurer()
            cellHeight = textMeasurer.measure("W", style).size.height.coerceAtLeast(1)
            tailPadding = with(density) { 8.dp.roundToPx() }
            val chunks = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, tui || frame.isAlternateScreen)
            policy = rememberTerminalVirtualHistoryPolicy(this, mode, ime)
            val wants = mode.isVirtualHistory &&
                policy.allows(chunks.isNotEmpty(), config.panEnabled, config.selectionMode,
                    tui || frame.isAlternateScreen, ime, avoidIme)
            bound = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements, frame, style, wants,
                sessionWidthIndex = sessionWidthIndex,
                metrics = { TerminalViewportMetrics(eager.maxValue, viewportHeight, cellHeight, tailPadding,
                    usesTuiViewport = tui || frame.isAlternateScreen, imeVisible = ime, avoidIme = ime && avoidIme) },
                gestureConfig = config)
            SideEffect {
                bound.binding.onEffectStarted = { effect ->
                    if (effect.animated && outputDuringJump) {
                        outputDuringJump = false
                        scope.launch { repeat(30) { withFrameNanos { }; append() } }
                    }
                }
            }
            MaterialTheme {
                val output: @Composable (Modifier) -> Unit = { modifier ->
                    Box(modifier.testTag("item-output")
                        .onSizeChanged { viewportHeight = it.height; viewportWidth = it.width }
                        .nestedScroll(bound.gestures.connection)) {
                        TerminalTranscriptViewport(frame, rows.toList(), style, chunks, mode, bound,
                            horizontal, config.panEnabled, config.selectionMode, Modifier.fillMaxSize())
                    }
                }
                if (systemIme) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        output(Modifier.weight(1f).fillMaxWidth())
                        BasicTextField(inputText, { inputText = it },
                            Modifier.fillMaxWidth().testTag("system-input")
                                .focusRequester(inputFocus).onFocusChanged { inputFocused = it.isFocused }, textStyle = style)
                    }
                } else output(Modifier.size(320.dp, heightDp.dp))
                if (showRendererDialog) {
                    TerminalRenderModeDialog(mode, chunks.isNotEmpty(), tui,
                        virtualHistoryAllowed = isVirtual, virtualHistoryImeFallback = policy.imeFallback,
                        onDismiss = { showRendererDialog = false }, onSave = {
                            mode = it
                            policy.reapplied(ime)
                            showRendererDialog = false
                        })
                }
            }
        }
    }
}
