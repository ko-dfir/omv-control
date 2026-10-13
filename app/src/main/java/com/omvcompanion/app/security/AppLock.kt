package com.omvcompanion.app.security

import android.content.SharedPreferences
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.activity.compose.BackHandler

@Composable fun AppLock(content: @Composable () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val prefs = remember { AppPreferences(activity) }
    var hideContent by remember { mutableStateOf(prefs.hideContent) }
    var enabled by remember { mutableStateOf(prefs.biometricLock) }
    val lock: AppLockState = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = activity)
    val unlocked by lock.unlocked.collectAsState()
    val startupSignIn by lock.startupSignIn.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var prompting by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val unlock = {
        if (!prompting) {
            prompting = true
            authenticate(activity, {
                lock.authenticated(); prompting = false; message = null
            }, { message = it; prompting = false })
        }
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "hide_content") hideContent = prefs.hideContent
            if (key == "biometric_lock") { enabled = prefs.biometricLock; lock.unlocked.value = true }
        }
        prefs.store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lock.stopped(SystemClock.elapsedRealtime(), activity.isChangingConfigurations, prompting)
            if (event == Lifecycle.Event.ON_START) {
                lock.started(SystemClock.elapsedRealtime(), enabled, prompting)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect {
        if (enabled || hideContent) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    val locked = enabled && !unlocked
    BackHandler(enabled = locked) { activity.moveTaskToBack(true) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(locked) { if (locked) { focus.clearFocus(force = true); keyboard?.hide() } }
    if (locked) {
        LaunchedEffect(Unit) { unlock() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("OMV Control is locked", style = MaterialTheme.typography.headlineSmall)
                Text("Authenticate to access your saved servers.")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button({ unlock() }, enabled = !prompting) { Text("Unlock") }
            }
        }
    } else {
        // NavController/saveable screen state live outside this gate. Protected
        // views and dialog windows are removed while locked, then restored.
        SideEffect { lock.contentStarted.value = true }
        CompositionLocalProvider(LocalUnlockSignIn provides UnlockSignIn(startupSignIn) { lock.startupSignIn.value = false }) { content() }
    }
}
