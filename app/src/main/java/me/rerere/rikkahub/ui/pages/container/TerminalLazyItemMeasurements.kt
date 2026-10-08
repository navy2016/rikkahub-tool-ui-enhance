package me.rerere.rikkahub.ui.pages.container

import android.os.Trace
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.rerere.rikkahub.data.container.TerminalEagerGeometryCache
import me.rerere.rikkahub.data.container.TerminalEagerHistoryBlockToken
import me.rerere.rikkahub.data.container.TerminalEagerViewportGeometry
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalVisibleRow
import me.rerere.rikkahub.utils.TerminalEmulator

internal const val TERMINAL_LAZY_SCREEN_KEY = "terminal-active-screen"
internal const val TERMINAL_LAZY_TAIL_KEY = "terminal-tail"
internal class TerminalLazyLayoutPass(val frame: TerminalEmulator.RenderFrame, val metricKey: Any)

/**
 * Single UI-thread observer, level-triggered invalidation. An entire eager measure/dispose burst
 * needs one wake-up, not one SharedFlow emission per row. The binding acknowledges AFTER its frame
 * wait and BEFORE reading live geometry; acknowledging after the read could swallow newer changes.
 * Replay keeps a pending wake-up across collection startup/cancellation. This retains no row/frame
 * or collector scope, posts no callbacks and adds no delay. Cache invalidation remains synchronous.
 */
internal class TerminalMeasurementInvalidations {
    private val mutableChanges = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()
    var pending = false
        private set
    var publishedNotifications = 0L
        private set
    var coalescedChanges = 0L
        private set

    fun invalidate() {
        if (pending) {
            coalescedChanges++
            return
        }
        pending = true // Set before emission: an immediate collector may acknowledge/re-invalidate.
        publishedNotifications++
        check(mutableChanges.tryEmit(Unit)) // DROP_OLDEST never needs a suspending producer.
    }

    fun acknowledge() { pending = false }
}

/**
 * UI-thread bookkeeping, not Compose state. Publishing a conflated notification never subscribes
 * a measure block to its own writes. Ownership tokens protect a row archiving/recomposing while its
 * old node is disposed. Text and font metrics, not frame revisions, prove height reuse.
 */
internal class TerminalLazyItemMeasurements {
    private data class Height(val owner: Any, val metricKey: Any, val text: AnnotatedString, val pixels: Int) {
        // Scalar-only capability, not a reference to a cache block/source/UI. Keep it on equal
        // remeasurement; a changed/disposed owner must revoke its block's height proof.
        var historyBlock: TerminalEagerHistoryBlockToken? = null
    }
    private val heights = mutableMapOf<Long, Height>()
    private val invalidations = TerminalMeasurementInvalidations()
    val changes = invalidations.changes
    val publishedNotifications: Long get() = invalidations.publishedNotifications
    val coalescedChanges: Long get() = invalidations.coalescedChanges
    private val eagerCache = TerminalEagerGeometryCache()
    val retainedRows: Int get() = heights.size
    val retainedEagerHistoryRows: Int get() = eagerCache.retainedHistoryRows
    val eagerHistoryBuildCount: Long get() = eagerCache.historyBuildCount
    val eagerVisitedHistoryRows: Long get() = eagerCache.visitedHistoryRows
    val eagerVisitedScreenRows: Long get() = eagerCache.visitedScreenRows
    val retainedEagerHistoryBlocks: Int get() = eagerCache.retainedHistoryBlocks
    val eagerVisitedHistoryBlocks: Long get() = eagerCache.visitedHistoryBlocks
    val eagerMeasuredHistoryBlocks: Long get() = eagerCache.measuredHistoryBlocks
    val eagerReusedHistoryBlocks: Long get() = eagerCache.reusedHistoryBlocks
    val eagerReusedHistoryRows: Long get() = eagerCache.reusedHistoryRows
    var createdRowNodes = 0L
        private set
    var measuredRowCount = 0L
        private set
    var changedRowMeasurements = 0L
        private set
    var createdHeightRecords = 0L
        private set
    /** Optional test probe; never observable state and never installed by the production page. */
    var onRowComposed: ((Long) -> Unit)? = null

    private fun changed(previous: Height?) {
        eagerCache.invalidateBlock(previous?.historyBlock)
        if (heights.isEmpty()) eagerCache.clear()
        invalidations.invalidate()
    }

    /** Sole binding calls this after yielding for a frame, before any live geometry read. */
    fun acknowledgeChanges() = invalidations.acknowledge()

    /** A virtual/unmeasured backend must not retain an eager frame or its full historical prefix. */
    fun clearEagerCache() = eagerCache.clear()

    fun peekEager(pass: TerminalLazyLayoutPass): TerminalEagerViewportGeometry? =
        eagerCache.peek(pass.frame, pass.metricKey)

    @Composable
    fun Row(metricKey: Any, row: TerminalRenderedRowState, style: TextStyle) {
        val text = row.text
        onRowComposed?.let { observer -> SideEffect { observer(row.lineId) } }
        TerminalRenderedRow(row, style, RowMeasurementElement(this, metricKey, row.lineId, text))
    }

    private fun record(owner: Any, metricKey: Any, lineId: Long, text: AnnotatedString, pixels: Int) {
        measuredRowCount++
        val previous = heights[lineId]
        // Parent constraint/IME passes can remeasure a row without changing its registration.
        // Keep its history-contributor proof and avoid allocating a throwaway Height record.
        if (previous != null && previous.owner === owner && previous.metricKey == metricKey &&
            previous.text == text && previous.pixels == pixels
        ) return
        val measured = Height(owner, metricKey, text, pixels)
        createdHeightRecords++
        heights[lineId] = measured
        changedRowMeasurements++
        changed(previous)
    }

    private fun release(owner: Any, lineId: Long) {
        // An old lazy item can detach AFTER its replacement has already registered the same ID.
        if (heights[lineId]?.owner === owner) changed(heights.remove(lineId))
    }

    private data class RowMeasurementElement(
        val measurements: TerminalLazyItemMeasurements,
        val metricKey: Any,
        val lineId: Long,
        val text: AnnotatedString,
    ) : ModifierNodeElement<RowMeasurementNode>() {
        override fun create(): RowMeasurementNode {
            measurements.createdRowNodes++
            return RowMeasurementNode(measurements, metricKey, lineId, text)
        }

        override fun update(node: RowMeasurementNode) = node.update(measurements, metricKey, lineId, text)

        override fun InspectorInfo.inspectableProperties() {
            name = "terminalRowMeasurement"
            properties["lineId"] = lineId // Do not retain/export transcript text to inspectors.
        }
    }

    /**
     * Measure the existing Text node instead of allocating a Box/LayoutNode + DisposableEffect
     * for every eager fallback row. The scalar registry owns a token, NEVER this UI node. Keep
     * the former Box's min-constraint stripping, TopStart placement and outer measured size.
     */
    private class RowMeasurementNode(
        private var measurements: TerminalLazyItemMeasurements,
        private var metricKey: Any,
        private var lineId: Long,
        private var text: AnnotatedString,
    ) : Modifier.Node(), LayoutModifierNode {
        private var owner = Any()

        fun update(measurements: TerminalLazyItemMeasurements, metricKey: Any, lineId: Long, text: AnnotatedString) {
            if (this.measurements !== measurements || this.metricKey != metricKey ||
                this.lineId != lineId || this.text != text
            ) {
                this.measurements.release(owner, this.lineId)
                this.measurements = measurements
                this.metricKey = metricKey
                this.lineId = lineId
                this.text = text
            }
            // Element updates use the default automatic measurement invalidation. No cached height
            // is republished until this node measures the current Text with current constraints.
        }

        override fun onAttach() { invalidateMeasurement() }
        override fun onDetach() { measurements.release(owner, lineId) }
        override fun onReset() {
            measurements.release(owner, lineId)
            owner = Any()
        }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val child = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val width = maxOf(constraints.minWidth, child.width)
            val height = maxOf(constraints.minHeight, child.height)
            measurements.record(owner, metricKey, lineId, text, height)
            return layout(width, height) { child.placeRelative(0, 0) }
        }

        // Intrinsic queries use synthetic Placeables. They must never register a phantom height
        // or invalidate a previously completed production layout.
        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
            measurable.minIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
            measurable.maxIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
            measurable.minIntrinsicHeight(width)
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
            measurable.maxIntrinsicHeight(width)
    }

    private fun validFrame(frame: TerminalEmulator.RenderFrame): Boolean =
        frame.historyCount in 0..frame.rows.size && frame.screenStartRow == frame.historyCount &&
            frame.historyLineIds.size == frame.historyCount &&
            frame.screenLineIds.size == frame.rows.size - frame.historyCount

    private fun height(pass: TerminalLazyLayoutPass, index: Int): Int? {
        val frame = pass.frame
        val id = if (index < frame.historyCount) frame.historyLineIds[index]
            else frame.screenLineIds[index - frame.historyCount]
        return heights[id]?.takeIf {
            it.metricKey == pass.metricKey && it.text == frame.rows[index].text && it.pixels > 0
        }?.pixels
    }

    fun read(pass: TerminalLazyLayoutPass, state: LazyListState, cellHeightPx: Int, tailPaddingPx: Int): TerminalItemViewport? {
        val frame = pass.frame
        if (!validFrame(frame) || frame.isAlternateScreen || cellHeightPx <= 0 || tailPaddingPx < 0) return null
        val info = state.layoutInfo
        if (info.totalItemsCount != frame.historyCount + 2 || info.viewportSize.height <= 0) return null
        val rows = mutableListOf<TerminalVisibleRow>()
        for (item in info.visibleItemsInfo) {
            if (item.index < 0) return null
            if (item.index < frame.historyCount) {
                if (item.key != frame.historyLineIds[item.index] || height(pass, item.index) != item.size) return null
                rows += TerminalVisibleRow(item.index, item.offset - info.viewportStartOffset, item.size)
            } else if (item.index == frame.historyCount) {
                if (item.key != TERMINAL_LAZY_SCREEN_KEY) return null
                var top = item.offset - info.viewportStartOffset
                val screenTop = top
                for (index in frame.historyCount until frame.rows.size) {
                    val pixels = height(pass, index) ?: return null
                    rows += TerminalVisibleRow(index, top, pixels)
                    top += pixels
                }
                if (top - screenTop != item.size) return null
            } else if (item.index != frame.historyCount + 1 || item.key != TERMINAL_LAZY_TAIL_KEY) return null
        }
        if (rows.isEmpty() || rows.zipWithNext().any { (a, b) -> a.index >= b.index || a.bottomPx > b.topPx }) return null
        return TerminalItemViewport(frame, pass.metricKey, rows, info.viewportSize.height,
            cellHeightPx, tailPaddingPx, state.canScrollBackward, state.canScrollForward)
    }

    /** Stamp only measured history rows; unchanged trusted blocks retain their original tokens. */
    fun readEager(pass: TerminalLazyLayoutPass): TerminalEagerViewportGeometry? {
        Trace.beginSection("Terminal.productionEagerGeometry")
        return try {
            eagerCache.read(pass.frame, pass.metricKey, onHistoryRowMeasured = { index, token ->
                checkNotNull(heights[pass.frame.historyLineIds[index]]).historyBlock = token
            }) { index -> height(pass, index) }
        } finally { Trace.endSection() }
    }
}

internal class TerminalRowMeasurementScope(val pass: TerminalLazyLayoutPass, val measurements: TerminalLazyItemMeasurements)
