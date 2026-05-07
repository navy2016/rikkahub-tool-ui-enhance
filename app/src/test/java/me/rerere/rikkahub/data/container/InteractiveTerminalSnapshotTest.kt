package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveTerminalSnapshotTest {
    @Test
    fun renderInteractiveTerminalSnapshotConvertsAnsiOutputToPlainScreen() {
        val bytes = "hello\u001B[2J\u001B[Htop\u001B[2;5Hmenu".toByteArray(Charsets.UTF_8)

        val snapshot = renderInteractiveTerminalSnapshot(bytes, columns = 12, rows = 6)

        assertEquals(12, snapshot.columns)
        assertEquals(6, snapshot.rows)
        assertEquals("", snapshot.modes)
        val lines = snapshot.screen.lines()
        assertEquals("top", lines[0])
        assertEquals("    menu", lines[1])
        assertEquals(6, lines.size)
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
}
