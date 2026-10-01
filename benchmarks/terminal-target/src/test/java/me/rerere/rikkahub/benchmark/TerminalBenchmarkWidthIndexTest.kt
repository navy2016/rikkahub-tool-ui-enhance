package me.rerere.rikkahub.benchmark

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scalar-index proofs with a deterministic measurement oracle. Android tests verify actual fonts. */
class TerminalBenchmarkWidthIndexTest {
    private fun seeded(size: Int = 100, wideHead: Boolean = false) = TerminalEmulator(
        initialColumns = 80, initialRows = 6, maxScrollbackLines = size,
    ).apply {
        feed("\u001B[?25l" + (0 until size + 6).joinToString("\r\n") {
            if (wideHead && it == 0) "W".repeat(70) else "row:$it"
        })
    }

    private fun scalar(text: AnnotatedString): Int = text.length * 7 + text.spanStyles.size

    private fun expected(frame: TerminalEmulator.RenderFrame): Int = frame.rows.maxOf { scalar(it.text) }

    @Test
    fun activeScreenUpdatesMeasureNoHistoryAtAllBenchmarkSizes() {
        for (size in listOf(1_000, 5_000, 10_000)) {
            val terminal = seeded(size)
            val index = TerminalBenchmarkWidthIndex()
            val first = terminal.renderFrame()
            assertEquals(expected(first), index.width(first, "font-14", ::scalar))
            assertEquals(size, index.lastMeasuredHistoryRows)
            repeat(5) { update ->
                terminal.feed("\r\u001B[2Kactive $update" + "x".repeat(update))
                val next = terminal.renderFrame()
                assertEquals(expected(next), index.width(next, "font-14", ::scalar))
                assertEquals(0, index.lastMeasuredHistoryRows)
                assertEquals(6, index.lastMeasuredScreenRows)
            }
        }
    }

    @Test
    fun fifoAppendsMeasureOneNewHistoryRowAndExpireTheWidestTrimmedRow() {
        val terminal = seeded(wideHead = true)
        val index = TerminalBenchmarkWidthIndex()
        val initial = index.width(terminal.renderFrame(), 1, ::scalar)
        repeat(30) { update ->
            terminal.feed("\r\nnext $update")
            val frame = terminal.renderFrame()
            val width = index.width(frame, 1, ::scalar)
            assertEquals(expected(frame), width)
            assertTrue(width < initial)
            assertEquals(1, index.lastMeasuredHistoryRows)
            assertEquals(6, index.lastMeasuredScreenRows)
            assertTrue(index.retainedCandidates <= frame.historyCount)
        }
        terminal.setMaxScrollbackLines(30)
        val limited = terminal.renderFrame()
        assertEquals(expected(limited), index.width(limited, 1, ::scalar))
        assertEquals(0, index.lastMeasuredHistoryRows)
        assertTrue(index.retainedCandidates <= 30)
    }

    @Test
    fun burstLargerThanTheRetainedRangeAndNonMonotonicIdsMatchFullMeasurement() {
        val terminal = seeded()
        val index = TerminalBenchmarkWidthIndex()
        index.width(terminal.renderFrame(), 1, ::scalar)
        terminal.feed((0 until 300).joinToString("\r\n", prefix = "\r\n") { "burst $it" })
        var frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        terminal.feed("\u001B[H\u001BM\u001B[6;1H\r\nreverse\r\nindex")
        frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(2, index.lastMeasuredHistoryRows)
    }

    @Test
    fun decreasingWidthsBoundTheCandidateDequeByTheRetainedHistory() {
        val terminal = seeded()
        val index = TerminalBenchmarkWidthIndex()
        fun width(text: AnnotatedString) = 1_000 - text.text.substringAfter(':').trim().toInt()
        var frame = terminal.renderFrame()
        assertEquals(frame.rows.maxOf { width(it.text) }, index.width(frame, 1, ::width))
        assertEquals(100, index.retainedCandidates)
        repeat(300) { update ->
            terminal.feed("\r\nrow:${106 + update}")
            frame = terminal.renderFrame()
            assertEquals(frame.rows.maxOf { width(it.text) }, index.width(frame, 1, ::width))
            assertEquals(100, index.retainedCandidates)
            assertEquals(1, index.lastMeasuredHistoryRows)
        }
    }

    @Test
    fun metricStyleColumnAndOwnerChangesInvalidateTheFullHistory() {
        val terminal = seeded()
        val index = TerminalBenchmarkWidthIndex()
        var metric = "density1-font14-ltr-typeface1"
        var frame = terminal.renderFrame()
        index.width(frame, metric, ::scalar)
        for (nextMetric in listOf("density2", "font21", "rtl", "typeface2")) {
            metric = nextMetric
            assertEquals(expected(frame), index.width(frame, metric, ::scalar))
            assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
            index.width(frame, metric, ::scalar)
            assertEquals(0, index.lastMeasuredHistoryRows)
        }
        val changes: List<(TerminalEmulator) -> Unit> = listOf(
            { it.feed("\u001B[?5h") }, { it.feed("\u001B]10;rgb:ffff/0000/0000\u0007") },
            { it.feed("\u001B]4;2;rgb:0000/ffff/0000\u0007") }, { it.resize(columns = 40, rows = 6) },
        )
        for (change in changes) {
            change(terminal)
            frame = terminal.renderFrame()
            assertEquals(expected(frame), index.width(frame, metric, ::scalar))
            assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        }
        val another = seeded().renderFrame()
        assertEquals(expected(another), index.width(another, metric, ::scalar))
        assertEquals(another.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test
    fun syntheticAndOutOfOrderFramesNeverReusePlausibleCounters() {
        val terminal = seeded(wideHead = true)
        val before = terminal.renderFrame()
        val index = TerminalBenchmarkWidthIndex()
        index.width(before, 1, ::scalar)
        terminal.feed("\r\nnext")
        val after = terminal.renderFrame()
        assertEquals(expected(after), index.width(after, 1, ::scalar))
        assertEquals(expected(before), index.width(before, 1, ::scalar))
        assertEquals(before.historyCount, index.lastMeasuredHistoryRows)
        val synthetic = before.copy(rows = before.rows.map { TerminalEmulator.RenderedRow(AnnotatedString("changed")) })
        repeat(2) {
            assertEquals(expected(synthetic), index.width(synthetic, 1, ::scalar))
            assertEquals(synthetic.historyCount, index.lastMeasuredHistoryRows)
        }
        assertEquals(expected(after), index.width(after, 1, ::scalar))
        assertEquals(after.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test
    fun hiddenHistoryClearResetAndScreenOnlyResizeUseTheRightWidths() {
        val terminal = seeded(wideHead = true)
        val index = TerminalBenchmarkWidthIndex()
        val original = terminal.renderFrame()
        val wide = index.width(original, 1, ::scalar)
        terminal.resize(columns = 80, rows = 8)
        var frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(0, index.lastMeasuredHistoryRows)
        assertEquals(8, index.lastMeasuredScreenRows)
        frame = terminal.renderFrame(includeScrollback = false)
        assertTrue(index.width(frame, 1, ::scalar) < wide)
        assertEquals(0, index.retainedCandidates)
        frame = terminal.renderFrame()
        assertEquals(wide, index.width(frame, 1, ::scalar))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        terminal.feed("\u001B[?1049hALT")
        frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(0, index.retainedCandidates)
        terminal.feed("\u001B[?1049l")
        terminal.clearScrollbackOnly()
        frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(0, index.retainedCandidates)
        terminal.reset()
        frame = terminal.renderFrame()
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
    }

    @Test
    fun throwingMeasurementRevokesPartiallyUpdatedCache() {
        val terminal = seeded()
        val index = TerminalBenchmarkWidthIndex()
        index.width(terminal.renderFrame(), 1, ::scalar)
        terminal.feed("\r\nnext")
        val frame = terminal.renderFrame()
        var measured = 0
        assertThrows(IllegalStateException::class.java) {
            index.width(frame, 1) { text ->
                check(++measured < 3) { "Measurement failed after updating the deque" }
                scalar(text)
            }
        }
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        assertThrows(IllegalArgumentException::class.java) { index.width(frame, 2) { -1 } }
        assertEquals(expected(frame), index.width(frame, 1, ::scalar))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
    }
}
