package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Box
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.container.ContainerManagerSheet
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager

private const val DEFAULT_CONTAINER_TIMEOUT_SECONDS = 300
private const val DEFAULT_CONTAINER_PIP_INSTALL_TIMEOUT_SECONDS = 120
private const val DEFAULT_CONTAINER_PIP_LIST_TIMEOUT_SECONDS = 30
private const val DEFAULT_SUBAGENT_TIMEOUT_SECONDS = 0
private const val DEFAULT_MAX_GENERATION_STEPS = 256
private const val DEFAULT_MAX_SUBAGENT_STEPS = 50
private const val DEFAULT_AUTO_RESEND_INTERVAL_SECONDS = 10
private const val DEFAULT_AUTO_RESEND_MAX_ATTEMPTS = 0
private const val DEFAULT_TOOL_CALL_DELAY_SECONDS = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingAdvancedPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settingsStore: SettingsStore = koinInject()
    val prootManager: PRootManager = koinInject()
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    var showContainerManager by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("高级") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("AI Code") },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingWorkflowControl) },
                        leadingContent = { Icon(Lucide.Sparkles, null) },
                        headlineContent = { Text("工作流控制") },
                        supportingContent = { Text("控制聊天页工作流入口与侧边栏显示") },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingScheduledTasks) },
                        leadingContent = { Icon(Lucide.Clock, null) },
                        headlineContent = { Text("定时任务") },
                        supportingContent = { Text("按计划触发助手执行任务并查看运行记录") },
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    CardGroup(
                        title = { Text("容器网络") },
                    ) {
                        item(
                            onClick = { showContainerManager = true },
                            leadingContent = { Icon(Lucide.Box, null) },
                            headlineContent = { Text("容器运行时管理") },
                            supportingContent = { Text("初始化、启动/停止、诊断修复、Node/npm 与长期服务辅助") },
                        )

                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "自定义 TUI 程序名单",
                                    description = "每行一个命令名。容器交互会话的 AUTO PTY 会将这些程序按 TUI/RAW 处理。示例：lazygit、btop、yazi。",
                                    value = settings.terminalCustomTuiCommands,
                                    placeholder = "lazygit\nbtop\nyazi\nranger",
                                    minLines = 4,
                                    maxLines = 8,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(terminalCustomTuiCommands = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "自定义 /etc/hosts 内容",
                                    description = "追加写入容器 /etc/hosts。每行格式示例：1.2.3.4 example.com。留空则只使用默认 localhost。",
                                    value = settings.containerCustomHosts,
                                    placeholder = "1.2.3.4 example.com\n2606:4700:4700::1111 dns.example",
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerCustomHosts = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "APK 镜像源",
                                    description = "容器 apk 源。可填 default、aliyun、tuna、ustc 或 Alpine 镜像 URL，例如 https://mirrors.aliyun.com/alpine/v3.19。留空使用官方源。",
                                    value = settings.containerApkMirror,
                                    placeholder = "aliyun",
                                    minLines = 1,
                                    maxLines = 2,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerApkMirror = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "npm registry",
                                    description = "容器 npm 源。可填 official、npmmirror、tencent、huawei 或 registry URL。安装终端工具时会自动应用。",
                                    value = settings.containerNpmRegistry,
                                    placeholder = "npmmirror",
                                    minLines = 1,
                                    maxLines = 2,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerNpmRegistry = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "GitHub 下载代理前缀",
                                    description = "用于 GitHub release 下载加速。示例：https://gh-proxy.com/。留空直连。当前用于 rikkahub-install-omp。",
                                    value = settings.containerGithubProxyPrefix,
                                    placeholder = "https://gh-proxy.com/",
                                    minLines = 1,
                                    maxLines = 2,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerGithubProxyPrefix = value) }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    CardGroup(
                        title = { Text("消息失败自动重发") },
                    ) {

                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "重发次数",
                                    description = "默认 0（关闭）。仅用户发送消息后的助手生成失败会触发；如果失败发生在助手调用工具之后，不会自动重发，避免重复执行工具。",
                                    value = settings.autoResendUserMessageMaxAttempts,
                                    suffix = "次",
                                    minValue = 0,
                                    defaultValue = DEFAULT_AUTO_RESEND_MAX_ATTEMPTS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(autoResendUserMessageMaxAttempts = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "重发间隔",
                                    description = "默认 10 秒。每次匹配到失败通知内容后，等待此时间再重新生成本次用户消息。",
                                    value = settings.autoResendUserMessageIntervalSeconds,
                                    suffix = "s",
                                    minValue = 1,
                                    defaultValue = DEFAULT_AUTO_RESEND_INTERVAL_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(autoResendUserMessageIntervalSeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "触发重发的失败通知内容",
                                    description = "每行一个匹配片段，大小写不敏感。只有生成失败的错误内容包含其中任一片段时才自动重发。留空则不会触发。示例：rate limit、timeout、Software caused connection abort。",
                                    value = settings.autoResendUserMessageFailureMatchers,
                                    placeholder = "rate limit\ntimeout\nSoftware caused connection abort\nupstream error",
                                    minLines = 4,
                                    maxLines = 8,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(autoResendUserMessageFailureMatchers = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("工具后失败自动继续", style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            text = "助手已开始调用工具后生成失败时，不重发原消息；保留现有助手/工具结果，并自动追加一条继续消息。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    Switch(
                                        checked = settings.autoContinueAfterToolFailureEnabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                settingsStore.update { s -> s.copy(autoContinueAfterToolFailureEnabled = enabled) }
                                            }
                                        }
                                    )
                                }
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurTextField(
                                    label = "工具后失败继续消息",
                                    description = "默认“继续”。仅在“工具后失败自动继续”开启且错误内容命中上方匹配片段时使用；会跳过下一次工作流自动继续，避免重复发送。",
                                    value = settings.autoContinueAfterToolFailureMessage,
                                    placeholder = "继续",
                                    minLines = 1,
                                    maxLines = 3,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(autoContinueAfterToolFailureMessage = value.ifBlank { "继续" }) }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    CardGroup(
                        title = { Text("工具调用节流") },
                    ) {
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "每次工具调用前延迟",
                                    description = "默认 0 秒（关闭）。助手连续调用工具时，在每个工具真正执行前等待指定秒数，用于缓解远程服务并发/频率限制。",
                                    value = settings.toolCallDelaySeconds,
                                    suffix = "s",
                                    minValue = 0,
                                    defaultValue = DEFAULT_TOOL_CALL_DELAY_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(toolCallDelaySeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    Row(modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                        Text(
                            text = "说明：超时类配置单位为秒；步骤类配置单位为步。输入完成后在失焦或点击键盘完成时保存。子代理超时填 0 表示不超时。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CardGroup(
                        title = { Text("时间控制") },
                    ) {
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "容器命令超时",
                                    description = "默认 300 秒（5分钟），Shell / Python 命令执行超时时间",
                                    value = settings.containerTimeoutSeconds,
                                    suffix = "s",
                                    minValue = 10,
                                    defaultValue = DEFAULT_CONTAINER_TIMEOUT_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerTimeoutSeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "pip install 超时",
                                    description = "默认 120 秒（2分钟），Python 包安装超时",
                                    value = settings.containerPipInstallTimeoutSeconds,
                                    suffix = "s",
                                    minValue = 10,
                                    defaultValue = DEFAULT_CONTAINER_PIP_INSTALL_TIMEOUT_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerPipInstallTimeoutSeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "pip list 超时",
                                    description = "默认 30 秒，获取已安装包列表超时",
                                    value = settings.containerPipListTimeoutSeconds,
                                    suffix = "s",
                                    minValue = 5,
                                    defaultValue = DEFAULT_CONTAINER_PIP_LIST_TIMEOUT_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(containerPipListTimeoutSeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "子代理超时",
                                    description = "默认 0（不超时，保持原始行为），子代理任务执行超时",
                                    value = settings.subagentTimeoutSeconds,
                                    suffix = "s",
                                    minValue = 0,
                                    defaultValue = DEFAULT_SUBAGENT_TIMEOUT_SECONDS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(subagentTimeoutSeconds = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "最大生成步骤",
                                    description = "默认 256 步，AI 生成的最大步骤数",
                                    value = settings.maxGenerationSteps,
                                    suffix = "步",
                                    minValue = 1,
                                    defaultValue = DEFAULT_MAX_GENERATION_STEPS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(maxGenerationSteps = value) }
                                        }
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = {
                                SaveOnBlurNumberField(
                                    label = "子代理最大步骤",
                                    description = "默认 50 步，子代理任务的最大步骤数",
                                    value = settings.maxSubagentSteps,
                                    suffix = "步",
                                    minValue = 1,
                                    defaultValue = DEFAULT_MAX_SUBAGENT_STEPS,
                                    onSave = { value ->
                                        scope.launch {
                                            settingsStore.update { s -> s.copy(maxSubagentSteps = value) }
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
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
}

@Composable
private fun SaveOnBlurNumberField(
    label: String,
    description: String,
    value: Int,
    suffix: String,
    minValue: Int,
    defaultValue: Int,
    onSave: (Int) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    var isError by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!hasFocus && textValue != value.toString()) {
            textValue = value.toString()
            isError = false
        }
    }

    fun commit() {
        val parsed = textValue.toIntOrNull()
        if (parsed == null || parsed < minValue) {
            isError = true
            textValue = value.toString()
            return
        }
        isError = false
        if (parsed != value) {
            onSave(parsed)
        }
    }

    Column {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = textValue,
                onValueChange = { newValue ->
                    textValue = newValue.filter { it.isDigit() }
                    isError = false
                },
                singleLine = true,
                isError = isError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commit()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .width(120.dp)
                    .onFocusChanged { state ->
                        val lostFocus = hasFocus && !state.isFocused
                        hasFocus = state.isFocused
                        if (lostFocus) commit()
                    },
                suffix = { Text(suffix) }
            )
            TextButton(
                onClick = {
                    textValue = defaultValue.toString()
                    isError = false
                    if (value != defaultValue) onSave(defaultValue)
                    focusManager.clearFocus()
                }
            ) {
                Text("恢复默认")
            }
        }
        if (isError) {
            Text(
                text = "请输入不小于 $minValue 的数字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SaveOnBlurTextField(
    label: String,
    description: String,
    value: String,
    placeholder: String,
    minLines: Int = 4,
    maxLines: Int = 10,
    onSave: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var textValue by remember(value) { mutableStateOf(value) }
    var hasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!hasFocus && textValue != value) {
            textValue = value
        }
    }

    fun commit() {
        val normalized = textValue.replace("\r\n", "\n").replace("\r", "\n").trimEnd()
        if (normalized != value) {
            textValue = normalized
            onSave(normalized)
        }
    }

    Column {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        OutlinedTextField(
            value = textValue,
            onValueChange = { textValue = it },
            minLines = minLines,
            maxLines = maxLines,
            placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Default
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .onFocusChanged { state ->
                    val lostFocus = hasFocus && !state.isFocused
                    hasFocus = state.isFocused
                    if (lostFocus) commit()
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = {
                    textValue = ""
                    if (value.isNotBlank()) onSave("")
                    focusManager.clearFocus()
                }
            ) {
                Text("清空")
            }
            TextButton(
                onClick = {
                    commit()
                    focusManager.clearFocus()
                }
            ) {
                Text("保存")
            }
        }
    }
}
