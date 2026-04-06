package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

@Composable
fun LedgerGenerationDialog(
    onCancel: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(R.string.chat_page_memory_ledger_generating_title_v2))
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.chat_page_memory_ledger_generating_v2))
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
