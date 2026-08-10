package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.container.BackgroundProcessInfo
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.isTuiCommand
import me.rerere.rikkahub.data.container.ControlInput
import me.rerere.rikkahub.data.container.ProcessStatus
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.container.ContainerManagerSheet
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalEmulator.Key
import me.rerere.rikkahub.utils.TerminalEmulator.MouseButton
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEvent
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEventType
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

private val TerminalConfigJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val TERMINAL_FORCED_COLUMN_PRESETS = listOf(80, 100, 120, 160, 200)
private const val TERMINAL_RENDER_FRAME_MS = 33L
private const val TERMINAL_SYNC_OUTPUT_MAX_WAIT_MS = 100L
private const val TERMINAL_PTY_RESIZE_DEBOUNCE_MS = 160L
private const val TERMINAL_IME_RESIZE_DEBOUNCE_MS = 120L
private const val TERMINAL_RESIZE_RENDER_FALLBACK_MS = 180L
private const val TERMINAL_FAST_FLING_VELOCITY_PX = 3500f
private const val TERMINAL_FAST_FLING_WINDOW_MS = 700L
private const val TERMINAL_EDGE_THRESHOLD_PX = 80
private const val TERMINAL_UI_MIN_SCROLLBACK_LINES = 100
private const val TERMINAL_IME_HEIGHT_MIN_DP = 80
private const val TERMINAL_IME_HEIGHT_MAX_DP = 800
private val TERMINAL_SCROLLBACK_PRESETS = listOf(100, 500, 1000, 2000, 5000, 10000)
private val TERMINAL_FAST_FLING_PRESETS = listOf(1, 2, 3, 4, 5)
private val TERMINAL_IME_HEIGHT_PRESETS = listOf(240, 280, 320, 360, 400, 440)

@Serializable
private data class TerminalQuickCommandConfig(
    val name: String,
    val command: String,
)

@Serializable
private data class TerminalItemConfig(
    val id: String,
    val label: String? = null,
)

@Serializable
private data class TerminalCommandPreference(
    val key: String,
    val command: String,
    val updatedAt: Long,
    val showStatusBar: Boolean = false,
    val rawInputMode: Boolean? = null,
    val autoScroll: Boolean = true,
    val showExtraKeys: Boolean? = null,
    val terminalPanMode: Boolean? = null,
    val showFullInputBar: Boolean? = null,
    val terminalFontSizeSp: Float = 12f,
    val forcedTerminalColumns: Int? = null,
    val maxScrollbackLines: Int = TerminalEmulator.DEFAULT_MAX_SCROLLBACK_LINES,
    val fastFlingRequiredCount: Int = 2,
    val customImeHeightDp: Int? = null,
)

private fun normalizedTerminalCommand(command: String): String = command.trim()

private fun terminalCommandKey(command: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(normalizedTerminalCommand(command).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun decodeTerminalCommandPreferences(raw: String): List<TerminalCommandPreference> =
    decodeTerminalConfig(raw, emptyList())

private fun terminalCommandPreference(raw: String, command: String): TerminalCommandPreference? {
    val key = terminalCommandKey(command)
    return decodeTerminalCommandPreferences(raw).firstOrNull { it.key == key }
}

private fun updatedTerminalCommandPreferences(
    raw: String,
    command: String,
    update: (TerminalCommandPreference) -> TerminalCommandPreference,
): String {
    val normalized = normalizedTerminalCommand(command)
    val key = terminalCommandKey(normalized)
    val records = decodeTerminalCommandPreferences(raw).filterNot { it.key == key }.toMutableList()
    val current = terminalCommandPreference(raw, normalized) ?: TerminalCommandPreference(
        key = key,
        command = normalized,
        updatedAt = 0L,
    )
    records += update(current).copy(key = key, command = normalized, updatedAt = System.currentTimeMillis())
    return TerminalConfigJson.encodeToString(records.sortedByDescending { it.updatedAt }.take(50))
}

private data class TerminalActionPreset(
    val id: String,
    val label: String,
)

private data class TerminalImeViewportAnchor(
    val followBottom: Boolean,
    val shouldAvoidIme: Boolean,
    val offsetPx: Int,
)

private fun shouldAvoidTerminalIme(
    terminalContentHeightPx: Int,
    fullOutputViewportHeightPx: Int,
    effectiveImeHeightPx: Int,
): Boolean = fullOutputViewportHeightPx > 0 && effectiveImeHeightPx > 0 &&
    terminalContentHeightPx > fullOutputViewportHeightPx - effectiveImeHeightPx

private fun terminalImeHeightLabel(customImeHeightDp: Int?): String =
    customImeHeightDp?.let { "I$it" } ?: "IAUTO"

private fun defaultTerminalQuickCommands() = listOf(
    TerminalQuickCommandConfig("tmux", "rikkahub-tmux"),
    TerminalQuickCommandConfig("bash", "bash"),
    TerminalQuickCommandConfig("vim", "vim"),
    TerminalQuickCommandConfig("nano", "nano"),
    TerminalQuickCommandConfig("claude", "claude"),
    TerminalQuickCommandConfig("codex", "codex"),
    TerminalQuickCommandConfig("opencode", "opencode"),
    TerminalQuickCommandConfig("fix-apk", "rikkahub-fix-apk"),
    TerminalQuickCommandConfig("test-network", "rikkahub-test-network"),
    TerminalQuickCommandConfig("network-boost-cn", "rikkahub-network-boost-cn"),
    TerminalQuickCommandConfig("clean-caches", "rikkahub-clean-caches"),
    TerminalQuickCommandConfig("npm-env", "rikkahub-npm-env"),
    TerminalQuickCommandConfig("terminal-tools", "rikkahub-install-terminal-tools"),
    TerminalQuickCommandConfig("ai-cli", "rikkahub-install-ai-cli"),
    TerminalQuickCommandConfig("install-omp", "rikkahub-install-omp"),
    TerminalQuickCommandConfig("test-omp", "rikkahub-test-omp"),
    TerminalQuickCommandConfig("node-help", "rikkahub-node-help"),
    TerminalQuickCommandConfig("build-tools", "rikkahub-install-node-build-tools"),
    TerminalQuickCommandConfig("enable-polling", "rikkahub-enable-polling"),
    TerminalQuickCommandConfig("test-node", "rikkahub-test-node-npm"),
    TerminalQuickCommandConfig("test-native", "rikkahub-test-node-native"),
    TerminalQuickCommandConfig("test-watch", "rikkahub-test-watch"),
    TerminalQuickCommandConfig("test-service", "rikkahub-test-service"),
    TerminalQuickCommandConfig("test-port", "rikkahub-test-port 3000"),
    TerminalQuickCommandConfig("service-env", "rikkahub-service-env 3000"),
    TerminalQuickCommandConfig("service-help", "rikkahub-service-help"),
    TerminalQuickCommandConfig("npm-dev", "rikkahub-run-service 3000 npm run dev"),
    TerminalQuickCommandConfig("http-server", "rikkahub-run-service 18080 python3 -m http.server 18080 --bind 127.0.0.1"),
    TerminalQuickCommandConfig("browser-tools", "rikkahub-install-browser-tools"),
    TerminalQuickCommandConfig("browser-help", "rikkahub-browser-help"),
    TerminalQuickCommandConfig("doctor", "rikkahub-doctor"),
    TerminalQuickCommandConfig("tty", "tty; stty size; echo ${'$'}TERM"),
)

private fun defaultTerminalStatusItems() = listOf("RAW", "AUTO", "COLS", "HIST", "JUMP", "IME", "KEYS", "TOUCH", "INPUT", "A-", "A+", "COPY", "PASTE", "CLR", "CTN", "FULL").map { TerminalItemConfig(it) }
private fun defaultTerminalExtraKeyItems() = listOf("CTRL", "ALT", "SHIFT", "SEL", "KBD", "ESC", "TAB", "S-TAB", "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN", "BKSP", "DEL", "ENTER", "C-C", "C-D", "C-Z", "C-L", "C-U", "C-W", "C-A", "C-E", "C-R", "COPY", "PASTE", "CLEAR", "TEST", "CLI").map { TerminalItemConfig(it) }

private val TerminalStatusPresets = listOf(
    TerminalActionPreset("RAW", "RAW/LINE"), TerminalActionPreset("AUTO", "AUTO/LOCK"), TerminalActionPreset("COLS", "FIT/80C/120C"), TerminalActionPreset("HIST", "HIST"), TerminalActionPreset("JUMP", "JUMP"), TerminalActionPreset("IME", "IME/AUTO"), TerminalActionPreset("KEYS", "KEYS"),
    TerminalActionPreset("TOUCH", "TOUCH/MOUSE"), TerminalActionPreset("INPUT", "INPUT/MINI"), TerminalActionPreset("A-", "A-"),
    TerminalActionPreset("A+", "A+"), TerminalActionPreset("COPY", "COPY"), TerminalActionPreset("PASTE", "PASTE"),
    TerminalActionPreset("CLR", "CLR"), TerminalActionPreset("CTN", "CTN"), TerminalActionPreset("FULL", "FULL/EXIT"),
)
private val TerminalExtraKeyPresets = listOf(
    TerminalActionPreset("CTRL", "CTRL"), TerminalActionPreset("ALT", "ALT"), TerminalActionPreset("SHIFT", "SHIFT"), TerminalActionPreset("SEL", "SEL"), TerminalActionPreset("KBD", "KBD"),
    TerminalActionPreset("ESC", "ESC"), TerminalActionPreset("TAB", "TAB"), TerminalActionPreset("S-TAB", "S-TAB"), TerminalActionPreset("UP", "↑"),
    TerminalActionPreset("DOWN", "↓"), TerminalActionPreset("LEFT", "←"), TerminalActionPreset("RIGHT", "→"), TerminalActionPreset("HOME", "HOME"),
    TerminalActionPreset("END", "END"), TerminalActionPreset("PGUP", "PGUP"), TerminalActionPreset("PGDN", "PGDN"), TerminalActionPreset("BKSP", "BKSP"),
    TerminalActionPreset("DEL", "DEL"), TerminalActionPreset("ENTER", "ENTER"), TerminalActionPreset("C-C", "C-C"), TerminalActionPreset("C-D", "C-D"),
    TerminalActionPreset("C-Z", "C-Z"), TerminalActionPreset("C-L", "C-L"), TerminalActionPreset("C-U", "C-U"), TerminalActionPreset("C-W", "C-W"),
    TerminalActionPreset("C-A", "C-A"), TerminalActionPreset("C-E", "C-E"), TerminalActionPreset("C-R", "C-R"), TerminalActionPreset("COPY", "COPY"),
    TerminalActionPreset("PASTE", "PASTE"), TerminalActionPreset("CLEAR", "CLEAR"), TerminalActionPreset("TEST", "TEST"), TerminalActionPreset("CLI", "CLI"),
)

private inline fun <reified T> decodeTerminalConfig(raw: String, fallback: List<T>): List<T> =
    runCatching { if (raw.isBlank()) fallback else TerminalConfigJson.decodeFromString<List<T>>(raw) }.getOrDefault(fallback)

private fun encodeTerminalQuickCommands(items: List<TerminalQuickCommandConfig>) = TerminalConfigJson.encodeToString(items)
private fun encodeTerminalItems(items: List<TerminalItemConfig>) = TerminalConfigJson.encodeToString(items)

private fun terminalScrollbackLabel(lines: Int): String =
    if (lines >= 1000) "H${lines / 1000}K" else "H$lines"

private fun ensureTerminalStatusItems(items: List<TerminalItemConfig>): List<TerminalItemConfig> {
    val result = items.toMutableList()
    if (result.none { it.id == "COLS" }) {
        val afterAuto = result.indexOfFirst { it.id == "AUTO" }.takeIf { it >= 0 }?.plus(1) ?: result.size
        result.add(afterAuto, TerminalItemConfig("COLS"))
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSessionPage(sandboxId: String, initialProcessId: String? = null) {
    val bgManager = koinInject<BackgroundProcessManager>()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val processStates by bgManager.processStates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val sandboxProcesses = processStates
        .filter { it.sandboxId == sandboxId }
        .sortedByDescending { it.createdAt }

    var activeInteractiveId by remember { mutableStateOf<String?>(null) }
    var terminalFullscreen by remember { mutableStateOf(true) }
    var showTerminalStatusBar by remember { mutableStateOf(false) }

    fun restoreStatusBarPreference(command: String) {
        showTerminalStatusBar = terminalCommandPreference(settings.terminalCommandPreferences, command)?.showStatusBar ?: false
    }

    fun persistStatusBarPreference(command: String, visible: Boolean) {
        scope.launch {
            settingsStore.update { current ->
                current.copy(terminalCommandPreferences = updatedTerminalCommandPreferences(
                    current.terminalCommandPreferences,
                    command,
                ) { it.copy(showStatusBar = visible) })
            }
        }
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogsFor by remember { mutableStateOf<String?>(null) }

    val activeInteractiveProcess = sandboxProcesses.firstOrNull {
        it.processId == activeInteractiveId && it.isInteractive
    }
    var pendingInitialProcessId by remember(initialProcessId) { mutableStateOf(initialProcessId) }
    var restoredSandboxUiState by remember(sandboxId) { mutableStateOf(false) }
    LaunchedEffect(pendingInitialProcessId, sandboxProcesses) {
        val id = pendingInitialProcessId ?: return@LaunchedEffect
        val target = sandboxProcesses.firstOrNull { it.processId == id && it.isInteractive } ?: return@LaunchedEffect
        activeInteractiveId = target.processId
        terminalFullscreen = true
        restoreStatusBarPreference(target.command)
        pendingInitialProcessId = null
        restoredSandboxUiState = true
    }
    LaunchedEffect(sandboxId, sandboxProcesses, pendingInitialProcessId, restoredSandboxUiState) {
        if (restoredSandboxUiState || pendingInitialProcessId != null || sandboxProcesses.isEmpty()) return@LaunchedEffect
        val state = bgManager.getTerminalSandboxUiState(sandboxId)
        val target = state?.activeInteractiveProcessId?.let { id ->
            sandboxProcesses.firstOrNull { it.processId == id && it.isInteractive }
        }
        if (target != null) {
            activeInteractiveId = target.processId
            terminalFullscreen = state.terminalFullscreen
            restoreStatusBarPreference(target.command)
        }
        restoredSandboxUiState = true
    }
    LaunchedEffect(sandboxId, activeInteractiveId, terminalFullscreen, restoredSandboxUiState) {
        if (restoredSandboxUiState || activeInteractiveId != null) {
            bgManager.saveTerminalSandboxUiState(sandboxId, activeInteractiveId, terminalFullscreen)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "会话管理",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "sandbox: ${sandboxId.takeLast(8)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (activeInteractiveProcess != null) {
                        TextButton(onClick = { terminalFullscreen = !terminalFullscreen }) {
                            Text(if (terminalFullscreen) "列表" else "全屏")
                        }
                        TextButton(onClick = {
                            showTerminalStatusBar = !showTerminalStatusBar
                            persistStatusBarPreference(activeInteractiveProcess.command, showTerminalStatusBar)
                        }) {
                            Text(if (showTerminalStatusBar) "隐藏状态" else "状态")
                        }
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Text("+", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        if (terminalFullscreen && activeInteractiveProcess != null) {
            TerminalInteractivePanel(
                process = activeInteractiveProcess,
                bgManager = bgManager,
                fullscreen = true,
                showStatusBar = showTerminalStatusBar,
                onFullscreenChange = { terminalFullscreen = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (sandboxProcesses.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreate = { showCreateDialog = true }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                sandboxProcesses.forEach { process ->
                    ProcessCard(
                        process = process,
                        isInteractiveActive = activeInteractiveId == process.processId,
                        onToggleInteractive = {
                            val isClosing = activeInteractiveId == process.processId
                            activeInteractiveId = if (isClosing) null else process.processId
                            if (!isClosing && process.isInteractive) {
                                terminalFullscreen = true
                                restoreStatusBarPreference(process.command)
                            }
                        },
                        onKill = {
                            scope.launch {
                                if (process.isInteractive) {
                                    bgManager.closeInteractiveSession(process.processId)
                                } else {
                                    bgManager.killProcess(process.processId)
                                }
                                if (activeInteractiveId == process.processId) {
                                    activeInteractiveId = null
                                }
                            }
                        },
                        onViewLogs = { showLogsFor = process.processId },
                        onRemove = {
                            bgManager.removeProcessRecord(process.processId)
                            if (activeInteractiveId == process.processId) {
                                activeInteractiveId = null
                            }
                        }
                    )

                    if (!terminalFullscreen && process.isInteractive && activeInteractiveId == process.processId) {
                        TerminalInteractivePanel(
                            process = process,
                            bgManager = bgManager,
                            fullscreen = false,
                            showStatusBar = showTerminalStatusBar,
                            onFullscreenChange = { terminalFullscreen = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateSessionDialog(
            settingsStore = settingsStore,
            quickCommandsConfig = settings.terminalQuickCommands,
            onDismiss = { showCreateDialog = false },
            onCreate = { command ->
                scope.launch {
                    val sizeHint = bgManager.getTerminalSizeHint(command)
                    val result = bgManager.startInteractiveSession(
                        sandboxId = sandboxId,
                        command = command,
                        preferTty = true,
                        columns = sizeHint?.columns ?: 80,
                        rows = sizeHint?.rows ?: 24
                    )
                    if (result.success) {
                        activeInteractiveId = result.processId
                        terminalFullscreen = true
                        restoreStatusBarPreference(command)
                    }
                    showCreateDialog = false
                }
            }
        )
    }

    showLogsFor?.let { processId ->
        LogsDialog(
            processId = processId,
            bgManager = bgManager,
            onDismiss = { showLogsFor = null }
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🖥️", fontSize = 56.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "暂无进程",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "AI 可通过 container_shell_bg 启动后台/交互进程\n用户可在此前台化交互式会话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCreate) {
            Text("新建交互会话")
        }
    }
}

@Composable
private fun ProcessCard(
    process: BackgroundProcessInfo,
    isInteractiveActive: Boolean,
    onToggleInteractive: () -> Unit,
    onKill: () -> Unit,
    onViewLogs: () -> Unit,
    onRemove: () -> Unit
) {
    val statusColor = when (process.status) {
        ProcessStatus.RUNNING -> Color(0xFF4CAF50)
        ProcessStatus.STARTING -> Color(0xFFFFA726)
        ProcessStatus.STOPPED -> Color(0xFF9E9E9E)
        ProcessStatus.COMPLETED -> Color(0xFF2196F3)
        ProcessStatus.FAILED -> Color(0xFFE53935)
        ProcessStatus.ORPHANED -> Color(0xFF795548)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = process.processId.takeLast(10),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }

                StatusChip(
                    status = process.status,
                    isInteractive = process.isInteractive,
                    ttyEnabled = process.ttyEnabled
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = process.command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    process.pid?.let {
                        Text(
                            text = "PID: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    process.startedAt?.let { startedAt ->
                        val end = process.exitedAt ?: System.currentTimeMillis()
                        Text(
                            text = formatDuration(end - startedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when {
                        process.isInteractive && process.status == ProcessStatus.RUNNING -> {
                            TextButton(onClick = onToggleInteractive) {
                                Text(if (isInteractiveActive) "收起" else "终端")
                            }
                            TextButton(
                                onClick = onKill,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("终止")
                            }
                        }

                        !process.isInteractive && process.status == ProcessStatus.RUNNING -> {
                            TextButton(onClick = onViewLogs) {
                                Text("日志")
                            }
                            TextButton(
                                onClick = onKill,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("终止")
                            }
                        }

                        else -> {
                            TextButton(onClick = onViewLogs) {
                                Text("日志")
                            }
                            TextButton(
                                onClick = onRemove,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("删除记录")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(
    status: ProcessStatus,
    isInteractive: Boolean,
    ttyEnabled: Boolean
) {
    val text = buildString {
        append(
            when (status) {
                ProcessStatus.RUNNING -> if (isInteractive) "交互中" else "运行中"
                ProcessStatus.STARTING -> "启动中"
                ProcessStatus.STOPPED -> "已停止"
                ProcessStatus.COMPLETED -> "已完成"
                ProcessStatus.FAILED -> "失败"
                ProcessStatus.ORPHANED -> "孤立"
            }
        )
        if (isInteractive && ttyEnabled) append(" · TTY")
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun TerminalInteractivePanel(
    process: BackgroundProcessInfo,
    bgManager: BackgroundProcessManager,
    fullscreen: Boolean,
    showStatusBar: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val processId = process.processId
    val restoredViewportState = remember(processId) { bgManager.getTerminalViewportState(processId) }
    val outputScroll = rememberScrollState(restoredViewportState?.verticalOffsetPx ?: 0)
    val horizontalScroll = rememberScrollState(restoredViewportState?.horizontalOffsetPx ?: 0)
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textMeasurer = rememberTextMeasurer()
    val prootManager: PRootManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val savedPreference = remember(processId, settings.terminalCommandPreferences) {
        terminalCommandPreference(settings.terminalCommandPreferences, process.command)
    }
    var showContainerManager by remember { mutableStateOf(false) }
    val sizeHint = remember(processId, process.command) { bgManager.getTerminalSizeHint(process.command) }
    val initialTerminalColumns = remember(processId) {
        (process.terminalColumns.takeIf { it > 0 } ?: sizeHint?.columns ?: 80)
            .coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
    }
    val initialTerminalRows = remember(processId) {
        (process.terminalRows.takeIf { it > 0 } ?: sizeHint?.rows ?: 24)
            .coerceIn(TerminalEmulator.MIN_ROWS, TerminalEmulator.MAX_ROWS)
    }

    val initialMaxScrollbackLines = savedPreference?.maxScrollbackLines
        ?.coerceIn(TERMINAL_UI_MIN_SCROLLBACK_LINES, TerminalEmulator.MAX_SCROLLBACK_LINES)
        ?: TerminalEmulator.DEFAULT_MAX_SCROLLBACK_LINES
    val terminalEmulator = remember(processId) {
        TerminalEmulator(
            initialColumns = initialTerminalColumns,
            initialRows = initialTerminalRows,
            maxScrollbackLines = initialMaxScrollbackLines,
        )
    }
    val terminalBackground = Color(0xFF101010)
    val terminalForeground = Color(0xFF00E676)
    val terminalMuted = Color(0xFFB0BEC5)
    var terminalFontSizeSp by remember(processId) { mutableStateOf(savedPreference?.terminalFontSizeSp ?: 12f) }
    val terminalTextStyle = TextStyle(
        color = terminalForeground,
        fontFamily = JetbrainsMono,
        fontSize = terminalFontSizeSp.sp,
        lineHeight = terminalFontSizeSp.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both
        )
    )
    val installCliCommand = remember {
        "rikkahub-install-terminal-tools || (printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf; " +
            "printf 'https://dl-cdn.alpinelinux.org/alpine/v3.19/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.19/community\n' > /etc/apk/repositories; " +
            "apk update && apk add --no-cache vim nano util-linux nodejs npm bash ca-certificates curl git openssh-client tmux && " +
            "npm config set prefix /usr/local && npm config set cache /tmp/npm-cache && npm config set python /usr/bin/python3)"
    }
    var input by remember { mutableStateOf("") }
    var terminalRenderedRows by remember { mutableStateOf(terminalEmulator.renderRows()) }
    var terminalModeSummary by remember { mutableStateOf(terminalEmulator.modeSummary()) }
    var terminalStatus by remember { mutableStateOf("就绪") }
    var autoScroll by remember(processId) { mutableStateOf(restoredViewportState?.autoScroll ?: savedPreference?.autoScroll ?: true) }
    var rawInputMode by remember(processId, settings.terminalCustomTuiCommands) { mutableStateOf(savedPreference?.rawInputMode ?: isTuiCommand(process.command, settings.terminalCustomTuiCommands)) }
    var showExtraKeys by remember(processId) { mutableStateOf(savedPreference?.showExtraKeys ?: !fullscreen) }
    var selectionMode by remember { mutableStateOf(false) }
    var terminalPanMode by remember(processId) { mutableStateOf(savedPreference?.terminalPanMode ?: rawInputMode) }
    var showFullInputBar by remember(processId) { mutableStateOf(savedPreference?.showFullInputBar ?: !rawInputMode) }
    var ctrlLatch by remember { mutableStateOf(false) }
    var altLatch by remember { mutableStateOf(false) }
    var shiftLatch by remember { mutableStateOf(false) }
    var terminalColumns by remember(processId) { mutableIntStateOf(initialTerminalColumns) }
    var terminalRows by remember(processId) { mutableIntStateOf(initialTerminalRows) }
    var measuredTerminalColumns by remember(processId) { mutableIntStateOf(initialTerminalColumns) }
    val measuredTerminalRows = remember(processId) { AtomicInteger(initialTerminalRows) }
    val pendingImeRowResizeJob = remember(processId) { AtomicReference<Job?>(null) }
    var forcedTerminalColumns by remember(processId) { mutableStateOf(savedPreference?.forcedTerminalColumns) }
    var maxScrollbackLines by remember(processId) { mutableIntStateOf(initialMaxScrollbackLines) }
    var fastFlingRequiredCount by remember(processId) {
        mutableIntStateOf((savedPreference?.fastFlingRequiredCount ?: 2).coerceIn(1, TERMINAL_FAST_FLING_PRESETS.last()))
    }
    var customImeHeightDp by remember(processId) {
        mutableStateOf(savedPreference?.customImeHeightDp?.coerceIn(TERMINAL_IME_HEIGHT_MIN_DP, TERMINAL_IME_HEIGHT_MAX_DP))
    }
    var terminalSettingsDialog by remember { mutableStateOf<String?>(null) }
    val imeVisible = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val measuredCell = remember(terminalTextStyle, density) { textMeasurer.measure("W", style = terminalTextStyle) }
    val terminalCellWidthPx = measuredCell.size.width.coerceAtLeast(1)
    val terminalCellHeightPx = with(density) { terminalTextStyle.lineHeight.toPx() }
        .roundToInt()
        .coerceAtLeast(measuredCell.size.height)
        .coerceAtLeast(1)
    val inputBarVisible = !rawInputMode || showFullInputBar
    val terminalInputBarHeight = if (inputBarVisible) 46.dp else 0.dp
    val terminalBottomRevealPadding = (if (showExtraKeys) 54.dp else 8.dp) + terminalInputBarHeight
    val actualImeHeightPx = imeInsets.getBottom(density)
    val effectiveImeHeightPx = with(density) {
        (customImeHeightDp?.dp ?: actualImeHeightPx.toDp()).roundToPx()
    }.coerceAtLeast(0)
    var terminalPreferencesDirty by remember(processId) { mutableStateOf(false) }
    fun markTerminalPreferencesDirty() {
        terminalPreferencesDirty = true
    }
    val terminalStatusItems = remember(settings.terminalStatusBarItems) {
        ensureTerminalStatusItems(decodeTerminalConfig(settings.terminalStatusBarItems, defaultTerminalStatusItems()))
    }
    val terminalExtraKeyItems = remember(settings.terminalExtraKeyItems) {
        decodeTerminalConfig(settings.terminalExtraKeyItems, defaultTerminalExtraKeyItems())
    }
    var editingTerminalItems by remember { mutableStateOf<String?>(null) }
    val rawInputChannel = remember(processId) { Channel<String>(Channel.UNLIMITED) }
    val renderPending = remember(processId) { AtomicBoolean(false) }
    val renderJob = remember(processId) { AtomicReference<Job?>(null) }
    val resizeRenderFallbackJob = remember(processId) { AtomicReference<Job?>(null) }
    val lastRenderAt = remember(processId) { AtomicLong(0L) }
    val keepBottomAfterNextLayout = remember(processId) { AtomicBoolean(false) }
    val lastObservedImeVisible = remember(processId) { AtomicBoolean(imeVisible) }
    val lastImeTransitionAt = remember(processId) { AtomicLong(0L) }
    val lastAppliedTerminalColumns = remember(processId) { AtomicInteger(initialTerminalColumns) }
    val lastAppliedTerminalRows = remember(processId) { AtomicInteger(initialTerminalRows) }
    val imeResizePending = remember(processId) { AtomicBoolean(false) }
    val imeViewportAnchor = remember(processId) { AtomicReference<TerminalImeViewportAnchor?>(null) }
    val imeViewportRestoreJob = remember(processId) { AtomicReference<Job?>(null) }
    val fullOutputViewportHeightPx = remember(processId) { AtomicInteger(0) }
    val activeTerminalMouseButton = remember(processId) { AtomicReference<MouseButton?>(null) }
    val currentImeVisible by rememberUpdatedState(imeVisible)
    val terminalContentHeightPx = with(density) {
        val bounds = terminalEmulator.contentBounds(includeScrollback = true)
        val contentRows = bounds.lastNonBlankRow?.plus(1) ?: 0
        contentRows * terminalCellHeightPx + terminalBottomRevealPadding.toPx().roundToInt()
    }
    val shouldAvoidIme = shouldAvoidTerminalIme(
        terminalContentHeightPx = terminalContentHeightPx,
        fullOutputViewportHeightPx = fullOutputViewportHeightPx.get(),
        effectiveImeHeightPx = effectiveImeHeightPx,
    )
    // imePadding keeps the input controls above the real IME. A custom value adds only the
    // calibration delta to the output viewport, so values smaller than the platform inset
    // remain safe and values larger than it move terminal content further upward.
    val imeOutputExtraPadding = if (imeVisible && autoScroll && shouldAvoidIme) {
        with(density) { (effectiveImeHeightPx - actualImeHeightPx).coerceAtLeast(0).toDp() }
    } else 0.dp

    fun terminalNearBottom(thresholdPx: Int = terminalCellHeightPx * 2): Boolean =
        outputScroll.maxValue <= thresholdPx || outputScroll.value >= outputScroll.maxValue - thresholdPx

    fun shouldFollowTerminalBottom(): Boolean =
        autoScroll && (imeViewportAnchor.get()?.followBottom != false)

    fun recordImeTransition(visible: Boolean) {
        if (lastObservedImeVisible.getAndSet(visible) == visible) return
        lastImeTransitionAt.set(System.currentTimeMillis())
        if (visible) {
            // Decide from the post-IME viewport, not from the old maxValue. This covers the
            // important case where content was shorter than the full viewport but becomes
            // scrollable after the IME reduces the available height.
            val avoidIme = shouldAvoidIme
            val followBottom = autoScroll && avoidIme && terminalNearBottom()
            imeViewportAnchor.set(TerminalImeViewportAnchor(
                followBottom = followBottom,
                shouldAvoidIme = avoidIme,
                offsetPx = outputScroll.value,
            ))
        }
        keepBottomAfterNextLayout.set(shouldFollowTerminalBottom() && terminalNearBottom())
    }

    fun saveTerminalViewport() {
        bgManager.saveTerminalViewportState(
            processId = processId,
            verticalOffsetPx = outputScroll.value,
            horizontalOffsetPx = horizontalScroll.value,
            autoScroll = autoScroll,
            atBottom = terminalNearBottom()
        )
    }

    fun cycleForcedTerminalColumns() {
        val currentIndex = forcedTerminalColumns?.let { TERMINAL_FORCED_COLUMN_PRESETS.indexOf(it) } ?: -1
        forcedTerminalColumns = if (currentIndex < 0) {
            TERMINAL_FORCED_COLUMN_PRESETS.first()
        } else {
            TERMINAL_FORCED_COLUMN_PRESETS.getOrNull(currentIndex + 1)
        }
    }

    fun renderTerminalFrame() {
        terminalRenderedRows = terminalEmulator.renderRows()
        terminalModeSummary = terminalEmulator.modeSummary()
        lastRenderAt.set(System.currentTimeMillis())
    }

    fun scheduleTerminalRender() {
        resizeRenderFallbackJob.getAndSet(null)?.cancel()
        renderPending.set(true)
        if (renderJob.get()?.isActive == true) return
        renderJob.set(scope.launch {
            while (renderPending.getAndSet(false)) {
                val elapsed = System.currentTimeMillis() - lastRenderAt.get()
                if (elapsed in 0 until TERMINAL_RENDER_FRAME_MS) {
                    delay(TERMINAL_RENDER_FRAME_MS - elapsed)
                }
                var syncWaited = 0L
                while (terminalEmulator.isSynchronizedOutput() && syncWaited < TERMINAL_SYNC_OUTPUT_MAX_WAIT_MS) {
                    delay(16L)
                    syncWaited += 16L
                }
                val bottomThreshold = terminalCellHeightPx * 2
                val wasNearBottom = outputScroll.maxValue <= bottomThreshold ||
                    outputScroll.value >= outputScroll.maxValue - bottomThreshold
                renderTerminalFrame()
                if (!terminalEmulator.isAlternateScreen && shouldFollowTerminalBottom() && wasNearBottom) {
                    withFrameNanos { }
                    runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
                }
            }
        })
    }

    LaunchedEffect(processId) {
        terminalEmulator.reset()
        bgManager.readInteractiveBuffer(processId)?.let { existing ->
            terminalEmulator.feed(existing)
            renderTerminalFrame()
        }
    }

    val commandHistory = remember(processId) {
        mutableStateListOf<String>().apply { addAll(bgManager.getTerminalCommandHistory(processId)) }
    }
    var historyIndex by remember(processId) { mutableIntStateOf(-1) }

    fun applyHistoryUp() {
        if (commandHistory.isEmpty()) return
        if (historyIndex == -1) {
            historyIndex = commandHistory.lastIndex
        } else if (historyIndex > 0) {
            historyIndex--
        }
        input = commandHistory.getOrElse(historyIndex) { input }
    }

    fun applyHistoryDown() {
        if (commandHistory.isEmpty()) return
        if (historyIndex in 0 until commandHistory.lastIndex) {
            historyIndex++
            input = commandHistory[historyIndex]
        } else {
            historyIndex = -1
            input = ""
        }
    }

    fun sendCommand(command: String, rememberHistory: Boolean = true) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        if (rememberHistory && commandHistory.lastOrNull() != trimmed) {
            commandHistory.add(trimmed)
            while (commandHistory.size > 50) commandHistory.removeAt(0)
            bgManager.rememberTerminalCommand(processId, trimmed)
        }
        historyIndex = -1
        scope.launch {
            val payload = terminalEmulator.wrapPaste(trimmed)
            val result = bgManager.sendInput(processId, payload, appendNewline = true)
            terminalStatus = result.exceptionOrNull()?.message ?: "已发送"
            input = ""
        }
    }

    fun submitCommand() {
        sendCommand(input, rememberHistory = true)
    }

    fun sendRaw(sequence: String) {
        if (sequence.isEmpty()) return
        if (!rawInputChannel.trySend(sequence).isSuccess) terminalStatus = "发送失败"
    }

    fun clearModifierLatches() {
        ctrlLatch = false
        altLatch = false
        shiftLatch = false
    }

    fun sendPastedText(text: String) {
        if (text.isEmpty()) return
        sendRaw(terminalEmulator.wrapPaste(text))
    }

    fun sendKey(key: Key) {
        sendRaw(terminalEmulator.sequenceFor(key, shift = shiftLatch, alt = altLatch, ctrl = ctrlLatch))
        clearModifierLatches()
    }

    fun sendPlainBackspace() {
        sendRaw(terminalEmulator.sequenceFor(Key.BACKSPACE))
    }

    fun sequenceForHardwareSpecialKey(event: KeyEvent, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
        val key = when (event.key) {
            ComposeKey.DirectionUp -> Key.UP
            ComposeKey.DirectionDown -> Key.DOWN
            ComposeKey.DirectionLeft -> Key.LEFT
            ComposeKey.DirectionRight -> Key.RIGHT
            ComposeKey.MoveHome, ComposeKey.NumPadMoveHome -> Key.HOME
            ComposeKey.MoveEnd, ComposeKey.NumPadMoveEnd -> Key.END
            ComposeKey.PageUp, ComposeKey.NumPadPageUp -> Key.PAGE_UP
            ComposeKey.PageDown, ComposeKey.NumPadPageDown -> Key.PAGE_DOWN
            ComposeKey.Insert, ComposeKey.NumPadInsert -> Key.INSERT
            ComposeKey.Delete, ComposeKey.NumPadDelete -> Key.DELETE
            ComposeKey.Backspace -> Key.BACKSPACE
            ComposeKey.Enter -> Key.ENTER
            ComposeKey.NumPadEnter -> Key.KP_ENTER
            ComposeKey.NumPad0 -> Key.KP_0
            ComposeKey.NumPad1 -> Key.KP_1
            ComposeKey.NumPad2 -> Key.KP_2
            ComposeKey.NumPad3 -> Key.KP_3
            ComposeKey.NumPad4 -> Key.KP_4
            ComposeKey.NumPad5 -> Key.KP_5
            ComposeKey.NumPad6 -> Key.KP_6
            ComposeKey.NumPad7 -> Key.KP_7
            ComposeKey.NumPad8 -> Key.KP_8
            ComposeKey.NumPad9 -> Key.KP_9
            ComposeKey.NumPadDot -> Key.KP_DECIMAL
            ComposeKey.NumPadAdd -> Key.KP_ADD
            ComposeKey.NumPadSubtract -> Key.KP_SUBTRACT
            ComposeKey.NumPadMultiply -> Key.KP_MULTIPLY
            ComposeKey.NumPadDivide -> Key.KP_DIVIDE
            ComposeKey.Tab -> Key.TAB
            ComposeKey.Escape -> Key.ESCAPE
            ComposeKey.F1 -> Key.F1
            ComposeKey.F2 -> Key.F2
            ComposeKey.F3 -> Key.F3
            ComposeKey.F4 -> Key.F4
            ComposeKey.F5 -> Key.F5
            ComposeKey.F6 -> Key.F6
            ComposeKey.F7 -> Key.F7
            ComposeKey.F8 -> Key.F8
            ComposeKey.F9 -> Key.F9
            ComposeKey.F10 -> Key.F10
            ComposeKey.F11 -> Key.F11
            ComposeKey.F12 -> Key.F12
            else -> return null
        }
        return terminalEmulator.sequenceFor(key, shift = shift, alt = alt, ctrl = ctrl)
    }

    fun handleHardwareKey(event: KeyEvent): Boolean {
        if (!rawInputMode || event.type != KeyEventType.KeyDown) return false
        if (input.isNotEmpty() && (event.key == ComposeKey.Backspace || event.key == ComposeKey.Delete)) {
            // Let BasicTextField delete local IME/input-buffer text. When the bridge buffer is
            // empty, Backspace/Delete below is sent to the TUI as a real terminal key.
            return false
        }
        val shift = event.isShiftPressed
        val alt = event.isAltPressed || altLatch
        val ctrl = event.isCtrlPressed || ctrlLatch
        val specialSequence = sequenceForHardwareSpecialKey(event, shift, alt, ctrl)
        if (specialSequence != null) {
            sendRaw(specialSequence)
            if (event.key == ComposeKey.Enter || event.key == ComposeKey.NumPadEnter) input = ""
            clearModifierLatches()
            return true
        }

        val codePoint = event.utf16CodePoint
        if (codePoint == 0) return false
        val sequence = terminalEmulator.sequenceForCodePoint(
            codePoint = codePoint,
            shift = shift,
            alt = alt,
            ctrl = ctrl
        )
        if (sequence.isEmpty()) return false
        sendRaw(sequence)
        clearModifierLatches()
        return true
    }

    fun handleInputChange(value: String) {
        if (!rawInputMode) {
            input = value
            return
        }
        val previous = input
        input = value
        when {
            value.length > previous.length && value.startsWith(previous) -> {
                val delta = value.removePrefix(previous)
                if (delta.isNotEmpty()) {
                    if (delta.length > 1 || delta.contains('\n') || delta.contains('\r')) sendPastedText(delta) else {
                        val cp = delta.codePointAt(0)
                        sendRaw(terminalEmulator.sequenceForCodePoint(cp, alt = altLatch, ctrl = ctrlLatch).ifEmpty { delta })
                        clearModifierLatches()
                    }
                }
            }
            previous.length > value.length && previous.startsWith(value) -> {
                repeat(previous.length - value.length) { sendPlainBackspace() }
            }
            value != previous -> {
                val common = previous.zip(value).takeWhile { it.first == it.second }.size
                repeat(previous.length - common) { sendPlainBackspace() }
                val delta = value.drop(common)
                if (delta.isNotEmpty()) {
                    if (delta.length > 1 || delta.contains('\n') || delta.contains('\r')) sendPastedText(delta) else {
                        val cp = delta.codePointAt(0)
                        sendRaw(terminalEmulator.sequenceForCodePoint(cp, alt = altLatch, ctrl = ctrlLatch).ifEmpty { delta })
                        clearModifierLatches()
                    }
                }
            }
        }
    }

    fun clearLocalTerminal() {
        terminalEmulator.reset()
        renderTerminalFrame()
    }

    LaunchedEffect(processId, rawInputChannel) {
        for (sequence in rawInputChannel) {
            val result = bgManager.sendInput(processId, sequence, appendNewline = false)
            result.exceptionOrNull()?.let { terminalStatus = it.message ?: "发送失败" }
        }
    }

    LaunchedEffect(processId, outputScroll) {
        snapshotFlow { Triple(outputScroll.isScrollInProgress, outputScroll.value, outputScroll.maxValue) }
            .distinctUntilChanged()
            .collect { (scrolling, value, maxValue) ->
                if (!scrolling) return@collect
                val nearBottom = maxValue <= terminalCellHeightPx * 2 || value >= maxValue - terminalCellHeightPx * 2
                if (autoScroll != nearBottom) autoScroll = nearBottom
            }
    }

    // Persist only explicit terminal-control changes. Scroll position is deliberately session-local.
    LaunchedEffect(
        processId,
        terminalPreferencesDirty,
        rawInputMode,
        autoScroll,
        showExtraKeys,
        terminalPanMode,
        showFullInputBar,
        terminalFontSizeSp,
        forcedTerminalColumns,
        maxScrollbackLines,
        fastFlingRequiredCount,
        customImeHeightDp,
    ) {
        if (!terminalPreferencesDirty) return@LaunchedEffect
        delay(800)
        settingsStore.update { current ->
            current.copy(terminalCommandPreferences = updatedTerminalCommandPreferences(
                current.terminalCommandPreferences,
                process.command,
            ) {
                it.copy(
                    rawInputMode = rawInputMode,
                    autoScroll = autoScroll,
                    showExtraKeys = showExtraKeys,
                    terminalPanMode = terminalPanMode,
                    showFullInputBar = showFullInputBar,
                    terminalFontSizeSp = terminalFontSizeSp,
                    forcedTerminalColumns = forcedTerminalColumns,
                    maxScrollbackLines = maxScrollbackLines,
                    fastFlingRequiredCount = fastFlingRequiredCount,
                    customImeHeightDp = customImeHeightDp,
                )
            })
        }
        terminalPreferencesDirty = false
    }

    LaunchedEffect(maxScrollbackLines) {
        terminalEmulator.setMaxScrollbackLines(maxScrollbackLines)
        renderTerminalFrame()
    }

    LaunchedEffect(forcedTerminalColumns, measuredTerminalColumns) {
        val cols = (forcedTerminalColumns ?: measuredTerminalColumns).coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
        if (cols != terminalColumns) terminalColumns = cols
    }

    LaunchedEffect(imeVisible, effectiveImeHeightPx) {
        recordImeTransition(imeVisible)
        if (!imeVisible) {
            // Let the inset and weighted terminal viewport settle before applying one final
            // emulator/PTY row count, rather than resizing through an intermediate height.
            pendingImeRowResizeJob.getAndSet(null)?.cancel()
            pendingImeRowResizeJob.set(scope.launch {
                delay(TERMINAL_IME_RESIZE_DEBOUNCE_MS)
                if (!currentImeVisible) {
                    val stableRows = measuredTerminalRows.get()
                    if (stableRows != terminalRows) terminalRows = stableRows
                    imeResizePending.set(false)
                }
            })
            imeViewportRestoreJob.getAndSet(null)?.cancel()
            imeViewportRestoreJob.set(scope.launch {
                delay(TERMINAL_IME_RESIZE_DEBOUNCE_MS)
                if (!currentImeVisible) {
                    withFrameNanos { }
                    imeViewportAnchor.getAndSet(null)?.let { anchor ->
                        if (anchor.followBottom && autoScroll) {
                            runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
                        } else if (!anchor.shouldAvoidIme) {
                            runCatching { outputScroll.scrollTo(anchor.offsetPx.coerceIn(0, outputScroll.maxValue)) }
                        }
                    }
                }
            })
        }
    }

    // Re-evaluate after the output viewport has been measured. Directly targeting maxValue
    // also handles maxValue changing from zero to positive when the IME first appears.
    LaunchedEffect(processId, outputScroll) {
        snapshotFlow {
            Triple(terminalRenderedRows.size, outputScroll.maxValue, imeVisible to shouldAvoidIme)
        }.distinctUntilChanged().collect { (_, _, imeState) ->
            val anchor = imeViewportAnchor.get()
            if (!imeState.first || !imeState.second || !autoScroll || anchor?.followBottom != true || !anchor.shouldAvoidIme) return@collect
            withFrameNanos { }
            if (currentImeVisible) runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
        }
    }

    LaunchedEffect(processId, terminalColumns, terminalRows) {
        val columnsChanged = terminalColumns != lastAppliedTerminalColumns.get()
        val rowsChanged = terminalRows != lastAppliedTerminalRows.get()
        val imeDrivenRowsChanged = rowsChanged && !columnsChanged && imeResizePending.get()
        if (rowsChanged) imeResizePending.set(false)

        val wasNearBottom = keepBottomAfterNextLayout.get() || terminalNearBottom()
        if (imeDrivenRowsChanged) {
            // Keep the last complete frame visible until the TUI redraws for SIGWINCH.
            // Showing the locally resized buffer first produces a blank intermediate frame.
            resizeRenderFallbackJob.getAndSet(null)?.cancel()
            renderJob.getAndSet(null)?.cancel()
            renderPending.set(false)
        }
        terminalEmulator.resize(terminalColumns, terminalRows)
        lastAppliedTerminalColumns.set(terminalColumns)
        lastAppliedTerminalRows.set(terminalRows)
        val imeStableForSizeHint = !currentImeVisible &&
            System.currentTimeMillis() - lastImeTransitionAt.get() >= TERMINAL_IME_RESIZE_DEBOUNCE_MS
        if (imeStableForSizeHint) bgManager.saveTerminalSizeHint(process.command, terminalColumns, terminalRows)

        if (imeDrivenRowsChanged) {
            resizeRenderFallbackJob.set(scope.launch {
                delay(TERMINAL_RESIZE_RENDER_FALLBACK_MS)
                renderTerminalFrame()
                if (shouldFollowTerminalBottom() && wasNearBottom) {
                    withFrameNanos { }
                    runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
                }
            })
            bgManager.resizeInteractiveSession(processId, terminalColumns, terminalRows)
        } else {
            renderTerminalFrame()
            delay(TERMINAL_PTY_RESIZE_DEBOUNCE_MS)
            bgManager.resizeInteractiveSession(processId, terminalColumns, terminalRows)
        }
        if (shouldFollowTerminalBottom() && wasNearBottom) {
            withFrameNanos { }
            runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
        }
        keepBottomAfterNextLayout.set(false)
    }

    LaunchedEffect(processId, restoredViewportState) {
        val restored = restoredViewportState ?: return@LaunchedEffect
        snapshotFlow { terminalRenderedRows.size to outputScroll.maxValue }
            .first { (renderedRows, maxValue) ->
                renderedRows > 0 && (restored.atBottom || restored.verticalOffsetPx <= 0 || maxValue > 0)
            }
        withFrameNanos { }
        if (restored.atBottom && restored.autoScroll) {
            runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
        } else {
            runCatching { outputScroll.scrollTo(restored.verticalOffsetPx.coerceIn(0, outputScroll.maxValue)) }
        }
        runCatching { horizontalScroll.scrollTo(restored.horizontalOffsetPx.coerceIn(0, horizontalScroll.maxValue)) }
    }

    LaunchedEffect(processId, outputScroll, horizontalScroll) {
        snapshotFlow { Triple(outputScroll.value, horizontalScroll.value, autoScroll) }
            .distinctUntilChanged()
            .collect { saveTerminalViewport() }
    }

    DisposableEffect(processId) {
        onDispose {
            pendingImeRowResizeJob.getAndSet(null)?.cancel()
            renderJob.getAndSet(null)?.cancel()
            resizeRenderFallbackJob.getAndSet(null)?.cancel()
            imeViewportRestoreJob.getAndSet(null)?.cancel()
            rawInputChannel.close()
            saveTerminalViewport()
        }
    }

    LaunchedEffect(processId, outputScroll) {
        snapshotFlow { Triple(outputScroll.maxValue, terminalRenderedRows.size, autoScroll) }
            .distinctUntilChanged()
            .collect { (_, _, followBottom) ->
                if (!followBottom || !shouldFollowTerminalBottom()) return@collect
                withFrameNanos { }
                runCatching { outputScroll.scrollTo(outputScroll.maxValue) }
            }
    }

    LaunchedEffect(processId) {
        bgManager.observeOutput(processId)?.collect { bytes ->
            terminalEmulator.feed(bytes)
            terminalEmulator.drainResponses().forEach { response ->
                bgManager.sendInput(processId, response, appendNewline = false)
            }
            terminalEmulator.drainClipboardRequests().forEach { clipboardText ->
                context.writeClipboardText(clipboardText)
                terminalStatus = "OSC52 已复制到剪贴板"
            }
            scheduleTerminalRender()
        }
    }

    var lastFastFlingDirection by remember(processId) { mutableIntStateOf(0) }
    var lastFastFlingAt by remember(processId) { mutableLongStateOf(0L) }
    var fastFlingCount by remember(processId) { mutableIntStateOf(0) }
    val fastFlingConnection = remember(processId, terminalPanMode, selectionMode, fastFlingRequiredCount) {
        object : NestedScrollConnection {
            override suspend fun onPreFling(available: Velocity): Velocity {
                val fastFlingEnabled = terminalPanMode && !selectionMode
                if (!fastFlingEnabled) return Velocity.Zero
                if (abs(available.y) < TERMINAL_FAST_FLING_VELOCITY_PX) return Velocity.Zero
                if (abs(available.x) > abs(available.y) * 0.8f) return Velocity.Zero
                val direction = if (available.y < 0f) 1 else -1
                val now = System.currentTimeMillis()
                fastFlingCount = if (direction == lastFastFlingDirection && now - lastFastFlingAt <= TERMINAL_FAST_FLING_WINDOW_MS) {
                    fastFlingCount + 1
                } else {
                    1
                }
                lastFastFlingDirection = direction
                lastFastFlingAt = now
                if (fastFlingCount < fastFlingRequiredCount) return Velocity.Zero

                fastFlingCount = 0
                var consumed = false
                if (direction > 0) {
                    if (outputScroll.maxValue - outputScroll.value > TERMINAL_EDGE_THRESHOLD_PX) {
                        autoScroll = true
                        outputScroll.animateScrollTo(outputScroll.maxValue)
                        saveTerminalViewport()
                        consumed = true
                    }
                } else {
                    if (outputScroll.value > TERMINAL_EDGE_THRESHOLD_PX) {
                        autoScroll = false
                        outputScroll.animateScrollTo(0)
                        saveTerminalViewport()
                        consumed = true
                    }
                }
                return if (consumed) available else Velocity.Zero
            }
        }
    }

    Surface(
        modifier = modifier,
        shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp),
        color = Color(0xFF101010)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Keep the input and extra-key bars above the real IME. The configured height
                // is a virtual calibration value used for terminal avoidance decisions.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = if (fullscreen) 4.dp else 8.dp, vertical = if (showStatusBar) 0.dp else if (fullscreen) 4.dp else 8.dp)
        ) {
            if (showStatusBar) {
                TerminalStatusBar(
                    title = process.tag ?: process.command.take(28),
                    columns = terminalColumns,
                    rows = terminalRows,
                    ptyMode = process.ptyMode,
                    terminalBackend = process.terminalBackend,
                    modeSummary = terminalModeSummary,
                    status = terminalStatus,
                    rawInputMode = rawInputMode,
                    autoScroll = autoScroll,
                    showExtraKeys = showExtraKeys,
                    terminalPanMode = terminalPanMode,
                    showFullInputBar = showFullInputBar,
                    terminalFontSizeSp = terminalFontSizeSp,
                    forcedTerminalColumns = forcedTerminalColumns,
                    maxScrollbackLines = maxScrollbackLines,
                    fastFlingRequiredCount = fastFlingRequiredCount,
                    customImeHeightDp = customImeHeightDp,
                    fullscreen = fullscreen,
                    terminalMuted = terminalMuted,
                    items = terminalStatusItems,
                    onEditItems = { editingTerminalItems = "status" },
                    onRawInputModeChange = {
                        rawInputMode = it
                        input = ""
                        markTerminalPreferencesDirty()
                    },
                    onAutoScrollChange = {
                        autoScroll = it
                        markTerminalPreferencesDirty()
                    },
                    onShowExtraKeysChange = {
                        showExtraKeys = it
                        markTerminalPreferencesDirty()
                    },
                    onTerminalPanModeChange = {
                        terminalPanMode = it
                        if (!it) selectionMode = false
                        markTerminalPreferencesDirty()
                    },
                    onShowFullInputBarChange = {
                        showFullInputBar = it
                        markTerminalPreferencesDirty()
                    },
                    onTerminalFontSizeChange = {
                        terminalFontSizeSp = it.coerceIn(9f, 22f)
                        markTerminalPreferencesDirty()
                    },
                    onCycleForcedTerminalColumns = {
                        cycleForcedTerminalColumns()
                        markTerminalPreferencesDirty()
                    },
                    onMaxScrollbackLinesClick = { terminalSettingsDialog = "scrollback" },
                    onFastFlingRequiredCountClick = { terminalSettingsDialog = "fastFling" },
                    onCustomImeHeightClick = { terminalSettingsDialog = "imeHeight" },
                    onFullscreenToggle = { onFullscreenChange(!fullscreen) },
                    onCopy = {
                        context.writeClipboardText(terminalEmulator.plainText(includeScrollback = true))
                        terminalStatus = "已复制"
                    },
                    onPaste = { sendPastedText(context.readClipboardText()) },
                    onClear = { clearLocalTerminal() },
                    onContainerManager = { showContainerManager = true }
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(terminalBackground, RoundedCornerShape(if (fullscreen) 0.dp else 8.dp))
                    .padding(horizontal = if (fullscreen) 4.dp else 6.dp, vertical = if (fullscreen) 3.dp else 5.dp)
                    .padding(bottom = imeOutputExtraPadding)
                    .onSizeChanged { size ->
                        if (!currentImeVisible || fullOutputViewportHeightPx.get() == 0) {
                            fullOutputViewportHeightPx.set(size.height)
                        }
                        if (autoScroll && terminalNearBottom()) keepBottomAfterNextLayout.set(true)
                        val measuredCols = (size.width / terminalCellWidthPx).coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
                        measuredTerminalColumns = measuredCols
                        val cols = (forcedTerminalColumns ?: measuredCols).coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
                        val rows = (size.height / terminalCellHeightPx).coerceIn(TerminalEmulator.MIN_ROWS, TerminalEmulator.MAX_ROWS)
                        if (cols != terminalColumns) terminalColumns = cols
                        if (rows != measuredTerminalRows.getAndSet(rows)) {
                            recordImeTransition(currentImeVisible)
                            pendingImeRowResizeJob.getAndSet(null)?.cancel()
                            val insideImeAnimationWindow = currentImeVisible || imeResizePending.get() ||
                                System.currentTimeMillis() - lastImeTransitionAt.get() < TERMINAL_IME_RESIZE_DEBOUNCE_MS
                            if (insideImeAnimationWindow) imeResizePending.set(true)
                            pendingImeRowResizeJob.set(scope.launch {
                                if (insideImeAnimationWindow) {
                                    delay(TERMINAL_IME_RESIZE_DEBOUNCE_MS)
                                }
                                // Do not resize the PTY while the IME is visible. The IME
                                // transition effect below applies the latest stable row count
                                // after dismissal.
                                if (!currentImeVisible) {
                                    val stableRows = measuredTerminalRows.get()
                                    if (stableRows != terminalRows) terminalRows = stableRows
                                    imeResizePending.set(false)
                                }
                            })
                        }
                    }
                    .onFocusChanged { focusState ->
                        terminalEmulator.sequenceForFocus(focusState.isFocused)?.let { sequence -> sendRaw(sequence) }
                    }
                    .focusable()
                    .pointerInput(terminalPanMode, selectionMode) {
                        if (terminalPanMode && !selectionMode) {
                            detectTapGestures(
                                onTap = {
                                    inputFocusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            )
                        }
                    }
                    .then(if (selectionMode || terminalPanMode) Modifier else Modifier.pointerInteropFilter { event ->
                        val absoluteX = event.x + horizontalScroll.value
                        val absoluteY = event.y + if (terminalEmulator.isAlternateScreen) 0 else outputScroll.value
                        val col = (absoluteX.toInt() / terminalCellWidthPx).coerceIn(0, terminalColumns - 1)
                        val row = (absoluteY.toInt() / terminalCellHeightPx).coerceIn(0, terminalRows - 1)
                        val activeButton = activeTerminalMouseButton.get()
                        val eventType = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> MouseEventType.PRESS
                            MotionEvent.ACTION_BUTTON_PRESS -> if (activeButton == null) MouseEventType.PRESS else return@pointerInteropFilter true
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> MouseEventType.RELEASE
                            MotionEvent.ACTION_BUTTON_RELEASE -> if (activeButton != null) MouseEventType.RELEASE else return@pointerInteropFilter false
                            MotionEvent.ACTION_MOVE -> if (activeButton != null || event.buttonState != 0) MouseEventType.DRAG else MouseEventType.MOVE
                            MotionEvent.ACTION_HOVER_MOVE -> MouseEventType.MOVE
                            MotionEvent.ACTION_SCROLL -> MouseEventType.WHEEL
                            else -> return@pointerInteropFilter false
                        }
                        val pressedButton = when {
                            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 || event.actionButton == MotionEvent.BUTTON_SECONDARY -> MouseButton.RIGHT
                            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 || event.actionButton == MotionEvent.BUTTON_TERTIARY -> MouseButton.MIDDLE
                            else -> MouseButton.LEFT
                        }
                        val button = when {
                            eventType == MouseEventType.WHEEL && event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0f -> MouseButton.WHEEL_DOWN
                            eventType == MouseEventType.WHEEL -> MouseButton.WHEEL_UP
                            eventType == MouseEventType.DRAG || eventType == MouseEventType.RELEASE -> activeButton ?: pressedButton
                            else -> pressedButton
                        }
                        val hadActivePress = activeButton != null
                        val sequence = terminalEmulator.sequenceForMouse(MouseEvent(row = row, column = col, button = button, type = eventType))
                        if (eventType == MouseEventType.PRESS && sequence != null) activeTerminalMouseButton.set(button)
                        if (eventType == MouseEventType.RELEASE) activeTerminalMouseButton.set(null)
                        if (sequence != null) sendRaw(sequence)
                        sequence != null || hadActivePress
                    })
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(fastFlingConnection)
                        .horizontalScroll(horizontalScroll, enabled = terminalPanMode || selectionMode)
                        // Keep a tiny manual viewport pan available in TOUCH/selection modes. In
                        // MOUSE mode, do not let Compose scroll gestures compete with xterm mouse
                        // events intended for the TUI.
                        .verticalScroll(outputScroll, enabled = terminalPanMode || selectionMode)
                ) {
                    val terminalContent: @Composable () -> Unit = {
                        if (terminalRenderedRows.isEmpty()) {
                            Text(
                                text = "等待输出...",
                                style = terminalTextStyle,
                                softWrap = false,
                                maxLines = 1
                            )
                        } else {
                            terminalRenderedRows.forEach { row ->
                                Text(
                                    text = row.text,
                                    style = terminalTextStyle,
                                    softWrap = false,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    if (selectionMode) {
                        SelectionContainer {
                            Column { terminalContent() }
                        }
                    } else {
                        terminalContent()
                    }
                    Spacer(
                        modifier = Modifier.height(
                            if (terminalEmulator.isAlternateScreen) terminalBottomRevealPadding else 8.dp
                        )
                    )
                }
            }

            if (showExtraKeys) {
                Spacer(modifier = Modifier.height(4.dp))
                TerminalExtraKeysRow(
                    terminalMuted = terminalMuted,
                    items = terminalExtraKeyItems,
                    onEditItems = { editingTerminalItems = "keys" },
                    ctrlLatch = ctrlLatch,
                    altLatch = altLatch,
                    shiftLatch = shiftLatch,
                    selectionMode = selectionMode,
                    onToggleCtrl = { ctrlLatch = !ctrlLatch },
                    onToggleAlt = { altLatch = !altLatch },
                    onToggleShift = { shiftLatch = !shiftLatch },
                    onToggleSelection = {
                        selectionMode = !selectionMode
                        if (!selectionMode) {
                            terminalPanMode = true
                            markTerminalPreferencesDirty()
                        }
                    },
                    onKeyboard = {
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    onControl = { control ->
                        val actual = if (shiftLatch && control == ControlInput.TAB) ControlInput.BACK_TAB else control
                        scope.launch { bgManager.sendControlInput(processId, actual) }
                        clearModifierLatches()
                    },
                    onKey = { key -> sendKey(key) },
                    onCopy = {
                        context.writeClipboardText(terminalEmulator.plainText(includeScrollback = true))
                        terminalStatus = "已复制"
                    },
                    onPaste = { sendPastedText(context.readClipboardText()) },
                    onClear = { clearLocalTerminal() },
                    onSelfTest = { sendCommand("tty; stty size; echo ${'$'}TERM", rememberHistory = true) },
                    onInstallCli = { sendCommand(installCliCommand, rememberHistory = true) }
                )
            }

            val submitInput = {
                if (rawInputMode) {
                    sendRaw(terminalEmulator.sequenceFor(Key.ENTER))
                    input = ""
                } else {
                    submitCommand()
                }
            }
            if (rawInputMode && !showFullInputBar) {
                TerminalHiddenInputBridge(
                    input = input,
                    focusRequester = inputFocusRequester,
                    onInputChange = { handleInputChange(it) },
                    onSubmit = submitInput,
                    onHardwareKey = { event -> handleHardwareKey(event) }
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                TerminalInputBar(
                    input = input,
                    rawInputMode = rawInputMode,
                    terminalForeground = terminalForeground,
                    terminalMuted = terminalMuted,
                    terminalBackground = terminalBackground,
                    onInputChange = { handleInputChange(it) },
                    focusRequester = inputFocusRequester,
                    onSubmit = submitInput,
                    onHardwareKey = { event -> handleHardwareKey(event) }
                )
            }
        }
    }

    if (showContainerManager) {
        ContainerManagerSheet(
            visible = showContainerManager,
            onDismiss = { showContainerManager = false },
            prootManager = prootManager,
        )
    }
    when (terminalSettingsDialog) {
        "scrollback" -> TerminalNumberSettingDialog(
            title = "终端历史行数",
            value = maxScrollbackLines,
            presets = TERMINAL_SCROLLBACK_PRESETS,
            min = TERMINAL_UI_MIN_SCROLLBACK_LINES,
            max = TerminalEmulator.MAX_SCROLLBACK_LINES,
            suffix = "行",
            onDismiss = { terminalSettingsDialog = null },
            onSave = {
                maxScrollbackLines = it
                markTerminalPreferencesDirty()
                terminalSettingsDialog = null
            }
        )
        "imeHeight" -> TerminalImeHeightSettingDialog(
            value = customImeHeightDp,
            onDismiss = { terminalSettingsDialog = null },
            onSave = {
                customImeHeightDp = it
                markTerminalPreferencesDirty()
                terminalSettingsDialog = null
            }
        )
        "fastFling" -> TerminalNumberSettingDialog(
            title = "连续快速滑动次数",
            value = fastFlingRequiredCount,
            presets = TERMINAL_FAST_FLING_PRESETS,
            min = TERMINAL_FAST_FLING_PRESETS.first(),
            max = TERMINAL_FAST_FLING_PRESETS.last(),
            suffix = "次",
            onDismiss = { terminalSettingsDialog = null },
            onSave = {
                fastFlingRequiredCount = it
                fastFlingCount = 0
                markTerminalPreferencesDirty()
                terminalSettingsDialog = null
            }
        )
    }
    when (editingTerminalItems) {
        "status" -> TerminalItemsEditorDialog(
            title = "编辑终端状态栏选项",
            items = terminalStatusItems,
            presets = TerminalStatusPresets,
            defaultItems = defaultTerminalStatusItems(),
            onDismiss = { editingTerminalItems = null },
            onSave = { items ->
                scope.launch { settingsStore.update { it.copy(terminalStatusBarItems = encodeTerminalItems(items)) } }
                editingTerminalItems = null
            }
        )
        "keys" -> TerminalItemsEditorDialog(
            title = "编辑 KEYS 选项",
            items = terminalExtraKeyItems,
            presets = TerminalExtraKeyPresets,
            defaultItems = defaultTerminalExtraKeyItems(),
            onDismiss = { editingTerminalItems = null },
            onSave = { items ->
                scope.launch { settingsStore.update { it.copy(terminalExtraKeyItems = encodeTerminalItems(items)) } }
                editingTerminalItems = null
            }
        )
    }
}

/* Terminal initial size follows measured FIT unless a session size hint exists. */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalStatusBar(
    title: String,
    columns: Int,
    rows: Int,
    ptyMode: String,
    terminalBackend: String,
    modeSummary: String,
    status: String,
    rawInputMode: Boolean,
    autoScroll: Boolean,
    showExtraKeys: Boolean,
    terminalPanMode: Boolean,
    showFullInputBar: Boolean,
    terminalFontSizeSp: Float,
    forcedTerminalColumns: Int?,
    maxScrollbackLines: Int,
    fastFlingRequiredCount: Int,
    customImeHeightDp: Int?,
    fullscreen: Boolean,
    terminalMuted: Color,
    items: List<TerminalItemConfig>,
    onEditItems: () -> Unit,
    onRawInputModeChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onShowExtraKeysChange: (Boolean) -> Unit,
    onTerminalPanModeChange: (Boolean) -> Unit,
    onShowFullInputBarChange: (Boolean) -> Unit,
    onTerminalFontSizeChange: (Float) -> Unit,
    onCycleForcedTerminalColumns: () -> Unit,
    onMaxScrollbackLinesClick: () -> Unit,
    onFastFlingRequiredCountClick: () -> Unit,
    onCustomImeHeightClick: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onContainerManager: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .background(Color(0xFF151515), RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = buildString {
                append(title.ifBlank { "terminal" })
                append(" · ${columns}x${rows}")
                append(" · ${ptyMode.uppercase()}")
                if (terminalBackend.isNotBlank()) append(" · $terminalBackend")
                if (modeSummary.isNotBlank()) append(" · ${modeSummary.replace("BRACKETED-PASTE", "BP")}")
                if (status.isNotBlank()) append(" · $status")
            },
            color = terminalMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(160.dp)
                .combinedClickable(onClick = {}, onLongClick = onEditItems)
        )
        items.forEach { item ->
            when (item.id) {
                "RAW" -> TerminalStatusKey(if (rawInputMode) "RAW" else "LINE", rawInputMode, onLongClick = onEditItems) { onRawInputModeChange(!rawInputMode) }
                "AUTO" -> TerminalStatusKey(if (autoScroll) "AUTO" else "LOCK", autoScroll, onLongClick = onEditItems) { onAutoScrollChange(!autoScroll) }
                "COLS" -> TerminalStatusKey(forcedTerminalColumns?.let { "${it}C" } ?: "FIT", forcedTerminalColumns != null, onLongClick = onEditItems) { onCycleForcedTerminalColumns() }
                "HIST" -> TerminalStatusKey(terminalScrollbackLabel(maxScrollbackLines), onLongClick = onEditItems, onClick = onMaxScrollbackLinesClick)
                "JUMP" -> TerminalStatusKey("J${fastFlingRequiredCount}", onLongClick = onEditItems, onClick = onFastFlingRequiredCountClick)
                "IME" -> TerminalStatusKey(terminalImeHeightLabel(customImeHeightDp), highlight = customImeHeightDp != null, onLongClick = onEditItems, onClick = onCustomImeHeightClick)
                "KEYS" -> TerminalStatusKey("KEYS", showExtraKeys, onLongClick = onEditItems) { onShowExtraKeysChange(!showExtraKeys) }
                "TOUCH" -> TerminalStatusKey(if (terminalPanMode) "TOUCH" else "MOUSE", !terminalPanMode, onLongClick = onEditItems) { onTerminalPanModeChange(!terminalPanMode) }
                "INPUT" -> TerminalStatusKey(if (showFullInputBar) "INPUT" else "HIDE", showFullInputBar, onLongClick = onEditItems) { onShowFullInputBarChange(!showFullInputBar) }
                "A-" -> TerminalStatusKey("A-", onLongClick = onEditItems, onClick = { onTerminalFontSizeChange(terminalFontSizeSp - 1f) })
                "A+" -> TerminalStatusKey("A+", onLongClick = onEditItems, onClick = { onTerminalFontSizeChange(terminalFontSizeSp + 1f) })
                "COPY" -> TerminalStatusKey("COPY", onLongClick = onEditItems, onClick = onCopy)
                "PASTE" -> TerminalStatusKey("PASTE", onLongClick = onEditItems, onClick = onPaste)
                "CLR" -> TerminalStatusKey("CLR", onLongClick = onEditItems, onClick = onClear)
                "CTN" -> TerminalStatusKey("CTN", onLongClick = onEditItems, onClick = onContainerManager)
                "FULL" -> TerminalStatusKey(if (fullscreen) "EXIT" else "FULL", highlight = true, onLongClick = onEditItems, onClick = onFullscreenToggle)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalExtraKeysRow(
    terminalMuted: Color,
    items: List<TerminalItemConfig>,
    onEditItems: () -> Unit,
    ctrlLatch: Boolean,
    altLatch: Boolean,
    shiftLatch: Boolean,
    selectionMode: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleShift: () -> Unit,
    onToggleSelection: () -> Unit,
    onKeyboard: () -> Unit,
    onControl: (ControlInput) -> Unit,
    onKey: (Key) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onSelfTest: () -> Unit,
    onInstallCli: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(Color(0xFF171717), RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "KEYS",
            color = terminalMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onEditItems)
        )
        items.forEach { item ->
            when (item.id) {
                "CTRL" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), highlight = ctrlLatch, onLongClick = onEditItems) { onToggleCtrl() }
                "ALT" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), highlight = altLatch, onLongClick = onEditItems) { onToggleAlt() }
                "SHIFT" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), highlight = shiftLatch, onLongClick = onEditItems) { onToggleShift() }
                "SEL" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), highlight = selectionMode, onLongClick = onEditItems) { onToggleSelection() }
                "KBD" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKeyboard() }
                "ESC" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.ESC) }
                "TAB" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.TAB) }
                "S-TAB" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.BACK_TAB) }
                "UP" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.UP) }
                "DOWN" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.DOWN) }
                "LEFT" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.LEFT) }
                "RIGHT" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.RIGHT) }
                "HOME" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.HOME) }
                "END" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.END) }
                "PGUP" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.PAGE_UP) }
                "PGDN" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.PAGE_DOWN) }
                "BKSP" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.BACKSPACE) }
                "DEL" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onKey(Key.DELETE) }
                "ENTER" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), highlight = true, onLongClick = onEditItems) { onControl(ControlInput.ENTER) }
                "C-C" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_C) }
                "C-D" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_D) }
                "C-Z" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_Z) }
                "C-L" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_L) }
                "C-U" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_U) }
                "C-W" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_W) }
                "C-A" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_A) }
                "C-E" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_E) }
                "C-R" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onControl(ControlInput.CTRL_R) }
                "COPY" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onCopy() }
                "PASTE" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onPaste() }
                "CLEAR" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onClear() }
                "TEST" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onSelfTest() }
                "CLI" -> TerminalKey(labelForTerminalItem(item, TerminalExtraKeyPresets), onLongClick = onEditItems) { onInstallCli() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalStatusKey(
    label: String,
    highlight: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(5.dp),
        color = if (highlight) Color(0xFF1B5E20) else Color(0xFF252525),
        contentColor = Color(0xFFE0E0E0)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TerminalToggleKey(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TerminalKey(label = label, highlight = selected, onClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalKey(
    label: String,
    highlight: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(6.dp),
        color = if (highlight) Color(0xFF1B5E20) else Color(0xFF252525),
        contentColor = Color(0xFFE0E0E0)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun TerminalHiddenInputBridge(
    input: String,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHardwareKey: (KeyEvent) -> Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        var fieldValue by remember { mutableStateOf(TextFieldValue(input, selection = TextRange(input.length))) }
        LaunchedEffect(input) {
            if (fieldValue.composition == null && fieldValue.text != input) {
                fieldValue = TextFieldValue(input, selection = TextRange(input.length))
            }
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { value ->
                fieldValue = value
                if (value.composition == null) onInputChange(value.text)
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event -> onHardwareKey(event) },
            singleLine = true,
            textStyle = TextStyle(
                color = Color.Transparent,
                fontFamily = FontFamily.Monospace,
                fontSize = 1.sp
            ),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        )
    }
}

@Composable
private fun TerminalInputBar(
    input: String,
    rawInputMode: Boolean,
    terminalForeground: Color,
    terminalMuted: Color,
    terminalBackground: Color,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHardwareKey: (KeyEvent) -> Boolean
) {
    val promptColor = if (rawInputMode) Color(0xFFFFB74D) else Color(0xFF64B5F6)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color(0xFF111111), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (rawInputMode) "»" else "$",
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            color = promptColor,
            modifier = Modifier.width(16.dp),
            textAlign = TextAlign.Center
        )

        var fieldValue by remember(rawInputMode) { mutableStateOf(TextFieldValue(input, selection = TextRange(input.length))) }
        LaunchedEffect(input, rawInputMode) {
            if (fieldValue.composition == null && fieldValue.text != input) {
                fieldValue = TextFieldValue(input, selection = TextRange(input.length))
            }
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { value ->
                fieldValue = value
                if (value.composition == null) {
                    onInputChange(value.text)
                }
            },
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .focusRequester(focusRequester)
                .background(terminalBackground, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .onPreviewKeyEvent { event -> onHardwareKey(event) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = terminalForeground,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(terminalForeground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = if (rawInputMode) "逐字输入 / 粘贴后点 ↵" else "输入命令…",
                            color = terminalMuted.copy(alpha = 0.55f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}


private fun labelForTerminalItem(id: String, presets: List<TerminalActionPreset>): String =
    presets.firstOrNull { it.id == id }?.label ?: id

private fun labelForTerminalItem(item: TerminalItemConfig, presets: List<TerminalActionPreset>): String =
    item.label?.takeIf { it.isNotBlank() } ?: labelForTerminalItem(item.id, presets)

@Composable
private fun TerminalNumberSettingDialog(
    title: String,
    value: Int,
    presets: List<Int>,
    min: Int,
    max: Int,
    suffix: String,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value.toString()) }
    val parsed = input.toIntOrNull()?.coerceIn(min, max)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("范围：$min～$max$suffix", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        TerminalKey("$preset$suffix", highlight = parsed == preset) { input = preset.toString() }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(6) },
                    label = { Text("数值") },
                    supportingText = {
                        if (input.isNotBlank() && input.toIntOrNull() == null) Text("请输入有效数字")
                        else if (input.toIntOrNull()?.let { it !in min..max } == true) Text("将限制在范围内")
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TerminalImeHeightSettingDialog(
    value: Int?,
    onDismiss: () -> Unit,
    onSave: (Int?) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val parsed = input.toIntOrNull()?.coerceIn(TERMINAL_IME_HEIGHT_MIN_DP, TERMINAL_IME_HEIGHT_MAX_DP)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("IME 避让高度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("自动适配或手动设置 IME 高度（$TERMINAL_IME_HEIGHT_MIN_DP～$TERMINAL_IME_HEIGHT_MAX_DP dp）。", style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TerminalKey("自动", highlight = value == null && input.isBlank()) { input = "" }
                    TERMINAL_IME_HEIGHT_PRESETS.forEach { preset ->
                        TerminalKey("${preset}dp", highlight = parsed == preset) { input = preset.toString() }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text("高度 dp") },
                    supportingText = {
                        if (input.isNotBlank() && input.toIntOrNull() == null) Text("请输入有效数字")
                        else if (input.toIntOrNull()?.let { it !in TERMINAL_IME_HEIGHT_MIN_DP..TERMINAL_IME_HEIGHT_MAX_DP } == true) Text("将限制在范围内")
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(if (input.isBlank()) null else parsed) }, enabled = input.isBlank() || parsed != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TerminalItemsEditorDialog(
    title: String,
    items: List<TerminalItemConfig>,
    presets: List<TerminalActionPreset>,
    defaultItems: List<TerminalItemConfig>,
    onDismiss: () -> Unit,
    onSave: (List<TerminalItemConfig>) -> Unit,
) {
    val working = remember(items) { mutableStateListOf<TerminalItemConfig>().apply { addAll(items) } }

    fun moveItem(from: Int, orderText: String) {
        val to = orderText.toIntOrNull()?.minus(1) ?: return
        if (from !in working.indices || to !in working.indices || from == to) return
        val item = working.removeAt(from)
        working.add(to, item)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "长按状态栏/KEYS 任意项进入本页。序号直接改为 1..${working.size.coerceAtLeast(1)} 可排序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                working.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = (index + 1).toString(),
                            onValueChange = { moveItem(index, it) },
                            modifier = Modifier.width(72.dp),
                            label = { Text("序号") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = item.label.orEmpty(),
                            onValueChange = { value ->
                                if (index in working.indices) working[index] = working[index].copy(label = value.take(16))
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            placeholder = { Text("显示名") },
                            singleLine = true
                        )
                        Text(
                            text = labelForTerminalItem(item.id, presets),
                            modifier = Modifier.width(64.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { if (index in working.indices) working.removeAt(index) }) {
                            Text("删除")
                        }
                    }
                }
                Text("新增预设", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.filter { preset -> working.none { it.id == preset.id } }.forEach { preset ->
                        TerminalKey(preset.label) { working.add(TerminalItemConfig(preset.id)) }
                    }
                }
                TextButton(onClick = {
                    working.clear()
                    working.addAll(defaultItems)
                }) { Text("恢复默认") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(working.toList()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CreateSessionDialog(
    settingsStore: SettingsStore,
    quickCommandsConfig: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val defaultQuickCommands = remember { defaultTerminalQuickCommands() }
    val quickCommands = remember(quickCommandsConfig) {
        mutableStateListOf<TerminalQuickCommandConfig>().apply {
            addAll(decodeTerminalConfig(quickCommandsConfig, defaultQuickCommands))
        }
    }
    var command by remember(quickCommandsConfig) {
        mutableStateOf(quickCommands.firstOrNull()?.command ?: "rikkahub-tmux")
    }
    var editingQuickCommandIndex by remember { mutableIntStateOf(-2) }
    var editingQuickCommandName by remember { mutableStateOf("") }
    var editingQuickCommandCommand by remember { mutableStateOf("") }
    var editingQuickCommandOrder by remember { mutableStateOf("") }

    fun persistQuickCommands() {
        val encoded = encodeTerminalQuickCommands(quickCommands.toList())
        scope.launch { settingsStore.update { it.copy(terminalQuickCommands = encoded) } }
    }

    fun startEditQuickCommand(index: Int) {
        val item = quickCommands.getOrNull(index) ?: return
        editingQuickCommandIndex = index
        editingQuickCommandName = item.name
        editingQuickCommandCommand = item.command
        editingQuickCommandOrder = (index + 1).toString()
    }

    fun startAddQuickCommand() {
        editingQuickCommandIndex = -1
        editingQuickCommandName = command.take(18).ifBlank { "custom" }
        editingQuickCommandCommand = command
        editingQuickCommandOrder = (quickCommands.size + 1).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建交互会话") },
        text = {
            Column {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("命令") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickCommands.forEachIndexed { index, item ->
                        Surface(
                            modifier = Modifier.combinedClickable(
                                onClick = { command = item.command },
                                onLongClick = { startEditQuickCommand(index) }
                            ),
                            shape = RoundedCornerShape(8.dp),
                            color = if (command == item.command) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (command == item.command) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = item.name,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { startAddQuickCommand() }) { Text("新增快捷命令") }
                    TextButton(onClick = {
                        quickCommands.clear()
                        quickCommands.addAll(defaultQuickCommands)
                        command = quickCommands.firstOrNull()?.command ?: command
                        persistQuickCommands()
                    }) { Text("恢复默认排序") }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "长按快捷命令可编辑名称、实际命令、排序序号。基础工具运行 terminal-tools；AI CLI 较重，按需运行 ai-cli；native addon 运行 build-tools；诊断运行 doctor。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(command) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (editingQuickCommandIndex != -2) {
        AlertDialog(
            onDismissRequest = { editingQuickCommandIndex = -2 },
            title = { Text(if (editingQuickCommandIndex >= 0) "编辑快捷命令" else "新增快捷命令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editingQuickCommandName,
                        onValueChange = { editingQuickCommandName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("显示名称") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editingQuickCommandCommand,
                        onValueChange = { editingQuickCommandCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("实际命令") },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3
                    )
                    OutlinedTextField(
                        value = editingQuickCommandOrder,
                        onValueChange = { editingQuickCommandOrder = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("排序序号") },
                        singleLine = true
                    )
                    if (editingQuickCommandIndex >= 0) {
                        TextButton(
                            onClick = {
                                quickCommands.removeAt(editingQuickCommandIndex)
                                persistQuickCommands()
                                editingQuickCommandIndex = -2
                            }
                        ) { Text("删除") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = editingQuickCommandName.trim()
                    val value = editingQuickCommandCommand.trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) {
                        val insertMax = quickCommands.size + if (editingQuickCommandIndex >= 0) 0 else 1
                        val target = (editingQuickCommandOrder.toIntOrNull() ?: insertMax).coerceIn(1, insertMax) - 1
                        if (editingQuickCommandIndex >= 0) quickCommands.removeAt(editingQuickCommandIndex)
                        quickCommands.add(target.coerceIn(0, quickCommands.size), TerminalQuickCommandConfig(name, value))
                        command = value
                        persistQuickCommands()
                    }
                    editingQuickCommandIndex = -2
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingQuickCommandIndex = -2 }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LogsDialog(
    processId: String,
    bgManager: BackgroundProcessManager,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("加载中...") }

    LaunchedEffect(processId) {
        val result = bgManager.readProcessLogs(
            processId = processId,
            stream = "stdout",
            offset = 0,
            limit = 400
        )
        text = if (result.error != null) {
            "读取失败：${result.error}"
        } else {
            sanitizeTerminalLogText(result.lines.joinToString("\n"))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("进程日志") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = text.ifBlank { "(空)" },
                    color = Color(0xFF00E676),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun sanitizeTerminalLogText(raw: String): String {
    val terminal = TerminalEmulator(initialColumns = 120, initialRows = 40)
    terminal.feed(raw)
    return terminal.plainText(includeScrollback = true)
        .replace(Regex("""\u001B\][^\u0007]*(\u0007|\u001B\\)"""), "")
        .replace(Regex("""\u001B\[[0-?]*[ -/]*[@-~]"""), "")
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
