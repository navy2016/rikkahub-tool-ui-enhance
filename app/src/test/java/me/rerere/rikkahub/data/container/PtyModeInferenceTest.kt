package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
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
        assertEquals(PtyMode.RAW, inferPtyMode("pi", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("pi --model test", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("bunx pi", PtyMode.AUTO))
        assertEquals(PtyMode.RAW, inferPtyMode("npm exec pi", PtyMode.AUTO))
    }

    @Test
    fun piTokenDoesNotMatchUnrelatedCommands() {
        assertEquals(PtyMode.COOKED, inferPtyMode("pip install requests", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("ping example.com", PtyMode.AUTO))
        assertEquals(PtyMode.COOKED, inferPtyMode("python script.py", PtyMode.AUTO))
    }

    @Test
    fun explicitModeOverridesAuto() {
        assertEquals(PtyMode.COOKED, inferPtyMode("claude", PtyMode.COOKED))
        assertEquals(PtyMode.RAW, inferPtyMode("sh", PtyMode.RAW))
    }
}
