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
        assertEquals("prompt> hello ", snapshot.cursorLineTextBeforeCursor)
        assertEquals("world", snapshot.cursorLineTextAfterCursor)
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
        assertEquals(null, snapshot.cursorVisibleContentRow)
        assertEquals(null, snapshot.cursorVisibleContentLine)
    }
}
