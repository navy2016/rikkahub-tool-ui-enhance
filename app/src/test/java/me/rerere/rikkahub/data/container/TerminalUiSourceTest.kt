package me.rerere.rikkahub.data.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalUiSourceTest {
    private val processSessionSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt")
    ).first { it.isFile }.readText()
    private val chatInputSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt")
    ).first { it.isFile }.readText()
    private val conversationListSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt")
    ).first { it.isFile }.readText()
    private val advancedSettingsSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAdvancedPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAdvancedPage.kt")
    ).first { it.isFile }.readText()
    private val chatPageSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt")
    ).first { it.isFile }.readText()
    private val routeSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/RouteActivity.kt"),
        File("src/main/java/me/rerere/rikkahub/RouteActivity.kt")
    ).first { it.isFile }.readText()
    private val pRootManagerSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt")
    ).first { it.isFile }.readText()
    private val preferencesStoreSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt"),
        File("src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt")
    ).first { it.isFile }.readText()
    private val backgroundProcessManagerSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt")
    ).first { it.isFile }.readText()
    private val renderedRowsSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun terminalKeysSupportCustomLabelsAndShiftLatch() {
        assertTrue(processSessionSource.contains("val label: String? = null"))
        assertTrue(processSessionSource.contains("SHIFT"))
        assertTrue(processSessionSource.contains("shiftLatch"))
        assertTrue(processSessionSource.contains("sequenceFor(key, shift = shiftLatch"))
        assertTrue(processSessionSource.contains("""placeholder = { Text("显示名") }"""))
    }

    @Test
    fun virtualHistoryIsExplicitlyOptInAndDoesNotChangeDefaultRenderer() {
        val lazyMeasurementsSource = listOf(
            File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt"),
            File("src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt"),
        ).first { it.isFile }.readText()
        assertTrue(processSessionSource.contains("TerminalRenderMode.VIRTUAL_HISTORY"))
        assertTrue(processSessionSource.contains("val wantsVirtualHistory = appliedTerminalRenderMode == TerminalRenderMode.VIRTUAL_HISTORY"))
        assertTrue(processSessionSource.contains("virtualHistoryPolicy.allows(terminalHistoryChunkPlan.isNotEmpty(), terminalPanMode, selectionMode,"))
        assertTrue(processSessionSource.contains("currentUsesTuiViewport, imeVisible)"))
        assertTrue(processSessionSource.contains("TerminalLazyItemMeasurements"))
        assertTrue(processSessionSource.contains("effectiveRenderMode = effectiveTerminalRenderMode"))
        assertTrue(processSessionSource.contains("rememberTerminalBoundViewport("))
        assertTrue(processSessionSource.contains("rememberTerminalVirtualHistoryPolicy(processId, appliedTerminalRenderMode, imeVisible)"))
        assertTrue(processSessionSource.contains("virtualHistoryPolicy.reapplied(currentImeVisible)"))
        assertTrue(processSessionSource.contains("latestSaveTerminalViewport"))
        assertTrue(processSessionSource.contains("rows = terminalRenderedRows.toList(),"))
        assertTrue(lazyMeasurementsSource.contains("metricKey"))
        assertTrue(lazyMeasurementsSource.contains("it.text == frame.rows[index].text"))
        assertTrue(lazyMeasurementsSource.contains("heights[lineId]?.owner === owner"))
        assertTrue(lazyMeasurementsSource.contains("ModifierNodeElement<RowMeasurementNode>"))
        assertTrue(lazyMeasurementsSource.contains("override fun onDetach()"))
        assertTrue(lazyMeasurementsSource.contains("override fun onReset()"))
        assertFalse(lazyMeasurementsSource.contains("Box("))
        assertFalse(lazyMeasurementsSource.contains("DisposableEffect("))
        assertFalse(lazyMeasurementsSource.contains("mutableStateMapOf"))
        assertFalse(lazyMeasurementsSource.contains("Snapshot.withoutReadObservation"))
        assertTrue(lazyMeasurementsSource.contains("MutableSharedFlow<Unit>"))
        assertFalse(lazyMeasurementsSource.contains("runCatching"))
        assertTrue(renderedRowsSource.contains("BasicText(text = state.text"))
        assertFalse(renderedRowsSource.lines().any { it.trimStart().startsWith("Text(text = state.text") })
        assertEquals(TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.DEFAULT)
    }

    @Test
    fun rendererButtonIsAdditiveAndKeepsKeysUntouched() {
        assertTrue(processSessionSource.contains("TerminalActionPreset(\"RENDER\", \"渲染方式\")"))
        assertTrue(processSessionSource.contains("\"RENDER\" -> TerminalRenderButton"))
        assertTrue(processSessionSource.contains("settingsStore.setTerminalRenderer(process.command, mode)"))
        assertTrue(processSessionSource.contains("if (!selectionMode) appliedTerminalRenderMode = terminalRenderMode"))
        assertTrue(processSessionSource.contains("TerminalTranscriptViewport("))
        assertTrue(processSessionSource.contains("\"GRID\", \"RENDER\", \"KEYS\""))
        assertTrue(processSessionSource.contains("rememberTerminalBoundViewport("))
        assertTrue(processSessionSource.contains("bound = boundViewport"))
        assertTrue(processSessionSource.contains("virtualHistoryAllowed = virtualHistoryEnabled"))
        assertTrue(processSessionSource.contains("virtualHistoryPolicy.allows("))
        assertTrue(preferencesStoreSource.contains("preferences[TERMINAL_RENDER_PREFERENCES] = settings.terminalRenderPreferences"))
        assertTrue(preferencesStoreSource.contains("settingsFlow.value = settingsFlow.value.copy(terminalRenderPreferences = nextRaw)"))
        // Existing status actions and KEYS remain exactly one dispatch branch each.
        for (id in listOf("RAW", "AUTO", "COLS", "HIST", "JUMP", "IME", "GRID", "KEYS", "TOUCH",
            "INPUT", "A-", "A+", "COPY", "PASTE", "CLR", "CTN", "FULL")) {
            assertEquals(1, processSessionSource.split("\"$id\" -> TerminalStatus").size - 1)
        }
        for (id in listOf("CTRL", "ALT", "SHIFT", "SEL", "KBD", "ESC", "TAB", "S-TAB", "UP", "DOWN",
            "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL", "ENTER", "C-C", "C-D",
            "C-Z", "C-L", "C-U", "C-W", "C-A", "C-E", "C-R", "COPY", "PASTE", "CLEAR", "TEST", "CLI")) {
            assertEquals(1, processSessionSource.split("\"$id\" -> TerminalKey").size - 1)
        }
    }

    @Test
    fun terminalQuickCommandsIncludeOmpInstallAndTest() {
        assertTrue(processSessionSource.contains("""TerminalQuickCommandConfig("install-omp", "rikkahub-install-omp")"""))
        assertTrue(processSessionSource.contains("""TerminalQuickCommandConfig("test-omp", "rikkahub-test-omp")"""))
        assertTrue(processSessionSource.contains("""TerminalQuickCommandConfig("network-boost-cn", "rikkahub-network-boost-cn")"""))
    }

    @Test
    fun chatInputHasTerminalButtonWithLongPress() {
        assertTrue(chatInputSource.contains("TerminalSessionButton"))
        assertTrue(chatInputSource.contains("onStartFirstTerminalQuickCommand"))
        assertTrue(chatInputSource.contains("modifier = Modifier.combinedClickable("))
        assertTrue(chatInputSource.contains("onLongClick = onLongClick"))
        assertTrue(chatInputSource.contains("Lucide.Terminal"))
        assertTrue(chatInputSource.contains("modifier = Modifier.size(22.dp)"))
        assertTrue(chatInputSource.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(chatInputSource.contains("Modifier.size(24.dp)"))
        assertTrue(chatInputSource.contains("padding(vertical = 8.dp, horizontal = 8.dp)"))
    }

    @Test
    fun customTuiEntryLivesInAdvancedSettingsNotSessionToolbar() {
        assertTrue(advancedSettingsSource.contains("自定义 TUI 程序名单"))
        assertTrue(advancedSettingsSource.contains("terminalCustomTuiCommands"))
        assertTrue(!processSessionSource.contains("showCustomTuiDialog"))
        assertTrue(!processSessionSource.contains("TextButton(onClick = { showCustomTuiDialog = true })"))
    }

    @Test
    fun fullGridProgramListIsIndependentEmptyByDefaultAndPersisted() {
        assertTrue(preferencesStoreSource.contains("TERMINAL_FULL_GRID_COMMANDS"))
        assertTrue(preferencesStoreSource.contains("terminalFullGridCommands = preferences[TERMINAL_FULL_GRID_COMMANDS] ?: \"\""))
        assertTrue(preferencesStoreSource.contains("preferences[TERMINAL_FULL_GRID_COMMANDS] = settings.terminalFullGridCommands"))
        assertTrue(preferencesStoreSource.contains("val terminalFullGridCommands: String = \"\""))
        assertTrue(processSessionSource.contains("settings.terminalFullGridCommands"))
        assertTrue(!processSessionSource.contains("terminalGridStatusItemMigrated"))
    }

    @Test
    fun longPressTerminalButtonNavigatesToStartedTerminalPanel() {
        assertTrue(routeSource.contains("data class ProcessSessions(val sandboxId: String, val processId: String? = null)"))
        assertTrue(processSessionSource.contains("initialProcessId"))
        assertTrue(chatPageSource.contains("Screen.ProcessSessions(sandboxId, result.processId)"))
    }

    @Test
    fun conversationListDisplaysRunningProcessCountInErrorColor() {
        assertTrue(conversationListSource.contains("runningProcessCount"))
        assertTrue(conversationListSource.contains("MaterialTheme.colorScheme.error"))
    }

    @Test
    fun containerNetworkAccelerationSettingsAreExposed() {
        assertTrue(advancedSettingsSource.contains("APK 镜像源"))
        assertTrue(advancedSettingsSource.contains("npm registry"))
        assertTrue(advancedSettingsSource.contains("GitHub 下载代理前缀"))
        assertTrue(preferencesStoreSource.contains("CONTAINER_APK_MIRROR"))
        assertTrue(preferencesStoreSource.contains("CONTAINER_NPM_REGISTRY"))
        assertTrue(preferencesStoreSource.contains("CONTAINER_GITHUB_PROXY_PREFIX"))
        assertTrue(pRootManagerSource.contains("RIKKAHUB_APK_MIRROR"))
        assertTrue(pRootManagerSource.contains("RIKKAHUB_NPM_REGISTRY"))
        assertTrue(pRootManagerSource.contains("RIKKAHUB_GITHUB_PROXY_PREFIX"))
        assertTrue(pRootManagerSource.contains("rikkahub-network-boost-cn"))
        assertTrue(pRootManagerSource.contains("rikkahub-set-apk-mirror"))
        assertTrue(pRootManagerSource.contains("rikkahub-set-npm-registry"))
        assertTrue(backgroundProcessManagerSource.contains("private val settingsStore: SettingsStore"))
        assertTrue(backgroundProcessManagerSource.contains("containerNetworkEnvironment"))
        assertTrue(backgroundProcessManagerSource.contains("normalizeNpmRegistrySetting"))
        assertTrue(backgroundProcessManagerSource.contains("https://registry.npmmirror.com"))
        assertTrue(backgroundProcessManagerSource.contains("env = processEnv"))
        assertTrue(backgroundProcessManagerSource.contains("env = containerNetworkEnvironment()"))
    }
}
