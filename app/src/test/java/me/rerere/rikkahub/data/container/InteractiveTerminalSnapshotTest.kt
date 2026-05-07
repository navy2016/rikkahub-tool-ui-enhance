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
        assertEquals(listOf(1, 2), snapshot.nonEmptyRows)
        assertEquals(1, snapshot.firstNonEmptyRow)
        assertEquals(2, snapshot.lastNonEmptyRow)
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
        assertEquals("", snapshot.visibleContent)
        assertEquals(emptyList<String>(), snapshot.visibleContentLines)
        assertEquals(null, snapshot.firstNonEmptyRow)
        assertEquals(null, snapshot.lastNonEmptyRow)
        assertEquals(0, snapshot.contentHeight)
        assertEquals(true, snapshot.isEmpty)
    }

    @Test
    fun renderInteractiveTerminalSnapshotIncludesCursorLineContext() {
        val bytes = "prompt> hello world\u001B[1;15H".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(1, snapshot.cursorRow)
        assertEquals(15, snapshot.cursorColumn)
        assertEquals("prompt> hello world", snapshot.cursorLine)
        assertEquals("prompt> hello ", snapshot.cursorLineTextBeforeCursor)
        assertEquals("world", snapshot.cursorLineTextAfterCursor)
        assertEquals(true, snapshot.cursorInVisibleContent)
        assertEquals(1, snapshot.cursorVisibleContentRow)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsSparseContentBounds() {
        val bytes = "\u001B[3;4Hmiddle\u001B[6;1Hbottom".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(listOf(3, 6), snapshot.nonEmptyRows)
        assertEquals("   middle\n\n\nbottom", snapshot.visibleContent)
        assertEquals(listOf("   middle", "", "", "bottom"), snapshot.visibleContentLines)
        assertEquals(true, snapshot.cursorInVisibleContent)
        assertEquals(4, snapshot.cursorVisibleContentRow)
        assertEquals(3, snapshot.firstNonEmptyRow)
        assertEquals(6, snapshot.lastNonEmptyRow)
        assertEquals(4, snapshot.contentHeight)
    }

    @Test
    fun renderInteractiveTerminalSnapshotReportsCursorOutsideVisibleContent() {
        val bytes = "\u001B[3;1Hcontent\u001B[1;1H".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 20, rows = 6)

        assertEquals(1, snapshot.cursorRow)
        assertEquals(3, snapshot.firstNonEmptyRow)
        assertEquals(false, snapshot.cursorInVisibleContent)
        assertEquals(null, snapshot.cursorVisibleContentRow)
    }
}
