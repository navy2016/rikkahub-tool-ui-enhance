package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLazyViewportMeasurementTest {
    private fun frame(
        history: List<Long> = listOf(10, 11, 12),
        screen: List<Long> = listOf(100, 101),
        historyGeneration: Long = 3,
        screenGeneration: Long = 8,
    ) = TerminalEmulator.RenderFrame(
        rows = List(history.size + screen.size) { TerminalEmulator.RenderedRow(AnnotatedString("row")) },
        contentBounds = TerminalEmulator.ContentBounds(0, history.size + screen.size - 1, history.size + screen.size),
        screenContentBounds = TerminalEmulator.ContentBounds(0, screen.lastIndex, screen.size),
        screenStartRow = history.size,
        historyCount = history.size,
        historyLineIds = history,
        historyGeneration = historyGeneration,
        screenLineIds = screen,
        screenGeneration = screenGeneration,
        cursorRow = 0,
        cursorVisible = false,
        isAlternateScreen = false,
        modeSummary = "",
        revision = 1,
    )

    @Test
    fun overlappingLayoutsPreserveMeasuredAbsoluteContentCoordinates() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val initial = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 10, 0, 20),
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 11, 20, 36),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = true,
        )
        assertEquals(0, initial.currentScrollPx)
        assertNull(initial.maxScrollPx)
        assertEquals(false, initial.generationChanged)

        val next = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 11, -12, 36),
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 12, 24, 18),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = true,
        )
        assertEquals(32, next.currentScrollPx)
        assertEquals(20, next.layout.measuredRows.first { it.lineId == 11L }.topPx)
        assertEquals(56, next.layout.measuredRows.first { it.lineId == 12L }.topPx)
    }

    @Test
    fun disjointLayoutUsesExplicitExpectedScrollInsteadOfRowHeightEstimate() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 10, 0, 20),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = true,
        )
        tracker.setExpectedScrollPx(417)
        val next = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 12, -9, 18),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = true,
        )
        assertEquals(417, next.currentScrollPx)
        assertEquals(408, next.layout.measuredRows.single().topPx)
    }

    @Test
    fun tailObservationMakesRangeKnownAndScreenRowsUseActualHeights() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, offsetPx = 1, heightPx = 55),
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.TAIL, offsetPx = 72, heightPx = 8),
            ),
            screenRowHeights = mapOf(100L to 24, 101L to 31),
            screenRowsComplete = true,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = false,
        )
        assertEquals(0, result.currentScrollPx)
        assertEquals(0, result.maxScrollPx)
        assertEquals(1, result.layout.activeScreenItemTopPx)
        assertEquals(25, result.layout.measuredRows.first { it.lineId == 101L }.topPx)
        assertEquals(31, result.layout.measuredRows.first { it.lineId == 101L }.heightPx)
    }

    @Test
    fun aFalseForwardBoundaryWithoutTheTailDoesNotClaimKnownRange() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 11, 0, 36),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = false,
        )
        assertNull(result.maxScrollPx)
    }

    @Test
    fun knownRangeUsesMeasuredTailBottomAndViewportHeight() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.TAIL, offsetPx = 220, heightPx = 8),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = false,
        )
        assertEquals(128, result.maxScrollPx)
        assertEquals(128, result.maxScrollPx?.coerceIn(0, 128))
    }

    @Test
    fun measuredRangeNeverFallsBehindTheCurrentScrollPosition() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        tracker.setExpectedScrollPx(200)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.TAIL, offsetPx = 0, heightPx = 8),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 100,
            canScrollForward = false,
        )
        assertEquals(200, result.currentScrollPx)
        assertEquals(200, result.maxScrollPx)
    }

    @Test
    fun incompletePhysicalScreenMeasurementDoesNotPublishPartialRows() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, offsetPx = 0, heightPx = 55),
            ),
            screenRowHeights = mapOf(100L to 24),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        assertEquals(emptyList<TerminalMeasuredViewportItem>(), result.layout.measuredRows)
    }

    @Test
    fun screenRowsAreRejectedWhenTheirMeasuredSumDiffersFromTheScreenItem() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        val result = tracker.update(
            frame = frame(),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, offsetPx = 0, heightPx = 70),
            ),
            screenRowHeights = mapOf(100L to 24, 101L to 31),
            screenRowsComplete = true,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        assertEquals(emptyList<TerminalMeasuredViewportItem>(), result.layout.measuredRows)
    }

    @Test
    fun generationChangeDoesNotReuseExpectedCoordinateFromTheOldLayout() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        tracker.update(
            frame = frame(historyGeneration = 3),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 10, 0, 20),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        tracker.setExpectedScrollPx(900)
        val next = tracker.update(
            frame = frame(history = listOf(20, 21), historyGeneration = 4),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 20, 0, 20),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        assertEquals(0, next.currentScrollPx)
        assertEquals(true, next.generationChanged)
    }

    @Test
    fun screenGenerationChangeInvalidatesOnlyScreenGeometry() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        tracker.update(
            frame = frame(screenGeneration = 8),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 10, 0, 20),
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, 20, 20, 55),
            ),
            screenRowHeights = mapOf(100L to 24, 101L to 31),
            screenRowsComplete = true,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        val next = tracker.update(
            frame = frame(screenGeneration = 9),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.HISTORY, 10, 0, 20),
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, 20, 20, 55),
            ),
            screenRowHeights = emptyMap(),
            screenRowsComplete = false,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        assertEquals(true, next.generationChanged)
        assertEquals(0, next.layout.measuredRows.single { it.lineId == 10L }.topPx)
        assertEquals(null, next.layout.measuredRows.firstOrNull { it.lineId == 100L })
    }

    @Test
    fun stableGenerationAfterInvalidationCanPublishMeasuredRowsAgain() {
        val tracker = TerminalLazyViewportMeasurementTracker(tailItemHeightPx = 8)
        tracker.update(
            frame = frame(screenGeneration = 8),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, offsetPx = 0, heightPx = 55),
            ),
            screenRowHeights = mapOf(100L to 24, 101L to 31),
            screenRowsComplete = true,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        val stable = tracker.update(
            frame = frame(screenGeneration = 8),
            visibleItems = listOf(
                TerminalLazyViewportVisibleItem(TerminalLazyViewportItemKind.ACTIVE_SCREEN, offsetPx = 0, heightPx = 55),
            ),
            screenRowHeights = mapOf(100L to 24, 101L to 31),
            screenRowsComplete = true,
            viewportStartOffsetPx = 0,
            viewportHeightPx = 80,
            canScrollForward = true,
        )
        assertEquals(false, stable.generationChanged)
        assertEquals(2, stable.layout.measuredRows.size)
    }
}
