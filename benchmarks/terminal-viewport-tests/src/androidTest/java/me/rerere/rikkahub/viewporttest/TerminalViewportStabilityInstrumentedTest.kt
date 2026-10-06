package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.terminalScaledItemClip
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

/** Stress the exact production renderer/binding, not a benchmark-local replacement. */
class TerminalViewportStabilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(e: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "stability lazy=true failed ${description.methodName}", e)
        }
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.controller.state.value.initialized && f.controller.state.value.scrollEffect == null && f.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("overlapping scroll writers", f.maximumWriters <= 1)
            if (f.isVirtual) {
                assertTrue("unbounded retained row measurements", f.measurements.retainedRows < 100)
                assertEquals("virtual backend retained an eager history prefix", 0, f.measurements.retainedEagerHistoryRows)
                assertTrue("missing completed layout", f.observation() != null)
            }
        }
    }

    @Test fun stabilityTenThousandRowsStreamKeepsLockedIdClippingAndBoundedMeasurements() {
        val f = Fixture(stressSpans = false, historyRows = 10_000)
        compose.setContent { f.Content() }
        settle(f)
        val anchor = compose.runOnIdle { f.top().anchor }
        val initialEffects = compose.runOnIdle { f.bound.binding.effectCount }
        val output = compose.runOnIdle { f.stream(frames = 180, linesPerFrame = 8) }
        compose.waitUntil(25_000) { output.isCompleted }
        settle(f)
        compose.runOnIdle {
            assertEquals(1_440, f.appendCount)
            assertEquals(10_000, f.frame.historyCount)
            assertEquals(anchor, f.top().anchor)
            assertFalse(f.controller.state.value.autoScroll)
            // Stable LazyList keys should retain the anchor on ordinary FIFO trims; no correction storm.
            assertTrue("repeated correction storm", f.bound.binding.effectCount - initialEffects <= 2)
        }
    }

    @Test fun stabilitySwitchDuringTopAnimationCancelsOldWriterButPreservesTopIntent() {
        val f = Fixture(stressSpans = false)
        compose.setContent { f.Content() }
        settle(f)
        // Leave a nonzero dormant eager ScrollState. A missing handoff must not accidentally pass
        // just because a never-used eager state happens to start at zero.
        compose.runOnIdle { f.mode = TerminalRenderMode.CHUNKED_LAYERS }
        settle(f)
        compose.runOnIdle { assertTrue(f.eager.value > 0); f.mode = TerminalRenderMode.VIRTUAL_HISTORY }
        settle(f)
        lateinit var old: TerminalViewportScrollEffect
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { f.controller.jumpToTop(f.inputPx()) }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread {
                old = requireNotNull(f.controller.state.value.scrollEffect)
                assertEquals(TerminalItemScrollTarget.Top, old.itemTarget)
                f.mode = TerminalRenderMode.FLAT
                repeat(10) { f.append() }
            }
            compose.mainClock.advanceTimeBy(64)
        } finally { compose.mainClock.autoAdvance = true }
        settle(f)
        compose.runOnIdle {
            assertFalse(f.controller.isCurrent(old))
            assertEquals(0, f.eager.value)
            assertEquals(f.frame.historyLineIds.first(), f.controller.state.value.anchor?.lineId)
            assertFalse(f.controller.state.value.autoScroll)
            assertEquals(1, f.maximumWriters)
        }
    }

    @Test fun stabilityDetachAndRecreateRestoresMeasuredAnchorAfterBackgroundTrim() {
        val sharedWidthIndex = TerminalTranscriptWidthIndex()
        val f = Fixture(stressSpans = false, sessionWidthIndex = sharedWidthIndex)
        var shown by mutableStateOf<Fixture?>(f, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        settle(f)
        compose.runOnIdle { f.panHorizontal(47); f.controller.setFollow(false, f.inputPx()) }
        settle(f)
        val saved = compose.runOnIdle { f.snapshotViewport() }
        val oldAnchor = compose.runOnIdle { f.top().anchor }
        val widthIndex = compose.runOnIdle { f.bound.binding.widthIndex }
        val measuredHistory = compose.runOnIdle { widthIndex.measuredHistoryRows }
        val measuredScreen = compose.runOnIdle { widthIndex.measuredScreenRows }
        compose.runOnIdle { shown = null }
        compose.waitForIdle()
        val recreated = compose.runOnIdle {
            assertEquals(0, f.measurements.retainedRows)
            assertEquals(0, f.activeWriters)
            assertTrue("session width state was cleared with the page", widthIndex.hasRetainedState)
            f.terminal.feed((0 until 120).joinToString("\r\n", prefix = "\r\n") { "background-$it" })
            Fixture(stressSpans = false, terminalOverride = f.terminal, restored = saved,
                sessionWidthIndex = sharedWidthIndex).also { shown = it }
        }
        settle(recreated)
        compose.runOnIdle {
            assertEquals(oldAnchor, recreated.top().anchor)
            assertEquals(47, recreated.horizontal.value)
            assertFalse(recreated.controller.state.value.autoScroll)
            assertEquals(0, f.measurements.retainedRows)
            assertTrue(widthIndex === recreated.bound.binding.widthIndex)
            assertEquals(120, widthIndex.lastMeasuredHistoryRows)
            assertEquals(24, widthIndex.lastMeasuredScreenRows)
            assertEquals(measuredHistory + 120, widthIndex.measuredHistoryRows)
            assertEquals(measuredScreen + 24, widthIndex.measuredScreenRows)
        }
    }

    @Test fun stabilityOutputDuringRepeatedModeAndImeFallbackChangesSettlesAtLatestTail() {
        val f = Fixture(stressSpans = false)
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle { f.controller.setFollow(true, f.inputPx()) }
        settle(f)
        val output = compose.runOnIdle {
            f.stream(frames = 120) { index ->
                when (index % 20) {
                    0 -> f.mode = TerminalRenderMode.FLAT
                    5 -> f.mode = TerminalRenderMode.VIRTUAL_HISTORY
                    10 -> { f.ime = true; f.heightDp = 140 }
                    15 -> { f.ime = false; f.heightDp = 220; f.policy.reapplied(false) }
                }
            }
        }
        compose.waitUntil(25_000) { output.isCompleted }
        settle(f)
        compose.runOnIdle {
            assertEquals(120, f.appendCount)
            assertTrue(f.isVirtual)
            assertTrue(f.controller.state.value.autoScroll)
            assertTrue(f.observation()!!.isSatisfied(TerminalItemScrollTarget.Follow))
        }
        val effects = compose.runOnIdle { f.bound.binding.effectCount }
        compose.mainClock.advanceTimeBy(800)
        settle(f)
        compose.runOnIdle { assertEquals(effects, f.bound.binding.effectCount) }
    }

    @Test fun stabilityTenThousandRowFallbackSharesHistoryAndReleasesPrefixOnReturn() {
        val f = Fixture(wideHead = true, stressSpans = false, historyRows = 10_000)
        compose.setContent { f.Content() }
        settle(f)
        val original = compose.runOnIdle { f.top() }
        compose.runOnIdle { f.ime = true }
        settle(f)
        val before = compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertEquals(10_000, f.measurements.retainedEagerHistoryRows)
            assertTrue("full fallback did not coalesce row invalidations", f.measurements.coalescedChanges >= 9_000)
            assertTrue("row notifications remain proportional to history",
                f.measurements.publishedNotifications < f.measurements.coalescedChanges / 10)
            requireNotNull(f.bound.binding.eagerGeometry())
        }
        val totalHeight = before.contentHeightPx
        val visits = compose.runOnIdle { f.measurements.eagerVisitedHistoryRows }
        val builds = compose.runOnIdle { f.measurements.eagerHistoryBuildCount }
        var notifications = compose.runOnIdle { f.measurements.publishedNotifications }
        repeat(12) { update ->
            compose.runOnIdle {
                f.terminal.feed("\r\u001B[2Kactive-$update 中文")
                f.publish()
            }
            settle(f)
            compose.runOnIdle {
                val current = requireNotNull(f.bound.binding.eagerGeometry())
                assertTrue(before.historyPrefix === current.historyPrefix)
                assertEquals(visits, f.measurements.eagerVisitedHistoryRows)
                assertEquals(builds, f.measurements.eagerHistoryBuildCount)
                assertTrue("later frame lost its measurement wakeup", f.measurements.publishedNotifications > notifications)
                notifications = f.measurements.publishedNotifications
                assertEquals(original.anchor, f.top().anchor)
            }
        }
        compose.runOnIdle {
            assertEquals("published old geometry was mutated", totalHeight, before.contentHeightPx)
            f.ime = false
            f.policy.reapplied(false)
        }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertEquals(0, f.measurements.retainedEagerHistoryRows)
            assertEquals(original.anchor, f.top().anchor)
        }
    }

    @Test fun stabilityEagerPrefixRebuildsOnFontResizeAndTrimWithoutAnchorDrift() {
        val f = Fixture(stressSpans = false)
        compose.setContent { f.Content() }
        settle(f)
        compose.runOnIdle { f.controller.setFollow(false, f.inputPx()); f.ime = true }
        settle(f)
        val original = compose.runOnIdle { f.top() }
        val initialPrefix = compose.runOnIdle { f.bound.binding.eagerGeometry()!!.historyPrefix }
        val builds = compose.runOnIdle { f.measurements.eagerHistoryBuildCount }
        compose.runOnIdle { f.fontSp = 21 }
        settle(f)
        compose.runOnIdle {
            assertTrue(initialPrefix !== f.bound.binding.eagerGeometry()!!.historyPrefix)
            assertTrue(f.measurements.eagerHistoryBuildCount > builds)
            val resized = f.top()
            assertEquals(original.anchor.lineId, resized.anchor.lineId)
            assertEquals(terminalScaledItemClip(original.anchor.clippedTopPx, original.capturedRowHeightPx,
                resized.capturedRowHeightPx), resized.anchor.clippedTopPx)
            f.fontSp = 14
        }
        settle(f)
        val beforeTrim = compose.runOnIdle { f.bound.binding.eagerGeometry()!!.historyPrefix }
        compose.runOnIdle { repeat(10) { f.append() } }
        settle(f)
        compose.runOnIdle {
            assertTrue(beforeTrim !== f.bound.binding.eagerGeometry()!!.historyPrefix)
            assertEquals(original.anchor, f.top().anchor)
            f.terminal.feed("\u001B[3J")
            f.publish()
        }
        settle(f)
        compose.runOnIdle {
            assertEquals(0, f.frame.historyCount)
            assertEquals(0, f.measurements.retainedEagerHistoryRows)
            assertTrue(f.controller.state.value.autoScroll)
        }
    }

    @Test fun stabilityEagerFallbackDisposalReleasesAllMeasurementAndPrefixEntries() {
        val f = Fixture(stressSpans = false)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) f.Content() }
        settle(f)
        compose.runOnIdle { f.ime = true }
        settle(f)
        compose.runOnIdle {
            assertFalse(f.isVirtual)
            assertEquals(f.frame.historyCount, f.measurements.retainedEagerHistoryRows)
            assertEquals(f.frame.rows.size, f.measurements.retainedRows)
            assertTrue(f.bound.binding.widthIndex.hasRetainedState)
            shown = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, f.measurements.retainedRows)
            assertEquals(0, f.measurements.retainedEagerHistoryRows)
            assertEquals(0, f.activeWriters)
            assertFalse(f.bound.binding.widthIndex.hasRetainedState)
            assertEquals(0, f.bound.binding.widthIndex.retainedCandidates)
        }
    }

    @Test fun stabilityDisposalDuringJumpReleasesRowsAndStopsTheWriter() {
        val f = Fixture(stressSpans = false)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) f.Content() }
        settle(f)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { f.controller.jumpToBottom(f.inputPx()) }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread { assertEquals(1, f.activeWriters); shown = false }
            compose.mainClock.advanceTimeBy(64)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, f.measurements.retainedRows)
            assertEquals(0, f.activeWriters)
            assertNull(f.controller.state.value.scrollEffect)
            assertFalse(f.bound.binding.widthIndex.hasRetainedState)
            assertEquals(0, f.bound.binding.widthIndex.retainedCandidates)
        }
    }
}
