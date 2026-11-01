package me.rerere.rikkahub.data.container

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume

class TerminalInFramePublicationTest {
    private fun allowed(flags: List<Boolean>) = terminalInFramePublicationAllowed(
        flags[0], flags[1], flags[2], flags[3], flags[4], flags[5], flags[6], flags[7], flags[8], flags[9], flags[10])
    private val safe = listOf(true,true,true,true,true,false,false,false,false,false,true)
    private class HeldFrameClock : MonotonicFrameClock {
        var callback: (() -> Unit)? = null
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = suspendCancellableCoroutine { continuation ->
            check(callback == null)
            callback = { continuation.resume(onFrame(1L)) }
        }
        fun fire() { val current=checkNotNull(callback);callback=null;current() }
    }

    @Test fun allSafetyConditionsAreRequiredExhaustively() {
        for (mask in 0 until 2048) {
            val flags=(0 until 11).map { mask and (1 shl it) != 0 }
            assertEquals("guard vector $mask", flags==safe, allowed(flags))
        }
    }
    @Test fun eligibleCaptureClearsBeforeAndPublishesOnce() {
        val pending=AtomicBoolean(true);var calls=0
        assertTrue(tryTerminalInFramePublication(allowed(safe),pending) { calls++;assertFalse(pending.get());true })
        assertEquals(1,calls);assertFalse(pending.get())
    }
    @Test fun declinedGateNeverConsumesPendingOrPublishes() {
        for (index in safe.indices) {
            val flags=safe.toMutableList().apply{this[index]=!this[index]};val pending=AtomicBoolean(true)
            assertFalse(tryTerminalInFramePublication(allowed(flags),pending) { fail("Rejected guard published");true })
            assertTrue(pending.get())
        }
    }
    @Test fun rejectedCaptureRestoresPendingForOriginalPath() {
        val pending=AtomicBoolean(true);var calls=0
        assertFalse(tryTerminalInFramePublication(true,pending) { calls++;false })
        assertTrue(pending.get());assertEquals(1,calls)
    }
    @Test fun notificationDuringSuccessfulCaptureIsNotClearedAfterward() {
        val pending=AtomicBoolean(true)
        assertTrue(tryTerminalInFramePublication(true,pending) { pending.set(true);true })
        assertTrue("Later work must survive the callback",pending.get())
    }
    @Test fun notificationAfterCallbackRemainsForNextLoop() {
        val pending=AtomicBoolean(true)
        assertTrue(tryTerminalInFramePublication(true,pending) { true })
        pending.set(true)
        assertTrue(pending.getAndSet(false))
    }
    @Test fun modelRaceRefusalCanBeRetriedByOriginalContinuation() {
        val pending=AtomicBoolean(true);var calls=0
        val inFrame=tryTerminalInFramePublication(true,pending) { calls++;false }
        if (!inFrame) { pending.set(false);calls++ }
        assertEquals(2,calls);assertFalse(pending.get())
    }
    @Test fun completedCallbackDoesNotDuplicateOriginalPublication() {
        val pending=AtomicBoolean(true);var calls=0
        val inFrame=tryTerminalInFramePublication(true,pending) { calls++;true }
        if (!inFrame) calls++
        assertEquals(1,calls)
    }
    @Test fun exceptionIsNotConvertedToSuccessfulPublication() {
        val expected=IllegalStateException("test");val pending=AtomicBoolean(true)
        try { tryTerminalInFramePublication(true,pending) { throw expected };fail("Exception swallowed") }
        catch (actual:IllegalStateException) { assertSame(expected,actual) }
    }
    @Test fun cancelledJobDoesNotPublishEvenIfAnAlreadyCapturedFrameCallbackRuns() = runBlocking {
        val clock=HeldFrameClock();val pending=AtomicBoolean(true);var calls=0
        val job=launch(clock) {
            val context=currentCoroutineContext()
            withFrameNanos { context.isActive && tryTerminalInFramePublication(true,pending) { calls++;true } }
        }
        yield();assertNotNull(clock.callback);job.cancel();clock.fire();job.join()
        assertEquals(0,calls);assertTrue(pending.get())
    }
    @Test fun callbackNotificationSurvivesUntilNextFrameWithoutDuplicateContinuationPublish() = runBlocking {
        val clock=HeldFrameClock();val pending=AtomicBoolean(true);var callbacks=0;var fallback=0
        val job=launch(clock) {
            while(pending.getAndSet(false)) {
                val context=currentCoroutineContext()
                val completed=withFrameNanos {
                    context.isActive && tryTerminalInFramePublication(true,pending) {
                        callbacks++;if(callbacks==1)pending.set(true);true
                    }
                }
                if(!completed)fallback++
            }
        }
        yield();clock.fire();yield()
        assertEquals(1,callbacks);assertNotNull(clock.callback)
        clock.fire();job.join();assertEquals(2,callbacks);assertEquals(0,fallback);assertFalse(pending.get())
    }
    @Test fun readOnlySnapshotMustUseContinuationInsteadOfPublishingInCallback() {
        val snapshot=Snapshot.takeSnapshot()
        try {
            snapshot.enter {
                assertTrue(Snapshot.current.readOnly)
                assertFalse(allowed(safe.toMutableList().apply { this[10]=!Snapshot.current.readOnly }))
            }
        } finally { snapshot.dispose() }
    }
}
