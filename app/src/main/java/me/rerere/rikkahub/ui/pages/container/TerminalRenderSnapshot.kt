package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.snapshots.SnapshotStateList
import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * The frame and immutable row-list structure read in ONE caller snapshot. Capture once at initial
 * mount and after row synchronization INSIDE the publisher's mutable snapshot; publish this object
 * in that transaction. The binding's scope reads the published value before making child lambdas.
 * Neither child recomposition nor input/layout updates recapture or compare the history structure.
 *
 * Dense toList() is O(1); sparse publications share owned immutable history and bounded screen states.
 * Row objects keep their existing per-row observable text and stable line IDs.
 * This does not freeze/copy Text, retain a second history or own a coroutine. Every publication,
 * including same-frame forced blank commits, gets a fresh identity; this is not a revision cache.
 */
internal class TerminalRenderSnapshot private constructor(
    val frame: TerminalEmulator.RenderFrame,
    val rows: List<TerminalRenderedRowState>,
    val cursors: Map<Long, TerminalCursorState> = emptyMap(),
) {
    companion object {
        fun capture(frame: TerminalEmulator.RenderFrame, store: TerminalRowStore): TerminalRenderSnapshot =
            TerminalRenderSnapshot(frame, store.view(frame), store.cursors(frame))

        fun capture(
            frame: TerminalEmulator.RenderFrame,
            rows: SnapshotStateList<TerminalRenderedRowState>,
        ): TerminalRenderSnapshot {
            val view = rows.toList()
            require(view.size == frame.rows.size) {
                "Terminal render capture mismatch: frameRevision=${frame.revision} " +
                    "frameRows=${frame.rows.size} rows=${view.size}"
            }
            return TerminalRenderSnapshot(frame, view)
        }
    }
}

/** Keep the exact original invariant; diagnostics contain only counts/revisions, never text. */
internal fun requireTerminalRenderPair(
    pass: TerminalLazyLayoutPass,
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
) {
    require(pass.frame === frame && rows.size == frame.rows.size) {
        "Terminal render pair mismatch: sameFrame=${pass.frame === frame} " +
            "passRevision=${pass.frame.revision} frameRevision=${frame.revision} " +
            "passRows=${pass.frame.rows.size} frameRows=${frame.rows.size} rows=${rows.size}"
    }
}
