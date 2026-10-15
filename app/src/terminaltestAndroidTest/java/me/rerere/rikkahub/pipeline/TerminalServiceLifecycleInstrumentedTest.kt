package me.rerere.rikkahub.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalForegroundLifecycle
import me.rerere.rikkahub.service.ContainerProcessForegroundService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Isolated terminaltest package only: validates platform promotion, rapid stop/start and final byte ownership. */
class TerminalServiceLifecycleInstrumentedTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(180)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()

    private fun state(): TerminalForegroundLifecycle.Diagnostic {
        var value: TerminalForegroundLifecycle.Diagnostic? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            value = ContainerProcessForegroundService.diagnostic()
        }
        return requireNotNull(value)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Foreground lifecycle acknowledgement timed out" }
            SystemClock.sleep(10)
        }
    }

    @Test fun bundleForegroundRapidStopDuringStartupAndRestart() {
        val context = compose.activity
        val before = state()
        repeat(20) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ContainerProcessForegroundService.update(context, 1)
                ContainerProcessForegroundService.stop(context)
            }
            await { val s = state(); !s.pending && !s.live }
        }
        ContainerProcessForegroundService.update(context, 2)
        try {
            await { state().acknowledged == 2 }
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val foreground = activityManager.getRunningServices(100).any {
                it.service.className == ContainerProcessForegroundService::class.java.name && it.foreground
            }
            assertTrue("Android has not recorded foreground promotion", foreground)
            ContainerProcessForegroundService.update(context, 3)
            await { state().acknowledged == 3 }
        } finally { ContainerProcessForegroundService.stop(context) }
        await { val s = state(); !s.pending && !s.live }
        val after = state()
        assertTrue(after.promotions - before.promotions >= 22)
        emit("foreground", JSONObject().put("cycles", 20).put("promotions", after.promotions - before.promotions)
            .put("pending", after.pending).put("live", after.live))
    }

    @Test fun bundleExitedPtyHasExactOrderedTailAndReleasesCheckpoint() {
        runBlocking { proot.start().getOrThrow() }
        val expected = (0 until 500).joinToString("") { "%04d:END\r\n".format(java.util.Locale.ROOT, it) }
        val script = "stty -echo -onlcr; i=0; while [ \"\$i\" -lt 500 ]; do " +
            "printf '%04d:END\\r\\n' \"\$i\"; i=\$((i+1)); done"
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        val started = runBlocking {
            manager.startInteractiveSession("bundle-tail", command, columns = 80, rows = 24, ptyMode = PtyMode.COOKED)
        }
        assertTrue(started.message, started.success)
        val id = started.processId
        try {
            await { manager.readInteractiveBuffer(id) == expected }
            await { manager.getProcess(id)?.exitedAt != null }
            assertEquals(expected, manager.readInteractiveBuffer(id))
            val notices = requireNotNull(manager.observeTerminalUpdates(id)).value
            assertEquals(expected.toByteArray().size.toLong(), notices.totalBytes)
            assertNull(requireNotNull(manager.terminalWidthCheckpoint(id)).open())
            emit("orderedTail", JSONObject().put("lines", 500).put("bytes", notices.totalBytes))
        } finally {
            runBlocking { manager.closeInteractiveSession(id) }
            manager.removeProcessRecord(id)
        }
        assertNull(manager.getInteractiveTerminalEmulator(id))
    }

    private fun emit(case: String, values: JSONObject) {
        values.put("case", case).put("suite", "terminal-bundle-service-v1")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nSERVICE_SAMPLE $values\n")
        })
    }
}
