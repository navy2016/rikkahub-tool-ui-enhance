package me.rerere.rikkahub.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

/** Lightweight VT100/xterm screen emulator for the in-app terminal. */
class TerminalEmulator(
    initialColumns: Int = 80,
    initialRows: Int = 24,
    private val maxScrollbackLines: Int = 1000
) {
    var columns: Int = initialColumns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        private set
    var rows: Int = initialRows.coerceIn(MIN_ROWS, MAX_ROWS)
        private set

    companion object {
        const val MIN_COLUMNS = 20
        const val MAX_COLUMNS = 240
        const val MIN_ROWS = 6
        const val MAX_ROWS = 80
        private const val MAX_CSI_LENGTH = 256
        private const val MAX_STRING_SEQUENCE = 4096
        private const val ZERO_WIDTH_JOINER = 0x200D
    }

    private data class Hyperlink(val uri: String, val id: String? = null)

    private data class Style(
        val fg: Color = Color(0xFF00E676),
        val bg: Color? = null,
        val bold: Boolean = false,
        val faint: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
        val concealed: Boolean = false,
        val strike: Boolean = false,
        val overline: Boolean = false,
        val baselineShift: BaselineShift = BaselineShift.NORMAL,
        val protected: Boolean = false,
        val hyperlink: Hyperlink? = null
    )

    private data class Cell(
        var text: String = " ",
        var style: Style = Style(),
        var width: Int = 1,
        var continuation: Boolean = false
    )

    enum class CursorShape { DEFAULT, BLOCK, STEADY_BLOCK, UNDERLINE, STEADY_UNDERLINE, BAR, STEADY_BAR }

    enum class MouseButton { LEFT, MIDDLE, RIGHT, RELEASE, WHEEL_UP, WHEEL_DOWN, WHEEL_LEFT, WHEEL_RIGHT }
    enum class MouseEventType { PRESS, RELEASE, DRAG, MOVE, WHEEL }
    data class MouseEvent(
        val row: Int,
        val column: Int,
        val button: MouseButton = MouseButton.LEFT,
        val type: MouseEventType = MouseEventType.PRESS,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val ctrl: Boolean = false
    )

    private enum class MouseProtocol { DEFAULT, UTF8, SGR, SGR_PIXELS, URXVT }
    private enum class MouseTrackingMode { OFF, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }
    private enum class BaselineShift { NORMAL, SUPERSCRIPT, SUBSCRIPT }
    enum class Key {
        UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
        TAB, BACK_TAB, ENTER, ESCAPE, BACKSPACE,
        F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
        KP_0, KP_1, KP_2, KP_3, KP_4, KP_5, KP_6, KP_7, KP_8, KP_9,
        KP_DECIMAL, KP_ADD, KP_SUBTRACT, KP_MULTIPLY, KP_DIVIDE, KP_ENTER
    }

    private enum class ParserState { NORMAL, ESC, CSI, OSC, DCS, STRING_IGNORE, ESC_CHARSET_G0, ESC_CHARSET_G1 }

    private data class CsiSequence(
        val privateMarker: Char?,
        val params: List<String>,
        val intermediates: String,
        val final: Char
    )

    private data class SavedCursor(
        val row: Int,
        val col: Int,
        val style: Style,
        val originMode: Boolean,
        val lineDrawing: Boolean,
        val pendingWrap: Boolean
    )

    private val defaultStyle = Style()
    private var currentStyle = defaultStyle
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var savedCursor = SavedCursor(0, 0, defaultStyle, false, false, false)
    private var parserState = ParserState.NORMAL
    private var csiBuffer = StringBuilder()
    private var oscBuffer = StringBuilder()
    private var dcsBuffer = StringBuilder()
    private var oscEscSeen = false
    var title: String = ""
        private set
    var workingDirectoryUri: String = ""
        private set
    private var iconTitle: String = ""
    private val titleStack = ArrayDeque<String>()
    private val iconTitleStack = ArrayDeque<String>()
    private var cursorVisible = true
    private var reverseVideo = false
    private var wraparound = true
    private var pendingWrap = false
    private var originMode = false
    private var applicationCursorKeys = false
    private var applicationKeypad = false
    private var insertMode = false
    private var newlineMode = false
    private var bracketedPaste = false
    private var mouseTracking = false
    private var mouseTrackingMode = MouseTrackingMode.OFF
    private var mouseProtocol = MouseProtocol.DEFAULT
    private var focusReporting = false
    private var cursorShape = CursorShape.DEFAULT
    private var cursorSaveMode = false
    private var synchronizedOutput = false
    private var alternateScroll = false
    private var metaSendsEscape = false
    private var modifyCursorKeys = 0
    private var modifyFunctionKeys = 0
    private var modifyOtherKeys = 0
    private var formatOtherKeys = 0
    private var graphemeJoinPending = false
    private var lastGraphicText: String = ""
    private var lastGraphicCodePoint: Int = 0
    private val tabStops = sortedSetOf<Int>()
    private val clipboardRequests = mutableListOf<String>()
    private var currentHyperlink: Hyperlink? = null
    private val paletteOverrides = mutableMapOf<Int, Color>()
    private var defaultForeground = Color(0xFF00E676)
    private var defaultBackground = Color(0xFF101010)
    private var cursorColor = Color(0xFF00E676)
    private var alternateScreen = false
    private var lineDrawing = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private val pendingResponses = mutableListOf<String>()
    private var utf8Decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingUtf8 = ByteArray(0)

    private val scrollback = ArrayDeque<Array<Cell>>()
    private val mainScreen = MutableList(rows) { blankLine() }
    private val altScreen = MutableList(rows) { blankLine() }
    private val screen: MutableList<Array<Cell>> get() = if (alternateScreen) altScreen else mainScreen

    init {
        resetTabStops()
    }

    @Synchronized
    fun reset() {
        currentStyle = defaultStyle
        scrollback.clear()
        mainScreen.resetScreen()
        altScreen.resetScreen()
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        savedCursor = SavedCursor(0, 0, defaultStyle, false, false, false)
        parserState = ParserState.NORMAL
        csiBuffer.clear()
        oscBuffer.clear()
        dcsBuffer.clear()
        oscEscSeen = false
        pendingResponses.clear()
        clipboardRequests.clear()
        paletteOverrides.clear()
        defaultForeground = Color(0xFF00E676)
        defaultBackground = Color(0xFF101010)
        cursorColor = Color(0xFF00E676)
        resetDecoder()
        pendingUtf8 = ByteArray(0)
        cursorVisible = true
        reverseVideo = false
        resetTabStops()
        wraparound = true
        pendingWrap = false
        title = ""
        iconTitle = ""
        titleStack.clear()
        iconTitleStack.clear()
        workingDirectoryUri = ""
        originMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        insertMode = false
        newlineMode = false
        bracketedPaste = false
        mouseTracking = false
        mouseTrackingMode = MouseTrackingMode.OFF
        mouseProtocol = MouseProtocol.DEFAULT
        focusReporting = false
        cursorShape = CursorShape.DEFAULT
        cursorSaveMode = false
        synchronizedOutput = false
        alternateScroll = false
        metaSendsEscape = false
        modifyCursorKeys = 0
        modifyFunctionKeys = 0
        modifyOtherKeys = 0
        formatOtherKeys = 0
        graphemeJoinPending = false
        lastGraphicText = ""
        lastGraphicCodePoint = 0
        currentHyperlink = null
        alternateScreen = false
        lineDrawing = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun resize(columns: Int, rows: Int) {
        val newColumns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
        val newRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        if (newColumns == this.columns && newRows == this.rows) return

        this.columns = newColumns
        this.rows = newRows
        tabStops.removeIf { it >= newColumns }
        if (tabStops.isEmpty()) resetTabStops()
        mainScreen.resizeScreen(newRows, newColumns)
        altScreen.resizeScreen(newRows, newColumns)
        val resizedScrollback = scrollback.map { resizedLine(it, newColumns, defaultStyle) }
        scrollback.clear()
        resizedScrollback.takeLast(maxScrollbackLines).forEach { scrollback.addLast(it) }
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newColumns - 1)
        savedRow = savedRow.coerceIn(0, newRows - 1)
        savedCol = savedCol.coerceIn(0, newColumns - 1)
        savedCursor = savedCursor.copy(
            row = savedCursor.row.coerceIn(0, newRows - 1),
            col = savedCursor.col.coerceIn(0, newColumns - 1),
            pendingWrap = false
        )
        pendingWrap = false
    }

    @Synchronized
    fun softReset() {
        currentStyle = defaultStyle
        parserState = ParserState.NORMAL
        csiBuffer.clear()
        oscBuffer.clear()
        dcsBuffer.clear()
        oscEscSeen = false
        pendingResponses.clear()
        resetDecoder()
        pendingUtf8 = ByteArray(0)
        cursorVisible = true
        reverseVideo = false
        wraparound = true
        pendingWrap = false
        originMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        insertMode = false
        newlineMode = false
        bracketedPaste = false
        mouseTracking = false
        mouseTrackingMode = MouseTrackingMode.OFF
        mouseProtocol = MouseProtocol.DEFAULT
        focusReporting = false
        cursorShape = CursorShape.DEFAULT
        cursorSaveMode = false
        synchronizedOutput = false
        alternateScroll = false
        metaSendsEscape = false
        modifyCursorKeys = 0
        modifyFunctionKeys = 0
        modifyOtherKeys = 0
        formatOtherKeys = 0
        graphemeJoinPending = false
        lastGraphicText = ""
        lastGraphicCodePoint = 0
        currentHyperlink = null
        currentStyle = currentStyle.copy(hyperlink = null)
        lineDrawing = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        val source = if (pendingUtf8.isEmpty()) bytes else pendingUtf8 + bytes
        val input = ByteBuffer.wrap(source)
        val output = CharBuffer.allocate((source.size * 2).coerceAtLeast(16))
        while (true) {
            val result = utf8Decoder.decode(input, output, false)
            output.flip()
            if (output.hasRemaining()) {
                feed(output.toString())
            }
            output.clear()
            if (result.isOverflow) continue
            if (result.isUnderflow) {
                pendingUtf8 = if (input.hasRemaining()) {
                    ByteArray(input.remaining()).also { input.get(it) }
                } else {
                    ByteArray(0)
                }
                break
            }
            pendingUtf8 = ByteArray(0)
            break
        }
    }

    @Synchronized
    fun feed(text: String) {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val chars = String(Character.toChars(codePoint))
            if (parserState == ParserState.NORMAL && Character.charCount(codePoint) > 1) {
                putCodePoint(chars, codePoint)
            } else {
                chars.forEach { feedChar(it) }
            }
            index += Character.charCount(codePoint)
        }
    }

    @Synchronized
    fun drainResponses(): List<String> {
        if (pendingResponses.isEmpty()) return emptyList()
        val result = pendingResponses.toList()
        pendingResponses.clear()
        return result
    }

    @Synchronized
    fun drainClipboardRequests(): List<String> {
        if (clipboardRequests.isEmpty()) return emptyList()
        val result = clipboardRequests.toList()
        clipboardRequests.clear()
        return result
    }

    @Synchronized
    fun cursorShape(): CursorShape = cursorShape

    @Synchronized
    fun sequenceFor(key: Key, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        fun modifier(): Int = keyModifier(shift, alt, ctrl)
        fun maybeAlt(sequence: String): String = if (alt && !shift && !ctrl) "\u001B$sequence" else sequence
        fun csiModified(final: Char, normal: String): String = if (shift || alt || ctrl) "\u001B[1;${modifier()}$final" else normal
        fun tildeModified(code: Int, normal: String): String = if (shift || alt || ctrl) "\u001B[${code};${modifier()}~" else normal
        fun ss3Modified(final: Char, normal: String): String = if (shift || alt || ctrl) "\u001B[1;${modifier()}$final" else normal

        return when (key) {
            Key.UP -> csiModified('A', if (applicationCursorKeys) "\u001BOA" else "\u001B[A")
            Key.DOWN -> csiModified('B', if (applicationCursorKeys) "\u001BOB" else "\u001B[B")
            Key.RIGHT -> csiModified('C', if (applicationCursorKeys) "\u001BOC" else "\u001B[C")
            Key.LEFT -> csiModified('D', if (applicationCursorKeys) "\u001BOD" else "\u001B[D")
            Key.HOME -> csiModified('H', "\u001B[H")
            Key.END -> csiModified('F', "\u001B[F")
            Key.PAGE_UP -> tildeModified(5, "\u001B[5~")
            Key.PAGE_DOWN -> tildeModified(6, "\u001B[6~")
            Key.INSERT -> tildeModified(2, "\u001B[2~")
            Key.DELETE -> tildeModified(3, "\u001B[3~")
            Key.TAB -> if (shift) "\u001B[Z" else maybeAlt("\t")
            Key.BACK_TAB -> "\u001B[Z"
            Key.ENTER -> maybeAlt("\r")
            Key.ESCAPE -> "\u001B"
            Key.BACKSPACE -> if (ctrl) "\u0017" else maybeAlt("\u007F")
            Key.F1 -> ss3Modified('P', "\u001BOP")
            Key.F2 -> ss3Modified('Q', "\u001BOQ")
            Key.F3 -> ss3Modified('R', "\u001BOR")
            Key.F4 -> ss3Modified('S', "\u001BOS")
            Key.F5 -> tildeModified(15, "\u001B[15~")
            Key.F6 -> tildeModified(17, "\u001B[17~")
            Key.F7 -> tildeModified(18, "\u001B[18~")
            Key.F8 -> tildeModified(19, "\u001B[19~")
            Key.F9 -> tildeModified(20, "\u001B[20~")
            Key.F10 -> tildeModified(21, "\u001B[21~")
            Key.F11 -> tildeModified(23, "\u001B[23~")
            Key.F12 -> tildeModified(24, "\u001B[24~")
            Key.KP_0 -> maybeAlt(if (applicationKeypad) "\u001BOp" else "0")
            Key.KP_1 -> maybeAlt(if (applicationKeypad) "\u001BOq" else "1")
            Key.KP_2 -> maybeAlt(if (applicationKeypad) "\u001BOr" else "2")
            Key.KP_3 -> maybeAlt(if (applicationKeypad) "\u001BOs" else "3")
            Key.KP_4 -> maybeAlt(if (applicationKeypad) "\u001BOt" else "4")
            Key.KP_5 -> maybeAlt(if (applicationKeypad) "\u001BOu" else "5")
            Key.KP_6 -> maybeAlt(if (applicationKeypad) "\u001BOv" else "6")
            Key.KP_7 -> maybeAlt(if (applicationKeypad) "\u001BOw" else "7")
            Key.KP_8 -> maybeAlt(if (applicationKeypad) "\u001BOx" else "8")
            Key.KP_9 -> maybeAlt(if (applicationKeypad) "\u001BOy" else "9")
            Key.KP_DECIMAL -> maybeAlt(if (applicationKeypad) "\u001BOn" else ".")
            Key.KP_ADD -> maybeAlt(if (applicationKeypad) "\u001BOk" else "+")
            Key.KP_SUBTRACT -> maybeAlt(if (applicationKeypad) "\u001BOm" else "-")
            Key.KP_MULTIPLY -> maybeAlt(if (applicationKeypad) "\u001BOj" else "*")
            Key.KP_DIVIDE -> maybeAlt(if (applicationKeypad) "\u001BOo" else "/")
            Key.KP_ENTER -> maybeAlt(if (applicationKeypad) "\u001BOM" else "\r")
        }
    }


    @Synchronized
    fun sequenceForCodePoint(codePoint: Int, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        if (!Character.isValidCodePoint(codePoint)) return ""
        val text = String(Character.toChars(codePoint))
        val modified = shift || alt || ctrl
        if (modifyOtherKeys > 0 && modified) {
            val modifier = keyModifier(shift, alt, ctrl)
            return if (formatOtherKeys == 2) {
                "\u001B[${codePoint};${modifier}u"
            } else {
                "\u001B[27;${modifier};${codePoint}~"
            }
        }
        return when {
            ctrl && codePoint in 'a'.code..'z'.code -> ((codePoint - 'a'.code + 1).toChar()).toString()
            ctrl && codePoint in 'A'.code..'Z'.code -> ((codePoint - 'A'.code + 1).toChar()).toString()
            ctrl && codePoint == ' '.code -> "\u0000"
            ctrl && codePoint == '2'.code -> "\u0000"
            ctrl && codePoint == '['.code -> "\u001B"
            ctrl && codePoint == '3'.code -> "\u001B"
            ctrl && codePoint == '\\'.code -> "\u001C"
            ctrl && codePoint == '4'.code -> "\u001C"
            ctrl && codePoint == ']'.code -> "\u001D"
            ctrl && codePoint == '5'.code -> "\u001D"
            ctrl && codePoint == '^'.code -> "\u001E"
            ctrl && codePoint == '6'.code -> "\u001E"
            ctrl && codePoint == '_'.code -> "\u001F"
            ctrl && codePoint == '7'.code -> "\u001F"
            ctrl && codePoint == '?'.code -> "\u007F"
            ctrl && codePoint == '8'.code -> "\u007F"
            alt -> "\u001B$text"
            else -> text
        }
    }

    private fun keyModifier(shift: Boolean, alt: Boolean, ctrl: Boolean): Int {
        return 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    }

    @Synchronized
    fun wrapPaste(text: String): String {
        return if (bracketedPaste) bracketedPasteSequence(text) else text
    }

    fun bracketedPasteSequence(text: String): String = "\u001B[200~$text\u001B[201~"

    @Synchronized
    fun isMouseTrackingEnabled(): Boolean = mouseTracking

    @Synchronized
    fun mouseModeSummary(): String {
        if (mouseTrackingMode == MouseTrackingMode.OFF) return ""
        val mode = when (mouseTrackingMode) {
            MouseTrackingMode.OFF -> ""
            MouseTrackingMode.X10 -> "MOUSE-X10"
            MouseTrackingMode.NORMAL -> "MOUSE"
            MouseTrackingMode.BUTTON_EVENT -> "MOUSE-BUTTON"
            MouseTrackingMode.ANY_EVENT -> "MOUSE-ANY"
        }
        val protocol = when (mouseProtocol) {
            MouseProtocol.DEFAULT -> ""
            MouseProtocol.UTF8 -> "/UTF8"
            MouseProtocol.SGR -> "/SGR"
            MouseProtocol.SGR_PIXELS -> "/SGR-PIXELS"
            MouseProtocol.URXVT -> "/URXVT"
        }
        return mode + protocol
    }

    @Synchronized
    fun sequenceForMouse(event: MouseEvent): String? {
        if (mouseTrackingMode == MouseTrackingMode.OFF) return alternateScrollSequence(event)
        if (event.type == MouseEventType.MOVE && mouseTrackingMode != MouseTrackingMode.ANY_EVENT) return null
        if (event.type == MouseEventType.DRAG && mouseTrackingMode !in setOf(MouseTrackingMode.BUTTON_EVENT, MouseTrackingMode.ANY_EVENT)) return null
        if (event.type == MouseEventType.RELEASE && mouseTrackingMode == MouseTrackingMode.X10) return null
        val col = (event.column + 1).coerceIn(1, columns)
        val row = (event.row + 1).coerceIn(1, rows)
        val pixelCol = (event.column * 7 + 1).coerceAtLeast(1)
        val pixelRow = (event.row * 14 + 1).coerceAtLeast(1)
        var code = when (event.button) {
            MouseButton.LEFT -> 0
            MouseButton.MIDDLE -> 1
            MouseButton.RIGHT -> 2
            MouseButton.RELEASE -> 3
            MouseButton.WHEEL_UP -> 64
            MouseButton.WHEEL_DOWN -> 65
            MouseButton.WHEEL_LEFT -> 66
            MouseButton.WHEEL_RIGHT -> 67
        }
        if (event.type == MouseEventType.RELEASE) code = 3
        if (event.type == MouseEventType.DRAG) code += 32
        if (event.shift) code += 4
        if (event.alt) code += 8
        if (event.ctrl) code += 16
        return when (mouseProtocol) {
            MouseProtocol.SGR -> "\u001B[<${code};${col};${row}${if (event.type == MouseEventType.RELEASE) 'm' else 'M'}"
            MouseProtocol.SGR_PIXELS -> "\u001B[<${code};${pixelCol};${pixelRow}${if (event.type == MouseEventType.RELEASE) 'm' else 'M'}"
            MouseProtocol.URXVT -> "\u001B[${code + 32};${col};${row}M"
            else -> buildString {
                append("\u001B[M")
                append((32 + code).coerceIn(32, 255).toChar())
                append((32 + col).coerceIn(32, 255).toChar())
                append((32 + row).coerceIn(32, 255).toChar())
            }
        }
    }

    private fun alternateScrollSequence(event: MouseEvent): String? {
        if (!alternateScreen || !alternateScroll || event.type != MouseEventType.WHEEL) return null
        return when (event.button) {
            MouseButton.WHEEL_UP -> sequenceFor(Key.UP)
            MouseButton.WHEEL_DOWN -> sequenceFor(Key.DOWN)
            MouseButton.WHEEL_LEFT -> sequenceFor(Key.LEFT)
            MouseButton.WHEEL_RIGHT -> sequenceFor(Key.RIGHT)
            else -> null
        }
    }

    @Synchronized
    fun isFocusReportingEnabled(): Boolean = focusReporting

    @Synchronized
    fun sequenceForFocus(focused: Boolean): String? {
        return if (focusReporting) if (focused) "\u001B[I" else "\u001B[O" else null
    }

    @Synchronized
    fun modeSummary(): String = buildList {
        if (alternateScreen) add("ALT")
        if (reverseVideo) add("REVERSE-VIDEO")
        if (applicationCursorKeys) add("APP-CURSOR")
        if (applicationKeypad) add("APP-KEYPAD")
        if (bracketedPaste) add("BRACKETED-PASTE")
        if (synchronizedOutput) add("SYNC-OUTPUT")
        if (focusReporting) add("FOCUS")
        if (mouseTracking) add(mouseModeSummary())
        if (cursorShape != CursorShape.DEFAULT) add(cursorShape.name.replace('_', '-'))
        if (originMode) add("ORIGIN")
    }.joinToString(" · ")

    @Synchronized
    fun render(includeScrollback: Boolean = true): AnnotatedString = buildAnnotatedString {
        val active = screenWithCursor()
        val lines = if (includeScrollback && !alternateScreen) scrollback.toList() + active else active
        lines.forEachIndexed { index, line ->
            appendStyledLine(line)
            if (index != lines.lastIndex) append('\n')
        }
    }

    @Synchronized
    fun plainText(includeScrollback: Boolean = true): String {
        val active = screenWithCursor(drawCursor = false)
        val lines = if (includeScrollback && !alternateScreen) scrollback.toList() + active else active
        return lines.joinToString("\n") { line -> line.joinToString("") { if (it.continuation) "" else it.text }.trimEnd() }
    }

    private fun resetDecoder() {
        utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
    }

    private fun resizedLine(old: Array<Cell>, newColumns: Int, fillStyle: Style = currentStyle): Array<Cell> {
        return Array(newColumns) { index ->
            if (index < old.size) old[index].copy() else Cell(style = fillStyle)
        }
    }

    private fun MutableList<Array<Cell>>.resizeScreen(newRows: Int, newColumns: Int) {
        val old = toList()
        clear()
        val copyRows = min(old.size, newRows)
        repeat(copyRows) { row -> add(resizedLine(old[row], newColumns, defaultStyle)) }
        repeat(newRows - copyRows) { add(Array(newColumns) { Cell(style = defaultStyle) }) }
    }

    private fun MutableList<Array<Cell>>.resetScreen() {
        clear()
        repeat(rows) { add(blankLine()) }
    }

    private fun screenWithCursor(drawCursor: Boolean = true): List<Array<Cell>> {
        val copy = screen.map { line -> Array(columns) { i -> line[i].copy() } }
        if (drawCursor && cursorVisible && cursorRow in 0 until rows && cursorCol in 0 until columns) {
            val cell = copy[cursorRow][cursorCol]
            cell.style = cell.style.copy(inverse = !cell.style.inverse)
            if (cell.text == " " || cell.continuation) {
                cell.text = cursorGlyph()
                cell.width = 1
                cell.continuation = false
            }
        }
        return copy
    }

    private fun AnnotatedString.Builder.appendStyledLine(line: Array<Cell>) {
        val last = line.indexOfLast { !it.continuation && it.text != " " }.coerceAtLeast(0)
        var i = 0
        while (i <= last) {
            val style = line[i].style
            var j = i + 1
            while (j <= last && line[j].style == style) j++
            val start = length
            withStyle(style.toSpanStyle()) { for (k in i until j) if (!line[k].continuation) append(line[k].text) }
            style.hyperlink?.let { link ->
                addStringAnnotation(tag = "URL", annotation = link.uri, start = start, end = length)
            }
            i = j
        }
    }

    private fun feedChar(ch: Char) {
        when (parserState) {
            ParserState.NORMAL -> handleNormal(ch)
            ParserState.ESC -> handleEsc(ch)
            ParserState.CSI -> handleCsi(ch)
            ParserState.OSC -> handleOsc(ch)
            ParserState.DCS -> handleDcs(ch)
            ParserState.STRING_IGNORE -> handleStringTerminatedBySt(ch)
            ParserState.ESC_CHARSET_G0 -> {
                lineDrawing = ch == '0'
                parserState = ParserState.NORMAL
            }
            ParserState.ESC_CHARSET_G1 -> parserState = ParserState.NORMAL
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch) {
            '\u001B' -> parserState = ParserState.ESC
            '\u009B' -> {
                csiBuffer.clear()
                parserState = ParserState.CSI
            }
            '\u009D' -> {
                oscBuffer.clear()
                oscEscSeen = false
                parserState = ParserState.OSC
            }
            '\u0090' -> {
                dcsBuffer.clear()
                oscEscSeen = false
                parserState = ParserState.DCS
            }
            '\u0098', '\u009E', '\u009F' -> {
                oscEscSeen = false
                parserState = ParserState.STRING_IGNORE
            }
            '\u0084' -> {
                pendingWrap = false
                lineFeed()
            }
            '\u0085' -> {
                pendingWrap = false
                cursorCol = 0
                lineFeed()
            }
            '\u0088' -> tabStops.add(cursorCol)
            '\u008D' -> {
                pendingWrap = false
                reverseIndex()
            }
            '\r' -> {
                pendingWrap = false
                cursorCol = 0
            }
            '\n' -> {
                pendingWrap = false
                if (newlineMode) cursorCol = 0
                lineFeed()
            }
            '\u000E' -> lineDrawing = true
            '\u000F' -> lineDrawing = false
            '\b', '\u007F' -> {
                pendingWrap = false
                if (cursorCol > 0) cursorCol--
            }
            '\t' -> moveCursor(col = nextTabStop())
            in '\u0000'..'\u001F' -> Unit
            else -> putChar(if (lineDrawing) mapLineDrawing(ch) else ch)
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.clear()
                parserState = ParserState.CSI
            }
            ']' -> {
                oscBuffer.clear()
                oscEscSeen = false
                parserState = ParserState.OSC
            }
            'P' -> {
                dcsBuffer.clear()
                oscEscSeen = false
                parserState = ParserState.DCS
            }
            '^', '_', 'X' -> {
                oscEscSeen = false
                parserState = ParserState.STRING_IGNORE
            }
            'Z' -> {
                pendingResponses.add("\u001B[?1;2c")
                parserState = ParserState.NORMAL
            }
            '=' -> {
                applicationKeypad = true
                parserState = ParserState.NORMAL
            }
            '>' -> {
                applicationKeypad = false
                parserState = ParserState.NORMAL
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'c' -> reset()
            'D' -> {
                pendingWrap = false
                lineFeed()
            }
            'E' -> {
                pendingWrap = false
                cursorCol = 0
                lineFeed()
            }
            'M' -> {
                pendingWrap = false
                reverseIndex()
            }
            'H' -> {
                tabStops.add(cursorCol)
                parserState = ParserState.NORMAL
            }
            '(' -> parserState = ParserState.ESC_CHARSET_G0
            ')' -> parserState = ParserState.ESC_CHARSET_G1
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun handleOsc(ch: Char) {
        if (ch == '' || ch == '\u009C') {
            finishOsc()
            return
        }
        if (oscEscSeen && ch == '\\') {
            finishOsc()
            return
        }
        if (oscEscSeen) {
            if (oscBuffer.length < MAX_STRING_SEQUENCE) oscBuffer.append('\u001B')
            oscEscSeen = false
        }
        if (ch == '\u001B') {
            oscEscSeen = true
        } else if (oscBuffer.length < MAX_STRING_SEQUENCE) {
            oscBuffer.append(ch)
        }
    }

    private fun finishOsc() {
        val text = oscBuffer.toString()
        val sep = text.indexOf(';')
        if (sep > 0) {
            val code = text.substring(0, sep).toIntOrNull()
            val value = text.substring(sep + 1)
            when (code) {
                0 -> {
                    val normalized = value.take(MAX_STRING_SEQUENCE)
                    title = normalized
                    iconTitle = normalized
                }
                1 -> iconTitle = value.take(MAX_STRING_SEQUENCE)
                2 -> title = value.take(MAX_STRING_SEQUENCE)
                4 -> applyPaletteOsc(value)
                7 -> workingDirectoryUri = value.take(MAX_STRING_SEQUENCE)
                8 -> applyHyperlinkOsc(value)
                10 -> applyDynamicColorOsc(10, value)
                11 -> applyDynamicColorOsc(11, value)
                12 -> applyDynamicColorOsc(12, value)
                52 -> applyClipboardOsc(value)
                104 -> resetPaletteOsc(value)
                105, 106, 107 -> resetPaletteOsc(value)
                110 -> defaultForeground = defaultStyle.fg
                111 -> defaultBackground = Color(0xFF101010)
                112 -> cursorColor = defaultStyle.fg
            }
        }
        oscBuffer.clear()
        oscEscSeen = false
        parserState = ParserState.NORMAL
    }

    private fun resetPaletteOsc(value: String) {
        if (value.isBlank()) {
            paletteOverrides.clear()
            return
        }
        value.split(';').mapNotNull { it.toIntOrNull() }.forEach { paletteOverrides.remove(it.coerceIn(0, 255)) }
    }

    private fun applyPaletteOsc(value: String) {
        val parts = value.split(';')
        var index = 0
        while (index + 1 < parts.size) {
            val colorIndex = parts[index].toIntOrNull()
            val spec = parts[index + 1]
            if (colorIndex != null && colorIndex in 0..255) {
                if (spec == "?") {
                    pendingResponses.add("\u001B]4;${colorIndex};${colorToOscRgb(xterm256(colorIndex))}\u0007")
                } else {
                    parseOscColor(spec)?.let { paletteOverrides[colorIndex] = it }
                }
            }
            index += 2
        }
    }

    private fun applyDynamicColorOsc(code: Int, value: String) {
        if (value == "?") {
            val color = when (code) {
                10 -> defaultForeground
                11 -> defaultBackground
                12 -> cursorColor
                else -> defaultForeground
            }
            pendingResponses.add("\u001B]${code};${colorToOscRgb(color)}\u0007")
            return
        }
        parseOscColor(value)?.let { color ->
            when (code) {
                10 -> defaultForeground = color
                11 -> defaultBackground = color
                12 -> cursorColor = color
            }
        }
    }

    private fun parseOscColor(spec: String): Color? {
        val rgb = Regex("rgb:([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})").matchEntire(spec) ?: return null
        fun parse(component: String): Int {
            val raw = component.toInt(16)
            val max = (1 shl (component.length * 4)) - 1
            return ((raw * 255) / max).coerceIn(0, 255)
        }
        return rgbColor(parse(rgb.groupValues[1]), parse(rgb.groupValues[2]), parse(rgb.groupValues[3]))
    }

    private fun colorToOscRgb(color: Color): String {
        fun channel(value: Float): String = ((value.coerceIn(0f, 1f) * 65535f).toInt()).coerceIn(0, 65535).toString(16).padStart(4, '0')
        return "rgb:${channel(color.red)}/${channel(color.green)}/${channel(color.blue)}"
    }

    private fun applyClipboardOsc(value: String) {
        val selector = value.substringBefore(';', missingDelimiterValue = "c")
        val payload = value.substringAfter(';', missingDelimiterValue = "")
        if (payload == "?") {
            pendingResponses.add("\u001B]52;${selector};\u0007")
            return
        }
        if (payload.isBlank()) return
        runCatching {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        }.getOrNull()?.take(MAX_STRING_SEQUENCE)?.let { clipboardRequests.add(it) }
    }

    private fun applyHyperlinkOsc(value: String) {
        val secondSep = value.indexOf(';')
        if (secondSep < 0) return
        val params = value.substring(0, secondSep)
        val uri = value.substring(secondSep + 1)
        currentHyperlink = if (uri.isEmpty()) {
            null
        } else {
            val id = params.split(':', ';').firstNotNullOfOrNull { part ->
                part.substringAfter("id=", missingDelimiterValue = "").takeIf { it.isNotEmpty() }
            }
            Hyperlink(uri.take(MAX_STRING_SEQUENCE), id)
        }
        currentStyle = currentStyle.copy(hyperlink = currentHyperlink)
    }

    private fun handleDcs(ch: Char) {
        when {
            ch == '\u0007' || ch == '\u009C' -> {
                finishDcs()
                return
            }
            ch == '\u001B' -> {
                oscEscSeen = true
                return
            }
            oscEscSeen && ch == '\\' -> {
                finishDcs()
                return
            }
            oscEscSeen -> {
                if (dcsBuffer.length < MAX_STRING_SEQUENCE) dcsBuffer.append('\u001B')
                oscEscSeen = false
            }
        }
        if (dcsBuffer.length < MAX_STRING_SEQUENCE) dcsBuffer.append(ch)
    }

    private fun finishDcs() {
        val text = dcsBuffer.toString()
        when {
            text.startsWith("+q") -> handleXtGetTcap(text.drop(2))
            text.startsWith("\$q") -> handleRequestStatusString(text.drop(2))
        }
        dcsBuffer.clear()
        oscEscSeen = false
        parserState = ParserState.NORMAL
    }

    private fun handleRequestStatusString(payload: String) {
        val response = when (payload) {
            " q" -> "${cursorShapeCode()} q"
            "\"q" -> "${characterProtectionCode()}\"q"
            "m" -> sgrStatusString()
            "r" -> "${scrollTop + 1};${scrollBottom + 1}r"
            else -> null
        }
        if (response == null) {
            pendingResponses.add("\u001BP0\$r${payload}\u001B\\")
        } else {
            pendingResponses.add("\u001BP1\$r${response}\u001B\\")
        }
    }

    private fun sgrStatusString(): String {
        val codes = mutableListOf<Int>()
        if (currentStyle.bold) codes.add(1)
        if (currentStyle.faint) codes.add(2)
        if (currentStyle.italic) codes.add(3)
        if (currentStyle.underline) codes.add(4)
        if (currentStyle.inverse) codes.add(7)
        if (currentStyle.concealed) codes.add(8)
        if (currentStyle.strike) codes.add(9)
        if (currentStyle.overline) codes.add(53)
        when (currentStyle.baselineShift) {
            BaselineShift.SUPERSCRIPT -> codes.add(73)
            BaselineShift.SUBSCRIPT -> codes.add(74)
            BaselineShift.NORMAL -> Unit
        }
        if (codes.isEmpty()) codes.add(0)
        return codes.joinToString(";") + "m"
    }

    private fun characterProtectionCode(): Int = if (currentStyle.protected) 1 else 0

    private fun cursorShapeCode(): Int = when (cursorShape) {
        CursorShape.DEFAULT -> 0
        CursorShape.BLOCK -> 1
        CursorShape.STEADY_BLOCK -> 2
        CursorShape.UNDERLINE -> 3
        CursorShape.STEADY_UNDERLINE -> 4
        CursorShape.BAR -> 5
        CursorShape.STEADY_BAR -> 6
    }

    private fun handleXtGetTcap(payload: String) {
        payload.split(';')
            .mapNotNull { encoded ->
                val name = hexDecodeAscii(encoded)
                if (name.isEmpty()) null else encoded to name
            }
            .forEach { (encoded, name) ->
                val value = xtGetTcapValue(name)
                if (value == null) {
                    pendingResponses.add("\u001BP0+r${encoded}\u001B\\")
                } else {
                    pendingResponses.add("\u001BP1+r${encoded}=${asciiToHex(value)}\u001B\\")
                }
            }
    }

    private fun xtGetTcapValue(name: String): String? = when (name) {
        "TN" -> "xterm-256color"
        "Co", "colors" -> "256"
        "RGB", "Tc" -> "1"
        "Ms" -> "\u001B]52;%p1%s;%p2%s\u0007"
        "Se" -> "\u001B[2 q"
        "Ss" -> "\u001B[%p1%d q"
        else -> null
    }

    private fun hexDecodeAscii(hex: String): String {
        if (hex.length % 2 != 0) return ""
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return ""
        return hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    }

    private fun asciiToHex(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    private fun handleStringTerminatedBySt(ch: Char) {
        if (ch == '\u009C' || (oscEscSeen && ch == '\\')) {
            parserState = ParserState.NORMAL
            oscEscSeen = false
            return
        }
        oscEscSeen = ch == '\u001B'
    }

    private fun handleCsi(ch: Char) {
        if (ch in '@'..'~') {
            executeCsi(csiBuffer.toString(), ch)
            parserState = ParserState.NORMAL
            csiBuffer.clear()
        } else {
            if (csiBuffer.length < MAX_CSI_LENGTH) {
                csiBuffer.append(ch)
            } else {
                csiBuffer.clear()
                parserState = ParserState.NORMAL
            }
        }
    }

    private fun parseCsi(raw: String, final: Char): CsiSequence {
        var index = 0
        var privateMarker: Char? = null
        if (raw.isNotEmpty() && raw[0] in charArrayOf('?', '>', '<', '=')) {
            privateMarker = raw[0]
            index = 1
        }
        val params = StringBuilder()
        val intermediates = StringBuilder()
        while (index < raw.length) {
            val c = raw[index]
            when (c) {
                in '0'..'?' -> params.append(c)
                in ' '..'/' -> intermediates.append(c)
            }
            index++
        }
        val paramList = if (params.isEmpty()) emptyList() else params.toString().split(';')
        return CsiSequence(privateMarker, paramList, intermediates.toString(), final)
    }

    private fun CsiSequence.paramInt(index: Int, default: Int): Int {
        return params.getOrNull(index)
            ?.substringBefore(':')
            ?.toIntOrNull()
            ?.takeIf { it != 0 }
            ?: default
    }

    private fun CsiSequence.paramZero(index: Int): Int {
        return params.getOrNull(index)?.substringBefore(':')?.toIntOrNull() ?: 0
    }

    private fun CsiSequence.intParams(): List<Int> = params.mapNotNull { it.substringBefore(':').toIntOrNull() }

    private fun moveCursor(row: Int = cursorRow, col: Int = cursorCol) {
        pendingWrap = false
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, columns - 1)
    }

    private fun executeCsi(raw: String, command: Char) {
        val seq = parseCsi(raw, command)
        if (seq.privateMarker == '<' && (command == 'M' || command == 'm')) return // xterm mouse report

        when (command) {
            'A' -> if (seq.intermediates == " ") scrollRight(seq.paramInt(0, 1)) else moveCursor(row = (cursorRow - seq.paramInt(0, 1)).coerceAtLeast(scrollTop))
            'B' -> moveCursor(row = (cursorRow + seq.paramInt(0, 1)).coerceAtMost(scrollBottom))
            'C' -> moveCursor(col = (cursorCol + seq.paramInt(0, 1)).coerceAtMost(columns - 1))
            'D' -> moveCursor(col = (cursorCol - seq.paramInt(0, 1)).coerceAtLeast(0))
            'E' -> moveCursor(row = (cursorRow + seq.paramInt(0, 1)).coerceAtMost(scrollBottom), col = 0)
            'F' -> moveCursor(row = (cursorRow - seq.paramInt(0, 1)).coerceAtLeast(scrollTop), col = 0)
            'G', '`' -> moveCursor(col = seq.paramInt(0, 1) - 1)
            'I' -> moveCursor(col = nextTabStop(seq.paramInt(0, 1)))
            'a' -> moveCursor(col = (cursorCol + seq.paramInt(0, 1)).coerceAtMost(columns - 1))
            'e' -> moveCursor(row = (cursorRow + seq.paramInt(0, 1)).coerceAtMost(scrollBottom))
            'H', 'f' -> {
                val targetRow = seq.paramInt(0, 1) - 1
                val row = if (originMode) (scrollTop + targetRow).coerceIn(scrollTop, scrollBottom) else targetRow.coerceIn(0, rows - 1)
                moveCursor(row = row, col = seq.paramInt(1, 1) - 1)
            }
            'J' -> eraseDisplay(seq.paramZero(0), selective = seq.privateMarker == '?')
            'K' -> eraseLine(seq.paramZero(0), selective = seq.privateMarker == '?')
            'm' -> applySgr(seq.params.ifEmpty { listOf("0") })
            's' -> saveCursor()
            'u' -> restoreCursor()
            'L' -> repeat(seq.paramInt(0, 1)) { insertLine() }
            'M' -> repeat(seq.paramInt(0, 1)) { deleteLine() }
            'P' -> deleteChars(seq.paramInt(0, 1))
            '@' -> if (seq.intermediates == " ") scrollLeft(seq.paramInt(0, 1)) else insertChars(seq.paramInt(0, 1))
            'X' -> eraseChars(seq.paramInt(0, 1))
            'S' -> repeat(seq.paramInt(0, 1)) { scrollUp() }
            'T' -> repeat(seq.paramInt(0, 1)) { scrollDown() }
            'Z' -> moveCursor(col = previousTabStop(seq.paramInt(0, 1)))
            'b' -> repeatLastGraphic(seq.paramInt(0, 1))
            'c' -> handleDeviceAttributes(seq)
            'd' -> {
                val targetRow = seq.paramInt(0, 1) - 1
                val row = if (originMode) (scrollTop + targetRow).coerceIn(scrollTop, scrollBottom) else targetRow.coerceIn(0, rows - 1)
                moveCursor(row = row)
            }
            'n' -> handleDeviceStatusReport(seq)
            'g' -> clearTabStops(seq.paramZero(0))
            'W' -> handleCursorTabControl(seq)
            'q' -> when {
                seq.intermediates == " " -> setCursorShape(seq.paramZero(0))
                seq.intermediates == "\"" -> setCharacterProtection(seq.paramZero(0))
                seq.privateMarker == '>' -> pendingResponses.add("\u001BP>|RikkaHubTerminal 1.0\u001B\\")
            }
            'p' -> if (seq.intermediates == "!") softReset() else if (seq.intermediates == "$") handleRequestMode(seq)
            'r' -> if (seq.intermediates == "$") changeRectangleAttributes(seq, reverse = false) else setScrollRegion(seq.paramInt(0, 1), seq.paramInt(1, rows))
            'x' -> if (seq.intermediates == "$") fillRectangle(seq)
            'y' -> if (seq.intermediates == "*") reportRectangleChecksum(seq)
            'z' -> if (seq.intermediates == "$") eraseRectangle(seq, selective = false)
            '{' -> if (seq.intermediates == "$") eraseRectangle(seq, selective = true)
            't' -> if (seq.intermediates == "$") changeRectangleAttributes(seq, reverse = true) else handleWindowOperation(seq)
            'v' -> if (seq.intermediates == "$") copyRectangle(seq)
            'h' -> if (seq.privateMarker == '?') setPrivateModes(seq.intParams(), true) else setModes(seq.intParams(), true)
            'l' -> if (seq.privateMarker == '?') setPrivateModes(seq.intParams(), false) else setModes(seq.intParams(), false)
        }
    }

    private fun putChar(ch: Char) {
        putCodePoint(ch.toString(), ch.code)
    }

    private fun putCodePoint(text: String, codePoint: Int) {
        if (shouldAppendToPreviousCell(codePoint)) {
            appendToPreviousCell(text)
            graphemeJoinPending = codePoint == ZERO_WIDTH_JOINER
            return
        }
        graphemeJoinPending = false
        if (pendingWrap) {
            cursorCol = 0
            lineFeed()
            pendingWrap = false
        }
        val width = codePointCellWidth(codePoint)
        if (width == 0) return
        lastGraphicText = text
        lastGraphicCodePoint = codePoint
        if (width == 2 && cursorCol == columns - 1) {
            screen[cursorRow][cursorCol] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
            cursorCol = 0
            lineFeed()
        }
        if (insertMode) insertChars(width)
        clearCellForWrite(cursorRow, cursorCol)
        screen[cursorRow][cursorCol] = Cell(text, currentStyle.copy(hyperlink = currentHyperlink), width, continuation = false)
        if (width == 2 && cursorCol + 1 < columns) {
            screen[cursorRow][cursorCol + 1] = Cell("", currentStyle.copy(hyperlink = currentHyperlink), 0, continuation = true)
        }
        if (cursorCol + width >= columns) {
            cursorCol = columns - 1
            pendingWrap = wraparound
        } else {
            cursorCol += width
            pendingWrap = false
        }
    }

    private fun repeatLastGraphic(count: Int) {
        if (lastGraphicText.isEmpty() || lastGraphicCodePoint == 0) return
        repeat(count.coerceIn(1, columns * rows)) {
            putCodePoint(lastGraphicText, lastGraphicCodePoint)
        }
    }

    private fun appendToPreviousCell(text: String) {
        var col = cursorCol - 1
        if (col in 0 until columns && screen[cursorRow][col].continuation) col--
        if (col in 0 until columns) {
            screen[cursorRow][col].text += text
        }
    }

    private fun clearCellForWrite(row: Int, col: Int) {
        if (screen[row][col].continuation && col > 0) {
            screen[row][col - 1] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
        }
        if (col + 1 < columns && screen[row][col + 1].continuation) {
            screen[row][col + 1] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
        }
    }

    private fun shouldAppendToPreviousCell(codePoint: Int): Boolean {
        if (cursorCol <= 0) return false
        if (codePoint == ZERO_WIDTH_JOINER) return true
        if (graphemeJoinPending) return true
        if (isCombiningCodePoint(codePoint)) return true
        if (isRegionalIndicator(codePoint)) {
            val previous = previousBaseCellText() ?: return false
            val trailingRegionalIndicators = previous.codePoints().toArray().takeLastWhile { isRegionalIndicator(it) }.size
            return trailingRegionalIndicators % 2 == 1
        }
        return false
    }

    private fun previousBaseCellText(): String? {
        var col = cursorCol - 1
        if (col in 0 until columns && screen[cursorRow][col].continuation) col--
        return if (col in 0 until columns) screen[cursorRow][col].text else null
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isCombiningCodePoint(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF
    }

    private fun codePointCellWidth(codePoint: Int): Int {
        if (codePoint == 0) return 0
        if (codePoint < 32 || codePoint in 0x7F..0x9F) return 0
        if (isCombiningCodePoint(codePoint)) return 0
        return if (
            codePoint in 0x1100..0x115F || codePoint in 0x2329..0x232A || codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7A3 || codePoint in 0xF900..0xFAFF || codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F || codePoint in 0xFF00..0xFF60 || codePoint in 0xFFE0..0xFFE6 ||
            codePoint in 0x1F000..0x1FAFF || codePoint in 0x20000..0x3FFFD
        ) 2 else 1
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp()
        } else {
            cursorRow = (cursorRow + 1).coerceAtMost(rows - 1)
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown()
        } else {
            cursorRow = (cursorRow - 1).coerceAtLeast(0)
        }
        parserState = ParserState.NORMAL
    }

    private fun scrollUp() {
        val removed = screen[scrollTop]
        if (!alternateScreen && scrollTop == 0) {
            scrollback.addLast(Array(columns) { i -> removed[i].copy() })
            while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
        }
        for (r in scrollTop until scrollBottom) {
            screen[r] = screen[r + 1]
        }
        screen[scrollBottom] = blankLine()
    }

    private fun scrollDown() {
        for (r in scrollBottom downTo scrollTop + 1) {
            screen[r] = screen[r - 1]
        }
        screen[scrollTop] = blankLine()
    }

    private fun eraseDisplay(mode: Int, selective: Boolean = false) {
        pendingWrap = false
        when (mode) {
            0 -> {
                eraseLine(0, selective)
                for (r in cursorRow + 1 until rows) eraseLineRange(r, 0, columns - 1, selective)
            }
            1 -> {
                eraseLine(1, selective)
                for (r in 0 until cursorRow) eraseLineRange(r, 0, columns - 1, selective)
            }
            2 -> {
                for (r in 0 until rows) eraseLineRange(r, 0, columns - 1, selective)
                cursorRow = 0
                cursorCol = 0
            }
            3 -> if (!selective) scrollback.clear()
        }
    }

    private fun eraseLine(mode: Int, selective: Boolean = false) {
        pendingWrap = false
        when (mode) {
            0 -> eraseLineRange(cursorRow, cursorCol, columns - 1, selective)
            1 -> eraseLineRange(cursorRow, 0, cursorCol, selective)
            2 -> eraseLineRange(cursorRow, 0, columns - 1, selective)
        }
    }

    private fun eraseLineRange(row: Int, startCol: Int, endCol: Int, selective: Boolean) {
        if (row !in 0 until rows || endCol < startCol) return
        for (c in startCol.coerceAtLeast(0)..endCol.coerceAtMost(columns - 1)) {
            if (!selective || !screen[row][c].style.protected) {
                screen[row][c] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
            }
        }
    }

    private fun eraseRectangle(seq: CsiSequence, selective: Boolean) {
        pendingWrap = false
        val top = (seq.paramInt(0, 1) - 1).coerceIn(0, rows - 1)
        val left = (seq.paramInt(1, 1) - 1).coerceIn(0, columns - 1)
        val bottom = (seq.paramInt(2, rows) - 1).coerceIn(top, rows - 1)
        val right = (seq.paramInt(3, columns) - 1).coerceIn(left, columns - 1)
        for (row in top..bottom) eraseLineRange(row, left, right, selective)
    }

    private fun reportRectangleChecksum(seq: CsiSequence) {
        pendingWrap = false
        val requestId = seq.paramZero(0).coerceIn(0, 65535)
        val top = (seq.paramInt(2, 1) - 1).coerceIn(0, rows - 1)
        val left = (seq.paramInt(3, 1) - 1).coerceIn(0, columns - 1)
        val bottom = (seq.paramInt(4, rows) - 1).coerceIn(top, rows - 1)
        val right = (seq.paramInt(5, columns) - 1).coerceIn(left, columns - 1)
        val checksum = rectangleChecksum(top, left, bottom, right)
        pendingResponses.add("\u001BP${requestId}!~${checksum.toString(16).uppercase().padStart(4, '0')}\u001B\\")
    }

    private fun rectangleChecksum(top: Int, left: Int, bottom: Int, right: Int): Int {
        var checksum = 0
        for (row in top..bottom) {
            for (col in left..right) {
                checksum = (checksum + cellChecksum(screen[row][col])) and 0xFFFF
            }
        }
        return checksum
    }

    private fun cellChecksum(cell: Cell): Int {
        var value = cell.width and 0xFF
        if (cell.continuation) value += 0x80
        cell.text.codePoints().forEach { value = (value + it) and 0xFFFF }
        val style = cell.style
        if (style.bold) value += 0x0100
        if (style.faint) value += 0x0200
        if (style.italic) value += 0x0400
        if (style.underline) value += 0x0800
        if (style.inverse) value += 0x1000
        if (style.concealed) value += 0x2000
        if (style.strike) value += 0x4000
        if (style.protected) value += 0x8000
        return value and 0xFFFF
    }

    private fun copyRectangle(seq: CsiSequence) {
        pendingWrap = false
        val sourceTop = (seq.paramInt(0, 1) - 1).coerceIn(0, rows - 1)
        val sourceLeft = (seq.paramInt(1, 1) - 1).coerceIn(0, columns - 1)
        val sourceBottom = (seq.paramInt(2, rows) - 1).coerceIn(sourceTop, rows - 1)
        val sourceRight = (seq.paramInt(3, columns) - 1).coerceIn(sourceLeft, columns - 1)
        val destTop = (seq.paramInt(5, 1) - 1).coerceIn(0, rows - 1)
        val destLeft = (seq.paramInt(6, 1) - 1).coerceIn(0, columns - 1)
        val snapshot = (sourceTop..sourceBottom).map { row ->
            (sourceLeft..sourceRight).map { col -> screen[row][col].copy() }
        }
        snapshot.forEachIndexed { rowOffset, copiedRow ->
            val destRow = destTop + rowOffset
            if (destRow in 0 until rows) {
                copiedRow.forEachIndexed { colOffset, cell ->
                    val destCol = destLeft + colOffset
                    if (destCol in 0 until columns) screen[destRow][destCol] = cell.copy()
                }
            }
        }
    }

    private fun fillRectangle(seq: CsiSequence) {
        pendingWrap = false
        val codePoint = seq.paramInt(0, 32).coerceIn(32, 0x10FFFF)
        val fillText = codePointToString(codePoint) ?: " "
        val width = codePointCellWidth(codePoint)
        val top = (seq.paramInt(1, 1) - 1).coerceIn(0, rows - 1)
        val left = (seq.paramInt(2, 1) - 1).coerceIn(0, columns - 1)
        val bottom = (seq.paramInt(3, rows) - 1).coerceIn(top, rows - 1)
        val right = (seq.paramInt(4, columns) - 1).coerceIn(left, columns - 1)
        for (row in top..bottom) {
            var col = left
            while (col <= right) {
                screen[row][col] = Cell(fillText, currentStyle.copy(hyperlink = currentHyperlink), width, continuation = false)
                if (width == 2 && col + 1 <= right) {
                    screen[row][col + 1] = Cell("", currentStyle.copy(hyperlink = currentHyperlink), 0, continuation = true)
                    col += 2
                } else {
                    col++
                }
            }
        }
    }

    private fun codePointToString(codePoint: Int): String? = try {
        String(Character.toChars(codePoint))
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun changeRectangleAttributes(seq: CsiSequence, reverse: Boolean) {
        pendingWrap = false
        val top = (seq.paramInt(0, 1) - 1).coerceIn(0, rows - 1)
        val left = (seq.paramInt(1, 1) - 1).coerceIn(0, columns - 1)
        val bottom = (seq.paramInt(2, rows) - 1).coerceIn(top, rows - 1)
        val right = (seq.paramInt(3, columns) - 1).coerceIn(left, columns - 1)
        val attributes = seq.params.drop(4).map { it.substringBefore(':').toIntOrNull() ?: 0 }.ifEmpty { listOf(0) }
        for (row in top..bottom) {
            for (col in left..right) {
                val cell = screen[row][col]
                cell.style = if (reverse) reverseCharacterAttributes(cell.style, attributes) else changeCharacterAttributes(cell.style, attributes)
            }
        }
    }

    private fun changeCharacterAttributes(style: Style, attributes: List<Int>): Style {
        var updated = style
        attributes.forEach { code ->
            updated = when (code) {
                0 -> updated.copy(
                    bold = false,
                    faint = false,
                    italic = false,
                    underline = false,
                    inverse = false,
                    concealed = false,
                    strike = false,
                    overline = false,
                    baselineShift = BaselineShift.NORMAL
                )
                1 -> updated.copy(bold = true, faint = false)
                4 -> updated.copy(underline = true)
                7 -> updated.copy(inverse = true)
                22 -> updated.copy(bold = false, faint = false)
                24 -> updated.copy(underline = false)
                27 -> updated.copy(inverse = false)
                else -> updated
            }
        }
        return updated
    }

    private fun reverseCharacterAttributes(style: Style, attributes: List<Int>): Style {
        var updated = style
        attributes.forEach { code ->
            updated = when (code) {
                1 -> updated.copy(bold = !updated.bold, faint = false)
                4 -> updated.copy(underline = !updated.underline)
                7 -> updated.copy(inverse = !updated.inverse)
                else -> updated
            }
        }
        return updated
    }

    private fun insertLine() {
        pendingWrap = false
        if (cursorRow !in scrollTop..scrollBottom) return
        for (r in scrollBottom downTo cursorRow + 1) {
            screen[r] = screen[r - 1]
        }
        screen[cursorRow] = blankLine()
    }

    private fun deleteLine() {
        pendingWrap = false
        if (cursorRow !in scrollTop..scrollBottom) return
        for (r in cursorRow until scrollBottom) {
            screen[r] = screen[r + 1]
        }
        screen[scrollBottom] = blankLine()
    }

    private fun scrollLeft(count: Int) {
        pendingWrap = false
        val n = count.coerceIn(1, columns)
        for (row in scrollTop..scrollBottom) {
            val line = screen[row]
            for (col in 0 until columns - n) line[col] = line[col + n].copy()
            for (col in columns - n until columns) line[col] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
        }
    }

    private fun scrollRight(count: Int) {
        pendingWrap = false
        val n = count.coerceIn(1, columns)
        for (row in scrollTop..scrollBottom) {
            val line = screen[row]
            for (col in columns - 1 downTo n) line[col] = line[col - n].copy()
            for (col in 0 until n) line[col] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
        }
    }

    private fun insertChars(count: Int) {
        pendingWrap = false
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        val line = screen[cursorRow]
        for (c in columns - 1 downTo cursorCol + n) line[c] = line[c - n].copy()
        for (c in cursorCol until min(columns, cursorCol + n)) line[c] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
    }

    private fun deleteChars(count: Int) {
        pendingWrap = false
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        val line = screen[cursorRow]
        for (c in cursorCol until columns - n) line[c] = line[c + n].copy()
        for (c in max(cursorCol, columns - n) until columns) line[c] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
    }

    private fun eraseChars(count: Int) {
        pendingWrap = false
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        for (c in cursorCol until cursorCol + n) screen[cursorRow][c] = Cell(style = currentStyle.copy(hyperlink = currentHyperlink))
    }

    private fun setScrollRegion(topOneBased: Int, bottomOneBased: Int) {
        val top = (topOneBased - 1).coerceIn(0, rows - 1)
        val bottom = (bottomOneBased - 1).coerceIn(top, rows - 1)
        scrollTop = top
        scrollBottom = bottom
        cursorRow = scrollTop
        cursorCol = 0
        pendingWrap = false
    }

    private fun resetTabStops() {
        tabStops.clear()
        var col = 8
        while (col < columns) {
            tabStops.add(col)
            col += 8
        }
    }

    private fun nextTabStop(count: Int = 1): Int {
        var col = cursorCol
        repeat(count.coerceAtLeast(1)) {
            col = tabStops.firstOrNull { it > col } ?: (columns - 1)
        }
        return col.coerceIn(0, columns - 1)
    }

    private fun previousTabStop(count: Int = 1): Int {
        var col = cursorCol
        repeat(count.coerceAtLeast(1)) {
            col = tabStops.lastOrNull { it < col } ?: 0
        }
        return col.coerceIn(0, columns - 1)
    }

    private fun clearTabStops(mode: Int) {
        when (mode) {
            0 -> tabStops.remove(cursorCol)
            3 -> tabStops.clear()
        }
    }

    private fun handleCursorTabControl(seq: CsiSequence) {
        when (seq.paramZero(0)) {
            0 -> tabStops.add(cursorCol)
            2 -> tabStops.remove(cursorCol)
            5 -> tabStops.clear()
        }
    }

    private fun handleDeviceAttributes(seq: CsiSequence) {
        if (seq.privateMarker == '>') {
            pendingResponses.add("\u001B[>0;276;0c")
        } else if (seq.params.isEmpty() || seq.paramZero(0) == 0) {
            pendingResponses.add("\u001B[?1;2c")
        }
    }

    private fun handleRequestMode(seq: CsiSequence) {
        val code = seq.paramZero(0)
        val value = when (seq.privateMarker) {
            '?' -> privateModeReportValue(code)
            else -> ansiModeReportValue(code)
        }
        val prefix = seq.privateMarker?.toString() ?: ""
        pendingResponses.add("\u001B[${prefix}${code};${value}\$y")
    }

    private fun ansiModeReportValue(code: Int): Int = when (code) {
        4 -> if (insertMode) 1 else 2
        20 -> if (newlineMode) 1 else 2
        else -> 0
    }

    private fun privateModeReportValue(code: Int): Int = when (code) {
        1 -> if (applicationCursorKeys) 1 else 2
        5 -> if (reverseVideo) 1 else 2
        6 -> if (originMode) 1 else 2
        7 -> if (wraparound) 1 else 2
        25 -> if (cursorVisible) 1 else 2
        9 -> if (mouseTrackingMode == MouseTrackingMode.X10) 1 else 2
        1000 -> if (mouseTrackingMode == MouseTrackingMode.NORMAL) 1 else 2
        1002 -> if (mouseTrackingMode == MouseTrackingMode.BUTTON_EVENT) 1 else 2
        1003 -> if (mouseTrackingMode == MouseTrackingMode.ANY_EVENT) 1 else 2
        1004 -> if (focusReporting) 1 else 2
        1005 -> if (mouseProtocol == MouseProtocol.UTF8) 1 else 2
        1006 -> if (mouseProtocol == MouseProtocol.SGR) 1 else 2
        1015 -> if (mouseProtocol == MouseProtocol.URXVT) 1 else 2
        1016 -> if (mouseProtocol == MouseProtocol.SGR_PIXELS) 1 else 2
        1007 -> if (alternateScroll) 1 else 2
        1034 -> if (metaSendsEscape) 1 else 2
        1036 -> if (modifyCursorKeys > 0) 1 else 2
        1039 -> if (modifyOtherKeys > 0) 1 else 2
        47, 1047, 1049 -> if (alternateScreen) 1 else 2
        1048 -> if (cursorSaveMode) 1 else 2
        1050, 1051, 1052, 1053 -> if (modifyFunctionKeys == code - 1049) 1 else 2
        1060, 1061 -> if (formatOtherKeys == code - 1059) 1 else 2
        2004 -> if (bracketedPaste) 1 else 2
        2026 -> if (synchronizedOutput) 1 else 2
        else -> 0
    }

    private fun handleDeviceStatusReport(seq: CsiSequence) {
        val code = seq.paramZero(0)
        when {
            seq.privateMarker == '?' && code == 6 -> pendingResponses.add("\u001B[?${cursorRow + 1};${cursorCol + 1}R")
            seq.privateMarker == '?' && code == 15 -> pendingResponses.add("\u001B[?13n")
            seq.privateMarker == '?' && code == 25 -> pendingResponses.add("\u001B[?20n")
            seq.privateMarker == '?' && code == 53 -> pendingResponses.add("\u001B[?50n")
            code == 5 -> pendingResponses.add("\u001B[0n")
            code == 6 -> pendingResponses.add("\u001B[${cursorRow + 1};${cursorCol + 1}R")
        }
    }

    private fun handleWindowOperation(seq: CsiSequence) {
        when (seq.paramZero(0)) {
            11 -> pendingResponses.add("\u001B[1t")
            13 -> pendingResponses.add("\u001B[3;0;0t")
            14 -> pendingResponses.add("\u001B[4;${rows * 14};${columns * 7}t")
            15 -> pendingResponses.add("\u001B[5;${rows * 14};${columns * 7}t")
            16 -> pendingResponses.add("\u001B[6;14;7t")
            18 -> pendingResponses.add("\u001B[8;${rows};${columns}t")
            19 -> pendingResponses.add("\u001B[9;${rows};${columns}t")
            20 -> pendingResponses.add("\u001B]L;${iconTitle.ifEmpty { title }}\u001B\\")
            21 -> pendingResponses.add("\u001B]l;${title}\u001B\\")
            22 -> saveTitleStack(seq.paramZero(1))
            23 -> restoreTitleStack(seq.paramZero(1))
        }
    }

    private fun saveTitleStack(selector: Int) {
        when (selector) {
            1 -> iconTitleStack.addLast(iconTitle)
            2 -> titleStack.addLast(title)
            else -> {
                iconTitleStack.addLast(iconTitle)
                titleStack.addLast(title)
            }
        }
    }

    private fun restoreTitleStack(selector: Int) {
        when (selector) {
            1 -> if (iconTitleStack.isNotEmpty()) iconTitle = iconTitleStack.removeLast()
            2 -> if (titleStack.isNotEmpty()) title = titleStack.removeLast()
            else -> {
                if (iconTitleStack.isNotEmpty()) iconTitle = iconTitleStack.removeLast()
                if (titleStack.isNotEmpty()) title = titleStack.removeLast()
            }
        }
    }

    private fun setModes(params: List<Int>, enabled: Boolean) {
        params.forEach { code ->
            when (code) {
                4 -> insertMode = enabled
                20 -> newlineMode = enabled
            }
        }
        pendingWrap = false
    }

    private fun setPrivateModes(params: List<Int>, enabled: Boolean) {
        params.forEach { code ->
            when (code) {
                1 -> applicationCursorKeys = enabled
                5 -> reverseVideo = enabled
                6 -> {
                    originMode = enabled
                    cursorRow = if (enabled) scrollTop else 0
                    cursorCol = 0
                    pendingWrap = false
                }
                7 -> {
                    wraparound = enabled
                    if (!enabled) pendingWrap = false
                }
                12 -> Unit
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> setAlternateScreen(enabled, clear = code == 1049)
                9 -> setMouseMode(if (enabled) MouseTrackingMode.X10 else MouseTrackingMode.OFF)
                1000 -> setMouseMode(if (enabled) MouseTrackingMode.NORMAL else MouseTrackingMode.OFF)
                1002 -> setMouseMode(if (enabled) MouseTrackingMode.BUTTON_EVENT else MouseTrackingMode.OFF)
                1003 -> setMouseMode(if (enabled) MouseTrackingMode.ANY_EVENT else MouseTrackingMode.OFF)
                1005 -> if (enabled) mouseProtocol = MouseProtocol.UTF8 else mouseProtocol = MouseProtocol.DEFAULT
                1006 -> if (enabled) mouseProtocol = MouseProtocol.SGR else mouseProtocol = MouseProtocol.DEFAULT
                1015 -> if (enabled) mouseProtocol = MouseProtocol.URXVT else mouseProtocol = MouseProtocol.DEFAULT
                1016 -> if (enabled) mouseProtocol = MouseProtocol.SGR_PIXELS else mouseProtocol = MouseProtocol.DEFAULT
                1004 -> focusReporting = enabled
                1007 -> alternateScroll = enabled
                1034 -> metaSendsEscape = enabled
                1036 -> modifyCursorKeys = if (enabled) 1 else 0
                1039 -> modifyOtherKeys = if (enabled) 1 else 0
                1048 -> {
                    if (enabled) {
                        saveCursor()
                        cursorSaveMode = true
                    } else {
                        restoreCursor()
                        cursorSaveMode = false
                    }
                }
                1050, 1051, 1052, 1053 -> modifyFunctionKeys = if (enabled) code - 1049 else 0
                1060, 1061 -> formatOtherKeys = if (enabled) code - 1059 else 0
                2004 -> bracketedPaste = enabled
                2026 -> synchronizedOutput = enabled
            }
        }
    }

    private fun setMouseMode(mode: MouseTrackingMode) {
        mouseTrackingMode = mode
        mouseTracking = mode != MouseTrackingMode.OFF
        if (!mouseTracking) mouseProtocol = MouseProtocol.DEFAULT
    }

    private fun setCharacterProtection(code: Int) {
        currentStyle = currentStyle.copy(protected = code == 1)
    }

    private fun setCursorShape(code: Int) {
        cursorShape = when (code) {
            0 -> CursorShape.DEFAULT
            1 -> CursorShape.BLOCK
            2 -> CursorShape.STEADY_BLOCK
            3 -> CursorShape.UNDERLINE
            4 -> CursorShape.STEADY_UNDERLINE
            5 -> CursorShape.BAR
            6 -> CursorShape.STEADY_BAR
            else -> cursorShape
        }
    }

    private fun setAlternateScreen(enabled: Boolean, clear: Boolean) {
        if (enabled == alternateScreen) return
        if (enabled) {
            saveCursor()
            cursorSaveMode = true
            alternateScreen = true
            if (clear) altScreen.resetScreen()
            cursorRow = 0
            cursorCol = 0
            pendingWrap = false
        } else {
            alternateScreen = false
            restoreCursor()
            cursorSaveMode = false
        }
        parserState = ParserState.NORMAL
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedCursor = SavedCursor(cursorRow, cursorCol, currentStyle, originMode, lineDrawing, pendingWrap)
        parserState = ParserState.NORMAL
    }

    private fun restoreCursor() {
        cursorRow = savedCursor.row.coerceIn(0, rows - 1)
        cursorCol = savedCursor.col.coerceIn(0, columns - 1)
        currentStyle = savedCursor.style
        originMode = savedCursor.originMode
        lineDrawing = savedCursor.lineDrawing
        pendingWrap = savedCursor.pendingWrap
        parserState = ParserState.NORMAL
    }

    private fun applySgr(rawParams: List<String>) {
        val params = if (rawParams.isEmpty()) listOf("0") else rawParams
        var i = 0
        while (i < params.size) {
            val token = params[i]
            if (token.contains(':')) {
                applyColonSgr(token)
                i++
                continue
            }
            when (val code = token.toIntOrNull() ?: 0) {
                0 -> currentStyle = defaultStyle.copy(fg = defaultForeground)
                1 -> currentStyle = currentStyle.copy(bold = true, faint = false)
                2 -> currentStyle = currentStyle.copy(faint = true, bold = false)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4, 21 -> currentStyle = currentStyle.copy(underline = true)
                5, 6 -> Unit
                7 -> currentStyle = currentStyle.copy(inverse = true)
                8 -> currentStyle = currentStyle.copy(concealed = true)
                9 -> currentStyle = currentStyle.copy(strike = true)
                22 -> currentStyle = currentStyle.copy(bold = false, faint = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                25 -> Unit
                27 -> currentStyle = currentStyle.copy(inverse = false)
                28 -> currentStyle = currentStyle.copy(concealed = false)
                29 -> currentStyle = currentStyle.copy(strike = false)
                39 -> currentStyle = currentStyle.copy(fg = defaultForeground)
                49 -> currentStyle = currentStyle.copy(bg = null)
                in 30..37 -> currentStyle = currentStyle.copy(fg = paletteOverrides[code - 30] ?: ansiColor(code - 30, currentStyle.bold))
                in 90..97 -> currentStyle = currentStyle.copy(fg = paletteOverrides[code - 90 + 8] ?: ansiColor(code - 90, true))
                in 40..47 -> currentStyle = currentStyle.copy(bg = paletteOverrides[code - 40] ?: ansiColor(code - 40, false))
                in 100..107 -> currentStyle = currentStyle.copy(bg = paletteOverrides[code - 100 + 8] ?: ansiColor(code - 100, true))
                38, 48 -> {
                    val isFg = code == 38
                    when (params.getOrNull(i + 1)?.toIntOrNull()) {
                        5 -> {
                            val color = xterm256(params.getOrNull(i + 2)?.toIntOrNull() ?: 7)
                            currentStyle = if (isFg) currentStyle.copy(fg = color) else currentStyle.copy(bg = color)
                            i += 2
                        }
                        2 -> {
                            val r = params.getOrNull(i + 2)?.toIntOrNull() ?: 0
                            val g = params.getOrNull(i + 3)?.toIntOrNull() ?: 0
                            val b = params.getOrNull(i + 4)?.toIntOrNull() ?: 0
                            val color = rgbColor(r, g, b)
                            currentStyle = if (isFg) currentStyle.copy(fg = color) else currentStyle.copy(bg = color)
                            i += 4
                        }
                    }
                }
                53 -> currentStyle = currentStyle.copy(overline = true)
                55 -> currentStyle = currentStyle.copy(overline = false)
                73 -> currentStyle = currentStyle.copy(baselineShift = BaselineShift.SUPERSCRIPT)
                74 -> currentStyle = currentStyle.copy(baselineShift = BaselineShift.SUBSCRIPT)
                75 -> currentStyle = currentStyle.copy(baselineShift = BaselineShift.NORMAL)
                58, 59 -> Unit
            }
            i++
        }
    }

    private fun applyColonSgr(token: String) {
        val parts = token.split(':')
        val code = parts.firstOrNull()?.toIntOrNull() ?: return
        if (code == 4) {
            currentStyle = currentStyle.copy(underline = parts.getOrNull(1)?.toIntOrNull() != 0)
            return
        }
        val isFg = code == 38
        val isBg = code == 48
        if (!isFg && !isBg) return
        val mode = parts.getOrNull(1)?.toIntOrNull() ?: return
        val color = when (mode) {
            5 -> xterm256(parts.getOrNull(2)?.toIntOrNull() ?: 7)
            2 -> {
                val rgb = parts.drop(2).mapNotNull { it.toIntOrNull() }.takeLast(3)
                if (rgb.size == 3) rgbColor(rgb[0], rgb[1], rgb[2]) else null
            }
            else -> null
        } ?: return
        currentStyle = if (isFg) currentStyle.copy(fg = color) else currentStyle.copy(bg = color)
    }

    private fun Style.toSpanStyle(): SpanStyle {
        val effectiveInverse = inverse.xor(reverseVideo)
        val rawFgColor = if (effectiveInverse) bg ?: Color(0xFF101010) else fg
        val fgColor = if (concealed) bg ?: Color.Transparent else rawFgColor
        val bgColor = if (effectiveInverse) fg else bg
        val linkUnderline = hyperlink != null
        val textDecoration = when {
            (underline || linkUnderline) && strike -> TextDecoration.Underline + TextDecoration.LineThrough
            underline || linkUnderline -> TextDecoration.Underline
            strike -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }
        return SpanStyle(
            color = if (hyperlink != null) Color(0xFF64B5F6) else if (faint) fgColor.copy(alpha = 0.72f) else fgColor,
            background = bgColor ?: Color.Transparent,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = textDecoration
        )
    }

    private fun blankLine(): Array<Cell> = Array(columns) { Cell(style = currentStyle.copy(hyperlink = currentHyperlink)) }

    private fun cursorGlyph(): String = when (cursorShape) {
        CursorShape.UNDERLINE, CursorShape.STEADY_UNDERLINE -> "▁"
        CursorShape.BAR, CursorShape.STEADY_BAR -> "▏"
        else -> "█"
    }

    private fun mapLineDrawing(ch: Char): Char = when (ch) {
        'j' -> '┘'; 'k' -> '┐'; 'l' -> '┌'; 'm' -> '└'; 'n' -> '┼'; 'q' -> '─'; 't' -> '├'; 'u' -> '┤'; 'v' -> '┴'; 'w' -> '┬'; 'x' -> '│'
        else -> ch
    }

    private fun rgbColor(red: Int, green: Int, blue: Int): Color {
        return Color(
            red = red.coerceIn(0, 255) / 255f,
            green = green.coerceIn(0, 255) / 255f,
            blue = blue.coerceIn(0, 255) / 255f
        )
    }

    private fun ansiColor(index: Int, bright: Boolean): Color {
        val normal = listOf(
            Color(0xFF7F8490), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
            Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFD3D7CF)
        )
        val brightColors = listOf(
            Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
            Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFFFFFFF)
        )
        return (if (bright) brightColors else normal)[index.coerceIn(0, 7)]
    }

    private fun xterm256(code: Int): Color {
        val c = code.coerceIn(0, 255)
        paletteOverrides[c]?.let { return it }
        if (c < 16) return ansiColor(c % 8, c >= 8)
        if (c in 232..255) {
            val v = 8 + (c - 232) * 10
            return rgbColor(v, v, v)
        }
        val n = c - 16
        val r = n / 36
        val g = (n / 6) % 6
        val b = n % 6
        fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        return rgbColor(level(r), level(g), level(b))
    }
}
