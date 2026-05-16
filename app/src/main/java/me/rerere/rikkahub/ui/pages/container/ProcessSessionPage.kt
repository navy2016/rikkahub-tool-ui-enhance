package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.BackgroundProcessInfo
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.ControlInput
import me.rerere.rikkahub.data.container.ProcessStatus
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalEmulator.Key
import me.rerere.rikkahub.utils.TerminalEmulator.MouseButton
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEvent
import me.rerere.rikkahub.utils.TerminalEmulator.MouseEventType
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
import android.view.MotionEvent
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSessionPage(sandboxId: String) {
    val bgManager = koinInject<BackgroundProcessManager>()
    val processStates by bgManager.processStates.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val sandboxProcesses = processStates
        .filter { it.sandboxId == sandboxId }
        .sortedByDescending { it.createdAt }

    var activeInteractiveId by remember { mutableStateOf<String?>(null) }
    var terminalFullscreen by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogsFor by remember { mutableStateOf<String?>(null) }

    val activeInteractiveProcess = sandboxProcesses.firstOrNull {
        it.processId == activeInteractiveId && it.isInteractive
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
                    IconButton(onClick = { showCreateDialog = true }) {
                        Text("+", fontSize = 20.sp)
                    }
                }
            )
        }
    ) { padding ->
        if (terminalFullscreen && activeInteractiveProcess != null) {
            TerminalInteractivePanel(
                processId = activeInteractiveProcess.processId,
                bgManager = bgManager,
                fullscreen = true,
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
                            processId = process.processId,
                            bgManager = bgManager,
                            fullscreen = false,
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
            onDismiss = { showCreateDialog = false },
            onCreate = { command ->
                scope.launch {
                    val result = bgManager.startInteractiveSession(
                        sandboxId = sandboxId,
                        command = command,
                        preferTty = true,
                        columns = 120,
                        rows = 40
                    )
                    if (result.success) {
                        activeInteractiveId = result.processId
                        terminalFullscreen = true
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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TerminalInteractivePanel(
    processId: String,
    bgManager: BackgroundProcessManager,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val outputScroll = rememberScrollState()

    val terminalEmulator = remember(processId) { TerminalEmulator(initialColumns = 80, initialRows = 24) }
    val terminalBackground = Color(0xFF101010)
    val terminalForeground = Color(0xFF00E676)
    val terminalMuted = Color(0xFFB0BEC5)
    val installCliCommand = remember {
        "rikkahub-install-cli || (printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > /etc/resolv.conf; " +
            "printf 'https://dl-cdn.alpinelinux.org/alpine/v3.19/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.19/community\n' > /etc/apk/repositories; " +
            "apk update && apk add --no-cache vim nano util-linux nodejs npm bash ca-certificates curl git openssh-client tmux && " +
            "npm config set prefix /usr/local && npm config set cache /tmp/npm-cache && " +
            "npm install -g @anthropic-ai/claude-code @openai/codex opencode-ai)"
    }
    var input by remember { mutableStateOf("") }
    var terminalText by remember { mutableStateOf(terminalEmulator.render()) }
    var terminalModeSummary by remember { mutableStateOf(terminalEmulator.modeSummary()) }
    var terminalStatus by remember { mutableStateOf("就绪") }
    var autoScroll by remember { mutableStateOf(true) }
    var rawInputMode by remember { mutableStateOf(false) }
    var terminalColumns by remember { mutableIntStateOf(80) }
    var terminalRows by remember { mutableIntStateOf(24) }
    var terminalCellWidthPx by remember { mutableIntStateOf(7) }
    var terminalCellHeightPx by remember { mutableIntStateOf(14) }

    LaunchedEffect(processId) {
        terminalEmulator.reset()
        bgManager.readInteractiveBuffer(processId)?.let { existing ->
            terminalEmulator.feed(existing)
            terminalText = terminalEmulator.render()
            terminalModeSummary = terminalEmulator.modeSummary()
        }
    }

    val commandHistory = remember { mutableStateListOf<String>() }
    var historyIndex by remember { mutableIntStateOf(-1) }

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
        scope.launch {
            val result = bgManager.sendInput(processId, sequence, appendNewline = false)
            result.exceptionOrNull()?.let { terminalStatus = it.message ?: "发送失败" }
        }
    }

    fun sendPastedText(text: String) {
        if (text.isEmpty()) return
        sendRaw(terminalEmulator.wrapPaste(text))
    }

    fun sendKey(key: Key) {
        sendRaw(terminalEmulator.sequenceFor(key))
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
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed
        val specialSequence = sequenceForHardwareSpecialKey(event, shift, alt, ctrl)
        if (specialSequence != null) {
            sendRaw(specialSequence)
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
                    if (delta.length > 1 || delta.contains('\n') || delta.contains('\r')) sendPastedText(delta) else sendRaw(delta)
                }
            }
            previous.length > value.length && previous.startsWith(value) -> {
                repeat(previous.length - value.length) { sendKey(Key.DELETE) }
            }
            value != previous -> {
                val common = previous.zip(value).takeWhile { it.first == it.second }.size
                repeat(previous.length - common) { sendKey(Key.DELETE) }
                val delta = value.drop(common)
                if (delta.isNotEmpty()) {
                    if (delta.length > 1 || delta.contains('\n') || delta.contains('\r')) sendPastedText(delta) else sendRaw(delta)
                }
            }
        }
    }

    fun clearLocalTerminal() {
        terminalEmulator.reset()
        terminalText = terminalEmulator.render()
        terminalModeSummary = terminalEmulator.modeSummary()
    }

    LaunchedEffect(processId, terminalColumns, terminalRows) {
        terminalEmulator.resize(terminalColumns, terminalRows)
        terminalText = terminalEmulator.render()
        terminalModeSummary = terminalEmulator.modeSummary()
        kotlinx.coroutines.delay(180)
        bgManager.resizeInteractiveSession(processId, terminalColumns, terminalRows)
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
            terminalText = terminalEmulator.render()
            terminalModeSummary = terminalEmulator.modeSummary()
            if (autoScroll) {
                outputScroll.scrollTo(outputScroll.maxValue)
            }
        }
    }

    Card(
        modifier = modifier,
        shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1B1B1B)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (fullscreen) 6.dp else 10.dp)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append("终端 · ${terminalColumns}x${terminalRows}")
                        if (terminalModeSummary.isNotBlank()) append(" · $terminalModeSummary")
                        if (terminalStatus.isNotBlank()) append(" · $terminalStatus")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = terminalMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onFullscreenChange(!fullscreen) }) {
                        Text(if (fullscreen) "退出全屏" else "全屏")
                    }
                    if (!fullscreen) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "自动滚动",
                            style = MaterialTheme.typography.labelSmall,
                            color = terminalMuted
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = autoScroll,
                            onCheckedChange = { autoScroll = it }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "逐字输入",
                            style = MaterialTheme.typography.labelSmall,
                            color = terminalMuted
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = rawInputMode,
                            onCheckedChange = {
                                rawInputMode = it
                                input = ""
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (fullscreen) 4.dp else 8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fullscreen) Modifier.weight(1f) else Modifier.height(300.dp))
                    .background(terminalBackground, if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(8.dp))
                    .padding(if (fullscreen) 6.dp else 10.dp)
                    .onSizeChanged { size ->
                        terminalCellWidthPx = 7
                        terminalCellHeightPx = 14
                        val cols = (size.width / terminalCellWidthPx).coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
                        val rows = (size.height / terminalCellHeightPx).coerceIn(TerminalEmulator.MIN_ROWS, TerminalEmulator.MAX_ROWS)
                        if (cols != terminalColumns) terminalColumns = cols
                        if (rows != terminalRows) terminalRows = rows
                    }
                    .onFocusChanged { focusState ->
                        terminalEmulator.sequenceForFocus(focusState.isFocused)?.let { sequence -> sendRaw(sequence) }
                    }
                    .focusable()
                    .pointerInteropFilter { event ->
                        val col = (event.x.toInt() / terminalCellWidthPx).coerceIn(0, terminalColumns - 1)
                        val row = (event.y.toInt() / terminalCellHeightPx).coerceIn(0, terminalRows - 1)
                        val eventType = when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> MouseEventType.PRESS
                            MotionEvent.ACTION_UP -> MouseEventType.RELEASE
                            MotionEvent.ACTION_MOVE -> if (event.buttonState != 0) MouseEventType.DRAG else MouseEventType.MOVE
                            MotionEvent.ACTION_SCROLL -> MouseEventType.WHEEL
                            else -> return@pointerInteropFilter true
                        }
                        val button = when {
                            eventType == MouseEventType.WHEEL && event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0f -> MouseButton.WHEEL_DOWN
                            eventType == MouseEventType.WHEEL -> MouseButton.WHEEL_UP
                            eventType == MouseEventType.RELEASE -> MouseButton.RELEASE
                            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> MouseButton.RIGHT
                            event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> MouseButton.MIDDLE
                            else -> MouseButton.LEFT
                        }
                        val sequence = terminalEmulator.sequenceForMouse(MouseEvent(row = row, column = col, button = button, type = eventType))
                            ?: return@pointerInteropFilter false
                        sendRaw(sequence)
                        true
                    }
                    .verticalScroll(outputScroll)
            ) {
                Column {
                    Spacer(modifier = Modifier.height(if (fullscreen) 2.dp else 8.dp))
                    Text(
                        text = if (terminalText.text.isEmpty()) AnnotatedString("等待输出...") else terminalText,
                        color = terminalForeground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(if (fullscreen) 28.dp else 12.dp))
                }
            }

            if (!fullscreen) {
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ControlChip("Ctrl+C") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_C) } }
                    ControlChip("Ctrl+D") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_D) } }
                    ControlChip("Ctrl+Z") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_Z) } }
                    ControlChip("Ctrl+L") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_L) } }
                    ControlChip("Ctrl+U") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_U) } }
                    ControlChip("Ctrl+W") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_W) } }
                    ControlChip("Tab") { scope.launch { bgManager.sendControlInput(processId, ControlInput.TAB) } }
                    ControlChip("Shift+Tab") { scope.launch { bgManager.sendControlInput(processId, ControlInput.BACK_TAB) } }
                    ControlChip("Esc") { scope.launch { bgManager.sendControlInput(processId, ControlInput.ESC) } }
                    ControlChip("Enter") { scope.launch { bgManager.sendControlInput(processId, ControlInput.ENTER) } }
                    ControlChip("Backspace") { scope.launch { bgManager.sendControlInput(processId, ControlInput.BACKSPACE) } }
                    ControlChip("Shell↑") { sendKey(Key.UP) }
                    ControlChip("Shell↓") { sendKey(Key.DOWN) }
                    ControlChip("Shell←") { sendKey(Key.LEFT) }
                    ControlChip("Shell→") { sendKey(Key.RIGHT) }
                    ControlChip("Home") { sendKey(Key.HOME) }
                    ControlChip("End") { sendKey(Key.END) }
                    ControlChip("Ctrl+A") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_A) } }
                    ControlChip("Ctrl+E") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_E) } }
                    ControlChip("Ctrl+R") { scope.launch { bgManager.sendControlInput(processId, ControlInput.CTRL_R) } }
                    ControlChip("PgUp") { sendKey(Key.PAGE_UP) }
                    ControlChip("PgDn") { sendKey(Key.PAGE_DOWN) }
                    ControlChip("Ins") { sendKey(Key.INSERT) }
                    ControlChip("Del") { sendKey(Key.DELETE) }
                    ControlChip("F1") { sendKey(Key.F1) }
                    ControlChip("F2") { sendKey(Key.F2) }
                    ControlChip("F3") { sendKey(Key.F3) }
                    ControlChip("F4") { sendKey(Key.F4) }
                    ControlChip("本地清屏") { clearLocalTerminal() }
                    ControlChip("复制输出") {
                        context.writeClipboardText(terminalEmulator.plainText(includeScrollback = true))
                    }
                    ControlChip("粘贴") {
                        sendPastedText(context.readClipboardText())
                    }
                    ControlChip("PTY自检") {
                        sendCommand("tty; stty size; echo ${'$'}TERM", rememberHistory = true)
                    }
                    ControlChip("安装CLI") {
                        sendCommand(installCliCommand, rememberHistory = true)
                    }
                    ControlChip("↑历史") { applyHistoryUp() }
                    ControlChip("↓历史") { applyHistoryDown() }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "提示：默认进入 tmux；安装失败先点/运行 rikkahub-fix-apk；TUI 使用逐字输入 + 方向键/ESC 控制。",
                    style = MaterialTheme.typography.labelSmall,
                    color = terminalMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { handleInputChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event -> handleHardwareKey(event) },
                singleLine = true,
                prefix = {
                    Text(
                        text = "$",
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                label = { Text(if (rawInputMode) "逐字输入（适合 vim/nano/TUI）" else "终端输入") },
                placeholder = { Text(if (rawInputMode) "输入会立即发送；用 Esc/Ctrl+C/方向键按钮控制" else "例如：rikkahub-tmux 或 npm install -g @anthropic-ai/claude-code") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { submitCommand() }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = terminalForeground,
                    unfocusedTextColor = terminalForeground,
                    disabledTextColor = terminalMuted,
                    cursorColor = terminalForeground,
                    focusedContainerColor = terminalBackground,
                    unfocusedContainerColor = terminalBackground,
                    focusedBorderColor = terminalForeground,
                    unfocusedBorderColor = Color(0xFF455A64),
                    focusedLabelColor = terminalForeground,
                    unfocusedLabelColor = terminalMuted,
                    focusedPlaceholderColor = Color(0xFF78909C),
                    unfocusedPlaceholderColor = Color(0xFF78909C)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { applyHistoryUp() }) {
                        Text("上一条")
                    }
                    TextButton(onClick = { applyHistoryDown() }) {
                        Text("下一条")
                    }
                }

                Button(
                    onClick = {
                        if (rawInputMode) {
                            scope.launch { bgManager.sendControlInput(processId, ControlInput.ENTER) }
                            input = ""
                        } else {
                            submitCommand()
                        }
                    }
                ) {
                    Text(if (rawInputMode) "发送 Enter" else "回车执行")
                }
            }
        }
    }
}

@Composable
private fun ControlChip(
    text: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace
            )
        }
    )
}

@Composable
private fun CreateSessionDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var command by remember { mutableStateOf("rikkahub-tmux") }
    val quickCommands = listOf(
        "rikkahub-tmux",
        "tmux",
        "bash",
        "vim",
        "nano",
        "claude",
        "codex",
        "opencode",
        "rikkahub-fix-apk",
        "rikkahub-install-cli",
        "tty; stty size; echo ${'$'}TERM"
    )

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
                    quickCommands.forEach { item ->
                        FilterChip(
                            selected = command == item,
                            onClick = { command = item },
                            label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "CLI/TUI 建议 TTY 模式。安装：apk add vim nano util-linux nodejs npm；npm install -g @anthropic-ai/claude-code @openai/codex opencode-ai",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(command) }) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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
            result.lines.joinToString("\n")
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
