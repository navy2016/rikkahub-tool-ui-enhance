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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 可拖动的 Workflow 悬浮按钮
 *
 * 奶白色空心圆圈设计，圆内无图标 且100%透明，支持在屏幕任意位置拖动
 */
@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleSize = 56.dp
    // 纯白色边框
    val whiteColor = Color.White

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val density = LocalDensity.current
        // 允许拖到屏幕边缘，最大坐标为屏幕宽高减去按钮尺寸
        val maxX = with(density) { maxWidth.toPx() - handleSize.toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { maxHeight.toPx() - handleSize.toPx() }.coerceAtLeast(0f)
        
        var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
        var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
        var initialized by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(maxX, maxY) {
            if (!initialized) {
                // 初始位置：右上角偏下一点
                offsetX = maxX * 0.9f
                offsetY = maxY * 0.35f
                initialized = true
            } else {
                // 确保位置在有效范围内
                offsetX = offsetX.coerceIn(0f, maxX)
                offsetY = offsetY.coerceIn(0f, maxY)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                // 使用 offset 将按钮定位到任意位置
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // 更新位置并限制在屏幕范围内
                        offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                        offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                    }
                }
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                }
        ) {
            // 纯白色空心圆圈，内部100%透明，0.5dp边框
            Box(
                modifier = Modifier
                    .size(handleSize)
                    .background(Color.Transparent, shape = CircleShape)
                    .border(width = 0.5.dp, color = whiteColor, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 圆内无图标，100%透明
            }
        }
    }
}
