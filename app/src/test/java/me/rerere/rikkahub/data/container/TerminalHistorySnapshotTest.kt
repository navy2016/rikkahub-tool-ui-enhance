package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalHistorySnapshot
import me.rerere.rikkahub.utils.TerminalHistorySnapshotRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.RandomAccess
import kotlin.random.Random

/** A full list is the oracle; it never uses the candidate's bucket arithmetic or bounds cache. */
class TerminalHistorySnapshotTest {
    private val owner = Any()

    // Dense archival ordinals MUST NOT be confused with physical line IDs, which can be unsorted.
    private fun value(sequence: Long, revision: Long = 0): TerminalHistorySnapshotRow {
        val occupied = sequence % 7L != 0L
        return TerminalHistorySnapshotRow(TerminalEmulator.RenderedRow(
            AnnotatedString(if (occupied) "中文-$sequence-style$revision" else "")),
            if (sequence % 2L == 0L) sequence else Long.MAX_VALUE - sequence, occupied)
    }

    private fun build(
        first: Long, size: Int, previous: TerminalHistorySnapshot? = null,
        source: Any = owner, generation: Long = 1, revision: Long = 0,
        read: (Int) -> TerminalHistorySnapshotRow = { value(first + it, revision) },
    ) = TerminalHistorySnapshot.build(source, generation, revision, first, size, previous, read)

    private fun verify(snapshot: TerminalHistorySnapshot, expected: List<TerminalHistorySnapshotRow>) {
        assertEquals(expected.size, snapshot.size)
        assertEquals(expected.map { it.row }, snapshot.rows)
        assertEquals(expected.map { it.lineId }, snapshot.lineIds)
        assertEquals(expected.map { it.row }.hashCode(), snapshot.rows.hashCode())
        assertEquals(expected.map { it.lineId }.hashCode(), snapshot.lineIds.hashCode())
        val occupied = expected.indices.filter { expected[it].isNotBlank }
        assertEquals(TerminalEmulator.ContentBounds(occupied.firstOrNull(), occupied.lastOrNull(), occupied.size),
            snapshot.contentBounds)
        assertEquals(expected.size, snapshot.blocks.sumOf { it.size })
        assertTrue(snapshot.rows is RandomAccess)
        assertTrue(snapshot.lineIds is RandomAccess)
        var next = snapshot.firstSequence
        for (block in snapshot.blocks) {
            assertEquals(next, block.firstSequence)
            assertTrue(block.size in 1..TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS)
            assertEquals(block.firstSequence / TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS,
                (block.endSequence - 1) / TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS)
            next = block.endSequence
        }
        assertEquals(snapshot.endSequence, next)
    }

    @Test fun coldAndUnchangedSnapshotsMatchListsAtEveryBucketBoundary() {
        for (first in listOf(0L, 1L, 126L, 127L, 128L, 255L)) {
            for (size in listOf(0, 1, 127, 128, 129, 257, 1000, 5000, 10000)) {
                var visits = 0
                val snapshot = build(first, size) { visits++; value(first + it) }
                assertEquals(size, visits)
                verify(snapshot, List(size) { value(first + it) })
                assertSame(snapshot, build(first, size, snapshot) { error("Unchanged source was read") })
            }
        }
    }

    @Test fun repeatedAppendAndTrimShareMiddleBlocksAndOnlyReadOneNewRowAtAllSizes() {
        for (size in listOf(1, 127, 128, 129, 1000, 5000, 10000)) {
            var current = build(125, size)
            val original = current
            val originalRows = current.rows.toList()
            val originalIds = current.lineIds.toList()
            val originalBounds = current.contentBounds
            repeat(260) {
                val before = current
                val nextFirst = before.firstSequence + 1
                var visits = 0
                current = build(nextFirst, size, before) { index ->
                    visits++
                    assertEquals(size - 1, index)
                    value(nextFirst + index)
                }
                assertEquals(1, visits)
                val oldBlocks = before.blocks.associateBy { it.firstSequence }
                val copied = current.blocks.filter { block -> oldBlocks[block.firstSequence] !== block }
                assertTrue("More than two changed boundary blocks", copied.size <= 2)
                assertTrue(copied.sumOf { it.size } <= 2 * TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS)
                assertEquals(size, current.blocks.sumOf { it.size })
                if (size > 1) assertSame(before.rows[size - 1], current.rows[size - 2])
                // Verify a whole snapshot periodically without adding O(H) work to every assertion.
                if (it % 64 == 0) verify(current, List(size) { index -> value(nextFirst + index) })
            }
            assertEquals(originalRows, original.rows)
            assertEquals(originalIds, original.lineIds)
            assertEquals(originalBounds, original.contentBounds)
        }
    }

    @Test fun headTrimDoesNotReadSourceOrRetainTrimmedRowsInAnyBlock() {
        val original = build(3, 1000)
        val expected = List(1000) { value(3L + it) }
        for (drop in listOf(1, 124, 125, 126, 127, 128, 129, 500, 999, 1000)) {
            val trimmed = build(3L + drop, 1000 - drop, original) { error("Head-only trim read source") }
            verify(trimmed, expected.drop(drop))
            assertEquals(3L + drop, trimmed.firstSequence)
            assertTrue(trimmed.blocks.all { it.firstSequence >= trimmed.firstSequence })
        }
        verify(original, expected)
    }

    @Test fun growingTailReadsOnlySuffixAndPreservesAllCompleteBuckets() {
        val original = build(63, 194)
        for (added in listOf(1, 127, 128, 129, 513)) {
            var visits = 0
            val next = build(63, original.size + added, original) { index ->
                assertTrue(index >= original.size)
                visits++
                value(63L + index)
            }
            assertEquals(added, visits)
            for (old in original.blocks.dropLast(1)) assertSame(old, next.blocks.first { it.firstSequence == old.firstSequence })
            verify(next, List(next.size) { value(63L + it) })
        }
    }

    @Test fun aBurstThatReplacesTheWholeWindowNeverKeepsAnOlderSnapshotChain() {
        val original = build(0, 129)
        var visits = 0
        val next = build(700, 129, original) { visits++; value(700L + it) }
        assertEquals(129, visits)
        assertTrue(next.blocks.none { block -> original.blocks.any { it === block } })
        verify(next, List(129) { value(700L + it) })
        verify(original, List(129) { value(it.toLong()) })
    }

    @Test fun ownerGenerationAndStyleInvalidationsCannotReuseAnyBlock() {
        val original = build(1, 257)
        for ((source, generation, revision) in listOf(Triple(Any(), 1L, 0L), Triple(owner, 2L, 0L), Triple(owner, 1L, 1L))) {
            var visits = 0
            val next = build(1, 257, original, source, generation, revision) {
                visits++
                value(1L + it, revision)
            }
            assertEquals(257, visits)
            assertTrue(next.blocks.none { block -> original.blocks.any { it === block } })
            verify(next, List(257) { value(1L + it, revision) })
        }
    }

    @Test fun nonFifoRangeChangesForceAFullRead() {
        val original = build(10, 257)
        for ((first, size) in listOf(9L to 257, 11L to 100)) {
            var visits = 0
            val next = build(first, size, original) { visits++; value(first + it) }
            assertEquals(size, visits)
            verify(next, List(size) { value(first + it) })
        }
    }

    @Test fun allBlankAndLastOccupiedRowTrimmingPreserveExactContentBounds() {
        val original = build(0, 257) { index ->
            TerminalHistorySnapshotRow(TerminalEmulator.RenderedRow(AnnotatedString(if (index == 0) "x" else "")),
                index.toLong(), index == 0)
        }
        assertEquals(TerminalEmulator.ContentBounds(0, 0, 1), original.contentBounds)
        val trimmed = build(1, 256, original) { error("Trimmed source re-read") }
        assertEquals(TerminalEmulator.ContentBounds(null, null, 0), trimmed.contentBounds)
        assertTrue(trimmed.blocks.all { it.contentBounds.nonBlankRowCount == 0 })
    }

    @Test fun failedBuildDoesNotMutatePublishedBlocksOrPublishAPartialResult() {
        val original = build(127, 129)
        val expected = List(129) { value(127L + it) }
        assertThrows(IllegalStateException::class.java) {
            build(128, 257, original) { if (it > 140) error("render failure") else value(128L + it) }
        }
        verify(original, expected)
        verify(build(128, 257, original), List(257) { value(128L + it) })
    }

    @Test fun publishedViewsCannotBeMutatedAndIndexChecksRemainListCompatible() {
        val snapshot = build(127, 257)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.rows as MutableList<TerminalEmulator.RenderedRow>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.lineIds as MutableList<Long>)[0] = 0 }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.blocks as MutableList<*>).clear()
        }
        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.rows[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.lineIds[snapshot.size] }
        assertEquals(snapshot.rows.toList().subList(1, 256), snapshot.rows.subList(1, 256))
    }

    @Test fun largeArchivalOrdinalsDoNotOverflowBucketCoordinates() {
        val first = Long.MAX_VALUE - 2000
        val original = build(first, 1000)
        val next = build(first + 129, 1000, original)
        verify(next, List(1000) { value(first + 129 + it) })
        assertThrows(ArithmeticException::class.java) { build(Long.MAX_VALUE - 1, 2) }
        assertThrows(IllegalArgumentException::class.java) { build(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { build(0, 10001) }
        assertThrows(IllegalArgumentException::class.java) { TerminalHistorySnapshot.empty(owner, 1, 0, -1) }
    }

    @Test fun randomizedFifoWindowsMatchFullReferenceWithoutRetainedSourceReads() {
        val random = Random(713)
        var first = 7L
        var size = 500
        var current = build(first, size)
        repeat(500) {
            val before = current
            val added = random.nextInt(0, 250)
            val drop = random.nextInt(0, size + added + 1)
            first += drop
            size += added - drop
            val expectedReads = (first + size - maxOf(first, before.endSequence)).toInt()
            var visits = 0
            current = build(first, size, before) { index -> visits++; value(first + index) }
            assertEquals(expectedReads, visits)
            verify(current, List(size) { index -> value(first + index) })
        }
    }
}
