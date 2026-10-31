package me.rerere.rikkahub.pipeline

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.updatedTerminalRenderPreferences
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.security.MessageDigest

/** Startup writer diagnosis only. No Activity, terminal, optimization or latency samples. */
class TerminalStartupSettingsTest : KoinComponent {
    @get:Rule val timeout = Timeout.seconds(90)
    private val store: SettingsStore by inject()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun startupReceipt(): String {
        val pid = Process.myPid()
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val log = shell("logcat -d -v brief --pid $pid -s RikkaHubApp:I '*:S'")
            assertFalse("Application launch-count write failed", log.contains("incrementLaunchCount failed"))
            val line = log.lineSequence().singleOrNull { it.contains("incrementLaunchCount:") }
            if (line != null) return line.trim()
            SystemClock.sleep(20)
        }
        error("No completion receipt for this PID's application startup write")
    }

    private fun exercise(name: String, fenced: Boolean, replay: Boolean = false) {
        instrumentation.uiAutomation.serviceInfo
        val notes = JSONArray()
        fun snapshot(stage: String): Settings {
            val value = store.settingsFlow.value
            notes.put(JSONObject().put("stage", stage).put("ns", System.nanoTime())
                .put("launchCount", value.launchCount).put("rawSha256", hash(value.terminalCommandPreferences))
                .put("rawEmpty", value.terminalCommandPreferences.isEmpty()))
            return value
        }
        val startup = if (fenced || replay) startupReceipt() else null
        val saved = runBlocking { withTimeout(30_000) { store.settingsFlow.first { !it.init } } }
        val command = "startup-setting-proof-$name"
        val intended = JSONArray().put(JSONObject().put("key", hash(command)).put("command", command)
            .put("updatedAt", 1L).put("terminalPanMode", true).put("forcedTerminalColumns", 80)
            .put("maxScrollbackLines", 10000)).toString()
        var completed = false
        var persistedSeen = false
        var flowSeen = false
        try {
            snapshot("before-write")
            runBlocking {
                store.update { it.copy(terminalCommandPreferences = intended,
                    terminalRenderPreferences = updatedTerminalRenderPreferences(it.terminalRenderPreferences, command, TerminalRenderMode.FULL_VIRTUAL)) }
                persistedSeen = withTimeoutOrNull(2000) { store.settingsFlowRaw.first { it.terminalCommandPreferences == intended } } != null
                flowSeen = withTimeoutOrNull(2000) { store.settingsFlow.first { it.terminalCommandPreferences == intended } } != null
            }
            val confirmed = snapshot("confirmed")
            if (fenced) {
                assertTrue("Fenced persisted/flow configuration missing", persistedSeen && flowSeen)
                assertEquals(intended, confirmed.terminalCommandPreferences)
            }
            if (replay) {
                // Deterministic negative control: the exact old-copy operation from incrementLaunchCount.
                // This is an explicit replay, NOT a claim about the old failed run's hidden call stack.
                runBlocking { store.update(saved.copy(launchCount = saved.launchCount + 1)) }
            }
            val receipt = startup ?: startupReceipt()
            val stored = runBlocking { withTimeout(30_000) { store.settingsFlowRaw.first { !it.init } } }
            runBlocking { withTimeout(30_000) { store.settingsFlow.first {
                it.launchCount == stored.launchCount && it.terminalCommandPreferences == stored.terminalCommandPreferences
            } } }
            val final = snapshot("after-startup")
            val retained = final.terminalCommandPreferences == intended
            if (fenced && !replay) assertTrue("Startup-fenced preference was overwritten", retained)
            if (replay) {
                assertEquals(saved.terminalCommandPreferences, final.terminalCommandPreferences)
                assertFalse(retained)
            }
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nSTARTUP_SETTINGS_CASE " + JSONObject().put("case", name)
                    .put("pid", Process.myPid()).put("fenced", fenced).put("controlledReplay", replay)
                    .put("persistedSeen", persistedSeen).put("flowSeen", flowSeen)
                    .put("retained", retained).put("launchReceipt", receipt).put("notes", notes)
                    .put("performanceMeasured", false).put("passed", true) + "\n")
            })
            completed = true
        } finally {
            runBlocking { store.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                terminalRenderPreferences = saved.terminalRenderPreferences) } }
            if (!completed) instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nSTARTUP_SETTINGS_FAILURE " + JSONObject().put("case", name).put("notes", notes) + "\n")
            })
        }
    }
    @Test fun startupUnfenced0() = exercise("unfenced0", false)
    @Test fun startupUnfenced1() = exercise("unfenced1", false)
    @Test fun startupUnfenced2() = exercise("unfenced2", false)
    @Test fun startupUnfenced3() = exercise("unfenced3", false)
    @Test fun startupFenced0() = exercise("fenced0", true)
    @Test fun startupFenced1() = exercise("fenced1", true)
    @Test fun startupFenced2() = exercise("fenced2", true)
    @Test fun startupFenced3() = exercise("fenced3", true)
    @Test fun startupControlledStaleCopyReplay() = exercise("controlled-replay", true, true)
}
