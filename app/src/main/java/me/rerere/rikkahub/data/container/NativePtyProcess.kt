package me.rerere.rikkahub.data.container

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativePtyProcess internal constructor(
    private val childPid: Int,
    private val masterFd: Int
) : Process() {
    private val closed = AtomicBoolean(false)
    @Volatile private var cachedExitCode: Int? = null

    private val stdin = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed.get() || len <= 0) return
            if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
            var writtenTotal = 0
            while (writtenTotal < len && !closed.get()) {
                val chunk = b.copyOfRange(off + writtenTotal, off + len)
                val written = NativePtyBridge.write(masterFd, chunk)
                if (written <= 0) break
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
            val temp = if (off == 0 && len == b.size) b else ByteArray(len)
            val read = NativePtyBridge.read(masterFd, temp)
            if (read == 0) return -1
            if (read < 0) return -1
            if (temp !== b) temp.copyInto(b, off, 0, read)
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
        val code = cachedExitCode ?: NativePtyBridge.waitFor(childPid)
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
        val code = NativePtyBridge.waitNoHang(childPid)
            ?: throw IllegalThreadStateException("Process is still running")
        cachedExitCode = code
        return code
    }

    override fun destroy() {
        NativePtyBridge.kill(childPid, 15)
        closePty()
    }

    override fun destroyForcibly(): Process {
        NativePtyBridge.kill(childPid, 9)
        closePty()
        return this
    }

    override fun isAlive(): Boolean {
        if (cachedExitCode != null) return false
        NativePtyBridge.waitNoHang(childPid)?.let { code ->
            cachedExitCode = code
            return false
        }
        return NativePtyBridge.isProcessAlive(childPid)
    }

    fun resize(columns: Int, rows: Int) {
        NativePtyBridge.resize(masterFd, columns, rows)
    }

    fun pidOrNull(): Int = childPid

    private fun closePty() {
        if (closed.compareAndSet(false, true)) {
            NativePtyBridge.close(masterFd)
        }
    }
}
