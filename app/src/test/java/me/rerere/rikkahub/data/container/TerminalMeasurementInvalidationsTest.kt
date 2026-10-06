package me.rerere.rikkahub.data.container

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.pages.container.TerminalMeasurementInvalidations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic work-count/ordering checks; no Android dispatcher, timing sleeps or mock frames. */
class TerminalMeasurementInvalidationsTest {
    @Test fun tenThousandRowBurstPublishesOneWakeupUntilTheObserverAcknowledges() {
        val invalidations = TerminalMeasurementInvalidations()
        assertFalse(invalidations.pending)
        assertTrue(invalidations.changes.replayCache.isEmpty())
        repeat(10_000) { invalidations.invalidate() }
        assertTrue(invalidations.pending)
        assertEquals(1L, invalidations.publishedNotifications)
        assertEquals(9_999L, invalidations.coalescedChanges)
        assertEquals(listOf(Unit), invalidations.changes.replayCache)
        invalidations.acknowledge()
        assertFalse(invalidations.pending)
        invalidations.invalidate()
        assertTrue(invalidations.pending)
        assertEquals(2L, invalidations.publishedNotifications)
    }

    @Test fun emissionDeliveryDoesNotRearmWhileTheBindingIsWaitingForItsFrame() = runBlocking {
        val invalidations = TerminalMeasurementInvalidations()
        invalidations.invalidate()
        invalidations.changes.first()
        repeat(10_000) { invalidations.invalidate() }
        assertEquals(1L, invalidations.publishedNotifications)
        assertEquals(10_000L, invalidations.coalescedChanges)
        invalidations.acknowledge() // Simulates the binding resuming from withFrameNanos.
        invalidations.invalidate() // A later measure must request another refresh.
        assertEquals(2L, invalidations.publishedNotifications)
        assertEquals(Unit, invalidations.changes.first())
    }

    @Test fun cancelledCollectorCannotLosePendingChangesForItsReplacement() = runBlocking {
        val invalidations = TerminalMeasurementInvalidations()
        var observed = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            invalidations.changes.collect { observed++ }
        }
        invalidations.invalidate()
        first.cancelAndJoin()
        assertTrue(invalidations.pending)
        val before = observed
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            invalidations.changes.collect { observed++ }
        }
        assertEquals(before + 1, observed)
        assertTrue(invalidations.pending)
        invalidations.acknowledge()
        invalidations.invalidate()
        assertEquals(2L, invalidations.publishedNotifications)
        replacement.cancelAndJoin()
    }

    @Test fun immediateCollectorCanAcknowledgeAndInvalidateDuringEmission() = runBlocking {
        val invalidations = TerminalMeasurementInvalidations()
        var observed = 0
        val collector = launch(kotlinx.coroutines.Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            invalidations.changes.collect {
                observed++
                if (observed == 1) {
                    invalidations.acknowledge()
                    invalidations.invalidate()
                }
            }
        }
        invalidations.invalidate()
        assertEquals(2, observed)
        assertEquals(2L, invalidations.publishedNotifications)
        assertEquals(0L, invalidations.coalescedChanges)
        assertTrue(invalidations.pending)
        collector.cancelAndJoin()
    }

    @Test fun snapshotOnlyRefreshAcknowledgementDoesNotPublishSpuriousMeasurements() {
        val invalidations = TerminalMeasurementInvalidations()
        repeat(8) { invalidations.acknowledge() }
        assertFalse(invalidations.pending)
        assertTrue(invalidations.changes.replayCache.isEmpty())
        assertEquals(0L, invalidations.publishedNotifications)
        invalidations.invalidate()
        invalidations.acknowledge()
        invalidations.acknowledge()
        assertEquals(1L, invalidations.publishedNotifications)
        assertEquals(0L, invalidations.coalescedChanges)
    }
}
