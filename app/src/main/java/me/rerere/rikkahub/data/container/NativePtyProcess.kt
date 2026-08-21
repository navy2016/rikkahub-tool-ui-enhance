package me.rerere.rikkahub.data.container

import java.io.InputStream
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativePtyProcess internal constructor(
    private val childPid: Int,
    private val masterFd: Int
) : Process() {
    companion object {
        private const val TAG = "NativePtyProcess"
    }

    private val closed = AtomicBoolean(false)
    private val readErrorLogged = AtomicBoolean(false)
    private val writeErrorLogged = AtomicBoolean(false)
    @Volatile private var cachedExitCode: Int? = null
    private val waitStarted = AtomicBoolean(false)

    private val stdin = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get() || len <= 0) return
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            var writtenTotal = 0
            while (writtenTotal < len && !closed.get()) {
                val written = NativePtyBridge.write(masterFd, b, off + writtenTotal, len - writtenTotal)
                if (written < 0) {
                    logWriteError(written)
                    throw IOException("PTY write failed: ${NativePtyBridge.describeResult(written)}")
                }
                if (written == 0) throw IOException("PTY write returned 0 bytes")
                writtenTotal += written
            }
        }

        override fun close() {
            this@NativePtyProcess.closePty()
        }
    }

    private val stdout = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            if (closed.get()) return -1
            if (len <= 0) return 0
            val read = NativePtyBridge.read(masterFd, b, off, len)
            if (read == 0) return -1
            if (read < 0) {
                logReadError(read)
                return -1
            }
            return read
        }

        override fun close() {
            this@NativePtyProcess.closePty()
        }
    }

    private val stderr = object : InputStream() {
        override fun read(): Int = -1
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        cachedExitCode?.let { return it }
        waitStarted.set(true)
        val code = NativePtyBridge.waitFor(childPid)
        cachedExitCode = code
        return code
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (!isAlive) {
                try { cachedExitCode = exitValue() } catch (_: Exception) {}
                closePty()
                return true
            }
            Thread.sleep(30)
        }
        return false
    }

    override fun exitValue(): Int {
        cachedExitCode?.let { return it }
        if (!waitStarted.compareAndSet(false, true)) {
            throw IllegalThreadStateException("Process exit is being awaited")
        }
        val code = NativePtyBridge.waitNoHang(childPid)
            ?: run {
                waitStarted.set(false)
                throw IllegalThreadStateException("Process is still running")
            }
        cachedExitCode = code
        return code
    }

    override fun destroy() {
        NativePtyBridge.kill(childPid, 15)
        Thread {
            try {
                if (!waitFor(800, TimeUnit.MILLISECONDS)) closePty()
            } catch (_: Exception) {
                closePty()
            }
        }.apply {
            name = "NativePtyProcess-destroy-$childPid"
            isDaemon = true
            start()
        }
    }

    override fun destroyForcibly(): Process {
        NativePtyBridge.kill(childPid, 9)
        Thread {
            try {
                waitFor(300, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            } finally {
                closePty()
            }
        }.apply {
            name = "NativePtyProcess-kill-$childPid"
            isDaemon = true
            start()
        }
        return this
    }

    override fun isAlive(): Boolean {
        if (cachedExitCode != null) return false
        if (!waitStarted.compareAndSet(false, true)) return NativePtyBridge.isProcessAlive(childPid)
        val code = NativePtyBridge.waitNoHang(childPid)
        if (code != null) {
            cachedExitCode = code
            return false
        }
        waitStarted.set(false)
        return NativePtyBridge.isProcessAlive(childPid)
    }

    /** @return true when the kernel winsize was applied, false on ioctl failure. */
    fun resize(columns: Int, rows: Int): Boolean = NativePtyBridge.resize(masterFd, columns, rows) == 0

    fun pidOrNull(): Int = childPid

    fun drainAvailable(maxBytes: Int = 8192): ByteArray {
        if (closed.get()) return ByteArray(0)
        return NativePtyBridge.drain(masterFd, maxBytes)
    }

    private fun logReadError(result: Int) {
        if (readErrorLogged.compareAndSet(false, true)) {
            Log.w(TAG, "PTY read failed: ${NativePtyBridge.describeResult(result)}")
        }
    }

    private fun logWriteError(result: Int) {
        if (writeErrorLogged.compareAndSet(false, true)) {
            Log.w(TAG, "PTY write failed: ${NativePtyBridge.describeResult(result)}")
        }
    }

    private fun closePty() {
        if (closed.compareAndSet(false, true)) {
            NativePtyBridge.close(masterFd)
        }
    }
}
