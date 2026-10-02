package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRenderPreferencesTest {
    @Test
    fun missingCorruptAndUnknownPreferencesKeepTheCurrentDefault() {
        for (raw in listOf("", "not json", "[]", "{\"other\":\"eager\"}")) {
            assertEquals(TerminalRenderMode.DEFAULT, terminalRendererForCommand(raw, "bash"))
        }
        assertEquals(TerminalRenderMode.DEFAULT, TerminalRenderMode.fromId("future-renderer"))
    }

    @Test
    fun rendererChoiceIsNormalizedAndIsolatedPerCommand() {
        var raw = updatedTerminalRenderPreferences("", "  bash  ", TerminalRenderMode.FLAT)
        raw = updatedTerminalRenderPreferences(raw, "vim", TerminalRenderMode.CHUNKED)
        assertEquals(TerminalRenderMode.FLAT, terminalRendererForCommand(raw, "bash"))
        assertEquals(TerminalRenderMode.FLAT, terminalRendererForCommand(raw, " bash\n"))
        assertEquals(TerminalRenderMode.CHUNKED, terminalRendererForCommand(raw, "vim"))
        assertEquals(TerminalRenderMode.DEFAULT, terminalRendererForCommand(raw, "tmux"))
        raw = updatedTerminalRenderPreferences(raw, "bash", TerminalRenderMode.DEFAULT)
        assertEquals(TerminalRenderMode.DEFAULT, terminalRendererForCommand(raw, "bash"))
        assertEquals(TerminalRenderMode.CHUNKED, terminalRendererForCommand(raw, "vim"))
    }

    @Test
    fun rendererPreferenceMapStaysBounded() {
        var raw = ""
        repeat(80) { raw = updatedTerminalRenderPreferences(raw, "command-$it", TerminalRenderMode.FLAT) }
        assertEquals(TerminalRenderMode.DEFAULT, terminalRendererForCommand(raw, "command-0"))
        assertEquals(TerminalRenderMode.FLAT, terminalRendererForCommand(raw, "command-79"))
        assertEquals(50, Regex("eager").findAll(raw).count())
    }

    @Test
    fun gridFallbackDoesNotMutateThePreferredMode() {
        for (mode in TerminalRenderMode.entries) {
            assertEquals(mode, effectiveTerminalRenderMode(mode, hasHistoryChunks = true))
            assertEquals(
                if (mode == TerminalRenderMode.VIRTUAL_HISTORY) mode else TerminalRenderMode.FLAT,
                effectiveTerminalRenderMode(mode, hasHistoryChunks = false),
            )
            assertEquals(TerminalRenderMode.FLAT, effectiveTerminalRenderMode(
                mode, hasHistoryChunks = false, virtualHistoryAllowed = false,
            ))
        }
    }

    @Test
    fun oneTimeStatusMigrationPreservesOrderLabelsAndNeverAddsKeys() {
        val raw = """[{"id":"RAW","label":"输入"},{"id":"KEYS","label":"按键"}]"""
        val migrated = addTerminalRendererStatusItem(raw)
        assertTrue(migrated.indexOf("RAW") < migrated.indexOf("KEYS"))
        assertTrue(migrated.contains("输入"))
        assertTrue(migrated.contains("按键"))
        assertTrue(migrated.endsWith("{\"id\":\"RENDER\"}]"))
        assertEquals(migrated, addTerminalRendererStatusItem(migrated))
        assertFalse(migrated.contains("CTRL"))
        assertEquals("", addTerminalRendererStatusItem(""))
        assertEquals("broken", addTerminalRendererStatusItem("broken"))
    }
}
