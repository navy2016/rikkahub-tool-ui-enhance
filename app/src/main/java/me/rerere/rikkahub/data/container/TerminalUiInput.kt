package me.rerere.rikkahub.data.container

import java.text.BreakIterator
import kotlinx.coroutines.channels.Channel

/** All panel inputs enter synchronously, then one collector forwards them to the session writer. */
internal sealed interface TerminalUiInput {
    data class Text(val value: String, val appendNewline: Boolean = false) : TerminalUiInput
    data class Bytes(val value: ByteArray) : TerminalUiInput
    data class Control(val value: ControlInput) : TerminalUiInput
}

internal class TerminalUiInputQueue {
    private val channel = Channel<TerminalUiInput>(Channel.UNLIMITED)
    fun offer(input: TerminalUiInput): Boolean = channel.trySend(
        if (input is TerminalUiInput.Bytes) TerminalUiInput.Bytes(input.value.copyOf()) else input,
    ).isSuccess
    suspend fun consume(send: suspend (TerminalUiInput) -> Unit) {
        for (input in channel) send(input)
    }
    fun close() { channel.close() }
}

internal data class TerminalTextEdit(val removedGraphemes: Int, val inserted: String, val insertedGraphemes: Int)

/** JVM/Android BreakIterator versions differ on emoji. Merge the terminal's supported extensions. */
internal fun terminalGraphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = 0
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        if (end == text.length || terminalGraphemeBoundary(text, start, end)) {
            result.add(text.substring(start, end))
            start = end
        }
        end = iterator.next()
    }
    return result
}

private fun terminalGraphemeBoundary(text: String, start: Int, boundary: Int): Boolean {
    val next = text.codePointAt(boundary)
    val previous = text.codePointBefore(boundary)
    if (next == 0x200D || previous == 0x200D || next in 0xFE00..0xFE0F ||
        next in 0xE0100..0xE01EF || next in 0x1F3FB..0x1F3FF) return false
    if (Character.getType(next) in setOf(Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) return false
    if (next in 0x1F1E6..0x1F1FF) {
        var cursor = boundary
        var count = 0
        while (cursor > start && text.codePointBefore(cursor) in 0x1F1E6..0x1F1FF) {
            cursor -= Character.charCount(text.codePointBefore(cursor))
            count++
        }
        if (count % 2 == 1) return false
    }
    return true
}

internal fun terminalTextEdit(previous: String, next: String): TerminalTextEdit {
    val before = terminalGraphemes(previous)
    val after = terminalGraphemes(next)
    var common = 0
    var offset = 0
    while (common < before.size && common < after.size && before[common] == after[common]) {
        offset += after[common].length
        common++
    }
    return TerminalTextEdit(before.size - common, next.substring(offset), after.size - common)
}
