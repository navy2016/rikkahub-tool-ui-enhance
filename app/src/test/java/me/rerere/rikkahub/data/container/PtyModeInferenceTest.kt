package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyModeInferenceTest {
    @Test
    fun autoKeepsSimpleShellCooked() {
        assertEquals(PtyMode.COOKED, inferPtyMode("sh", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("bash -l", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("python3", PtyMode.AUTO))
    }

    @Test
    fun autoUsesRawForClaudeAndTuiCommands() {
        assertEquals(PtyMode.RAW, inferPtyMode("claude", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("claude --dangerously-skip-permissions", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("npx claude-code", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("npm exec claude", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("vim README.md", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("tmux new -A -s main", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("omp", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("omp --help", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("pi", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("pi --model test", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("bunx omp", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("bunx pi", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("npm exec omp", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("npm exec pi", PtyMode.AUTO))
    }

    @Test
    fun ompAndPiTokensDoNotMatchUnrelatedCommands() {
        assertEquals(PtyMode.COOKED, inferPtyMode("pip install requests", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("ping example.com", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("python script.py", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("compile source", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("prompt-tool", PtyMode.AUTO))
    }

    @Test
    fun tuiTokenInArgumentsDoesNotForceRawMode() {
        assertEquals(PtyMode.COOKED, inferPtyMode("echo pi", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("apk add pi", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("printf '%s' omp", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("echo ok; pi --model test", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("/usr/local/bin/omp", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("TERM=xterm-256color omp", PtyMode.AUTO))
    }

    @Test
    fun customTuiCommandsUseRawWithoutMatchingUnrelatedCommands() {
        val custom = "lazygit\nbtop, yazi\ninvalid token;evil"
        assertEquals(PtyMode.RAW, inferPtyMode("lazygit", PtyMode.AUTO, custom))
        assertEquals(PtyMode.RAW, inferPtyMode("bunx yazi", PtyMode.AUTO, custom))
        assertEquals(PtyMode.COOKED, inferPtyMode("lazy", PtyMode.AUTO, custom))
        assertEquals(PtyMode.COOKED, inferPtyMode("evil", PtyMode.AUTO, "invalid token;evil"))
    }

    @Test
    fun fullGridMatcherUsesOnlyTheUserConfiguredList() {
        assertFalse(isConfiguredTerminalCommand("vim README.md", ""))
        assertFalse(isConfiguredTerminalCommand("tmux new", "lazygit\nyazi"))
        assertTrue(isConfiguredTerminalCommand("vim README.md", "vim"))
        assertTrue(isConfiguredTerminalCommand("/usr/bin/tmux new", "tmux"))
        assertTrue(isConfiguredTerminalCommand("bunx yazi", "yazi"))
        assertFalse(isConfiguredTerminalCommand("echo yazi", "yazi"))
    }

    @Test
    fun explicitModeOverridesAuto() {
        assertEquals(PtyMode.COOKED, inferPtyMode("claude", PtyMode.COOKED))
        assertEquals(PtyMode.RAW, inferPtyMode("sh", PtyMode.RAW))
    }
}
