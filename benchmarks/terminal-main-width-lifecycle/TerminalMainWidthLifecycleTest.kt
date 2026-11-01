package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Candidate-only scalar oracle. Android shaping/layout is validated separately on the real fixture. */
class TerminalMainWidthLifecycleTest {
    private val proof = "packaged-font-proof-v1"
    private fun terminal(size: Int = 129) = TerminalEmulator(80, 6, size).apply {
        feed("\u001b[?25l" + (0 until size + 6).joinToString("\r\n") {
            if (it == 0) "W".repeat(70) else "row-$it 中文 e\u0301 👩‍💻"
        } + "\r")
    }
    private fun measure(text: AnnotatedString): Int =
        text.length * 7 + text.spanStyles.sumOf { it.end - it.start + it.item.hashCode().and(7) }
    private fun check(index: TerminalTranscriptWidthIndex, frame: TerminalEmulator.RenderFrame,
        metric: Int = 1, font: Any? = proof): Int {
        val width = index.width(frame, metric, font) { measure(it) * metric }
        assertEquals(frame.rows.maxOfOrNull { measure(it.text) * metric } ?: 0, width)
        assertTrue(index.readyFor(frame, metric))
        assertTrue(index.retainedCandidates <= TerminalEmulator.MAX_SCROLLBACK_LINES)
        assertTrue(index.retainedScreenRows <= TerminalEmulator.MAX_ROWS)
        return width
    }
    private fun alt(terminal: TerminalEmulator): TerminalEmulator.RenderFrame {
        terminal.feed("\u001b[?1049h\u001b[?25l\u001b[2J\u001b[Halt\r")
        return terminal.renderFrame()
    }
    private fun main(terminal: TerminalEmulator): TerminalEmulator.RenderFrame {
        terminal.feed("\u001b[?1049l")
        return terminal.renderFrame()
    }

    @Test fun unchangedHistoryRoundTripsAtOneFiveAndTenThousandNeedNoHistoryMeasurements() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = terminal(size); val index = TerminalTranscriptWidthIndex()
            val first = terminal.renderFrame(); check(index, first)
            repeat(4) {
                check(index, alt(terminal)); assertEquals(0, index.lastMeasuredHistoryRows)
                assertEquals(0, index.retainedScreenRows)
                val returned = main(terminal)
                assertSame(first.ownedRows()!!.history, returned.ownedRows()!!.history)
                assertEquals(0, index.requiredHistoryMeasurements(returned, 1, proof))
                check(index, returned); assertEquals(0, index.lastMeasuredHistoryRows)
            }
            assertEquals(size.toLong(), index.measuredHistoryRows)
            println("MAIN_WIDTH_SCALAR size=$size roundTrips=4 historyMeasurements=${index.measuredHistoryRows}")
        }
    }

    @Test fun alternateWidthNeverIncludesTheHiddenMainMaximumOrRetainsAlternateText() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        val wide = check(index, terminal.renderFrame()); val narrow = check(index, alt(terminal))
        assertTrue(narrow < wide); assertEquals(0, index.retainedScreenRows)
        assertNotNull(index.checkpoint(proof, 1))
        repeat(5) {
            terminal.feed("\r\u001b[2Kchanged-$it\r"); check(index, terminal.renderFrame())
            assertEquals(6, index.lastMeasuredScreenRows); assertEquals(0, index.lastReusedScreenRows)
        }
        assertEquals(wide, check(index, main(terminal)))
    }

    @Test fun ordinaryRefreshStillReusesHistoryAndAllUnchangedMainScreenWidths() {
        val terminal = terminal(1000); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame())
        repeat(50) {
            terminal.feed("\r\u001b[2Kactive-$it\r"); check(index, terminal.renderFrame())
            assertEquals(0, index.lastMeasuredHistoryRows); assertEquals(1, index.lastMeasuredScreenRows)
            assertEquals(5, index.lastReusedScreenRows)
        }
        assertEquals(1000L, index.measuredHistoryRows)
    }

    @Test fun unmeasuredMainSuffixBeforeAltIsNotMistakenForMeasuredEnd() {
        for (added in listOf(1, 128, 200)) {
            val terminal = terminal(129); val index = TerminalTranscriptWidthIndex()
            check(index, terminal.renderFrame())
            repeat(added) { terminal.feed("\r\nnew-$it\r") } // Deliberately no MAIN publication.
            check(index, alt(terminal)); val returned = main(terminal)
            assertEquals(minOf(added, 129), index.requiredHistoryMeasurements(returned, 1, proof))
            check(index, returned); assertEquals(minOf(added, 129), index.lastMeasuredHistoryRows)
        }
    }

    @Test fun trimWhileAlternateRemovesRetiredMaximumOnReturnWithoutMeasuringSurvivors() {
        val terminal = terminal(260); val index = TerminalTranscriptWidthIndex()
        val before = check(index, terminal.renderFrame()); check(index, alt(terminal))
        terminal.setMaxScrollbackLines(128); check(index, terminal.renderFrame())
        val returned = main(terminal); assertEquals(128, returned.historyCount)
        assertTrue(check(index, returned) < before); assertEquals(0, index.lastMeasuredHistoryRows)
        assertTrue(index.retainedCandidates <= 128)
    }

    @Test fun previousMainFrameCannotReuseAfterPruningRetiredRows() {
        val terminal = terminal(260); val first = terminal.renderFrame(); val index = TerminalTranscriptWidthIndex()
        check(index, first); check(index, alt(terminal)); terminal.setMaxScrollbackLines(128)
        check(index, main(terminal)); check(index, first)
        assertEquals(260, index.lastMeasuredHistoryRows)
    }

    @Test fun changedMetricWhileAlternateRevokesHistoryEvenIfTheExternalProofLooksEqual() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); check(index, alt(terminal), 2)
        assertNull(index.checkpoint(proof, 2)); check(index, main(terminal), 2)
        assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun changedProofWhileAlternateRevokesHistoryEvenIfMetricsLookEqual() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); check(index, alt(terminal), font = "font-v2")
        assertNull(index.checkpoint(proof)); check(index, main(terminal), font = "font-v2")
        assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun changedProofOnMainMakesRequiredWorkAndMeasurementAgree() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); val frame = terminal.renderFrame()
        check(index, frame); assertEquals(129, index.requiredHistoryMeasurements(frame, 1, "new-proof"))
        check(index, frame, font = "new-proof"); assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun missingProofTakesLegacyAlternateInvalidationPath() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); check(index, alt(terminal), font = null)
        assertEquals(0, index.retainedCandidates); check(index, main(terminal))
        assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun nonFullLegacyApiDoesNotOptInAndDoesNotRetainAlternateHistory() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        index.width(terminal.renderFrame(), 1, ::measure)
        index.width(alt(terminal), 1, ::measure)
        assertEquals(0, index.retainedCandidates); assertNull(index.checkpoint(proof))
        index.width(main(terminal), 1, ::measure); assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun alternateStyleAndColumnInvalidationForceFullMainRemeasurement() {
        val edits: List<(TerminalEmulator) -> Unit> = listOf(
            { it.resize(40, 6) }, { it.feed("\u001b[?5h") },
            { it.feed("\u001b]4;2;rgb:ffff/0000/0000\u0007") })
        for (edit in edits) {
            val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
            check(index, terminal.renderFrame()); check(index, alt(terminal)); edit(terminal)
            check(index, terminal.renderFrame()); assertNull(index.checkpoint(proof))
            val returned = main(terminal); check(index, returned)
            assertEquals(returned.historyCount, index.lastMeasuredHistoryRows)
        }
    }

    @Test fun clearHistoryWhileAlternateDoesNotResurrectOldMaximum() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); check(index, alt(terminal))
        terminal.clearScrollbackOnly(); check(index, terminal.renderFrame())
        assertNull(index.checkpoint(proof)); val returned = main(terminal)
        assertEquals(0, returned.historyCount); check(index, returned); assertEquals(0, index.retainedCandidates)
    }

    @Test fun replacementOwnerAndForgedOwnedMetadataRevokeHiddenHistory() {
        val terminal = terminal(); val first = terminal.renderFrame(); val realAlt = alt(terminal)
        val replacements = listOf(realAlt.copy(rows = realAlt.rows.toList()),
            realAlt.copy(screenLineIds = realAlt.screenLineIds.toList()),
            realAlt.copy(historyGeneration = realAlt.historyGeneration + 1),
            realAlt.copy(screenGeneration = realAlt.screenGeneration + 1), alt(this.terminal()))
        for (frame in replacements) {
            val index = TerminalTranscriptWidthIndex(); check(index, first); check(index, frame)
            assertEquals(0, index.retainedCandidates); assertNull(index.checkpoint(proof))
        }
    }

    @Test fun mainWithoutScrollbackCannotMasqueradeAsAlternateViaRenderFrameCopy() {
        val terminal = terminal(); val frame = terminal.renderFrame(); val elided = terminal.renderFrame(false)
        assertNotNull(elided.ownedRows())
        for (view in listOf(elided, elided.copy(isAlternateScreen = true))) {
            val index = TerminalTranscriptWidthIndex(); check(index, frame); check(index, view)
            assertEquals(0, index.retainedCandidates); assertNull(index.checkpoint(proof))
            check(index, frame); assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        }
    }

    @Test fun ownedAlternateProvenanceIsCapturedByTheEmulatorAndNeverInferredFromEmptyHistory() {
        val terminal = terminal(); assertFalse(terminal.renderFrame(false).ownedRows()!!.alternateScreen)
        assertTrue(alt(terminal).ownedRows()!!.alternateScreen)
        assertFalse(main(terminal).ownedRows()!!.alternateScreen)
    }

    @Test fun alternateOnlyAndEmptyMainNeverCreateAHistoryCheckpoint() {
        val terminal = TerminalEmulator(80, 6); val index = TerminalTranscriptWidthIndex()
        check(index, alt(terminal)); assertNull(index.checkpoint(proof)); check(index, main(terminal))
        assertNull(index.checkpoint(proof)); assertEquals(0, index.retainedCandidates)
    }

    @Test fun failedAlternateScreenMeasurementDoesNotPublishReadyOrReusableHistory() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); val frame = alt(terminal); var calls = 0
        assertThrows(IllegalStateException::class.java) {
            index.width(frame, 1, proof) { if (++calls == 2) error("ALT font failure") else measure(it) }
        }
        assertFalse(index.readyFor(frame, 1)); assertNull(index.checkpoint(proof))
        check(index, main(terminal)); assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun failedSuffixAndNegativeWidthsNeverPublishAPartialHistory() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        check(index, terminal.renderFrame()); repeat(3) { terminal.feed("\r\nappend-$it\r") }
        val frame = terminal.renderFrame(); var calls = 0
        assertThrows(IllegalArgumentException::class.java) {
            index.width(frame, 1, proof) { if (++calls == 2) -1 else measure(it) }
        }
        assertFalse(index.readyFor(frame, 1)); assertNull(index.checkpoint(proof))
        check(index, frame); assertEquals(129, index.lastMeasuredHistoryRows)
    }

    @Test fun alternateCheckpointRemountPreservesOnlyScalarsAndStillRequiresCurrentFrameReadiness() {
        val terminal = terminal(); val old = TerminalTranscriptWidthIndex()
        check(old, terminal.renderFrame()); val alternate = alt(terminal); check(old, alternate)
        val checkpoint = requireNotNull(old.checkpoint(proof)); old.clear()
        assertFalse(old.hasRetainedState); assertEquals(0, old.retainedScreenRows)
        val fresh = TerminalTranscriptWidthIndex(); assertTrue(fresh.restore(checkpoint, proof, 1))
        assertFalse(fresh.readyFor(alternate, 1)); check(fresh, alternate)
        assertEquals(0, fresh.retainedScreenRows); check(fresh, main(terminal))
        assertEquals(0, fresh.lastMeasuredHistoryRows); assertNull(fresh.checkpoint("wrong-proof"))
    }

    @Test fun remountCheckpointRejectsWrongFontAndMeasuresNewUnpublishedSuffix() {
        val terminal = terminal(); val old = TerminalTranscriptWidthIndex(); check(old, terminal.renderFrame())
        val checkpoint = requireNotNull(old.checkpoint(proof)); old.clear()
        repeat(12) { terminal.feed("\r\nnew-$it\r") }
        val next = terminal.renderFrame(); val fresh = TerminalTranscriptWidthIndex()
        assertFalse(fresh.restore(checkpoint, "different", 1)); assertTrue(fresh.restore(checkpoint, proof, 1))
        check(fresh, next); assertEquals(12, fresh.lastMeasuredHistoryRows)
    }

    @Test fun staleLeaseAndSessionCloseCannotOverwriteCurrentCheckpoint() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); check(index, terminal.renderFrame())
        check(index, alt(terminal)); val checkpoint = requireNotNull(index.checkpoint(proof))
        val store = TerminalWidthCheckpointStore(); val old = requireNotNull(store.open()); store.save(old, checkpoint)
        val current = requireNotNull(store.open()); store.save(old, null); assertSame(checkpoint, store.read(current))
        store.close(); store.save(current, checkpoint); assertEquals(0, store.retainedRows()); assertNull(store.open())
    }

    @Test fun legacyFallbackClearsAlternateOwnershipButPrunesMainWithoutGuessingMissingEnd() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); check(index, terminal.renderFrame())
        val frame = alt(terminal); check(index, frame); index.retainFor(frame, 1)
        assertFalse(index.hasRetainedState); check(index, main(terminal))
        repeat(3) { terminal.feed("\r\nmore-$it\r") }; val next = terminal.renderFrame()
        index.retainFor(next, 1); assertFalse(index.readyFor(next, 1)); assertEquals(0, index.retainedScreenRows)
        check(index, next); assertEquals(3, index.lastMeasuredHistoryRows)
    }

    @Test fun successfulWorkerTransfersBothHistoryAndFrameProofButCannotCommitAfterClear() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); val first = terminal.renderFrame()
        val worker = index.fork(); val epoch = index.epoch; check(worker, first)
        assertFalse(index.readyFor(first, 1)); assertTrue(index.acceptComputed(worker, epoch, first))
        check(index, alt(terminal)); check(index, main(terminal)); assertEquals(0, index.lastMeasuredHistoryRows)
        val old = index.epoch; val late = index.fork(); check(late, first); index.clear()
        assertFalse(index.acceptComputed(late, old, first)); assertFalse(index.hasRetainedState)
    }

    @Test fun simultaneousColdWorkerAndAlternatePublicationCannotCommitStaleMain() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); val first = terminal.renderFrame()
        val worker = index.fork(); val epoch = index.epoch
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Int> {
                var waiting = true
                worker.width(first, 1, proof) {
                    if (waiting) { waiting = false; entered.countDown(); kotlin.check(release.await(20, TimeUnit.SECONDS)) }
                    measure(it)
                }
            }
            assertTrue(entered.await(20, TimeUnit.SECONDS)); val alternate = alt(terminal); check(index, alternate)
            release.countDown(); assertEquals(first.rows.maxOf { measure(it.text) }, result.get(20, TimeUnit.SECONDS))
            assertFalse(index.acceptComputed(worker, epoch, first)); assertTrue(index.readyFor(alternate, 1))
            check(index, main(terminal)); assertEquals(129, index.lastMeasuredHistoryRows)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun canceledWorkerAndWrongCompletedFrameCannotInventReadiness() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); val first = terminal.renderFrame()
        val worker = index.fork()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            worker.width(first, 1, proof) { throw java.util.concurrent.CancellationException("request cancelled") }
        }
        assertThrows(IllegalArgumentException::class.java) { index.acceptComputed(worker, index.epoch, first) }
        assertFalse(index.hasRetainedState); check(worker, first)
        assertThrows(IllegalArgumentException::class.java) { index.acceptComputed(worker, index.epoch, first.copy()) }
        assertFalse(index.hasRetainedState)
    }

    @Test fun sameRevisionNewFrameMustAcquireItsOwnReadyIdentity() {
        val terminal = terminal(); val index = TerminalTranscriptWidthIndex(); val first = terminal.renderFrame()
        check(index, first); val next = terminal.renderFrame(); assertEquals(first.revision, next.revision)
        assertNotSame(first, next); assertFalse(index.readyFor(next, 1)); check(index, next)
        assertEquals(0, index.lastMeasuredHistoryRows); assertEquals(0, index.lastMeasuredScreenRows)
        assertFalse(index.readyFor(first, 1)); assertEquals(0, index.requiredHistoryMeasurements(next, 1, proof))
    }

    @Test fun scalarRetentionIsBoundedForWorstCaseMonotonicWidthsAndDisposeReleasesIt() {
        val terminal = terminal(10000); val frame = terminal.renderFrame(); val index = TerminalTranscriptWidthIndex()
        var ordinal = 0
        index.width(frame, 1, proof) { 20000 - ordinal++ }
        assertEquals(10000, index.retainedCandidates) // Strictly decreasing: no maximum can be elided.
        val alternate = alt(terminal); check(index, alternate)
        assertEquals(10000, index.retainedCandidates); assertEquals(0, index.retainedScreenRows)
        index.clear(); assertEquals(0, index.retainedCandidates); assertFalse(index.hasRetainedState)
        assertNull(index.checkpoint(proof)); assertFalse(index.readyFor(alternate, 1))
    }

    @Test fun randomizedFifoAltProofAndMetricTransitionsMatchFullOracle() {
        val random = Random(3062026); val terminal = terminal(); val index = TerminalTranscriptWidthIndex()
        var metric = 1; var currentProof: Any? = proof
        repeat(360) { step ->
            when (random.nextInt(10)) {
                0 -> { if (terminal.renderFrame().isAlternateScreen) main(terminal) else alt(terminal) }
                1 -> terminal.feed("\r\nnew-$step\r")
                2 -> terminal.feed("\r\u001b[2K\u001b[1mchanged-$step 中文\u001b[0m\r")
                3 -> metric = metric % 3 + 1
                4 -> currentProof = listOf(null, proof, "font-v2")[random.nextInt(3)]
                5 -> terminal.setMaxScrollbackLines(listOf(100, 128, 260)[random.nextInt(3)])
                6 -> terminal.resize(listOf(40, 80)[random.nextInt(2)], 6)
                7 -> { if (step % 11 == 0) terminal.clearScrollbackOnly() }
                8 -> { if (step % 13 == 0) index.clear() }
                else -> terminal.feed("\u001b[2;1H\u001b[L")
            }
            val frame = terminal.renderFrame(); check(index, frame, metric, currentProof)
            assertEquals(0, index.requiredHistoryMeasurements(frame, metric, currentProof))
        }
        println("MAIN_WIDTH_RANDOM steps=360 seed=3062026 passed=true")
    }
}
