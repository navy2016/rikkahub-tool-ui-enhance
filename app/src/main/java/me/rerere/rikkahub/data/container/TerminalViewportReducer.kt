package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * Semantic viewport modes for the terminal reducer.
 * Replaces the boolean autoScroll + fragile pixel-based compensation with an
 * explicit mode that distinguishes tail-follow, screen-follow, and line-locked.
 */
enum class ViewportMode {
    /** Follow the latest output (bottom of the grid). History append/trim keeps
     *  the scroll at the bottom — no drift. */
    TAIL,

    /** Follow the semantic physical-screen target supplied by the terminal UI.
     *  New output can push rows into history without moving the screen lane. */
    SCREEN,

    /** Locked to a specific stable line ID. The scroll offset is recomputed
     *  so that the anchored line remains at a fixed pixel offset from the
     *  viewport top, even across history trim, resize, or font-size change. */
    LOCKED
}

enum class ViewportScrollOrigin(val isUserInput: Boolean) {
    USER_DRAG(true),
    USER_FLING(true),
    REDUCER(false),
    RESTORE(false),
    IME(false),
    RESIZE(false),
    JUMP(false),
}

/**
 * Pure reducer input: the previous viewport state and the new terminal frame.
 * Produces a new viewport state and a target scroll offset (in pixels) that
 * the UI should apply.
 */
data class ViewportInput(
    val mode: ViewportMode,
    /** For LOCKED mode: the stable line ID to anchor on. */
    val anchorLineId: Long? = null,
    /** For LOCKED mode: how many pixels at the top of the anchored line are
     *  clipped above the viewport. */
    val anchorClippedTopPx: Int = 0,
    /** Generation of the physical screen that owned this anchor when it was captured. */
    val anchorScreenGeneration: Long? = null,
    /** Generation of scrollback history that owned a history anchor. */
    val anchorHistoryGeneration: Long? = null,
    /** Scroll offset reported by the scrollable container (px). */
    val currentScrollPx: Int = 0,
    /** Maximum scroll value (px). */
    val maxScrollPx: Int = 0,
    /** Viewport height (px) of the grid-usable area. */
    val viewportHeightPx: Int = 0,
    /** Cell height (px) for rounding. */
    val cellHeightPx: Int = 20,
    /** Semantic target for normal tail-following. */
    val tailScrollPx: Int = maxScrollPx,
    /** Semantic target for physical-screen following. Defaults to screen top. */
    val screenScrollPx: Int? = null,
    /** Mode selected when a locked anchor no longer exists. */
    val fallbackMode: ViewportMode = ViewportMode.TAIL,
    /** Exact target derived from measured layout items; null keeps the legacy fixed-grid reducer. */
    val measuredAnchorScrollPx: Int? = null,
)

data class ViewportOutput(
    val mode: ViewportMode,
    val anchorLineId: Long? = null,
    val anchorClippedTopPx: Int = 0,
    val anchorScreenGeneration: Long? = null,
    val anchorHistoryGeneration: Long? = null,
    /** The scroll offset the UI should apply. */
    val targetScrollPx: Int,
    /** Whether the original anchor disappeared, with either replacement or fallback applied. */
    val anchorTrimmed: Boolean = false,
)

/** A stable row anchor captured from the current scroll position. */
data class ViewportAnchor(
    val lineId: Long,
    val clippedTopPx: Int,
    /** Non-null only when [lineId] belongs to the active physical screen. */
    val screenGeneration: Long?,
    /** Non-null only when [lineId] belongs to scrollback history. */
    val historyGeneration: Long?,
)

/**
 * Reduces the previous viewport state against a new terminal frame,
 * returning the target scroll offset and updated mode.
 *
 * @param input  previous viewport state and current scroll metrics.
 * @param frame  the new terminal frame (with line-ID metadata).
 * @param renderedRows  the rendered row count in the UI (history + screen).
 */
fun reduceViewport(
    input: ViewportInput,
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
): ViewportOutput {
    val maxScroll = input.maxScrollPx

    fun fallback(trimmed: Boolean = true): ViewportOutput {
        val mode = input.fallbackMode
        val target = when (mode) {
            ViewportMode.TAIL -> input.tailScrollPx
            ViewportMode.SCREEN -> input.screenScrollPx ?: frame.screenStartRow * input.cellHeightPx
            ViewportMode.LOCKED -> input.tailScrollPx
        }.coerceIn(0, maxScroll)
        return ViewportOutput(
            mode = if (mode == ViewportMode.LOCKED) ViewportMode.TAIL else mode,
            targetScrollPx = target,
            anchorTrimmed = trimmed,
        )
    }

    return when (input.mode) {
        ViewportMode.TAIL -> {
            // TAIL: always scroll to the bottom of the content.
            val target = input.tailScrollPx.coerceIn(0, maxScroll)
            ViewportOutput(
                mode = ViewportMode.TAIL,
                targetScrollPx = target,
            )
        }

        ViewportMode.SCREEN -> {
            // SCREEN: follow the physical-screen semantic target.
            val screenStartPx = input.screenScrollPx ?: frame.screenStartRow * input.cellHeightPx
            val target = screenStartPx.coerceIn(0, maxScroll)
            ViewportOutput(
                mode = ViewportMode.SCREEN,
                targetScrollPx = target,
            )
        }

        ViewportMode.LOCKED -> {
            val renderedLineIds = buildLineIdsFromFrame(frame, renderedRows)
            val anchorId = input.anchorLineId ?: run {
                return fallback()
            }
            val anchorRowIndex = renderedLineIds.indexOf(anchorId)
            val anchorIsScreenRow = anchorRowIndex >= frame.historyCount
            val historyGenerationChanged = input.anchorScreenGeneration == null &&
                input.anchorHistoryGeneration != null &&
                input.anchorHistoryGeneration != frame.historyGeneration

            when {
                anchorRowIndex >= 0 && anchorIsScreenRow && input.anchorScreenGeneration != null &&
                    input.anchorScreenGeneration != frame.screenGeneration -> {
                    // A full clear or alternate-screen reset replaced this physical screen.
                    fallback()
                }

                anchorRowIndex >= 0 && !anchorIsScreenRow && historyGenerationChanged -> {
                    // The entire scrollback was cleared; a numerically similar ID is unrelated.
                    fallback()
                }

                anchorRowIndex >= 0 -> {
                    val target = input.measuredAnchorScrollPx ?:
                        (anchorRowIndex * input.cellHeightPx + input.anchorClippedTopPx)
                        .coerceIn(0, maxScroll)
                    ViewportOutput(
                        mode = ViewportMode.LOCKED,
                        anchorLineId = anchorId,
                        anchorClippedTopPx = input.anchorClippedTopPx,
                        anchorScreenGeneration = if (anchorIsScreenRow) input.anchorScreenGeneration else null,
                        anchorHistoryGeneration = if (anchorIsScreenRow) null else frame.historyGeneration,
                        targetScrollPx = target,
                    )
                }

                input.anchorScreenGeneration != null || historyGenerationChanged || renderedLineIds.isEmpty() -> {
                    fallback()
                }

                else -> {
                    // A normal scrollback-limit trim removes rows from the head. Keep LOCKED mode
                    // on the closest surviving history row instead of jumping to the live tail.
                    val replacementId = nearestHistoryLineId(frame.historyLineIds, anchorId)
                        ?: return fallback()
                    val replacementRowIndex = frame.historyLineIds.indexOf(replacementId)
                    val target = input.measuredAnchorScrollPx ?:
                        (replacementRowIndex * input.cellHeightPx + input.anchorClippedTopPx)
                        .coerceIn(0, maxScroll)
                    ViewportOutput(
                        mode = ViewportMode.LOCKED,
                        anchorLineId = replacementId,
                        anchorClippedTopPx = input.anchorClippedTopPx,
                        anchorHistoryGeneration = frame.historyGeneration,
                        targetScrollPx = target,
                        anchorTrimmed = true,
                    )
                }
            }
        }
    }
}

/**
 * Builds a list of stable line IDs for each rendered row (history + screen).
 * Returns an empty list if the frame's stable-ID metadata is incomplete.
 */
internal fun buildLineIdsFromFrame(
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
): List<Long> {
    if (renderedRows <= 0) return emptyList()

    val historyCount = frame.historyCount
    if (historyCount < 0 || historyCount > renderedRows) return emptyList()

    // Stable IDs are the source of truth. The range endpoints are diagnostic metadata only:
    // IDs may be non-contiguous after resize, clear, or alternate-screen transitions.
    val historyIds = when {
        historyCount == 0 -> emptyList()
        frame.historyLineIds.size == historyCount -> frame.historyLineIds
        else -> return emptyList()
    }

    val screenRows = renderedRows - historyCount
    if (frame.screenLineIds.size < screenRows) return emptyList()

    return buildList(renderedRows) {
        addAll(historyIds)
        addAll(frame.screenLineIds.take(screenRows))
    }
}

internal fun nearestHistoryLineId(historyLineIds: List<Long>, anchorLineId: Long): Long? =
    historyLineIds.minByOrNull { lineId ->
        if (lineId >= anchorLineId) lineId - anchorLineId else anchorLineId - lineId
    }

/** Captures the stable row visible at the top edge of a manually positioned viewport. */
internal fun captureViewportAnchor(
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
    scrollPx: Int,
    cellHeightPx: Int,
): ViewportAnchor? {
    if (cellHeightPx <= 0) return null
    val lineIds = buildLineIdsFromFrame(frame, renderedRows)
    if (lineIds.isEmpty()) return null
    val row = (scrollPx.coerceAtLeast(0) / cellHeightPx).coerceIn(0, lineIds.lastIndex)
    val lineId = lineIds.getOrNull(row) ?: return null
    val clippedTopPx = scrollPx.coerceAtLeast(0) % cellHeightPx
    return ViewportAnchor(
        lineId = lineId,
        clippedTopPx = clippedTopPx,
        screenGeneration = if (row >= frame.historyCount) frame.screenGeneration else null,
        historyGeneration = if (row < frame.historyCount) frame.historyGeneration else null,
    )
}
