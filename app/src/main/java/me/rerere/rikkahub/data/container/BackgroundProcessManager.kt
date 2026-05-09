package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.utils.TerminalEmulator
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


internal data class InteractiveTerminalSnapshot(
    val screen: String,
    val screenLines: List<String>,
    val visibleContent: String,
    val visibleContentLines: List<String>,
    val topLines: List<String>,
    val topLinesStartRow: Int,
    val topLinesEndRow: Int,
    val bottomLines: List<String>,
    val bottomLinesStartRow: Int,
    val bottomLinesEndRow: Int,
    val nonEmptyRows: List<Int>,
    val nonEmptyLines: List<String>,
    val nonEmptyRowCount: Int,
    val internalBlankRowCount: Int,
    val leadingBlankRowCount: Int,
    val trailingBlankRowCount: Int,
    val contentDensityPermille: Int,
    val viewportDensityPermille: Int,
    val firstNonEmptyRow: Int?,
    val lastNonEmptyRow: Int?,
    val firstNonEmptyLine: String?,
    val lastNonEmptyLine: String?,
    val contentHeight: Int,
    val isEmpty: Boolean,
    val modes: String,
    val title: String,
    val columns: Int,
    val rows: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
    val rowsAboveCursor: Int,
    val rowsBelowCursor: Int,
    val cursorDistanceFromContentTop: Int?,
    val cursorDistanceFromContentBottom: Int?,
    val cursorContextLines: List<String>,
    val cursorContextStartRow: Int,
    val cursorContextEndRow: Int,
    val cursorContextCursorIndex: Int,
    val cursorLine: String,
    val cursorLineIsBlank: Boolean,
    val cursorLineLength: Int,
    val cursorLineLeadingBlankCount: Int,
    val cursorLineTrailingBlankCount: Int,
    val cursorLineFirstNonBlankColumn: Int?,
    val cursorLineLastNonBlankColumn: Int?,
    val cursorDistanceFromLineTextStart: Int?,
    val cursorDistanceFromLineTextEnd: Int?,
    val cursorLineCursorRegion: String,
    val cursorOnBlankLine: Boolean,
    val cursorBeforeLineText: Boolean,
    val cursorAtLineTextStart: Boolean,
    val cursorInsideLineText: Boolean,
    val cursorAtLineTextEnd: Boolean,
    val cursorAfterLineText: Boolean,
    val cursorTextColumn: Int,
    val cursorDistanceFromLineEnd: Int,
    val cursorLineTextBeforeCursor: String,
    val cursorLineTextAfterCursor: String,
    val cursorInVisibleContent: Boolean,
    val cursorVisibleContentRow: Int?,
    val cursorVisibleContentLine: String?,
    val cursorShape: String,
    val cursorVisible: Boolean,
    val workingDirectoryUri: String,
    val responses: List<String>,
    val clipboardRequests: List<String>
)

internal fun renderInteractiveTerminalSnapshot(
    bytes: ByteArray,
    columns: Int,
    rows: Int
): InteractiveTerminalSnapshot {
    val safeColumns = columns.coerceIn(20, 240)
    val safeRows = rows.coerceIn(6, 80)
    val terminal = TerminalEmulator(initialColumns = safeColumns, initialRows = safeRows)
    terminal.feed(bytes)
    val screen = terminal.plainText(includeScrollback = false)
    val screenLines = screen.lines()
    val edgeBandSize = safeRows.coerceAtMost(3)
    val topLinesStartRow = 1
    val topLinesEndRow = edgeBandSize
    val topLines = screenLines.take(edgeBandSize)
    val bottomLinesStartRow = safeRows - edgeBandSize + 1
    val bottomLinesEndRow = safeRows
    val bottomLines = screenLines.takeLast(edgeBandSize)
    val nonEmptyRows = screenLines.mapIndexedNotNull { index, line ->
        if (line.isNotBlank()) index + 1 else null
    }
    val nonEmptyLines = nonEmptyRows.mapNotNull { row -> screenLines.getOrNull(row - 1) }
    val firstNonEmptyRow = nonEmptyRows.firstOrNull()
    val lastNonEmptyRow = nonEmptyRows.lastOrNull()
    val contentHeight = if (firstNonEmptyRow != null && lastNonEmptyRow != null) {
        lastNonEmptyRow - firstNonEmptyRow + 1
    } else {
        0
    }
    val visibleContentLines = if (firstNonEmptyRow != null && lastNonEmptyRow != null) {
        screenLines.subList(firstNonEmptyRow - 1, lastNonEmptyRow)
    } else {
        emptyList()
    }
    val internalBlankRowCount = contentHeight - nonEmptyRows.size
    val leadingBlankRowCount = firstNonEmptyRow?.let { it - 1 } ?: safeRows
    val trailingBlankRowCount = lastNonEmptyRow?.let { safeRows - it } ?: 0
    val contentDensityPermille = if (contentHeight > 0) {
        (nonEmptyRows.size * 1000) / contentHeight
    } else {
        0
    }
    val viewportDensityPermille = (nonEmptyRows.size * 1000) / safeRows
    val visibleContent = visibleContentLines.joinToString("\n")
    val firstNonEmptyLine = firstNonEmptyRow?.let { row -> screenLines.getOrNull(row - 1) }
    val lastNonEmptyLine = lastNonEmptyRow?.let { row -> screenLines.getOrNull(row - 1) }
    val cursorRow = terminal.cursorRow()
    val cursorColumn = terminal.cursorColumn()
    val rowsAboveCursor = cursorRow - 1
    val rowsBelowCursor = safeRows - cursorRow
    val cursorDistanceFromContentTop = firstNonEmptyRow?.let { cursorRow - it }
    val cursorDistanceFromContentBottom = lastNonEmptyRow?.let { it - cursorRow }
    val cursorContextStartRow = (cursorRow - 2).coerceAtLeast(1)
    val cursorContextEndRow = (cursorRow + 2).coerceAtMost(safeRows)
    val cursorContextLines = screenLines.subList(cursorContextStartRow - 1, cursorContextEndRow)
    val cursorContextCursorIndex = cursorRow - cursorContextStartRow
    val cursorLine = screenLines.getOrElse(cursorRow - 1) { "" }
    val cursorLineIsBlank = cursorLine.isBlank()
    val cursorLineLength = cursorLine.length
    val cursorLineLeadingBlankCount = cursorLine.indexOfFirst { !it.isWhitespace() }.let { index ->
        if (index == -1) cursorLineLength else index
    }
    val cursorLineTrailingBlankCount = cursorLine.indexOfLast { !it.isWhitespace() }.let { index ->
        if (index == -1) cursorLineLength else cursorLineLength - index - 1
    }
    val cursorLineFirstNonBlankColumn = cursorLine.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }?.let { it + 1 }
    val cursorLineLastNonBlankColumn = cursorLine.indexOfLast { !it.isWhitespace() }.takeIf { it >= 0 }?.let { it + 1 }
    val cursorDistanceFromLineTextStart = cursorLineFirstNonBlankColumn?.let { cursorColumn - it }
    val cursorDistanceFromLineTextEnd = cursorLineLastNonBlankColumn?.let { cursorColumn - (it + 1) }
    val cursorLineCursorRegion = when {
        cursorLineIsBlank -> "BLANK_LINE"
        cursorDistanceFromLineTextStart != null && cursorDistanceFromLineTextStart < 0 -> "BEFORE_TEXT"
        cursorDistanceFromLineTextStart == 0 -> "TEXT_START"
        cursorDistanceFromLineTextEnd != null && cursorDistanceFromLineTextEnd < 0 -> "INSIDE_TEXT"
        cursorDistanceFromLineTextEnd == 0 -> "TEXT_END"
        else -> "AFTER_TEXT"
    }
    val cursorOnBlankLine = cursorLineCursorRegion == "BLANK_LINE"
    val cursorBeforeLineText = cursorLineCursorRegion == "BEFORE_TEXT"
    val cursorAtLineTextStart = cursorLineCursorRegion == "TEXT_START"
    val cursorInsideLineText = cursorLineCursorRegion == "INSIDE_TEXT"
    val cursorAtLineTextEnd = cursorLineCursorRegion == "TEXT_END"
    val cursorAfterLineText = cursorLineCursorRegion == "AFTER_TEXT"
    val cursorSplitColumn = (cursorColumn - 1).coerceIn(0, cursorLineLength)
    val cursorDistanceFromLineEnd = cursorColumn - (cursorLineLength + 1)
    val cursorInVisibleContent = firstNonEmptyRow != null && lastNonEmptyRow != null && cursorRow in firstNonEmptyRow..lastNonEmptyRow
    val cursorVisibleContentRow = if (cursorInVisibleContent && firstNonEmptyRow != null) {
        cursorRow - firstNonEmptyRow + 1
    } else {
        null
    }
    val cursorVisibleContentLine = cursorVisibleContentRow?.let { row ->
        visibleContentLines.getOrNull(row - 1)
    }
    return InteractiveTerminalSnapshot(
        screen = screen,
        screenLines = screenLines,
        visibleContent = visibleContent,
        visibleContentLines = visibleContentLines,
        topLines = topLines,
        topLinesStartRow = topLinesStartRow,
        topLinesEndRow = topLinesEndRow,
        bottomLines = bottomLines,
        bottomLinesStartRow = bottomLinesStartRow,
        bottomLinesEndRow = bottomLinesEndRow,
        nonEmptyRows = nonEmptyRows,
        nonEmptyLines = nonEmptyLines,
        nonEmptyRowCount = nonEmptyRows.size,
        internalBlankRowCount = internalBlankRowCount,
        leadingBlankRowCount = leadingBlankRowCount,
        trailingBlankRowCount = trailingBlankRowCount,
        contentDensityPermille = contentDensityPermille,
        viewportDensityPermille = viewportDensityPermille,
        firstNonEmptyRow = firstNonEmptyRow,
        lastNonEmptyRow = lastNonEmptyRow,
        firstNonEmptyLine = firstNonEmptyLine,
        lastNonEmptyLine = lastNonEmptyLine,
        contentHeight = contentHeight,
        isEmpty = nonEmptyRows.isEmpty(),
        modes = terminal.modeSummary(),
        title = terminal.title,
        columns = safeColumns,
        rows = safeRows,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        rowsAboveCursor = rowsAboveCursor,
        rowsBelowCursor = rowsBelowCursor,
        cursorDistanceFromContentTop = cursorDistanceFromContentTop,
        cursorDistanceFromContentBottom = cursorDistanceFromContentBottom,
        cursorContextLines = cursorContextLines,
        cursorContextStartRow = cursorContextStartRow,
        cursorContextEndRow = cursorContextEndRow,
        cursorContextCursorIndex = cursorContextCursorIndex,
        cursorLine = cursorLine,
        cursorLineIsBlank = cursorLineIsBlank,
        cursorLineLength = cursorLineLength,
        cursorLineLeadingBlankCount = cursorLineLeadingBlankCount,
        cursorLineTrailingBlankCount = cursorLineTrailingBlankCount,
        cursorLineFirstNonBlankColumn = cursorLineFirstNonBlankColumn,
        cursorLineLastNonBlankColumn = cursorLineLastNonBlankColumn,
        cursorDistanceFromLineTextStart = cursorDistanceFromLineTextStart,
        cursorDistanceFromLineTextEnd = cursorDistanceFromLineTextEnd,
        cursorLineCursorRegion = cursorLineCursorRegion,
        cursorOnBlankLine = cursorOnBlankLine,
        cursorBeforeLineText = cursorBeforeLineText,
        cursorAtLineTextStart = cursorAtLineTextStart,
        cursorInsideLineText = cursorInsideLineText,
        cursorAtLineTextEnd = cursorAtLineTextEnd,
        cursorAfterLineText = cursorAfterLineText,
        cursorTextColumn = cursorSplitColumn,
        cursorDistanceFromLineEnd = cursorDistanceFromLineEnd,
        cursorLineTextBeforeCursor = cursorLine.take(cursorSplitColumn),
        cursorLineTextAfterCursor = cursorLine.drop(cursorSplitColumn),
        cursorInVisibleContent = cursorInVisibleContent,
        cursorVisibleContentRow = cursorVisibleContentRow,
        cursorVisibleContentLine = cursorVisibleContentLine,
        cursorShape = terminal.cursorShape().name,
        cursorVisible = terminal.isCursorVisible(),
        workingDirectoryUri = terminal.workingDirectoryUri,
        responses = terminal.drainResponses(),
        clipboardRequests = terminal.drainClipboardRequests()
    )
}

enum class ControlInput {
    CTRL_SPACE,
    CTRL_A,
    CTRL_B,
    CTRL_C,
    CTRL_D,
    CTRL_E,
    CTRL_F,
    CTRL_G,
    CTRL_H,
    CTRL_I,
    CTRL_J,
    CTRL_K,
    CTRL_L,
    CTRL_M,
    CTRL_N,
    CTRL_O,
    CTRL_P,
    CTRL_Q,
    CTRL_R,
    CTRL_S,
    CTRL_T,
    CTRL_U,
    CTRL_V,
    CTRL_W,
    CTRL_X,
    CTRL_Y,
    CTRL_Z,
    CTRL_LEFT_BRACKET,
    CTRL_BACKSLASH,
    CTRL_RIGHT_BRACKET,
    CTRL_CARET,
    CTRL_UNDERSCORE,
    ALT_SPACE,
    ALT_A,
    ALT_B,
    ALT_C,
    ALT_D,
    ALT_E,
    ALT_F,
    ALT_G,
    ALT_H,
    ALT_I,
    ALT_J,
    ALT_K,
    ALT_L,
    ALT_M,
    ALT_N,
    ALT_O,
    ALT_P,
    ALT_Q,
    ALT_R,
    ALT_S,
    ALT_T,
    ALT_U,
    ALT_V,
    ALT_W,
    ALT_X,
    ALT_Y,
    ALT_Z,
    ALT_0,
    ALT_1,
    ALT_2,
    ALT_3,
    ALT_4,
    ALT_5,
    ALT_6,
    ALT_7,
    ALT_8,
    ALT_9,
    ALT_MINUS,
    ALT_EQUALS,
    ALT_LEFT_BRACKET,
    ALT_RIGHT_BRACKET,
    ALT_BACKSLASH,
    ALT_SEMICOLON,
    ALT_APOSTROPHE,
    ALT_COMMA,
    ALT_PERIOD,
    ALT_SLASH,
    ALT_GRAVE,
    SHIFT_ALT_A,
    SHIFT_ALT_B,
    SHIFT_ALT_C,
    SHIFT_ALT_D,
    SHIFT_ALT_E,
    SHIFT_ALT_F,
    SHIFT_ALT_G,
    SHIFT_ALT_H,
    SHIFT_ALT_I,
    SHIFT_ALT_J,
    SHIFT_ALT_K,
    SHIFT_ALT_L,
    SHIFT_ALT_M,
    SHIFT_ALT_N,
    SHIFT_ALT_O,
    SHIFT_ALT_P,
    SHIFT_ALT_Q,
    SHIFT_ALT_R,
    SHIFT_ALT_S,
    SHIFT_ALT_T,
    SHIFT_ALT_U,
    SHIFT_ALT_V,
    SHIFT_ALT_W,
    SHIFT_ALT_X,
    SHIFT_ALT_Y,
    SHIFT_ALT_Z,
    ALT_CTRL_A,
    ALT_CTRL_B,
    ALT_CTRL_C,
    ALT_CTRL_D,
    ALT_CTRL_E,
    ALT_CTRL_F,
    ALT_CTRL_G,
    ALT_CTRL_H,
    ALT_CTRL_I,
    ALT_CTRL_J,
    ALT_CTRL_K,
    ALT_CTRL_L,
    ALT_CTRL_M,
    ALT_CTRL_N,
    ALT_CTRL_O,
    ALT_CTRL_P,
    ALT_CTRL_Q,
    ALT_CTRL_R,
    ALT_CTRL_S,
    ALT_CTRL_T,
    ALT_CTRL_U,
    ALT_CTRL_V,
    ALT_CTRL_W,
    ALT_CTRL_X,
    ALT_CTRL_Y,
    ALT_CTRL_Z,
    SHIFT_ALT_CTRL_A,
    SHIFT_ALT_CTRL_B,
    SHIFT_ALT_CTRL_C,
    SHIFT_ALT_CTRL_D,
    SHIFT_ALT_CTRL_E,
    SHIFT_ALT_CTRL_F,
    SHIFT_ALT_CTRL_G,
    SHIFT_ALT_CTRL_H,
    SHIFT_ALT_CTRL_I,
    SHIFT_ALT_CTRL_J,
    SHIFT_ALT_CTRL_K,
    SHIFT_ALT_CTRL_L,
    SHIFT_ALT_CTRL_M,
    SHIFT_ALT_CTRL_N,
    SHIFT_ALT_CTRL_O,
    SHIFT_ALT_CTRL_P,
    SHIFT_ALT_CTRL_Q,
    SHIFT_ALT_CTRL_R,
    SHIFT_ALT_CTRL_S,
    SHIFT_ALT_CTRL_T,
    SHIFT_ALT_CTRL_U,
    SHIFT_ALT_CTRL_V,
    SHIFT_ALT_CTRL_W,
    SHIFT_ALT_CTRL_X,
    SHIFT_ALT_CTRL_Y,
    SHIFT_ALT_CTRL_Z,
    ALT_CTRL_SPACE,
    ALT_CTRL_LEFT_BRACKET,
    ALT_CTRL_BACKSLASH,
    ALT_CTRL_RIGHT_BRACKET,
    ALT_CTRL_CARET,
    ALT_CTRL_UNDERSCORE,
    ALT_CTRL_QUESTION_MARK,
    TAB,
    BACK_TAB,
    ALT_TAB,
    ESC,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    SHIFT_UP,
    SHIFT_DOWN,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    ALT_UP,
    ALT_DOWN,
    ALT_LEFT,
    ALT_RIGHT,
    CTRL_UP,
    CTRL_DOWN,
    CTRL_LEFT,
    CTRL_RIGHT,
    SHIFT_CTRL_UP,
    SHIFT_CTRL_DOWN,
    SHIFT_CTRL_LEFT,
    SHIFT_CTRL_RIGHT,
    ALT_CTRL_UP,
    ALT_CTRL_DOWN,
    ALT_CTRL_LEFT,
    ALT_CTRL_RIGHT,
    SHIFT_ALT_CTRL_UP,
    SHIFT_ALT_CTRL_DOWN,
    SHIFT_ALT_CTRL_LEFT,
    SHIFT_ALT_CTRL_RIGHT,
    HOME,
    END,
    SHIFT_HOME,
    SHIFT_END,
    ALT_HOME,
    ALT_END,
    CTRL_HOME,
    CTRL_END,
    PAGE_UP,
    PAGE_DOWN,
    SHIFT_PAGE_UP,
    SHIFT_PAGE_DOWN,
    CTRL_PAGE_UP,
    CTRL_PAGE_DOWN,
    INSERT,
    DELETE,
    SHIFT_INSERT,
    SHIFT_DELETE,
    CTRL_INSERT,
    CTRL_DELETE,
    ALT_INSERT,
    ALT_DELETE,
    ENTER,
    ALT_ENTER,
    BACKSPACE,
    CTRL_BACKSPACE,
    ALT_BACKSPACE,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    SHIFT_F1,
    SHIFT_F2,
    SHIFT_F3,
    SHIFT_F4,
    SHIFT_F5,
    SHIFT_F6,
    SHIFT_F7,
    SHIFT_F8,
    SHIFT_F9,
    SHIFT_F10,
    SHIFT_F11,
    SHIFT_F12,
    ALT_F1,
    ALT_F2,
    ALT_F3,
    ALT_F4,
    ALT_F5,
    ALT_F6,
    ALT_F7,
    ALT_F8,
    ALT_F9,
    ALT_F10,
    ALT_F11,
    ALT_F12,
    CTRL_F1,
    CTRL_F2,
    CTRL_F3,
    CTRL_F4,
    CTRL_F5,
    CTRL_F6,
    CTRL_F7,
    CTRL_F8,
    CTRL_F9,
    CTRL_F10,
    CTRL_F11,
    CTRL_F12,
    SHIFT_CTRL_F1,
    SHIFT_CTRL_F2,
    SHIFT_CTRL_F3,
    SHIFT_CTRL_F4,
    SHIFT_CTRL_F5,
    SHIFT_CTRL_F6,
    SHIFT_CTRL_F7,
    SHIFT_CTRL_F8,
    SHIFT_CTRL_F9,
    SHIFT_CTRL_F10,
    SHIFT_CTRL_F11,
    SHIFT_CTRL_F12,
    ALT_CTRL_F1,
    ALT_CTRL_F2,
    ALT_CTRL_F3,
    ALT_CTRL_F4,
    ALT_CTRL_F5,
    ALT_CTRL_F6,
    ALT_CTRL_F7,
    ALT_CTRL_F8,
    ALT_CTRL_F9,
    ALT_CTRL_F10,
    ALT_CTRL_F11,
    ALT_CTRL_F12,
    SHIFT_ALT_CTRL_F1,
    SHIFT_ALT_CTRL_F2,
    SHIFT_ALT_CTRL_F3,
    SHIFT_ALT_CTRL_F4,
    SHIFT_ALT_CTRL_F5,
    SHIFT_ALT_CTRL_F6,
    SHIFT_ALT_CTRL_F7,
    SHIFT_ALT_CTRL_F8,
    SHIFT_ALT_CTRL_F9,
    SHIFT_ALT_CTRL_F10,
    SHIFT_ALT_CTRL_F11,
    SHIFT_ALT_CTRL_F12
}

internal fun controlInputBytes(control: ControlInput): ByteArray {
    fun esc(suffix: String): ByteArray = ("\u001B" + suffix).toByteArray(Charsets.UTF_8)
    fun csiModified(final: Char, modifier: Int): ByteArray = esc("[1;${modifier}$final")
    fun tildeModified(code: Int, modifier: Int): ByteArray = esc("[${code};${modifier}~")
    fun modifyOtherKey(codePoint: Int, modifier: Int): ByteArray = esc("[27;${modifier};${codePoint}~")
    fun alt(bytes: ByteArray): ByteArray = byteArrayOf(0x1B) + bytes
    return when (control) {
        ControlInput.CTRL_SPACE -> byteArrayOf(0x00)
        ControlInput.CTRL_A -> byteArrayOf(0x01)
        ControlInput.CTRL_B -> byteArrayOf(0x02)
        ControlInput.CTRL_C -> byteArrayOf(0x03)
        ControlInput.CTRL_D -> byteArrayOf(0x04)
        ControlInput.CTRL_E -> byteArrayOf(0x05)
        ControlInput.CTRL_F -> byteArrayOf(0x06)
        ControlInput.CTRL_G -> byteArrayOf(0x07)
        ControlInput.CTRL_H -> byteArrayOf(0x08)
        ControlInput.CTRL_I -> byteArrayOf('\t'.code.toByte())
        ControlInput.CTRL_J -> byteArrayOf('\n'.code.toByte())
        ControlInput.CTRL_K -> byteArrayOf(0x0B)
        ControlInput.CTRL_L -> byteArrayOf(0x0C)
        ControlInput.CTRL_M -> byteArrayOf('\r'.code.toByte())
        ControlInput.CTRL_N -> byteArrayOf(0x0E)
        ControlInput.CTRL_O -> byteArrayOf(0x0F)
        ControlInput.CTRL_P -> byteArrayOf(0x10)
        ControlInput.CTRL_Q -> byteArrayOf(0x11)
        ControlInput.CTRL_R -> byteArrayOf(0x12)
        ControlInput.CTRL_S -> byteArrayOf(0x13)
        ControlInput.CTRL_T -> byteArrayOf(0x14)
        ControlInput.CTRL_U -> byteArrayOf(0x15)
        ControlInput.CTRL_V -> byteArrayOf(0x16)
        ControlInput.CTRL_W -> byteArrayOf(0x17)
        ControlInput.CTRL_X -> byteArrayOf(0x18)
        ControlInput.CTRL_Y -> byteArrayOf(0x19)
        ControlInput.CTRL_Z -> byteArrayOf(0x1A)
        ControlInput.CTRL_LEFT_BRACKET -> byteArrayOf(0x1B)
        ControlInput.CTRL_BACKSLASH -> byteArrayOf(0x1C)
        ControlInput.CTRL_RIGHT_BRACKET -> byteArrayOf(0x1D)
        ControlInput.CTRL_CARET -> byteArrayOf(0x1E)
        ControlInput.CTRL_UNDERSCORE -> byteArrayOf(0x1F)
        ControlInput.ALT_SPACE -> alt(byteArrayOf(' '.code.toByte()))
        ControlInput.ALT_A -> alt(byteArrayOf('a'.code.toByte()))
        ControlInput.ALT_B -> alt(byteArrayOf('b'.code.toByte()))
        ControlInput.ALT_C -> alt(byteArrayOf('c'.code.toByte()))
        ControlInput.ALT_D -> alt(byteArrayOf('d'.code.toByte()))
        ControlInput.ALT_E -> alt(byteArrayOf('e'.code.toByte()))
        ControlInput.ALT_F -> alt(byteArrayOf('f'.code.toByte()))
        ControlInput.ALT_G -> alt(byteArrayOf('g'.code.toByte()))
        ControlInput.ALT_H -> alt(byteArrayOf('h'.code.toByte()))
        ControlInput.ALT_I -> alt(byteArrayOf('i'.code.toByte()))
        ControlInput.ALT_J -> alt(byteArrayOf('j'.code.toByte()))
        ControlInput.ALT_K -> alt(byteArrayOf('k'.code.toByte()))
        ControlInput.ALT_L -> alt(byteArrayOf('l'.code.toByte()))
        ControlInput.ALT_M -> alt(byteArrayOf('m'.code.toByte()))
        ControlInput.ALT_N -> alt(byteArrayOf('n'.code.toByte()))
        ControlInput.ALT_O -> alt(byteArrayOf('o'.code.toByte()))
        ControlInput.ALT_P -> alt(byteArrayOf('p'.code.toByte()))
        ControlInput.ALT_Q -> alt(byteArrayOf('q'.code.toByte()))
        ControlInput.ALT_R -> alt(byteArrayOf('r'.code.toByte()))
        ControlInput.ALT_S -> alt(byteArrayOf('s'.code.toByte()))
        ControlInput.ALT_T -> alt(byteArrayOf('t'.code.toByte()))
        ControlInput.ALT_U -> alt(byteArrayOf('u'.code.toByte()))
        ControlInput.ALT_V -> alt(byteArrayOf('v'.code.toByte()))
        ControlInput.ALT_W -> alt(byteArrayOf('w'.code.toByte()))
        ControlInput.ALT_X -> alt(byteArrayOf('x'.code.toByte()))
        ControlInput.ALT_Y -> alt(byteArrayOf('y'.code.toByte()))
        ControlInput.ALT_Z -> alt(byteArrayOf('z'.code.toByte()))
        ControlInput.ALT_0 -> alt(byteArrayOf('0'.code.toByte()))
        ControlInput.ALT_1 -> alt(byteArrayOf('1'.code.toByte()))
        ControlInput.ALT_2 -> alt(byteArrayOf('2'.code.toByte()))
        ControlInput.ALT_3 -> alt(byteArrayOf('3'.code.toByte()))
        ControlInput.ALT_4 -> alt(byteArrayOf('4'.code.toByte()))
        ControlInput.ALT_5 -> alt(byteArrayOf('5'.code.toByte()))
        ControlInput.ALT_6 -> alt(byteArrayOf('6'.code.toByte()))
        ControlInput.ALT_7 -> alt(byteArrayOf('7'.code.toByte()))
        ControlInput.ALT_8 -> alt(byteArrayOf('8'.code.toByte()))
        ControlInput.ALT_9 -> alt(byteArrayOf('9'.code.toByte()))
        ControlInput.ALT_MINUS -> alt(byteArrayOf('-'.code.toByte()))
        ControlInput.ALT_EQUALS -> alt(byteArrayOf('='.code.toByte()))
        ControlInput.ALT_LEFT_BRACKET -> alt(byteArrayOf('['.code.toByte()))
        ControlInput.ALT_RIGHT_BRACKET -> alt(byteArrayOf(']'.code.toByte()))
        ControlInput.ALT_BACKSLASH -> alt(byteArrayOf('\\'.code.toByte()))
        ControlInput.ALT_SEMICOLON -> alt(byteArrayOf(';'.code.toByte()))
        ControlInput.ALT_APOSTROPHE -> alt(byteArrayOf("'".single().code.toByte()))
        ControlInput.ALT_COMMA -> alt(byteArrayOf(','.code.toByte()))
        ControlInput.ALT_PERIOD -> alt(byteArrayOf('.'.code.toByte()))
        ControlInput.ALT_SLASH -> alt(byteArrayOf('/'.code.toByte()))
        ControlInput.ALT_GRAVE -> alt(byteArrayOf('`'.code.toByte()))
        ControlInput.SHIFT_ALT_A -> modifyOtherKey('A'.code, 4)
        ControlInput.SHIFT_ALT_B -> modifyOtherKey('B'.code, 4)
        ControlInput.SHIFT_ALT_C -> modifyOtherKey('C'.code, 4)
        ControlInput.SHIFT_ALT_D -> modifyOtherKey('D'.code, 4)
        ControlInput.SHIFT_ALT_E -> modifyOtherKey('E'.code, 4)
        ControlInput.SHIFT_ALT_F -> modifyOtherKey('F'.code, 4)
        ControlInput.SHIFT_ALT_G -> modifyOtherKey('G'.code, 4)
        ControlInput.SHIFT_ALT_H -> modifyOtherKey('H'.code, 4)
        ControlInput.SHIFT_ALT_I -> modifyOtherKey('I'.code, 4)
        ControlInput.SHIFT_ALT_J -> modifyOtherKey('J'.code, 4)
        ControlInput.SHIFT_ALT_K -> modifyOtherKey('K'.code, 4)
        ControlInput.SHIFT_ALT_L -> modifyOtherKey('L'.code, 4)
        ControlInput.SHIFT_ALT_M -> modifyOtherKey('M'.code, 4)
        ControlInput.SHIFT_ALT_N -> modifyOtherKey('N'.code, 4)
        ControlInput.SHIFT_ALT_O -> modifyOtherKey('O'.code, 4)
        ControlInput.SHIFT_ALT_P -> modifyOtherKey('P'.code, 4)
        ControlInput.SHIFT_ALT_Q -> modifyOtherKey('Q'.code, 4)
        ControlInput.SHIFT_ALT_R -> modifyOtherKey('R'.code, 4)
        ControlInput.SHIFT_ALT_S -> modifyOtherKey('S'.code, 4)
        ControlInput.SHIFT_ALT_T -> modifyOtherKey('T'.code, 4)
        ControlInput.SHIFT_ALT_U -> modifyOtherKey('U'.code, 4)
        ControlInput.SHIFT_ALT_V -> modifyOtherKey('V'.code, 4)
        ControlInput.SHIFT_ALT_W -> modifyOtherKey('W'.code, 4)
        ControlInput.SHIFT_ALT_X -> modifyOtherKey('X'.code, 4)
        ControlInput.SHIFT_ALT_Y -> modifyOtherKey('Y'.code, 4)
        ControlInput.SHIFT_ALT_Z -> modifyOtherKey('Z'.code, 4)
        ControlInput.ALT_CTRL_A -> modifyOtherKey('a'.code, 7)
        ControlInput.ALT_CTRL_B -> modifyOtherKey('b'.code, 7)
        ControlInput.ALT_CTRL_C -> modifyOtherKey('c'.code, 7)
        ControlInput.ALT_CTRL_D -> modifyOtherKey('d'.code, 7)
        ControlInput.ALT_CTRL_E -> modifyOtherKey('e'.code, 7)
        ControlInput.ALT_CTRL_F -> modifyOtherKey('f'.code, 7)
        ControlInput.ALT_CTRL_G -> modifyOtherKey('g'.code, 7)
        ControlInput.ALT_CTRL_H -> modifyOtherKey('h'.code, 7)
        ControlInput.ALT_CTRL_I -> modifyOtherKey('i'.code, 7)
        ControlInput.ALT_CTRL_J -> modifyOtherKey('j'.code, 7)
        ControlInput.ALT_CTRL_K -> modifyOtherKey('k'.code, 7)
        ControlInput.ALT_CTRL_L -> modifyOtherKey('l'.code, 7)
        ControlInput.ALT_CTRL_M -> modifyOtherKey('m'.code, 7)
        ControlInput.ALT_CTRL_N -> modifyOtherKey('n'.code, 7)
        ControlInput.ALT_CTRL_O -> modifyOtherKey('o'.code, 7)
        ControlInput.ALT_CTRL_P -> modifyOtherKey('p'.code, 7)
        ControlInput.ALT_CTRL_Q -> modifyOtherKey('q'.code, 7)
        ControlInput.ALT_CTRL_R -> modifyOtherKey('r'.code, 7)
        ControlInput.ALT_CTRL_S -> modifyOtherKey('s'.code, 7)
        ControlInput.ALT_CTRL_T -> modifyOtherKey('t'.code, 7)
        ControlInput.ALT_CTRL_U -> modifyOtherKey('u'.code, 7)
        ControlInput.ALT_CTRL_V -> modifyOtherKey('v'.code, 7)
        ControlInput.ALT_CTRL_W -> modifyOtherKey('w'.code, 7)
        ControlInput.ALT_CTRL_X -> modifyOtherKey('x'.code, 7)
        ControlInput.ALT_CTRL_Y -> modifyOtherKey('y'.code, 7)
        ControlInput.ALT_CTRL_Z -> modifyOtherKey('z'.code, 7)
        ControlInput.SHIFT_ALT_CTRL_A -> modifyOtherKey('A'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_B -> modifyOtherKey('B'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_C -> modifyOtherKey('C'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_D -> modifyOtherKey('D'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_E -> modifyOtherKey('E'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_F -> modifyOtherKey('F'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_G -> modifyOtherKey('G'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_H -> modifyOtherKey('H'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_I -> modifyOtherKey('I'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_J -> modifyOtherKey('J'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_K -> modifyOtherKey('K'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_L -> modifyOtherKey('L'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_M -> modifyOtherKey('M'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_N -> modifyOtherKey('N'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_O -> modifyOtherKey('O'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_P -> modifyOtherKey('P'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_Q -> modifyOtherKey('Q'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_R -> modifyOtherKey('R'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_S -> modifyOtherKey('S'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_T -> modifyOtherKey('T'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_U -> modifyOtherKey('U'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_V -> modifyOtherKey('V'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_W -> modifyOtherKey('W'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_X -> modifyOtherKey('X'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_Y -> modifyOtherKey('Y'.code, 8)
        ControlInput.SHIFT_ALT_CTRL_Z -> modifyOtherKey('Z'.code, 8)
        ControlInput.ALT_CTRL_SPACE -> modifyOtherKey(' '.code, 7)
        ControlInput.ALT_CTRL_LEFT_BRACKET -> modifyOtherKey('['.code, 7)
        ControlInput.ALT_CTRL_BACKSLASH -> modifyOtherKey('\\'.code, 7)
        ControlInput.ALT_CTRL_RIGHT_BRACKET -> modifyOtherKey(']'.code, 7)
        ControlInput.ALT_CTRL_CARET -> modifyOtherKey('^'.code, 7)
        ControlInput.ALT_CTRL_UNDERSCORE -> modifyOtherKey('_'.code, 7)
        ControlInput.ALT_CTRL_QUESTION_MARK -> modifyOtherKey('?'.code, 7)
        ControlInput.TAB -> byteArrayOf('\t'.code.toByte())
        ControlInput.BACK_TAB -> esc("[Z")
        ControlInput.ALT_TAB -> alt(byteArrayOf('\t'.code.toByte()))
        ControlInput.ESC -> byteArrayOf(0x1B)
        ControlInput.UP -> esc("[A")
        ControlInput.DOWN -> esc("[B")
        ControlInput.LEFT -> esc("[D")
        ControlInput.RIGHT -> esc("[C")
        ControlInput.SHIFT_UP -> csiModified('A', 2)
        ControlInput.SHIFT_DOWN -> csiModified('B', 2)
        ControlInput.SHIFT_RIGHT -> csiModified('C', 2)
        ControlInput.SHIFT_LEFT -> csiModified('D', 2)
        ControlInput.ALT_UP -> csiModified('A', 3)
        ControlInput.ALT_DOWN -> csiModified('B', 3)
        ControlInput.ALT_RIGHT -> csiModified('C', 3)
        ControlInput.ALT_LEFT -> csiModified('D', 3)
        ControlInput.CTRL_UP -> csiModified('A', 5)
        ControlInput.CTRL_DOWN -> csiModified('B', 5)
        ControlInput.CTRL_RIGHT -> csiModified('C', 5)
        ControlInput.CTRL_LEFT -> csiModified('D', 5)
        ControlInput.SHIFT_CTRL_UP -> csiModified('A', 6)
        ControlInput.SHIFT_CTRL_DOWN -> csiModified('B', 6)
        ControlInput.SHIFT_CTRL_RIGHT -> csiModified('C', 6)
        ControlInput.SHIFT_CTRL_LEFT -> csiModified('D', 6)
        ControlInput.ALT_CTRL_UP -> csiModified('A', 7)
        ControlInput.ALT_CTRL_DOWN -> csiModified('B', 7)
        ControlInput.ALT_CTRL_RIGHT -> csiModified('C', 7)
        ControlInput.ALT_CTRL_LEFT -> csiModified('D', 7)
        ControlInput.SHIFT_ALT_CTRL_UP -> csiModified('A', 8)
        ControlInput.SHIFT_ALT_CTRL_DOWN -> csiModified('B', 8)
        ControlInput.SHIFT_ALT_CTRL_RIGHT -> csiModified('C', 8)
        ControlInput.SHIFT_ALT_CTRL_LEFT -> csiModified('D', 8)
        ControlInput.HOME -> esc("[H")
        ControlInput.END -> esc("[F")
        ControlInput.SHIFT_HOME -> csiModified('H', 2)
        ControlInput.SHIFT_END -> csiModified('F', 2)
        ControlInput.ALT_HOME -> csiModified('H', 3)
        ControlInput.ALT_END -> csiModified('F', 3)
        ControlInput.CTRL_HOME -> csiModified('H', 5)
        ControlInput.CTRL_END -> csiModified('F', 5)
        ControlInput.PAGE_UP -> esc("[5~")
        ControlInput.PAGE_DOWN -> esc("[6~")
        ControlInput.SHIFT_PAGE_UP -> tildeModified(5, 2)
        ControlInput.SHIFT_PAGE_DOWN -> tildeModified(6, 2)
        ControlInput.CTRL_PAGE_UP -> tildeModified(5, 5)
        ControlInput.CTRL_PAGE_DOWN -> tildeModified(6, 5)
        ControlInput.INSERT -> esc("[2~")
        ControlInput.DELETE -> esc("[3~")
        ControlInput.SHIFT_INSERT -> tildeModified(2, 2)
        ControlInput.SHIFT_DELETE -> tildeModified(3, 2)
        ControlInput.CTRL_INSERT -> tildeModified(2, 5)
        ControlInput.CTRL_DELETE -> tildeModified(3, 5)
        ControlInput.ALT_INSERT -> tildeModified(2, 3)
        ControlInput.ALT_DELETE -> tildeModified(3, 3)
        ControlInput.ENTER -> byteArrayOf('\n'.code.toByte())
        ControlInput.ALT_ENTER -> alt(byteArrayOf('\r'.code.toByte()))
        ControlInput.BACKSPACE -> byteArrayOf(0x7F)
        ControlInput.CTRL_BACKSPACE -> byteArrayOf(0x17)
        ControlInput.ALT_BACKSPACE -> alt(byteArrayOf(0x7F))
        ControlInput.F1 -> esc("OP")
        ControlInput.F2 -> esc("OQ")
        ControlInput.F3 -> esc("OR")
        ControlInput.F4 -> esc("OS")
        ControlInput.F5 -> esc("[15~")
        ControlInput.F6 -> esc("[17~")
        ControlInput.F7 -> esc("[18~")
        ControlInput.F8 -> esc("[19~")
        ControlInput.F9 -> esc("[20~")
        ControlInput.F10 -> esc("[21~")
        ControlInput.F11 -> esc("[23~")
        ControlInput.F12 -> esc("[24~")
        ControlInput.SHIFT_F1 -> csiModified('P', 2)
        ControlInput.SHIFT_F2 -> csiModified('Q', 2)
        ControlInput.SHIFT_F3 -> csiModified('R', 2)
        ControlInput.SHIFT_F4 -> csiModified('S', 2)
        ControlInput.SHIFT_F5 -> tildeModified(15, 2)
        ControlInput.SHIFT_F6 -> tildeModified(17, 2)
        ControlInput.SHIFT_F7 -> tildeModified(18, 2)
        ControlInput.SHIFT_F8 -> tildeModified(19, 2)
        ControlInput.SHIFT_F9 -> tildeModified(20, 2)
        ControlInput.SHIFT_F10 -> tildeModified(21, 2)
        ControlInput.SHIFT_F11 -> tildeModified(23, 2)
        ControlInput.SHIFT_F12 -> tildeModified(24, 2)
        ControlInput.ALT_F1 -> csiModified('P', 3)
        ControlInput.ALT_F2 -> csiModified('Q', 3)
        ControlInput.ALT_F3 -> csiModified('R', 3)
        ControlInput.ALT_F4 -> csiModified('S', 3)
        ControlInput.ALT_F5 -> tildeModified(15, 3)
        ControlInput.ALT_F6 -> tildeModified(17, 3)
        ControlInput.ALT_F7 -> tildeModified(18, 3)
        ControlInput.ALT_F8 -> tildeModified(19, 3)
        ControlInput.ALT_F9 -> tildeModified(20, 3)
        ControlInput.ALT_F10 -> tildeModified(21, 3)
        ControlInput.ALT_F11 -> tildeModified(23, 3)
        ControlInput.ALT_F12 -> tildeModified(24, 3)
        ControlInput.CTRL_F1 -> csiModified('P', 5)
        ControlInput.CTRL_F2 -> csiModified('Q', 5)
        ControlInput.CTRL_F3 -> csiModified('R', 5)
        ControlInput.CTRL_F4 -> csiModified('S', 5)
        ControlInput.CTRL_F5 -> tildeModified(15, 5)
        ControlInput.CTRL_F6 -> tildeModified(17, 5)
        ControlInput.CTRL_F7 -> tildeModified(18, 5)
        ControlInput.CTRL_F8 -> tildeModified(19, 5)
        ControlInput.CTRL_F9 -> tildeModified(20, 5)
        ControlInput.CTRL_F10 -> tildeModified(21, 5)
        ControlInput.CTRL_F11 -> tildeModified(23, 5)
        ControlInput.CTRL_F12 -> tildeModified(24, 5)
        ControlInput.SHIFT_CTRL_F1 -> csiModified('P', 6)
        ControlInput.SHIFT_CTRL_F2 -> csiModified('Q', 6)
        ControlInput.SHIFT_CTRL_F3 -> csiModified('R', 6)
        ControlInput.SHIFT_CTRL_F4 -> csiModified('S', 6)
        ControlInput.SHIFT_CTRL_F5 -> tildeModified(15, 6)
        ControlInput.SHIFT_CTRL_F6 -> tildeModified(17, 6)
        ControlInput.SHIFT_CTRL_F7 -> tildeModified(18, 6)
        ControlInput.SHIFT_CTRL_F8 -> tildeModified(19, 6)
        ControlInput.SHIFT_CTRL_F9 -> tildeModified(20, 6)
        ControlInput.SHIFT_CTRL_F10 -> tildeModified(21, 6)
        ControlInput.SHIFT_CTRL_F11 -> tildeModified(23, 6)
        ControlInput.SHIFT_CTRL_F12 -> tildeModified(24, 6)
        ControlInput.ALT_CTRL_F1 -> csiModified('P', 7)
        ControlInput.ALT_CTRL_F2 -> csiModified('Q', 7)
        ControlInput.ALT_CTRL_F3 -> csiModified('R', 7)
        ControlInput.ALT_CTRL_F4 -> csiModified('S', 7)
        ControlInput.ALT_CTRL_F5 -> tildeModified(15, 7)
        ControlInput.ALT_CTRL_F6 -> tildeModified(17, 7)
        ControlInput.ALT_CTRL_F7 -> tildeModified(18, 7)
        ControlInput.ALT_CTRL_F8 -> tildeModified(19, 7)
        ControlInput.ALT_CTRL_F9 -> tildeModified(20, 7)
        ControlInput.ALT_CTRL_F10 -> tildeModified(21, 7)
        ControlInput.ALT_CTRL_F11 -> tildeModified(23, 7)
        ControlInput.ALT_CTRL_F12 -> tildeModified(24, 7)
        ControlInput.SHIFT_ALT_CTRL_F1 -> csiModified('P', 8)
        ControlInput.SHIFT_ALT_CTRL_F2 -> csiModified('Q', 8)
        ControlInput.SHIFT_ALT_CTRL_F3 -> csiModified('R', 8)
        ControlInput.SHIFT_ALT_CTRL_F4 -> csiModified('S', 8)
        ControlInput.SHIFT_ALT_CTRL_F5 -> tildeModified(15, 8)
        ControlInput.SHIFT_ALT_CTRL_F6 -> tildeModified(17, 8)
        ControlInput.SHIFT_ALT_CTRL_F7 -> tildeModified(18, 8)
        ControlInput.SHIFT_ALT_CTRL_F8 -> tildeModified(19, 8)
        ControlInput.SHIFT_ALT_CTRL_F9 -> tildeModified(20, 8)
        ControlInput.SHIFT_ALT_CTRL_F10 -> tildeModified(21, 8)
        ControlInput.SHIFT_ALT_CTRL_F11 -> tildeModified(23, 8)
        ControlInput.SHIFT_ALT_CTRL_F12 -> tildeModified(24, 8)
    }
}

/**
 * 后台进程管理器
 *
 * 负责管理两类进程：
 * 1. 后台非交互进程（原有 container_shell_bg 行为）：输出到日志文件
 * 2. 交互式 session（container_shell_bg interactive=true）：支持 stdin / 实时输出 / 可选 tty
 */
@Singleton
class BackgroundProcessManager @Inject constructor(
    private val context: Context
) {
    private val prootManager: PRootManager
        get() = GlobalContext.get().get()

    companion object {
        private const val TAG = "BackgroundProcessManager"

        // 日志文件大小限制（10MB），internal 以便 PRootManager 访问
        internal const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB

        private const val PROCESS_CHECK_INTERVAL_MS = 5000L
        private const val MAX_RUNNING_PROCESSES_PER_SANDBOX = 10
        private const val MAX_INTERACTIVE_SESSIONS = 5
        private const val INTERACTIVE_BUFFER_MAX_BYTES = 256 * 1024
    }

    /**
     * 原有后台进程记录
     */
    private val processes = ConcurrentHashMap<String, BackgroundProcessInfo>()

    /**
     * 新增：交互式 session 记录
     */
    private val interactiveSessions = ConcurrentHashMap<String, InteractiveSessionRecord>()

    private val interactiveReadOffsets = ConcurrentHashMap<String, Long>()

    /**
     * 统一状态流（后台进程 + 交互 session）
     */
    private val _processStates = MutableStateFlow<List<BackgroundProcessInfo>>(emptyList())
    val processStates: StateFlow<List<BackgroundProcessInfo>> = _processStates.asStateFlow()

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null

    init {
        startProcessMonitoring()
    }

    /**
     * 交互式会话输出缓冲
     * 仅保留最近 maxBytes 数据，供 UI 展示和 tool read/logs 复用
     */
    private class SessionOutputBuffer(
        private val maxBytes: Int = INTERACTIVE_BUFFER_MAX_BYTES
    ) {
        private val data = ByteArrayOutputStream()
        private var baseOffset: Long = 0L
        private var totalWritten: Long = 0L

        @Synchronized
        fun append(bytes: ByteArray) {
            data.write(bytes)
            totalWritten += bytes.size
            val current = data.toByteArray()
            if (current.size > maxBytes) {
                val drop = current.size - maxBytes
                val trimmed = current.copyOfRange(drop, current.size)
                data.reset()
                data.write(trimmed)
                baseOffset += drop.toLong()
            }
        }

        @Synchronized
        fun snapshot(): ByteArray = data.toByteArray()

        @Synchronized
        fun snapshotAsString(): String = data.toByteArray().toString(Charsets.UTF_8)

        @Synchronized
        fun totalBytes(): Long = totalWritten

        @Synchronized
        fun readFrom(offset: Long, limitBytes: Int): BufferRead {
            val safeLimit = limitBytes.coerceIn(1, maxBytes)
            val effectiveOffset = offset.coerceAtLeast(baseOffset).coerceAtMost(totalWritten)
            val localStart = (effectiveOffset - baseOffset).toInt()
            val bytes = data.toByteArray()
            val end = minOf(localStart + safeLimit, bytes.size)
            val slice = if (localStart < end) bytes.copyOfRange(localStart, end) else ByteArray(0)
            val toOffset = baseOffset + end
            return BufferRead(
                content = slice.toString(StandardCharsets.UTF_8),
                fromOffset = effectiveOffset,
                toOffset = toOffset,
                totalBytes = totalWritten,
                baseOffset = baseOffset,
                hasMore = toOffset < totalWritten
            )
        }
    }

    data class BufferRead(
        val content: String,
        val fromOffset: Long,
        val toOffset: Long,
        val totalBytes: Long,
        val baseOffset: Long,
        val hasMore: Boolean,
        val terminalScreen: String? = null,
        val terminalScreenLines: List<String>? = null,
        val terminalVisibleContent: String? = null,
        val terminalVisibleContentLines: List<String>? = null,
        val terminalTopLines: List<String>? = null,
        val terminalTopLinesStartRow: Int? = null,
        val terminalTopLinesEndRow: Int? = null,
        val terminalBottomLines: List<String>? = null,
        val terminalBottomLinesStartRow: Int? = null,
        val terminalBottomLinesEndRow: Int? = null,
        val terminalNonEmptyRows: List<Int>? = null,
        val terminalNonEmptyLines: List<String>? = null,
        val terminalNonEmptyRowCount: Int? = null,
        val terminalInternalBlankRowCount: Int? = null,
        val terminalLeadingBlankRowCount: Int? = null,
        val terminalTrailingBlankRowCount: Int? = null,
        val terminalContentDensityPermille: Int? = null,
        val terminalViewportDensityPermille: Int? = null,
        val terminalFirstNonEmptyRow: Int? = null,
        val terminalLastNonEmptyRow: Int? = null,
        val terminalFirstNonEmptyLine: String? = null,
        val terminalLastNonEmptyLine: String? = null,
        val terminalContentHeight: Int? = null,
        val terminalIsEmpty: Boolean? = null,
        val terminalModes: String? = null,
        val terminalTitle: String? = null,
        val terminalColumns: Int? = null,
        val terminalRows: Int? = null,
        val terminalCursorRow: Int? = null,
        val terminalCursorColumn: Int? = null,
        val terminalRowsAboveCursor: Int? = null,
        val terminalRowsBelowCursor: Int? = null,
        val terminalCursorDistanceFromContentTop: Int? = null,
        val terminalCursorDistanceFromContentBottom: Int? = null,
        val terminalCursorContextLines: List<String>? = null,
        val terminalCursorContextStartRow: Int? = null,
        val terminalCursorContextEndRow: Int? = null,
        val terminalCursorContextCursorIndex: Int? = null,
        val terminalCursorLine: String? = null,
        val terminalCursorLineIsBlank: Boolean? = null,
        val terminalCursorLineLength: Int? = null,
        val terminalCursorLineLeadingBlankCount: Int? = null,
        val terminalCursorLineTrailingBlankCount: Int? = null,
        val terminalCursorLineFirstNonBlankColumn: Int? = null,
        val terminalCursorLineLastNonBlankColumn: Int? = null,
        val terminalCursorDistanceFromLineTextStart: Int? = null,
        val terminalCursorDistanceFromLineTextEnd: Int? = null,
        val terminalCursorLineCursorRegion: String? = null,
        val terminalCursorOnBlankLine: Boolean? = null,
        val terminalCursorBeforeLineText: Boolean? = null,
        val terminalCursorAtLineTextStart: Boolean? = null,
        val terminalCursorInsideLineText: Boolean? = null,
        val terminalCursorAtLineTextEnd: Boolean? = null,
        val terminalCursorAfterLineText: Boolean? = null,
        val terminalCursorTextColumn: Int? = null,
        val terminalCursorDistanceFromLineEnd: Int? = null,
        val terminalCursorLineTextBeforeCursor: String? = null,
        val terminalCursorLineTextAfterCursor: String? = null,
        val terminalCursorInVisibleContent: Boolean? = null,
        val terminalCursorVisibleContentRow: Int? = null,
        val terminalCursorVisibleContentLine: String? = null,
        val terminalCursorShape: String? = null,
        val terminalCursorVisible: Boolean? = null,
        val terminalWorkingDirectoryUri: String? = null,
        val terminalResponses: List<String>? = null,
        val terminalClipboardRequests: List<String>? = null
    )

    /**
     * 交互式 session 运行态记录
     */
    private data class InteractiveSessionRecord(
        val processId: String,
        val sandboxId: String,
        val command: String,
        val process: Process,
        val ttyEnabled: Boolean,
        val nativePtyEnabled: Boolean = false,
        val outputFlow: MutableSharedFlow<ByteArray>,
        val outputBuffer: SessionOutputBuffer,
        var columns: Int = 80,
        var rows: Int = 24,
        var stdoutJob: Job? = null,
        var stderrJob: Job? = null,
        var waiterJob: Job? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var startedAt: Long? = System.currentTimeMillis(),
        var exitedAt: Long? = null,
        var exitCode: Int? = null,
        var finalStatus: ProcessStatus? = null,
        var lastActivityAt: Long = System.currentTimeMillis(),
        val tag: String? = null
    )

    /**
     * 启动后台进程（原有逻辑）
     */
    suspend fun startBackgroundProcess(
        sandboxId: String,
        command: String,
        tag: String? = null
    ): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            val runningCount = processes.values
                .count { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }

            if (runningCount >= MAX_RUNNING_PROCESSES_PER_SANDBOX) {
                return@withContext ProcessExecutionResult(
                    success = false,
                    processId = "",
                    status = ProcessStatus.FAILED,
                    message = "Too many running processes (max $MAX_RUNNING_PROCESSES_PER_SANDBOX). Please stop some processes first."
                )
            }

            val processId = generateProcessId()
            val timestamp = System.currentTimeMillis()

            val logsDir = File(context.filesDir, "sandboxes/$sandboxId/logs").apply { mkdirs() }
            val stdoutFile = File(logsDir, "$processId.stdout.log")
            val stderrFile = File(logsDir, "$processId.stderr.log")

            val processInfo = BackgroundProcessInfo(
                processId = processId,
                sandboxId = sandboxId,
                command = command,
                status = ProcessStatus.STARTING,
                pid = null,
                stdoutPath = stdoutFile.absolutePath,
                stderrPath = stderrFile.absolutePath,
                createdAt = timestamp,
                startedAt = null,
                exitedAt = null,
                exitCode = null,
                tag = tag
            )

            val result = prootManager.execInBackground(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                processId = processId,
                stdoutFile = stdoutFile,
                stderrFile = stderrFile
            )

            if (result.exitCode == 0) {
                val startedAt = System.currentTimeMillis()
                val pid = extractPidFromOutput(result.stdout)

                val updatedInfo = processInfo.copy(
                    status = ProcessStatus.RUNNING,
                    pid = pid,
                    startedAt = startedAt
                )

                processes[processId] = updatedInfo
                refreshProcessStates()

                Log.i(TAG, "Started background process: $processId, command: $command")

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.RUNNING,
                    message = "Process started successfully",
                    stdoutFile = stdoutFile.absolutePath,
                    stderrFile = stderrFile.absolutePath,
                    pid = pid,
                    isInteractive = false,
                    stdinEnabled = false,
                    ttyEnabled = false
                )
            } else {
                val failedInfo = processInfo.copy(status = ProcessStatus.FAILED)
                processes[processId] = failedInfo
                refreshProcessStates()

                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Failed to start process: ${result.stderr}",
                    isInteractive = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting background process", e)
            ProcessExecutionResult(
                success = false,
                processId = "",
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = false
            )
        }
    }

    /**
     * 启动交互式 session
     *
     * 优先尝试使用 script 分配轻量 tty；
     * 如果容器内不存在 script，则自动 fallback 为普通 pipe 模式。
     */
    suspend fun startInteractiveSession(
        sandboxId: String,
        command: String,
        tag: String? = null,
        preferTty: Boolean = true,
        columns: Int = 80,
        rows: Int = 24
    ): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            val aliveInteractiveCount = interactiveSessions.values.count { it.process.isAlive }
            if (aliveInteractiveCount >= MAX_INTERACTIVE_SESSIONS) {
                return@withContext ProcessExecutionResult(
                    success = false,
                    processId = "",
                    status = ProcessStatus.FAILED,
                    message = "Too many interactive sessions (max $MAX_INTERACTIVE_SESSIONS)."
                )
            }

            val processId = generateProcessId()
            val nativePtyEnabled = preferTty && NativePtyBridge.isAvailable
            val scriptTtyEnabled = preferTty && !nativePtyEnabled && hasScriptCommand(sandboxId)
            val ttyEnabled = nativePtyEnabled || scriptTtyEnabled
            val initialColumns = columns.coerceIn(20, 240)
            val initialRows = rows.coerceIn(6, 80)

            val envPrefix = "export TERM=xterm-256color LINES=$initialRows COLUMNS=$initialColumns; " +
                "export FORCE_COLOR=1 COLORTERM=truecolor; " +
                "stty rows $initialRows cols $initialColumns 2>/dev/null || true; "
            var actualNativePtyEnabled = nativePtyEnabled
            var actualScriptTtyEnabled = scriptTtyEnabled
            val process = if (nativePtyEnabled) {
                try {
                    prootManager.execNativePty(
                        sandboxId = sandboxId,
                        command = listOf("sh", "-lc", "$envPrefix exec $command"),
                        columns = initialColumns,
                        rows = initialRows
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Native PTY failed, falling back to script/pipe", e)
                    actualNativePtyEnabled = false
                    actualScriptTtyEnabled = preferTty && hasScriptCommand(sandboxId)
                    val wrappedCommand = if (actualScriptTtyEnabled) {
                        val inner = "$envPrefix exec $command"
                        val quoted = shellQuote(inner)
                        "TERM=xterm-256color LINES=$initialRows COLUMNS=$initialColumns script -q -e -c $quoted /dev/null"
                    } else {
                        "TERM=dumb NO_COLOR=1 FORCE_COLOR=0 CLICOLOR=0 LINES=$initialRows COLUMNS=$initialColumns $command"
                    }
                    prootManager.execInteractive(
                        sandboxId = sandboxId,
                        command = listOf("sh", "-lc", wrappedCommand)
                    )
                }
            } else {
                val wrappedCommand = if (scriptTtyEnabled) {
                    val inner = "$envPrefix exec $command"
                    val quoted = shellQuote(inner)
                    "TERM=xterm-256color LINES=$initialRows COLUMNS=$initialColumns script -q -e -c $quoted /dev/null"
                } else {
                    "TERM=dumb NO_COLOR=1 FORCE_COLOR=0 CLICOLOR=0 LINES=$initialRows COLUMNS=$initialColumns $command"
                }
                prootManager.execInteractive(
                    sandboxId = sandboxId,
                    command = listOf("sh", "-lc", wrappedCommand)
                )
            }
            actualScriptTtyEnabled = !actualNativePtyEnabled && process !is NativePtyProcess && actualScriptTtyEnabled
            val actualTtyEnabled = actualNativePtyEnabled || actualScriptTtyEnabled

            val outputFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
            val outputBuffer = SessionOutputBuffer()

            val record = InteractiveSessionRecord(
                processId = processId,
                sandboxId = sandboxId,
                command = command,
                process = process,
                ttyEnabled = actualTtyEnabled,
                nativePtyEnabled = actualNativePtyEnabled,
                outputFlow = outputFlow,
                outputBuffer = outputBuffer,
                tag = tag,
                columns = initialColumns,
                rows = initialRows
            )

            interactiveSessions[processId] = record

            record.stdoutJob = launchStreamReader(
                inputStream = process.inputStream,
                outputFlow = outputFlow,
                buffer = outputBuffer
            ) {
                record.lastActivityAt = System.currentTimeMillis()
            }

            record.stderrJob = launchStreamReader(
                inputStream = process.errorStream,
                outputFlow = outputFlow,
                buffer = outputBuffer
            ) {
                record.lastActivityAt = System.currentTimeMillis()
            }

            record.waiterJob = appScope.launch {
                val code = try {
                    process.waitFor()
                } catch (_: Exception) {
                    -1
                }

                interactiveSessions[processId]?.let { session ->
                    drainNativePtyTail(session)
                    session.exitCode = code
                    session.exitedAt = System.currentTimeMillis()
                    if (session.finalStatus == null) {
                        session.finalStatus = if (code == 0) {
                            ProcessStatus.COMPLETED
                        } else {
                            ProcessStatus.FAILED
                        }
                    }
                }
                refreshProcessStates()
            }

            refreshProcessStates()

            ProcessExecutionResult(
                success = true,
                processId = processId,
                status = ProcessStatus.RUNNING,
                message = "Interactive session started",
                pid = tryGetPid(process),
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = actualTtyEnabled,
                terminalColumns = initialColumns,
                terminalRows = initialRows,
                terminalBackend = if (actualNativePtyEnabled) "native-pty" else if (actualScriptTtyEnabled) "script-sigwinch" else "pipe"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting interactive session", e)
            ProcessExecutionResult(
                success = false,
                processId = "",
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = true
            )
        }
    }

    /**
     * 获取进程信息（统一视图）
     */
    fun getProcess(processId: String): BackgroundProcessInfo? {
        refreshProcessStates()
        return _processStates.value.firstOrNull { it.processId == processId }
    }

    /**
     * 获取指定 sandbox 的所有进程
     */
    fun getProcessesBySandbox(sandboxId: String): List<BackgroundProcessInfo> {
        refreshProcessStates()
        return _processStates.value.filter { it.sandboxId == sandboxId }
    }

    /**
     * 获取所有进程
     */
    fun getAllProcesses(): List<BackgroundProcessInfo> {
        refreshProcessStates()
        return _processStates.value
    }

    /**
     * 向交互式 session 发送文本输入
     */
    suspend fun sendInput(
        processId: String,
        input: String,
        appendNewline: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext Result.failure(
                IllegalStateException("Interactive session not found: $processId")
            )

        try {
            val bytes = if (appendNewline) {
                (input + "\n").toByteArray(Charsets.UTF_8)
            } else {
                input.toByteArray(Charsets.UTF_8)
            }
            record.process.outputStream.write(bytes)
            record.process.outputStream.flush()
            record.lastActivityAt = System.currentTimeMillis()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending input to session: $processId", e)
            Result.failure(e)
        }
    }

    /**
     * 发送控制输入
     */
    suspend fun sendControlInput(
        processId: String,
        control: ControlInput
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext Result.failure(
                IllegalStateException("Interactive session not found: $processId")
            )

        try {
            val bytes = controlInputBytes(control)
            record.process.outputStream.write(bytes)
            record.process.outputStream.flush()
            record.lastActivityAt = System.currentTimeMillis()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending control input to session: $processId", e)
            Result.failure(e)
        }
    }

    /**
     * 观察交互式输出流
     */
    fun observeOutput(processId: String): Flow<ByteArray>? {
        return interactiveSessions[processId]?.outputFlow?.asSharedFlow()
    }

    /**
     * 读取交互式输出缓冲
     */
    fun readInteractiveBuffer(processId: String): String? {
        return interactiveSessions[processId]?.outputBuffer?.snapshotAsString()
    }

    fun readInteractiveOutput(
        processId: String,
        mode: String = "new",
        offset: Long? = null,
        limitBytes: Int = 64 * 1024,
        renderTerminal: Boolean = false
    ): BufferRead? {
        val record = interactiveSessions[processId] ?: return null
        val buffer = record.outputBuffer
        val fromOffset = when (mode) {
            "all" -> 0L
            "tail" -> (buffer.totalBytes() - limitBytes).coerceAtLeast(0L)
            else -> offset ?: interactiveReadOffsets[processId] ?: 0L
        }
        val result = buffer.readFrom(fromOffset, limitBytes)
        if (mode == "new") {
            interactiveReadOffsets[processId] = result.toOffset
        }
        return if (renderTerminal) result.withTerminalSnapshot(record) else result
    }

    private fun BufferRead.withTerminalSnapshot(record: InteractiveSessionRecord): BufferRead {
        val snapshot = renderInteractiveTerminalSnapshot(
            bytes = record.outputBuffer.snapshot(),
            columns = record.columns,
            rows = record.rows
        )
        return copy(
            terminalScreen = snapshot.screen,
            terminalScreenLines = snapshot.screenLines,
            terminalVisibleContent = snapshot.visibleContent,
            terminalVisibleContentLines = snapshot.visibleContentLines,
            terminalTopLines = snapshot.topLines,
            terminalTopLinesStartRow = snapshot.topLinesStartRow,
            terminalTopLinesEndRow = snapshot.topLinesEndRow,
            terminalBottomLines = snapshot.bottomLines,
            terminalBottomLinesStartRow = snapshot.bottomLinesStartRow,
            terminalBottomLinesEndRow = snapshot.bottomLinesEndRow,
            terminalNonEmptyRows = snapshot.nonEmptyRows,
            terminalNonEmptyLines = snapshot.nonEmptyLines,
            terminalNonEmptyRowCount = snapshot.nonEmptyRowCount,
            terminalInternalBlankRowCount = snapshot.internalBlankRowCount,
            terminalLeadingBlankRowCount = snapshot.leadingBlankRowCount,
            terminalTrailingBlankRowCount = snapshot.trailingBlankRowCount,
            terminalContentDensityPermille = snapshot.contentDensityPermille,
            terminalViewportDensityPermille = snapshot.viewportDensityPermille,
            terminalFirstNonEmptyRow = snapshot.firstNonEmptyRow,
            terminalLastNonEmptyRow = snapshot.lastNonEmptyRow,
            terminalFirstNonEmptyLine = snapshot.firstNonEmptyLine,
            terminalLastNonEmptyLine = snapshot.lastNonEmptyLine,
            terminalContentHeight = snapshot.contentHeight,
            terminalIsEmpty = snapshot.isEmpty,
            terminalModes = snapshot.modes,
            terminalTitle = snapshot.title,
            terminalColumns = snapshot.columns,
            terminalRows = snapshot.rows,
            terminalCursorRow = snapshot.cursorRow,
            terminalCursorColumn = snapshot.cursorColumn,
            terminalRowsAboveCursor = snapshot.rowsAboveCursor,
            terminalRowsBelowCursor = snapshot.rowsBelowCursor,
            terminalCursorDistanceFromContentTop = snapshot.cursorDistanceFromContentTop,
            terminalCursorDistanceFromContentBottom = snapshot.cursorDistanceFromContentBottom,
            terminalCursorContextLines = snapshot.cursorContextLines,
            terminalCursorContextStartRow = snapshot.cursorContextStartRow,
            terminalCursorContextEndRow = snapshot.cursorContextEndRow,
            terminalCursorContextCursorIndex = snapshot.cursorContextCursorIndex,
            terminalCursorLine = snapshot.cursorLine,
            terminalCursorLineIsBlank = snapshot.cursorLineIsBlank,
            terminalCursorLineLength = snapshot.cursorLineLength,
            terminalCursorLineLeadingBlankCount = snapshot.cursorLineLeadingBlankCount,
            terminalCursorLineTrailingBlankCount = snapshot.cursorLineTrailingBlankCount,
            terminalCursorLineFirstNonBlankColumn = snapshot.cursorLineFirstNonBlankColumn,
            terminalCursorLineLastNonBlankColumn = snapshot.cursorLineLastNonBlankColumn,
            terminalCursorDistanceFromLineTextStart = snapshot.cursorDistanceFromLineTextStart,
            terminalCursorDistanceFromLineTextEnd = snapshot.cursorDistanceFromLineTextEnd,
            terminalCursorLineCursorRegion = snapshot.cursorLineCursorRegion,
            terminalCursorOnBlankLine = snapshot.cursorOnBlankLine,
            terminalCursorBeforeLineText = snapshot.cursorBeforeLineText,
            terminalCursorAtLineTextStart = snapshot.cursorAtLineTextStart,
            terminalCursorInsideLineText = snapshot.cursorInsideLineText,
            terminalCursorAtLineTextEnd = snapshot.cursorAtLineTextEnd,
            terminalCursorAfterLineText = snapshot.cursorAfterLineText,
            terminalCursorTextColumn = snapshot.cursorTextColumn,
            terminalCursorDistanceFromLineEnd = snapshot.cursorDistanceFromLineEnd,
            terminalCursorLineTextBeforeCursor = snapshot.cursorLineTextBeforeCursor,
            terminalCursorLineTextAfterCursor = snapshot.cursorLineTextAfterCursor,
            terminalCursorInVisibleContent = snapshot.cursorInVisibleContent,
            terminalCursorVisibleContentRow = snapshot.cursorVisibleContentRow,
            terminalCursorVisibleContentLine = snapshot.cursorVisibleContentLine,
            terminalCursorShape = snapshot.cursorShape,
            terminalCursorVisible = snapshot.cursorVisible,
            terminalWorkingDirectoryUri = snapshot.workingDirectoryUri,
            terminalResponses = snapshot.responses,
            terminalClipboardRequests = snapshot.clipboardRequests
        )
    }

    /**
     * 更新交互式 session 的终端尺寸。Java Process 本身没有 PTY resize API；
     * 这里同步记录并向进程树发送 WINCH。script/util-linux 有机会据此刷新，
     * 同时新启动的 session 会使用动态 rows/columns 初始化 stty。
     */
    suspend fun resizeInteractiveSession(
        processId: String,
        columns: Int,
        rows: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext Result.failure(
                IllegalStateException("Interactive session not found: $processId")
            )
        val newColumns = columns.coerceIn(20, 240)
        val newRows = rows.coerceIn(6, 80)
        if (record.columns == newColumns && record.rows == newRows) {
            return@withContext Result.success(Unit)
        }
        record.columns = newColumns
        record.rows = newRows
        record.lastActivityAt = System.currentTimeMillis()
        if (record.nativePtyEnabled && record.process is NativePtyProcess) {
            record.process.resize(newColumns, newRows)
        } else if (record.ttyEnabled) {
            prootManager.signalProcessTree(record.process, "WINCH")
        }
        Result.success(Unit)
    }

    private suspend fun drainNativePtyTail(record: InteractiveSessionRecord, maxBytes: Int = 16 * 1024) {
        val nativeProcess = record.process as? NativePtyProcess ?: return
        val tail = nativeProcess.drainAvailable(maxBytes)
        if (tail.isNotEmpty()) {
            record.outputBuffer.append(tail)
            record.outputFlow.emit(tail)
            record.lastActivityAt = System.currentTimeMillis()
        }
    }

    /**
     * 关闭交互式会话
     */
    suspend fun closeInteractiveSession(processId: String): ProcessExecutionResult = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Interactive session not found: $processId"
            )

        try {
            if (record.process.isAlive) {
                record.finalStatus = ProcessStatus.STOPPED
                record.exitedAt = System.currentTimeMillis()

                prootManager.terminateProcessTree(record.process)
                if (!record.process.waitFor(800, TimeUnit.MILLISECONDS)) {
                    record.process.destroyForcibly()
                    prootManager.terminateProcessTree(record.process, force = true)
                }
            }

            record.exitCode = try {
                record.process.exitValue()
            } catch (_: Exception) {
                -1
            }

            drainNativePtyTail(record)

            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()
            record.waiterJob?.cancel()

            refreshProcessStates()

            ProcessExecutionResult(
                success = true,
                processId = processId,
                status = ProcessStatus.STOPPED,
                message = "Interactive session stopped",
                pid = tryGetPid(record.process),
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = record.ttyEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interactive session: $processId", e)
            ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = true
            )
        }
    }

    /**
     * 终止进程
     * 对交互 session 自动转到 closeInteractiveSession
     */
    suspend fun killProcess(processId: String): ProcessExecutionResult = withContext(Dispatchers.IO) {
        interactiveSessions[processId]?.let {
            return@withContext closeInteractiveSession(processId)
        }

        try {
            val managedProcess = processes[processId]
                ?: return@withContext ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Process not found: $processId"
                )

            val result = prootManager.killBackgroundProcess(processId)

            if (result.exitCode == 0) {
                val updatedInfo = managedProcess.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis()
                )
                processes[processId] = updatedInfo
                refreshProcessStates()

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.STOPPED,
                    message = "Process stopped successfully"
                )
            } else {
                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = managedProcess.status,
                    message = result.stderr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error killing process: $processId", e)
            ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * 读取进程日志
     * - 普通后台进程：读取日志文件
     * - 交互 session：读取内存缓冲
     */
    suspend fun readProcessLogs(
        processId: String,
        stream: String = "stdout",
        offset: Int = 0,
        limit: Int = 1000
    ): LogReadResult = withContext(Dispatchers.IO) {
        try {
            interactiveSessions[processId]?.let { session ->
                val allLines = session.outputBuffer.snapshotAsString().lines()
                val totalLines = allLines.size
                val lines = if (offset < totalLines) {
                    val end = minOf(offset + limit, totalLines)
                    allLines.subList(offset, end)
                } else {
                    emptyList()
                }

                return@withContext LogReadResult(
                    lines = lines,
                    totalLines = totalLines,
                    hasMore = offset + limit < totalLines
                )
            }

            val processInfo = processes[processId]
                ?: return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Process not found: $processId"
                )

            val logFile = when (stream) {
                "stdout" -> File(processInfo.stdoutPath)
                "stderr" -> File(processInfo.stderrPath)
                else -> return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Invalid stream: $stream"
                )
            }

            if (!logFile.exists()) {
                return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false
                )
            }

            val allLines = logFile.readLines()
            val totalLines = allLines.size

            val lines = if (offset < allLines.size) {
                val end = minOf(offset + limit, allLines.size)
                allLines.subList(offset, end)
            } else {
                emptyList()
            }

            LogReadResult(
                lines = lines,
                totalLines = totalLines,
                hasMore = offset + limit < totalLines
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logs for process: $processId", e)
            LogReadResult(
                lines = emptyList(),
                totalLines = 0,
                hasMore = false,
                error = "Error: ${e.message}"
            )
        }
    }

    /**
     * 删除单条进程记录（仅允许删除已结束记录）
     */
    fun removeProcessRecord(processId: String): Boolean {
        val interactive = interactiveSessions[processId]
        if (interactive != null) {
            if (interactive.process.isAlive) return false
            interactive.stdoutJob?.cancel()
            interactive.stderrJob?.cancel()
            interactive.waiterJob?.cancel()
            interactiveSessions.remove(processId)
            interactiveReadOffsets.remove(processId)
            refreshProcessStates()
            return true
        }

        val info = processes[processId] ?: return false
        if (info.status == ProcessStatus.RUNNING || info.status == ProcessStatus.STARTING) {
            return false
        }

        try {
            if (info.stdoutPath.isNotBlank()) File(info.stdoutPath).delete()
            if (info.stderrPath.isNotBlank()) File(info.stderrPath).delete()
        } catch (_: Exception) {
        }

        processes.remove(processId)
        refreshProcessStates()
        return true
    }

    /**
     * 清理已结束的旧进程记录
     */
    suspend fun cleanupOldProcesses(
        olderThan: Long = 24 * 60 * 60 * 1000L
    ): Int = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()

            val toRemove = processes.values
                .filter { it.exitedAt != null && (now - it.exitedAt!!) > olderThan }
                .map { it.processId }

            toRemove.forEach { processId ->
                val info = processes[processId]
                info?.let {
                    try {
                        File(it.stdoutPath).delete()
                        File(it.stderrPath).delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete log files for process $processId", e)
                    }
                }
                processes.remove(processId)
            }

            val interactiveToRemove = interactiveSessions.values
                .filter { it.exitedAt != null && (now - it.exitedAt!!) > olderThan }
                .map { it.processId }

            interactiveToRemove.forEach { processId ->
                interactiveSessions[processId]?.let { record ->
                    record.stdoutJob?.cancel()
                    record.stderrJob?.cancel()
                    record.waiterJob?.cancel()
                }
                interactiveSessions.remove(processId)
                interactiveReadOffsets.remove(processId)
            }

            refreshProcessStates()

            Log.i(
                TAG,
                "Cleaned up ${toRemove.size} old background processes and ${interactiveToRemove.size} old interactive sessions"
            )

            toRemove.size + interactiveToRemove.size
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old processes", e)
            0
        }
    }

    /**
     * 清理指定 sandbox 的所有进程
     */
    suspend fun cleanupSandboxProcesses(sandboxId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sandboxProcesses = processes.values
                .filter { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            sandboxProcesses.forEach { processId ->
                killProcess(processId)
            }

            val interactiveIds = interactiveSessions.values
                .filter { it.sandboxId == sandboxId }
                .map { it.processId }

            interactiveIds.forEach { processId ->
                closeInteractiveSession(processId)
            }

            val bgToRemove = processes.filter { it.value.sandboxId == sandboxId }.keys.toList()
            bgToRemove.forEach { processes.remove(it) }

            interactiveIds.forEach { processId ->
                interactiveSessions[processId]?.stdoutJob?.cancel()
                interactiveSessions[processId]?.stderrJob?.cancel()
                interactiveSessions[processId]?.waiterJob?.cancel()
                interactiveSessions.remove(processId)
                interactiveReadOffsets.remove(processId)
            }

            refreshProcessStates()
            Result.success(bgToRemove.size + interactiveIds.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止所有运行中的进程
     */
    suspend fun stopAllProcesses(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val runningProcesses = processes.values
                .filter { it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            runningProcesses.forEach { processId ->
                killProcess(processId)
            }

            interactiveSessions.keys.toList().forEach { processId ->
                closeInteractiveSession(processId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 标记所有运行中的进程为已停止（容器停止时调用）
     */
    fun markAllProcessesStopped() {
        val updatedProcesses = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING || process.status == ProcessStatus.STARTING) {
                process.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis(),
                    exitCode = -1
                )
            } else {
                process
            }
        }

        updatedProcesses.forEach { process ->
            processes[process.processId] = process
        }

        interactiveSessions.values.forEach { record ->
            record.finalStatus = ProcessStatus.STOPPED
            record.exitedAt = System.currentTimeMillis()
            record.exitCode = -1
            try {
                if (record.process.isAlive) {
                    record.process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }

        refreshProcessStates()
        Log.d(TAG, "All processes marked as stopped")
    }

    /**
     * 清理所有进程状态（容器销毁时调用）
     */
    fun clearAllProcesses() {
        interactiveSessions.values.forEach { record ->
            try {
                if (record.process.isAlive) {
                    record.process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()
            record.waiterJob?.cancel()
        }

        processes.clear()
        interactiveSessions.clear()
        interactiveReadOffsets.clear()
        _processStates.value = emptyList()
        Log.d(TAG, "All process states cleared")
    }

    /**
     * 生成进程ID
     */
    private fun generateProcessId(): String {
        val uuid = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis().toString(36)
        return "proc_${timestamp}_$uuid"
    }

    /**
     * 启动进程监控协程
     */
    private fun startProcessMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = appScope.launch {
            while (true) {
                try {
                    delay(PROCESS_CHECK_INTERVAL_MS)
                    updateProcessStates()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in process monitoring", e)
                }
            }
        }
        Log.i(TAG, "Process monitoring started")
    }

    /**
     * 周期性更新进程状态
     */
    private suspend fun updateProcessStates() {
        val updatedBackground = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING && process.pid != null) {
                if (!isProcessAlive(process.pid)) {
                    process.copy(
                        status = ProcessStatus.FAILED,
                        exitedAt = System.currentTimeMillis(),
                        exitCode = -1
                    )
                } else {
                    process
                }
            } else {
                process
            }
        }

        updatedBackground.forEach { process ->
            processes[process.processId] = process
        }

        interactiveSessions.values.forEach { session ->
            if (!session.process.isAlive && session.finalStatus == null) {
                val exitCode = try {
                    session.process.exitValue()
                } catch (_: Exception) {
                    -1
                }
                session.exitCode = exitCode
                session.exitedAt = System.currentTimeMillis()
                session.finalStatus = if (exitCode == 0) {
                    ProcessStatus.COMPLETED
                } else {
                    ProcessStatus.FAILED
                }
            }
        }

        refreshProcessStates()
    }

    /**
     * 统一重建状态流
     */
    private fun refreshProcessStates() {
        val backgroundList = processes.values.toList()

        val interactiveList = interactiveSessions.values.map { record ->
            val status = when {
                record.process.isAlive -> ProcessStatus.RUNNING
                record.finalStatus != null -> record.finalStatus!!
                record.exitCode == 0 -> ProcessStatus.COMPLETED
                else -> ProcessStatus.FAILED
            }

            BackgroundProcessInfo(
                processId = record.processId,
                sandboxId = record.sandboxId,
                command = record.command,
                status = status,
                pid = tryGetPid(record.process),
                stdoutPath = "",
                stderrPath = "",
                createdAt = record.createdAt,
                startedAt = record.startedAt,
                exitedAt = record.exitedAt,
                exitCode = record.exitCode,
                tag = record.tag,
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = record.ttyEnabled,
                terminalColumns = record.columns,
                terminalRows = record.rows,
                terminalBackend = if (record.nativePtyEnabled) "native-pty" else if (record.ttyEnabled) "script-sigwinch" else "pipe",
                processSource = "container_shell_bg"
            )
        }

        _processStates.value = (backgroundList + interactiveList)
            .sortedByDescending { it.createdAt }
    }

    /**
     * 读取交互流，按 byte chunk 处理
     */
    private fun launchStreamReader(
        inputStream: InputStream,
        outputFlow: MutableSharedFlow<ByteArray>,
        buffer: SessionOutputBuffer,
        onChunk: () -> Unit = {}
    ): Job = appScope.launch {
        try {
            val chunk = ByteArray(4096)
            while (isActive) {
                val read = inputStream.read(chunk)
                if (read < 0) break
                if (read == 0) continue

                val data = chunk.copyOf(read)
                buffer.append(data)
                outputFlow.emit(data)
                onChunk()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 容器内探测 script 命令
     */
    private suspend fun hasScriptCommand(sandboxId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val probe = prootManager.execInteractive(
                sandboxId = sandboxId,
                command = listOf(
                    "sh",
                    "-lc",
                    "if command -v script >/dev/null 2>&1; then printf 1; else printf 0; fi"
                )
            )

            val outputBuffer = ByteArrayOutputStream()
            val readerJob = appScope.launch {
                val chunk = ByteArray(64)
                while (isActive) {
                    val read = probe.inputStream.read(chunk)
                    if (read < 0) break
                    if (read > 0) outputBuffer.write(chunk, 0, read)
                }
            }
            val finished = try {
                probe.waitFor(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
            if (!finished) {
                try { probe.destroyForcibly() } catch (_: Exception) {}
            }
            readerJob.cancel()
            try {
                if (probe.isAlive) probe.destroyForcibly() else probe.destroy()
            } catch (_: Exception) {
            }

            outputBuffer.toString(Charsets.UTF_8.name()).trim() == "1"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查进程是否存活
     */
    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 从输出中提取 PID
     * 输出格式：Process started with PID: 12345
     */
    private fun extractPidFromOutput(output: String): Int? {
        return try {
            val pattern = Regex("PID:\\s*(\\d+)")
            val match = pattern.find(output)
            match?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract PID from output: $output", e)
            null
        }
    }

    private fun tryGetPid(process: Process): Int? {
        if (process is NativePtyProcess) return process.pidOrNull()
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.get(process) as? Int
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 对 shell 参数做单引号安全转义
     */
    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
