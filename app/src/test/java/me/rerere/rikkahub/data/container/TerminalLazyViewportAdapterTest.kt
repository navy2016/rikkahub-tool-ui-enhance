package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLazyViewportAdapterTest {
    private val layout = TerminalLazyViewportLayout(
        historyLineIds = listOf(10, 11, 12),
        screenLineIds = listOf(100, 101),
        historyGeneration = 3,
        screenGeneration = 8,
        measuredRows = listOf(
            TerminalMeasuredViewportItem(10, 0, 20, historyGeneration = 3),
            TerminalMeasuredViewportItem(11, 20, 36, historyGeneration = 3),
            TerminalMeasuredViewportItem(12, 56, 18, historyGeneration = 3),
            TerminalMeasuredViewportItem(100, 74, 24, screenGeneration = 8),
            TerminalMeasuredViewportItem(101, 98, 31, screenGeneration = 8),
        ),
        activeScreenItemTopPx = 74,
        tailItemHeightPx = 8,
        viewportHeightPx = 200,
    )

    @Test
    fun historyAnchorTargetsTheStableHistoryItemWithItsClippedOffset() {
        assertEquals(
            TerminalLazyViewportScrollTarget(itemIndex = 1, itemScrollOffsetPx = 7),
            terminalLazyTargetForAnchor(
                layout,
                ViewportAnchor(11, 7, null, 3),
            ),
        )
    }

    @Test
    fun screenAnchorTargetsTheSinglePhysicalGridItemWithInternalOffset() {
        assertEquals(
            TerminalLazyViewportScrollTarget(itemIndex = 3, itemScrollOffsetPx = 31),
            terminalLazyTargetForAnchor(
                layout,
                ViewportAnchor(101, 7, 8, null),
            ),
        )
    }

    @Test
    fun missingScreenContainerMeasurementDoesNotGuessAnInternalTarget() {
        val withoutScreenTop = layout.copy(activeScreenItemTopPx = null)
        assertNull(terminalLazyTargetForAnchor(withoutScreenTop, ViewportAnchor(101, 7, 8, null)))
    }

    @Test
    fun offscreenHistoryAnchorUsesStableIndexWithoutAnEstimatedHeight() {
        val measuredOnlyElsewhere = layout.copy(
            measuredRows = layout.measuredRows.filter { it.lineId != 11L },
        )
        assertEquals(
            TerminalLazyViewportScrollTarget(itemIndex = 1, itemScrollOffsetPx = 7),
            terminalLazyTargetForAnchor(measuredOnlyElsewhere, ViewportAnchor(11, 7, null, 3)),
        )
    }

    @Test
    fun topAndBottomUseStableStructuralItems() {
        assertEquals(
            TerminalLazyViewportScrollTarget(itemIndex = 0, itemScrollOffsetPx = 0),
            terminalLazyTargetForTop(layout),
        )
        assertEquals(
            TerminalLazyViewportScrollTarget(itemIndex = 4, itemScrollOffsetPx = -192),
            terminalLazyTargetForBottom(layout),
        )
    }

    @Test
    fun unknownOrWrongGenerationAnchorIsNotAddressed() {
        assertNull(terminalLazyTargetForAnchor(layout, ViewportAnchor(999, 0, null, 3)))
        assertNull(terminalLazyTargetForAnchor(layout, ViewportAnchor(101, 0, 7, null)))
    }
}
