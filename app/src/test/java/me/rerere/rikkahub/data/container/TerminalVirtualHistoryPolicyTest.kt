package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.ui.pages.container.TerminalVirtualHistoryPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalVirtualHistoryPolicyTest {
    @Test fun stableKeyboardIsExplicitAndKeepsTheLegacyImeLatchUnchanged() {
        val legacy = TerminalVirtualHistoryPolicy()
        val stable = TerminalVirtualHistoryPolicy(keepVirtualOnIme = true)
        repeat(3) {
            legacy.observeIme(true)
            stable.observeIme(true)
            assertFalse(legacy.allows(true, true, false, false, true, avoidIme = true))
            assertTrue(stable.allows(true, true, false, false, true, avoidIme = true))
            legacy.observeIme(false)
            stable.observeIme(false)
            assertFalse(legacy.allows(true, true, false, false, false))
            assertTrue(stable.allows(true, true, false, false, false))
            assertFalse(stable.imeFallback)
        }
        stable.reapplied(true)
        assertTrue(stable.allows(true, true, false, false, true, avoidIme = true))
        assertFalse(stable.imeFallback)
    }

    @Test fun stableKeyboardRetainsAllOtherCompatibilityGatesAndOffsetPreservation() {
        val policy = TerminalVirtualHistoryPolicy(keepVirtualOnIme = true)
        for (ime in listOf(false, true)) {
            assertFalse(policy.allows(false, true, false, false, ime, avoidIme = true))
            assertFalse(policy.allows(true, false, false, false, ime, avoidIme = true))
            assertFalse(policy.allows(true, true, true, false, ime, avoidIme = true))
            assertFalse(policy.allows(true, true, false, true, ime, avoidIme = true))
        }
        assertFalse(policy.allows(true, true, false, false, true))
        assertFalse(policy.allows(true, true, false, false, true, avoidIme = false))
        assertTrue(policy.allows(true, true, false, false, true, avoidIme = true))
        assertTrue(policy.allows(true, true, false, false, false, avoidIme = false))
    }

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
