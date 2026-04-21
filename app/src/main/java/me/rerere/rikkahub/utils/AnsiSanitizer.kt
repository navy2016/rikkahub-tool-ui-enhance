package me.rerere.rikkahub.utils

/**
 * Lightweight ANSI/control-sequence sanitizer for displaying shell output in Compose Text.
 * This is not a full terminal emulator; it removes common VT/xterm sequences and keeps
 * readable text for logs, AI reads, and the fallback terminal UI.
 */
object AnsiSanitizer {
    private val ESC = "\u001B"
    private val oscRegex = Regex("$ESC\\][^\u0007$ESC]*(?:\u0007|$ESC\\\\)")
    private val csiRegex = Regex("$ESC\\[[0-?]*[ -/]*[@-~]")
    private val escRegex = Regex("$ESC[()#%*+\\-./]?[0-~]")
    private val controlRegex = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001A\u001C-\u001F\u007F]")

    fun clean(input: String): String {
        if (input.isEmpty()) return input
        val withoutAnsi = input
            .replace(oscRegex, "")
            .replace(csiRegex, "")
            .replace(escRegex, "")
            .replace(ESC, "")
            .replace(controlRegex, "")

        return normalizeCarriageReturns(withoutAnsi)
    }

    private fun normalizeCarriageReturns(input: String): String {
        val lines = mutableListOf(StringBuilder())
        input.forEach { ch ->
            when (ch) {
                '\r' -> lines[lines.lastIndex].clear()
                '\n' -> lines.add(StringBuilder())
                else -> lines[lines.lastIndex].append(ch)
            }
        }
        return lines.joinToString("\n") { it.toString() }
    }
}
