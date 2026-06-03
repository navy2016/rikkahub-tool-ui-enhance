package me.rerere.rikkahub.ui.components.container

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.ContainerStateEnum
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.writeClipboardText

/**
 * 容器管理弹窗（底部展开）
 *
 * 4 状态管理界面：
 * - 未初始化：显示 [准备环境] 开关
 * - 初始化中：显示进度条
 * - 运行中：显示 [停止容器] 开关 + 统计信息
 * - 已停止：显示 [启动容器] 开关 + [销毁容器] 按钮（带确认）
 * - 错误：显示错误信息 + [重试] 按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerManagerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    prootManager: PRootManager
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = LocalSettings.current

    // 监听容器状态
    val containerState by prootManager.containerState.collectAsStateWithLifecycle()

    // 统计信息（仅 Running/Stopped 状态显示）
    var installedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var containerSize by remember { mutableStateOf(0L) }
    var runningUtility by remember { mutableStateOf<String?>(null) }
    var utilityOutput by remember { mutableStateOf("") }

    fun runUtility(script: String, timeoutSeconds: Int = 120) {
        if (runningUtility != null) return
        scope.launch {
            runningUtility = script
            utilityOutput = "Running $script ..."
            val result = runCatching { prootManager.runUtilityScript(script, timeoutSeconds) }
            utilityOutput = result.fold(
                onSuccess = { execution ->
                    buildString {
                        appendLine("$script exit=${execution.exitCode}")
                        if (execution.stdout.isNotBlank()) {
                            appendLine()
                            appendLine(execution.stdout.trimEnd())
                        }
                        if (execution.stderr.isNotBlank()) {
                            appendLine()
                            appendLine("[stderr]")
                            appendLine(execution.stderr.trimEnd())
                        }
                    }.trimEnd()
                },
                onFailure = { error ->
                    "${script} failed: ${error.message ?: error::class.java.simpleName}"
                }
            )
            runningUtility = null
        }
    }

    // 销毁确认弹窗
    var showDestroyConfirm by remember { mutableStateOf(false) }

    // 加载统计信息
    LaunchedEffect(containerState) {
        if (containerState is ContainerStateEnum.Running || containerState is ContainerStateEnum.Stopped) {
            try {
                installedPackages = prootManager.getInstalledPackages(settings.containerPipListTimeoutSeconds)
                containerSize = prootManager.getContainerSize()
            } catch (e: Exception) {
                // 统计信息加载失败，使用默认值
                installedPackages = emptyList()
                containerSize = 0L
            }
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "容器运行时管理",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                        }
                    }) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 大状态区域
                StatusDisplay(containerState)

                Spacer(modifier = Modifier.height(32.dp))

                // 操作区
                when (containerState) {
                    is ContainerStateEnum.NotInitialized -> {
                        ActionCard(
                            icon = "🐧",
                            title = "初始化容器",
                            subtitle = "",
                            description = "支持 Python/Node/npm/Go/Rust/Java；内置 apk 源修复脚本",
                            onClick = {
                                scope.launch {
                                    prootManager.initialize()
                                }
                            }
                        )
                    }
                    is ContainerStateEnum.Initializing -> {
                        val progress = (containerState as ContainerStateEnum.Initializing).progress
                        InitializingCard(progress = progress)
                    }
                    is ContainerStateEnum.Running -> {
                        RunningCard(
                            onStop = {
                                scope.launch {
                                    prootManager.stop()
                                }
                            }
                        )
                    }
                    is ContainerStateEnum.Stopped -> {
                        StoppedCard(
                            onStart = {
                                scope.launch {
                                    prootManager.start()
                                }
                            },
                            onDestroy = {
                                showDestroyConfirm = true
                            }
                        )
                    }
                    is ContainerStateEnum.Error -> {
                        val message = (containerState as ContainerStateEnum.Error).message
                        ErrorCard(
                            message = message,
                            onRetry = {
                                scope.launch {
                                    prootManager.initialize()
                                }
                            }
                        )
                    }
                }

                // 统计信息（Running/Stopped 状态）
                if (containerState is ContainerStateEnum.Running || containerState is ContainerStateEnum.Stopped) {
                    Spacer(modifier = Modifier.height(24.dp))
                    StatsSection(
                        packages = installedPackages,
                        size = containerSize
                    )
                }

                if (containerState is ContainerStateEnum.Running) {
                    Spacer(modifier = Modifier.height(24.dp))
                    UtilitySection(
                        runningUtility = runningUtility,
                        utilityOutput = utilityOutput,
                        onRunUtility = { script, timeout -> runUtility(script, timeout) },
                        onCopyOutput = { context.writeClipboardText(utilityOutput) },
                        onClearOutput = { utilityOutput = "" }
                    )
                }

                // 说明文字
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = when (containerState) {
                        is ContainerStateEnum.NotInitialized -> "容器环境提供完整的 Linux 运行环境，支持 pip 安装任意 Python 包"
                        is ContainerStateEnum.Initializing -> "正在准备环境，请稍候..."
                        is ContainerStateEnum.Running -> "容器运行中，AI 可以使用 container_python/container_shell 工具"
                        is ContainerStateEnum.Stopped -> "容器已停止，依赖保留，可快速重启"
                        is ContainerStateEnum.Error -> "初始化失败，请检查网络连接后重试"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // 运行提示（所有状态都显示）
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "⚠️ 运行提示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• 网络异常：rikkahub-fix-apk\n• 安装 CLI/TUI：rikkahub-install-cli\n• native addon 编译：rikkahub-install-node-build-tools\n• 文件监听异常：rikkahub-enable-polling 后重启会话\n• 一键诊断：rikkahub-doctor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // 销毁确认弹窗
    if (showDestroyConfirm) {
        AlertDialog(
            onDismissRequest = { showDestroyConfirm = false },
            title = { Text("销毁容器环境？") },
            text = {
                Text(
                    "这将删除所有已安装的 Python 依赖包（numpy、pandas 等）\n\n" +
                    "基础系统文件会保留，下次使用需要重新准备环境。\n\n" +
                    "注意：开发工具可通过 apk/npm 安装，依赖会被一并删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            prootManager.destroy()
                            showDestroyConfirm = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认销毁")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDestroyConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StatusDisplay(state: ContainerStateEnum) {
    val (icon, title, subtitle, color) = when (state) {
        is ContainerStateEnum.NotInitialized ->
            Quadruple("⚪", "未初始化", "点击准备环境", MaterialTheme.colorScheme.onSurfaceVariant)
        is ContainerStateEnum.Initializing ->
            Quadruple("⏳", "准备中", "正在下载环境...", MaterialTheme.colorScheme.tertiary)
        is ContainerStateEnum.Running ->
            Quadruple("🐧", "运行中", "Alpine Linux • Python 3.11", MaterialTheme.colorScheme.primary)
        is ContainerStateEnum.Stopped ->
            Quadruple("⚫", "已停止", "依赖保留，可快速重启", MaterialTheme.colorScheme.onSurfaceVariant)
        is ContainerStateEnum.Error ->
            Quadruple("⚠️", "错误", "初始化失败", MaterialTheme.colorScheme.error)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.displayLarge,
            color = color
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class ContainerUtilityAction(
    val script: String,
    val label: String,
    val description: String,
    val timeoutSeconds: Int = 120,
)

private val ContainerUtilityActions = listOf(
    ContainerUtilityAction("rikkahub-fix-apk", "修复 apk/网络", "刷新 DNS、apk 源和包索引", 60),
    ContainerUtilityAction("rikkahub-install-cli", "安装 CLI/TUI", "Claude Code / Codex / OpenCode 与常用终端工具", 360),
    ContainerUtilityAction("rikkahub-node-help", "Node/npm 说明", "npm、native addon、watch、dev server 指南", 30),
    ContainerUtilityAction("rikkahub-install-node-build-tools", "安装 native 构建工具", "python3/make/g++/headers，用于 node-gyp", 240),
    ContainerUtilityAction("rikkahub-enable-polling", "启用文件监听兼容", "为 Vite/Webpack/TS 写入 polling 环境变量", 30),
    ContainerUtilityAction("rikkahub-disable-polling", "关闭文件监听兼容", "移除 polling profile", 30),
    ContainerUtilityAction("rikkahub-test-node-npm", "测试 Node/npm", "npm install/exec/global/worker 回归", 240),
    ContainerUtilityAction("rikkahub-test-node-native", "测试 native addon", "编译最小 N-API addon，验证 node-gyp", 360),
    ContainerUtilityAction("rikkahub-test-watch", "测试文件监听", "检测 fs.watch 是否可用", 60),
    ContainerUtilityAction("rikkahub-test-service", "测试长期服务", "启动临时 HTTP 服务并本地访问", 60),
    ContainerUtilityAction("rikkahub-service-env", "服务环境变量", "输出 HOST/PORT/watch polling 环境模板", 30),
    ContainerUtilityAction("rikkahub-service-help", "长期服务说明", "npm/vite/uvicorn/http.server 运行模板", 30),
    ContainerUtilityAction("rikkahub-install-browser-tools", "安装浏览器工具", "可选 Chromium/headless 依赖，体积较大", 360),
    ContainerUtilityAction("rikkahub-browser-help", "浏览器自动化说明", "本地 Chromium 与远程 Browserless 建议", 30),
    ContainerUtilityAction("rikkahub-test-browser", "测试浏览器", "headless Chromium smoke test", 120),
    ContainerUtilityAction("rikkahub-doctor", "一键诊断", "汇总 apk/node/build/native/watch/service/browser 状态", 360),
)

@Composable
private fun UtilitySection(
    runningUtility: String?,
    utilityOutput: String,
    onRunUtility: (String, Int) -> Unit,
    onCopyOutput: () -> Unit,
    onClearOutput: () -> Unit,
) {
    Column {
        Text(
            text = "容器诊断与修复",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ContainerUtilityActions) { action ->
                UtilityActionRow(
                    action = action,
                    running = runningUtility == action.script,
                    enabled = runningUtility == null,
                    onClick = { onRunUtility(action.script, action.timeoutSeconds) }
                )
            }
        }
        if (utilityOutput.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("输出", style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(onClick = onCopyOutput) { Text("复制") }
                            TextButton(onClick = onClearOutput) { Text("清空") }
                        }
                    }
                    Text(
                        text = utilityOutput.takeLast(6000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UtilityActionRow(
    action: ContainerUtilityAction,
    running: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (running) 0.8f else 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(action.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("运行", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: String,
    title: String,
    subtitle: String,
    description: String? = null,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.titleMedium
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 开关样式（实际上是个按钮，但看起来像开关）
            Box(
                modifier = Modifier
                    .size(48.dp, 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "准备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun InitializingCard(progress: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunningCard(onStop: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onStop),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "⏹️",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "停止容器",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun StoppedCard(
    onStart: () -> Unit,
    onDestroy: () -> Unit
) {
    Column {
        // 启动按钮
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onStart),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "▶️",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "启动容器",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 销毁按钮（红色警告）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDestroy),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "🗑️",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "销毁容器环境",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRetry),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "错误: $message",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点击重试",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun StatsSection(packages: List<String>, size: Long) {
    Column {
        Text(
            text = "统计信息",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 容器大小
        StatItem(
            label = "容器大小",
            value = formatSize(size)
        )

        // 已安装包
        if (packages.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            StatItem(
                label = "已安装依赖",
                value = packages.take(5).joinToString(", ") +
                    if (packages.size > 5) " 等 ${packages.size} 个" else ""
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// 辅助数据类
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private fun formatSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
