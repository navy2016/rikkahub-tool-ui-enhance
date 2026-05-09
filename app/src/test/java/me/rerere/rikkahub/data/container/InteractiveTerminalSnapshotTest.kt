package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveTerminalSnapshotTest {
    @Test
    fun renderInteractiveTerminalSnapshotConvertsAnsiOutputToPlainScreen() {
        val bytes = "hello\u001B[2J\u001B[Htop\u001B[2;5Hmenu".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(20, snapshot.columns)
        assertEquals(6, snapshot.rows)
        assertEquals("", snapshot.modes)
        val lines = snapshot.screen.lines()
        assertEquals("top", lines[0])
        assertEquals("    menu", lines[1])
        assertEquals(6, lines.size)
        assertEquals(lines, snapshot.screenLines)
        assertEquals("top\n    menu", snapshot.visibleContent)
        assertEquals(listOf("top", "    menu"), snapshot.visibleContentLines)
        assertEquals(listOf("top", "    menu", ""), snapshot.topLines)
        assertEquals(1, snapshot.topLinesStartRow)
        assertEquals(3, snapshot.topLinesEndRow)
        assertEquals(listOf("", "", ""), snapshot.bottomLines)
        assertEquals(4, snapshot.bottomLinesStartRow)
        assertEquals(6, snapshot.bottomLinesEndRow)
        assertEquals(listOf(1, 2), snapshot.nonEmptyRows)
        assertEquals(listOf("top", "    menu"), snapshot.nonEmptyLines)
        assertEquals(2, snapshot.nonEmptyRowCount)
        assertEquals(0, snapshot.internalBlankRowCount)
        assertEquals(0, snapshot.leadingBlankRowCount)
        assertEquals(4, snapshot.trailingBlankRowCount)
        assertEquals(1000, snapshot.contentDensityPermille)
        assertEquals(333, snapshot.viewportDensityPermille)
        assertEquals(1, snapshot.firstNonEmptyRow)
        assertEquals(2, snapshot.lastNonEmptyRow)
        assertEquals("top", snapshot.firstNonEmptyLine)
        assertEquals("    menu", snapshot.lastNonEmptyLine)
        assertEquals(2, snapshot.contentHeight)
        assertEquals(false, snapshot.isEmpty)
    }

    @Test
    fun renderInteractiveTerminalSnapshotIncludesTitleAndModeState() {
        val bytes = "\u001B]0;codex\u0007\u001B[?1049hALT".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals("codex", snapshot.title)
        assertTrue(snapshot.modes.contains("ALT"))
        assertTrue(snapshot.screen.lines().first().startsWith("ALT"))
    }

    @Test
    fun renderInteractiveTerminalSnapshotClampsRequestedSize() {
        val snapshot = renderInteractiveTerminalSnapshot("x".toByteArray(), columns = 5, rows = 2)

        assertEquals(20, snapshot.columns)
        assertEquals(6, snapshot.rows)
    }

    @Test
    fun renderInteractiveTerminalSnapshotIncludesCursorMetadataAndWorkingDirectory() {
        val bytes = (
            "\u001B]7;file://sandbox/workspace/project\u001B\\" +
                "\u001B[?25l" +
                "\u001B[3 q" +
                "\u001B[4;7Hcursor"
        ).toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(4, snapshot.cursorRow)
        assertEquals(13, snapshot.cursorColumn)
        assertEquals(3, snapshot.rowsAboveCursor)
        assertEquals(2, snapshot.rowsBelowCursor)
        assertEquals("UNDERLINE", snapshot.cursorShape)
        assertEquals(false, snapshot.cursorVisible)
        assertEquals("file://sandbox/workspace/project", snapshot.workingDirectoryUri)
    }

    @Test
    fun renderInteractiveTerminalSnapshotIncludesResponsesAndClipboardRequests() {
        val bytes = (
            "before" +
                "\u001B[6n" +
                "\u001B]52;c;aGVsbG8=\u0007" +
                "after"
        ).toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(listOf("\u001B[1;7R"), snapshot.responses)
        assertEquals(listOf("hello"), snapshot.clipboardRequests)
        assertTrue(snapshot.screen.lines().first().contains("beforeafter"))
    }

    @Test
    fun renderInteractiveTerminalSnapshotMarksBlankScreenEmpty() {
        val snapshot = renderInteractiveTerminalSnapshot("\u001B[2J".toByteArray(), columns = 20, rows = 6)

        assertEquals(List(6) { "" }, snapshot.screenLines)
        assertEquals(emptyList<Int>(), snapshot.nonEmptyRows)
        assertEquals(emptyList<String>(), snapshot.nonEmptyLines)
        assertEquals(0, snapshot.nonEmptyRowCount)
        assertEquals(0, snapshot.internalBlankRowCount)
        assertEquals(6, snapshot.leadingBlankRowCount)
        assertEquals(0, snapshot.trailingBlankRowCount)
        assertEquals(0, snapshot.contentDensityPermille)
        assertEquals(0, snapshot.viewportDensityPermille)
        assertEquals(null, snapshot.cursorDistanceFromContentTop)
        assertEquals(null, snapshot.cursorDistanceFromContentBottom)
        assertEquals(true, snapshot.cursorLineIsBlank)
        assertEquals(0, snapshot.cursorLineLength)
        assertEquals(0, snapshot.cursorLineLeadingBlankCount)
        assertEquals(0, snapshot.cursorLineTrailingBlankCount)
        assertEquals(null, snapshot.cursorLineFirstNonBlankColumn)
        assertEquals(null, snapshot.cursorLineLastNonBlankColumn)
        assertEquals(null, snapshot.cursorDistanceFromLineTextStart)
        assertEquals(null, snapshot.cursorDistanceFromLineTextEnd)
        assertEquals("BLANK_LINE", snapshot.cursorLineCursorRegion)
        assertCursorRegionFlags(snapshot, onBlankLine = true)
        assertEquals(null, snapshot.cursorLineTextContent)
        assertEquals(null, snapshot.cursorLineTextLength)
        assertEquals(null, snapshot.cursorLineTextLeadingPadding)
        assertEquals(null, snapshot.cursorLineTextTrailingPadding)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingLength)
        assertEquals(null, snapshot.cursorLineTextTrailingPaddingLength)
        assertEquals(null, snapshot.cursorLineTextBeforeCursorInText)
        assertEquals(null, snapshot.cursorLineTextAtCursor)
        assertEquals(null, snapshot.cursorLineTextAfterCursorInText)
        assertEquals(null, snapshot.cursorLineTextCursorOffset)
        assertEquals(null, snapshot.cursorLineTextCursorOffsetFromEnd)
        assertEquals(null, snapshot.cursorLineCharAtCursor)
        assertEquals(null, snapshot.cursorLineTextCursorSegment)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, snapshot.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(0, snapshot.cursorTextColumn)
        assertEquals(0, snapshot.cursorDistanceFromLineEnd)
        assertEquals(listOf("", "", ""), snapshot.topLines)
        assertEquals(listOf("", "", ""), snapshot.bottomLines)
        assertEquals(listOf("", "", ""), snapshot.cursorContextLines)
        assertEquals(1, snapshot.cursorContextStartRow)
        assertEquals(3, snapshot.cursorContextEndRow)
        assertEquals(0, snapshot.cursorContextCursorIndex)
        assertEquals("", snapshot.visibleContent)
        assertEquals(emptyList<String>(), snapshot.visibleContentLines)
        assertEquals(null, snapshot.firstNonEmptyRow)
        assertEquals(null, snapshot.lastNonEmptyRow)
        assertEquals(null, snapshot.firstNonEmptyLine)
        assertEquals(null, snapshot.lastNonEmptyLine)
        assertEquals(0, snapshot.contentHeight)
        assertEquals(true, snapshot.isEmpty)
    }

    @Test
    fun renderInteractiveTerminalSnapshotIncludesCursorLineContext() {
        val bytes = "prompt> hello world\u001B[1;15H".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(1, snapshot.cursorRow)
        assertEquals(15, snapshot.cursorColumn)
        assertEquals(0, snapshot.rowsAboveCursor)
        assertEquals(5, snapshot.rowsBelowCursor)
        assertEquals(0, snapshot.cursorDistanceFromContentTop)
        assertEquals(0, snapshot.cursorDistanceFromContentBottom)
        assertEquals(1, snapshot.cursorContextStartRow)
        assertEquals(3, snapshot.cursorContextEndRow)
        assertEquals(0, snapshot.cursorContextCursorIndex)
        assertEquals(listOf("prompt> hello world", "", ""), snapshot.cursorContextLines)
        assertEquals("prompt> hello world", snapshot.cursorLine)
        assertEquals(false, snapshot.cursorLineIsBlank)
        assertEquals(19, snapshot.cursorLineLength)
        assertEquals(0, snapshot.cursorLineLeadingBlankCount)
        assertEquals(0, snapshot.cursorLineTrailingBlankCount)
        assertEquals(1, snapshot.cursorLineFirstNonBlankColumn)
        assertEquals(19, snapshot.cursorLineLastNonBlankColumn)
        assertEquals(14, snapshot.cursorDistanceFromLineTextStart)
        assertEquals(-5, snapshot.cursorDistanceFromLineTextEnd)
        assertEquals("INSIDE_TEXT", snapshot.cursorLineCursorRegion)
        assertCursorRegionFlags(snapshot, insideLineText = true)
        assertEquals(14, snapshot.cursorTextColumn)
        assertEquals(-5, snapshot.cursorDistanceFromLineEnd)
        assertEquals("prompt> hello ", snapshot.cursorLineTextBeforeCursor)
        assertEquals("world", snapshot.cursorLineTextAfterCursor)
        assertEquals("w", snapshot.cursorLineCharAtCursor)
        assertEquals("prompt> hello world", snapshot.cursorLineTextContent)
        assertEquals(19, snapshot.cursorLineTextLength)
        assertEquals("prompt> hello ", snapshot.cursorLineTextBeforeCursorInText)
        assertEquals("w", snapshot.cursorLineTextAtCursor)
        assertEquals("world", snapshot.cursorLineTextAfterCursorInText)
        assertEquals(14, snapshot.cursorLineTextCursorOffset)
        assertEquals(5, snapshot.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TEXT_CONTENT", snapshot.cursorLineTextCursorSegment)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, snapshot.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(true, snapshot.cursorInVisibleContent)
        assertEquals(1, snapshot.cursorVisibleContentRow)
        assertEquals("prompt> hello world", snapshot.cursorVisibleContentLine)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsSparseContentBounds() {
        val bytes = "\u001B[3;4Hmiddle\u001B[6;1Hbottom".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(listOf(3, 6), snapshot.nonEmptyRows)
        assertEquals(listOf("   middle", "bottom"), snapshot.nonEmptyLines)
        assertEquals(2, snapshot.nonEmptyRowCount)
        assertEquals(2, snapshot.internalBlankRowCount)
        assertEquals(2, snapshot.leadingBlankRowCount)
        assertEquals(0, snapshot.trailingBlankRowCount)
        assertEquals(500, snapshot.contentDensityPermille)
        assertEquals(333, snapshot.viewportDensityPermille)
        assertEquals("   middle\n\n\nbottom", snapshot.visibleContent)
        assertEquals(listOf("   middle", "", "", "bottom"), snapshot.visibleContentLines)
        assertEquals(listOf("", "", "   middle"), snapshot.topLines)
        assertEquals(listOf("", "", "bottom"), snapshot.bottomLines)
        assertEquals(true, snapshot.cursorInVisibleContent)
        assertEquals(4, snapshot.cursorVisibleContentRow)
        assertEquals("bottom", snapshot.cursorVisibleContentLine)
        assertEquals(false, snapshot.cursorLineIsBlank)
        assertEquals(6, snapshot.cursorLineLength)
        assertEquals(0, snapshot.cursorLineLeadingBlankCount)
        assertEquals(0, snapshot.cursorLineTrailingBlankCount)
        assertEquals(1, snapshot.cursorLineFirstNonBlankColumn)
        assertEquals(6, snapshot.cursorLineLastNonBlankColumn)
        assertEquals(6, snapshot.cursorDistanceFromLineTextStart)
        assertEquals(0, snapshot.cursorDistanceFromLineTextEnd)
        assertEquals("TEXT_END", snapshot.cursorLineCursorRegion)
        assertCursorRegionFlags(snapshot, atLineTextEnd = true)
        assertEquals(6, snapshot.cursorTextColumn)
        assertEquals(0, snapshot.cursorDistanceFromLineEnd)
        assertEquals(3, snapshot.cursorDistanceFromContentTop)
        assertEquals(0, snapshot.cursorDistanceFromContentBottom)
        assertEquals(4, snapshot.cursorContextStartRow)
        assertEquals(6, snapshot.cursorContextEndRow)
        assertEquals(2, snapshot.cursorContextCursorIndex)
        assertEquals(listOf("", "", "bottom"), snapshot.cursorContextLines)
        assertEquals(3, snapshot.firstNonEmptyRow)
        assertEquals(6, snapshot.lastNonEmptyRow)
        assertEquals("   middle", snapshot.firstNonEmptyLine)
        assertEquals("bottom", snapshot.lastNonEmptyLine)
        assertEquals(4, snapshot.contentHeight)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsCursorLineWhitespaceBounds() {
        val bytes = "\u001B[3;4Hmiddle\u001B[3;10H".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals("   middle", snapshot.cursorLine)
        assertEquals(false, snapshot.cursorLineIsBlank)
        assertEquals(9, snapshot.cursorLineLength)
        assertEquals(3, snapshot.cursorLineLeadingBlankCount)
        assertEquals(0, snapshot.cursorLineTrailingBlankCount)
        assertEquals(4, snapshot.cursorLineFirstNonBlankColumn)
        assertEquals(9, snapshot.cursorLineLastNonBlankColumn)
        assertEquals(6, snapshot.cursorDistanceFromLineTextStart)
        assertEquals(0, snapshot.cursorDistanceFromLineTextEnd)
        assertEquals("TEXT_END", snapshot.cursorLineCursorRegion)
        assertCursorRegionFlags(snapshot, atLineTextEnd = true)
        assertEquals("middle", snapshot.cursorLineTextContent)
        assertEquals(6, snapshot.cursorLineTextLength)
        assertEquals("   ", snapshot.cursorLineTextLeadingPadding)
        assertEquals("", snapshot.cursorLineTextTrailingPadding)
        assertEquals(3, snapshot.cursorLineTextLeadingPaddingLength)
        assertEquals(0, snapshot.cursorLineTextTrailingPaddingLength)
        assertEquals(snapshot.cursorLine, snapshot.cursorLineTextLeadingPadding + snapshot.cursorLineTextContent + snapshot.cursorLineTextTrailingPadding)
        assertEquals(null, snapshot.cursorLineCharAtCursor)
        assertEquals("middle", snapshot.cursorLineTextBeforeCursorInText)
        assertEquals(null, snapshot.cursorLineTextAtCursor)
        assertEquals("", snapshot.cursorLineTextAfterCursorInText)
        assertEquals(6, snapshot.cursorLineTextCursorOffset)
        assertEquals(0, snapshot.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TRAILING_PADDING", snapshot.cursorLineTextCursorSegment)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(0, snapshot.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(9, snapshot.cursorTextColumn)
        assertEquals(0, snapshot.cursorDistanceFromLineEnd)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsCursorLineRegionBeforeAndAfterText() {
        val beforeText = renderInteractiveTerminalSnapshot("\u001B[3;4Hmiddle\u001B[3;2H".toByteArray(Charsets.UTF_8), columns = 20, rows = 6)
        assertEquals("BEFORE_TEXT", beforeText.cursorLineCursorRegion)
        assertCursorRegionFlags(beforeText, beforeLineText = true)
        assertEquals("middle", beforeText.cursorLineTextContent)
        assertEquals(6, beforeText.cursorLineTextLength)
        assertEquals("   ", beforeText.cursorLineTextLeadingPadding)
        assertEquals("", beforeText.cursorLineTextTrailingPadding)
        assertEquals(3, beforeText.cursorLineTextLeadingPaddingLength)
        assertEquals(0, beforeText.cursorLineTextTrailingPaddingLength)
        assertEquals(beforeText.cursorLine, beforeText.cursorLineTextLeadingPadding + beforeText.cursorLineTextContent + beforeText.cursorLineTextTrailingPadding)
        assertEquals("", beforeText.cursorLineTextBeforeCursorInText)
        assertEquals(" ", beforeText.cursorLineCharAtCursor)
        assertEquals("m", beforeText.cursorLineTextAtCursor)
        assertEquals("middle", beforeText.cursorLineTextAfterCursorInText)
        assertEquals(0, beforeText.cursorLineTextCursorOffset)
        assertEquals(6, beforeText.cursorLineTextCursorOffsetFromEnd)
        assertEquals("LEADING_PADDING", beforeText.cursorLineTextCursorSegment)
        assertEquals(1, beforeText.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, beforeText.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(-2, beforeText.cursorDistanceFromLineTextStart)
        assertEquals(-8, beforeText.cursorDistanceFromLineTextEnd)

        val atTextStart = renderInteractiveTerminalSnapshot("\u001B[3;4Hmiddle\u001B[3;4H".toByteArray(Charsets.UTF_8), columns = 20, rows = 6)
        assertEquals("TEXT_START", atTextStart.cursorLineCursorRegion)
        assertCursorRegionFlags(atTextStart, atLineTextStart = true)
        assertEquals("middle", atTextStart.cursorLineTextContent)
        assertEquals(6, atTextStart.cursorLineTextLength)
        assertEquals("", atTextStart.cursorLineTextBeforeCursorInText)
        assertEquals("m", atTextStart.cursorLineCharAtCursor)
        assertEquals("m", atTextStart.cursorLineTextAtCursor)
        assertEquals("middle", atTextStart.cursorLineTextAfterCursorInText)
        assertEquals(0, atTextStart.cursorLineTextCursorOffset)
        assertEquals(6, atTextStart.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TEXT_CONTENT", atTextStart.cursorLineTextCursorSegment)
        assertEquals(null, atTextStart.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, atTextStart.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(0, atTextStart.cursorDistanceFromLineTextStart)
        assertEquals(-6, atTextStart.cursorDistanceFromLineTextEnd)

        val afterText = renderInteractiveTerminalSnapshot("\u001B[3;4Hmiddle\u001B[3;12H".toByteArray(Charsets.UTF_8), columns = 20, rows = 6)
        assertEquals("AFTER_TEXT", afterText.cursorLineCursorRegion)
        assertCursorRegionFlags(afterText, afterLineText = true)
        assertEquals("middle", afterText.cursorLineTextContent)
        assertEquals(6, afterText.cursorLineTextLength)
        assertEquals("middle", afterText.cursorLineTextBeforeCursorInText)
        assertEquals(null, afterText.cursorLineCharAtCursor)
        assertEquals(null, afterText.cursorLineTextAtCursor)
        assertEquals("", afterText.cursorLineTextAfterCursorInText)
        assertEquals(6, afterText.cursorLineTextCursorOffset)
        assertEquals(0, afterText.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TRAILING_PADDING", afterText.cursorLineTextCursorSegment)
        assertEquals(null, afterText.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(0, afterText.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals(8, afterText.cursorDistanceFromLineTextStart)
        assertEquals(2, afterText.cursorDistanceFromLineTextEnd)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsSemanticCursorLineTextSegmentsInsideIndentedText() {
        val snapshot = renderInteractiveTerminalSnapshot("\u001B[3;4Hmiddle\u001B[3;7H".toByteArray(Charsets.UTF_8), columns = 20, rows = 6)

        assertEquals("   middle", snapshot.cursorLine)
        assertEquals("INSIDE_TEXT", snapshot.cursorLineCursorRegion)
        assertCursorRegionFlags(snapshot, insideLineText = true)
        assertEquals("middle", snapshot.cursorLineTextContent)
        assertEquals(6, snapshot.cursorLineTextLength)
        assertEquals("mid", snapshot.cursorLineTextBeforeCursorInText)
        assertEquals("d", snapshot.cursorLineTextAtCursor)
        assertEquals("dle", snapshot.cursorLineTextAfterCursorInText)
        assertEquals(3, snapshot.cursorLineTextCursorOffset)
        assertEquals(3, snapshot.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TEXT_CONTENT", snapshot.cursorLineTextCursorSegment)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, snapshot.cursorLineTextTrailingPaddingCursorOffset)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsCursorLineTextPaddingSegments() {
        val snapshot = renderInteractiveTerminalSnapshot("\u001B[3;4Hmiddle  \u001B[3;7H".toByteArray(Charsets.UTF_8), columns = 20, rows = 6)

        assertEquals("   middle", snapshot.cursorLine)
        assertEquals("middle", snapshot.cursorLineTextContent)
        assertEquals("   ", snapshot.cursorLineTextLeadingPadding)
        assertEquals("", snapshot.cursorLineTextTrailingPadding)
        assertEquals(3, snapshot.cursorLineTextLeadingPaddingLength)
        assertEquals(0, snapshot.cursorLineTextTrailingPaddingLength)
        assertEquals(snapshot.cursorLine, snapshot.cursorLineTextLeadingPadding + snapshot.cursorLineTextContent + snapshot.cursorLineTextTrailingPadding)
        assertEquals(6, snapshot.cursorLineTextLength)
        assertEquals(3, snapshot.cursorLineTextCursorOffset)
        assertEquals(3, snapshot.cursorLineTextCursorOffsetFromEnd)
        assertEquals("TEXT_CONTENT", snapshot.cursorLineTextCursorSegment)
        assertEquals(null, snapshot.cursorLineTextLeadingPaddingCursorOffset)
        assertEquals(null, snapshot.cursorLineTextTrailingPaddingCursorOffset)
        assertEquals("INSIDE_TEXT", snapshot.cursorLineCursorRegion)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsCursorOutsideVisibleContent() {
        val bytes = "\u001B[3;1Hcontent\u001B[1;1H".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(1, snapshot.cursorRow)
        assertEquals(0, snapshot.rowsAboveCursor)
        assertEquals(5, snapshot.rowsBelowCursor)
        assertEquals(3, snapshot.firstNonEmptyRow)
        assertEquals(-2, snapshot.cursorDistanceFromContentTop)
        assertEquals(2, snapshot.cursorDistanceFromContentBottom)
        assertEquals(false, snapshot.cursorInVisibleContent)
        assertEquals(0, snapshot.cursorLineLeadingBlankCount)
        assertEquals(0, snapshot.cursorLineTrailingBlankCount)
        assertEquals(null, snapshot.cursorVisibleContentRow)
        assertEquals(null, snapshot.cursorVisibleContentLine)
    }


    private fun assertCursorRegionFlags(
        snapshot: InteractiveTerminalSnapshot,
        onBlankLine: Boolean = false,
        beforeLineText: Boolean = false,
        atLineTextStart: Boolean = false,
        insideLineText: Boolean = false,
        atLineTextEnd: Boolean = false,
        afterLineText: Boolean = false
    ) {
        assertEquals(onBlankLine, snapshot.cursorOnBlankLine)
        assertEquals(beforeLineText, snapshot.cursorBeforeLineText)
        assertEquals(atLineTextStart, snapshot.cursorAtLineTextStart)
        assertEquals(insideLineText, snapshot.cursorInsideLineText)
        assertEquals(atLineTextEnd, snapshot.cursorAtLineTextEnd)
        assertEquals(afterLineText, snapshot.cursorAfterLineText)
        assertEquals(1, listOf(
            snapshot.cursorOnBlankLine,
            snapshot.cursorBeforeLineText,
            snapshot.cursorAtLineTextStart,
            snapshot.cursorInsideLineText,
            snapshot.cursorAtLineTextEnd,
            snapshot.cursorAfterLineText
        ).count { it })
    }

}
