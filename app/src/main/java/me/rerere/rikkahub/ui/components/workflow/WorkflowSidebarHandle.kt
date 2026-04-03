package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
    
    // 使用屏幕尺寸作为拖动范围（不受父容器限制）
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var initialized by rememberSaveable { mutableFloatStateOf(0f) } // 0=false

    // 初始化位置：右上角偏下（只在首次加载时执行）
    if (initialized < 0.5f) {
        offsetX = screenWidthPx * 0.9f
        offsetY = screenHeightPx * 0.35f
        initialized = 1f
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 【关键】自由拖动，基于屏幕尺寸进行边界限制
                    // 这样可以拖到屏幕任意位置（包括最顶和最底边缘）
                    val maxX = screenWidthPx - with(density) { handleSize.toPx() }
                    val maxY = screenHeightPx - with(density) { handleSize.toPx() }
                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                }
            }
            .pointerInput(onClick) {
                // 使用 detectTapGestures 避免 clickable 的涟漪效果
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
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
