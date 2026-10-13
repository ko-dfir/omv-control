package com.omvcompanion.app.security

import androidx.compose.runtime.*
import androidx.activity.compose.LocalActivity
import android.view.WindowManager

@Composable fun SensitiveScreen() {
    val activity = LocalActivity.current ?: return
    SideEffect { activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    DisposableEffect(activity) {
        onDispose {
            val prefs = AppPreferences(activity)
            if (!prefs.biometricLock && !prefs.hideContent) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
