package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

private val REGULAR_COMMAND_CHARACTERS = Regex("^[A-Za-z0-9_./=+\\- \\t]+$")
private val REGULAR_COMMAND_SEPARATOR = Regex("[ \\t]+")

/** Narrow, explicit launch evidence; do not parse shell expressions or infer main-screen safety. */
internal fun isExplicitPiRegularCommand(command: String): Boolean {
    val text = command.trim()
    // Only direct executable + known benign arguments. Shell wrappers, quoting, redirection,
    // environment interpolation and ambiguous/duplicate mode arguments keep compatibility mode.
    if (!REGULAR_COMMAND_CHARACTERS.matches(text)) return false
    val parts = text.split(REGULAR_COMMAND_SEPARATOR).filter { it.isNotEmpty() }
    if (parts.firstOrNull()?.substringAfterLast('/') != "pi") return false
    var mode: String? = null
    var index = 1
    while (index < parts.size) {
        val arg = parts[index++]
        when {
            arg == "--tui-mode" -> {
                if (mode != null || index >= parts.size) return false
                mode = parts[index++]
            }
            arg.startsWith("--tui-mode=") -> {
                if (mode != null) return false
                mode = arg.substringAfter('=')
            }
            arg in setOf("--resume", "--continue", "-r", "-c") -> Unit
            else -> return false
        }
    }
    return mode == "regular"
}

internal fun terminalRegularHistoryAllowed(
    mode: TerminalRenderMode, command: String, alternate: Boolean, fullGrid: Boolean,
    pan: Boolean, selection: Boolean, mouseTracking: Boolean,
): Boolean = mode == TerminalRenderMode.REGULAR_HISTORY && isExplicitPiRegularCommand(command) &&
    !alternate && !fullGrid && pan && !selection && !mouseTracking

internal fun terminalCursorOverlayAllowed(mode: TerminalRenderMode, pan: Boolean, selection: Boolean): Boolean =
    mode == TerminalRenderMode.CURSOR_OVERLAY && pan && !selection

/** Opt-in grouping of owned archival history only; does NOT reinterpret the command or TUI screen policy. */
internal fun terminalTuiHistoryAllowed(
    mode: TerminalRenderMode,
    frame: TerminalEmulator.RenderFrame,
    usesTuiViewport: Boolean,
    preservePhysicalGrid: Boolean,
    panEnabled: Boolean,
    selectionMode: Boolean,
): Boolean = mode.isTuiHistory && usesTuiViewport && !preservePhysicalGrid &&
    !frame.isAlternateScreen && !frame.mouseTracking && panEnabled && !selectionMode &&
    frame.historyCount > 0 && frame.ownedRows()?.includesHistory == true
