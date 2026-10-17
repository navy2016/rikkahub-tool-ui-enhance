package me.rerere.rikkahub.utils

import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.utils.TerminalEmulator.Key
import me.rerere.rikkahub.utils.TerminalEmulator.MouseButton
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEvent
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEventType

/** Golden vectors from xterm ctlseqs and Kitty detection, not derived from the implementation. */
class TerminalProtocolBundleTest {
    private val esc = "\u001b"

    @Test fun protocolKittyProbesDoNotRestoreCursorChangeStyleOrClaimSupport() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("${esc}[2;3H${esc}7${esc}[5;11H${esc}[31m")
        terminal.feed("${esc}[>7u${esc}[?u${esc}[<u${esc}[=1u${esc}[1 u${esc}[c")
        assertEquals(5, terminal.cursorRow())
        assertEquals(11, terminal.cursorColumn())
        assertEquals(listOf("${esc}[?1;2c"), terminal.drainResponses())
        terminal.feed("x")
        assertTrue(terminal.plainText(false).lines()[4].endsWith("x"))
        terminal.feed("${esc}[u")
        assertEquals(2, terminal.cursorRow())
        assertEquals(3, terminal.cursorColumn())
    }

    @Test fun protocolPiFallbackNegotiatesKeysWithoutSgrSideEffects() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("${esc}[?25l${esc}[32m${esc}[>4;2m${esc}[?4mx")
        assertEquals(listOf("${esc}[>4;2m"), terminal.drainResponses())
        val spans = terminal.renderFrame(false).rows.first().text.spanStyles
        assertTrue(spans.none { it.item.textDecoration == TextDecoration.Underline })
        assertEquals("${esc}[27;5;97~", terminal.sequenceForCodePoint(97, ctrl = true))
        assertEquals("${esc}[27;2;13~", terminal.sequenceFor(Key.ENTER, shift = true))
        assertEquals("${esc}[27;5;9~", terminal.sequenceFor(Key.TAB, ctrl = true))
        terminal.feed("${esc}[>4;0m${esc}[?4m")
        assertEquals(listOf("${esc}[>4;0m"), terminal.drainResponses())
        assertEquals("\u0001", terminal.sequenceForCodePoint(97, ctrl = true))
    }

    @Test fun protocolUnknownPrefixAndIntermediateCannotExecutePlainCommands() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("${esc}[3;7Hkeep${esc}[3;7H")
        val before = terminal.plainText(false)
        for (sequence in listOf("${esc}[>2J", "${esc}[<2K", "${esc}[?4A", "${esc}[1!m", "${esc}[>3;5H")) {
            terminal.feed(sequence)
            assertEquals(before, terminal.plainText(false))
            assertEquals(3, terminal.cursorRow())
            assertEquals(7, terminal.cursorColumn())
        }
    }

    @Test fun protocolEraseDisplayDoesNotMoveCursorOrClearSavedHistory() {
        val terminal = TerminalEmulator(20, 6)
        terminal.feed((0..8).joinToString("\r\n") { "line$it" })
        val ids = terminal.renderFrame().historyLineIds
        terminal.feed("${esc}[4;9H${esc}[2J")
        assertEquals(4, terminal.cursorRow())
        assertEquals(9, terminal.cursorColumn())
        assertEquals(ids, terminal.renderFrame().historyLineIds)
        assertTrue(terminal.plainText(false).isBlank())
        terminal.feed("${esc}[3J")
        assertEquals(0, terminal.renderFrame().historyCount)
        assertEquals(4, terminal.cursorRow())
    }

    @Test fun protocolTextCommitPreservesEveryCodePointAndExplicitModifiers() {
        val terminal = TerminalEmulator()
        for (text in listOf("e\u0301", "👍🏽", "👩‍💻", "🇨🇳", "1️⃣", "中文")) {
            assertEquals(text, terminal.sequenceForTextCommit(text))
            assertEquals(esc + text, terminal.sequenceForTextCommit(text, alt = true))
        }
        assertEquals("\u0005\u0301", terminal.sequenceForTextCommit("e\u0301", ctrl = true))
        terminal.feed("${esc}[?2004h${esc}[>4;2m")
        assertEquals("e\u0301", terminal.sequenceForTextCommit("e\u0301"))
        assertEquals("${esc}[200~e\u0301${esc}[201~", terminal.wrapPaste("e\u0301"))
    }

    @Test fun protocolX10AndNormalReportsHaveSixBytePrefixAndRawHighBytes() {
        val terminal = TerminalEmulator(240, 80)
        terminal.feed("${esc}[?9h")
        assertArrayEquals(byteArrayOf(27, 91, 77, 34, 33, 33),
            terminal.sequenceForMouse(MouseEvent(0, 0, MouseButton.RIGHT, shift = true)))
        assertNull(terminal.sequenceForMouse(MouseEvent(0, 0, type = MouseEventType.RELEASE)))
        assertNull(terminal.sequenceForMouse(MouseEvent(0, 0, MouseButton.WHEEL_UP, MouseEventType.WHEEL)))
        terminal.feed("${esc}[?1000h")
        assertArrayEquals(byteArrayOf(27, 91, 77, 36, 133.toByte(), 33),
            terminal.sequenceForMouse(MouseEvent(0, 100, shift = true)))
        assertArrayEquals(byteArrayOf(27, 91, 77, 35, 33, 33),
            terminal.sequenceForMouse(MouseEvent(0, 0, type = MouseEventType.RELEASE)))
        assertNull(terminal.sequenceForMouse(MouseEvent(0, 223)))
    }

    @Test fun protocolUtf8MouseEncodesFieldsButDoesNotCorruptLegacyMode() {
        val terminal = TerminalEmulator(240, 80)
        terminal.feed("${esc}[?1000h${esc}[?1005h")
        assertArrayEquals(byteArrayOf(27, 91, 77, 32, 0xC2.toByte(), 0x85.toByte(), 33),
            terminal.sequenceForMouse(MouseEvent(0, 100)))
        assertArrayEquals(byteArrayOf(27, 91, 77, 32, 0xC4.toByte(), 0x90.toByte(), 33),
            terminal.sequenceForMouse(MouseEvent(0, 239)))
        terminal.feed("${esc}[?1005l")
        assertArrayEquals(byteArrayOf(27, 91, 77, 32, 133.toByte(), 33),
            terminal.sequenceForMouse(MouseEvent(0, 100)))
    }

    @Test fun protocolSgrHoverMotionAndModifiersCannotBecomeButtonPresses() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("${esc}[?1003h${esc}[?1006h")
        assertEquals("${esc}[<35;3;2M", terminal.sequenceForMouse(
            MouseEvent(1, 2, type = MouseEventType.MOVE))!!.toString(Charsets.US_ASCII))
        assertEquals("${esc}[<62;3;2M", terminal.sequenceForMouse(
            MouseEvent(1, 2, MouseButton.RIGHT, MouseEventType.DRAG, shift = true, alt = true, ctrl = true))!!
            .toString(Charsets.US_ASCII))
        assertEquals("${esc}[<2;3;2m", terminal.sequenceForMouse(
            MouseEvent(1, 2, MouseButton.RIGHT, MouseEventType.RELEASE))!!.toString(Charsets.US_ASCII))
        terminal.feed("${esc}[?1002h")
        assertNull(terminal.sequenceForMouse(MouseEvent(1, 2, type = MouseEventType.MOVE)))
    }

    @Test fun protocolPixelCoordinatesAreOneBasedAndStaleScreensAreRejected() {
        val terminal = TerminalEmulator(40, 8)
        terminal.setCellPixelSize(10, 20)
        terminal.feed("${esc}[?1000h${esc}[?1016h")
        assertEquals("${esc}[<0;11;21M", terminal.sequenceForMouse(MouseEvent(1, 1))!!.toString(Charsets.US_ASCII))
        assertEquals("${esc}[<0;1;1M", terminal.sequenceForMouse(
            MouseEvent(0, 0, pixelX = 0, pixelY = 0))!!.toString(Charsets.US_ASCII))
        assertNull(terminal.sequenceForMouse(MouseEvent(0, 0, pixelY = -1)))
        val generation = terminal.renderFrame().screenGeneration
        terminal.feed("${esc}[?1049h")
        assertNull(terminal.sequenceForMouse(MouseEvent(0, 0), generation))
    }

    @Test fun protocolEveryByteSplitRetainsProbeOrderAndCursorState() {
        val text = "${esc}[3;4H${esc}7${esc}[5;11H${esc}[>7u${esc}[?u${esc}[c${esc}[>4;2m${esc}[?4m中文e\u0301"
        val bytes = text.toByteArray()
        val reference = TerminalEmulator(40, 8).apply { feed(bytes) }
        for (split in bytes.indices) {
            val candidate = TerminalEmulator(40, 8)
            candidate.feed(bytes.copyOfRange(0, split))
            candidate.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(reference.plainText(), candidate.plainText())
            assertEquals(reference.cursorRow(), candidate.cursorRow())
            assertEquals(reference.cursorColumn(), candidate.cursorColumn())
            assertEquals(reference.pendingResponsesView(), candidate.pendingResponsesView())
        }
    }

    @Test fun protocolLargeLineCountsKeepTheExactSurvivingIdsTextAndHistory() {
        for (alternate in listOf(false, true)) for (operation in listOf('L', 'M', 'S', 'T')) {
            val first = TerminalEmulator(20, 6, 17)
            val second = TerminalEmulator(20, 6, 17)
            val seed = (if (alternate) "${esc}[?1049h" else "") + "old\r\nrows\r\n${esc}[1;1H${esc}[31m"
            first.feed(seed)
            second.feed(seed)
            first.feed("${esc}[999$operation")
            repeat(999) { second.feed("${esc}[1$operation") }
            val actual = first.renderFrame()
            val reference = second.renderFrame()
            assertEquals("$alternate/$operation", reference.copy(revision = actual.revision), actual)
        }
    }

    @Test(timeout = 5000) fun protocolExtremeCountsAndTabsAreBoundedWithoutIntegerWrap() {
        val terminal = TerminalEmulator(20, 6, 17)
        for (op in listOf('S', 'T', 'L', 'M', 'I', 'Z')) terminal.feed("${esc}[2147483647$op")
        terminal.feed("${esc}[2;2H${esc}[2147483647B${esc}[2147483647C")
        assertEquals(6, terminal.cursorRow())
        assertEquals(20, terminal.cursorColumn())
        assertEquals(17, terminal.renderFrame().historyCount)
    }

    @Test fun protocolOverlayKeepsUnderlyingTextAndReusesTextForCursorOnlyMoves() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("abcdef中e\u0301${esc}[5 q${esc}[1;2H")
        val first = terminal.renderFrameForCursorOverlay()
        assertEquals("abcdef中e\u0301", first.rows.first().text.text)
        assertNotNull(first.rows.first().cursorOverlay)
        terminal.feed("${esc}[1;3H")
        val moved = terminal.renderFrameForCursorOverlay()
        assertSame(first.rows.first().text, moved.rows.first().text)
        assertEquals(2, moved.rows.first().cursorOverlay?.start)
        terminal.feed("${esc}[1;8H")
        val wide = terminal.renderFrameForCursorOverlay().rows.first().cursorOverlay!!
        assertEquals(2, wide.cells)
        assertEquals(1, wide.part)
        val ordinary = terminal.renderFrame()
        assertNull(ordinary.rows.first().cursorOverlay)
        assertTrue(ordinary.rows.first().text.text.contains("▏"))
        assertEquals("abcdef中e\u0301", terminal.plainText(false).lines().first())
        terminal.feed("${esc}[2 q")
        assertEquals(terminal.renderFrame().rows, terminal.renderFrameForCursorOverlay().rows)
    }

    @Test fun protocolPixelMetricsSurviveRisAndReportTheActualHostDimensions() {
        val terminal = TerminalEmulator(40, 8)
        terminal.setCellPixelSize(12, 22)
        terminal.feed("${esc}c${esc}[16t${esc}[14t")
        assertEquals(listOf("${esc}[6;22;12t", "${esc}[4;176;480t"), terminal.drainResponses())
    }
}
