package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRowStore
import me.rerere.rikkahub.ui.pages.container.TerminalSparseRows
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

class TerminalBundleContractsTest {
    private fun terminal(size: Int = 128) = TerminalEmulator(80, 24, size).apply {
        feed("\u001b[?25l" + (0 until size + 24).joinToString("\r\n") { "line-$it 中文" })
    }

    @Test fun foregroundStopDuringStartupWaitsForRealPromotion() {
        val lifecycle = TerminalForegroundLifecycle()
        val request = requireNotNull(lifecycle.request(1))
        assertNull(lifecycle.acknowledgedCount)
        assertNull(lifecycle.request(0))
        assertTrue(lifecycle.startPending)
        val instance = lifecycle.created()
        assertNull(lifecycle.promoted(instance, request.id + 1))
        assertEquals(0, lifecycle.promoted(instance, request.id))
        assertFalse(lifecycle.startPending)
        lifecycle.destroyed(instance)
        assertNull(lifecycle.request(0))
        assertNotNull(lifecycle.request(2))
    }

    @Test fun foregroundFailureAndOldInstanceCannotConfirmNewRequest() {
        val lifecycle = TerminalForegroundLifecycle()
        val first = requireNotNull(lifecycle.request(2))
        lifecycle.failed(first)
        val next = requireNotNull(lifecycle.request(2))
        val old = lifecycle.created()
        assertEquals(2, lifecycle.promoted(old, next.id))
        assertNull(lifecycle.request(2))
        lifecycle.destroyed(old)
        val retry = requireNotNull(lifecycle.request(2))
        val current = lifecycle.created()
        lifecycle.destroyed(old)
        assertNull(lifecycle.promoted(old, retry.id))
        assertEquals(2, lifecycle.promoted(current, retry.id))
    }

    @Test fun conflatedNotificationStillAccountsForEveryParsedByteAndResize() {
        val notices = TerminalOutputNotifications()
        repeat(10000) { notices.published(4096, it.toLong(), it.toLong() + 1) }
        val update = notices.flow.value
        assertEquals(10000L, update.sequence)
        assertEquals(40_960_000L, update.totalBytes)
        assertEquals(9999L, update.revision)
        notices.published(0, 10001, 10002)
        assertEquals(update.totalBytes, notices.flow.value.totalBytes)
        assertEquals(update.sequence + 1, notices.flow.value.sequence)
        assertEquals(update.timeNanos, notices.flow.value.timeNanos)
        notices.published(1, 1, 1)
        assertEquals(10001L, notices.flow.value.revision)
        assertEquals(update.timeNanos, notices.flow.value.timeNanos)
        assertThrows(IllegalArgumentException::class.java) { notices.published(-1, 0, 0) }
    }

    @Test fun notificationSequenceRemainsMonotonicWithConcurrentProducers() {
        val notices = TerminalOutputNotifications()
        val threads = List(4) { worker -> Thread {
            repeat(1000) { notices.published(7, worker.toLong(), it.toLong()) }
        } }
        threads.forEach(Thread::start)
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(4000L, notices.flow.value.sequence)
        assertEquals(28000L, notices.flow.value.totalBytes)
    }

    @Test fun sparsePublicationAllocatesOnlyPhysicalStatesAtEveryHistorySize() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = terminal(size)
            val frame = terminal.renderFrame()
            val store = TerminalRowStore(frame, sparse = true)
            val source = TerminalRenderSnapshot.capture(frame, store)
            assertTrue(source.rows is TerminalSparseRows)
            assertEquals(24, store.retainedRowStates)
            assertEquals(size + 24, source.rows.size)
            assertEquals(frame.historyLineIds[13], source.rows[13].lineId)
            assertEquals(frame.rows[13].text, source.rows[13].text)
            val screen = source.rows[size]
            repeat(30) {
                terminal.feed("\r\u001b[2Kactive-$it")
                val next = terminal.renderFrame()
                Snapshot.withMutableSnapshot { store.publish(next, true, false, false, 1000) }
                val changed = TerminalRenderSnapshot.capture(next, store)
                assertEquals(24, store.retainedRowStates)
                assertSame(screen, changed.rows[size])
                assertEquals(next.rows.last().text, changed.rows.last().text)
            }
        }
    }

    @Test fun sparseFifoAndEagerTransitionsPreserveFrameIdsAndActiveState() {
        val terminal = terminal()
        var frame = terminal.renderFrame()
        val store = TerminalRowStore(frame, true)
        val stable = store.view(frame).last()
        terminal.feed("\r\nnew")
        frame = terminal.renderFrame()
        Snapshot.withMutableSnapshot { store.publish(frame, true, false, false, 1000) }
        assertEquals(24, store.retainedRowStates)
        assertSame(stable, store.view(frame)[frame.rows.size - 2])
        for (sparse in listOf(false, true, false)) {
            Snapshot.withMutableSnapshot { store.publish(frame, sparse, false, true, 2000) }
            val source = TerminalRenderSnapshot.capture(frame, store)
            assertEquals(frame.historyLineIds + frame.screenLineIds, source.rows.map { it.lineId })
            assertEquals(frame.rows.map { it.text }, source.rows.map { it.text })
            assertEquals(if (sparse) 24 else 152, store.retainedRowStates)
        }
    }

    @Test fun discardedSparseTransactionCannotPublishNewFrameOrRows() {
        val terminal = terminal()
        val first = terminal.renderFrame()
        val store = TerminalRowStore(first, true)
        val before = store.view(first)
        terminal.feed("\r\nnew")
        val next = terminal.renderFrame()
        val transaction = Snapshot.takeMutableSnapshot()
        try {
            transaction.enter {
                store.publish(next, true, false, true, 1000)
                assertSame(next, TerminalRenderSnapshot.capture(next, store).frame)
            }
        } finally { transaction.dispose() }
        assertSame(before, store.view(first))
        assertThrows(IllegalArgumentException::class.java) { store.view(next) }
    }

    @Test fun scalarWidthCheckpointSurvivesRemountButPrunesAndMeasuresOnlyNewArchives() {
        val terminal = terminal(10000)
        val frame = terminal.renderFrame()
        val index = TerminalTranscriptWidthIndex()
        fun width(text: androidx.compose.ui.text.AnnotatedString) = text.length * 7
        val expected = index.width(frame, "metrics", ::width)
        val checkpoint = requireNotNull(index.checkpoint("proof"))
        assertTrue(checkpoint.retainedRows <= 10000)
        index.clear()
        val restored = TerminalTranscriptWidthIndex()
        assertTrue(restored.restore(checkpoint, "proof", "metrics"))
        assertEquals(expected, restored.width(frame, "metrics", ::width))
        assertEquals(0, restored.lastMeasuredHistoryRows)
        assertEquals(24, restored.lastMeasuredScreenRows)
        repeat(12) { terminal.feed("\r\nnew-$it") }
        val next = terminal.renderFrame()
        assertEquals(next.rows.maxOf { width(it.text) }, restored.width(next, "metrics", ::width))
        assertEquals(12, restored.lastMeasuredHistoryRows)
        assertEquals(0, restored.requiredHistoryMeasurements(next, "metrics"))
        assertFalse(restored.restore(checkpoint, "changed-font", "metrics"))
    }

    @Test fun checkpointLeasesRejectLateOldPagesAndClosedSessions() {
        val store = TerminalWidthCheckpointStore()
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        index.width(terminal.renderFrame(), 1) { it.length }
        val checkpoint = requireNotNull(index.checkpoint(2))
        val old = requireNotNull(store.open())
        store.save(old, checkpoint)
        val current = requireNotNull(store.open())
        store.save(old, null)
        assertSame(checkpoint, store.read(current))
        store.close()
        assertNull(store.open())
        store.save(current, checkpoint)
        assertEquals(0, store.retainedRows())
    }

    @Test fun workerForkAndMeasurementFailureNeverCorruptTheDisplayedIndex() {
        val terminal = terminal()
        val frame = terminal.renderFrame()
        val index = TerminalTranscriptWidthIndex()
        val expected = index.width(frame, 1) { it.length }
        val worker = index.fork()
        terminal.feed("\r\nextra")
        val next = terminal.renderFrame()
        assertThrows(IllegalStateException::class.java) { worker.width(next, 1) { error("cancelled work") } }
        assertTrue(index.readyFor(frame, 1))
        assertEquals(expected, index.width(frame, 1) { error("cached") })
        val complete = index.fork()
        val maximum = complete.width(next, 1) { it.length }
        assertFalse(index.readyFor(next, 1))
        index.acceptComputed(complete)
        assertTrue(index.readyFor(next, 1))
        assertEquals(maximum, index.width(next, 1) { error("already complete") })
    }

    @Test fun disposedOrReplacedIndexRejectsLateWorkerEvenIfItsFontAndFrameStillMatch() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        index.width(frame, 1) { it.length }
        val epoch = index.epoch
        val worker = index.fork()
        worker.width(frame, 1) { error("cached") }
        index.clear()
        assertFalse(index.acceptComputed(worker, epoch))
        assertFalse(index.hasRetainedState)
        val nextEpoch = index.epoch
        index.width(frame, 2) { it.length * 2 }
        assertFalse(index.acceptComputed(worker, nextEpoch))
        assertTrue(index.readyFor(frame, 2))
        assertNull(index.checkpoint("wrong metric", 1))
        assertThrows(IllegalArgumentException::class.java) {
            index.acceptComputed(TerminalTranscriptWidthIndex())
        }
    }
}
