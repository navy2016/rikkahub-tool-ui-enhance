package me.rerere.rikkahub.data.container

import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TerminalByteRingBufferTest {
    private fun compare(ring: TerminalByteRingBuffer, old: LegacyTerminalByteBuffer, capacity: Int) {
        assertEquals(old.totalBytes(), ring.totalBytes())
        assertArrayEquals(old.snapshot(), ring.snapshot())
        assertEquals(old.snapshotAsString(), ring.snapshotAsString())
        val total = old.totalBytes()
        val base = (total - capacity).coerceAtLeast(0)
        for (offset in listOf(Long.MIN_VALUE, -1L, 0L, base - 1, base, base + 1, total / 2, total, Long.MAX_VALUE)) {
            for (limit in listOf(Int.MIN_VALUE, 0, 1, 3, capacity, Int.MAX_VALUE)) {
                assertEquals("offset=$offset limit=$limit", old.readFrom(offset, limit), ring.readFrom(offset, limit))
            }
        }
    }

    @Test fun byteRingRejectsNonPositiveCapacityAndDoesNotAllocateBeforeData() {
        assertThrows(IllegalArgumentException::class.java) { TerminalByteRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { TerminalByteRingBuffer(-1) }
        val ring = TerminalByteRingBuffer(7)
        val field = TerminalByteRingBuffer::class.java.getDeclaredField("storage").apply { isAccessible = true }
        assertNull(field.get(ring))
        ring.append(byteArrayOf())
        assertNull(field.get(ring))
        compare(ring, LegacyTerminalByteBuffer(7), 7)
    }

    @Test fun byteRingCapacityOneKeepsOnlyTheLastByteAndAbsoluteOffsets() {
        val ring = TerminalByteRingBuffer(1)
        val old = LegacyTerminalByteBuffer(1)
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf(4))) {
            ring.append(bytes)
            old.append(bytes)
            compare(ring, old, 1)
        }
        assertEquals(3L, ring.readFrom(0, 1).baseOffset)
        assertArrayEquals(byteArrayOf(4), ring.snapshot())
    }

    @Test fun byteRingExactFillPartialWrapAndRepeatedWrapMatchFrozenReference() {
        val ring = TerminalByteRingBuffer(7)
        val old = LegacyTerminalByteBuffer(7)
        val writes = listOf("abc", "defg", "hi", "jklm", "no", "pqrstuv", "w", "")
        for (text in writes) {
            ring.append(text.toByteArray())
            old.append(text.toByteArray())
            compare(ring, old, 7)
        }
        assertEquals("qrstuvw", ring.snapshotAsString())
    }

    @Test fun byteRingOversizeAppendKeepsExactSuffixWithoutOldBytesOrGrowingStorage() {
        val ring = TerminalByteRingBuffer(11)
        val old = LegacyTerminalByteBuffer(11)
        val field = TerminalByteRingBuffer::class.java.getDeclaredField("storage").apply { isAccessible = true }
        ring.append(byteArrayOf(3))
        old.append(byteArrayOf(3))
        val backing = field.get(ring)
        for (size in listOf(35, 2, 11, 12, 5000, 1)) {
            val bytes = ByteArray(size) { (it * 19 + size).toByte() }
            ring.append(bytes)
            old.append(bytes)
            assertSame(backing, field.get(ring))
            assertEquals(11, (field.get(ring) as ByteArray).size)
            compare(ring, old, 11)
        }
    }

    @Test fun byteRingInputAndReturnedSnapshotsCannotMutateRetainedData() {
        val ring = TerminalByteRingBuffer(7)
        val bytes = "abcdefghi".toByteArray()
        ring.append(bytes)
        bytes.fill(0)
        val snapshot = ring.snapshot()
        val before = ring.readFrom(2, 7)
        snapshot.fill(1)
        ring.append("jkl".toByteArray())
        assertEquals("cdefghi", before.content)
        assertEquals("fghijkl", ring.snapshotAsString())
    }

    @Test fun byteRingUtf8CutsAnsiNulAndMalformedBytesKeepIndependentSliceDecoding() {
        val bytes = "中🙂e\u0301\u001b[31m终\u001b[0m\u0000\r\n".toByteArray() +
            byteArrayOf(0xC3.toByte(), 0x28, 0xF0.toByte(), 0x80.toByte(), 0x80.toByte())
        for (capacity in 1..bytes.size) {
            val ring = TerminalByteRingBuffer(capacity)
            val old = LegacyTerminalByteBuffer(capacity)
            for (b in bytes) {
                ring.append(byteArrayOf(b))
                old.append(byteArrayOf(b))
            }
            compare(ring, old, capacity)
            for (start in 0..bytes.size) for (length in 1..5) {
                assertEquals(old.readFrom(start.toLong(), length), ring.readFrom(start.toLong(), length))
            }
        }
    }

    @Test fun byteRingRandomizedChunksOffsetsAndLimitsMatchReferenceAcrossCapacities() {
        val random = Random(7812963L)
        for (capacity in listOf(1, 2, 3, 7, 32, 63, 64, 127, 256, 1025, 4096)) {
            val ring = TerminalByteRingBuffer(capacity)
            val old = LegacyTerminalByteBuffer(capacity)
            repeat(400) { iteration ->
                val size = if (iteration % 11 == 0) capacity + random.nextInt(capacity + 1)
                    else random.nextInt(minOf(capacity + 1, 129))
                val bytes = ByteArray(size).also(random::nextBytes)
                ring.append(bytes)
                old.append(bytes)
                val offset = old.totalBytes() - random.nextInt(capacity * 2 + 1)
                val limit = random.nextInt(capacity + 11) - 5
                assertEquals(old.readFrom(offset, limit), ring.readFrom(offset, limit))
                if (iteration % 37 == 0) compare(ring, old, capacity)
            }
            compare(ring, old, capacity)
        }
    }

    @Test fun byteRingReadCursorResumesAfterWrapAndClampsExpiredFutureAndInvalidLimits() {
        val ring = TerminalByteRingBuffer(5)
        ring.append("abcd".toByteArray())
        val first = ring.readFrom(0, 2)
        assertEquals(TerminalByteRingBuffer.Read("ab", 0, 2, 4, 0, true), first)
        ring.append("efgh".toByteArray())
        val resumed = ring.readFrom(first.toOffset, 2)
        assertEquals(TerminalByteRingBuffer.Read("de", 3, 5, 8, 3, true), resumed)
        val tail = ring.readFrom(resumed.toOffset, Int.MAX_VALUE)
        assertEquals(TerminalByteRingBuffer.Read("fgh", 5, 8, 8, 3, false), tail)
        assertEquals("d", ring.readFrom(Long.MIN_VALUE, Int.MIN_VALUE).content)
        assertEquals(TerminalByteRingBuffer.Read("", 8, 8, 8, 3, false), ring.readFrom(Long.MAX_VALUE, 99))
    }

    @Test fun byteRingLongOffsetsNeverNarrowToIntUntilInsideRetainedRange() {
        val ring = TerminalByteRingBuffer(8)
        ring.append("abcdefgh".toByteArray())
        val counter = TerminalByteRingBuffer::class.java.getDeclaredField("written").apply { isAccessible = true }
        val total = Int.MAX_VALUE.toLong() + 900
        counter.setLong(ring, total)
        ring.append("ij".toByteArray())
        assertEquals(TerminalByteRingBuffer.Read("efg", total - 4, total - 1, total + 2, total - 6, true),
            ring.readFrom(total - 4, 3))
    }

    @Test fun byteRingOverflowRejectsWriteWithoutChangingBytesOrMetadata() {
        val ring = TerminalByteRingBuffer(8)
        ring.append("abcdefgh".toByteArray())
        val counter = TerminalByteRingBuffer::class.java.getDeclaredField("written").apply { isAccessible = true }
        counter.setLong(ring, Long.MAX_VALUE - 1)
        val before = ring.readFrom(0, 8)
        assertThrows(ArithmeticException::class.java) { ring.append("xy".toByteArray()) }
        assertEquals(before, ring.readFrom(0, 8))
        assertEquals(Long.MAX_VALUE - 1, ring.totalBytes())
    }

    @Test fun byteRingConcurrentReaderSeesAnAtomicOffsetAndByteWindow() {
        val ring = TerminalByteRingBuffer(1025)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val firstRead = CountDownLatch(1)
        val middleRead = CountDownLatch(1)
        val done = AtomicBoolean(false)
        val observed = AtomicInteger()
        fun byteAt(index: Long) = (33 + index % 80).toByte()
        try {
            val reader = pool.submit {
                check(start.await(3, TimeUnit.SECONDS))
                do {
                    val read = ring.readFrom(0, Int.MAX_VALUE)
                    assertEquals((read.totalBytes - 1025).coerceAtLeast(0), read.baseOffset)
                    assertEquals(read.baseOffset, read.fromOffset)
                    assertEquals(read.totalBytes, read.toOffset)
                    val expected = ByteArray((read.toOffset - read.fromOffset).toInt()) { byteAt(read.fromOffset + it) }
                    assertEquals(expected.toString(Charsets.UTF_8), read.content)
                    observed.incrementAndGet()
                    firstRead.countDown()
                    if (read.totalBytes == 17000L) middleRead.countDown()
                } while (!done.get())
            }
            val writer = pool.submit {
                start.countDown()
                check(firstRead.await(3, TimeUnit.SECONDS))
                try {
                    repeat(5000) { n ->
                        ring.append(ByteArray(17) { byteAt(n * 17L + it) })
                        if (n == 999) check(middleRead.await(3, TimeUnit.SECONDS))
                    }
                }
                finally { done.set(true) }
            }
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
            assertTrue(observed.get() > 1)
            assertEquals(85000L, ring.totalBytes())
        } finally { done.set(true); start.countDown(); firstRead.countDown(); middleRead.countDown(); pool.shutdownNow() }
    }

    @Test fun byteRingConcurrentProducersKeepEveryCompletedChunkWhenCapacityAllows() {
        val ring = TerminalByteRingBuffer(4 * 1000 * 8)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (0 until 4).map { worker -> pool.submit {
                check(start.await(3, TimeUnit.SECONDS))
                repeat(1000) { ring.append(ByteArray(8) { (65 + worker).toByte() }) }
            } }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val bytes = ring.snapshot()
            assertEquals(32000L, ring.totalBytes())
            assertEquals(32000, bytes.size)
            for (index in bytes.indices step 8) assertTrue((0 until 8).all { bytes[index + it] == bytes[index] })
            for (worker in 0 until 4) assertEquals(8000, bytes.count { it == (65 + worker).toByte() })
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
