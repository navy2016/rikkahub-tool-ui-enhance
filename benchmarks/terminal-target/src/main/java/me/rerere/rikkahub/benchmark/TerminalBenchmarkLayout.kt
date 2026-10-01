package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.ui.pages.container.TerminalHistoryChunk
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.utils.TerminalEmulator

internal enum class BenchmarkRenderer(val wireName: String) {
    EAGER("eager"),
    LAZY_HISTORY("lazyHistory"),
    LAZY_INTRINSIC("lazyIntrinsic"),
    CHUNKED_EAGER("chunkedEager"),
    CHUNKED_LAYERS("chunkedLayers");

    companion object {
        fun fromWireName(value: String): BenchmarkRenderer = entries.firstOrNull { it.wireName == value }
            ?: error("Unknown benchmark renderer: $value")
    }
}

/** Benchmark-only partition. No production viewport state or pixel-to-row adapter lives here. */
internal data class TerminalBenchmarkLayout(
    val historyRows: Int,
    val screenRows: Int,
    val useLazyHistory: Boolean,
    val historyChunks: List<TerminalHistoryChunk> = emptyList(),
    val isolateChunkDrawing: Boolean = false,
    val useIntrinsicWidths: Boolean = false,
) {
    val useChunkedHistory: Boolean get() = historyChunks.isNotEmpty()
    val activeScreenItemIndex: Int get() = historyRows
    val tailItemIndex: Int get() = historyRows + 1
    val lazyItemCount: Int get() = historyRows + 2

    companion object {
        const val ACTIVE_SCREEN_KEY = "terminal-active-screen"
        const val TAIL_KEY = "terminal-tail"

        fun fromFrame(
            frame: TerminalEmulator.RenderFrame,
            renderer: BenchmarkRenderer,
            configuredTui: Boolean = false,
            preserveFullGrid: Boolean = false,
        ): TerminalBenchmarkLayout {
            require(frame.historyCount >= 0 && frame.historyCount <= frame.rows.size)
            require(frame.screenStartRow == frame.historyCount)
            val ordinaryHistory = !frame.isAlternateScreen && !configuredTui && !preserveFullGrid
            val chunked = renderer == BenchmarkRenderer.CHUNKED_EAGER || renderer == BenchmarkRenderer.CHUNKED_LAYERS
            val chunks = if (chunked) terminalHistoryChunks(
                frame.historyCount, frame.historyStartSequence, usesTuiViewport = !ordinaryHistory,
            ) else emptyList()
            return TerminalBenchmarkLayout(
                historyRows = frame.historyCount,
                screenRows = frame.rows.size - frame.historyCount,
                useLazyHistory = renderer in listOf(BenchmarkRenderer.LAZY_HISTORY, BenchmarkRenderer.LAZY_INTRINSIC) &&
                    ordinaryHistory,
                historyChunks = chunks,
                isolateChunkDrawing = renderer == BenchmarkRenderer.CHUNKED_LAYERS && chunks.isNotEmpty(),
                useIntrinsicWidths = renderer == BenchmarkRenderer.LAZY_INTRINSIC && ordinaryHistory,
            )
        }
    }
}
