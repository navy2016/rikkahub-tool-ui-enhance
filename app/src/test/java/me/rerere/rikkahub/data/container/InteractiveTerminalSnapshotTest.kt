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
        assertEquals(listOf(1, 2), snapshot.nonEmptyRows)
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
        assertEquals(true, snapshot.isEmpty)
    }
}
