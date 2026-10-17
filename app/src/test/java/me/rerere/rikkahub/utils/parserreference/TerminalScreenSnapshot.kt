package me.rerere.rikkahub.utils.parserreference

import java.util.Collections

/** Immutable cell/text map. Columns are zero based here; public terminal API columns are one based. */
internal class TerminalScreenTextRow(
    val text: String,
    cells: List<Cell>,
) {
    internal data class Cell(val column: Int, val width: Int, val start: Int, val end: Int)
    private val cells = Collections.unmodifiableList(cells.toList())
    private fun at(column: Int): Cell? = cells.firstOrNull { column >= it.column && column < it.column + it.width }
    private fun content(cell: Cell?): String? = cell?.takeIf { it.start < text.length }?.let {
        text.substring(it.start, minOf(it.end, text.length))
    }
    fun offset(column: Int): Int = (at(column)?.start ?: text.length).coerceIn(0, text.length)
    fun column(offset: Int): Int = cells.firstOrNull { offset >= it.start && offset < it.end }?.column ?: width
    val width: Int get() = cells.lastOrNull { it.start < text.length }?.let { it.column + it.width } ?: 0
    fun atCursor(column: Int): String? = content(at(column))
    fun beforeCursor(column: Int): String? {
        val start = offset(column)
        return content(cells.lastOrNull { it.end <= start })
    }
    fun afterCursor(column: Int): String? {
        val start = offset(column)
        val end = cells.firstOrNull { it.start == start }?.end ?: text.length
        return atOffset(end)
    }
    fun atOffset(offset: Int): String? = content(cells.firstOrNull { offset >= it.start && offset < it.end })
}

/** Captured under one emulator monitor; no mutable cells, live getters or consumed event queues escape. */
internal data class TerminalScreenSnapshot(
    val lines: List<TerminalScreenTextRow>,
    val columns: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val cursorShape: TerminalEmulator.CursorShape,
    val title: String,
    val workingDirectoryUri: String,
    val modes: String,
    val revision: Long,
    val responses: List<String>,
    val clipboardRequests: List<String>,
)
