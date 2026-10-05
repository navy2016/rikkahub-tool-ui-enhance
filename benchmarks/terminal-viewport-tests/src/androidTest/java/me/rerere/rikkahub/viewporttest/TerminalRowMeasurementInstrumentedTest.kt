package me.rerere.rikkahub.viewporttest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReusableContent
import androidx.compose.runtime.ReusableContentHost
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRow
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Compare actual Text, not estimated cells. The old Box wrapper is a test-only geometry oracle. */
class TerminalRowMeasurementInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(45)

    private val base = TerminalEmulator(80, 6).renderFrame()
    private val style = TextStyle(fontFamily = JetbrainsMono, fontSize = 14.sp, lineHeight = 14.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

    private fun pass(row: TerminalRenderedRowState, metric: Any) = TerminalLazyLayoutPass(base.copy(
        rows = listOf(TerminalEmulator.RenderedRow(row.text)), screenLineIds = listOf(row.lineId),
    ), metric)

    private data class Geometry(
        val outer: IntSize, val origin: Offset, val size: IntSize,
        val textSize: IntSize, val firstBaseline: Float, val lastBaseline: Float,
    )

    private fun geometry(size: IntSize): Geometry {
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true).fetchSemanticsNodes().single()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
        val text = results.single()
        return Geometry(size, node.positionInRoot, node.size, text.size, text.firstBaseline, text.lastBaseline)
    }

    @Composable private fun LegacyRow(row: TerminalRenderedRowState, textStyle: TextStyle) {
        Box(Modifier.layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.place(0, 0) }
        }) { TerminalRenderedRow(row, textStyle) }
    }

    @Test fun measurementNodeMatchesLegacyMinimumConstraintsRtlAndStyledTextGeometry() {
        val measurements = TerminalLazyItemMeasurements()
        val texts = listOf(AnnotatedString("short"), buildAnnotatedString {
            append("中文 e\u0301 العربية 👩‍💻")
            addStyle(SpanStyle(fontSize = 29.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), 0, 3)
        })
        var row by mutableStateOf(TerminalRenderedRowState(10, texts.first()), referentialEqualityPolicy())
        var metric by mutableStateOf(0)
        var node by mutableStateOf(false)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var density by mutableStateOf(Density(1f))
        var limits by mutableStateOf(Constraints(maxWidth = 800, maxHeight = 300))
        var size = IntSize.Zero
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Layout(content = {
                        if (node) measurements.Row(metric, row, style) else LegacyRow(row, style)
                    }) { measurables, _ ->
                        val child = measurables.single().measure(limits)
                        size = IntSize(child.width, child.height)
                        layout(child.width, child.height) { child.place(0, 0) }
                    }
                }
            }
        }
        for (text in texts) for (rtl in listOf(false, true)) for (minimum in listOf(0, 220)) {
            compose.runOnIdle {
                node = false
                row = TerminalRenderedRowState(10, text)
                direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                density = if (minimum == 0) Density(1f) else Density(1.33f, 1.3f)
                limits = Constraints(minWidth = minimum, maxWidth = 800,
                    minHeight = if (minimum == 0) 0 else 80, maxHeight = 300)
                metric++
            }
            compose.waitForIdle()
            val original = geometry(size)
            compose.runOnIdle { node = true }
            compose.waitForIdle()
            assertEquals(original, geometry(size))
            compose.runOnIdle {
                val measured = requireNotNull(measurements.readEager(pass(row, metric)))
                assertEquals(original.outer.height, measured.height(0))
                assertEquals(1, measurements.retainedRows)
            }
        }
    }

    @Test fun measurementNodeTextMetricIdAndRegistryReplacementDoNotAllocateAnotherNode() {
        val original = TerminalLazyItemMeasurements()
        var measurements by mutableStateOf(original, referentialEqualityPolicy())
        var row by mutableStateOf(TerminalRenderedRowState(7, AnnotatedString("small")), referentialEqualityPolicy())
        var textStyle by mutableStateOf(style)
        compose.setContent { MaterialTheme { measurements.Row(textStyle, row, textStyle) } }
        compose.waitForIdle()
        val oldPass = pass(row, textStyle)
        val old = compose.runOnIdle { requireNotNull(original.readEager(oldPass)) }
        val oldHeight = old.height(0)
        compose.runOnIdle {
            row.text = buildAnnotatedString { append("large 中"); addStyle(SpanStyle(fontSize = 37.sp), 0, 5) }
            textStyle = style.copy(fontSize = 23.sp)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(original.peekEager(oldPass))
            assertTrue(requireNotNull(original.readEager(pass(row, textStyle))).height(0) > oldHeight)
            assertEquals(oldHeight, old.height(0))
            assertEquals(1L, original.createdRowNodes)
            row = TerminalRenderedRowState(99, AnnotatedString("replacement"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, original.retainedRows)
            assertNotNull(original.readEager(pass(row, textStyle)))
            assertEquals(1L, original.createdRowNodes)
            measurements = TerminalLazyItemMeasurements()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, original.retainedRows)
            assertNull(original.peekEager(oldPass))
            assertEquals(1, measurements.retainedRows)
            assertNotNull(measurements.readEager(pass(row, textStyle)))
            assertEquals(0L, measurements.createdRowNodes) // Existing UI node rebound to a new registry.
        }
    }

    @Test fun measurementNodeOlderOwnerDisposalCannotEraseItsReplacement() {
        val measurements = TerminalLazyItemMeasurements()
        val row = TerminalRenderedRowState(15, AnnotatedString("same stable ID"))
        var oldVisible by mutableStateOf(true)
        var newVisible by mutableStateOf(false)
        val bigger = style.copy(fontSize = 28.sp)
        compose.setContent {
            MaterialTheme {
                Column {
                    if (oldVisible) key("old") { measurements.Row(style, row, style) }
                    if (newVisible) key("new") { measurements.Row(bigger, row, bigger) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, measurements.retainedRows); newVisible = true }
        compose.waitForIdle()
        val newPass = pass(row, bigger)
        val height = compose.runOnIdle {
            val result = requireNotNull(measurements.readEager(newPass))
            oldVisible = false
            result.height(0)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, measurements.retainedRows)
            assertEquals(height, requireNotNull(measurements.readEager(newPass)).height(0))
            newVisible = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, measurements.retainedRows)
            assertNull(measurements.peekEager(newPass))
        }
    }

    @Test fun measurementNodeIntrinsicQueriesNeverPublishOrInvalidateHeights() {
        val measurements = TerminalLazyItemMeasurements()
        val row = TerminalRenderedRowState(9, AnnotatedString("intrinsic 中"))
        var mutations = 0L
        var queries = 0
        var height = 0
        compose.setContent {
            MaterialTheme {
                Layout(content = { measurements.Row(style, row, style) }) { measurables, _ ->
                    val measurable = measurables.single()
                    val before = measurements.measuredRowCount
                    measurable.minIntrinsicWidth(200)
                    measurable.maxIntrinsicWidth(200)
                    measurable.minIntrinsicHeight(400)
                    measurable.maxIntrinsicHeight(400)
                    queries += 4
                    mutations += measurements.measuredRowCount - before
                    val child = measurable.measure(Constraints(maxWidth = 800, maxHeight = 300))
                    height = child.height
                    layout(child.width, child.height) { child.place(0, 0) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(queries >= 4)
            assertEquals(0L, mutations)
            assertEquals(1, measurements.retainedRows)
            assertEquals(height, requireNotNull(measurements.readEager(pass(row, style))).height(0))
        }
    }

    @Test fun measurementNodePooledContentReleasesAndRemeasuresOnReactivation() {
        val measurements = TerminalLazyItemMeasurements()
        var active by mutableStateOf(true)
        var row by mutableStateOf(TerminalRenderedRowState(17, AnnotatedString("old")), referentialEqualityPolicy())
        compose.setContent {
            MaterialTheme {
                ReusableContentHost(active) {
                    ReusableContent(row.lineId) { measurements.Row(style, row, style) }
                }
            }
        }
        compose.waitForIdle()
        val oldPass = pass(row, style)
        compose.runOnIdle { assertNotNull(measurements.readEager(oldPass)); active = false }
        compose.waitForIdle()
        val visits = compose.runOnIdle {
            assertEquals(0, measurements.retainedRows)
            assertNull(measurements.peekEager(oldPass))
            row = TerminalRenderedRowState(23, AnnotatedString("new reused row"))
            active = true
            measurements.measuredRowCount
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, measurements.retainedRows)
            assertTrue(measurements.measuredRowCount > visits)
            assertNotNull(measurements.readEager(pass(row, style)))
            active = false
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, measurements.retainedRows) }
    }
}
