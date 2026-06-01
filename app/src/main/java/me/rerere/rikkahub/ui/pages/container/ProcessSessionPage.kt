package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
    var showTerminalStatusBar by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showLogsFor by remember { mutableStateOf<String?>(null) }

    val activeInteractiveProcess = sandboxProcesses.firstOrNull {
        it.processId == activeInteractiveId && it.isInteractive
    }
    val terminalFullscreenActive = terminalFullscreen && activeInteractiveProcess != null

    Scaffold(
        topBar = {
            if (!terminalFullscreenActive) TopAppBar(
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
                        TextButton(onClick = { showTerminalStatusBar = !showTerminalStatusBar }) {
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
        if (terminalFullscreenActive && activeInteractiveProcess != null) {
            TerminalInteractivePanel(
                process = activeInteractiveProcess,
                bgManager = bgManager,
                fullscreen = true,
                showStatusBar = showTerminalStatusBar,
                onFullscreenChange = { terminalFullscreen = it },
                modifier = Modifier.fillMaxSize()
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
    val outputScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val textMeasurer = rememberTextMeasurer()
    val processId = process.processId

    val terminalEmulator = remember(processId) { TerminalEmulator(initialColumns = 80, initialRows = 24) }
    val terminalBackground = Color(0xFF101010)
    val terminalForeground = Color(0xFF00E676)
    val terminalMuted = Color(0xFFB0BEC5)
    val terminalTextStyle = TextStyle(
        color = terminalForeground,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 15.sp
    )
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
    var rawInputMode by remember(processId) { mutableStateOf(isTuiCommand(process.command)) }
    var showExtraKeys by remember(fullscreen) { mutableStateOf(!fullscreen) }
    var selectionMode by remember { mutableStateOf(false) }
    var ctrlLatch by remember { mutableStateOf(false) }
    var altLatch by remember { mutableStateOf(false) }
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

    fun clearModifierLatches() {
        ctrlLatch = false
        altLatch = false
    }

    fun sendPastedText(text: String) {
        if (text.isEmpty()) return
        sendRaw(terminalEmulator.wrapPaste(text))
    }

    fun sendKey(key: Key) {
        sendRaw(terminalEmulator.sequenceFor(key, alt = altLatch, ctrl = ctrlLatch))
        clearModifierLatches()
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
        val alt = event.isAltPressed || altLatch
        val ctrl = event.isCtrlPressed || ctrlLatch
        val specialSequence = sequenceForHardwareSpecialKey(event, shift, alt, ctrl)
        if (specialSequence != null) {
            sendRaw(specialSequence)
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
                repeat(previous.length - value.length) { sendKey(Key.BACKSPACE) }
            }
            value != previous -> {
                val common = previous.zip(value).takeWhile { it.first == it.second }.size
                repeat(previous.length - common) { sendKey(Key.BACKSPACE) }
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

    Surface(
        modifier = modifier,
        shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp),
        color = Color(0xFF101010)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                    fullscreen = fullscreen,
                    terminalMuted = terminalMuted,
                    onRawInputModeChange = {
                        rawInputMode = it
                        input = ""
                    },
                    onAutoScrollChange = { autoScroll = it },
                    onShowExtraKeysChange = { showExtraKeys = it },
                    onFullscreenToggle = { onFullscreenChange(!fullscreen) },
                    onCopy = {
                        context.writeClipboardText(terminalEmulator.plainText(includeScrollback = true))
                        terminalStatus = "已复制"
                    },
                    onPaste = { sendPastedText(context.readClipboardText()) },
                    onClear = { clearLocalTerminal() }
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(terminalBackground, RoundedCornerShape(if (fullscreen) 0.dp else 8.dp))
                    .padding(horizontal = if (fullscreen) 4.dp else 6.dp, vertical = if (fullscreen) 3.dp else 5.dp)
                    .onSizeChanged { size ->
                        val measuredCell = textMeasurer.measure("W", style = terminalTextStyle)
                        terminalCellWidthPx = measuredCell.size.width.coerceAtLeast(1)
                        terminalCellHeightPx = measuredCell.size.height.coerceAtLeast(1)
                        val cols = (size.width / terminalCellWidthPx).coerceIn(TerminalEmulator.MIN_COLUMNS, TerminalEmulator.MAX_COLUMNS)
                        val rows = (size.height / terminalCellHeightPx).coerceIn(TerminalEmulator.MIN_ROWS, TerminalEmulator.MAX_ROWS)
                        if (cols != terminalColumns) terminalColumns = cols
                        if (rows != terminalRows) terminalRows = rows
                    }
                    .onFocusChanged { focusState ->
                        terminalEmulator.sequenceForFocus(focusState.isFocused)?.let { sequence -> sendRaw(sequence) }
                    }
                    .focusable()
                    .then(if (selectionMode) Modifier else Modifier.pointerInteropFilter { event ->
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
                    })
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScroll)
                        .then(if (terminalEmulator.isAlternateScreen) Modifier else Modifier.verticalScroll(outputScroll))
                ) {
                    val terminalContent: @Composable () -> Unit = {
                        Text(
                            text = if (terminalText.text.isEmpty()) AnnotatedString("等待输出...") else terminalText,
                            style = terminalTextStyle,
                            softWrap = false,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                    if (selectionMode) {
                        SelectionContainer { terminalContent() }
                    } else {
                        terminalContent()
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (showExtraKeys) {
                Spacer(modifier = Modifier.height(4.dp))
                TerminalExtraKeysRow(
                    terminalMuted = terminalMuted,
                    ctrlLatch = ctrlLatch,
                    altLatch = altLatch,
                    selectionMode = selectionMode,
                    onToggleCtrl = { ctrlLatch = !ctrlLatch },
                    onToggleAlt = { altLatch = !altLatch },
                    onToggleSelection = { selectionMode = !selectionMode },
                    onKeyboard = {
                        inputFocusRequester.requestFocus()
                        keyboardController?.show()
                    },
                    onControl = { control -> scope.launch { bgManager.sendControlInput(processId, control) } },
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

            Spacer(modifier = Modifier.height(4.dp))

            TerminalInputBar(
                input = input,
                rawInputMode = rawInputMode,
                terminalForeground = terminalForeground,
                terminalMuted = terminalMuted,
                terminalBackground = terminalBackground,
                onInputChange = { handleInputChange(it) },
                focusRequester = inputFocusRequester,
                onSubmit = {
                    if (rawInputMode) {
                        scope.launch { bgManager.sendControlInput(processId, ControlInput.ENTER) }
                        input = ""
                    } else {
                        submitCommand()
                    }
                },
                onHardwareKey = { event -> handleHardwareKey(event) }
            )
        }
    }
}

private fun isTuiCommand(command: String): Boolean {
    val normalized = command.lowercase()
    return listOf(
        "claude", "claude-code", "codex", "opencode", "opencode-ai",
        "vim", "nvim", "vi", "nano", "emacs", "tmux", "screen",
        "less", "more", "top", "htop", "fzf"
    ).any { token ->
        Regex("""(^|[\s;&|()])""" + Regex.escape(token) + """([\s;&|()]|$)""").containsMatchIn(normalized)
    }
}

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
    fullscreen: Boolean,
    terminalMuted: Color,
    onRawInputModeChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onShowExtraKeysChange: (Boolean) -> Unit,
    onFullscreenToggle: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit
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
            modifier = Modifier.width(160.dp)
        )
        TerminalStatusKey(if (rawInputMode) "RAW" else "LINE", rawInputMode) { onRawInputModeChange(!rawInputMode) }
        TerminalStatusKey(if (autoScroll) "AUTO" else "LOCK", autoScroll) { onAutoScrollChange(!autoScroll) }
        TerminalStatusKey("KEYS", showExtraKeys) { onShowExtraKeysChange(!showExtraKeys) }
        TerminalStatusKey("COPY", onClick = onCopy)
        TerminalStatusKey("PASTE", onClick = onPaste)
        TerminalStatusKey("CLR", onClick = onClear)
        TerminalStatusKey(if (fullscreen) "EXIT" else "FULL", highlight = true, onClick = onFullscreenToggle)
    }
}

@Composable
private fun TerminalExtraKeysRow(
    terminalMuted: Color,
    ctrlLatch: Boolean,
    altLatch: Boolean,
    selectionMode: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
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
        Text("KEYS", color = terminalMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        TerminalKey("CTRL", highlight = ctrlLatch) { onToggleCtrl() }
        TerminalKey("ALT", highlight = altLatch) { onToggleAlt() }
        TerminalKey("SEL", highlight = selectionMode) { onToggleSelection() }
        TerminalKey("KBD") { onKeyboard() }
        TerminalKey("ESC") { onControl(ControlInput.ESC) }
        TerminalKey("TAB") { onControl(ControlInput.TAB) }
        TerminalKey("S-TAB") { onControl(ControlInput.BACK_TAB) }
        TerminalKey("↑") { onKey(Key.UP) }
        TerminalKey("↓") { onKey(Key.DOWN) }
        TerminalKey("←") { onKey(Key.LEFT) }
        TerminalKey("→") { onKey(Key.RIGHT) }
        TerminalKey("HOME") { onKey(Key.HOME) }
        TerminalKey("END") { onKey(Key.END) }
        TerminalKey("PGUP") { onKey(Key.PAGE_UP) }
        TerminalKey("PGDN") { onKey(Key.PAGE_DOWN) }
        TerminalKey("BKSP") { onControl(ControlInput.BACKSPACE) }
        TerminalKey("DEL") { onKey(Key.DELETE) }
        TerminalKey("ENTER", highlight = true) { onControl(ControlInput.ENTER) }
        TerminalKey("C-C") { onControl(ControlInput.CTRL_C) }
        TerminalKey("C-D") { onControl(ControlInput.CTRL_D) }
        TerminalKey("C-Z") { onControl(ControlInput.CTRL_Z) }
        TerminalKey("C-L") { onControl(ControlInput.CTRL_L) }
        TerminalKey("C-U") { onControl(ControlInput.CTRL_U) }
        TerminalKey("C-W") { onControl(ControlInput.CTRL_W) }
        TerminalKey("C-A") { onControl(ControlInput.CTRL_A) }
        TerminalKey("C-E") { onControl(ControlInput.CTRL_E) }
        TerminalKey("C-R") { onControl(ControlInput.CTRL_R) }
        TerminalKey("COPY") { onCopy() }
        TerminalKey("PASTE") { onPaste() }
        TerminalKey("CLEAR") { onClear() }
        TerminalKey("TEST") { onSelfTest() }
        TerminalKey("CLI") { onInstallCli() }
    }
}

@Composable
private fun TerminalStatusKey(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
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

@Composable
private fun TerminalKey(
    label: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
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

        BasicTextField(
            value = input,
            onValueChange = onInputChange,
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
        .replace(Regex("\u001B\][^\u0007]*(\u0007|\u001B\\)"), "")
        .replace(Regex("\u001B\[[0-?]*[ -/]*[@-~]"), "")
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
