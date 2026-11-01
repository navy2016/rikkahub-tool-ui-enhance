package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.snapshots.Snapshot
import me.rerere.rikkahub.ui.pages.container.TerminalPhysicalMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalPhysicalRowMeasurement
import me.rerere.rikkahub.ui.pages.container.TerminalRowStore
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import kotlin.random.Random

/** Scalar routing/lifetime oracle only. Real font/layout equivalence is tested on Android. */
class TerminalPhysicalMetricsTest {
    private fun terminal(history: Int = 100) = TerminalEmulator(80, 6, history).apply {
        feed("\u001b[?25l" + (0 until history + 6).joinToString("\r\n") {
            if (it == 0) "W".repeat(79) else "row-$it 中文 e\u0301"
        })
    }
    private fun measured(text: AnnotatedString) = text.length * 7 + text.spanStyles.size * 2
    private fun oracle(frame: TerminalEmulator.RenderFrame) = frame.rows.maxOf { measured(it.text) }
    private fun product(frame: TerminalEmulator.RenderFrame, metric: Any = "metric", sharing: Boolean = true,
        text: (Int) -> AnnotatedString = { frame.rows[frame.historyCount + it].text },
        width: (Int, AnnotatedString) -> Int? = { _, row -> measured(row) }): TerminalPhysicalMeasurements {
        val rows = frame.screenLineIds.indices.associate { index ->
            val row = text(index)
            frame.screenLineIds[index] to TerminalPhysicalRowMeasurement(row, 17, width(index, row))
        }
        return TerminalPhysicalMeasurements(frame, metric, TerminalPhysicalRowHeights(IntArray(rows.size) { 17 }),
            Collections.unmodifiableMap(rows), sharing)
    }

    @Test fun originalApiStillMeasuresEveryColdScreenRow() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        assertEquals(oracle(frame), index.width(frame, "metric", ::measured))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        assertEquals(6, index.lastMeasuredScreenRows); assertEquals(0, index.lastSharedScreenRows)
    }
    @Test fun currentProductsShareOnlyScreenAndKeepFullHistoryOracleAtAllSizes() {
        for (size in listOf(1000, 5000, 10000)) {
            val frame = terminal(size).renderFrame(); val index = TerminalTranscriptWidthIndex(); var fallback = 0
            assertEquals(oracle(frame), index.width(frame, "metric", product(frame)) { fallback++; measured(it) })
            assertEquals(size, fallback); assertEquals(size, index.lastMeasuredHistoryRows)
            assertEquals(0, index.lastMeasuredScreenRows); assertEquals(6, index.lastSharedScreenRows)
            assertEquals(6, product(frame).retainedRows); assertTrue(index.readyFor(frame, "metric"))
        }
    }
    @Test fun unchangedMainRowsUseExistingCacheWithoutCapabilityLookups() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        index.width(frame, "metric", product(frame), ::measured)
        assertEquals(oracle(frame), index.width(frame, "metric", TerminalScreenWidthSource { _, _, _, _ ->
            error("A cached row must not consult a second source")
        }, ::measured))
        assertEquals(6, index.lastReusedScreenRows); assertEquals(0, index.lastSharedScreenRows)
    }
    @Test fun changedMainLineUsesNewProductWithoutRemeasuringHistory() {
        val model = terminal(); val index = TerminalTranscriptWidthIndex(); val first = model.renderFrame()
        index.width(first, "metric", product(first), ::measured)
        model.feed("\r\u001b[2Knew line"); val next = model.renderFrame()
        assertEquals(oracle(next), index.width(next, "metric", product(next), ::measured))
        assertEquals(0, index.lastMeasuredHistoryRows); assertEquals(0, index.lastMeasuredScreenRows)
        assertEquals(1, index.lastSharedScreenRows); assertEquals(5, index.lastReusedScreenRows)
    }
    @Test fun alternateUsesCurrentScalarsButNeverAddsAnAlternateTextCache() {
        val model = terminal(); model.feed("\u001b[?1049hALT"); val frame = model.renderFrame()
        val index = TerminalTranscriptWidthIndex()
        repeat(3) {
            assertEquals(oracle(frame), index.width(frame, "metric", product(frame), ::measured))
            assertEquals(6, index.lastSharedScreenRows); assertEquals(0, index.retainedScreenRows)
            assertEquals(0, index.retainedCandidates)
        }
    }
    @Test fun mainAltReturnKeeps306HistoryInvalidationNotRejectedA1() {
        val model = terminal(1000); val index = TerminalTranscriptWidthIndex()
        fun update() { val f = model.renderFrame(); assertEquals(oracle(f), index.width(f, "metric", product(f), ::measured)) }
        update(); model.feed("\u001b[?1049hALT"); update(); assertEquals(0, index.retainedCandidates)
        model.feed("\u001b[?1049l"); update(); assertEquals(1000, index.lastMeasuredHistoryRows)
    }
    @Test fun nullCapabilityAndDeclinedRowsUseTheOriginalMeasurement() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        assertEquals(oracle(frame), index.width(frame, "metric", null, ::measured))
        assertEquals(6, index.lastMeasuredScreenRows)
        index.clear()
        assertEquals(oracle(frame), index.width(frame, "metric", product(frame, width = { i, t ->
            if (i % 2 == 0) null else measured(t)
        }), ::measured))
        assertEquals(3, index.lastSharedScreenRows); assertEquals(3, index.lastMeasuredScreenRows)
    }
    @Test fun disabledSharingStillKeepsExactPhysicalHeights() {
        val frame = terminal().renderFrame(); val p = product(frame, sharing = false)
        assertEquals(102, p.heights.height); assertEquals(6, p.retainedRows)
        frame.screenLineIds.indices.forEach { assertNull(p.width(frame, "metric", it, frame.rows[frame.historyCount + it].text)) }
    }
    @Test fun wrongMetricAndSameRevisionOtherFrameCannotUseTheProduct() {
        val model = terminal(); val frame = model.renderFrame(); val p = product(frame); val next = model.renderFrame()
        assertEquals(frame.revision, next.revision); assertNotSame(frame, next)
        assertNull(p.width(frame, "changed", 0, frame.rows[frame.historyCount].text))
        assertNull(p.width(next, "metric", 0, next.rows[next.historyCount].text))
    }
    @Test fun anotherOwnerCopiedRowsAndReplacedIdsCannotUseTheProduct() {
        val frame = terminal().renderFrame(); val p = product(frame)
        val replaced = listOf(terminal().renderFrame(), frame.copy(rows = frame.rows.toList()),
            frame.copy(screenLineIds = frame.screenLineIds.toList()))
        replaced.forEach { next ->
            assertNull(p.width(next, "metric", 0, next.rows[next.historyCount].text))
            if (next.rows !is me.rerere.rikkahub.utils.TerminalFrameRows || next !== replaced.first()) {
                val forged = product(next)
                assertNull(forged.width(next, "metric", 0, next.rows[next.historyCount].text))
            }
        }
    }
    @Test fun fullTextEqualityIncludesStyleNotJustCharacters() {
        val frame = terminal().renderFrame(); val p = product(frame); val text = frame.rows[frame.historyCount].text
        val changed = AnnotatedString(text.text, spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 0, text.length)))
        assertNull(p.width(frame, "metric", 0, changed))
        val equal = AnnotatedString.Builder().apply { append(text) }.toAnnotatedString()
        assertEquals(measured(text), p.width(frame, "metric", 0, equal))
    }
    @Test fun displayedBlankGraceTextCannotReplaceNewFrameText() {
        val frame = terminal().renderFrame()
        val oldText = AnnotatedString("previous chrome wider than newly captured row")
        val p = product(frame, text = { if (it == 5) oldText else frame.rows[frame.historyCount + it].text })
        val freshText = frame.rows[frame.historyCount + 5].text
        assertNull(p.width(frame, "metric", 5, freshText)); assertNull(p.width(frame, "metric", 5, oldText))
        val index = TerminalTranscriptWidthIndex()
        assertEquals(oracle(frame), index.width(frame, "metric", p, ::measured))
        assertEquals(5, index.lastSharedScreenRows); assertEquals(1, index.lastMeasuredScreenRows)
    }
    @Test fun invalidScreenIndicesAndOldProductAfterResizeAreRejected() {
        val model = terminal(); val frame = model.renderFrame(); val p = product(frame)
        assertNull(p.width(frame, "metric", -1, AnnotatedString("")))
        assertNull(p.width(frame, "metric", 6, AnnotatedString("")))
        model.resize(70, 8); val next = model.renderFrame()
        assertNull(p.width(next, "metric", 0, next.rows[next.historyCount].text))
        assertEquals(oracle(next), TerminalTranscriptWidthIndex().width(next, "metric", product(next), ::measured))
    }
    @Test fun fifoArchivedRowsAreMeasuredAsHistoryNotOldPhysicalProducts() {
        val model = terminal(); val index = TerminalTranscriptWidthIndex(); val first = model.renderFrame()
        index.width(first, "metric", product(first), ::measured)
        model.feed("\r\nappend"); val next = model.renderFrame()
        assertEquals(oracle(next), index.width(next, "metric", product(next), ::measured))
        assertEquals(1, index.lastMeasuredHistoryRows)
        assertEquals(0, index.lastMeasuredScreenRows); assertEquals(1, index.lastSharedScreenRows)
    }
    @Test fun shorteningAndTrimmingTheWidestRowUpdatesExactMaximum() {
        val model = terminal(); val index = TerminalTranscriptWidthIndex(); val first = model.renderFrame()
        val wide = index.width(first, "metric", product(first), ::measured)
        model.setMaxScrollbackLines(40); model.feed("\r\u001b[2Kx"); val next = model.renderFrame()
        val actual = index.width(next, "metric", product(next), ::measured)
        assertEquals(oracle(next), actual); assertTrue(actual < wide)
    }
    @Test fun negativeProductRevokesPartialReadinessAndRecovers() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        assertThrows(IllegalArgumentException::class.java) {
            index.width(frame, "metric", product(frame, width = { i, t -> if (i == 3) -1 else measured(t) }), ::measured)
        }
        assertFalse(index.readyFor(frame, "metric"))
        assertEquals(oracle(frame), index.width(frame, "metric", product(frame), ::measured))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
    }
    @Test fun throwingCapabilityCannotPublishAnIncompleteFrameOrScreenCache() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        assertThrows(IllegalStateException::class.java) {
            index.width(frame, "metric", TerminalScreenWidthSource { _, _, i, text ->
                if (i == 2) error("width product failure") else measured(text)
            }, ::measured)
        }
        assertFalse(index.readyFor(frame, "metric")); assertEquals(0, index.retainedScreenRows)
        assertEquals(oracle(frame), index.width(frame, "metric", ::measured))
    }
    @Test fun failedFallbackAfterSharedRowsCannotPublishReady() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        val p = product(frame, width = { i, t -> if (i == 5) null else measured(t) })
        val last = frame.rows.last().text
        assertThrows(IllegalStateException::class.java) {
            index.width(frame, "metric", p) { if (it === last) error("resolver failure") else measured(it) }
        }
        assertFalse(index.readyFor(frame, "metric")); assertEquals(0, index.retainedScreenRows)
    }
    @Test fun workerForkTransfersScalarCountersWithoutRetainingTheSource() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex(); val worker = index.fork()
        assertEquals(oracle(frame), worker.width(frame, "metric", product(frame), ::measured))
        assertFalse(index.readyFor(frame, "metric")); assertTrue(index.acceptComputed(worker, index.epoch, frame))
        assertEquals(6L, index.sharedScreenRows); assertEquals(6, index.lastSharedScreenRows)
        assertTrue(index.readyFor(frame, "metric"))
        assertFalse(index.javaClass.declaredFields.any { TerminalScreenWidthSource::class.java.isAssignableFrom(it.type) })
    }
    @Test fun clearedEpochRejectsLateComputedProductAndPreservesCountersContract() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex(); val epoch = index.epoch
        val worker = index.fork(); worker.width(frame, "metric", product(frame), ::measured)
        index.clear(); assertFalse(index.acceptComputed(worker, epoch, frame)); assertFalse(index.hasRetainedState)
        index.width(frame, "metric", product(frame), ::measured); index.clear()
        assertEquals(0, index.lastSharedScreenRows); assertEquals(6L, index.sharedScreenRows)
    }
    @Test fun originalFallbackPrunesAndStillRequiresUnmeasuredSuffix() {
        val model = terminal(); val first = model.renderFrame(); val index = TerminalTranscriptWidthIndex()
        index.width(first, "metric", product(first), ::measured)
        repeat(5) { model.feed("\r\nnew-$it") }; val next = model.renderFrame()
        index.retainFor(next, "metric"); assertFalse(index.readyFor(next, "metric"))
        assertEquals(oracle(next), index.width(next, "metric", product(next), ::measured))
        assertEquals(5, index.lastMeasuredHistoryRows)
    }
    @Test fun checkpointContainsOnlyHistoryAndNeverPhysicalProduct() {
        val frame = terminal().renderFrame(); val index = TerminalTranscriptWidthIndex()
        index.width(frame, "metric", product(frame), ::measured)
        val saved = index.checkpoint("font")!!; val restored = TerminalTranscriptWidthIndex()
        assertTrue(restored.restore(saved, "font", "metric")); assertEquals(0, restored.retainedScreenRows)
        assertEquals(oracle(frame), restored.width(frame, "metric", product(frame), ::measured))
        assertEquals(0, restored.lastMeasuredHistoryRows); assertEquals(6, restored.lastSharedScreenRows)
    }
    @Test fun physicalProductsDoNotRetainStrongFramesOrLayouts() {
        val frame = terminal().renderFrame(); val p = product(frame)
        assertEquals(6, p.retainedRows)
        val names = (p.javaClass.declaredFields + TerminalPhysicalRowMeasurement::class.java.declaredFields).map { it.type.name }
        for (name in names) {
            assertFalse(name.contains("RenderFrame")); assertFalse(name.contains("TextLayoutResult"))
            assertFalse(name.contains("Paragraph")); assertFalse(name.contains("TerminalRenderedRowState"))
        }
        assertTrue(names.any { it == "java.lang.ref.WeakReference" })
    }
    @Test fun discardedRowPublicationCannotMakeProductMatchAnotherFrame() {
        val model = terminal(); val frame = model.renderFrame(); val store = TerminalRowStore(frame, sparse = true)
        val before = store.view(frame); model.feed("\rnew"); val next = model.renderFrame(); val p = product(next)
        val snapshot = Snapshot.takeMutableSnapshot()
        try { snapshot.enter { store.publish(next, true, false, true, 1000) } } finally { snapshot.dispose() }
        assertSame(before, store.view(frame)); assertNull(p.width(frame, "metric", 0, frame.rows[frame.historyCount].text))
    }
    @Test fun randomizedFramesMetricsFifoAltAndResizeMatchFullOracle() {
        val random = Random(0xB100); val model = terminal(128); val index = TerminalTranscriptWidthIndex(); var metric = 0
        repeat(180) { step ->
            when (random.nextInt(7)) {
                0 -> model.feed("\r\u001b[2Knew-$step-中")
                1 -> model.feed("\r\nappend-$step")
                2 -> model.feed(if (step % 2 == 0) "\u001b[?1049hALT" else "\u001b[?1049l")
                3 -> model.resize(60 + random.nextInt(21), 6 + random.nextInt(3))
                4 -> metric++
                5 -> model.feed("\u001b[1;3mstyled-$step\u001b[0m")
                else -> model.feed("\u001b[2;4H\u001b[?25h")
            }
            val frame = model.renderFrame(); val p = product(frame, metric)
            assertEquals("step=$step", oracle(frame), index.width(frame, metric, p, ::measured))
            assertEquals(frame.screenLineIds.size, index.lastSharedScreenRows + index.lastReusedScreenRows + index.lastMeasuredScreenRows)
            assertTrue(index.retainedScreenRows <= 80); assertTrue(p.retainedRows <= 80)
        }
    }
}
