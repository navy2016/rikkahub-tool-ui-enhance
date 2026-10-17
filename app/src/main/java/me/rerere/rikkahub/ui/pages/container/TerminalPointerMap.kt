package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

/** UI-lifetime only. No pointer geometry/coordinates are kept in the session, parser or checkpoints. */
internal class TerminalPointerMap {
    internal class Pass(
        val frame: TerminalEmulator.RenderFrame,
        val rows: List<TerminalRenderedRowState>,
        val cellWidth: Int,
        val metrics: Any,
    ) {
        val ids = frame.screenLineIds.withIndex().associate { it.value to it.index }
    }
    private data class Entry(val owner: Any, val text: AnnotatedString, val metrics: Any, val layout: LayoutCoordinates)
    private val entries = HashMap<Long, Entry>()
    private var pass: Pass? = null
    var inputCoordinates: LayoutCoordinates? = null
    var viewportCoordinates: LayoutCoordinates? = null
    val retainedRows: Int get() = entries.size

    fun bind(next: Pass) {
        pass = next
        entries.keys.retainAll(next.ids.keys)
    }
    fun clear() { pass = null; entries.clear(); viewportCoordinates = null }
    fun register(owner: Any, id: Long, text: AnnotatedString, metrics: Any, layout: LayoutCoordinates) {
        entries[id] = Entry(owner, text, metrics, layout)
    }
    fun release(owner: Any, id: Long) {
        if (entries[id]?.owner === owner) entries.remove(id)
    }

    data class Hit(val row: Int, val column: Int, val pixelX: Int, val pixelY: Int,
        val generation: Long, val columns: Int, val rows: Int)

    fun hit(x: Float, y: Float): Hit? {
        val current = pass ?: return null
        val input = inputCoordinates?.takeIf { it.isAttached } ?: return null
        val viewport = viewportCoordinates?.takeIf { it.isAttached } ?: return null
        val root = input.localToRoot(Offset(x, y))
        if (!root.x.isFinite() || !root.y.isFinite() || !viewport.boundsInRoot().contains(root)) return null
        val frame = current.frame
        val columns = frame.ownedRows()?.columns ?: return null
        val first = frame.screenLineIds.firstOrNull()?.let { entries[it] } ?: return null
        if (!first.layout.isAttached || first.metrics != current.metrics) return null
        if (first.text != current.rows[frame.historyCount].text) return null
        val origin = first.layout.positionInRoot()
        for ((id, row) in current.ids) {
            val entry = entries[id] ?: continue
            if (!entry.layout.isAttached || entry.metrics != current.metrics ||
                entry.text != current.rows[frame.historyCount + row].text) continue
            val position = entry.layout.positionInRoot()
            val height = entry.layout.size.height
            if (root.y < position.y || root.y >= position.y + height) continue
            val localX = root.x - position.x
            val localY = root.y - origin.y
            // Reject negatives BEFORE integer truncation. History, status strips and padding
            // cannot be folded into row 0. Row comes from actual layout, never nominal history height.
            val column = terminalPointerColumn(localX, current.cellWidth, columns) ?: return null
            if (localY < 0 || !localY.isFinite()) return null
            return Hit(row, column, localX.toInt(), localY.toInt(), frame.screenGeneration,
                columns, frame.screenLineIds.size)
        }
        return null
    }
}

internal fun terminalPointerColumn(x: Float, cellWidth: Int, columns: Int): Int? {
    if (!x.isFinite() || x < 0f || cellWidth <= 0 || columns <= 0 ||
        x.toDouble() >= cellWidth.toDouble() * columns) return null
    return (x / cellWidth).toInt()
}

internal data class TerminalPointerScope(val map: TerminalPointerMap, val pass: TerminalPointerMap.Pass)
internal val LocalTerminalPointerScope = compositionLocalOf<TerminalPointerScope?> { null }

@Composable
internal fun terminalPointerRowModifier(row: TerminalRenderedRowState, text: AnnotatedString,
    scope: TerminalPointerScope): Modifier {
    if (row.lineId !in scope.pass.ids) return Modifier
    val owner = remember(scope.map, row.lineId) { Any() }
    DisposableEffect(scope.map, row.lineId, owner) {
        onDispose { scope.map.release(owner, row.lineId) }
    }
    return Modifier.onGloballyPositioned {
        scope.map.register(owner, row.lineId, text, scope.pass.metrics, it)
    }
}
