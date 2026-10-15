package me.rerere.rikkahub.viewporttest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.ui.pages.container.TerminalSparseRows
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

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
