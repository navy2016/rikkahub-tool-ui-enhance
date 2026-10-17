package me.rerere.rikkahub.pipeline

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Exercise the production manager, actual PTY and read/log APIs after repeated raw-tail eviction. */
class TerminalByteBufferInstrumentedTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(180)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Byte-buffer completion timed out: $label" }
            SystemClock.sleep(10)
        }
    }

    @Test fun byteBufferNativePtyWrapReadModesUtf8AndExitTail() {
        runBlocking { proot.start().getOrThrow() }
        val capacity = 256 * 1024
        val rows = 7000
        val script = """
            stty -echo -onlcr
            printf 'BUFFER_READY\r\n'
            round=0
            while IFS= read -r command; do
              case "${'$'}command" in
                WRITE)
                  i=0
                  while [ "${'$'}i" -lt 7000 ]; do
                    printf '%d:%05d:abcdefghijklmnopqrstuvwxyz0123456789\033[32m中文🙂\033[0m\r\n' "${'$'}round" "${'$'}i"
                    i=${'$'}((i+1))
                  done
                  printf 'BUFFER_DONE:%d\r\n' "${'$'}round"
                  round=${'$'}((round+1))
                  ;;
                QUIT) exit 0 ;;
              esac
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        val started = runBlocking {
            manager.startInteractiveSession("byte-buffer-check", command,
                columns = 80, rows = 24, ptyMode = PtyMode.COOKED)
        }
        assertTrue(started.message, started.success)
        val id = started.processId
        var all = "BUFFER_READY\r\n".toByteArray(StandardCharsets.UTF_8)
        var implicitOffset = 0L
        var readChecks = 0
        fun checkRead(mode: String, limit: Int, offset: Long? = null) {
            val from = when (mode) {
                "all" -> 0L
                "tail" -> (all.size.toLong() - limit).coerceAtLeast(0)
                else -> offset ?: implicitOffset
            }
            val base = (all.size - capacity).coerceAtLeast(0).toLong()
            val begin = from.coerceIn(base, all.size.toLong())
            val end = minOf(begin + limit.coerceIn(1, capacity), all.size.toLong())
            val result = requireNotNull(manager.readInteractiveOutput(id, mode, offset, limit))
            assertEquals(all.copyOfRange(begin.toInt(), end.toInt()).toString(StandardCharsets.UTF_8), result.content)
            assertEquals(begin, result.fromOffset)
            assertEquals(end, result.toOffset)
            assertEquals(base, result.baseOffset)
            assertEquals(all.size.toLong(), result.totalBytes)
            assertEquals(end < all.size, result.hasMore)
            if (mode == "new") implicitOffset = end
            readChecks++
        }
        try {
            await("ready") { manager.readInteractiveBuffer(id) == "BUFFER_READY\r\n" }
            assertEquals("native-pty", manager.getProcess(id)?.terminalBackend)
            checkRead("new", 5)
            repeat(3) { round ->
                val payload = buildString {
                    repeat(rows) { index ->
                        append("%d:%05d:abcdefghijklmnopqrstuvwxyz0123456789\u001b[32m中文🙂\u001b[0m\r\n"
                            .format(Locale.ROOT, round, index))
                    }
                    append("BUFFER_DONE:$round\r\n")
                }.toByteArray(StandardCharsets.UTF_8)
                assertTrue(payload.size > capacity)
                all += payload
                runBlocking { manager.sendInput(id, "WRITE", appendNewline = true).getOrThrow() }
                await("round $round parsed") {
                    val bytes = requireNotNull(manager.observeTerminalUpdates(id)).value.totalBytes
                    check(bytes <= all.size) { "Unexpected PTY bytes in round $round: $bytes/${all.size}" }
                    bytes == all.size.toLong()
                }
                val tail = all.copyOfRange(all.size - capacity, all.size)
                assertEquals(tail.toString(StandardCharsets.UTF_8), manager.readInteractiveBuffer(id))
                val base = (all.size - capacity).toLong()
                checkRead("all", Int.MAX_VALUE)
                for (limit in listOf(1, 2, 3, 64, 4097, capacity, capacity + 19)) checkRead("tail", limit)
                for (offset in listOf(0L, base - 1, base + 1, base + 9, base + 55, all.size.toLong(), Long.MAX_VALUE)) {
                    for (limit in listOf(0, 1, 2, 7, 63)) checkRead("new", limit, offset)
                }
                checkRead("new", 37, base + 2)
                checkRead("new", 47)
                val expectedLines = tail.toString(StandardCharsets.UTF_8).lines()
                val logs = runBlocking { manager.readProcessLogs(id, offset = 3, limit = 7) }
                assertEquals(expectedLines.subList(3, 10), logs.lines)
                assertEquals(expectedLines.size, logs.totalLines)
                assertEquals(10 < expectedLines.size, logs.hasMore)
                val terminal = requireNotNull(manager.getInteractiveTerminalEmulator(id))
                assertFalse(terminal.isAlternateScreen)
                assertTrue(terminal.plainText().contains("BUFFER_DONE:$round"))
                val sample = JSONObject().put("suite", "terminal-byte-buffer-pty-v1").put("round", round)
                    .put("capacity", capacity).put("payloadBytes", payload.size).put("totalBytes", all.size)
                    .put("baseOffset", base).put("retainedBytes", tail.size).put("readChecks", readChecks)
                    .put("backend", "native-pty").put("historyRows", terminal.renderFrame().historyCount)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(tail)
                        .joinToString("") { "%02x".format(it) })
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nBYTE_BUFFER_PTY $sample\n")
                })
            }
            runBlocking { manager.sendInput(id, "QUIT", appendNewline = true).getOrThrow() }
            await("exit") { manager.getProcess(id)?.exitedAt != null }
            assertEquals(0, manager.getProcess(id)?.exitCode)
            checkRead("all", Int.MAX_VALUE)
            assertEquals(all.size.toLong(), requireNotNull(manager.observeTerminalUpdates(id)).value.totalBytes)
        } finally {
            runBlocking { manager.closeInteractiveSession(id) }
            manager.removeProcessRecord(id)
        }
        assertNull(manager.getInteractiveTerminalEmulator(id))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nBYTE_BUFFER_EXIT {\"suite\":\"terminal-byte-buffer-pty-v1\"," +
                "\"readChecks\":$readChecks,\"totalBytes\":${all.size},\"exitCode\":0,\"removed\":true}\n")
        })
    }
}
