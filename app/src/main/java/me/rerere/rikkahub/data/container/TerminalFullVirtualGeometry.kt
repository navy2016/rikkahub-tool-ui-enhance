package me.rerere.rikkahub.data.container

/** Exact bounded physical-screen measurements. No history estimate or retained Text/UI is stored. */
internal class TerminalPhysicalRowHeights(heights: IntArray) {
    private val prefix = IntArray(heights.size + 1)
    init {
        require(heights.size in 1..me.rerere.rikkahub.utils.TerminalEmulator.MAX_ROWS && heights.all { it > 0 })
        heights.forEachIndexed { index, height -> prefix[index + 1] = Math.addExact(prefix[index], height) }
    }
    val size: Int get() = prefix.size - 1
    val height: Int get() = prefix.last()
    fun top(index: Int): Int = prefix[index]
    fun rowHeight(index: Int): Int = prefix[index + 1] - prefix[index]
    override fun equals(other: Any?): Boolean = other is TerminalPhysicalRowHeights && prefix.contentEquals(other.prefix)
    override fun hashCode(): Int = prefix.contentHashCode()
}

/** Present ONLY for the new full renderer; legacy history-only items keep their original behavior. */
internal data class TerminalFullVirtualPolicy(
    val screen: TerminalPhysicalRowHeights,
    val tui: Boolean,
    val preserveGrid: Boolean,
    val ime: Boolean,
    val avoidIme: Boolean,
    val retainedScreenBottom: Int? = null,
)
