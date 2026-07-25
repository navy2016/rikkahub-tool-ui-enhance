package me.rerere.rikkahub.data.container

private val BUILTIN_TUI_COMMANDS = setOf(
    "claude", "claude-code", "codex", "opencode", "opencode-ai", "omp", "pi",
    "vim", "nvim", "vi", "nano", "emacs",
    "tmux", "screen", "ssh", "less", "more", "top", "htop", "fzf",
)

private val CUSTOM_TUI_TOKEN_REGEX = Regex("^[A-Za-z0-9._+-]{1,80}$")

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

internal fun isTuiCommand(command: String, customRaw: String = ""): Boolean {
    val normalized = command.lowercase()
    return terminalTuiCommands(customRaw).any { token ->
        Regex("""(^|[\s;&|()])""" + Regex.escape(token) + """([\s;&|()]|$)""").containsMatchIn(normalized)
    }
}

internal fun inferPtyMode(command: String, requested: PtyMode = PtyMode.AUTO, customTuiCommandsRaw: String = ""): PtyMode {
    if (requested != PtyMode.AUTO) return requested
    val normalized = command.lowercase()
    val cliTokens = terminalTuiCommands(customTuiCommandsRaw).joinToString("|") { Regex.escape(it) }
    val rawRegexes = listOf(
        Regex("""(^|[\s;&|()])(?:$cliTokens)([\s;&|()]|$)"""),
        Regex("""(^|[\s;&|()])(?:npx|pnpm\s+dlx|bunx|npm\s+exec)\s+[^;&|()]*?(?:$cliTokens)([\s;&|()]|$)""")
    )
    return if (rawRegexes.any { it.containsMatchIn(normalized) }) PtyMode.RAW else PtyMode.COOKED
}
