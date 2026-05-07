package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun delayedWrapMatchesXtermBehavior() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("abcdefghijklmnopqrst")
        assertEquals("abcdefghijklmnopqrst", terminal.plainText(includeScrollback = false).lineSequence().first())
        terminal.feed("u")
        val lines = terminal.plainText(includeScrollback = false).lines()
        assertEquals("abcdefghijklmnopqrst", lines[0])
        assertEquals("u", lines[1])
    }

    @Test
    fun parsesColonTrueColorAndOscTitleWithoutPrintingControlText() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 4)
        terminal.feed("\u001B]2;tmux-title\u0007")
        terminal.feed("\u001B[38:2::255:0:0mred\u001B[0m")
        assertEquals("tmux-title", terminal.title)
        assertTrue(terminal.plainText(includeScrollback = false).contains("red"))
        assertTrue(!terminal.plainText(includeScrollback = false).contains("tmux-title"))
    }

    @Test
    fun resizePreservesVisibleCells() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("abc")
        terminal.resize(25, 8)
        assertTrue(terminal.plainText(includeScrollback = false).lines().first().startsWith("abc"))
    }

    @Test
    fun cjkAndCombiningCharactersUseExpectedCells() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("中a")
        assertTrue(terminal.plainText(includeScrollback = false).lines().first().startsWith("中a"))

        val combining = TerminalEmulator(initialColumns = 20, initialRows = 6)
        combining.feed("éx")
        assertTrue(combining.plainText(includeScrollback = false).lines().first().startsWith("éx"))

        val emoji = TerminalEmulator(initialColumns = 20, initialRows = 6)
        emoji.feed("🙂a")
        assertTrue(emoji.plainText(includeScrollback = false).lines().first().startsWith("🙂a"))
    }

    @Test
    fun osc52ClipboardIsDecodedAndNotRendered() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("before]52;c;aGVsbG8=after")
        assertEquals(listOf("hello"), terminal.drainClipboardRequests())
        assertTrue(terminal.plainText(includeScrollback = false).contains("beforeafter"))
    }

    @Test
    fun osc8HyperlinkAnnotatesRenderedTextWithoutPrintingControlText() {
        val terminal = TerminalEmulator(initialColumns = 30, initialRows = 6)
        terminal.feed("]8;id=1;https://example.comlink]8;;")
        val rendered = terminal.render(includeScrollback = false)
        assertTrue(rendered.text.contains("link"))
        assertTrue(!rendered.text.contains("example.com"))
        assertEquals(
            "https://example.com",
            rendered.getStringAnnotations("URL", 0, rendered.length).first().item
        )
    }

    @Test
    fun cursorShapeAndSgrMouseReportsAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[5 q")
        assertEquals(TerminalEmulator.CursorShape.BAR, terminal.cursorShape())
        terminal.feed("[?1000h[?1006h")
        val sequence = terminal.sequenceForMouse(
            TerminalEmulator.MouseEvent(
                row = 1,
                column = 2,
                button = TerminalEmulator.MouseButton.LEFT,
                type = TerminalEmulator.MouseEventType.PRESS
            )
        )
        assertEquals("[<0;3;2M", sequence)
    }

}
