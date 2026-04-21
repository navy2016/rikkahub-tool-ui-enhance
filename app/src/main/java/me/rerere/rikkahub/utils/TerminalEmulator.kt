package me.rerere.rikkahub.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.max
import kotlin.math.min

/** Lightweight VT100/xterm screen emulator for the in-app terminal. */
class TerminalEmulator(
    val columns: Int = 80,
    val rows: Int = 24,
    private val maxScrollbackLines: Int = 1000
) {
    private data class Style(
        val fg: Color = Color(0xFF00E676),
        val bg: Color? = null,
        val bold: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false
    )

    private data class Cell(var ch: Char = ' ', var style: Style = Style())
    private enum class ParserState { NORMAL, ESC, CSI, OSC, ESC_CHARSET_G0, ESC_CHARSET_G1 }

    private val defaultStyle = Style()
    private var currentStyle = defaultStyle
    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var parserState = ParserState.NORMAL
    private var csiBuffer = StringBuilder()
    private var oscEscSeen = false
    private var cursorVisible = true
    private var wraparound = true
    private var alternateScreen = false
    private var lineDrawing = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private val scrollback = ArrayDeque<Array<Cell>>()
    private val mainScreen = MutableList(rows) { blankLine() }
    private val altScreen = MutableList(rows) { blankLine() }
    private val screen: MutableList<Array<Cell>> get() = if (alternateScreen) altScreen else mainScreen

    @Synchronized
    fun reset() {
        scrollback.clear()
        mainScreen.resetScreen()
        altScreen.resetScreen()
        cursorRow = 0
        cursorCol = 0
        savedRow = 0
        savedCol = 0
        currentStyle = defaultStyle
        parserState = ParserState.NORMAL
        csiBuffer.clear()
        oscEscSeen = false
        cursorVisible = true
        wraparound = true
        alternateScreen = false
        lineDrawing = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun softReset() {
        currentStyle = defaultStyle
        parserState = ParserState.NORMAL
        csiBuffer.clear()
        oscEscSeen = false
        cursorVisible = true
        wraparound = true
        lineDrawing = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    @Synchronized
    fun feed(text: String) {
        text.forEach { feedChar(it) }
    }

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
        return lines.joinToString("\n") { line -> line.joinToString("") { it.ch.toString() }.trimEnd() }
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
            if (cell.ch == ' ') cell.ch = '█'
        }
        return copy
    }

    private fun AnnotatedString.Builder.appendStyledLine(line: Array<Cell>) {
        val last = line.indexOfLast { it.ch != ' ' }.coerceAtLeast(0)
        var i = 0
        while (i <= last) {
            val style = line[i].style
            var j = i + 1
            while (j <= last && line[j].style == style) j++
            withStyle(style.toSpanStyle()) { for (k in i until j) append(line[k].ch) }
            i = j
        }
    }

    private fun feedChar(ch: Char) {
        when (parserState) {
            ParserState.NORMAL -> handleNormal(ch)
            ParserState.ESC -> handleEsc(ch)
            ParserState.CSI -> handleCsi(ch)
            ParserState.OSC -> handleOsc(ch)
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
            '\r' -> cursorCol = 0
            '\n' -> lineFeed()
            '\b', '\u007F' -> if (cursorCol > 0) cursorCol--
            '\t' -> repeat(8 - (cursorCol % 8)) { putChar(' ') }
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
                oscEscSeen = false
                parserState = ParserState.OSC
            }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'c' -> reset()
            'D' -> lineFeed()
            'E' -> {
                cursorCol = 0
                lineFeed()
            }
            'M' -> reverseIndex()
            '(' -> parserState = ParserState.ESC_CHARSET_G0
            ')' -> parserState = ParserState.ESC_CHARSET_G1
            else -> parserState = ParserState.NORMAL
        }
    }

    private fun handleOsc(ch: Char) {
        if (ch == '\u0007') {
            parserState = ParserState.NORMAL
            return
        }
        if (oscEscSeen && ch == '\\') {
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
            csiBuffer.append(ch)
        }
    }

    private fun executeCsi(raw: String, command: Char) {
        val isPrivate = raw.startsWith("?")
        val clean = raw.trimStart('?', '>', '!')
        val params = clean.split(';').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        fun p(index: Int, default: Int): Int = params.getOrNull(index)?.takeIf { it != 0 } ?: default

        when (command) {
            'A' -> cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop)
            'B' -> cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom)
            'C' -> cursorCol = (cursorCol + p(0, 1)).coerceAtMost(columns - 1)
            'D' -> cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom)
                cursorCol = 0
            }
            'F' -> {
                cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop)
                cursorCol = 0
            }
            'G', '`' -> cursorCol = (p(0, 1) - 1).coerceIn(0, columns - 1)
            'H', 'f' -> {
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> eraseLine(params.getOrNull(0) ?: 0)
            'm' -> applySgr(if (params.isEmpty()) listOf(0) else params)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'L' -> repeat(p(0, 1)) { insertLine() }
            'M' -> repeat(p(0, 1)) { deleteLine() }
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> repeat(p(0, 1)) { scrollUp() }
            'T' -> repeat(p(0, 1)) { scrollDown() }
            'r' -> setScrollRegion(p(0, 1), p(1, rows))
            'h' -> if (isPrivate) setPrivateModes(params, true)
            'l' -> if (isPrivate) setPrivateModes(params, false)
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= columns) {
            if (!wraparound) {
                cursorCol = columns - 1
            } else {
                cursorCol = 0
                lineFeed()
            }
        }
        screen[cursorRow][cursorCol].ch = ch
        screen[cursorRow][cursorCol].style = currentStyle
        cursorCol++
        if (cursorCol >= columns && wraparound) {
            cursorCol = 0
            lineFeed()
        }
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

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screen[r] = blankLine()
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) screen[r] = blankLine()
            }
            2, 3 -> {
                for (r in 0 until rows) screen[r] = blankLine()
                cursorRow = 0
                cursorCol = 0
                if (mode == 3) scrollback.clear()
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until columns) screen[cursorRow][c] = Cell(style = currentStyle)
            1 -> for (c in 0..cursorCol) screen[cursorRow][c] = Cell(style = currentStyle)
            2 -> screen[cursorRow] = blankLine()
        }
    }

    private fun insertLine() {
        if (cursorRow !in scrollTop..scrollBottom) return
        for (r in scrollBottom downTo cursorRow + 1) {
            screen[r] = screen[r - 1]
        }
        screen[cursorRow] = blankLine()
    }

    private fun deleteLine() {
        if (cursorRow !in scrollTop..scrollBottom) return
        for (r in cursorRow until scrollBottom) {
            screen[r] = screen[r + 1]
        }
        screen[scrollBottom] = blankLine()
    }

    private fun insertChars(count: Int) {
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        val line = screen[cursorRow]
        for (c in columns - 1 downTo cursorCol + n) line[c] = line[c - n].copy()
        for (c in cursorCol until min(columns, cursorCol + n)) line[c] = Cell(style = currentStyle)
    }

    private fun deleteChars(count: Int) {
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        val line = screen[cursorRow]
        for (c in cursorCol until columns - n) line[c] = line[c + n].copy()
        for (c in max(cursorCol, columns - n) until columns) line[c] = Cell(style = currentStyle)
    }

    private fun eraseChars(count: Int) {
        val available = columns - cursorCol
        if (available <= 0) return
        val n = count.coerceIn(1, available)
        for (c in cursorCol until cursorCol + n) screen[cursorRow][c] = Cell(style = currentStyle)
    }

    private fun setScrollRegion(topOneBased: Int, bottomOneBased: Int) {
        val top = (topOneBased - 1).coerceIn(0, rows - 1)
        val bottom = (bottomOneBased - 1).coerceIn(top, rows - 1)
        scrollTop = top
        scrollBottom = bottom
        cursorRow = scrollTop
        cursorCol = 0
    }

    private fun setPrivateModes(params: List<Int>, enabled: Boolean) {
        params.forEach { code ->
            when (code) {
                7 -> wraparound = enabled
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> setAlternateScreen(enabled, clear = code == 1049)
                1048 -> if (enabled) saveCursor() else restoreCursor()
                2004 -> Unit // bracketed paste
            }
        }
    }

    private fun setAlternateScreen(enabled: Boolean, clear: Boolean) {
        if (enabled == alternateScreen) return
        val previousRow = cursorRow
        val previousCol = cursorCol
        if (enabled) {
            saveCursor()
            alternateScreen = true
            if (clear) altScreen.resetScreen()
            cursorRow = 0
            cursorCol = 0
        } else {
            alternateScreen = false
            cursorRow = previousRow.coerceIn(0, rows - 1)
            cursorCol = previousCol.coerceIn(0, columns - 1)
        }
        parserState = ParserState.NORMAL
    }

    private fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        parserState = ParserState.NORMAL
    }

    private fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, columns - 1)
        parserState = ParserState.NORMAL
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> currentStyle = defaultStyle
                1 -> currentStyle = currentStyle.copy(bold = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                22 -> currentStyle = currentStyle.copy(bold = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                39 -> currentStyle = currentStyle.copy(fg = defaultStyle.fg)
                49 -> currentStyle = currentStyle.copy(bg = null)
                in 30..37 -> currentStyle = currentStyle.copy(fg = ansiColor(code - 30, currentStyle.bold))
                in 90..97 -> currentStyle = currentStyle.copy(fg = ansiColor(code - 90, true))
                in 40..47 -> currentStyle = currentStyle.copy(bg = ansiColor(code - 40, false))
                in 100..107 -> currentStyle = currentStyle.copy(bg = ansiColor(code - 100, true))
                38, 48 -> {
                    val isFg = code == 38
                    val mode = params.getOrNull(i + 1)
                    if (mode == 5) {
                        val color = xterm256(params.getOrNull(i + 2) ?: 7)
                        currentStyle = if (isFg) currentStyle.copy(fg = color) else currentStyle.copy(bg = color)
                        i += 2
                    } else if (mode == 2) {
                        val r = params.getOrNull(i + 2) ?: 0
                        val g = params.getOrNull(i + 3) ?: 0
                        val b = params.getOrNull(i + 4) ?: 0
                        val color = rgbColor(r, g, b)
                        currentStyle = if (isFg) currentStyle.copy(fg = color) else currentStyle.copy(bg = color)
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun Style.toSpanStyle(): SpanStyle {
        val fgColor = if (inverse) bg ?: Color(0xFF101010) else fg
        val bgColor = if (inverse) fg else bg
        return SpanStyle(
            color = fgColor,
            background = bgColor ?: Color.Transparent,
            textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None
        )
    }

    private fun blankLine(): Array<Cell> = Array(columns) { Cell(style = currentStyle) }

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
            Color(0xFF000000), Color(0xFFCC0000), Color(0xFF4E9A06), Color(0xFFC4A000),
            Color(0xFF3465A4), Color(0xFF75507B), Color(0xFF06989A), Color(0xFFD3D7CF)
        )
        val brightColors = listOf(
            Color(0xFF555753), Color(0xFFEF2929), Color(0xFF8AE234), Color(0xFFFCE94F),
            Color(0xFF729FCF), Color(0xFFAD7FA8), Color(0xFF34E2E2), Color(0xFFFFFFFF)
        )
        return (if (bright) brightColors else normal)[index.coerceIn(0, 7)]
    }

    private fun xterm256(code: Int): Color {
        val c = code.coerceIn(0, 255)
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
