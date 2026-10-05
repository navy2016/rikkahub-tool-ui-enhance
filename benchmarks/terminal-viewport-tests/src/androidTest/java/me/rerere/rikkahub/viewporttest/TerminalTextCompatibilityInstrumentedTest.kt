package me.rerere.rikkahub.viewporttest

import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRow
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.terminalUsesBasicText
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** Material Text is an independent oracle, never a call through the candidate terminal helper. */
class TerminalTextCompatibilityInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)
    @get:Rule val probe = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "text compatibility ${description.methodName}", error)
        }
    }

    private val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono,
        fontSize = 20.sp, lineHeight = 20.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    private val textMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private data class PaintedText(
        val imageSize: IntSize, val pixels: IntArray, val position: Offset, val size: IntSize,
        val input: AnnotatedString, val style: TextStyle, val layoutSize: IntSize,
        val baselines: Pair<Float, Float>, val characterBoxes: List<Rect>,
    )

    private fun layout(): TextLayoutResult {
        val node = compose.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes().single()
        val results = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
        }
        return results.single()
    }

    private fun capture(): PaintedText {
        compose.waitForIdle()
        val text = layout()
        val node = compose.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes().single()
        val image = compose.onNodeWithTag("text-pixels").captureToImage()
        val map = image.toPixelMap()
        return PaintedText(IntSize(image.width, image.height),
            IntArray(image.width * image.height) { map[it % image.width, it / image.width].toArgb() },
            node.positionInRoot, node.size, text.layoutInput.text, text.layoutInput.style, text.size,
            text.firstBaseline to text.lastBaseline,
            text.layoutInput.text.indices.map { text.getBoundingBox(it) })
    }

    private fun assertEquivalent(expected: PaintedText, actual: PaintedText) {
        assertEquals(expected.imageSize, actual.imageSize)
        assertArrayEquals("terminal pixels differ from Material Text", expected.pixels, actual.pixels)
        assertEquals(expected.position, actual.position)
        assertEquals(expected.size, actual.size)
        assertEquals(expected.input, actual.input)
        assertEquals(expected.style, actual.style)
        assertEquals(expected.layoutSize, actual.layoutSize)
        assertEquals(expected.baselines, actual.baselines)
        assertEquals(expected.characterBoxes, actual.characterBoxes)
    }

    @Test fun terminalTextStyledPixelsAndLayoutMatchMaterialAcrossRtlAndFontScale() {
        val terminal = TerminalEmulator(80, 6).apply {
            feed("\u001B[?25l\u001B[1;3;31mANSI\u001B[0m " +
                "\u001B]8;;https://example.invalid/terminal\u001B\\URL\u001B]8;;\u001B\\")
        }
        val texts = listOf(AnnotatedString("plain ffi -> !=="), terminal.renderFrame().rows.first().text,
            AnnotatedString("中文 e\u0301 العربية 👩‍💻"), buildAnnotatedString {
                append("mixed 字体 text")
                addStyle(SpanStyle(fontSize = 31.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), 0, 5)
            })
        val f = Fixture(style)
        compose.setContent { f.Content() }
        for (text in texts) for (rtl in listOf(false, true)) for (scaled in listOf(false, true)) {
            compose.runOnIdle {
                f.optimized = false
                f.row.text = text
                f.direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                f.density = if (scaled) Density(1.33f, 1.3f) else Density(1f)
                assertTrue(terminalUsesBasicText(text, f.style))
            }
            val expected = capture()
            compose.runOnIdle { f.optimized = true }
            assertEquivalent(expected, capture())
        }
    }

    @Test fun terminalTextMaterialFallbackPreservesContentColorBrushAndClickableLinks() {
        val f = Fixture(style)
        compose.setContent { f.Content() }
        val brushStyle = TextStyle(brush = Brush.linearGradient(listOf(Color.Yellow, Color.Blue)),
            fontFamily = JetbrainsMono, fontSize = 20.sp, lineHeight = 20.sp)
        for (textStyle in listOf(style.copy(color = Color.Unspecified), brushStyle)) {
            for (color in listOf(Color.Magenta, Color.Cyan)) {
                compose.runOnIdle {
                    f.optimized = false
                    f.row.text = AnnotatedString("ambient 字 color")
                    f.style = textStyle
                    f.contentColor = color
                    assertFalse(terminalUsesBasicText(f.row.text, textStyle))
                }
                val expected = capture()
                compose.runOnIdle { f.optimized = true }
                assertEquivalent(expected, capture())
            }
        }
        val uri = "https://example.invalid/terminal"
        compose.runOnIdle {
            f.optimized = false
            f.style = style
            f.row.text = buildAnnotatedString { withLink(LinkAnnotation.Url(uri)) { append("clickable link") } }
            assertFalse(terminalUsesBasicText(f.row.text, f.style))
        }
        val expected = capture()
        val point = layout().getBoundingBox(2).center
        compose.onNode(textMatcher, useUnmergedTree = true).performTouchInput { click(point) }
        compose.runOnIdle { assertEquals(listOf(uri), f.openedUris); f.optimized = true }
        assertEquivalent(expected, capture())
        compose.onNode(textMatcher, useUnmergedTree = true).performTouchInput { click(point) }
        compose.runOnIdle { assertEquals(listOf(uri, uri), f.openedUris) }
    }

    @Test fun terminalTextSelectionCopiesIdenticalTextThroughTheSystemMenu() {
        val f = Fixture(style.copy(fontSize = 24.sp, lineHeight = 24.sp)).apply {
            selection = true
            row.text = buildAnnotatedString {
                append("copyword 中文 e\u0301 URL")
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.Yellow), 0, 8)
                addStringAnnotation("URL", "https://example.invalid/terminal", 16, length)
            }
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val copyLabel = compose.activity.getString(android.R.string.copy)
        compose.setContent { f.Content() }
        for (rtl in listOf(false, true)) for (optimized in listOf(false, true)) {
            compose.runOnIdle {
                clipboard.clearPrimaryClip()
                f.direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                f.optimized = optimized
            }
            compose.waitForIdle()
            val point = layout().getBoundingBox(3).center
            compose.onNode(textMatcher, useUnmergedTree = true).performTouchInput { longClick(point) }
            val copy = checkNotNull(device.wait(Until.findObject(By.text(copyLabel)), 5_000)) {
                "System Copy action missing: rtl=$rtl terminal=$optimized"
            }
            copy.click()
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyword" }
            compose.runOnIdle { assertEquals("copyword", clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        }
    }

    private class Fixture(initialStyle: TextStyle) {
        var optimized by mutableStateOf(false)
        var style by mutableStateOf(initialStyle)
        var contentColor by mutableStateOf(Color.Magenta)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var density by mutableStateOf<Density>(Density(1f))
        var selection = false
        val row = TerminalRenderedRowState(1, AnnotatedString("initial"))
        val openedUris = mutableListOf<String>()
        private val handler = object : UriHandler {
            override fun openUri(uri: String) { openedUris.add(uri) }
        }

        @Composable fun Content() {
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction,
                LocalUriHandler provides handler) {
                MaterialTheme {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            Box(Modifier.size(360.dp, 110.dp).background(Color(0xFF101010)).testTag("text-pixels")) {
                                key(optimized, direction) {
                                    val text: @Composable () -> Unit = {
                                        if (optimized) TerminalRenderedRow(row, style) else {
                                            Text(text = row.text, style = style, softWrap = false, maxLines = 1)
                                        }
                                    }
                                    if (selection) SelectionContainer { text() } else text()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
