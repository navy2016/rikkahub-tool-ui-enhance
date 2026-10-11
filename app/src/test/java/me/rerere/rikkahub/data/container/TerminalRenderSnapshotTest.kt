package me.rerere.rikkahub.data.container

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.snapshots.Snapshot
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.requireTerminalRenderPair
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRenderSnapshotTest {
    private fun terminal(history: Int = 64) = TerminalEmulator(80, 6, history).apply {
        feed("\u001B[?25l" + (0 until history + 6).joinToString("\r\n") { "row-$it" })
    }

    @Test fun captureUsesTheO1ImmutableRowViewAndKeepsSurvivorIdentity() {
        val terminal = terminal(10_000)
        val frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val before = TerminalRenderSnapshot.capture(frame, rows)
        val ids = before.rows.map { it.lineId }
        assertSame(rows.toList(), before.rows)
        assertSame(frame, before.frame)
        terminal.feed("\r\nnext")
        val next = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100) }
        val after = TerminalRenderSnapshot.capture(next, rows)
        assertEquals(ids, before.rows.map { it.lineId })
        assertNotSame(before.rows, after.rows)
        assertSame(before.rows[1], after.rows[0])
        requireTerminalRenderPair(TerminalLazyLayoutPass(frame, "metrics"), before.frame, before.rows)
    }

    @Test fun sameRevisionDifferentFrameAndWrongRowCountAreStillRejectedWithoutText() {
        val terminal = terminal()
        val frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val pass = TerminalLazyLayoutPass(frame, "metrics")
        val other = frame.copy()
        val identityError = assertThrows(IllegalArgumentException::class.java) {
            requireTerminalRenderPair(pass, other, rows.toList())
        }
        assertTrue(identityError.message!!.contains("sameFrame=false"))
        val countError = assertThrows(IllegalArgumentException::class.java) {
            requireTerminalRenderPair(pass, frame, rows.toList().dropLast(1))
        }
        assertTrue(countError.message!!.contains("sameFrame=true"))
        assertFalse(identityError.message!!.contains("row-"))
        assertFalse(countError.message!!.contains("row-"))
        rows.removeAt(rows.lastIndex)
        assertThrows(IllegalArgumentException::class.java) { TerminalRenderSnapshot.capture(frame, rows) }
    }

    @Test fun oldAndNewCallerSnapshotsEachSeeTheMatchingAtomicPublication() {
        val terminal = terminal()
        val first = terminal.renderFrame()
        val frame = mutableStateOf(first, referentialEqualityPolicy())
        val rows = createTerminalRenderedRows(first)
        val sync = createTerminalRenderedRowsSyncState(first, rows)
        val previous = Snapshot.takeSnapshot()
        try {
            terminal.feed("\r\nnext")
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100, syncState = sync)
                frame.value = next
            }
            previous.enter {
                val captured = TerminalRenderSnapshot.capture(frame.value, rows)
                assertSame(first, captured.frame)
                assertEquals(first.historyLineIds.first(), captured.rows.first().lineId)
                requireTerminalRenderPair(TerminalLazyLayoutPass(first, "metrics"), captured.frame, captured.rows)
            }
            val captured = TerminalRenderSnapshot.capture(frame.value, rows)
            assertSame(next, captured.frame)
            assertEquals(next.historyLineIds.first(), captured.rows.first().lineId)
        } finally {
            previous.dispose()
        }
    }

    @Test fun discardedPublicationCannotLeakItsFrameOrRowStructure() {
        val terminal = terminal()
        val first = terminal.renderFrame()
        val frame = mutableStateOf(first, referentialEqualityPolicy())
        val rows = createTerminalRenderedRows(first)
        val before = TerminalRenderSnapshot.capture(first, rows)
        terminal.feed("\r\nnext")
        val next = terminal.renderFrame()
        val abandoned = Snapshot.takeMutableSnapshot()
        try {
            abandoned.enter {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100)
                frame.value = next
                assertSame(next, TerminalRenderSnapshot.capture(frame.value, rows).frame)
            }
        } finally {
            abandoned.dispose() // Intentionally no apply().
        }
        val after = TerminalRenderSnapshot.capture(frame.value, rows)
        assertSame(before.frame, after.frame)
        assertSame(before.rows, after.rows)
    }
}
