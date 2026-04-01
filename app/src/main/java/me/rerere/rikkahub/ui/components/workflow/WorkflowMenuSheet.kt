package me.rerere.rikkahub.ui.components.workflow

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.container.ContainerStateEnum
import me.rerere.rikkahub.data.model.WorkflowPhase
import me.rerere.rikkahub.ui.components.container.ContainerRuntimeCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowMenuSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    currentPhase: WorkflowPhase,
    onPhaseChange: (WorkflowPhase) -> Unit,
    onOpenSandbox: () -> Unit,
    containerState: ContainerStateEnum,
    onOpenContainerManager: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    if (!visible) return

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.workflow_menu_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.workflow_menu_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WorkflowPhase.entries.forEach { phase ->
                PhaseCard(
                    phase = phase,
                    description = getPhaseDescription(phase),
                    isSelected = currentPhase == phase,
                    phaseColor = getPhaseColor(phase),
                    onClick = { onPhaseChange(phase) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            ContainerRuntimeCard(
                state = containerState,
                onClick = onOpenContainerManager
            )

            Spacer(modifier = Modifier.height(8.dp))

            SandboxCard(onClick = onOpenSandbox)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.workflow_menu_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PhaseCard(
    phase: WorkflowPhase,
    description: String,
    isSelected: Boolean,
    phaseColor: Color,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        phaseColor.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val borderColor = if (isSelected) phaseColor else Color.Transparent
    val textColor = if (isSelected) phaseColor else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = phase.name,
                style = MaterialTheme.typography.titleSmall,
                color = textColor
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    textColor.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
            )
        }
    }
}

@Composable
private fun SandboxCard(
    onClick: () -> Unit
) {
    WorkflowMenuCard(
        icon = Lucide.FolderOpen,
        iconTint = MaterialTheme.colorScheme.secondary,
        title = stringResource(R.string.chat_page_open_sandbox_file_manager),
        subtitle = stringResource(R.string.workflow_menu_sandbox_subtitle),
        onClick = onClick
    )
}

@Composable
private fun getPhaseColor(phase: WorkflowPhase): Color {
    return when (phase) {
        WorkflowPhase.PLAN -> Color(0xFF4A90D9)
        WorkflowPhase.EXECUTE -> Color(0xFF7B7B7B)
        WorkflowPhase.REVIEW -> Color(0xFF7B7B7B)
    }
}

@Composable
private fun getPhaseDescription(phase: WorkflowPhase): String {
    return when (phase) {
        WorkflowPhase.PLAN -> stringResource(R.string.workflow_menu_phase_plan_desc)
        WorkflowPhase.EXECUTE -> stringResource(R.string.workflow_menu_phase_execute_desc)
        WorkflowPhase.REVIEW -> stringResource(R.string.workflow_menu_phase_review_desc)
    }
}
