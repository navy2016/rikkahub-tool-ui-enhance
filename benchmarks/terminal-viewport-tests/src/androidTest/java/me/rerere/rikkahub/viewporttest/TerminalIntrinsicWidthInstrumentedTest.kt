package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Bullet
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.resolveDefaults
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
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.ui.pages.container.TerminalRowWidthMeasurer
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Exact integer widths, not a performance benchmark. No font/character-width approximation. */
class TerminalIntrinsicWidthInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(90)
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
    fun intrinsicWidthScreenCacheMatchesUncachedFontsAnsiCursorAndResize() {
        ready()
        compose.runOnIdle {
            val terminal = TerminalEmulator(80, 6, 32).apply {
                feed("\u001B[?25l" + (0 until 38).joinToString("\r\n") { "r$it 中文 e\u0301 👩‍💻" })
            }
            val index = TerminalTranscriptWidthIndex()
            for (density in densities) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val style = terminalStyle.copy(fontSize = if (direction == LayoutDirection.Rtl) 19.sp else 14.sp)
                val metric = listOf(style, density, direction)
                val full = TextMeasurer(resolver, density, direction, cacheSize = 0)
                val intrinsic = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                fun verify() {
                    val frame = terminal.renderFrame()
                    val expected = frame.rows.maxOf {
                        full.measure(it.text, style, softWrap = false, maxLines = 1, skipCache = true).size.width
                    }
                    assertEquals("density=$density direction=$direction", expected, index.width(frame, metric, intrinsic::width))
                    assertEquals(terminal.rows, index.retainedScreenRows)
                    assertEquals(terminal.rows, index.lastMeasuredScreenRows + index.lastReusedScreenRows)
                }
                verify()
                assertEquals(terminal.rows, index.lastMeasuredScreenRows) // New font/density/direction.
                verify()
                assertEquals(0, index.lastMeasuredScreenRows)
                assertEquals(terminal.rows, index.lastReusedScreenRows)
                for (command in listOf(
                    "\r\u001B[2Kwide WWWWW 中文 e\u0301",
                    "\r\u001B[2K\u001B[1;3mwide WWWWW 中文 e\u0301\u001B[0m",
                    "\r\u001B[2K\u001B]8;;https://example.invalid/a\u001B\\URL 👩‍💻\u001B]8;;\u001B\\",
                    "\u001B[?25h\u001B[2;4H", "\u001B[3;5H", "\u001B[?25l",
                    "\r\nappended", "\u001B[H\u001B[2Kshort", "\u001B[?5h", "\u001B[?5l",
                )) {
                    terminal.feed(command)
                    verify()
                }
                terminal.resize(if (terminal.columns == 80) 40 else 80, if (terminal.rows == 6) 8 else 6)
                verify()
                assertEquals(terminal.rows, index.lastMeasuredScreenRows)
                index.retainFor(terminal.renderFrame(), metric)
                assertEquals(0, index.retainedScreenRows)
                verify()
                assertEquals(terminal.rows, index.lastMeasuredScreenRows)
            }
            index.clear()
            assertEquals(0, index.retainedScreenRows)
        }
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

    @Test fun intrinsicWidthOwnedSingleParagraphMatchesFullAndMultiAtAllMetrics() {
        ready()
        val terminal = TerminalEmulator(80, 6, 80).apply {
            val lines = listOf("ffi -> !== 0123", "中文 日本語 한국어", "e\u0301\u0327 👩‍💻 🇨🇳 ⚙️",
                "עברית العربية 123", "\u001B[1;3;31mbold italic\u001B[0m plain",
                "\u001B]8;;https://example.invalid/test\u001B\\URL 中文\u001B]8;;\u001B\\ end",
                "\u001B[4;7;9m inverse strike \u001B[0m", "    spaces  ")
            feed("\u001B[?25l" + (0 until 86).joinToString("\r\n") { lines[it % lines.size] })
        }
        val frames = mutableListOf(terminal.renderFrame())
        for (input in listOf("\u001B[?25h\u001B[2;5H", "\u001B[?5h", "\u001B[?1049hALT", "\u001B[?1049l")) {
            terminal.feed(input)
            frames += terminal.renderFrame()
        }
        terminal.resize(45, 8)
        frames += terminal.renderFrame()
        compose.runOnIdle {
            for (density in densities) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                for (style in listOf(terminalStyle, terminalStyle.copy(fontFamily = FontFamily.Serif,
                    fontSize = 17.sp, letterSpacing = 0.3.sp, textIndent = TextIndent(7.sp, 2.sp)))) {
                    val full = TextMeasurer(resolver, density, direction, cacheSize = 0)
                    val old = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                    for (frame in frames) {
                        val candidate = TerminalRowWidthMeasurer(frame, resolveDefaults(style, direction), density, resolver)
                        frame.rows.forEachIndexed { index, row ->
                            val expected = full.measure(row.text, style, softWrap = false, maxLines = 1, skipCache = true).size.width
                            assertEquals("full row=$index density=$density direction=$direction", expected, candidate.width(row.text))
                            assertEquals("multi row=$index density=$density direction=$direction", old.width(row.text), expected)
                        }
                        assertEquals(frame.rows.size, candidate.singleParagraphCalls)
                        assertEquals(0, candidate.multiParagraphCalls)
                    }
                }
            }
        }
    }

    @Test fun intrinsicWidthSingleParagraphFallbackPreservesComplexAnnotationsAndSeparators() {
        ready()
        val frame = TerminalEmulator(80, 6).renderFrame()
        val texts = listOf(
            AnnotatedString("", spanStyles = listOf(AnnotatedString.Range(SpanStyle(fontSize = 20.sp), 0, 0))),
            AnnotatedString("\nlong second paragraph\n"),
            AnnotatedString("a\u0085b\u2028c\u2029d"),
            AnnotatedString("paragraph indent", paragraphStyles = listOf(
                AnnotatedString.Range(ParagraphStyle(textDirection = TextDirection.Rtl,
                    textIndent = TextIndent(11.sp, 3.sp)), 0, 16))),
            AnnotatedString("bullet and link", annotations = listOf(
                AnnotatedString.Range(Bullet.Default, 0, 15),
                AnnotatedString.Range(LinkAnnotation.Url("https://example.invalid"), 7, 15),
                AnnotatedString.Range(SpanStyle(fontSize = 27.sp, fontFamily = FontFamily.Cursive), 2, 10))),
        )
        compose.runOnIdle {
            for (density in densities) for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val style = terminalStyle.copy(textIndent = TextIndent(13.sp, 3.sp))
                val old = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                val synthetic = frame.copy(rows = texts.map { TerminalEmulator.RenderedRow(it) })
                val candidate = TerminalRowWidthMeasurer(synthetic, resolveDefaults(style, direction), density, resolver)
                for (text in texts) assertEquals(old.width(text), candidate.width(text))
                assertEquals(0, candidate.singleParagraphCalls)
                assertEquals(texts.size, candidate.multiParagraphCalls)
                // Unicode separators can be printable emulator cell contents; ownership alone
                // is deliberately insufficient for the optimized physical-row path.
                val terminal = TerminalEmulator(80, 6).apply { feed("a\u2028b\u2029c") }
                val owned = terminal.renderFrame()
                val mixed = TerminalRowWidthMeasurer(owned, resolveDefaults(style, direction), density, resolver)
                for (row in owned.rows) assertEquals(old.width(row.text), mixed.width(row.text))
                assertTrue(mixed.multiParagraphCalls > 0)
            }
        }
    }

    @Test fun intrinsicWidthSingleParagraphPairedPassesVisitEveryRowWithoutWidthCaches() {
        ready()
        // Typeface warmup is excluded for BOTH paths. No measured pass reuses a width index,
        // TextMeasurer LRU or Paragraph instance; native font caches remain normal Android caches.
        val density = Density(2.75f, 1.15f)
        val direction = LayoutDirection.Ltr
        val style = terminalStyle
        val resolved = resolveDefaults(style, direction)
        data class Pass(val widths: IntArray, val nanos: Long, val single: Int, val multi: Int)
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = TerminalEmulator(80, 6, size).apply {
                feed("\u001B[?25l" + (0 until size + 6).joinToString("\r\n") { index ->
                    "\u001B[36m${index.toString().padStart(6, '0')}\u001B[0m " + when (index % 3) {
                        0 -> "PASS compile terminal renderer 0123456789"
                        1 -> "\u001B[1;32m中文 e\u0301 👩‍💻\u001B[0m output retained"
                        else -> "العربية 123 עברית 0123456789"
                    }
                })
            }
            val frame = terminal.renderFrame()
            compose.runOnIdle {
                val warmOld = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                val warmNew = TerminalRowWidthMeasurer(frame, resolved, density, resolver)
                for (row in frame.rows.take(128)) assertEquals(warmOld.width(row.text), warmNew.width(row.text))
            }
            fun pass(legacy: Boolean): Pass = compose.runOnIdle {
                val old = TerminalIntrinsicWidthMeasurer(style, density, direction, resolver)
                val candidate = TerminalRowWidthMeasurer(frame, resolved, density, resolver)
                val start = System.nanoTime()
                val widths = IntArray(frame.rows.size) { index ->
                    val text = frame.rows[index].text
                    if (legacy) old.width(text) else candidate.width(text)
                }
                Pass(widths, System.nanoTime() - start, candidate.singleParagraphCalls, candidate.multiParagraphCalls)
            }
            repeat(4) { pair ->
                val oldFirst = pair % 2 == 0
                val first = pass(oldFirst)
                val second = pass(!oldFirst)
                val old = if (oldFirst) first else second
                val candidate = if (oldFirst) second else first
                assertArrayEquals("size=$size pair=$pair", old.widths, candidate.widths)
                assertEquals(frame.rows.size, candidate.single)
                assertEquals(0, candidate.multi)
                for (legacy in listOf(true, false)) {
                    val result = if (legacy) old else candidate
                    // Export the full width vector identity AFTER timing, never just its sum.
                    val bytes = ByteBuffer.allocate(result.widths.size * Int.SIZE_BYTES)
                    result.widths.forEach { bytes.putInt(it) }
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
                        .joinToString("") { "%02x".format(it) }
                    Log.i("TerminalRowWidth", "ROW_WIDTH_SAMPLE " + JSONObject().put("size", size).put("pair", pair)
                        .put("legacy", legacy).put("first", legacy == oldFirst).put("rows", frame.rows.size)
                        .put("singleCalls", result.single).put("multiCalls", if (legacy) frame.rows.size else result.multi)
                        .put("nanos", result.nanos).put("widthSum", result.widths.sumOf { it.toLong() })
                        .put("widthSha256", digest))
                }
            }
        }
    }
}
