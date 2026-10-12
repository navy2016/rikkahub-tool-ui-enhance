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

    /** Mirrors the production transaction; no capture is allowed in the consumer. */
    private class Publisher(initial: TerminalEmulator.RenderFrame) {
        val rows = createTerminalRenderedRows(initial)
        private val sync = createTerminalRenderedRowsSyncState(initial, rows)
        val published = mutableStateOf(TerminalRenderSnapshot.capture(initial, rows), referentialEqualityPolicy())

        fun publish(frame: TerminalEmulator.RenderFrame, tui: Boolean = false, force: Boolean = false): Boolean {
            var deferred = false
            Snapshot.withMutableSnapshot {
                deferred = synchronizeTerminalRenderedRows(
                    rows, frame, tui, forcePendingGridBlanks = force, nowMs = 100, syncState = sync,
                )
                published.value = TerminalRenderSnapshot.capture(frame, rows)
            }
            return deferred
        }
    }

    @Test fun repeatedReadsReuseThePublicationWithoutComparingOrCopyingItsRows() {
        val p = Publisher(terminal(10_000).renderFrame())
        val first = p.published.value
        repeat(32) {
            assertSame(first, p.published.value)
            assertSame(p.rows.toList(), p.published.value.rows)
        }
    }

    @Test fun sameFrameAndSameRevisionReplacementBothPublishNewIdentities() {
        val frame = terminal().renderFrame()
        val p = Publisher(frame)
        val before = p.published.value
        p.publish(frame)
        val sameFrame = p.published.value
        assertNotSame(before, sameFrame)
        assertSame(before.frame, sameFrame.frame)
        assertSame(before.rows, sameFrame.rows)
        val replacement = frame.copy()
        p.publish(replacement)
        val after = p.published.value
        assertNotSame(sameFrame, after)
        assertSame(replacement, after.frame)
        assertEquals(before.frame.revision, after.frame.revision)
        assertSame(before.rows, after.rows)
    }

    @Test fun fifoResizeAndClearPublishMatchingStructureAndRetainOldListViews() {
        val terminal = terminal()
        val p = Publisher(terminal.renderFrame())
        val before = p.published.value
        val oldIds = before.rows.map { it.lineId }
        val mutations: List<() -> Unit> = listOf(
            { terminal.feed("\r\nnext") },
            { terminal.resize(40, 8) },
            { terminal.feed("\u001B[3J\u001B[2J\u001B[Hclear") },
        )
        for (mutate in mutations) {
            mutate()
            val frame = terminal.renderFrame()
            val previous = p.published.value
            p.publish(frame)
            val current = p.published.value
            assertNotSame(previous, current)
            assertSame(frame, current.frame)
            assertSame(p.rows.toList(), current.rows)
            assertEquals(frame.historyLineIds + frame.screenLineIds, current.rows.map { it.lineId })
            assertEquals(frame.rows.map { it.text }, current.rows.map { it.text })
        }
        assertEquals(oldIds, before.rows.map { it.lineId })
        assertEquals(0, p.published.value.frame.historyCount)
    }

    @Test fun forcedGridBlankCommitPublishesEvenWithTheExactSameFrameAndRowView() {
        val terminal = TerminalEmulator(80, 6).apply { feed("\u001B[?25lstatus") }
        val p = Publisher(terminal.renderFrame())
        terminal.feed("\r\u001B[2K")
        val blank = terminal.renderFrame()
        assertTrue(p.publish(blank, tui = true))
        val deferred = p.published.value
        assertEquals("status", deferred.rows[0].text.text.trimEnd())
        assertFalse(p.publish(blank, tui = true, force = true))
        val committed = p.published.value
        assertNotSame(deferred, committed)
        assertSame(blank, committed.frame)
        assertSame(deferred.rows, committed.rows)
        assertTrue(committed.rows[0].text.text.isBlank())
        assertEquals(0L, committed.rows[0].pendingBlankSinceMs)
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
        val p = Publisher(first)
        val initial = p.published.value
        val previous = Snapshot.takeSnapshot()
        try {
            terminal.feed("\r\nnext")
            val next = terminal.renderFrame()
            p.publish(next)
            previous.enter {
                val captured = p.published.value
                assertSame(initial, captured)
                assertSame(p.rows.toList(), captured.rows)
                assertSame(first, captured.frame)
                assertEquals(first.historyLineIds.first(), captured.rows.first().lineId)
                requireTerminalRenderPair(TerminalLazyLayoutPass(first, "metrics"), captured.frame, captured.rows)
            }
            val captured = p.published.value
            assertSame(next, captured.frame)
            assertEquals(next.historyLineIds.first(), captured.rows.first().lineId)
        } finally {
            previous.dispose()
        }
    }

    @Test fun discardedPublicationCannotLeakItsFrameOrRowStructure() {
        val terminal = terminal()
        val first = terminal.renderFrame()
        val rows = createTerminalRenderedRows(first)
        val before = TerminalRenderSnapshot.capture(first, rows)
        val published = mutableStateOf(before, referentialEqualityPolicy())
        terminal.feed("\r\nnext")
        val next = terminal.renderFrame()
        val abandoned = Snapshot.takeMutableSnapshot()
        try {
            abandoned.enter {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100)
                published.value = TerminalRenderSnapshot.capture(next, rows)
                assertSame(next, published.value.frame)
            }
        } finally {
            abandoned.dispose() // Intentionally no apply().
        }
        val after = published.value
        assertSame(before, after)
        assertSame(before.frame, after.frame)
        assertSame(before.rows, after.rows)
        assertSame(before.rows, rows.toList())
    }
}
