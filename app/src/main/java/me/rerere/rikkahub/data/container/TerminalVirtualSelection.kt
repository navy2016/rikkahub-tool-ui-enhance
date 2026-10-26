package me.rerere.rikkahub.data.container

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import java.util.regex.Pattern
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

/** A semantic endpoint, never a UI node, pixel estimate, selected-text copy or history-sized state. */
internal data class TerminalSelectionPoint(
    val id: Long,
    val offset: Int,
    val index: Int,
    val sequence: Long?,
    val historyGeneration: Long,
    val screenGeneration: Long?,
    val text: String,
)

internal data class TerminalSelectionRange(val start: TerminalSelectionPoint, val end: TerminalSelectionPoint) {
    val reversed: Boolean get() = start.index > end.index || (start.index == end.index && start.offset > end.offset)
    val first: TerminalSelectionPoint get() = if (reversed) end else start
    val last: TerminalSelectionPoint get() = if (reversed) start else end
    val collapsed: Boolean get() = start.id == end.id && start.offset == end.offset
}

/**
 * Full virtual selection owns two semantic endpoints and at most80 current physical text keys.
 * The current immutable document supplies OFFSCREEN rows for copy; recycling a UI row never
 * truncates the range. Real source replacement/trim of an endpoint or changed selected text
 * invalidates selection, just as an edited selectable does in the original SelectionContainer.
 */
internal class TerminalVirtualSelection {
    var range by mutableStateOf<TerminalSelectionRange?>(null)
        private set
    private var frame: TerminalEmulator.RenderFrame? = null
    private var screenText: List<AnnotatedString> = emptyList()
    private var owner: Any? = null
    private var selectedScreen = emptyMap<Long, String>()
    private var historyRenderRevision = 0L

    private fun text(index: Int): AnnotatedString {
        val current = checkNotNull(frame)
        return if (index < current.historyCount) current.rows[index].text
            else screenText[index - current.historyCount]
    }

    /** The provider is UI-lifetime, current bounded physical rows ONLY; it must not capture a previous frame. */
    fun bind(next: TerminalEmulator.RenderFrame, physicalText: (Int) -> AnnotatedString) {
        val old = frame
        val nextOwner = next.ownedRows()?.history?.owner
        frame = next
        // Freeze the bounded displayed screen along with this frame. Later observable row-state
        // writes cannot change a COPY between publication and its selection bind SideEffect.
        screenText = List(next.screenLineIds.size, physicalText)
        val selected = range
        if (selected != null) {
            val replaced = old != null && (old.isAlternateScreen != next.isAlternateScreen ||
                (owner != null && nextOwner !== owner) || selected.first.historyGeneration != next.historyGeneration ||
                (selected.first.index < old.historyCount && (historyRenderRevision != next.historyRenderRevision ||
                    (nextOwner == null && old.rows !== next.rows))))
            val start = if (replaced) null else resolve(selected.start)
            val end = if (replaced) null else resolve(selected.end)
            val physicalChanged = selectedScreen.any { (id, before) ->
                val at = next.screenLineIds.indexOf(id)
                // A former screen row can archive unchanged. Validate that one ID only on handoff.
                val index = if (at >= 0) next.historyCount + at else next.historyLineIds.indexOf(id)
                index < 0 || text(index).text != before
            }
            if (start == null || end == null || physicalChanged) clear() else {
                range = TerminalSelectionRange(start, end)
                capturePhysicalText()
            }
        }
        owner = nextOwner
        historyRenderRevision = next.historyRenderRevision
    }

    private fun point(index: Int, offset: Int, forward: Boolean): TerminalSelectionPoint {
        val current = checkNotNull(frame)
        require(index in current.rows.indices)
        val value = text(index).text
        val id = if (index < current.historyCount) current.historyLineIds[index]
            else current.screenLineIds[index - current.historyCount]
        return TerminalSelectionPoint(id, terminalSelectionBoundary(value, offset, forward), index,
            current.historyStartSequence?.let { it + index }?.takeIf { index < current.historyCount },
            current.historyGeneration, current.screenGeneration.takeIf { index >= current.historyCount }, value)
    }

    private fun resolve(old: TerminalSelectionPoint): TerminalSelectionPoint? {
        val current = checkNotNull(frame)
        if (old.historyGeneration != current.historyGeneration) return null
        val ordinal = old.sequence?.let { sequence -> current.historyStartSequence?.let { first -> sequence - first } }
        val sequenceIndex = ordinal?.takeIf { it >= 0 && it < current.historyCount }?.toInt()
        val index = when {
            sequenceIndex != null && current.historyLineIds[sequenceIndex] == old.id -> sequenceIndex
            old.index in current.rows.indices && rowId(current, old.index) == old.id -> old.index
            else -> {
                val screen = current.screenLineIds.indexOf(old.id)
                if (screen >= 0) current.historyCount + screen else current.historyLineIds.indexOf(old.id)
            }
        }
        if (index < 0 || text(index).text != old.text) return null
        if (index >= current.historyCount && old.screenGeneration != current.screenGeneration) return null
        return point(index, old.offset, false)
    }

    private fun capturePhysicalText() {
        val current = frame ?: return
        val selected = range ?: return
        selectedScreen = buildMap {
            for (index in maxOf(selected.first.index, current.historyCount)..selected.last.index) {
                put(rowId(current, index), text(index).text)
            }
        }
    }

    fun select(index: Int, start: Int, end: Int) {
        range = TerminalSelectionRange(point(index, start, false), point(index, end, true))
        capturePhysicalText()
    }

    fun move(isStart: Boolean, index: Int, offset: Int) {
        val selected = range ?: return
        val fixed = if (isStart) selected.end else selected.start
        val forward = index > fixed.index || (index == fixed.index && offset >= fixed.offset)
        val moved = point(index, offset, forward)
        range = if (isStart) selected.copy(start = moved) else selected.copy(end = moved)
        capturePhysicalText()
    }

    fun selectAll() {
        val current = frame ?: return
        if (current.rows.isEmpty()) return
        range = TerminalSelectionRange(point(0, 0, false), point(current.rows.lastIndex, text(current.rows.lastIndex).length, true))
        capturePhysicalText()
    }

    fun rowRange(index: Int): TextRange? {
        val selected = range?.takeUnless { it.collapsed } ?: return null
        if (index !in (frame?.rows?.indices ?: IntRange.EMPTY)) return null
        if (index !in selected.first.index..selected.last.index) return null
        val from = if (index == selected.first.index) selected.first.offset else 0
        val to = if (index == selected.last.index) selected.last.offset else text(index).length
        return TextRange(from, to)
    }

    fun copy(): AnnotatedString? {
        val selected = range?.takeUnless { it.collapsed } ?: return null
        return buildAnnotatedString {
            for (index in selected.first.index..selected.last.index) {
                val offsets = checkNotNull(rowRange(index))
                append(text(index), offsets.min, offsets.max)
                if (index != selected.last.index) append('\n')
            }
        }
    }

    fun clear() { range = null; selectedScreen = emptyMap() }
    fun dispose() { clear(); frame = null; screenText = emptyList(); owner = null }
    val retainedPhysicalTextKeys: Int get() = selectedScreen.size

    private fun rowId(frame: TerminalEmulator.RenderFrame, index: Int): Long =
        if (index < frame.historyCount) frame.historyLineIds[index] else frame.screenLineIds[index - frame.historyCount]
}

private val terminalGraphemePattern = Pattern.compile("\\X")

/** Extended grapheme boundaries: combining marks, ZWJ emoji, modifiers and flag pairs stay intact. */
internal fun terminalSelectionBoundary(text: String, offset: Int, forward: Boolean): Int {
    val at = offset.coerceIn(0, text.length)
    if (at == 0 || at == text.length) return at
    val matches = terminalGraphemePattern.matcher(text)
    while (matches.find()) {
        if (at == matches.start() || at == matches.end()) return at
        if (at in (matches.start() + 1) until matches.end()) return if (forward) matches.end() else matches.start()
    }
    return at
}
