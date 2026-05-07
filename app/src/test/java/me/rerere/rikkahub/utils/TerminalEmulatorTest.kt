package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

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


    @Test
    fun zwjFlagsSkinToneAndKeycapStayInSingleGraphemeCells() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("👨‍👩‍👧a")
        assertTrue(terminal.plainText(includeScrollback = false).lines().first().startsWith("👨‍👩‍👧a"))

        val flag = TerminalEmulator(initialColumns = 20, initialRows = 6)
        flag.feed("🇺🇸a")
        assertTrue(flag.plainText(includeScrollback = false).lines().first().startsWith("🇺🇸a"))

        val skinTone = TerminalEmulator(initialColumns = 20, initialRows = 6)
        skinTone.feed("👍🏽a")
        assertTrue(skinTone.plainText(includeScrollback = false).lines().first().startsWith("👍🏽a"))

        val keycap = TerminalEmulator(initialColumns = 20, initialRows = 6)
        keycap.feed("1️⃣a")
        assertTrue(keycap.plainText(includeScrollback = false).lines().first().startsWith("1️⃣a"))
    }

    @Test
    fun focusAndWheelReportsFollowXtermModes() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        assertEquals(null, terminal.sequenceForFocus(true))
        terminal.feed("[?1004h")
        assertEquals("[I", terminal.sequenceForFocus(true))
        assertEquals("[O", terminal.sequenceForFocus(false))

        terminal.feed("[?1000h[?1006h")
        val wheel = terminal.sequenceForMouse(
            TerminalEmulator.MouseEvent(
                row = 0,
                column = 0,
                button = TerminalEmulator.MouseButton.WHEEL_DOWN,
                type = TerminalEmulator.MouseEventType.WHEEL
            )
        )
        assertEquals("[<65;1;1M", wheel)
    }

    @Test
    fun cursorShapeUsesVisibleGlyphForBlankCells() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[5 q")
        assertTrue(terminal.render(includeScrollback = false).text.startsWith("▏"))
        terminal.feed("[3 q")
        assertTrue(terminal.render(includeScrollback = false).text.startsWith("▁"))
    }


    @Test
    fun x10AndUrxvtMouseModesAreReportedCorrectly() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[?9h")
        assertEquals("MOUSE-X10", terminal.mouseModeSummary())
        assertEquals(
            "[M\"!!",
            terminal.sequenceForMouse(
                TerminalEmulator.MouseEvent(
                    row = 0,
                    column = 0,
                    button = TerminalEmulator.MouseButton.RIGHT,
                    type = TerminalEmulator.MouseEventType.PRESS
                )
            )
        )
        assertEquals(
            null,
            terminal.sequenceForMouse(
                TerminalEmulator.MouseEvent(
                    row = 0,
                    column = 0,
                    button = TerminalEmulator.MouseButton.RELEASE,
                    type = TerminalEmulator.MouseEventType.RELEASE
                )
            )
        )

        terminal.feed("[?1000h[?1015h")
        assertEquals("MOUSE/URXVT", terminal.mouseModeSummary())
        assertEquals(
            "[32;3;2M",
            terminal.sequenceForMouse(
                TerminalEmulator.MouseEvent(
                    row = 1,
                    column = 2,
                    button = TerminalEmulator.MouseButton.LEFT,
                    type = TerminalEmulator.MouseEventType.PRESS
                )
            )
        )
    }

    @Test
    fun xtwinopsReportsTerminalAndCellSizes() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[18t[14t[16t")
        assertEquals(
            listOf("[8;6;20t", "[4;84;140t", "[6;14;7t"),
            terminal.drainResponses()
        )
    }

    @Test
    fun osc7WorkingDirectoryUriIsCapturedWithoutRendering() {
        val terminal = TerminalEmulator(initialColumns = 30, initialRows = 6)
        terminal.feed("before]7;file://host/workspaceafter")
        assertEquals("file://host/workspace", terminal.workingDirectoryUri)
        assertTrue(terminal.plainText(includeScrollback = false).contains("beforeafter"))
    }


    @Test
    fun secondaryDeviceAttributesAndRequestModeReportsAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[>c")
        assertEquals(listOf("[>0;276;0c"), terminal.drainResponses())

        terminal.feed("[?2004h[?2004\$p[?1006\$p")
        assertEquals(
            listOf("[?2004;1\$y", "[?1006;2\$y"),
            terminal.drainResponses()
        )
    }

    @Test
    fun oscColorQueriesAndPaletteOverridesAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("]10;rgb:ffff/0000/0000]11;rgb:0000/0000/0000]12;rgb:0000/ffff/0000")
        terminal.feed("]10;?]11;?]12;?")
        assertEquals(
            listOf(
                "]10;rgb:ffff/0000/0000",
                "]11;rgb:0000/0000/0000",
                "]12;rgb:0000/ffff/0000"
            ),
            terminal.drainResponses()
        )

        terminal.feed("]4;1;rgb:ffff/0000/0000]4;1;?")
        assertEquals(listOf("]4;1;rgb:ffff/0000/0000"), terminal.drainResponses())
    }


    @Test
    fun tabStopsCanBeSetAndCleared() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("a\tb")
        assertEquals("a       b", terminal.plainText(includeScrollback = false).lines()[0])

        val custom = TerminalEmulator(initialColumns = 20, initialRows = 6)
        custom.feed("\u001B[3g") // clear all default tab stops
        custom.feed("\u001B[4G\u001BH") // HTS at zero-based column 3
        custom.feed("\rX\tY")
        assertEquals("X  Y", custom.plainText(includeScrollback = false).lines()[0])

        val ctc = TerminalEmulator(initialColumns = 20, initialRows = 6)
        ctc.feed("\u001B[3g\u001B[4G\u001B[W\rZ\tQ")
        assertEquals("Z  Q", ctc.plainText(includeScrollback = false).lines()[0])
        ctc.feed("\u001B[4G\u001B[2W\r\u001B[KR\tS")
        assertEquals("R                  S", ctc.plainText(includeScrollback = false).lines()[0])
    }

    @Test
    fun oscColorResetsRestoreDefaultsAndPaletteEntries() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("]10;rgb:ffff/0000/0000]10;?")
        assertEquals(listOf("]10;rgb:ffff/0000/0000"), terminal.drainResponses())
        terminal.feed("]110;]10;?")
        assertEquals(listOf("]10;rgb:0000/e6e6/7676"), terminal.drainResponses())

        terminal.feed("]4;2;rgb:0000/ffff/0000]4;2;?")
        assertEquals(listOf("]4;2;rgb:0000/ffff/0000"), terminal.drainResponses())
        terminal.feed("]104;2]4;2;?")
        assertEquals(listOf("]4;2;rgb:4e4e/9a9a/0606"), terminal.drainResponses())
    }

    @Test
    fun requestModeReportsMoreMouseAndAlternateModes() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[?9h[?9\$p[?1005h[?1005\$p")
        assertEquals(
            listOf("[?9;1\$y", "[?1005;1\$y"),
            terminal.drainResponses()
        )

        terminal.feed("[?1049h[?1047\$p[?1048\$p[?1049\$p")
        assertEquals(
            listOf("[?1047;1\$y", "[?1048;1\$y", "[?1049;1\$y"),
            terminal.drainResponses()
        )
    }


    @Test
    fun repeatPrecedingGraphicCharacterRepeatsFullGrapheme() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("A[3b")
        assertEquals("AAAA", terminal.plainText(includeScrollback = false).lines()[0])

        val wide = TerminalEmulator(initialColumns = 20, initialRows = 6)
        wide.feed("中[2b")
        assertEquals("中中中", wide.plainText(includeScrollback = false).lines()[0])
    }

    @Test
    fun oscPaletteResetSupportsMultipleEntriesAndAliases() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("]4;1;rgb:ffff/0000/0000;2;rgb:0000/ffff/0000")
        terminal.feed("]4;1;?]4;2;?")
        assertEquals(
            listOf("]4;1;rgb:ffff/0000/0000", "]4;2;rgb:0000/ffff/0000"),
            terminal.drainResponses()
        )
        terminal.feed("]104;1;2]4;1;?]4;2;?")
        assertEquals(
            listOf("]4;1;rgb:cccc/0000/0000", "]4;2;rgb:4e4e/9a9a/0606"),
            terminal.drainResponses()
        )

        terminal.feed("]4;3;rgb:ffff/ffff/0000]105;3]4;3;?")
        assertEquals(listOf("]4;3;rgb:c4c4/a0a0/0000"), terminal.drainResponses())
    }

    @Test
    fun requestModeReportsAnsiModesAndSgrBaselineControlsParse() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("[4\$p[20\$p[999\$p")
        assertEquals(
            listOf("[4;2\$y", "[20;2\$y", "[999;0\$y"),
            terminal.drainResponses()
        )
        terminal.feed("[73msuper[74msub[75mnormal")
        assertTrue(terminal.plainText(includeScrollback = false).contains("supersubnormal"))
    }


    @Test
    fun insertModeInsertsPrintableCharactersAndReportsState() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("abcd\u001B[2G\u001B[4hX\u001B[4\$p\u001B[4l\u001B[4\$p")
        assertEquals("aXbcd", terminal.plainText(includeScrollback = false).lines()[0])
        assertEquals(listOf("\u001B[4;1\$y", "\u001B[4;2\$y"), terminal.drainResponses())
    }

    @Test
    fun cursorTabulationUsesConfiguredTabStopsInBothDirections() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[3g\u001B[5G\u001BH\u001B[10G\u001BH")
        terminal.feed("\r\u001B[IX\u001B[IZ\u001B[2ZQ")
        assertEquals("    Q    Z", terminal.plainText(includeScrollback = false).lines()[0])
    }

    @Test
    fun private1048SaveRestoreCursorReportsStateIndependently() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("A\u001B[1;5H\u001B[?1048h\u001B[?1048\$p\u001B[1;6HB\u001B[?1048lC\u001B[?1048\$p")
        val line = terminal.plainText(includeScrollback = false).lines()[0]
        assertEquals('C', line[4])
        assertEquals('B', line[5])
        assertEquals(listOf("\u001B[?1048;1\$y", "\u001B[?1048;2\$y"), terminal.drainResponses())
    }


    @Test
    fun clearScrollbackApplicationKeypadAndSyncOutputModesAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6, maxScrollbackLines = 20)
        terminal.feed((1..8).joinToString("\n") { "line$it" })
        assertTrue(terminal.plainText(includeScrollback = true).contains("line1"))
        terminal.feed("\u001B[3J")
        assertTrue(!terminal.plainText(includeScrollback = true).contains("line1"))
        assertTrue(terminal.plainText(includeScrollback = false).contains("line8"))

        assertEquals("1", terminal.sequenceFor(TerminalEmulator.Key.KP_1))
        terminal.feed("\u001B=")
        assertEquals("\u001BOq", terminal.sequenceFor(TerminalEmulator.Key.KP_1))
        assertTrue(terminal.modeSummary().contains("APP-KEYPAD"))
        terminal.feed("\u001B>")
        assertEquals("1", terminal.sequenceFor(TerminalEmulator.Key.KP_1))

        terminal.feed("\u001B[?2026h\u001B[?2026\$p\u001B[?2026l\u001B[?2026\$p")
        assertEquals(listOf("\u001B[?2026;1\$y", "\u001B[?2026;2\$y"), terminal.drainResponses())
    }

    @Test
    fun privateDeviceStatusAndXtermVersionReportsAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[2;4H\u001B[?6n\u001B[?15n\u001B[?25n\u001B[>q")
        assertEquals(
            listOf(
                "\u001B[?2;4R",
                "\u001B[?13n",
                "\u001B[?20n",
                "\u001BP>|RikkaHubTerminal 1.0\u001B\\"
            ),
            terminal.drainResponses()
        )
    }

    @Test
    fun alternateScrollMetaEscapeAndColonUnderlineModesAreTolerated() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[?1007h\u001B[?1034h\u001B[?1007\$p\u001B[?1034\$p")
        assertEquals(listOf("\u001B[?1007;1\$y", "\u001B[?1034;1\$y"), terminal.drainResponses())
        terminal.feed("\u001B[4:3mcurly\u001B[4:0mplain")
        assertTrue(terminal.plainText(includeScrollback = false).contains("curlyplain"))
    }


    @Test
    fun reverseVideoPrivateModeIsTrackedReportedAndSummarized() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[?5h\u001B[?5\$p")
        assertTrue(terminal.modeSummary().contains("REVERSE-VIDEO"))
        assertEquals(listOf("\u001B[?5;1\$y"), terminal.drainResponses())
        terminal.feed("\u001B[?5l\u001B[?5\$p")
        assertEquals(listOf("\u001B[?5;2\$y"), terminal.drainResponses())
    }

    @Test
    fun xtwinopsWindowAndTitleReportsAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B]0;main-title\u0007")
        terminal.feed("\u001B[11t\u001B[13t\u001B[19t\u001B[20t\u001B[21t")
        assertEquals(
            listOf(
                "\u001B[1t",
                "\u001B[3;0;0t",
                "\u001B[9;6;20t",
                "\u001B]L;main-title\u001B\\",
                "\u001B]l;main-title\u001B\\"
            ),
            terminal.drainResponses()
        )
    }

    @Test
    fun xtwinopsTitleSaveAndRestoreAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B]0;first\u0007\u001B[22t\u001B]0;second\u0007")
        assertEquals("second", terminal.title)
        terminal.feed("\u001B[23t")
        assertEquals("first", terminal.title)
    }


    @Test
    fun keySequencesSupportXtermModifiersAndEditingKeys() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        assertEquals("\u001B[1;5A", terminal.sequenceFor(TerminalEmulator.Key.UP, ctrl = true))
        assertEquals("\u001B[1;4D", terminal.sequenceFor(TerminalEmulator.Key.LEFT, shift = true, alt = true))
        assertEquals("\u001B[3;2~", terminal.sequenceFor(TerminalEmulator.Key.DELETE, shift = true))
        assertEquals("\u001B[15;8~", terminal.sequenceFor(TerminalEmulator.Key.F5, shift = true, alt = true, ctrl = true))
        assertEquals("\u001B[Z", terminal.sequenceFor(TerminalEmulator.Key.BACK_TAB))
        assertEquals("\u0017", terminal.sequenceFor(TerminalEmulator.Key.BACKSPACE, ctrl = true))
    }

    @Test
    fun c1ControlsHandleIndexNextLineReverseIndexAndTabSet() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("A\u0085B")
        assertEquals("A", terminal.plainText(includeScrollback = false).lines()[0])
        assertEquals("B", terminal.plainText(includeScrollback = false).lines()[1])

        val tabbed = TerminalEmulator(initialColumns = 20, initialRows = 6)
        tabbed.feed("\u001B[3g\u001B[5G\u0088\rX\tY")
        assertEquals("X   Y", tabbed.plainText(includeScrollback = false).lines()[0])

        val reverse = TerminalEmulator(initialColumns = 20, initialRows = 6)
        reverse.feed("\u001B[2;1Hdown\u008Dtop")
        assertTrue(reverse.plainText(includeScrollback = false).lines()[0].contains("top"))
    }


    @Test
    fun ansiNewlineModeControlsLineFeedCarriageReturnAndReportsState() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("AB\u001B[1D\nC")
        assertEquals("AB", terminal.plainText(includeScrollback = false).lines()[0])
        assertEquals(" C", terminal.plainText(includeScrollback = false).lines()[1])
        terminal.feed("\u001B[20h\u001B[20\$p\u001B[20l\u001B[20\$p")
        assertEquals(listOf("\u001B[20;1\$y", "\u001B[20;2\$y"), terminal.drainResponses())
    }

    @Test
    fun xtermKeyboardOptionModesAreTrackedAndReported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[?1036h\u001B[?1036\$p\u001B[?1036l\u001B[?1036\$p")
        assertEquals(listOf("\u001B[?1036;1\$y", "\u001B[?1036;2\$y"), terminal.drainResponses())

        terminal.feed("\u001B[?1052h\u001B[?1052\$p\u001B[?1053\$p")
        assertEquals(listOf("\u001B[?1052;1\$y", "\u001B[?1053;2\$y"), terminal.drainResponses())

        terminal.feed("\u001B[?1061h\u001B[?1061\$p\u001B[?1060\$p")
        assertEquals(listOf("\u001B[?1061;1\$y", "\u001B[?1060;2\$y"), terminal.drainResponses())
    }


    @Test
    fun oscIconAndWindowTitlesAreTrackedSeparatelyAndReported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B]1;icon-only\u0007\u001B]2;window-only\u0007")
        terminal.feed("\u001B[20t\u001B[21t")
        assertEquals(
            listOf("\u001B]L;icon-only\u001B\\", "\u001B]l;window-only\u001B\\"),
            terminal.drainResponses()
        )

        terminal.feed("\u001B]0;both\u0007\u001B[20t\u001B[21t")
        assertEquals(
            listOf("\u001B]L;both\u001B\\", "\u001B]l;both\u001B\\"),
            terminal.drainResponses()
        )
    }

    @Test
    fun xtwinopsTitleStacksSupportNestedAndSelectiveRestore() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B]0;one\u0007\u001B[22t\u001B]2;two\u0007\u001B[22;2t\u001B]2;three\u0007")
        terminal.feed("\u001B[23;2t")
        assertEquals("two", terminal.title)
        terminal.feed("\u001B[23t")
        assertEquals("one", terminal.title)

        terminal.feed("\u001B]1;icon-a\u0007\u001B]2;window-a\u0007\u001B[22;1t\u001B]1;icon-b\u0007\u001B[23;1t\u001B[20t\u001B[21t")
        assertEquals(
            listOf("\u001B]L;icon-a\u001B\\", "\u001B]l;window-a\u001B\\"),
            terminal.drainResponses()
        )
    }

    @Test
    fun screenPixelReportAndPrivate47ModeReportAreSupported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[15t\u001B[?47h\u001B[?47\$p\u001B[?47l\u001B[?47\$p")
        assertEquals(
            listOf("\u001B[5;84;140t", "\u001B[?47;1\$y", "\u001B[?47;2\$y"),
            terminal.drainResponses()
        )
    }


    @Test
    fun xtGetTcapReportsCommonCapabilitiesAndUnknowns() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001BP+q544e;436f;524742;626164\u001B\\")
        assertEquals(
            listOf(
                "\u001BP1+r544e=787465726d2d323536636f6c6f72\u001B\\",
                "\u001BP1+r436f=323536\u001B\\",
                "\u001BP1+r524742=31\u001B\\",
                "\u001BP0+r626164\u001B\\"
            ),
            terminal.drainResponses()
        )
    }

    @Test
    fun osc52ClipboardQueryReturnsSafeEmptyResponse() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B]52;c;?\u0007")
        assertEquals(listOf("\u001B]52;c;\u0007"), terminal.drainResponses())
        assertEquals(emptyList<String>(), terminal.drainClipboardRequests())
    }


    @Test
    fun sgrPixelMouseModeReportsPixelCoordinatesAndModeState() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[?1000h\u001B[?1016h\u001B[?1016\$p")
        assertEquals(listOf("\u001B[?1016;1\$y"), terminal.drainResponses())
        assertEquals(
            "\u001B[<0;15;29M",
            terminal.sequenceForMouse(TerminalEmulator.MouseEvent(row = 2, column = 2))
        )
        assertTrue(terminal.mouseModeSummary().contains("SGR-PIXELS"))
        terminal.feed("\u001B[?1016l\u001B[?1016\$p")
        assertEquals(listOf("\u001B[?1016;2\$y"), terminal.drainResponses())
    }

    @Test
    fun locatorStatusDeviceReportReturnsAvailable() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[?53n")
        assertEquals(listOf("\u001B[?50n"), terminal.drainResponses())
    }


    @Test
    fun decRequestStatusStringReportsCursorStyleSgrAndScrollRegion() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[5 q\u001B[2;5r")
        terminal.feed("\u001BP\$q q\u001B\\\u001BP\$qm\u001B\\\u001BP\$qr\u001B\\")
        assertEquals(
            listOf(
                "\u001BP1\$r5 q\u001B\\",
                "\u001BP1\$r0m\u001B\\",
                "\u001BP1\$r2;5r\u001B\\"
            ),
            terminal.drainResponses()
        )
    }

    @Test
    fun decRequestStatusStringReportsUnknownSelectorsAsInvalid() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001BP\$qbad\u001B\\")
        assertEquals(listOf("\u001BP0\$rbad\u001B\\"), terminal.drainResponses())
    }


    @Test
    fun decRequestStatusStringReportsCurrentSgrAttributes() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[1;3;4;7;9;53;73m\u001BP\$qm\u001B\\")
        assertEquals(listOf("\u001BP1\$r1;3;4;7;9;53;73m\u001B\\"), terminal.drainResponses())

        terminal.feed("\u001B[0m\u001BP\$qm\u001B\\")
        assertEquals(listOf("\u001BP1\$r0m\u001B\\"), terminal.drainResponses())
    }


    @Test
    fun decSelectiveCharacterProtectionIsTrackedAndReported() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[1\"q\u001BP\$q\"q\u001B\\")
        assertEquals(listOf("\u001BP1\$r1\"q\u001B\\"), terminal.drainResponses())

        terminal.feed("\u001B[0\"q\u001BP\$q\"q\u001B\\")
        assertEquals(listOf("\u001BP1\$r0\"q\u001B\\"), terminal.drainResponses())
    }


    @Test
    fun selectiveErasePreservesProtectedCharacters() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[1\"qA\u001B[0\"qB\u001B[1G\u001B[?2K")
        assertEquals("A", terminal.plainText(includeScrollback = false).lines()[0].trimEnd())

        terminal.feed("\u001B[2J\u001B[1;1H\u001B[1\"qC\u001B[0\"qD\u001B[2;1HEF\u001B[1;1H\u001B[?2J")
        val lines = terminal.plainText(includeScrollback = false).lines()
        assertEquals("C", lines[0].trimEnd())
        assertEquals("", lines[1].trimEnd())
    }


    @Test
    fun rectangularEraseHonorsSelectiveProtection() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("\u001B[1\"qA\u001B[0\"qB\u001B[2;1HCD\u001B[1;1H\u001B[1;1;2;2\${")
        assertEquals("A", terminal.plainText(includeScrollback = false).lines()[0].trimEnd())
        assertEquals("", terminal.plainText(includeScrollback = false).lines()[1].trimEnd())

        terminal.feed("\u001B[2J\u001B[1;1H\u001B[1\"qE\u001B[0\"qF\u001B[1;1H\u001B[1;1;1;2\$z")
        assertEquals("", terminal.plainText(includeScrollback = false).lines()[0].trimEnd())
    }


    @Test
    fun rectangularCharacterAttributesCanBeChangedAndReversed() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("AB\u001B[2;1HCD\u001B[1;1;2;1;1;4\$r")
        var rendered = terminal.render(includeScrollback = false)
        val boldUnderlined = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold && it.item.textDecoration == TextDecoration.Underline }
        assertEquals(listOf(0 until 1, 3 until 4), boldUnderlined.map { it.start until it.end })

        terminal.feed("\u001B[1;1;1;1;1;4\$t")
        rendered = terminal.render(includeScrollback = false)
        val remainingBoldUnderlined = rendered.spanStyles.filter { it.item.fontWeight == FontWeight.Bold && it.item.textDecoration == TextDecoration.Underline }
        assertEquals(listOf(3 until 4), remainingBoldUnderlined.map { it.start until it.end })

        terminal.feed("\u001B[2;1;2;1;0\$r")
        rendered = terminal.render(includeScrollback = false)
        assertTrue(rendered.spanStyles.none { it.start == 3 && it.item.fontWeight == FontWeight.Bold })
    }


    @Test
    fun fillRectangleWritesRequestedCharacterAndClipsArea() {
        val terminal = TerminalEmulator(initialColumns = 20, initialRows = 6)
        terminal.feed("ABCD\u001B[2;1HEFGH\u001B[88;1;2;2;3\$x")
        var lines = terminal.plainText(includeScrollback = false).lines()
        assertEquals("AXXD", lines[0])
        assertEquals("EXXH", lines[1])

        terminal.feed("\u001B[2J\u001B[1;1HABCD\u001B[2;1HEFGH\u001B[90;2;3;9;30\$x")
        lines = terminal.plainText(includeScrollback = false).lines()
        assertEquals("ABCD", lines[0])
        assertEquals("EFZZ", lines[1])
    }

}
