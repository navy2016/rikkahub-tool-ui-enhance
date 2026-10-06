package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.rikkahub.data.container.TerminalRenderMode

internal class TerminalVirtualHistoryPolicy(private val keepVirtualOnIme: Boolean = false) {
    var imeFallback by mutableStateOf(false)
        private set

    fun observeIme(visible: Boolean) {
        if (visible && !keepVirtualOnIme) imeFallback = true
    }

    /** Called only AFTER successful preference persistence, even if the selected ID is unchanged. */
    fun reapplied(imeVisible: Boolean) {
        imeFallback = imeVisible && !keepVirtualOnIme
    }

    fun allows(
        historyAvailable: Boolean,
        panEnabled: Boolean,
        selection: Boolean,
        tui: Boolean,
        ime: Boolean,
        avoidIme: Boolean = false,
    ): Boolean = historyAvailable && panEnabled && !selection && !tui && !imeFallback &&
        // The item backend follows the semantic bottom as its viewport shrinks. Keep eager's
        // original offset-preserving IME behavior whenever avoidance is deliberately disabled.
        (!ime || (keepVirtualOnIme && avoidIme))
}

@Composable
internal fun rememberTerminalVirtualHistoryPolicy(
    sessionKey: Any,
    mode: TerminalRenderMode,
    imeVisible: Boolean,
): TerminalVirtualHistoryPolicy {
    val policy = remember(sessionKey, mode) {
        TerminalVirtualHistoryPolicy(keepVirtualOnIme = mode == TerminalRenderMode.VIRTUAL_HISTORY_IME)
    }
    LaunchedEffect(policy, imeVisible) {
        if (mode.isVirtualHistory) policy.observeIme(imeVisible)
    }
    return policy
}
