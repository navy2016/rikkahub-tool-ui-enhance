package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.ui.pages.container.TerminalChunkViewIdentity
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.createTerminalChunkViews
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalChunkViewIdentity
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalChunkViewsTest {
    private fun terminal(size: Int = 260, offset: Int = 125) = TerminalEmulator(80, 6, size).apply {
        feed("\u001B[?25l" + (0 until size + 6 + offset).joinToString("\r\n") { "row-$it" })
    }

    @Test fun chunkViewKeysNeverReadStructuralEqualityOrHashCodes() {
        class NoComparison {
            override fun equals(other: Any?): Boolean = error("structural equality was used")
            override fun hashCode(): Int = error("structural hash was used")
        }
        val rows = NoComparison()
        val plan = NoComparison()
        val first = TerminalChunkViewIdentity(rows, plan)
        val equal = TerminalChunkViewIdentity(rows, plan)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotEquals(first, TerminalChunkViewIdentity(NoComparison(), plan))
        assertNotEquals(first, TerminalChunkViewIdentity(rows, NoComparison()))
    }

    @Test fun chunkViewsCoverEachRowOnceAtEveryBoundaryAndHistorySize() {
        for (size in listOf(1, 127, 128, 129, 1_000, 5_000, 10_000)) {
            for (offset in listOf(0, 1, 125, 127, 128)) {
                val frame = terminal(size, offset).renderFrame()
                val source = TerminalRenderSnapshot.capture(frame, createTerminalRenderedRows(frame))
                val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
                val views = createTerminalChunkViews(source, plan)
                val flattened = views.history.flatMap { it.rows } + views.screen
                assertEquals(source.rows.size, flattened.size)
                source.rows.forEachIndexed { index, row -> assertSame(row, flattened[index]) }
                assertEquals(plan.map { it.bucket }, views.history.map { it.bucket })
                assertEquals(6, views.screen.size)
                assertTrue(views.history.all { it.rows.size in 1..128 })
            }
        }
    }

    @Test fun activeTextAndSameRevisionNewFramesKeepTheSameImmutableStructureKey() {
        val terminal = terminal()
        val frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val before = TerminalRenderSnapshot.capture(frame, rows)
        val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        val key = terminalChunkViewIdentity(before, before.rows, plan)
        val views = createTerminalChunkViews(before, plan)
        terminal.feed("\r\u001B[2Kupdated text")
        val next = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100) }
        val after = TerminalRenderSnapshot.capture(next, rows)
        assertSame(before.rows, after.rows)
        assertEquals(key, terminalChunkViewIdentity(after, after.rows, plan))
        assertEquals("updated text", views.screen.last().text.text.trimEnd())
        val replacement = TerminalRenderSnapshot.capture(next.copy(), rows)
        assertNotSame(after.frame, replacement.frame)
        assertEquals(key, terminalChunkViewIdentity(replacement, replacement.rows, plan))
    }

    @Test fun equalIdsWithNewRowObjectsAndEqualPlansCannotReuseOldViews() {
        val frame = terminal().renderFrame()
        val before = TerminalRenderSnapshot.capture(frame, createTerminalRenderedRows(frame))
        val after = TerminalRenderSnapshot.capture(frame, createTerminalRenderedRows(frame))
        val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        val newPlan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        assertEquals(plan, newPlan)
        assertNotEquals(terminalChunkViewIdentity(before, before.rows, plan),
            terminalChunkViewIdentity(after, after.rows, plan))
        assertNotEquals(terminalChunkViewIdentity(before, before.rows, plan),
            terminalChunkViewIdentity(before, before.rows, newPlan))
        val oldViews = createTerminalChunkViews(before, plan)
        val newViews = createTerminalChunkViews(after, plan)
        assertNotSame(oldViews.history.first().rows.first(), newViews.history.first().rows.first())
        assertSame(after.rows.first(), newViews.history.first().rows.first())
    }

    @Test fun fifoCrossingAnEntireBucketAndClearNeverMutateOldSlices() {
        val terminal = terminal()
        var frame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val original = TerminalRenderSnapshot.capture(frame, rows)
        val originalPlan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        val originalViews = createTerminalChunkViews(original, originalPlan)
        val oldIds = originalViews.history.flatMap { it.rows }.map { it.lineId }
        repeat(6) { update ->
            terminal.feed("\r\nappend-$update")
            frame = terminal.renderFrame()
            Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 100) }
            val source = TerminalRenderSnapshot.capture(frame, rows)
            val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
            val current = createTerminalChunkViews(source, plan)
            assertEquals(frame.historyLineIds, current.history.flatMap { it.rows }.map { it.lineId })
            assertEquals(frame.screenLineIds, current.screen.map { it.lineId })
            assertNotEquals(terminalChunkViewIdentity(original, original.rows, originalPlan),
                terminalChunkViewIdentity(source, source.rows, plan))
        }
        assertEquals(oldIds, originalViews.history.flatMap { it.rows }.map { it.lineId })
        terminal.clearScrollbackOnly()
        frame = terminal.renderFrame()
        Snapshot.withMutableSnapshot { synchronizeTerminalRenderedRows(rows, frame, false, nowMs = 200) }
        val cleared = TerminalRenderSnapshot.capture(frame, rows)
        assertNull(terminalChunkViewIdentity(cleared, cleared.rows,
            terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)))
    }

    @Test fun unprovenMutableSourcesStayUncachedAndMismatchedCapabilitiesAreRejected() {
        val frame = terminal().renderFrame()
        val source = TerminalRenderSnapshot.capture(frame, createTerminalRenderedRows(frame))
        val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        val arbitrary = plan.toMutableList()
        assertNull(terminalChunkViewIdentity(null, source.rows.toMutableList(), plan))
        assertNull(terminalChunkViewIdentity(source, source.rows, arbitrary))
        arbitrary.removeAt(arbitrary.lastIndex)
        assertNull(terminalChunkViewIdentity(source, source.rows, arbitrary))
        assertThrows(IllegalArgumentException::class.java) {
            terminalChunkViewIdentity(source, source.rows.toMutableList(), plan)
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalChunkViewIdentity(source, source.rows,
                terminalHistoryChunks(frame.historyCount - 1, frame.historyStartSequence))
        }
        assertThrows(IllegalArgumentException::class.java) {
            terminalChunkViewIdentity(source, source.rows,
                terminalHistoryChunks(frame.historyCount, requireNotNull(frame.historyStartSequence) + 1))
        }
    }

    @Test fun abortedStructureReplacementLeavesTheOriginalSourceAndViewsValid() {
        val frame = terminal().renderFrame()
        val rows = createTerminalRenderedRows(frame)
        val source = TerminalRenderSnapshot.capture(frame, rows)
        val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence)
        val views = createTerminalChunkViews(source, plan)
        val original = rows[0]
        val abandoned = Snapshot.takeMutableSnapshot()
        try {
            abandoned.enter {
                rows[0] = TerminalRenderedRowState(original.lineId, AnnotatedString("replacement"))
                val changed = TerminalRenderSnapshot.capture(frame, rows)
                assertNotEquals(terminalChunkViewIdentity(source, source.rows, plan),
                    terminalChunkViewIdentity(changed, changed.rows, plan))
            }
        } finally {
            abandoned.dispose()
        }
        assertSame(source.rows, rows.toList())
        assertSame(original, views.history.first().rows.first())
        val current = TerminalRenderSnapshot.capture(frame, rows)
        assertEquals(terminalChunkViewIdentity(source, source.rows, plan),
            terminalChunkViewIdentity(current, current.rows, plan))
    }
}
