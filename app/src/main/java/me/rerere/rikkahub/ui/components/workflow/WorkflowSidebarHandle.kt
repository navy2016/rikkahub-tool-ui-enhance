package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.ProcessStatus
import me.rerere.rikkahub.utils.SystemMonitor
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun WorkflowSidebarHandle(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val systemMonitor = koinInject<SystemMonitor>()
    val backgroundProcessManager = koinInject<BackgroundProcessManager>()

    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var cpuUsage by rememberSaveable { mutableFloatStateOf(0f) }

    val processStates by backgroundProcessManager.processStates.collectAsStateWithLifecycle()

    val runningBgCount = processStates.count {
        it.status == ProcessStatus.RUNNING && it.processSource == "container_shell_bg"
    }

    LaunchedEffect(Unit) {
        while (true) {
            cpuUsage = systemMonitor.getCpuUsagePercent()
            delay(1000L)
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(0.5.dp, Color.White, CircleShape)
                .background(Color.Transparent, CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val cpuText = if (cpuUsage >= 0f) "${cpuUsage.roundToInt()}%" else "--"

            Text(
                text = "CPU $cpuText\nBG $runningBgCount",
                color = Color.White,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                lineHeight = 12.sp
            )
        }
    }
}
