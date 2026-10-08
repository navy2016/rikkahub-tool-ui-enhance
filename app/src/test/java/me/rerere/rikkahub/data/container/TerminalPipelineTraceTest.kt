package me.rerere.rikkahub.data.container

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPipelineTraceTest {
    @Test fun disabledAndDifferentSessionsDoNotReadClockOrAllocateEvents() {
        TerminalPipelineTrace.record("disabled", TerminalPipelineStage.UI_INPUT)
        assertFalse(TerminalPipelineTrace.isRecording("disabled"))
        TerminalPipelineTrace.start("one", clock = { error("unexpected clock read") }).use { capture ->
            TerminalPipelineTrace.record("another", TerminalPipelineStage.OUTPUT_READ)
            assertTrue(capture.snapshot().isEmpty())
        }
    }

    @Test fun explicitWindowDiscardsSetupButNeverReusesSequenceNumbers() {
        TerminalPipelineTrace.start("one", 16).use { capture ->
            repeat(30) { TerminalPipelineTrace.record("one", TerminalPipelineStage.OUTPUT_READ) }
            assertEquals(30L, capture.beginWindow())
            assertEquals(0L, capture.dropped)
            assertTrue(capture.snapshot().isEmpty())
            TerminalPipelineTrace.record("one", TerminalPipelineStage.UI_INPUT)
            assertEquals(31L, capture.snapshot().single().sequence)
        }
    }

    @Test fun capacityBoundsEventsAndReportsDropsWithoutRenumbering() {
        var time = 100L
        TerminalPipelineTrace.start("one", 16) { ++time }.use { capture ->
            repeat(40) { TerminalPipelineTrace.record("one", TerminalPipelineStage.OUTPUT_READ, bytes = it) }
            val events = capture.snapshot()
            assertEquals(16, events.size)
            assertEquals(24L, capture.dropped)
            assertEquals(25L, events.first().sequence)
            assertEquals(40L, events.last().sequence)
            assertEquals(140L, events.last().timeNanos)
            assertEquals(39, events.last().bytes)
        }
    }

    @Test fun closedCaptureRejectsInFlightRecordAndCannotStopItsReplacement() {
        val old = TerminalPipelineTrace.start("one")
        old.close()
        TerminalPipelineTrace.start("one").use { current ->
            old.record(TerminalPipelineStage.OUTPUT_READ, 1, -1)
            old.close()
            assertTrue(old.snapshot().isEmpty())
            assertTrue(TerminalPipelineTrace.isRecording("one"))
            TerminalPipelineTrace.record("one", TerminalPipelineStage.FRAME_DRAWN, frameRevision = 42)
            assertEquals(42L, current.snapshot().single().frameRevision)
        }
        assertFalse(TerminalPipelineTrace.isRecording("one"))
    }

    @Test fun snapshotsDoNotChangeWhenMoreEventsAreRecorded() {
        TerminalPipelineTrace.start("one").use { capture ->
            TerminalPipelineTrace.record("one", TerminalPipelineStage.UI_INPUT)
            val before = capture.snapshot()
            TerminalPipelineTrace.record("one", TerminalPipelineStage.INPUT_ENQUEUED)
            assertEquals(1, before.size)
            assertEquals(2, capture.snapshot().size)
        }
    }

    @Test fun concurrentIoAndUiPublishUniqueOrderedBoundedEvents() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            TerminalPipelineTrace.start("one", 128).use { capture ->
                val jobs = (0..3).map { pool.submit { repeat(500) {
                    TerminalPipelineTrace.record("one", TerminalPipelineStage.EMULATOR_FED)
                } } }
                jobs.forEach { it.get() }
                val rows = capture.snapshot()
                assertEquals(128, rows.size)
                assertEquals(1872L, capture.dropped)
                assertTrue(rows.zipWithNext().all { (a, b) -> a.sequence + 1 == b.sequence && a.timeNanos <= b.timeNanos })
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun overlappingOrInvalidCapturesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TerminalPipelineTrace.start("") }
        assertThrows(IllegalArgumentException::class.java) { TerminalPipelineTrace.start("one", 4_097) }
        TerminalPipelineTrace.start("one").use {
            assertThrows(IllegalStateException::class.java) { TerminalPipelineTrace.start("two") }
        }
    }
}
