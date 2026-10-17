package me.rerere.rikkahub.data.container

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TerminalByteBufferWiringTest {
    private fun source(name: String): String = listOf(
        File("app/src/main/java/me/rerere/rikkahub/$name"),
        File("src/main/java/me/rerere/rikkahub/$name"),
    ).first { it.isFile }.readText()

    @Test fun byteRingWiringPreservesCapacityOffsetsAndTheSingleOrderedParserPath() {
        val manager = source("data/container/BackgroundProcessManager.kt")
        assertTrue(manager.contains("INTERACTIVE_BUFFER_MAX_BYTES = 256 * 1024"))
        assertTrue(manager.contains("val outputBuffer: TerminalByteRingBuffer"))
        assertTrue(manager.contains("val outputBuffer = TerminalByteRingBuffer(INTERACTIVE_BUFFER_MAX_BYTES)"))
        assertFalse(manager.contains("class SessionOutputBuffer"))
        val reader = manager.substringAfter("private suspend fun acceptTerminalOutput(")
            .substringBefore("private suspend fun hasScriptCommand(")
        assertTrue(reader.contains("record.outputMutex.withLock"))
        val append = reader.indexOf("record.outputBuffer.append(data)")
        val feed = reader.indexOf("record.terminalEmulator.feed(data)")
        val responses = reader.indexOf("handleTerminalProtocolEvents(record)")
        val notice = reader.indexOf("record.notifications.published(")
        val raw = reader.indexOf("record.outputFlow.emit(data)")
        assertTrue(append >= 0 && feed > append && responses > feed && notice > responses && raw > notice)
        val read = manager.substringAfter("fun readInteractiveOutput(").substringBefore("private fun BufferRead.withTerminalSnapshot")
        assertTrue(read.contains("val slice = buffer.readFrom(fromOffset, limitBytes)"))
        for (field in listOf("content", "fromOffset", "toOffset", "totalBytes", "baseOffset", "hasMore")) {
            assertTrue(read.contains("$field = slice.$field"))
        }
        assertTrue(read.contains("if (mode == \"new\")"))
        assertTrue(read.contains("interactiveReadOffsets[processId] = result.toOffset"))
        assertTrue(read.contains("return if (renderTerminal) result.withTerminalSnapshot(record) else result"))
        assertTrue(manager.contains("val allLines = session.outputBuffer.snapshotAsString().lines()"))
    }

    @Test fun byteRingWiringDoesNotReplaceParsedScrollbackOrTheCopySource() {
        val page = source("ui/pages/container/ProcessSessionPage.kt")
        val emulator = source("utils/TerminalEmulator.kt")
        assertTrue(page.contains("context.writeClipboardText(terminalEmulator.plainText(includeScrollback = true))"))
        assertTrue(emulator.contains("if (!alternateScreen && scrollTop == 0)"))
        assertFalse(page.contains("TerminalByteRingBuffer"))
        assertFalse(emulator.contains("TerminalByteRingBuffer"))
    }
}
