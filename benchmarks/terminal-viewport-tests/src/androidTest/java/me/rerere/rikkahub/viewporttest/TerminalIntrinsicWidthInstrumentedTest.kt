package me.rerere.rikkahub.viewporttest

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.benchmark.TerminalBenchmarkWidthIndex
import me.rerere.rikkahub.benchmark.TerminalIntrinsicWidthMeasurer
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Exact integer widths, not a performance benchmark. No font/character-width approximation. */
class TerminalIntrinsicWidthInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(45)
    private lateinit var resolver: FontFamily.Resolver

    private val terminalStyle = TextStyle(
        fontFamily = JetbrainsMono, fontSize = 14.sp, lineHeight = 14.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )
    private val densities = listOf(Density(1f), Density(1.33f, 1.3f), Density(2.75f, 1.15f))

    private fun ready() {
        compose.setContent { resolver = LocalFontFamilyResolver.current }
        compose.waitForIdle()
    }

    private fun compare(
        texts: List<AnnotatedString>,
        styles: List<TextStyle> = listOf(terminalStyle),
        directions: List<LayoutDirection> = listOf(LayoutDirection.Ltr, LayoutDirection.Rtl),
    ) {
        ready()
        compose.runOnIdle {
            for (density in densities) for (direction in directions) for (style in styles) {
                val full = TextMeasurer(resolver, density, direction, cacheSize = 0)
                val intrinsic = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                texts.forEachIndexed { index, text ->
                    val expected = full.measure(text, style, softWrap = false, maxLines = 1, skipCache = true).size.width
                    assertEquals("row=$index density=$density dir=$direction text=${text.text}",
                        expected, intrinsic.width(text))
                }
            }
        }
    }

    @Test
    fun intrinsicWidthAnsiCjkCombiningAndEmojiMatchFullLayout() {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 24, maxScrollbackLines = 100)
        val lines = listOf(
            "plain proportional? ffi -> !==", "中文字符 日本語 한국어", "e\u0301\u0327 A\u030a",
            "👩‍💻 👨‍👩‍👧‍👦 🇨🇳 ⚙️", "עברית العربية 123 ABC", "╭─┬──╮ └─┴──┘",
            "\u001B[1;31mbold\u001B[0m / \u001B[3;34mitalic\u001B[0m end",
            "\u001B[1;3m字体fallback\u001B[0m \u001B[4;7minverse\u001B[0m",
            "\u001B]8;;https://example.com/a\u001B\\link\u001B]8;;\u001B\\ tail",
        )
        terminal.feed("\u001B[?25l" + (0 until 50).joinToString("\r\n") { lines[it % lines.size] })
        compare(terminal.renderFrame().rows.map { it.text } + lines.take(6).map(::AnnotatedString))
    }

    @Test
    fun intrinsicWidthMixedFontsSpansAndGeometricTransformsMatchFullLayout() {
        val spans = listOf(
            SpanStyle(fontSize = 29.sp, fontFamily = FontFamily.Serif),
            SpanStyle(fontSize = 8.sp, baselineShift = BaselineShift.Superscript),
            SpanStyle(fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
            SpanStyle(letterSpacing = 0.37.sp, textGeometricTransform = TextGeometricTransform(1.2f, 0.2f)),
            SpanStyle(fontFamily = FontFamily.Cursive, fontSize = 21.sp, color = Color.Red),
        )
        compare(spans.map { span -> buildAnnotatedString {
            append("start 中 e\u0301 العربية ffi 🙂 end")
            addStyle(span, 3, length - 3)
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), 0, 7)
        } }, styles = listOf(terminalStyle, terminalStyle.copy(fontFamily = FontFamily.SansSerif, letterSpacing = 0.7.sp)))
    }

    @Test
    fun intrinsicWidthParagraphDirectionsAndIndentsMatchFullLayout() {
        val texts = listOf(
            AnnotatedString("short\nwide second paragraph text\n"),
            buildAnnotatedString {
                append("العربية abc\n中文 English end")
                addStyle(ParagraphStyle(textDirection = TextDirection.Rtl, textIndent = TextIndent(11.sp, 3.sp)), 0, 12)
                addStyle(SpanStyle(fontSize = 23.sp), 5, length)
            },
            AnnotatedString("abc\u202E123\u202C中文\u2067אבג\u2069"),
        )
        compare(texts, styles = listOf(terminalStyle, terminalStyle.copy(textDirection = TextDirection.ContentOrRtl)))
    }

    @Test
    fun intrinsicWidthEmptyWhitespaceAndBoundaryLengthsMatchFullLayout() {
        val texts = listOf("", " ", "        ", "\t", "\n", "\nlong second\n", "trailing  ", " leading", "\u200b") +
            listOf(1, 2, 79, 80, 120, 239, 240).flatMap { count -> listOf("W".repeat(count), "中".repeat(count)) }
        compare(texts.map(::AnnotatedString), styles = listOf(terminalStyle, terminalStyle.copy(fontSize = 18.sp)))
    }

    @Test
    fun intrinsicWidthPaletteResizeCursorAndGenerationFramesMatchFullLayout() {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 6, maxScrollbackLines = 40)
        terminal.feed((0 until 30).joinToString("\r\n") { "\u001B[${31 + it % 6}mrow:$it 中文\u001B[0m" })
        val texts = terminal.renderFrame().rows.map { it.text }.toMutableList()
        for (command in listOf("\u001B[?5h", "\u001B]4;2;rgb:ffff/0000/0000\u0007", "\u001B[?25l", "\u001B[?1049hALT", "\u001B[?1049l")) {
            terminal.feed(command)
            texts += terminal.renderFrame().rows.map { it.text }
        }
        terminal.resize(columns = 45, rows = 8)
        texts += terminal.renderFrame().rows.map { it.text }
        compare(texts)
    }

    @Test
    fun intrinsicWidthFifoIndexMatchesFullMaximumAfterTrimAndMetricChange() {
        ready()
        compose.runOnIdle {
            val terminal = TerminalEmulator(initialColumns = 80, initialRows = 6, maxScrollbackLines = 40)
            terminal.feed((0 until 46).joinToString("\r\n") { if (it == 0) "W".repeat(75) else "r$it 中文" })
            val index = TerminalBenchmarkWidthIndex()
            for (density in densities) {
                val full = TextMeasurer(resolver, density, LayoutDirection.Ltr, cacheSize = 0)
                val intrinsic = TerminalIntrinsicWidthMeasurer(terminalStyle, density, LayoutDirection.Ltr, resolver)
                repeat(8) { update ->
                    val frame = terminal.renderFrame()
                    val reference = frame.rows.maxOf {
                        full.measure(it.text, terminalStyle, softWrap = false, maxLines = 1, skipCache = true).size.width
                    }
                    assertEquals(reference, index.width(frame, density, intrinsic::width))
                    assertEquals(if (update == 0) 40 else 1, index.lastMeasuredHistoryRows)
                    assertTrue(index.retainedCandidates <= 40)
                    terminal.feed("\r\n\u001B[1mnext:$update 中文\u001B[0m")
                }
            }
        }
    }
}
