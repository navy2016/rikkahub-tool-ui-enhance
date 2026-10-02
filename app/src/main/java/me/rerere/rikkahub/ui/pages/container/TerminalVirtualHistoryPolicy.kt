package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.container.TerminalRenderMode

internal class TerminalVirtualHistoryPolicy {
    var imeFallback by mutableStateOf(false)
        private set

    fun observeIme(visible: Boolean) {
        if (visible) imeFallback = true
    }

    /** Called only AFTER successful preference persistence, even if the selected ID is unchanged. */
    fun reapplied(imeVisible: Boolean) {
        imeFallback = imeVisible
    }

    fun allows(historyAvailable: Boolean, panEnabled: Boolean, selection: Boolean, tui: Boolean, ime: Boolean): Boolean =
        historyAvailable && panEnabled && !selection && !tui && !ime && !imeFallback
}

@Composable
internal fun rememberTerminalVirtualHistoryPolicy(
    sessionKey: Any,
    mode: TerminalRenderMode,
    imeVisible: Boolean,
): TerminalVirtualHistoryPolicy {
    val policy = remember(sessionKey, mode) { TerminalVirtualHistoryPolicy() }
    LaunchedEffect(policy, imeVisible) {
        if (mode == TerminalRenderMode.VIRTUAL_HISTORY) policy.observeIme(imeVisible)
    }
    return policy
}
