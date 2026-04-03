package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 可拖动的 Workflow 悬浮按钮
 *
 * 纯白色空心圆圈设计，圆内显示当前X和Y坐标，支持在屏幕任意位置拖动
 */
@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleSize = 56.dp
    val whiteColor = Color.White
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        // 使用 BoxWithConstraints 的 maxWidth/maxHeight 作为拖动范围
        // 这样可以填满父容器（包括 padding 外的区域）
        val maxX = with(density) { (maxWidth - handleSize).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - handleSize).toPx().coerceAtLeast(0f) }
        
        var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
        var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
        var initialized by rememberSaveable { mutableStateOf(false) }

        // 在 LaunchedEffect 中初始化位置
        LaunchedEffect(maxX, maxY) {
            if (!initialized && maxX > 0 && maxY > 0) {
                // 初始位置：右上角偏下一点
                offsetX = maxX * 0.9f
                offsetY = maxY * 0.35f
                initialized = true
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                    }
                }
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                }
        ) {
            // 纯白色空心圆圈，0.5dp边框
            Box(
                modifier = Modifier
                    .size(handleSize)
                    .background(Color.Transparent, shape = CircleShape)
                    .border(width = 0.5.dp, color = whiteColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 圆内显示当前 X 和 Y 坐标
                Text(
                    text = "${offsetX.roundToInt()}\n${offsetY.roundToInt()}",
                    color = whiteColor,
                    fontSize = 8.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 10.sp
                )
            }
        }
    }
}
