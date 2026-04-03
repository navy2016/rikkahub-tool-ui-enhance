package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 可拖动的 Workflow 悬浮按钮
 *
 * 纯白色空心圆圈设计，无图标，支持在屏幕任意位置拖动
 * 初始位置：覆盖于搜索设置按钮上（底部第2个按钮）
 */
@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 初始位置：覆盖搜索设置按钮（从左边数第2个按钮）
    // 假设每个按钮约48dp宽，第2个按钮中心约在 48 + 24 = 72dp 左右
    // 从底部约80dp处（输入栏高度约60-80dp）
    var offsetX by rememberSaveable { mutableFloatStateOf(72f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(-100f) }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 自由拖动，无边界限制
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .pointerInput(onClick) {
                // 使用 detectTapGestures 处理点击，避免 clickable 的涟漪效果
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        // 纯白色空心圆圈，0.5dp边框，无图标
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color.Transparent, CircleShape)
                .border(0.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // 空心圆，内部无任何内容
        }
    }
}
