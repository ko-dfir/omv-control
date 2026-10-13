package com.omvcompanion.app.security

import org.junit.Assert.*
import org.junit.Test

class AppLockStateTest {
    @Test fun `rotation retains authentication without issuing another sign in`() {
        val state = AppLockState()
        state.authenticated()
        state.startupSignIn.value = false
        state.stopped(100, changingConfigurations = true, prompting = false)
        state.started(40_000, enabled = true, prompting = false)
        assertTrue(state.unlocked.value)
        assertFalse(state.startupSignIn.value)
    }
    @Test fun `background timeout still locks but resume does not sign in again`() {
        val state = AppLockState(); state.authenticated(); state.startupSignIn.value = false
        state.stopped(100, false, false); state.started(30_100, true, false)
        assertFalse(state.unlocked.value)
        state.authenticated()
        assertTrue(state.unlocked.value)
        assertFalse(state.startupSignIn.value)
    }
    @Test fun `fresh process requires authentication`() {
        val state = AppLockState()
        assertFalse(state.unlocked.value)
        assertFalse(state.contentStarted.value)
    }
    @Test fun `short background absence retains unlock`() {
        val state = AppLockState(); state.authenticated()
        state.stopped(100, false, false); state.started(29_999, true, false)
        assertTrue(state.unlocked.value)
    }
}
