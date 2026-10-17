package me.rerere.rikkahub.data.container

import org.junit.Assert.*
import org.junit.Test

class TerminalDisplayPolicyTest {
    @Test fun displayOnlyDirectExplicitRegularCommandsAreEligible() {
        for (command in listOf("pi --tui-mode regular", "pi --tui-mode=regular --resume",
            "/usr/local/bin/pi --continue --tui-mode regular", "pi\t--tui-mode\tregular")) {
            assertTrue(command, isExplicitPiRegularCommand(command))
        }
        for (command in listOf("pi", "pi --resume", "pi --tui-mode fullscreen", "echo pi --tui-mode regular",
            "sh -c 'pi --tui-mode regular'", "pi --tui-mode regular; vim", "pi --tui-mode regular\nvim",
            "pi --tui-mode regular --tui-mode fullscreen", "PI_X=1 pi --tui-mode regular",
            "pi --tui-mode regular --model unknown", "pi --tui-mode regular >file", "pi --tui-mode")) {
            assertFalse(command, isExplicitPiRegularCommand(command))
        }
    }

    @Test fun displayChoiceNeverReinterpretsInputOrBypassesCompatibilityGates() {
        val command = "pi --tui-mode regular --resume"
        assertEquals(PtyMode.RAW, inferPtyMode(command))
        for (mode in TerminalRenderMode.entries) {
            assertEquals(mode == TerminalRenderMode.REGULAR_HISTORY,
                terminalRegularHistoryAllowed(mode, command, false, false, true, false, false))
        }
        for (gate in 0..4) {
            assertFalse(terminalRegularHistoryAllowed(TerminalRenderMode.REGULAR_HISTORY, command,
                gate == 0, gate == 1, gate != 2, gate == 3, gate == 4))
        }
        assertTrue(terminalCursorOverlayAllowed(TerminalRenderMode.CURSOR_OVERLAY, true, false))
        assertFalse(terminalCursorOverlayAllowed(TerminalRenderMode.CURSOR_OVERLAY, false, false))
        assertFalse(terminalCursorOverlayAllowed(TerminalRenderMode.CURSOR_OVERLAY, true, true))
        assertEquals(TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.DEFAULT)
    }
}
