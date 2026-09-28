package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import me.rerere.rikkahub.ui.pages.container.TERMINAL_HISTORY_CHUNK_ROWS
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TerminalRenderedRowsTest {
    @Test
    fun unchangedIdsOnlyUpdateTextWithoutReplacingTheListOrRows() {
        val rows = createTerminalRenderedRows(frame(10L to "history", 30L to "prompt"))
        val original = rows.toList()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(10L to "history", 30L to "changed"), false, nowMs = 100)
        }
        assertSame(original, rows.toList())
        assertSame(original[1], rows[1])
        assertEquals("changed", rows[1].text.text)
    }

    @Test
    fun trimAndReorderReuseSurvivorsByIdNotPosition() {
        val rows = createTerminalRenderedRows(frame(10L to "a", 30L to "b", 70L to "c"))
        val original = rows.toList()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(70L to "c", 30L to "b", 90L to "new"), false, nowMs = 100)
        }
        assertSame(original[2], rows[0])
        assertSame(original[1], rows[1])
        assertTrue(original.none { it === rows[2] })
        assertEquals(listOf(70L, 30L, 90L), rows.map { it.lineId })
    }

    @Test
    fun pendingTuiBlankFollowsItsStableIdThroughReordering() {
        val rows = createTerminalRenderedRows(frame(10L to "status", 30L to "prompt"))
        val statusRow = rows[0]
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, frame(10L to "", 30L to "prompt"), true, nowMs = 100))
            assertTrue(synchronizeTerminalRenderedRows(rows, frame(30L to "prompt", 10L to ""), true, nowMs = 120))
        }
        assertSame(statusRow, rows[1])
        assertEquals(100L, rows[1].pendingBlankSinceMs)
        assertEquals("status", rows[1].text.text)
        Snapshot.withMutableSnapshot {
            assertFalse(synchronizeTerminalRenderedRows(rows, frame(30L to "prompt", 10L to ""), true, nowMs = 150))
        }
        assertEquals("", rows[1].text.text)
        assertEquals(0L, rows[1].pendingBlankSinceMs)
    }

    @Test
    fun ordinaryHistoryBlanksAreNeverDeferred() {
        val rows = createTerminalRenderedRows(frame(10L to "output"))
        Snapshot.withMutableSnapshot {
            assertFalse(synchronizeTerminalRenderedRows(rows, frame(10L to ""), false, nowMs = 100))
        }
        assertEquals("", rows[0].text.text)
    }

    @Test
    fun onlyTheBottomTuiRowsUseBlankGraceAndForcedCommitEndsIt() {
        val filled = frame(*(1L..10L).map { it to "status" }.toTypedArray())
        val blank = frame(*(1L..10L).map { it to "" }.toTypedArray())
        val rows = createTerminalRenderedRows(filled)
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, blank, true, nowMs = 100))
        }
        assertTrue(rows.take(2).all { it.text.text.isEmpty() })
        assertTrue(rows.drop(2).all { it.text.text == "status" })
        Snapshot.withMutableSnapshot {
            assertFalse(synchronizeTerminalRenderedRows(rows, blank, true, forcePendingGridBlanks = true, nowMs = 101))
        }
        assertTrue(rows.all { it.text.text.isEmpty() && it.pendingBlankSinceMs == 0L })
    }

    @Test
    fun aNonBlankRedrawResetsTheGracePeriod() {
        val rows = createTerminalRenderedRows(frame(1L to "status"))
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(1L to ""), true, nowMs = 100)
            synchronizeTerminalRenderedRows(rows, frame(1L to "redrawn"), true, nowMs = 120)
        }
        assertEquals(0L, rows[0].pendingBlankSinceMs)
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, frame(1L to ""), true, nowMs = 160))
        }
        assertEquals(160L, rows[0].pendingBlankSinceMs)
        assertEquals("redrawn", rows[0].text.text)
    }

    @Test
    fun clearAndNewIdsDoNotReuseDeadRows() {
        val rows = createTerminalRenderedRows(frame(1L to "old"))
        val old = rows.single()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(100L to "new"), false, nowMs = 100)
        }
        assertNotSame(old, rows.single())
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(), false, nowMs = 200)
        }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun incompleteMetadataUsesTheSameFallbackIdsAtCreationAndUpdate() {
        val incomplete = frame(1L to "output", 2L to "prompt").copy(screenLineIds = emptyList())
        val rows = createTerminalRenderedRows(incomplete)
        val original = rows.toList()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, incomplete, false, nowMs = 100)
        }
        assertSame(original, rows.toList())
        assertEquals(listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1), rows.map { it.lineId })
    }

    @Test
    fun realEmulatorArchivalPreservesRowStateAtAllBenchmarkSizes() {
        for (historySize in listOf(1_000, 5_000, 10_000)) {
            val terminal = TerminalEmulator(initialRows = 6, maxScrollbackLines = historySize)
            terminal.feed((0 until historySize + 6).joinToString("\r\n") { "line $it" })
            val before = terminal.renderFrame()
            val rows = createTerminalRenderedRows(before)
            repeat(30) { update ->
                val oldRows = rows.toList()
                val oldIds = oldRows.map { it.lineId }
                terminal.feed("\r\nnext $update")
                val after = terminal.renderFrame()
                Snapshot.withMutableSnapshot {
                    synchronizeTerminalRenderedRows(rows, after, false, nowMs = 100L + update)
                }
                assertEquals(historySize, after.historyCount)
                assertEquals(historySize + 6, rows.size)
                assertEquals(rows.size, rows.map { it.lineId }.toSet().size)
                for (index in 0 until rows.lastIndex) assertSame(oldRows[index + 1], rows[index])
                assertEquals(oldIds, oldRows.map { it.lineId })
            }
        }
    }

    @Test
    fun cachedTuiRedrawStillResetsAPendingBlank() {
        val original = frame(1L to "status")
        val rows = createTerminalRenderedRows(original)
        val cachedText = rows.single().text
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, frame(1L to ""), true, nowMs = 100))
            assertFalse(synchronizeTerminalRenderedRows(rows, original, true, nowMs = 120))
        }
        assertSame(cachedText, rows.single().text)
        assertEquals(0L, rows.single().pendingBlankSinceMs)
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, frame(1L to ""), true, nowMs = 160))
        }
        assertEquals(160L, rows.single().pendingBlankSinceMs)
    }

    @Test
    fun pendingBlankSurvivesContiguousTrimAndAppend() {
        val rows = createTerminalRenderedRows(frame(1L to "old", 7L to "status", 20L to "prompt"))
        val status = rows[1]
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(
                rows, frame(1L to "old", 7L to "", 20L to "prompt"), true, nowMs = 100,
            ))
            assertTrue(synchronizeTerminalRenderedRows(
                rows, frame(7L to "", 20L to "prompt", 80L to "new"), true, nowMs = 120,
            ))
        }
        assertSame(status, rows.first())
        assertEquals(100L, status.pendingBlankSinceMs)
        Snapshot.withMutableSnapshot {
            assertFalse(synchronizeTerminalRenderedRows(
                rows, frame(7L to "", 20L to "prompt", 80L to "new"), true, nowMs = 150,
            ))
        }
        assertEquals("", status.text.text)
        assertEquals(0L, status.pendingBlankSinceMs)
    }

    @Test
    fun suffixContainingAnOlderIdIsAReorderNotANewRow() {
        val rows = createTerminalRenderedRows(frame(1L to "one", 7L to "seven", 20L to "twenty"))
        val original = rows.toList()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, frame(20L to "twenty", 1L to "changed"), false, nowMs = 100)
        }
        assertSame(original[2], rows[0])
        assertSame(original[0], rows[1])
        assertEquals("changed", rows[1].text.text)
    }

    @Test
    fun unchangedCharactersStillApplyNewAnsiStyle() {
        fun styled(color: Color) = AnnotatedString(
            "same", spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = color), 0, 4)),
        )
        val before = frame(1L to "same").copy(rows = listOf(TerminalEmulator.RenderedRow(styled(Color.Red))))
        val after = before.copy(rows = listOf(TerminalEmulator.RenderedRow(styled(Color.Blue))))
        val rows = createTerminalRenderedRows(before)
        val state = rows.single()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, after, false, nowMs = 100) }
        assertSame(state, rows.single())
        assertEquals(after.rows.single().text, state.text)
    }

    @Test
    fun mixedStructuralUpdatesMatchStableIdReuseContract() {
        val random = Random(734)
        var ids = listOf(1L, 7L, 20L, 80L)
        var nextId = 100L
        val rows = createTerminalRenderedRows(frame(*ids.map { it to "row $it" }.toTypedArray()))
        repeat(120) { update ->
            val previous = rows.toList()
            val previousIds = previous.map { it.lineId }
            val byId = previous.associateBy { it.lineId }
            ids = when (update % 6) {
                0 -> ids.drop(1) + nextId++
                1 -> ids + listOf(nextId++, nextId++)
                2 -> ids.shuffled(random)
                3 -> ids.take(ids.size / 2)
                4 -> listOf(nextId++) + ids
                else -> ids.drop(1) + ids.take(1)
            }
            val next = frame(*ids.map { it to "row $it update $update" }.toTypedArray())
            Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100L + update) }
            assertEquals(ids, rows.map { it.lineId })
            assertEquals(previousIds, previous.map { it.lineId })
            rows.forEachIndexed { index, row ->
                byId[row.lineId]?.let { assertSame(it, row) }
                assertEquals(next.rows[index].text, row.text)
                assertEquals(0L, row.pendingBlankSinceMs)
            }
        }
    }

    @Test
    fun archivalChunksCoverEveryRowOnceWithBoundedGroupsAtAllSizes() {
        for (size in listOf(1, 127, 128, 129, 1_000, 5_000, 10_000)) {
            for (start in listOf(0L, 1L, 125L, 127L, 128L, Long.MAX_VALUE - 10_000)) {
                val chunks = terminalHistoryChunks(size, start)
                assertEquals((0 until size).toList(), chunks.flatMap { (it.start until it.endExclusive).toList() })
                assertEquals(chunks.size, chunks.map { it.bucket }.distinct().size)
                assertTrue(chunks.size <= (size + TERMINAL_HISTORY_CHUNK_ROWS - 1) / TERMINAL_HISTORY_CHUNK_ROWS + 1)
                chunks.forEach { chunk ->
                    assertTrue(chunk.endExclusive - chunk.start in 1..TERMINAL_HISTORY_CHUNK_ROWS)
                    for (index in chunk.start until chunk.endExclusive) {
                        assertEquals((start + index) / TERMINAL_HISTORY_CHUNK_ROWS, chunk.bucket)
                    }
                }
            }
        }
    }

    @Test
    fun unknownOrUnsafeArchivalMetadataAndTuiModesKeepFlatEager() {
        assertTrue(terminalHistoryChunks(10, null).isEmpty())
        assertTrue(terminalHistoryChunks(0, 1L).isEmpty())
        assertTrue(terminalHistoryChunks(-1, 1L).isEmpty())
        assertTrue(terminalHistoryChunks(10, -1L).isEmpty())
        assertTrue(terminalHistoryChunks(2, Long.MAX_VALUE).isEmpty())
        assertTrue(terminalHistoryChunks(1_000, 1L, usesTuiViewport = true).isEmpty())
        assertEquals(1, terminalHistoryChunks(1, Long.MAX_VALUE).size)
    }

    @Test
    fun archivalChunkMembershipIgnoresSparseAndReorderedLineIds() {
        val ids = (0 until 1_000).map { if (it % 2 == 0) Long.MAX_VALUE - it else it * 10_000L }
        val before = terminalHistoryChunks(ids.size, 125L).associate { chunk ->
            chunk.bucket to ids.subList(chunk.start, chunk.endExclusive)
        }
        val afterIds = ids.drop(1) + 9_999_999L
        val after = terminalHistoryChunks(afterIds.size, 126L).associate { chunk ->
            chunk.bucket to afterIds.subList(chunk.start, chunk.endExclusive)
        }
        assertEquals(afterIds, after.values.flatten())
        // Only the first and last buckets can change. No density-dependent singleton groups.
        before.keys.intersect(after.keys).filter { it != before.keys.first() && it != after.keys.last() }
            .forEach { assertEquals(before[it], after[it]) }
        assertTrue(after.size <= 9)
    }

    @Test
    fun realEmulatorTrimsKeepSurvivingRowsInTheSameArchivalBucket() {
        for (size in listOf(1_000, 5_000, 10_000)) {
            val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6, maxScrollbackLines = size)
            // Start near a bucket boundary so 30 trims remove an ENTIRE first bucket as well.
            terminal.feed((1..(size + 131)).joinToString("\r\n") { "row$it" })
            var frame = terminal.renderFrame()
            val rows = createTerminalRenderedRows(frame)
            repeat(30) { update ->
                val previous = rows.toList().take(size)
                val buckets = terminalHistoryChunks(size, frame.historyStartSequence).flatMap { chunk ->
                    (chunk.start until chunk.endExclusive).map { previous[it].lineId to chunk.bucket }
                }.toMap()
                terminal.feed("\r\nnext$update")
                frame = terminal.renderFrame()
                Snapshot.withMutableSnapshot {
                    synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 100L + update)
                }
                val chunks = terminalHistoryChunks(size, frame.historyStartSequence)
                for (chunk in chunks) {
                    for (index in chunk.start until chunk.endExclusive) {
                        val row = rows[index]
                        buckets[row.lineId]?.let { assertEquals(it, chunk.bucket) }
                        if (index < size - 1) assertSame(previous[index + 1], row)
                    }
                }
            }
        }
    }

    private fun frame(vararg lines: Pair<Long, String>) = TerminalEmulator.RenderFrame(
        rows = lines.map { TerminalEmulator.RenderedRow(AnnotatedString(it.second)) },
        contentBounds = TerminalEmulator.ContentBounds(null, null, 0),
        screenContentBounds = TerminalEmulator.ContentBounds(null, null, 0),
        screenStartRow = 0,
        screenLineIds = lines.map { it.first },
        cursorRow = 0,
        cursorVisible = false,
        isAlternateScreen = false,
        modeSummary = "",
        revision = 1,
    )
}
