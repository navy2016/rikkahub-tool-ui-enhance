package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import me.rerere.rikkahub.ui.pages.container.TERMINAL_HISTORY_CHUNK_ROWS
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
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
    fun completeFrameMetadataSkipsRetainedHistoryForActiveScreenUpdates() {
        for (historySize in listOf(1_000, 5_000, 10_000)) {
            val terminal = TerminalEmulator(initialColumns = 80, initialRows = 6, maxScrollbackLines = historySize)
            terminal.feed((0 until historySize + 6).joinToString("\r\n") { "line $it" })
            val before = terminal.renderFrame()
            assertEquals(historySize, before.historyCount)
            val rows = createTerminalRenderedRows(before)
            val historyStates = rows.take(historySize)
            val syncState = createTerminalRenderedRowsSyncState(before, rows)

            terminal.feed("\r\u001B[2Kactive changed")
            val after = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(
                    rows, after, false, nowMs = 100, syncState = syncState,
                )
            }

            assertTrue(syncState.lastUsedMetadataFastPath)
            assertEquals(terminal.rows, syncState.lastVisitedTextRows)
            assertEquals(before.historyStartSequence, after.historyStartSequence)
            assertEquals(before.historyRenderRevision, after.historyRenderRevision)
            historyStates.forEachIndexed { index, state -> assertSame(state, rows[index]) }
            assertTrue(rows.last().text.text.contains("active changed"))
        }
    }

    @Test
    fun globalStyleChangesAndColumnResizeForceHistoryTextSynchronization() {
        val terminal = TerminalEmulator(initialColumns = 30, initialRows = 6, maxScrollbackLines = 100)
        terminal.feed((0 until 106).joinToString("\r\n") { "\u001B[32mline $it\u001B[0m" })
        var frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val syncState = createTerminalRenderedRowsSyncState(frame, rows)

        terminal.feed("\u001B]4;2;rgb:ffff/0000/0000\u0007")
        var next = terminal.renderFrame()
        assertTrue(next.historyRenderRevision > frame.historyRenderRevision)
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100, syncState = syncState)
        }
        assertFalse(syncState.lastUsedMetadataFastPath)
        assertEquals(next.rows.size, syncState.lastVisitedTextRows)

        frame = next
        terminal.resize(columns = 12, rows = 6)
        next = terminal.renderFrame()
        assertTrue(next.historyRenderRevision > frame.historyRenderRevision)
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, next, false, nowMs = 200, syncState = syncState)
        }
        assertFalse(syncState.lastUsedMetadataFastPath)
        assertEquals(next.rows.size, syncState.lastVisitedTextRows)
        assertEquals(next.rows.map { it.text }, rows.map { it.text })
    }

    @Test
    fun appendUsesFifoButIncompleteMetadataKeepsTheFullFallback() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6, maxScrollbackLines = 100)
        terminal.feed((0 until 106).joinToString("\r\n") { "line $it" })
        val before = terminal.renderFrame()
        val rows = createTerminalRenderedRows(before)
        val syncState = createTerminalRenderedRowsSyncState(before, rows)
        terminal.feed("\r\nnew history")
        val after = terminal.renderFrame()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, after, false, nowMs = 100, syncState = syncState)
        }
        assertFalse(syncState.lastUsedMetadataFastPath)
        assertTrue(syncState.lastUsedFifoFastPath)
        assertEquals(terminal.rows + 1, syncState.lastVisitedTextRows)

        val incomplete = after.copy(historyLineIds = emptyList(), screenLineIds = emptyList())
        val incompleteRows = createTerminalRenderedRows(incomplete)
        val incompleteState = createTerminalRenderedRowsSyncState(incomplete, incompleteRows)
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(
                incompleteRows, incomplete, false, nowMs = 200, syncState = incompleteState,
            )
        }
        assertFalse(incompleteState.lastUsedMetadataFastPath)
        assertEquals(incomplete.rows.size, incompleteState.lastVisitedTextRows)
    }

    @Test
    fun fifoAppendsAndTrimsVisitOnlyTheChangedTailAtAllSizes() {
        for (size in listOf(1_000, 5_000, 10_000)) {
            val terminal = TerminalEmulator(initialRows = 6, maxScrollbackLines = size)
            terminal.feed((0 until size + 6).joinToString("\r\n") { "row $it" })
            val frame = terminal.renderFrame()
            val rows = createTerminalRenderedRows(frame)
            val sync = createTerminalRenderedRowsSyncState(frame, rows)
            repeat(30) { update ->
                val previous = rows.toList()
                terminal.feed("\r\nnew $update")
                val next = terminal.renderFrame()
                Snapshot.withMutableSnapshot {
                    synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100L + update, syncState = sync)
                }
                assertTrue(sync.lastUsedFifoFastPath)
                assertEquals(7, sync.lastVisitedTextRows)
                assertEquals(size - 1, sync.lastSkippedHistoryRows)
                assertEquals(buildLineIdsFromFrame(next, next.rows.size), rows.map { it.lineId })
                assertEquals(next.rows.map { it.text }, rows.map { it.text })
                for (index in 0 until rows.lastIndex) assertSame(previous[index + 1], rows[index])
            }
            terminal.setMaxScrollbackLines(size / 2)
            val limited = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, limited, false, nowMs = 200, syncState = sync)
            }
            assertTrue(sync.lastUsedFifoFastPath)
            assertEquals(6, sync.lastVisitedTextRows)
            assertEquals(limited.rows.map { it.text }, rows.map { it.text })
        }
    }

    @Test
    fun growingHistoryAndNonMonotonicIdsPreserveScreenStatesWhenTheyArchive() {
        val terminal = TerminalEmulator(initialRows = 6, maxScrollbackLines = 100)
        terminal.feed("\u001B[H\u001BM\u001B[6;1H") // A larger ID now precedes older screen IDs.
        val frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val sync = createTerminalRenderedRowsSyncState(frame, rows)
        repeat(20) { update ->
            val previous = rows.toList().associateBy { it.lineId }
            terminal.feed("\r\nnew $update")
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100L + update, syncState = sync)
            }
            assertTrue(sync.lastUsedFifoFastPath)
            assertEquals(7, sync.lastVisitedTextRows)
            rows.forEach { row -> previous[row.lineId]?.let { assertSame(it, row) } }
            assertEquals(next.rows.map { it.text }, rows.map { it.text })
        }
    }

    @Test
    fun mixedRealEmulatorMutationsMatchTheFullSynchronizer() {
        val terminal = TerminalEmulator(initialColumns = 40, initialRows = 8, maxScrollbackLines = 150)
        terminal.feed((0 until 180).joinToString("\r\n") { "\u001B[32mrow $it\u001B[0m" })
        val first = terminal.renderFrame()
        val optimized = createTerminalRenderedRows(first)
        val reference = createTerminalRenderedRows(first)
        val sync = createTerminalRenderedRowsSyncState(first, optimized)
        val mutations: List<(TerminalEmulator) -> Unit> = listOf(
            { it.feed("\r\nappend") }, { it.feed("\r\u001B[2K中文 changed") },
            { it.feed("\u001B[H\u001BM\u001B[8;1H\r\nreordered") },
            { it.feed("\u001B[2;1H\u001B[L\u001B[8;1H\r\ninsert") },
            { it.feed("\u001B[?5h\r\nreverse") }, { it.feed("\u001B[?5l") },
            { it.feed("\u001B]10;rgb:ffff/0000/0000\u0007") },
            { it.feed("\u001B]4;2;rgb:0000/0000/ffff\u0007\r\ncolored") },
            { it.resize(columns = 20, rows = 6) }, { it.resize(columns = 40, rows = 8) },
            { it.setMaxScrollbackLines(50) }, { it.setMaxScrollbackLines(150) },
            { it.feed((0..180).joinToString("\r\n", prefix = "\r\n") { "burst $it" }) },
            { it.feed("\u001B[?1049hALT") }, { it.feed("\u001B[2J") },
            { it.feed("\u001B[?1049l") }, { it.clearScrollbackOnly() },
            { it.feed("\u001B[8;1H\r\nnew history") }, { it.reset() },
        )
        repeat(3) { cycle ->
            mutations.forEachIndexed { index, mutate ->
                val previous = optimized.toList().associateBy { it.lineId }
                mutate(terminal)
                val next = terminal.renderFrame()
                Snapshot.withMutableSnapshot {
                    val now = 100L + cycle * 2_000L + index * 100L
                    val expected = synchronizeTerminalRenderedRows(reference, next, next.isAlternateScreen,
                        forcePendingGridBlanks = true, nowMs = now)
                    assertEquals(expected, synchronizeTerminalRenderedRows(optimized, next, next.isAlternateScreen,
                        forcePendingGridBlanks = true, nowMs = now, syncState = sync))
                }
                assertEquals(reference.map { it.lineId }, optimized.map { it.lineId })
                assertEquals(reference.map { it.text }, optimized.map { it.text })
                optimized.forEach { row -> previous[row.lineId]?.let { assertSame(it, row) } }
            }
        }
    }

    @Test
    fun pendingGridBlanksForceFullSyncUntilCommittedEvenWhenRowsArchive() {
        val terminal = TerminalEmulator(initialRows = 6, maxScrollbackLines = 100)
        terminal.feed((0 until 106).joinToString("\r\n") { "row $it" })
        val first = terminal.renderFrame()
        val rows = createTerminalRenderedRows(first)
        val sync = createTerminalRenderedRowsSyncState(first, rows)
        terminal.feed("\u001B[?25l\u001B[1;1H\u001B[2K")
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, terminal.renderFrame(), true, nowMs = 100, syncState = sync))
        }
        val next = terminal.renderFrame()
        Snapshot.withMutableSnapshot {
            assertFalse(synchronizeTerminalRenderedRows(rows, next, true,
                forcePendingGridBlanks = true, nowMs = 110, syncState = sync))
        }
        assertFalse(sync.lastUsedMetadataFastPath)
        assertEquals(next.rows.map { it.text }, rows.map { it.text })
        terminal.feed("\u001B[2;1H\u001B[2K")
        Snapshot.withMutableSnapshot {
            assertTrue(synchronizeTerminalRenderedRows(rows, terminal.renderFrame(), true, nowMs = 200, syncState = sync))
        }
        terminal.feed("\u001B[6;1H\r\nnew")
        val archived = terminal.renderFrame()
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, archived, false, nowMs = 210, syncState = sync)
        }
        assertFalse(sync.lastUsedFifoFastPath)
        assertEquals(archived.rows.size, sync.lastVisitedTextRows)
        assertEquals(archived.rows.map { it.text }, rows.map { it.text })
    }

    @Test
    fun replacedListSyntheticRowsDifferentOwnersAndScreenReorderCannotUseFifoProof() {
        fun seeded() = TerminalEmulator(initialRows = 6, maxScrollbackLines = 100).apply {
            feed((0 until 106).joinToString("\r\n") { "row $it" })
        }
        val terminal = seeded()
        var frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val sync = createTerminalRenderedRowsSyncState(frame, rows)
        Snapshot.withMutableSnapshot { val row = rows.removeAt(10); rows.add(10, row) }
        terminal.feed("\r\nappend")
        frame = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 100, syncState = sync) }
        assertFalse(sync.lastUsedFifoFastPath)
        val synthetic = frame.copy(rows = frame.rows.map { TerminalEmulator.RenderedRow(AnnotatedString("replaced")) })
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, synthetic, false, nowMs = 200, syncState = sync) }
        assertFalse(sync.lastUsedMetadataFastPath)
        assertEquals(synthetic.rows.map { it.text }, rows.map { it.text })
        frame = seeded().renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 300, syncState = sync) }
        assertFalse(sync.lastUsedMetadataFastPath)
        assertEquals(frame.rows.map { it.text }, rows.map { it.text })
        frame = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 400, syncState = sync) }
        terminal.feed("\u001B[H\u001BM\u001B[6;1H\r\nreordered")
        frame = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 500, syncState = sync) }
        assertFalse(sync.lastUsedFifoFastPath)
        assertEquals(frame.rows.map { it.text }, rows.map { it.text })
    }

    @Test
    fun discardedSnapshotDoesNotAdvanceTheHistorySynchronizationProof() {
        val terminal = TerminalEmulator(initialRows = 6, maxScrollbackLines = 100)
        terminal.feed((0 until 106).joinToString("\r\n") { "row $it" })
        val original = terminal.renderFrame()
        val rows = createTerminalRenderedRows(original)
        val sync = createTerminalRenderedRowsSyncState(original, rows)
        terminal.feed("\u001B[?5h")
        val changed = terminal.renderFrame()
        val discarded = Snapshot.takeMutableSnapshot()
        try {
            discarded.enter {
                synchronizeTerminalRenderedRows(rows, changed, false, nowMs = 100, syncState = sync)
            }
        } finally {
            discarded.dispose() // Deliberately do NOT apply the Text or its synchronization proof.
        }
        assertEquals(original.rows.map { it.text }, rows.map { it.text })
        Snapshot.withMutableSnapshot {
            synchronizeTerminalRenderedRows(rows, changed, false, nowMs = 200, syncState = sync)
        }
        assertFalse(sync.lastUsedMetadataFastPath)
        assertEquals(changed.rows.size, sync.lastVisitedTextRows)
        assertEquals(changed.rows.map { it.text }, rows.map { it.text })
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
