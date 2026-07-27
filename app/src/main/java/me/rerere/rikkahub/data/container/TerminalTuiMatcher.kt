package me.rerere.rikkahub.data.container

private val BUILTIN_TUI_COMMANDS = setOf(
    "claude", "claude-code", "codex", "opencode", "opencode-ai", "omp", "pi",
    "vim", "nvim", "vi", "nano", "emacs",
    "tmux", "screen", "ssh", "less", "more", "top", "htop", "fzf",
)

private val CUSTOM_TUI_TOKEN_REGEX = Regex("^[A-Za-z0-9._+-]{1,80}$")
private val SHELL_COMMAND_SEPARATOR = Regex("(?:&&|\\|\\||[;&|()])")
private val SHELL_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

internal fun parseCustomTuiCommands(raw: String): Set<String> {
    if (raw.isBlank()) return emptySet()
    return raw
        .split('\n', '\r', ',', ' ', '\t')
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && CUSTOM_TUI_TOKEN_REGEX.matches(it) }
        .distinct()
        .take(100)
        .toSet()
}

internal fun terminalTuiCommands(customRaw: String = ""): Set<String> = BUILTIN_TUI_COMMANDS + parseCustomTuiCommands(customRaw)

private fun commandSegments(command: String): Sequence<List<String>> = sequence {
    SHELL_COMMAND_SEPARATOR.split(command.lowercase()).forEach { segment ->
        val tokens = segment.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (tokens.firstOrNull()?.matches(SHELL_ASSIGNMENT) == true) tokens.removeAt(0)
        if (tokens.isNotEmpty()) yield(tokens)
    }
}

private fun isTuiExecutable(token: String, commands: Set<String>): Boolean =
    token.substringAfterLast('/').trim().lowercase() in commands

internal fun isTuiCommand(command: String, customRaw: String = ""): Boolean {
    val commands = terminalTuiCommands(customRaw)
    return commandSegments(command).any { tokens ->
        when (tokens.firstOrNull()) {
            "npx", "bunx" -> tokens.drop(1).firstOrNull()?.let { isTuiExecutable(it, commands) } == true
            "npm" -> tokens.getOrNull(1) == "exec" &&
                tokens.drop(2).firstOrNull { !it.startsWith('-') }?.let { isTuiExecutable(it, commands) } == true
            "pnpm" -> tokens.getOrNull(1) == "dlx" &&
                tokens.drop(2).firstOrNull { !it.startsWith('-') }?.let { isTuiExecutable(it, commands) } == true
            else -> isTuiExecutable(tokens.first(), commands)
        }
    }
}

internal fun inferPtyMode(
    command: String,
    requested: PtyMode = PtyMode.AUTO,
    customTuiCommandsRaw: String = ""
): PtyMode {
    if (requested != PtyMode.AUTO) return requested
    return if (isTuiCommand(command, customTuiCommandsRaw)) PtyMode.RAW else PtyMode.COOKED
}
