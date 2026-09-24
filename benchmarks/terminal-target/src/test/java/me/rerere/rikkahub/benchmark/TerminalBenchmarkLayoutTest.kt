package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBenchmarkLayoutTest {
    @Test
    fun historyIsLazyButScreenIsOneItemAndTheTailIsAlwaysLast() {
        for (size in TerminalBenchmarkWorkload.historySizes) {
            val frame = TerminalBenchmarkWorkload.prepare(size).renderFrame()
            val layout = TerminalBenchmarkLayout.fromFrame(frame, BenchmarkRenderer.LAZY_HISTORY)
            assertTrue(layout.useLazyHistory)
            assertEquals(size, layout.historyRows)
            assertEquals(TerminalBenchmarkWorkload.SCREEN_ROWS, layout.screenRows)
            assertEquals(size, layout.activeScreenItemIndex)
            assertEquals(size + 1, layout.tailItemIndex)
            assertEquals(size + 2, layout.lazyItemCount)
        }
    }

    @Test
    fun alternateConfiguredTuiAndFullGridAlwaysKeepTheEagerBackend() {
        val terminal = TerminalBenchmarkWorkload.prepare(1_000)
        val normal = terminal.renderFrame()
        for (renderer in BenchmarkRenderer.entries) {
            assertFalse(TerminalBenchmarkLayout.fromFrame(normal, renderer, configuredTui = true).useLazyHistory)
            assertFalse(TerminalBenchmarkLayout.fromFrame(normal, renderer, preserveFullGrid = true).useLazyHistory)
        }
        assertFalse(TerminalBenchmarkLayout.fromFrame(normal, BenchmarkRenderer.EAGER).useLazyHistory)
        TerminalBenchmarkWorkload.enterAlternateScreen(terminal)
        val alternate = terminal.renderFrame()
        for (renderer in BenchmarkRenderer.entries) {
            val layout = TerminalBenchmarkLayout.fromFrame(alternate, renderer)
            assertFalse(layout.useLazyHistory)
            assertEquals(0, layout.historyRows)
            assertEquals(24, layout.screenRows)
        }
    }

    @Test
    fun capTrimmingMovesHistoryIdsWithoutChangingTheScreenOrTailPartition() {
        val terminal = TerminalBenchmarkWorkload.prepare(1_000)
        val before = terminal.renderFrame()
        terminal.feed("\r\n" + TerminalBenchmarkWorkload.line(1_024))
        val after = terminal.renderFrame()
        assertEquals(
            TerminalBenchmarkLayout.fromFrame(before, BenchmarkRenderer.LAZY_HISTORY),
            TerminalBenchmarkLayout.fromFrame(after, BenchmarkRenderer.LAZY_HISTORY),
        )
        assertEquals(before.historyLineIds.drop(1), after.historyLineIds.dropLast(1))
        assertEquals(before.screenLineIds.first(), after.historyLineIds.last())
    }

    @Test
    fun emptyHistoryStillKeepsAllPhysicalRowsTogether() {
        val frame = TerminalEmulator(initialRows = 24).renderFrame()
        val layout = TerminalBenchmarkLayout.fromFrame(frame, BenchmarkRenderer.LAZY_HISTORY)
        assertEquals(0, layout.activeScreenItemIndex)
        assertEquals(1, layout.tailItemIndex)
        assertEquals(2, layout.lazyItemCount)
        assertEquals(24, layout.screenRows)
    }

    @Test(expected = IllegalStateException::class)
    fun unknownRendererCannotSilentlyFallBackToEager() {
        BenchmarkRenderer.fromWireName("typo")
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistentScreenBoundaryIsRejected() {
        val frame = TerminalEmulator().renderFrame().copy(screenStartRow = 1)
        TerminalBenchmarkLayout.fromFrame(frame, BenchmarkRenderer.LAZY_HISTORY)
    }
}
