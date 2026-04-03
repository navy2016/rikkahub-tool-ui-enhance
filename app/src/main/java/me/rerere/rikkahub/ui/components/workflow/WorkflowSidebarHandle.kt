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
 * 注意：此组件需要在外部使用 Box(Modifier.fillMaxSize()) 包裹以实现全屏拖动
 */
@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleSize = 56.dp
    val whiteColor = Color.White
    
    // 获取屏幕尺寸（不受父容器 padding 限制）
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeight = with(density) { configuration.screenHeightDp.dp.toPx() }
    
    // 最大坐标 = 屏幕尺寸 - 按钮尺寸
    val maxX = (screenWidth - with(density) { handleSize.toPx() }).coerceAtLeast(0f)
    val maxY = (screenHeight - with(density) { handleSize.toPx() }).coerceAtLeast(0f)
    
    // 使用 rememberSaveable 保存位置
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var initialized by rememberSaveable { mutableFloatStateOf(0f) } // 0=false, 1=true

    // 初始化位置：右上角偏下
    if (initialized < 0.5f && maxX > 0 && maxY > 0) {
        offsetX = maxX * 0.9f
        offsetY = maxY * 0.35f
        initialized = 1f
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    // 在屏幕范围内自由拖动
                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
                }
            }
            .pointerInput(onClick) {
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
