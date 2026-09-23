package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
            val oldRows = rows.toList()
            terminal.feed("\r\nnext")
            val after = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, after, false, nowMs = 100)
            }
            assertEquals(historySize, after.historyCount)
            assertEquals(historySize + 6, rows.size)
            assertEquals(rows.size, rows.map { it.lineId }.toSet().size)
            for (index in 0 until rows.lastIndex) assertSame(oldRows[index + 1], rows[index])
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
