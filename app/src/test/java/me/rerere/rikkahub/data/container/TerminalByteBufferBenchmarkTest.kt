package me.rerere.rikkahub.data.container

import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Same JVM AB/BA microbenchmark, not a PTY throughput, phone FPS or end-to-end latency claim. */
class TerminalByteBufferBenchmarkTest {
    companion object {
        private const val CAPACITY = 256 * 1024
        private const val CHUNK = 4096
        @Volatile private var blackhole = 0L
    }

    private interface Buffer {
        fun append(bytes: ByteArray)
        fun read(offset: Long, limit: Int): TerminalByteRingBuffer.Read
        fun snapshot(): ByteArray
    }

    private class Ring : Buffer {
        private val buffer = TerminalByteRingBuffer(CAPACITY)
        override fun append(bytes: ByteArray) = buffer.append(bytes)
        override fun read(offset: Long, limit: Int) = buffer.readFrom(offset, limit)
        override fun snapshot() = buffer.snapshot()
    }

    private class Legacy : Buffer {
        private val buffer = LegacyTerminalByteBuffer(CAPACITY)
        override fun append(bytes: ByteArray) = buffer.append(bytes)
        override fun read(offset: Long, limit: Int) = buffer.readFrom(offset, limit)
        override fun snapshot() = buffer.snapshot()
    }

    private class Allocations {
        // Reflection keeps HotSpot-only measurement types out of the Android compile classpath.
        private val type = Class.forName("com.sun.management.ThreadMXBean")
        private val bean = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
        private val read = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") private val id = Thread.currentThread().id
        init {
            check(type.isInstance(bean))
            check(type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
            if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true) {
                type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            }
        }
        fun bytes(): Long = (read.invoke(bean, id) as Long).also { check(it >= 0) }
    }

    private data class Result(val elapsedNanos: Long, val allocatedBytes: Long, val checksum: Long, val hash: String)

    private fun run(buffer: Buffer, scenario: String, iterations: Int, allocation: Allocations,
        seed: ByteArray, chunk: ByteArray): Result {
        buffer.append(seed)
        buffer.append(chunk) // Saturated and wrapped; construction/growth is outside steady-state timing.
        var checksum = 0L
        val allocated = allocation.bytes()
        val start = System.nanoTime()
        if (scenario == "saturatedAppend") {
            repeat(iterations) { buffer.append(chunk) }
        } else {
            repeat(iterations) { index ->
                val read = buffer.read(CHUNK + (index * 97 % (CAPACITY - 64)).toLong(), 64)
                checksum = java.lang.Long.rotateLeft(checksum, 1) xor read.content.hashCode().toLong() xor read.fromOffset
            }
        }
        val elapsed = System.nanoTime() - start
        val bytes = allocation.bytes() - allocated
        val snapshot = buffer.snapshot()
        assertEquals(CAPACITY, snapshot.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) }
        blackhole = checksum xor snapshot.contentHashCode().toLong()
        return Result(elapsed, bytes, checksum, hash)
    }

    private fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    @Test fun byteRingBenchmarkSameJvmAlternatingArmsKeepBytesAndMeasureAllocation() {
        val allocation = Allocations()
        val seed = ByteArray(CAPACITY) { (33 + it % 80).toByte() }
        val chunk = ByteArray(CHUNK) { (33 + it % 80).toByte() }
        val environment = listOf("java.version", "java.vm.name", "os.name", "os.arch")
            .joinToString(",") { quote(it) + ":" + quote(System.getProperty(it, "unknown")) }
        println("BUFFER_BENCH_ENV {\"suite\":\"terminal-byte-buffer-bench-v1\",\"sourceSha\":" +
            quote(System.getenv("GITHUB_SHA") ?: "non-ci") + ",\"runId\":" +
            quote(System.getenv("GITHUB_RUN_ID") ?: "non-ci") + ",\"environment\":{$environment}}")
        for (scenario in listOf("saturatedAppend", "smallRead")) {
            for (phase in listOf("warmup", "measurement")) {
                val pairs = if (phase == "warmup") 2 else 4
                val iterations = if (phase == "warmup") 64 else 256
                repeat(pairs) { pair ->
                    val order = if (pair % 2 == 0) listOf("legacy", "ring") else listOf("ring", "legacy")
                    val results = linkedMapOf<String, Result>()
                    order.forEachIndexed { position, arm ->
                        val result = run(if (arm == "legacy") Legacy() else Ring(), scenario, iterations, allocation, seed, chunk)
                        results[arm] = result
                        println("BUFFER_BENCH_SAMPLE {\"suite\":\"terminal-byte-buffer-bench-v1\"," +
                            "\"scenario\":\"$scenario\",\"phase\":\"$phase\",\"pair\":$pair," +
                            "\"arm\":\"$arm\",\"position\":$position,\"capacity\":$CAPACITY," +
                            "\"chunkBytes\":$CHUNK,\"readBytes\":64,\"iterations\":$iterations," +
                            "\"elapsedNanos\":${result.elapsedNanos},\"allocatedBytes\":${result.allocatedBytes}," +
                            "\"checksum\":${result.checksum},\"retainedBytes\":$CAPACITY,\"sha256\":\"${result.hash}\"}")
                    }
                    val legacy = results.getValue("legacy")
                    val ring = results.getValue("ring")
                    assertEquals(legacy.hash, ring.hash)
                    assertEquals(legacy.checksum, ring.checksum)
                    if (phase == "measurement") {
                        assertTrue("Ring must reduce measured allocation; timing is reported, not a pass threshold",
                            ring.allocatedBytes < legacy.allocatedBytes / 2)
                    }
                }
            }
        }
    }
}
