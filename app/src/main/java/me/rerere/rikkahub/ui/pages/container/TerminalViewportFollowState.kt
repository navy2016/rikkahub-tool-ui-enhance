package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportControllerState

/** A cold read-only projection: no new owner, coroutine, replay cache or mutable follow flag. */
internal fun Flow<TerminalViewportControllerState>.terminalFollowChanges(): Flow<Boolean> =
    map { it.autoScroll }.distinctUntilChanged()

/**
 * The panel uses only AUTO/LOCK. Anchor, gesture and effect IDs belong to the controller/binding;
 * collecting them into panel Compose state needlessly recomposes input, controls and layout wiring.
 * Scope initial state and lifecycle collection to this controller, including session replacement.
 */
@Composable
internal fun rememberTerminalFollowEnabled(controller: TerminalViewportController): State<Boolean> = key(controller) {
    val changes = remember(controller) { controller.state.terminalFollowChanges() }
    changes.collectAsStateWithLifecycle(initialValue = controller.state.value.autoScroll)
}
