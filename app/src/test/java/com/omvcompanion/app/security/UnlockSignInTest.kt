package com.omvcompanion.app.security

import org.junit.Assert.*
import org.junit.Test

class UnlockSignInTest {
    @Test fun startupUnlockWaitsForSavedCredentials() {
        assertFalse(shouldAutoSignIn(true, true, false, false))
        assertTrue(shouldAutoSignIn(true, true, true, false))
    }
    @Test fun consumedUnlockDoesNotSignInAgainWhenChangingServer() {
        assertFalse(shouldAutoSignIn(false, true, true, false))
    }
    @Test fun newConnectionOrExistingSignInDoesNotTriggerAutomaticLogin() {
        assertFalse(shouldAutoSignIn(true, false, true, false))
        assertFalse(shouldAutoSignIn(true, true, true, true))
    }
}
