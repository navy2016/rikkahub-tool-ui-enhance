package me.rerere.rikkahub.data.container

/** Default eager renderers plus an explicitly requested, compatibility-gated virtual history. */
internal enum class TerminalRenderMode(val id: String, val label: String, val shortLabel: String) {
    CHUNKED_LAYERS("chunkedLayers", "分块图层", "图层"),
    CHUNKED("chunkedEager", "轻量分块", "分块"),
    FLAT("eager", "逐行兼容", "逐行"),
    VIRTUAL_HISTORY("lazyHistory", "虚拟历史", "虚拟");

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
    preferred == TerminalRenderMode.VIRTUAL_HISTORY && hasHistoryChunks && virtualHistoryAllowed -> preferred
    preferred == TerminalRenderMode.VIRTUAL_HISTORY && hasHistoryChunks -> TerminalRenderMode.DEFAULT
    hasHistoryChunks -> preferred
    else -> TerminalRenderMode.FLAT
}
