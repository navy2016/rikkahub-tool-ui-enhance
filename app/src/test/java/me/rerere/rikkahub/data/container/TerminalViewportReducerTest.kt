package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewportReducerTest {

    private val cellHeightPx = 20
    private val viewportHeightPx = 200
    private val maxScrollPx = 1000

    private fun emptyFrame(
        screenStartRow: Int = 0,
        historyCount: Int = 0,
        historyStartId: Long = 0,
        historyEndId: Long = 0,
        historyLineIds: List<Long> = emptyList(),
        screenLineIds: List<Long> = listOf(1, 2, 3, 4, 5),
        screenGeneration: Long = 1,
        historyTrimmedCount: Int = 0,
    ): TerminalEmulator.RenderFrame = TerminalEmulator.RenderFrame(
        rows = emptyList(),
        contentBounds = TerminalEmulator.ContentBounds(0, 4, 5),
        screenContentBounds = TerminalEmulator.ContentBounds(0, 4, 5),
        screenStartRow = screenStartRow,
        historyStartId = historyStartId,
        historyEndId = historyEndId,
        historyCount = historyCount,
        historyLineIds = historyLineIds,
        historyTrimmedCount = historyTrimmedCount,
        screenLineIds = screenLineIds,
        screenGeneration = screenGeneration,
        cursorRow = 2,
        cursorVisible = true,
        isAlternateScreen = false,
        modeSummary = "",
        revision = 1,
    )

    // --- TAIL mode ---

    @Test
    fun tailAlwaysScrollsToBottom() {
        val input = ViewportInput(
            mode = ViewportMode.TAIL,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        val frame = emptyFrame()
        val output = reduceViewport(input, frame, 10)
        assertEquals(ViewportMode.TAIL, output.mode)
        assertEquals(maxScrollPx, output.targetScrollPx)
        assertEquals(false, output.anchorTrimmed)
    }

    @Test
    fun tailIgnoresHistoryTrimmed() {
        val input = ViewportInput(
            mode = ViewportMode.TAIL,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // Frame with history trimmed
        val frame = emptyFrame(historyTrimmedCount = 5, historyCount = 10, historyStartId = 100, historyEndId = 109)
        val output = reduceViewport(input, frame, 15)
        assertEquals(maxScrollPx, output.targetScrollPx)
    }

    // --- SCREEN mode ---

    @Test
    fun screenScrollsToScreenStartRow() {
        val input = ViewportInput(
            mode = ViewportMode.SCREEN,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // screenStartRow = 10, so screen start = 10 * 20 = 200px
        val frame = emptyFrame(screenStartRow = 10, historyCount = 10, historyStartId = 1, historyEndId = 10)
        val output = reduceViewport(input, frame, 15)
        assertEquals(ViewportMode.SCREEN, output.mode)
        assertEquals(200, output.targetScrollPx)
    }

    @Test
    fun screenClampsToMaxScroll() {
        val input = ViewportInput(
            mode = ViewportMode.SCREEN,
            maxScrollPx = 50,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // screenStartRow = 10 → 200px, but maxScroll=50
        val frame = emptyFrame(screenStartRow = 10, historyCount = 10, historyStartId = 1, historyEndId = 10)
        val output = reduceViewport(input, frame, 15)
        assertEquals(50, output.targetScrollPx)
    }

    // --- LOCKED mode ---

    @Test
    fun lockedStaysOnAnchorLine() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 5,
            anchorIntraOffsetPx = 0,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // 5 screen rows with ids [1,2,3,4,5]. anchorId=5 → row index 4 → 4*20=80px
        val frame = emptyFrame(screenLineIds = listOf(1, 2, 3, 4, 5))
        val output = reduceViewport(input, frame, 5)
        assertEquals(ViewportMode.LOCKED, output.mode)
        assertEquals(80, output.targetScrollPx)
        assertEquals(false, output.anchorTrimmed)
    }

    @Test
    fun lockedWithIntraOffset() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 3,
            anchorIntraOffsetPx = 10,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // anchorId=3 → row index 2 → 2*20 - 10 = 30px
        val frame = emptyFrame(screenLineIds = listOf(1, 2, 3, 4, 5))
        val output = reduceViewport(input, frame, 5)
        assertEquals(30, output.targetScrollPx)
    }

    @Test
    fun lockedAnchorTrimmedFallsBackToTail() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 999, // doesn't exist
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        val frame = emptyFrame(screenLineIds = listOf(1, 2, 3, 4, 5))
        val output = reduceViewport(input, frame, 5)
        assertEquals(ViewportMode.TAIL, output.mode)
        assertEquals(maxScrollPx, output.targetScrollPx)
        assertEquals(true, output.anchorTrimmed)
    }

    @Test
    fun lockedAnchorHistoryTrimmedFallsBackToTail() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 50, // in history, but history trimmed
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        // Frame: history from 100 to 109 (10 rows), screen 201..205
        // anchorId=50 not in range
        val frame = emptyFrame(
            historyCount = 10,
            historyStartId = 100,
            historyEndId = 109,
            screenLineIds = listOf(201, 202, 203, 204, 205),
        )
        val output = reduceViewport(input, frame, 15)
        assertEquals(ViewportMode.TAIL, output.mode)
        assertEquals(true, output.anchorTrimmed)
    }

    @Test
    fun lockedAnchorNoIdFallsBackToTail() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = null,
            maxScrollPx = maxScrollPx,
            viewportHeightPx = viewportHeightPx,
            cellHeightPx = cellHeightPx,
        )
        val frame = emptyFrame()
        val output = reduceViewport(input, frame, 5)
        assertEquals(ViewportMode.TAIL, output.mode)
        assertEquals(true, output.anchorTrimmed)
    }

    // --- buildLineIdsFromFrame ---

    @Test
    fun buildLineIdsFromFrameProducesCorrectIds() {
        val frame = emptyFrame(
            historyCount = 3,
            historyStartId = 10,
            historyEndId = 12,
            screenLineIds = listOf(100, 101, 102, 103, 104),
        )
        val ids = buildLineIdsFromFrame(frame, 8)
        assertEquals(listOf(10L, 11L, 12L, 100L, 101L, 102L, 103L, 104L), ids)
    }

    @Test
    fun buildLineIdsUsesPublishedNonContiguousHistoryIds() {
        val frame = emptyFrame(
            historyCount = 3,
            historyStartId = 10,
            historyEndId = 30,
            historyLineIds = listOf(10, 21, 30),
            screenLineIds = listOf(40, 41),
        )

        assertEquals(listOf(10L, 21L, 30L, 40L, 41L), buildLineIdsFromFrame(frame, 5))
    }

    @Test
    fun captureAnchorMarksScreenRowsWithFrameGeneration() {
        val frame = emptyFrame(
            historyCount = 2,
            historyLineIds = listOf(10, 21),
            screenLineIds = listOf(30, 31, 32),
            screenGeneration = 7,
        )

        val historyAnchor = captureViewportAnchor(frame, 5, scrollPx = 20, cellHeightPx = 20)
        val screenAnchor = captureViewportAnchor(frame, 5, scrollPx = 50, cellHeightPx = 20)

        assertEquals(21L, historyAnchor?.lineId)
        assertEquals(null, historyAnchor?.screenGeneration)
        assertEquals(30L, screenAnchor?.lineId)
        assertEquals(7L, screenAnchor?.screenGeneration)
        assertEquals(10, screenAnchor?.intraOffsetPx)
    }

    @Test
    fun lockedScreenAnchorFallsBackWhenGenerationChanges() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 30,
            anchorScreenGeneration = 1,
            maxScrollPx = maxScrollPx,
            tailScrollPx = 750,
        )
        val frame = emptyFrame(screenLineIds = listOf(30, 31, 32), screenGeneration = 2)

        val output = reduceViewport(input, frame, 3)

        assertEquals(ViewportMode.TAIL, output.mode)
        assertEquals(750, output.targetScrollPx)
        assertEquals(true, output.anchorTrimmed)
    }

    @Test
    fun archivedScreenAnchorSurvivesLaterScreenGenerationChanges() {
        val input = ViewportInput(
            mode = ViewportMode.LOCKED,
            anchorLineId = 30,
            anchorScreenGeneration = 1,
            maxScrollPx = maxScrollPx,
        )
        val frame = emptyFrame(
            historyCount = 1,
            historyLineIds = listOf(30),
            screenLineIds = listOf(40, 41),
            screenGeneration = 2,
        )

        val output = reduceViewport(input, frame, 3)

        assertEquals(ViewportMode.LOCKED, output.mode)
        assertEquals(0, output.targetScrollPx)
        assertEquals(null, output.anchorScreenGeneration)
    }

    @Test
    fun tailUsesSemanticTargetInsteadOfContainerMaximum() {
        val input = ViewportInput(
            mode = ViewportMode.TAIL,
            maxScrollPx = maxScrollPx,
            tailScrollPx = 640,
        )

        assertEquals(640, reduceViewport(input, emptyFrame(), 5).targetScrollPx)
    }

    @Test
    fun buildLineIdsFromFrameHandlesNoHistory() {
        val frame = emptyFrame(
            historyCount = 0,
            historyStartId = 0,
            historyEndId = 0,
            screenLineIds = listOf(1, 2, 3),
        )
        val ids = buildLineIdsFromFrame(frame, 3)
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun buildLineIdsFromFrameHandlesHistoryOnly() {
        val frame = emptyFrame(
            historyCount = 2,
            historyStartId = 5,
            historyEndId = 6,
            screenLineIds = emptyList(),
        )
        val ids = buildLineIdsFromFrame(frame, 2)
        assertEquals(listOf(5L, 6L), ids)
    }
}
