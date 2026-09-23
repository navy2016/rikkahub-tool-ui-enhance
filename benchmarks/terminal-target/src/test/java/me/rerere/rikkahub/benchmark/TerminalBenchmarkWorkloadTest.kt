package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.data.container.buildLineIdsFromFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBenchmarkWorkloadTest {
    @Test
    fun fixturesContainExactlyTheRequestedHistoryWithoutAccidentalWrapping() {
        for (size in TerminalBenchmarkWorkload.historySizes) {
            val terminal = TerminalBenchmarkWorkload.prepare(size)
            val frame = terminal.renderFrame()
            assertEquals(size, frame.historyCount)
            assertEquals(size + TerminalBenchmarkWorkload.SCREEN_ROWS, frame.rows.size)
            assertEquals(frame.rows.size, buildLineIdsFromFrame(frame, frame.rows.size).toSet().size)
            assertTrue(frame.rows[2].text.text.contains("中文输出"))
            assertFalse(frame.isAlternateScreen)

            terminal.feed("\r\n" + TerminalBenchmarkWorkload.line(size + TerminalBenchmarkWorkload.SCREEN_ROWS))
            val trimmed = terminal.renderFrame()
            assertEquals(size, trimmed.historyCount)
            assertEquals(frame.historyLineIds.drop(1), trimmed.historyLineIds.dropLast(1))

            TerminalBenchmarkWorkload.enterAlternateScreen(terminal)
            val alternate = terminal.renderFrame()
            assertEquals(0, alternate.historyCount)
            assertEquals(TerminalBenchmarkWorkload.SCREEN_ROWS, alternate.rows.size)
            assertTrue(alternate.isAlternateScreen)
        }
    }
}
