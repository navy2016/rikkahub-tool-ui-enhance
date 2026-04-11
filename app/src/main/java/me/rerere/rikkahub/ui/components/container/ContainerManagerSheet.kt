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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.ContainerStateEnum
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.ui.context.LocalSettings

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
    val settings = LocalSettings.current

    // 监听容器状态
    val containerState by prootManager.containerState.collectAsStateWithLifecycle()

    // 统计信息（仅 Running/Stopped 状态显示）
    var installedPackages by remember { mutableStateOf<List<String>>(emptyList()) }
    var containerSize by remember { mutableStateOf(0L) }

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
                            description = "支持 Python/Go/Rust/Java，npm 不可用",
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

                // 已知限制提示（所有状态都显示）
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
                            text = "⚠️ 已知限制",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• npm 在容器环境中不可用（上游 bug，截至 2026 年未修复）\n• 推荐使用：Python/pip、Go/mod、Rust/cargo、Java/Maven",
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
                    "注意：其他开发工具可通过 apk 安装（如 go、rust、openjdk），但 npm 不可用。"
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
