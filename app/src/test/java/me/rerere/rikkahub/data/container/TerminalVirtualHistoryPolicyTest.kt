package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.ui.pages.container.TerminalVirtualHistoryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalVirtualHistoryPolicyTest {
    @Test fun applyingSameModeAfterKeyboardHideReleasesFallback() {
        val policy = TerminalVirtualHistoryPolicy()
        policy.observeIme(true)
        policy.observeIme(false)
        assertFalse(policy.allows(true, true, false, false, false))
        policy.reapplied(false)
        assertTrue(policy.allows(true, true, false, false, false))
        policy.reapplied(true)
        assertFalse(policy.allows(true, true, false, false, false))
    }

    @Test fun incompatibleModesNeverActivateVirtualHistory() {
        val policy = TerminalVirtualHistoryPolicy()
        assertFalse(policy.allows(false, true, false, false, false))
        assertFalse(policy.allows(true, false, false, false, false))
        assertFalse(policy.allows(true, true, true, false, false))
        assertFalse(policy.allows(true, true, false, true, false))
        assertFalse(policy.allows(true, true, false, false, true))
    }
}
