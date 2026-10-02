package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalTranscriptWidthIndexTest {
    private fun terminal(size: Int = 100) = TerminalEmulator(80, 6, size).apply {
        feed("\u001B[?25l" + (0 until size + 6).joinToString("\r\n") {
            if (it == 0) "W".repeat(70) else "row:$it"
        })
    }
    private fun measure(text: AnnotatedString): Int = text.length * 7 + text.spanStyles.size
    private fun expected(frame: TerminalEmulator.RenderFrame) = frame.rows.maxOf { measure(it.text) }

    @Test fun ownedActivityUpdatesDoNotMeasureHistoryAtOneFiveAndTenThousandRows() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = terminal(size)
            val index = TerminalTranscriptWidthIndex()
            val first = terminal.renderFrame()
            assertEquals(expected(first), index.width(first, "metrics", ::measure))
            assertEquals(size, index.lastMeasuredHistoryRows)
            terminal.feed("\r\u001B[2Kchanged")
            val next = terminal.renderFrame()
            assertEquals(expected(next), index.width(next, "metrics", ::measure))
            assertEquals(0, index.lastMeasuredHistoryRows)
            assertEquals(6, index.lastMeasuredScreenRows)
        }
    }

    @Test fun trimsRemoveOffscreenMaximumAndMeasureOnlyNewArchivedRow() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        val wide = index.width(terminal.renderFrame(), 1, ::measure)
        repeat(30) {
            terminal.feed("\r\nappend-$it")
            val frame = terminal.renderFrame()
            assertEquals(expected(frame), index.width(frame, 1, ::measure))
            assertTrue(index.width(frame, 1, ::measure) < wide)
            assertTrue(index.retainedCandidates <= frame.historyCount)
        }
        terminal.feed("\r\nlast")
        index.width(terminal.renderFrame(), 1, ::measure)
        assertEquals(1, index.lastMeasuredHistoryRows)
    }

    @Test fun metricsOwnerAndReplacedRowsRevokeFifoProof() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        index.width(frame, 1, ::measure)
        index.width(frame, 2, ::measure)
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        val synthetic = frame.copy(rows = frame.rows.map { it.copy(text = AnnotatedString("longer " + it.text)) })
        assertEquals(expected(synthetic), index.width(synthetic, 2, ::measure))
        assertEquals(synthetic.historyCount, index.lastMeasuredHistoryRows)
        val another = terminal().renderFrame()
        assertEquals(expected(another), index.width(another, 2, ::measure))
        assertEquals(another.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test fun failedMeasurementDoesNotLeavePartiallyValidMaximum() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        assertThrows(IllegalStateException::class.java) { index.width(frame, 1) { error("font failure") } }
        assertEquals(expected(frame), index.width(frame, 1, ::measure))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
    }
}
