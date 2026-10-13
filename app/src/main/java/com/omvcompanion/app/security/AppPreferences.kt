package com.omvcompanion.app.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class AppPreferences(context: Context) {
    val store = context.applicationContext.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)
    var hideContent: Boolean
        get() = store.getBoolean("hide_content", true)
        set(value) { store.edit().putBoolean("hide_content", value).apply() }
    val navigationToken: String
        get() = synchronized(NavigationTokenLock) {
            store.getString("nav_token", null) ?: java.security.SecureRandom().let { random ->
                val bytes = ByteArray(32); random.nextBytes(bytes)
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).also {
                    check(store.edit().putString("nav_token", it).commit()) { "Cannot save navigation token" }
                }
            }
        }
    var biometricLock: Boolean
        get() = store.getBoolean("biometric_lock", false)
        set(value) { store.edit().putBoolean("biometric_lock", value).apply() }
    var notifications: Boolean
        get() = store.getBoolean("notifications", false)
        set(value) { store.edit().putBoolean("notifications", value).apply() }
    var storageAlerts: Boolean
        get() = store.getBoolean("storage_alerts", true)
        set(value) { store.edit().putBoolean("storage_alerts", value).apply() }
    var updateAlerts: Boolean
        get() = store.getBoolean("update_alerts", true)
        set(value) { store.edit().putBoolean("update_alerts", value).apply() }
    var intervalMinutes: Int
        get() = store.getInt("interval", 15)
        set(value) { require(value in listOf(15,30,60,180,360,720,1440)); store.edit().putInt("interval", value).apply() }
    var storageThreshold: Int
        get() = store.getInt("storage_threshold", 90)
        set(value) { require(value == 0 || value in 50..99); store.edit().putInt("storage_threshold", value).apply() }
    var temperatureThreshold: Int
        get() = store.getInt("temperature_threshold", 55)
        set(value) { require(value in 30..90); store.edit().putInt("temperature_threshold", value).apply() }
    var ignoredDrives: Set<String>
        get() = store.getStringSet("ignored_drives", emptySet()).orEmpty().toSet()
        set(value) { store.edit().putStringSet("ignored_drives", value).apply() }
    var temperatureAlerts: Boolean
        get() = store.getBoolean("temperature_alerts", true)
        set(value) { store.edit().putBoolean("temperature_alerts", value).apply() }
    var serviceAlerts: Boolean
        get() = store.getBoolean("service_alerts", true)
        set(value) { store.edit().putBoolean("service_alerts", value).apply() }
    var containerAlerts: Boolean
        get() = store.getBoolean("container_alerts", true)
        set(value) { store.edit().putBoolean("container_alerts", value).apply() }
    var imageAlerts: Boolean
        get() = store.getBoolean("image_alerts", false)
        set(value) { store.edit().putBoolean("image_alerts", value).apply() }
    var recoveryAlerts: Boolean
        get() = store.getBoolean("recovery_alerts", true)
        set(value) { store.edit().putBoolean("recovery_alerts", value).apply() }
    var ongoingMonitoring: Boolean
        get() = store.getBoolean("ongoing_monitoring", false)
        set(value) { store.edit().putBoolean("ongoing_monitoring", value).apply() }

}
fun authenticators(): Int = (if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG else BiometricManager.Authenticators.BIOMETRIC_WEAK) or BiometricManager.Authenticators.DEVICE_CREDENTIAL
fun authenticate(activity: FragmentActivity, success: () -> Unit, failure: (String) -> Unit) {
    if (BiometricManager.from(activity).canAuthenticate(authenticators()) != BiometricManager.BIOMETRIC_SUCCESS) {
        failure("Set up a fingerprint, face unlock or screen-lock PIN in Android settings first.")
        return
    }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { success() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { failure(errString.toString()) }
    })
    prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock OMV Control")
        .setSubtitle("Use your biometrics or device screen lock")
        .setAllowedAuthenticators(authenticators()).build())
}

private object NavigationTokenLock
