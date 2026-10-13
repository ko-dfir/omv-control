package com.omvcompanion.app.security

import androidx.compose.runtime.staticCompositionLocalOf

/** One-use, in-memory permission to reuse saved credentials after startup unlock. */
data class UnlockSignIn(val pending: Boolean = false, val consume: () -> Unit = {})
val LocalUnlockSignIn = staticCompositionLocalOf { UnlockSignIn() }

internal fun shouldAutoSignIn(pending: Boolean, savedServer: Boolean, credentialsLoaded: Boolean, busy: Boolean): Boolean =
    pending && savedServer && credentialsLoaded && !busy
