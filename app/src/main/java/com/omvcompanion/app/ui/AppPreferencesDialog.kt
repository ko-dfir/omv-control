package com.omvcompanion.app.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import com.omvcompanion.app.notifications.ServerNotifications
import com.omvcompanion.app.security.AppPreferences
import com.omvcompanion.app.security.authenticate
import java.text.DateFormat
import java.util.Date

@Composable fun AppPreferencesDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val prefs = remember { AppPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var authenticating by remember { mutableStateOf(false) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        prefs.store.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.store.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val notifications = remember(revision) { prefs.notifications }
    val biometric = remember(revision) { prefs.biometricLock }
    val hideContent = remember(revision) { prefs.hideContent }
    fun enableNotifications(enabled: Boolean) {
        prefs.notifications = enabled
        ServerNotifications.schedule(context, enabled)
        if (enabled) ServerNotifications.checkNow(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableNotifications(true) else message = "Notifications were not allowed. You can enable them in Android app settings."
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("App preferences") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric unlock", style = MaterialTheme.typography.titleSmall)
                    Text("Protects app screens with fingerprint, face or device PIN. Saved passwords are protected by Android's keystore. Locks on launch and after 30 seconds away.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(biometric, { enabled ->
                    authenticating = true
                    authenticate(activity, { prefs.biometricLock = enabled; com.omvcompanion.app.widget.ServerWidget.render(context); authenticating = false; message = null }, { message = it; authenticating = false })
                }, enabled = !authenticating)
            }
            Text("Your saved OMV credentials remain encrypted. Server passwords and authenticator codes are still verified by OMV. App lock protects screens; background checks continue while the device is locked. If an older saved key cannot be read, unlock the device and re-enter the password.", style = MaterialTheme.typography.bodySmall)
            Text(captureProtectionText(biometric, hideContent), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hide content in Recents and screenshots", Modifier.weight(1f))
                Switch(hideContent, { prefs.hideContent = it })
            }
            HorizontalDivider()
            Text("Home-screen widget", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Shows the selected server's health, system and storage snapshot. Refreshes about every 15 minutes; server details are hidden when app lock is enabled.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({
                val manager = android.appwidget.AppWidgetManager.getInstance(context)
                if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(context, com.omvcompanion.app.widget.ServerWidget::class.java), null, null)
                else message = "Add OMV Control status from your launcher's Widgets menu."
            }) { Text("Add status widget") }
            HorizontalDivider()
            Text("Server notifications", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background monitoring", style = MaterialTheme.typography.titleSmall)
                    Text("Checks your selected server approximately every ${prefs.intervalMinutes} minutes when the tablet has a network connection. Android may delay checks to save battery.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(notifications, { enabled ->
                    if (!enabled) enableNotifications(false)
                    else if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (NotificationManagerCompat.from(context).areNotificationsEnabled()) enableNotifications(true)
                    else message = "Allow notifications in Android app settings first."
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Storage capacity and confirmed SMART warnings", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(prefs.storageAlerts, { prefs.storageAlerts = it }, enabled = notifications)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Updates and reboot required", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(prefs.updateAlerts, { prefs.updateAlerts = it }, enabled = notifications)
            }
            Text("Monitoring options", style = MaterialTheme.typography.titleSmall)
            var intervalMenu by remember { mutableStateOf(false) }
            Box { OutlinedButton({ intervalMenu = true }, enabled = notifications) { Text("Interval: ${prefs.intervalMinutes} minutes") }
                DropdownMenu(intervalMenu, { intervalMenu = false }) { listOf(15,30,60,180,360,720,1440).forEach { value -> DropdownMenuItem(text = { Text("$value minutes") }, onClick = { prefs.intervalMinutes = value; intervalMenu = false; ServerNotifications.schedule(context, prefs.notifications) }) } } }
            Text("Storage warning: ${if (prefs.storageThreshold == 0) "Use each volume's OMV threshold" else "${prefs.storageThreshold}%"}", style = MaterialTheme.typography.bodySmall)
            Slider(prefs.storageThreshold.takeIf { it > 0 }?.toFloat() ?: 85f, { prefs.storageThreshold = it.toInt() }, valueRange = 50f..99f, steps = 48, enabled = notifications)
            TextButton({ prefs.storageThreshold = 0 }, enabled = notifications) { Text("Use volume thresholds") }
            Row { Text("Drive temperature ≥${prefs.temperatureThreshold}°C", Modifier.weight(1f)); Switch(prefs.temperatureAlerts, { prefs.temperatureAlerts = it }, enabled = notifications) }
            Slider(prefs.temperatureThreshold.toFloat(), { prefs.temperatureThreshold = it.toInt() }, valueRange = 30f..90f, steps = 59, enabled = notifications)
            Row { Text("Stopped services", Modifier.weight(1f)); Switch(prefs.serviceAlerts, { prefs.serviceAlerts = it }, enabled = notifications) }
            Row { Text("Stopped or unhealthy containers", Modifier.weight(1f)); Switch(prefs.containerAlerts, { prefs.containerAlerts = it }, enabled = notifications) }
            Row { Text("Image updates (Compose / ImageCheck)", Modifier.weight(1f)); Switch(prefs.imageAlerts, { prefs.imageAlerts = it }, enabled = notifications) }
            Row { Text("Notify when issues recover", Modifier.weight(1f)); Switch(prefs.recoveryAlerts, { prefs.recoveryAlerts = it }, enabled = notifications) }
            Row { Text("Ongoing monitoring notification", Modifier.weight(1f)); Switch(prefs.ongoingMonitoring, { prefs.ongoingMonitoring = it; ServerNotifications.checkNow(context) }, enabled = notifications) }
            var ignored by remember { mutableStateOf(prefs.ignoredDrives.joinToString(", ")) }
            OutlinedTextField(ignored, { ignored = it }, label = { Text("Ignored SMART devices (comma-separated /dev paths)") }, modifier = Modifier.fillMaxWidth(), enabled = notifications)
            TextButton({ prefs.ignoredDrives = ignored.split(',').map(String::trim).filter(String::isNotEmpty).toSet(); message = "Ignored devices saved." }, enabled = notifications) { Text("Save ignored devices") }
            TextButton({
                val editor = prefs.store.edit()
                prefs.store.all.keys.filter { it.startsWith("alerts:") || it.startsWith("baseline:") }.forEach(editor::remove)
                editor.apply(); message = "Current alerts and running baselines reset. The next check establishes a new baseline."
            }, enabled = notifications) { Text("Reset current alerts and baselines") }
            Text("Connection warnings require two failed checks. Unreadable SMART devices are not reported as failed. Alerts repeat only after an issue clears and returns.", style = MaterialTheme.typography.bodySmall)
            Text("With OMV two-factor authentication, monitoring requires an active verified session. Sign in again when the session expires.", style = MaterialTheme.typography.bodySmall)
            if (notifications) {
                Text(prefs.store.getString("monitor_status", "Waiting for the first check.").orEmpty(), style = MaterialTheme.typography.bodySmall)
                val checked = prefs.store.getLong("monitor_checked", 0)
                if (checked > 0) Text("Last check: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(checked))}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ ServerNotifications.checkNow(context); message = "Check queued. Results will appear here." }) { Text("Check now") }
                    TextButton({ message = if (ServerNotifications.show(context, 98701, "OMV Control", "Test notification. Server alerts are enabled.")) "Test notification sent." else "Notifications are blocked. Allow them in Android app settings." }) { Text("Test alert") }
                }
            }
            TextButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Android app settings") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClose) { Text("Done") } })
}

internal fun captureProtectionText(biometric: Boolean, hideContent: Boolean): String =
    if (biometric || hideContent) "Screen captures, screen mirroring and Recents thumbnails are hidden."
    else "Screen captures are allowed; the SSH and sign-in screens are always hidden."
