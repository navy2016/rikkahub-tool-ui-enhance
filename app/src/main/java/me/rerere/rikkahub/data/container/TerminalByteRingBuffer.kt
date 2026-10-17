package me.rerere.rikkahub.data.container

import java.nio.charset.StandardCharsets

/**
 * Bounded raw-output tail, not the terminal's parsed scrollback. Reads retain absolute byte offsets
 * and decode EACH requested slice independently, including replacement characters at UTF-8 cuts.
 * All state and copies share this monitor; caller-owned inputs/results never alias retained bytes.
 * Storage is allocated on first non-empty append and never grows or shifts retained prefixes.
 */
internal class TerminalByteRingBuffer(private val capacity: Int) {
    init { require(capacity > 0) }

    data class Read(
        val content: String,
        val fromOffset: Long,
        val toOffset: Long,
        val totalBytes: Long,
        val baseOffset: Long,
        val hasMore: Boolean,
    )

    private var storage: ByteArray? = null
    private var head = 0
    private var retained = 0
    private var written = 0L

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Reject unreachable counter overflow before mutating any retained byte or metadata.
        val nextWritten = Math.addExact(written, bytes.size.toLong())
        val data = storage ?: ByteArray(capacity).also { storage = it }
        if (bytes.size >= capacity) {
            // Only the new suffix can survive. Never allocate/copy an oversize transient buffer.
            bytes.copyInto(data, 0, bytes.size - capacity, bytes.size)
            head = 0
            retained = capacity
        } else {
            val tail = advance(head, retained)
            val first = minOf(bytes.size, capacity - tail)
            bytes.copyInto(data, tail, 0, first)
            if (first < bytes.size) bytes.copyInto(data, 0, first, bytes.size)
            val dropped = (bytes.size - (capacity - retained)).coerceAtLeast(0)
            head = advance(head, dropped)
            retained += bytes.size - dropped
        }
        written = nextWritten
    }

    @Synchronized
    fun snapshot(): ByteArray = copyRange(0, retained)

    @Synchronized
    fun snapshotAsString(): String = copyRange(0, retained).toString(StandardCharsets.UTF_8)

    @Synchronized
    fun totalBytes(): Long = written

    @Synchronized
    fun readFrom(offset: Long, limitBytes: Int): Read {
        val base = written - retained
        val from = offset.coerceIn(base, written)
        val start = (from - base).toInt()
        val count = minOf(limitBytes.coerceIn(1, capacity), retained - start)
        val content = copyRange(start, count).toString(StandardCharsets.UTF_8)
        val end = from + count
        return Read(content, from, end, written, base, end < written)
    }

    /** index and count are in 0..capacity; avoid overflowing Int on index + count. */
    private fun advance(index: Int, count: Int): Int =
        if (count >= capacity - index) count - (capacity - index) else index + count

    private fun copyRange(start: Int, count: Int): ByteArray {
        val result = ByteArray(count)
        if (count == 0) return result
        val data = checkNotNull(storage)
        val physical = advance(head, start)
        val first = minOf(count, capacity - physical)
        data.copyInto(result, 0, physical, physical + first)
        if (first < count) data.copyInto(result, first, 0, count - first)
        return result
    }
}
