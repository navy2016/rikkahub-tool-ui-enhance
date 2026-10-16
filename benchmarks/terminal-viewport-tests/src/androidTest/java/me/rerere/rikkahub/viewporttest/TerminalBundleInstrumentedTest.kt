package me.rerere.rikkahub.viewporttest

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalPipelineSettleProbe
import me.rerere.rikkahub.data.container.TerminalPipelineViewport
import me.rerere.rikkahub.data.container.TerminalPipelineViewportSample
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.ui.pages.container.TerminalSparseRows
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicReference

/** The new sparse/async branch; legacy full-row geometry regressions remain independently runnable. */
class TerminalBundleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(150)
    private fun fixture(size: Int = 1000, checkpoint: TerminalWidthCheckpointStore? = null) = Fixture(
        stressSpans = false, historyRows = size, foreground = Color(0xFF00E676), sparse = true,
        initialMode = TerminalRenderMode.VIRTUAL_HISTORY_IME, widthCheckpoint = checkpoint,
    )

    private fun settle(f: Fixture) {
        compose.waitUntil(30_000) {
            runCatching {
                val state = f.controller.state.value
                state.initialized && state.scrollEffect == null && f.activeWriters == 0 &&
                    (if (f.isVirtual) f.observation() != null &&
                        f.bound.binding.widthIndex.readyFor(f.frame, f.bound.pass.metricKey)
                    else f.measurements.peekEager(f.bound.pass) != null)
            }.getOrDefault(false)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(f.maximumWriters <= 1) }
    }

    @Test fun bundleSparseThousandRowsKeepNaturalAnchorAndWidestOffscreenRange() = sparseGeometry(1000)
    @Test fun bundleSparseFiveThousandRowsKeepNaturalAnchorAndWidestOffscreenRange() = sparseGeometry(5000)
    @Test fun bundleSparseTenThousandRowsKeepNaturalAnchorAndWidestOffscreenRange() = sparseGeometry(10000)

    @Test fun bundleVisibleEagerDrawDoesNotCompletePendingVirtualHandoff() {
        val f = Fixture(stressSpans = false, historyRows = 1000, foreground = Color(0xFF00E676), sparse = true,
            initialMode = TerminalRenderMode.DEFAULT, restored = TerminalViewportState(autoScroll = true))
        var pulse by mutableIntStateOf(0)
        var draw = 0L
        val observed = AtomicReference<TerminalPipelineViewportSample?>()
        compose.setContent {
            Box(Modifier.drawWithContent {
                pulse
                drawContent()
                val state = f.controller.state.value
                val frame = f.frame
                val virtual = f.isVirtual
                val items = if (virtual) f.observation() else null
                val geometry = f.measurements.peekEager(f.bound.pass)
                val bottom = frame.contentBounds.lastNonBlankRow?.let { geometry?.bottom(it) }
                val target = bottom?.let { (it + f.tailPadding - f.viewportHeight).coerceIn(0, f.eager.maxValue) }
                val policy = f.policy.allows(true, true, false, false, false)
                val value = TerminalPipelineViewport(
                    revision = frame.revision, preferred = f.mode, applied = f.mode, historyRows = frame.historyCount,
                    hasChunks = true, pan = true, selection = false, tui = false, alternate = false,
                    ime = false, avoidIme = false, imeFallback = f.policy.imeFallback,
                    wantsVirtual = f.mode.isVirtualHistory && policy, virtual = virtual,
                    bindingVirtual = f.bound.binding.virtual, sameFrame = f.bound.pass.frame === frame,
                    initialized = state.initialized, following = state.autoScroll,
                    gestureId = state.gesture?.id, effectId = state.scrollEffect?.id, writers = f.activeWriters,
                    scrolling = if (virtual) f.lazy.isScrollInProgress else f.eager.isScrollInProgress,
                    geometryReady = if (virtual) items != null else geometry != null,
                    widthReady = f.bound.binding.widthIndex.readyFor(frame, f.bound.pass.metricKey),
                    tailSatisfied = if (virtual) items?.isSatisfied(TerminalItemScrollTarget.Follow) == true
                        else target != null && f.eager.value == target,
                )
                observed.set(TerminalPipelineViewportSample(++draw, System.nanoTime(), value))
            }) { f.Content() }
        }
        settle(f)
        var gesture = 0L
        val revision = compose.runOnIdle {
            // Fling ownership ends explicitly; the real idle-drag cleanup would finish a synthetic drag.
            gesture = f.controller.beginUserScroll(ViewportScrollOrigin.USER_FLING, f.inputPx())
            f.mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
            pulse++
            f.frame.revision
        }
        compose.waitForIdle()
        val probe = TerminalPipelineSettleProbe()
        repeat(3) {
            compose.runOnIdle { pulse++ }
            compose.waitForIdle()
            val sample = requireNotNull(observed.get())
            assertTrue(sample.viewport.wantsVirtual)
            assertFalse(sample.viewport.virtual)
            assertFalse(probe.observe(sample, f.mode, true, false, revision))
        }
        // Independent visibility oracle: the eager tail Text is visible even though handoff is not complete.
        val tail = f.frame.rows[requireNotNull(f.frame.contentBounds.lastNonBlankRow)].text
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.config[SemanticsProperties.Text].any { text -> text == tail } }
        val viewport = compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val top = node.positionInRoot.y - viewport.positionInRoot.y
        assertTrue(top >= -1 && top + node.size.height <= viewport.size.height + 1)
        compose.runOnIdle { f.controller.endUserScroll(gesture) }
        compose.waitUntil(15_000) {
            val done = probe.observe(observed.get(), f.mode, true, false, revision)
            if (!done) compose.runOnUiThread { pulse++ }
            done
        }
        compose.runOnIdle {
            assertTrue(f.isVirtual)
            assertEquals(revision, f.frame.revision) // No new output was needed to finish the transition.
            assertTrue(requireNotNull(observed.get()).viewport.tailSatisfied)
            assertTrue(f.maximumWriters <= 1)
        }
    }

    private fun sparseGeometry(size: Int) {
        val f = fixture(size)
        compose.setContent { f.Content() }
        settle(f)
        val anchor = compose.runOnIdle { f.top().anchor }
        val width = compose.runOnIdle { f.horizontal.maxValue }
        compose.runOnIdle {
            assertTrue(f.rows is TerminalSparseRows)
            assertEquals(24, f.rowStore.retainedRowStates)
            assertTrue(f.measurements.retainedRows < 128)
            f.mode = TerminalRenderMode.DEFAULT
        }
        settle(f)
        compose.runOnIdle {
            assertEquals(anchor, f.top().anchor)
            assertEquals(width, f.horizontal.maxValue)
            assertEquals(size + 24, f.measurements.retainedRows)
            f.mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
        }
        settle(f)
        repeat(12) { compose.runOnIdle { f.append() }; settle(f) }
        compose.runOnIdle {
            assertEquals(anchor, f.top().anchor)
            assertEquals(24, f.rowStore.retainedRowStates)
            assertTrue(f.measurements.retainedRows < 128)
        }
    }

    @Test fun bundleWarmRemountMeasuresOnlyTwelveNewHistoryRowsAndReleasesOldOwner() {
        val checkpoint = TerminalWidthCheckpointStore()
        val old = fixture(10000, checkpoint)
        var shown by mutableStateOf<Fixture?>(old, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        settle(old)
        compose.runOnIdle { old.panHorizontal(47) }
        settle(old)
        compose.runOnIdle { assertEquals(47, old.horizontal.value) }
        val saved = compose.runOnIdle { old.snapshotViewport() }
        compose.runOnIdle { shown = null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, old.measurements.retainedRows)
            assertFalse(old.bound.binding.widthIndex.hasRetainedState)
            assertTrue(checkpoint.retainedRows() > 0)
        }
        old.terminal.feed((0 until 12).joinToString("\r\n", prefix = "\r\n") { "background-$it" })
        val next = Fixture(stressSpans = false, terminalOverride = old.terminal, restored = saved,
            foreground = Color(0xFF00E676), sparse = true, widthCheckpoint = checkpoint,
            initialMode = TerminalRenderMode.VIRTUAL_HISTORY_IME)
        compose.runOnIdle { shown = next }
        settle(next)
        compose.runOnIdle {
            assertEquals(12L, next.bound.binding.widthIndex.measuredHistoryRows)
            assertEquals(saved.anchorLineId, next.top().anchor.lineId)
            assertEquals(saved.anchorClippedTopPx, next.top().anchor.clippedTopPx)
            assertEquals(saved.horizontalOffsetPx, next.horizontal.value)
            shown = null
        }
        compose.waitForIdle()
        checkpoint.close()
        assertEquals(0, checkpoint.retainedRows())
    }

    @Test fun bundleFontChangeAndClearInvalidateColdWorkWithoutPublishingOldGeometry() {
        val f = fixture(10000)
        compose.setContent { f.Content() }
        compose.runOnUiThread {
            f.fontSp = 19
            f.terminal.clearScrollbackOnly()
            f.terminal.feed((0 until 256).joinToString("\r\n", prefix = "\r\n") { "changed-$it" })
            f.publish()
        }
        settle(f)
        compose.runOnIdle {
            assertTrue(f.frame.historyCount < 1000)
            assertTrue(f.bound.binding.widthIndex.readyFor(f.frame, f.bound.pass.metricKey))
            assertEquals(24, f.rowStore.retainedRowStates)
            assertTrue(f.maximumWriters <= 1)
        }
    }

    @Test fun bundleSparseImeSelectionMouseFallbacksPreserveMeasuredAnchor() {
        val f = fixture()
        compose.setContent { f.Content() }
        settle(f)
        val original = compose.runOnIdle { f.top().anchor }
        for (selection in listOf(true, false)) {
            compose.runOnIdle { f.config = f.config.copy(selectionMode = selection) }
            settle(f)
            compose.runOnIdle { assertEquals(original, f.top().anchor); assertEquals(!selection, f.isVirtual) }
        }
        compose.runOnIdle { f.ime = true }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); assertEquals(original, f.top().anchor) }
        compose.runOnIdle { f.ime = false; f.config = f.config.copy(panEnabled = false) }
        settle(f)
        compose.runOnIdle { assertFalse(f.isVirtual); assertEquals(original, f.top().anchor) }
        compose.runOnIdle { f.config = f.config.copy(panEnabled = true) }
        settle(f)
        compose.runOnIdle { assertTrue(f.isVirtual); assertEquals(original, f.top().anchor) }
    }
}
