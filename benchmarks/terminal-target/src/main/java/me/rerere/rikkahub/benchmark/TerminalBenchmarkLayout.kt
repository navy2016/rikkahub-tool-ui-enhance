package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.utils.TerminalEmulator

internal enum class BenchmarkRenderer(val wireName: String) {
    EAGER("eager"),
    LAZY_HISTORY("lazyHistory");

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
) {
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
            return TerminalBenchmarkLayout(
                historyRows = frame.historyCount,
                screenRows = frame.rows.size - frame.historyCount,
                useLazyHistory = renderer == BenchmarkRenderer.LAZY_HISTORY &&
                    !frame.isAlternateScreen && !configuredTui && !preserveFullGrid,
            )
        }
    }
}
