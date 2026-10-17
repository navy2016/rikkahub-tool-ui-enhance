package me.rerere.rikkahub.data.container

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Frozen 6e81739 SessionOutputBuffer algorithm; result type only adapted for the differential oracle. */
internal class LegacyTerminalByteBuffer(private val maxBytes: Int) {
    private val data = ByteArrayOutputStream()
    private var baseOffset: Long = 0L
    private var totalWritten: Long = 0L

    @Synchronized
    fun append(bytes: ByteArray) {
        data.write(bytes)
        totalWritten += bytes.size
        if (data.size() > maxBytes) {
            val current = data.toByteArray()
            val drop = current.size - maxBytes
            val trimmed = current.copyOfRange(drop, current.size)
            data.reset()
            data.write(trimmed)
            baseOffset += drop.toLong()
        }
    }

    @Synchronized fun snapshot(): ByteArray = data.toByteArray()
    @Synchronized fun snapshotAsString(): String = data.toByteArray().toString(Charsets.UTF_8)
    @Synchronized fun totalBytes(): Long = totalWritten

    @Synchronized
    fun readFrom(offset: Long, limitBytes: Int): TerminalByteRingBuffer.Read {
        val safeLimit = limitBytes.coerceIn(1, maxBytes)
        val effectiveOffset = offset.coerceAtLeast(baseOffset).coerceAtMost(totalWritten)
        val localStart = (effectiveOffset - baseOffset).toInt()
        val bytes = data.toByteArray()
        val end = minOf(localStart + safeLimit, bytes.size)
        val slice = if (localStart < end) bytes.copyOfRange(localStart, end) else ByteArray(0)
        val toOffset = baseOffset + end
        return TerminalByteRingBuffer.Read(
            content = slice.toString(StandardCharsets.UTF_8),
            fromOffset = effectiveOffset,
            toOffset = toOffset,
            totalBytes = totalWritten,
            baseOffset = baseOffset,
            hasMore = toOffset < totalWritten
        )
    }
}
