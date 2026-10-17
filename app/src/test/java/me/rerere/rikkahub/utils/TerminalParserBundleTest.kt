package me.rerere.rikkahub.utils

import java.security.MessageDigest
import kotlin.random.Random
import me.rerere.rikkahub.utils.parserreference.TerminalEmulator as ReferenceEmulator
import me.rerere.rikkahub.utils.parserreference.ownedRows as referenceOwnedRows
import org.junit.Assert.*
import org.junit.Test

/** Frozen post-protocol/pre-allocation implementation: semantic fixes are identical in both arms. */
class TerminalParserBundleTest {
    private fun compare(old: ReferenceEmulator, next: TerminalEmulator) {
        for (overlay in listOf(false, true)) {
            val a = if (overlay) old.renderFrameForCursorOverlay() else old.renderFrame()
            val b = if (overlay) next.renderFrameForCursorOverlay() else next.renderFrame()
            assertEquals(a.rows.map { it.text }, b.rows.map { it.text })
            assertEquals(a.rows.map { it.cursorOverlay?.toString() }, b.rows.map { it.cursorOverlay?.toString() })
            assertEquals(a.contentBounds.toString(), b.contentBounds.toString())
            assertEquals(a.screenContentBounds.toString(), b.screenContentBounds.toString())
            assertEquals(a.historyLineIds, b.historyLineIds)
            assertEquals(a.historyStartSequence, b.historyStartSequence)
            assertEquals(a.historyGeneration, b.historyGeneration)
            assertEquals(a.historyRenderRevision, b.historyRenderRevision)
            assertEquals(a.screenLineIds, b.screenLineIds)
            assertEquals(a.screenGeneration, b.screenGeneration)
            assertEquals(a.cursorRow, b.cursorRow)
            assertEquals(a.cursorVisible, b.cursorVisible)
            assertEquals(a.isAlternateScreen, b.isAlternateScreen)
            assertEquals(a.modeSummary, b.modeSummary)
            assertEquals(a.revision, b.revision)
            assertEquals(a.referenceOwnedRows()!!.nextLineId, b.ownedRows()!!.nextLineId)
        }
        assertEquals(old.plainText(), next.plainText())
        assertEquals(old.cursorColumn(), next.cursorColumn())
        assertEquals(old.title, next.title)
        assertEquals(old.workingDirectoryUri, next.workingDirectoryUri)
        assertEquals(old.pendingResponsesView(), next.pendingResponsesView())
        assertEquals(old.pendingClipboardRequestsView(), next.pendingClipboardRequestsView())
    }

    @Test fun parserEveryUtf8BoundaryAndMalformedSequenceMatchesFrozenReference() {
        val stream = ("ASCII\r\n中e\u0301👩‍💻🇨🇳\u001b[31mred\u001b[0m\u001b[6n").toByteArray() +
            byteArrayOf(0xF0.toByte(), 0x28, 0x8C.toByte(), 0x28, 0xC0.toByte(), 0xAF.toByte())
        for (split in 0..stream.size) {
            val a = ReferenceEmulator(40, 8, 37)
            val b = TerminalEmulator(40, 8, 37)
            for (chunk in listOf(stream.copyOfRange(0, split), stream.copyOfRange(split, stream.size))) {
                a.feed(chunk); b.feed(chunk); compare(a, b)
            }
        }
    }

    @Test fun parserScratchOverflowsAndResetWithinChunkKeepOneRevisionAndFullText() {
        for (prefix in listOf(4093, 4094, 4095, 4096, 8191, 16385)) {
            val bytes = ("a".repeat(prefix) + "👩‍💻\u001bc" + "中e\u0301\r\n".repeat(1900) + "\u001b[6n").toByteArray()
            val a = ReferenceEmulator(40, 8, 64)
            val b = TerminalEmulator(40, 8, 64)
            a.feed(bytes); b.feed(bytes); compare(a, b)
        }
    }

    @Test fun parserRandomMutationsResizeHistoryAndStylesMatchAtEveryPublication() {
        val random = Random(823771)
        val a = ReferenceEmulator(40, 8, 64)
        val b = TerminalEmulator(40, 8, 64)
        val operations = listOf("hello", "中 e\u0301 👩‍💻", "\r\n", "\u001b[31;1m", "\u001b[0m", "\u001b[2J",
            "\u001b[3J", "\u001b[1;1H", "\u001b[2L", "\u001b[3M", "\u001b[5S", "\u001b[6T", "\u001b[2K",
            "\u001b[1@", "\u001b[2P", "\u001b[3X", "\u001b[?1049h", "\u001b[?1049l", "\u001b[?25l",
            "\u001b[?25h", "\u001b[5 q", "\u001b[2 q", "\u001b[6n", "\u001b[>4;2m", "\u001b[?4m",
            "\u001b[>7u\u001b[?u\u001b[c", "\u001b]8;;https://example.test\u0007URL\u001b]8;;\u0007",
            "\u001b]52;c;ZGF0YQ==\u0007", "\u001b[65;1;2;4;9\$x", "\u001b[1;1;2;4;1;3;2;1\$v")
        repeat(300) { step ->
            val bytes = operations[random.nextInt(operations.size)].toByteArray()
            val split = random.nextInt(bytes.size + 1)
            for (chunk in listOf(bytes.copyOfRange(0, split), bytes.copyOfRange(split, bytes.size))) {
                a.feed(chunk); b.feed(chunk)
            }
            if (step % 19 == 0) { a.resize(30 + step % 20, 6 + step % 7); b.resize(30 + step % 20, 6 + step % 7) }
            if (step % 43 == 0) { a.clearScrollbackOnly(); b.clearScrollbackOnly() }
            if (step % 79 == 0) { a.softReset(); b.softReset() }
            if (step % 101 == 0) { a.reset(); b.reset() }
            compare(a, b)
            assertEquals(a.drainResponses(), b.drainResponses())
            assertEquals(a.drainClipboardRequests(), b.drainClipboardRequests())
        }
    }

    @Test fun parserOldPublishedFramesCannotBeMutatedBySharedCells() {
        val next = TerminalEmulator(40, 8, 64)
        next.feed((0..90).joinToString("\r\n") { "original-$it" })
        val frame = next.renderFrame()
        val texts = frame.rows.map { it.text }
        repeat(40) { next.feed("\u001b[H\u001b[2Kreplaced-$it\r\n"); next.resize(40 + it % 2, 8) }
        assertEquals(texts, frame.rows.map { it.text })
    }

    private class Allocations {
        private val type = Class.forName("com.sun.management.ThreadMXBean")
        private val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        private val read = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") private val thread = Thread.currentThread().id
        init {
            check(type.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
            if (type.getMethod("isThreadAllocatedMemoryEnabled").invoke(bean) != true)
                type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        }
        fun bytes() = (read.invoke(bean, thread) as Long).also { check(it >= 0) }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test fun parserBenchmarkKeepsEveryAlternatingSampleAndReportsRealAllocations() {
        val allocation = Allocations()
        val env = listOf("java.version", "java.vm.name", "os.name", "os.arch").joinToString(",") {
            quote(it) + ":" + quote(System.getProperty(it, "unknown"))
        }
        println("PARSER_ENV {\"sourceSha\":${quote(System.getenv("GITHUB_SHA") ?: "non-ci")}," +
            "\"runId\":${quote(System.getenv("GITHUB_RUN_ID") ?: "non-ci")},\"environment\":{$env}}")
        val workloads = linkedMapOf(
            "ascii" to ("row:" + "abc123 ".repeat(9) + "\r\n").repeat(48),
            "unicode" to ("中文 e\u0301 👩‍💻 🇨🇳\r\n").repeat(100),
            "redraw" to ("\u001b[H\u001b[2J\u001b[31;1mstatus\u001b[0m\r\n" + "x".repeat(64)).repeat(32),
        )
        for ((scenario, text) in workloads) {
            val chunk = text.toByteArray()
            for (phase in listOf("warmup", "measurement")) repeat(if (phase == "warmup") 2 else 4) { pair ->
                val iterations = if (phase == "warmup") 24 else 96
                val samples = linkedMapOf<String, Pair<Long, String>>()
                val order = if (pair % 2 == 0) listOf("reference", "candidate") else listOf("candidate", "reference")
                for ((position, arm) in order.withIndex()) {
                    val old = if (arm == "reference") ReferenceEmulator(80, 24, 128) else null
                    val next = if (old == null) TerminalEmulator(80, 24, 128) else null
                    // Initialization and decoder warmup are outside steady-state measurement.
                    old?.feed(chunk) ?: next!!.feed(chunk)
                    val before = allocation.bytes()
                    val start = System.nanoTime()
                    if (old != null) repeat(iterations) { old.feed(chunk) } else repeat(iterations) { next!!.feed(chunk) }
                    val elapsed = System.nanoTime() - start
                    val bytes = allocation.bytes() - before
                    val plain = old?.plainText() ?: next!!.plainText()
                    val hash = MessageDigest.getInstance("SHA-256").digest(plain.toByteArray()).joinToString("") { "%02x".format(it) }
                    val revision = old?.revision() ?: next!!.revision()
                    val history = old?.renderFrame()?.historyCount ?: next!!.renderFrame().historyCount
                    samples[arm] = bytes to hash
                    println("PARSER_SAMPLE {\"scenario\":\"$scenario\",\"phase\":\"$phase\",\"pair\":$pair," +
                        "\"arm\":\"$arm\",\"position\":$position,\"iterations\":$iterations,\"chunkBytes\":${chunk.size}," +
                        "\"elapsedNanos\":$elapsed,\"allocatedBytes\":$bytes,\"revision\":$revision," +
                        "\"historyRows\":$history,\"sha256\":\"$hash\"}")
                }
                assertEquals(samples.getValue("reference").second, samples.getValue("candidate").second)
                if (phase == "measurement") assertTrue("Allocation must improve; time is never filtered",
                    samples.getValue("candidate").first < samples.getValue("reference").first)
            }
        }
    }
}
