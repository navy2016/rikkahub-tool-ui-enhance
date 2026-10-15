package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TerminalScreenCacheTest {
    @Test fun unchangedScreenAndSingleLineUpdatesBuildOnlyChangedRows() {
        val terminal = TerminalEmulator(80, 24).apply { feed("\u001b[?25lhello\r") }
        val first = terminal.renderFrame()
        assertEquals(24, terminal.lastRenderScreenBuilds)
        val same = terminal.renderFrame()
        assertEquals(0, terminal.lastRenderScreenBuilds)
        assertEquals(24, terminal.lastRenderScreenReuses)
        first.rows.indices.forEach { assertSame(first.rows[it], same.rows[it]) }
        repeat(32) {
            terminal.feed("\r\u001b[2Kvalue-$it")
            val next = terminal.renderFrame()
            assertEquals(1, terminal.lastRenderScreenBuilds)
            assertEquals(next, terminal.renderFrameUncached())
        }
    }

    @Test fun hiddenCursorReservationAndVisibleCursorMovementInvalidateBothRows() {
        val terminal = TerminalEmulator(80, 24)
        terminal.renderFrame()
        val changes = listOf(
            "\u001b[?25l\u001b[2;40H" to 2,
            "\u001b[?25h\u001b[3;10H" to 2,
            "\u001b[4 q" to 1,
            // The production OSC parser accepts rgb:r/g/b, not CSS hex color syntax.
            "\u001b]12;rgb:ff/ff/ff\u0007" to 1,
            "\u001b]12;#ffffff\u0007" to 0,
        )
        for ((sequence, expectedBuilds) in changes) {
            terminal.feed(sequence)
            val frame = terminal.renderFrame()
            assertEquals("Screen invalidation count", expectedBuilds, terminal.lastRenderScreenBuilds)
            assertEquals(frame, terminal.renderFrameUncached())
        }
    }

    @Test fun globalStyleRevisionsCannotCollideAfterHistoryClear() {
        val terminal = TerminalEmulator(80, 6, 30).apply { feed((0..40).joinToString("\r\n") { "x-$it" }) }
        terminal.feed("\u001b[?5h")
        terminal.renderFrame()
        terminal.clearScrollbackOnly()
        terminal.feed("\u001b[?5l")
        val frame = terminal.renderFrame()
        assertEquals(6, terminal.lastRenderScreenBuilds)
        assertEquals(frame, terminal.renderFrameUncached())
    }

    @Test fun protocolMutationSequencesMatchTheFullScreenOracleAtEveryStep() {
        val terminal = TerminalEmulator(80, 24, 128)
        val random = Random(7654321)
        val mutations = listOf("abc", "中文", "e\u0301", "👨‍👩‍👧", "\r", "\n", "\u001b[2K",
            "\u001b[1@", "\u001b[2P", "\u001b[3X", "\u001b[2L", "\u001b[2M",
            "\u001b[1;2;8;30;1\$r", "\u001b[1;2;8;30;7\$t", "\u001b[65;1;2;8;30\$x",
            "\u001b[1;2;8;30\$z", "\u001b[1;1;3;10;1;5;4;1\$v", "\u001b[?25l", "\u001b[?25h",
            "\u001b[?1049h", "\u001b[?1049l", "\u001b[?5h", "\u001b[?5l", "\u001b[31;1m", "\u001b[0m",
            "\u001b]8;;https://example.test\u0007link\u001b]8;;\u0007", "\u001b[3J", "\u001b[2J")
        repeat(500) { step ->
            terminal.feed("\u001b[${random.nextInt(1, terminal.rows + 1)};${random.nextInt(1, terminal.columns + 1)}H")
            terminal.feed(mutations[random.nextInt(mutations.size)])
            if (step % 37 == 0) terminal.resize(if (step % 2 == 0) 100 else 80, if (step % 3 == 0) 12 else 24)
            if (step % 101 == 0) terminal.softReset()
            if (step % 157 == 0) terminal.reset()
            val cached = terminal.renderFrame()
            assertEquals("step=$step", cached, terminal.renderFrameUncached())
        }
    }
}
