package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalViewportItemGeometryTest {
    private fun item(id: Long, top: Int, height: Int, history: Long? = 7) =
        TerminalMeasuredViewportItem(id, top, height, historyGeneration = history)

    @Test
    fun capturesRealClippedOffsetAcrossVariableHistoryHeights() {
        val items = listOf(item(10, 0, 20), item(11, 20, 36), item(12, 56, 18))
        assertEquals(
            TerminalViewportItemAnchor(11, 7, null, 7),
            captureMeasuredViewportAnchor(items, 27),
        )
        assertEquals(27, resolveMeasuredViewportAnchor(items, TerminalViewportItemAnchor(11, 7, null, 7), 500))
    }

    @Test
    fun anchorSurvivesTrimAndReorderingByStableIdNotOldIndex() {
        val old = listOf(item(10, 0, 20), item(11, 20, 36), item(12, 56, 18))
        val anchor = requireNotNull(captureMeasuredViewportAnchor(old, 27))
        val updated = listOf(item(11, 0, 50), item(12, 50, 18), item(13, 68, 24))
        assertEquals(7, requireNotNull(resolveMeasuredViewportAnchor(updated, anchor, 500)))
    }

    @Test
    fun missingOrReplacedGenerationDoesNotRestoreAnUnrelatedItem() {
        val anchor = TerminalViewportItemAnchor(11, 7, null, 7)
        assertNull(resolveMeasuredViewportAnchor(listOf(item(11, 0, 36, history = 8)), anchor, 500))
        assertNull(resolveMeasuredViewportAnchor(listOf(item(10, 0, 20)), anchor, 500))
    }

    @Test
    fun offsetsClampInsideMeasuredItemAndMaximumScroll() {
        val items = listOf(item(10, 0, 20))
        assertEquals(19, requireNotNull(captureMeasuredViewportAnchor(items, 99)).clippedTopPx)
        assertEquals(10, resolveMeasuredViewportAnchor(items, TerminalViewportItemAnchor(10, 99, null, 7), 10))
    }

    @Test
    fun measuredResolutionRejectsWrongAnchorGeneration() {
        val items = listOf(item(10, 0, 20, history = 7))
        assertNull(resolveMeasuredViewportAnchor(
            items,
            TerminalViewportItemAnchor(10, 2, null, 8),
            100,
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroHeightItemsAreRejectedRatherThanCreatingInvalidAnchors() {
        TerminalMeasuredViewportItem(1, 0, 0)
    }
}
