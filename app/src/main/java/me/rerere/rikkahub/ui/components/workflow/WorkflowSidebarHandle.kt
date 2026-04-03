package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
 */
@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

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
    ) {
        // 纯白色空心圆圈，0.5dp边框，无图标
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(0.5.dp, Color.White, CircleShape)
                .background(Color.Transparent, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // 空心圆，内部无任何内容
        }
    }
}
