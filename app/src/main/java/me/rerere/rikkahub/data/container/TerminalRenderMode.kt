package me.rerere.rikkahub.data.container

/** User-facing eager renderers with identical Text, scroll, selection and input semantics. */
internal enum class TerminalRenderMode(val id: String, val label: String, val shortLabel: String) {
    CHUNKED_LAYERS("chunkedLayers", "分块图层", "图层"),
    CHUNKED("chunkedEager", "轻量分块", "分块"),
    FLAT("eager", "逐行兼容", "逐行");

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
): TerminalRenderMode = if (hasHistoryChunks) preferred else TerminalRenderMode.FLAT
