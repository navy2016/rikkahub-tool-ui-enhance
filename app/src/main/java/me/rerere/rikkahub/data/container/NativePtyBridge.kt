package me.rerere.rikkahub.data.container

/** JNI-backed PTY bridge. Falls back to script(1)+SIGWINCH when the native library is unavailable. */
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

    val isAvailable: Boolean get() = loadState.available
    val unavailableReason: String? get() = loadState.errorMessage

    fun backendSummary(): String = if (isAvailable) {
        "native-pty"
    } else {
        "script-sigwinch (${unavailableReason ?: "native library not bundled"})"
    }

    fun start(argv: List<String>, env: Map<String, String>, columns: Int, rows: Int): NativePtyProcess? {
        if (!isAvailable) return null
        val envArray = env.map { (key, value) -> "$key=$value" }.toTypedArray()
        val result = nativeStart(argv.toTypedArray(), envArray, columns, rows) ?: return null
        if (result.size < 2) return null
        return NativePtyProcess(result[0].toInt(), result[1].toInt())
    }

    fun read(fd: Int, buffer: ByteArray): Int = nativeRead(fd, buffer, buffer.size)
    fun write(fd: Int, buffer: ByteArray): Int = nativeWrite(fd, buffer, buffer.size)
    fun resize(fd: Int, columns: Int, rows: Int): Int = nativeResize(fd, columns, rows)
    fun waitFor(pid: Int): Int = nativeWait(pid)
    fun waitNoHang(pid: Int): Int? {
        val result = nativeWaitNoHang(pid)
        return when (result) {
            STILL_RUNNING -> null
            ALREADY_REAPED -> -1
            else -> result
        }
    }
    fun kill(pid: Int, signal: Int): Int = nativeKill(pid, signal)
    fun close(fd: Int) = nativeClose(fd)
    fun isProcessAlive(pid: Int): Boolean = nativeKill(pid, 0) == 0

    private external fun nativeStart(argv: Array<String>, env: Array<String>, columns: Int, rows: Int): LongArray?
    private external fun nativeRead(fd: Int, buffer: ByteArray, length: Int): Int
    private external fun nativeWrite(fd: Int, buffer: ByteArray, length: Int): Int
    private external fun nativeResize(fd: Int, columns: Int, rows: Int): Int
    private const val STILL_RUNNING = -100000
    private const val ALREADY_REAPED = -100001

    private external fun nativeWait(pid: Int): Int
    private external fun nativeWaitNoHang(pid: Int): Int
    private external fun nativeKill(pid: Int, signal: Int): Int
    private external fun nativeClose(fd: Int)
}
