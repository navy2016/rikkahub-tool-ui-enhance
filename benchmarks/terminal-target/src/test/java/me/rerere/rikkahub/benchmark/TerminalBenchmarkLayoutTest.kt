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
            for (layout in listOf(
                TerminalBenchmarkLayout.fromFrame(normal, renderer, configuredTui = true),
                TerminalBenchmarkLayout.fromFrame(normal, renderer, preserveFullGrid = true),
            )) {
                assertFalse(layout.useLazyHistory)
                assertFalse(layout.useChunkedHistory)
            }
        }
        assertFalse(TerminalBenchmarkLayout.fromFrame(normal, BenchmarkRenderer.EAGER).useLazyHistory)
        TerminalBenchmarkWorkload.enterAlternateScreen(terminal)
        val alternate = terminal.renderFrame()
        for (renderer in BenchmarkRenderer.entries) {
            val layout = TerminalBenchmarkLayout.fromFrame(alternate, renderer)
            assertFalse(layout.useLazyHistory)
            assertFalse(layout.useChunkedHistory)
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

    @Test
    fun eagerChunksCoverEveryHistoryRowOnceAtEveryBenchmarkSize() {
        for (size in TerminalBenchmarkWorkload.historySizes) {
            val ids = List(size) { 100L + it }
            val chunks = stableEagerHistoryChunks(ids)
            assertEquals(ids.indices.toList(), chunks.flatMap { (it.start until it.endExclusive).toList() })
            assertEquals(chunks.size, chunks.map { it.bucket }.distinct().size)
            assertTrue(chunks.all { it.endExclusive - it.start in 1..EAGER_HISTORY_CHUNK_IDS.toInt() })
        }
    }

    @Test
    fun trimmingAPartialHeadChunkKeepsInteriorKeysAndMembership() {
        val before = (125L..383L).toList()
        val after = before.drop(1) + 384L
        fun contents(ids: List<Long>) = stableEagerHistoryChunks(ids).associate {
            it.bucket to ids.subList(it.start, it.endExclusive)
        }
        val old = contents(before)
        val next = contents(after)
        assertEquals(old.getValue(0).drop(1), next.getValue(0))
        assertEquals(old.getValue(1), next.getValue(1))
        assertEquals(old.getValue(2), next.getValue(2))
        assertEquals(listOf(384L), next.getValue(3))
        // Neither first-row IDs nor positional chunk numbers are used as unstable keys.
        assertEquals(listOf(0L, 1L, 2L, 3L), next.keys.toList())
    }

    @Test
    fun chunkingPreservesGapsAndRejectsReorderedOrDuplicateIds() {
        val ids = listOf(-1L, 0L, 7L, 127L, 129L, 300L)
        val chunks = stableEagerHistoryChunks(ids)
        assertEquals(ids, chunks.flatMap { ids.subList(it.start, it.endExclusive) })
        assertEquals(listOf(-1L, 0L, 1L, 2L), chunks.map { it.bucket })
        assertTrue(stableEagerHistoryChunks(listOf(300L, 1L, 301L)).isEmpty())
        assertTrue(stableEagerHistoryChunks(listOf(1L, 1L)).isEmpty())
        assertTrue(stableEagerHistoryChunks(emptyList()).isEmpty())
    }

    @Test
    fun chunkCandidateRequiresCompleteMonotonicHistoryAndKeepsOneScreen() {
        val frame = TerminalBenchmarkWorkload.prepare(1_000).renderFrame()
        val layout = TerminalBenchmarkLayout.fromFrame(frame, BenchmarkRenderer.CHUNKED_EAGER)
        assertTrue(layout.useChunkedHistory)
        assertFalse(layout.useLazyHistory)
        assertEquals(24, layout.screenRows)
        assertEquals(1_000, layout.historyChunks.sumOf { it.endExclusive - it.start })
        val invalidFrames = listOf(
            frame.copy(historyLineIds = frame.historyLineIds.reversed()),
            frame.copy(historyLineIds = emptyList()),
            TerminalEmulator(initialRows = 24).renderFrame(),
        )
        for (invalid in invalidFrames) {
            val fallback = TerminalBenchmarkLayout.fromFrame(invalid, BenchmarkRenderer.CHUNKED_EAGER)
            assertFalse(fallback.useChunkedHistory)
            assertFalse(fallback.useLazyHistory)
        }
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
