package me.rerere.rikkahub.data.container

data class TerminalViewportState(
    val verticalOffsetPx: Int = 0,
    val horizontalOffsetPx: Int = 0,
    val autoScroll: Boolean = true,
    val atBottom: Boolean = true,
    val viewportMode: ViewportMode = ViewportMode.TAIL,
    val anchorLineId: Long? = null,
    val anchorClippedTopPx: Int = 0,
    val anchorCellHeightPx: Int = 0,
    val anchorScreenGeneration: Long? = null,
    val anchorHistoryGeneration: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
