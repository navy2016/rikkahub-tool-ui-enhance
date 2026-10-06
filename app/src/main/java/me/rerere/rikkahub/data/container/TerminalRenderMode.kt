package me.rerere.rikkahub.data.container

/** Default eager renderers plus an explicitly requested, compatibility-gated virtual history. */
internal enum class TerminalRenderMode(val id: String, val label: String, val shortLabel: String) {
    CHUNKED_LAYERS("chunkedLayers", "分块图层", "图层"),
    CHUNKED("chunkedEager", "轻量分块", "分块"),
    FLAT("eager", "逐行兼容", "逐行"),
    VIRTUAL_HISTORY("lazyHistory", "虚拟历史", "虚拟"),
    VIRTUAL_HISTORY_IME("lazyHistoryIme", "虚拟历史·键盘稳定", "稳虚拟");

    val isVirtualHistory: Boolean get() = this == VIRTUAL_HISTORY || this == VIRTUAL_HISTORY_IME

    companion object {
        val DEFAULT = CHUNKED_LAYERS

        /** Missing, corrupt or future IDs never opt a user into a different renderer. */
        fun fromId(id: String?): TerminalRenderMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Grid/empty/unsafe-history fallback does not overwrite the user's per-command preference. */
internal fun effectiveTerminalRenderMode(
    preferred: TerminalRenderMode,
    hasHistoryChunks: Boolean,
    virtualHistoryAllowed: Boolean = false,
): TerminalRenderMode = when {
    preferred.isVirtualHistory && hasHistoryChunks && virtualHistoryAllowed -> preferred
    preferred.isVirtualHistory && hasHistoryChunks -> TerminalRenderMode.DEFAULT
    hasHistoryChunks -> preferred
    else -> TerminalRenderMode.FLAT
}
