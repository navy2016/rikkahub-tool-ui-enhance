package me.rerere.rikkahub.data.container

/**
 * Thin feature probe for a future JNI-backed PTY implementation.
 *
 * Android's Java Process API cannot expose the PTY fd needed for ioctl(TIOCSWINSZ).
 * The current production backend therefore uses script(1)+SIGWINCH. This bridge is intentionally
 * conservative: it reports availability only if a bundled native library is present and loadable.
 */
object NativePtyBridge {
    private data class LoadState(
        val available: Boolean,
        val errorMessage: String? = null
    )

    private val loadState: LoadState by lazy {
        try {
            System.loadLibrary("rikkahub-pty")
            LoadState(available = true)
        } catch (e: Throwable) {
            LoadState(available = false, errorMessage = e.message)
        }
    }

    val isAvailable: Boolean
        get() = loadState.available

    val unavailableReason: String?
        get() = loadState.errorMessage

    fun backendSummary(): String {
        return if (isAvailable) {
            "native-pty"
        } else {
            "script-sigwinch (${unavailableReason ?: "native library not bundled"})"
        }
    }
}
