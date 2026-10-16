package me.rerere.rikkahub.data.container

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPipelineTraceTest {
    private fun viewport(revision: Long = 1) = TerminalPipelineViewport(
        revision = revision, preferred = TerminalRenderMode.VIRTUAL_HISTORY_IME,
        applied = TerminalRenderMode.VIRTUAL_HISTORY_IME, historyRows = 10_000,
        hasChunks = true, pan = true, selection = false, tui = false, alternate = false,
        ime = false, avoidIme = false, imeFallback = false, wantsVirtual = true,
        virtual = true, bindingVirtual = true, sameFrame = true, initialized = true, following = true,
        gestureId = null, effectId = null, writers = 0, scrolling = false,
        geometryReady = true, widthReady = true, tailSatisfied = true,
    )

    @Test fun viewportDiagnosticsAreBoundedScalarsAndDoNotConsumeTheEventRing() {
        var time = 0L
        TerminalPipelineTrace.start("one", 16, clock = { ++time }).use { capture ->
            repeat(100) { TerminalPipelineTrace.recordViewport("one", viewport(it.toLong())) }
            assertEquals(64, capture.viewportTransitions().size)
            assertEquals(99L, capture.viewport()!!.viewport.revision)
            assertEquals(100L, capture.viewport()!!.draw)
            assertEquals(0L, capture.dropped)
            assertTrue(capture.snapshot().isEmpty())
            val before = capture.viewportTransitions()
            repeat(10) { TerminalPipelineTrace.recordViewport("one", viewport(99)) }
            assertEquals(before, capture.viewportTransitions())
            assertEquals(110L, capture.viewport()!!.draw)
            capture.beginWindow()
            assertEquals(110L, capture.viewport()!!.draw)
        }
    }

    @Test fun absentOrReversedDrawDiscardsPreviouslyAcceptedHalfOfAConfirmation() {
        val probe = TerminalPipelineSettleProbe()
        val mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
        fun sample(draw: Long) = TerminalPipelineViewportSample(draw, draw, viewport())
        assertFalse(probe.observe(sample(2), mode, true, false, 1))
        assertFalse(probe.observe(null, mode, true, false, 1))
        assertEquals(0, probe.confirmations)
        assertFalse(probe.observe(sample(3), mode, true, false, 1))
        assertFalse(probe.observe(sample(2), mode, true, false, 1))
        assertEquals(0, probe.confirmations)
        assertFalse(probe.observe(sample(4), mode, true, false, 1))
        assertTrue(probe.observe(sample(5), mode, true, false, 1))
    }

    @Test fun settleRequiresTwoNewActualDrawsNotTwoReadsOfOneSnapshot() {
        val value = viewport()
        val mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
        val probe = TerminalPipelineSettleProbe(afterDraw = 10)
        fun sample(draw: Long) = TerminalPipelineViewportSample(draw, draw * 100, value)
        assertFalse(probe.observe(sample(10), mode, true, false, 1))
        assertFalse(probe.observe(sample(11), mode, true, false, 1))
        repeat(10) { assertFalse(probe.observe(sample(11), mode, true, false, 1)) }
        assertEquals(1, probe.confirmations)
        assertTrue(probe.observe(sample(12), mode, true, false, 1))
        assertEquals(11L, probe.firstStableDraw)
        assertEquals(2, probe.confirmations)
    }

    @Test fun visibleEagerHandoffAndUnmeasuredVirtualNeverSatisfyTheExpectedVirtualMode() {
        val mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
        val ready = viewport()
        val waiting = listOf(
            ready.copy(virtual = false, bindingVirtual = false, widthReady = false),
            ready.copy(widthReady = false, geometryReady = false, tailSatisfied = false),
            ready.copy(effectId = 7, writers = 1, scrolling = true),
        )
        for (value in waiting) {
            val probe = TerminalPipelineSettleProbe()
            repeat(8) { index ->
                assertFalse(probe.observe(TerminalPipelineViewportSample(index.toLong() + 1, index.toLong(), value),
                    mode, true, false, 1))
            }
            assertFalse(probe.observe(TerminalPipelineViewportSample(9, 9, ready), mode, true, false, 1))
            assertTrue(probe.observe(TerminalPipelineViewportSample(10, 10, ready), mode, true, false, 1))
        }
    }

    @Test fun everyCompatibilityReadinessAndSourceGateCanRejectASample() {
        val ready = viewport()
        val wrong = listOf(
            ready.copy(preferred = TerminalRenderMode.DEFAULT), ready.copy(applied = TerminalRenderMode.DEFAULT),
            ready.copy(historyRows = 0), ready.copy(hasChunks = false), ready.copy(pan = false),
            ready.copy(selection = true), ready.copy(tui = true), ready.copy(alternate = true),
            ready.copy(ime = true), ready.copy(imeFallback = true), ready.copy(wantsVirtual = false),
            ready.copy(virtual = false), ready.copy(bindingVirtual = false), ready.copy(sameFrame = false),
            ready.copy(initialized = false), ready.copy(following = false), ready.copy(gestureId = 1),
            ready.copy(effectId = 1), ready.copy(writers = 1), ready.copy(scrolling = true),
            ready.copy(geometryReady = false), ready.copy(widthReady = false), ready.copy(tailSatisfied = false),
            ready.copy(revision = 0),
        )
        for (value in wrong) assertFalse(value.satisfies(TerminalRenderMode.VIRTUAL_HISTORY_IME, true, false, 1))
        assertTrue(ready.copy(ime = true, avoidIme = true)
            .satisfies(TerminalRenderMode.VIRTUAL_HISTORY_IME, true, true, 1))
        val fallback = ready.copy(preferred = TerminalRenderMode.VIRTUAL_HISTORY,
            applied = TerminalRenderMode.VIRTUAL_HISTORY, imeFallback = true,
            wantsVirtual = false, virtual = false, bindingVirtual = false, widthReady = false)
        assertTrue(fallback.satisfies(TerminalRenderMode.VIRTUAL_HISTORY, false, false, 1))
        assertFalse(fallback.satisfies(TerminalRenderMode.VIRTUAL_HISTORY, true, false, 1))
    }

    @Test fun changedFrameOrInterveningInvalidLayoutRestartsConsecutiveDrawConfirmation() {
        val mode = TerminalRenderMode.VIRTUAL_HISTORY_IME
        val probe = TerminalPipelineSettleProbe()
        val ready = viewport()
        assertFalse(probe.observe(TerminalPipelineViewportSample(1, 1, ready), mode, true, false, 1))
        assertFalse(probe.observe(TerminalPipelineViewportSample(2, 2, ready.copy(revision = 2)),
            mode, true, false, 2))
        assertFalse(probe.observe(TerminalPipelineViewportSample(3, 3, ready.copy(geometryReady = false)),
            mode, true, false, 2))
        assertEquals(0, probe.confirmations)
        assertFalse(probe.observe(TerminalPipelineViewportSample(4, 4, ready.copy(revision = 2)),
            mode, true, false, 2))
        assertTrue(probe.observe(TerminalPipelineViewportSample(5, 5, ready.copy(revision = 2)),
            mode, true, false, 2))
        assertEquals(4L, probe.firstStableDraw)
    }

    @Test fun viewportDiagnosticsCannotCrossSessionsOrReviveAClosedCapture() {
        val old = TerminalPipelineTrace.start("one")
        TerminalPipelineTrace.recordViewport("other", viewport())
        assertEquals(null, old.viewport())
        old.close()
        TerminalPipelineTrace.start("one").use { current ->
            old.recordViewport(viewport())
            assertEquals(null, old.viewport())
            assertEquals(null, current.viewport())
            TerminalPipelineTrace.recordViewport("one", viewport())
            assertEquals(1L, current.viewport()!!.draw)
            old.close()
            TerminalPipelineTrace.recordViewport("one", viewport(2))
            assertEquals(2L, current.viewport()!!.draw)
        }
    }

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
