package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.effectiveTerminalRenderMode

/** Deliberately uses the original parent ScrollState/SelectionContainer; no new scroll owner. */
@Composable
internal fun TerminalConfiguredTranscript(
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
    historyChunks: List<TerminalHistoryChunk>,
    mode: TerminalRenderMode,
    observer: TerminalTranscriptCompositionObserver? = null,
    measurement: TerminalRowMeasurementScope? = null,
) {
    val effective = effectiveTerminalRenderMode(mode, historyChunks.isNotEmpty(), virtualHistoryAllowed = false)
    val content: @Composable () -> Unit = {
        TerminalRenderedTranscript(rows, style,
            if (effective == TerminalRenderMode.FLAT) emptyList() else historyChunks,
            isolateChunkDrawing = effective == TerminalRenderMode.CHUNKED_LAYERS, observer = observer)
    }
    if (measurement == null) content() else {
        val measurements = measurement.measurements
        val metricKey = measurement.pass.metricKey
        // A new output frame must not replace a static CompositionLocal callback and invalidate
        // every archived chunk. Retain metrics only, not the frame or its history rows.
        val decorator: @Composable (TerminalRenderedRowState, TextStyle) -> Unit = remember(measurements, metricKey) {
            { row, textStyle -> measurements.Row(metricKey, row, textStyle) }
        }
        CompositionLocalProvider(LocalTerminalRowDecorator provides decorator) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalRenderButton(
    preferred: TerminalRenderMode,
    effective: TerminalRenderMode,
    customLabel: String? = null,
    onEditItems: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.testTag("terminal-render-button")
            .semantics {
                contentDescription = "选择终端渲染方式"
                stateDescription = "已选${preferred.label}，实际${effective.label}"
            }
            .combinedClickable(onClick = onClick, onLongClick = onEditItems),
        shape = RoundedCornerShape(5.dp),
        color = if (preferred != TerminalRenderMode.DEFAULT) Color(0xFF1B5E20) else Color(0xFF252525),
        contentColor = Color(0xFFE0E0E0),
    ) {
        Text(
            text = customLabel?.takeIf { it.isNotBlank() } ?: "渲染·${preferred.shortLabel}",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1,
        )
    }
}

@Composable
internal fun TerminalRenderModeDialog(
    value: TerminalRenderMode,
    hasHistoryChunks: Boolean,
    usesTuiViewport: Boolean,
    virtualHistoryAllowed: Boolean = false,
    virtualHistoryImeFallback: Boolean = false,
    appliedMode: TerminalRenderMode = value,
    selectionActive: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
    onDismiss: () -> Unit,
    onSave: (TerminalRenderMode) -> Unit,
) {
    var pending by remember(value) { mutableStateOf(value) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("终端渲染方式") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("按当前命令记住选择；切换不重启进程、不清空历史。没有适合所有设备的最快模式，请用同一段输出比较。",
                    style = MaterialTheme.typography.bodySmall)
                if (selectionActive) Text("请先退出 SEL 选择模式再切换，避免清除当前选区。",
                    style = MaterialTheme.typography.bodySmall)
                if (virtualHistoryImeFallback && value == TerminalRenderMode.VIRTUAL_HISTORY) {
                    Text("键盘交互后已保留兼容渲染。关闭键盘后，可再次应用“虚拟历史”恢复；无需先切换其它模式。",
                        modifier = Modifier.testTag("terminal-render-ime-fallback"),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("已选：${value.label} · 实际：${effectiveTerminalRenderMode(appliedMode, hasHistoryChunks, virtualHistoryAllowed).label}",
                    modifier = Modifier.testTag("terminal-render-effective"), style = MaterialTheme.typography.bodySmall)
                if (usesTuiViewport || !hasHistoryChunks) {
                    Text(if (usesTuiViewport) "当前为 TUI／全网格，沿用逐行兼容绘制；返回普通历史后使用所选模式。"
                        else "当前没有可分块历史，沿用逐行兼容绘制；产生有效历史后使用所选模式。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.selectableGroup()) {
                    TerminalRenderMode.entries.forEach { mode ->
                        Row(Modifier.fillMaxWidth().testTag("terminal-render-option-${mode.id}")
                            .selectable(selected = pending == mode, enabled = !saving && !selectionActive, role = Role.RadioButton) { pending = mode }
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pending == mode, onClick = null, enabled = !saving && !selectionActive)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(mode.label + if (mode == TerminalRenderMode.DEFAULT) "（当前默认）" else "",
                                    style = MaterialTheme.typography.titleSmall)
                                Text(when (mode) {
                                    TerminalRenderMode.CHUNKED_LAYERS -> "复用历史分块的绘制记录，适合长历史和连续输出；使用额外图层记录。"
                                    TerminalRenderMode.CHUNKED -> "保留历史分块，不使用独立绘制图层；适合实测图层收益较低的设备。"
                                    TerminalRenderMode.FLAT -> "逐行直接布局，无历史分块；便于短输出和兼容性对照，长历史成本较高。"
                                    TerminalRenderMode.VIRTUAL_HISTORY -> "只组合当前可见历史行；键盘交互后保留兼容渲染，可再次应用恢复虚拟历史。"
                                    TerminalRenderMode.VIRTUAL_HISTORY_IME -> "实验选项：长历史在键盘显示时保留虚拟行树；自动跟随、锁定阅读和 IME 高度设置不变。短内容需要保留原位时仍回退。"
                                }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = { pending = TerminalRenderMode.DEFAULT }, enabled = !saving && !selectionActive,
                    modifier = Modifier.testTag("terminal-render-reset")) { Text("恢复默认") }
                Text("所有模式均保留完整历史和自然行高。虚拟历史在 SEL、MOUSE、TUI／全网格场景自动回退，避免改变原有交互语义。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已有同机测试显示长历史受益于分块图层；模拟器数据不是本机帧率保证。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(pending) }, enabled = !saving && !selectionActive,
                modifier = Modifier.testTag("terminal-render-apply")) { Text(if (saving) "保存中…" else "应用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving,
                modifier = Modifier.testTag("terminal-render-cancel")) { Text("取消") }
        },
    )
}
