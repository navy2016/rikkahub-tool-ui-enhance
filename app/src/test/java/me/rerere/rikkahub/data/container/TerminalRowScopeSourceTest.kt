package me.rerere.rikkahub.data.container

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TerminalRowScopeSourceTest {
    private fun source(name: String): String = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/$name.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/$name.kt"),
    ).first { it.isFile }.readText()

    @Test fun rowScopeMeasuredRowReadsOnceAndUsesTheSameTextForHeightAndDrawing() {
        val code = source("TerminalLazyItemMeasurements")
        val row = code.substringAfter("fun Row(").substringBefore("private fun record(")
        assertEquals(1, Regex("val text = row\\.text").findAll(row).count())
        assertTrue(row.contains("TerminalRowText(text, style, rowModifier(metricKey, row.lineId, text))"))
        assertFalse(row.contains("TerminalRenderedRow("))
        assertTrue(row.contains("RowMeasurementElement(this, metricKey, lineId, text)"))
        assertFalse(code.contains("@NonRestartableComposable")) // This row owns the subscription.
    }

    @Test fun rowScopePureForwarderIsNonRestartableButUnmeasuredRowsKeepOwnScope() {
        val code = source("TerminalRenderedRows")
        val pure = code.substringAfter("@NonRestartableComposable\ninternal fun TerminalRowText(")
            .substringBefore("internal const val TERMINAL_HISTORY_CHUNK_ROWS")
        assertTrue(pure.contains("text: AnnotatedString"))
        assertFalse(pure.contains("state.text"))
        assertTrue(pure.contains(
            "BasicText(text = text, modifier = modifier, style = style, softWrap = false, maxLines = 1)"))
        assertTrue(pure.contains(
            "Text(text = text, modifier = modifier, style = style, softWrap = false, maxLines = 1)"))
        val wrapper = code.substringAfter("@Composable\ninternal fun TerminalRenderedRow(")
            .substringBefore("/** Caller owns the row subscription")
        assertTrue(wrapper.contains("TerminalRowText(state.text, style, modifier)"))
        assertFalse(wrapper.contains("@NonRestartableComposable"))
        assertEquals(1, Regex("@NonRestartableComposable").findAll(code).count())
    }

    @Test fun rowScopeNodeStillInvalidatesAndReleasesOwnersWithoutPhantomIntrinsicMeasurements() {
        val code = source("TerminalLazyItemMeasurements")
        assertTrue(code.contains("heights[lineId]?.owner === owner"))
        assertTrue(code.contains("eagerCache.invalidateBlock(previous?.historyBlock)"))
        assertTrue(code.contains("override fun onDetach() { measurements.release(owner, lineId) }"))
        val intrinsics = code.substringAfter("// Intrinsic queries").substringBefore("private fun validFrame")
        assertFalse(intrinsics.contains("measurements.record("))
        assertFalse(code.contains("withoutReadObservation"))
        assertFalse(code.contains("heightIn("))
    }
}
