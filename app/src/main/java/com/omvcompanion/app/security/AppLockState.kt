package com.omvcompanion.app.security

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory only: survives activity rotation, never process recreation. */
class AppLockState : ViewModel() {
    val unlocked = MutableStateFlow(false)
    val contentStarted = MutableStateFlow(false)
    val startupSignIn = MutableStateFlow(false)
    private var backgroundAt: Long? = null

    fun authenticated() {
        if (!contentStarted.value) startupSignIn.value = true
        contentStarted.value = true
        unlocked.value = true
    }

    fun stopped(now: Long, changingConfigurations: Boolean, prompting: Boolean) {
        if (!changingConfigurations && !prompting) backgroundAt = now
    }

    fun started(now: Long, enabled: Boolean, prompting: Boolean) {
        if (enabled && !prompting && backgroundAt?.let { now - it >= 30_000 } == true) {
            unlocked.value = false
            startupSignIn.value = false
        }
        backgroundAt = null
    }
}
