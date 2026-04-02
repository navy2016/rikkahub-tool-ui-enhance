package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.X
import me.rerere.rikkahub.data.model.WorkflowPhase

@Composable
fun WorkflowFloatingPanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    autoContinue: Boolean,
    autoContinueMaxCount: Int,
    autoContinueDelayMs: Long,
    currentPhase: WorkflowPhase?,
    onAutoContinueChange: (Boolean) -> Unit,
    onAutoContinueMaxCountChange: (Int) -> Unit,
    onAutoContinueDelayMsChange: (Long) -> Unit,
    onPhaseChange: (WorkflowPhase?) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.12f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 108.dp, end = 48.dp)
                    .widthIn(min = 300.dp, max = 380.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {}
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Lucide.Sparkles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "工作流",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Lucide.X, contentDescription = "Close")
                        }
                    }

                    // AutoContinue 开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自动继续",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (autoContinue) "开启" else "关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoContinue,
                            onCheckedChange = onAutoContinueChange
                        )
                    }

                    // 自动继续设置（仅当开启时显示）
                    AnimatedVisibility(visible = autoContinue) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 最大次数滑块
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "最大次数",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${autoContinueMaxCount}次",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = autoContinueMaxCount.toFloat(),
                                    onValueChange = { onAutoContinueMaxCountChange(it.toInt()) },
                                    valueRange = 1f..50f,
                                    steps = 49
                                )
                            }

                            // 延迟时间滑块
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "延迟时间",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${autoContinueDelayMs}ms",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = autoContinueDelayMs.toFloat(),
                                    onValueChange = { onAutoContinueDelayMsChange(it.toLong()) },
                                    valueRange = 0f..10000f,
                                    steps = 20
                                )
                            }

                            // 提示文字
                            Text(
                                text = "自动继续时，助手完成回复后将等待${autoContinueDelayMs}ms后发送"继续"（最多${autoContinueMaxCount}次）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 阶段选择提示
                            Text(
                                text = "可选阶段：不选则按助手回复自动执行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 阶段卡片
                    WorkflowPhase.entries.forEach { phase ->
                        WorkflowPhaseCard(
                            phase = phase,
                            selected = phase == currentPhase,
                            onClick = {
                                if (autoContinue) {
                                    // AutoContinue 模式：点击已选中的可以取消，否则选中
                                    onPhaseChange(if (currentPhase == phase) null else phase)
                                } else {
                                    // 非 AutoContinue 模式：必须选中一个
                                    onPhaseChange(phase)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowPhaseCard(
    phase: WorkflowPhase,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val background = if (selected) {
        selectedColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        selectedColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = phase.name,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor
            )
            Text(
                text = getPhaseDescription(phase),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getPhaseDescription(phase: WorkflowPhase): String {
    return when (phase) {
        WorkflowPhase.PLAN -> "分析需求并制定执行计划"
        WorkflowPhase.EXECUTE -> "执行代码与自动化任务"
        WorkflowPhase.REVIEW -> "复核质量与安全问题"
    }
}
