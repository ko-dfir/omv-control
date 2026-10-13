package com.omvcompanion.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.omvcompanion.app.MainActivity
import com.omvcompanion.app.OmvApp
import com.omvcompanion.app.R
import com.omvcompanion.app.security.AppPreferences
import com.omvcompanion.app.data.rpc.OmvAuthException
import com.omvcompanion.app.data.rpc.OmvConnectionException
import com.omvcompanion.app.data.rpc.UntrustedCertificateException
import com.omvcompanion.app.data.rpc.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

object ServerNotifications {
    const val CHANNEL = "server_alerts"
    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) { work.cancelUniqueWork("omv-monitor"); work.cancelUniqueWork("omv-check-now"); context.getSystemService(NotificationManager::class.java).cancelAll(); return }
        work.enqueueUniquePeriodicWork("omv-monitor", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ServerMonitorWorker>(AppPreferences(context).intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("omv-check-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ServerMonitorWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun show(context: Context, id: Int, title: String, message: String, target: Int = 0, serverId: String? = null, ongoing: Boolean = false): Boolean {
        if (!AppPreferences(context).notifications || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Server alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val channel = if (ongoing) "monitoring" else when (target) { 1 -> "disk_health"; 2 -> "services"; 3 -> "updates"; 4 -> "containers"; else -> CHANNEL }
        manager.createNotificationChannel(NotificationChannel(channel, if (ongoing) "Background monitoring" else when(target) { 1 -> "Drive and storage alerts"; 2 -> "Service alerts"; 3 -> "Update alerts"; 4 -> "Container alerts"; else -> "Server alerts" }, if (ongoing) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java).putExtra("nav_token", AppPreferences(context).navigationToken).putExtra("nav_target", target).putExtra("server_id", serverId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(intent).setOngoing(ongoing).setAutoCancel(!ongoing).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try { manager.notify(id, notification); true } catch (_: SecurityException) { false }
    }
}

class ServerMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = monitorMutex.withLock {
        val prefs = AppPreferences(applicationContext)
        if (!prefs.notifications) return@withLock Result.success()
        val container = (applicationContext as OmvApp).container
        val profile = try {
            container.serverRepository.selectedServerId.first()?.let { container.serverRepository.get(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            prefs.store.edit().putString("monitor_status", e.message ?: "Cannot read saved server settings").apply()
            return@withLock Result.success()
        }
        if (profile == null) { prefs.store.edit().putString("monitor_status", "Choose a saved server first.").apply(); return@withLock Result.success() }
        fun updateSection(section: String, alerts: List<ServerAlert>, retain: Set<String> = emptySet()) {
            if (!prefs.notifications || !NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return
            val key = "alerts:${profile.id}:$section"
            val old = prefs.store.getStringSet(key, emptySet()).orEmpty().toSet()
            val current = retainedAlertKeys(old, alerts.map { it.key }.toSet(), retain)
            val delta = transitions(old, current)
            val target = when(section) { "capacity", "smart", "temperature" -> 1; "services" -> 2; "system" -> 3; "containers", "images" -> 4; else -> 0 }
            for (alert in alerts.filter { it.key in delta.fired }) {
                if (ServerNotifications.show(applicationContext, ("${profile.id}:${alert.key}").hashCode(), "${profile.name} · ${alert.title}", alert.message, target, profile.id))
                    AlertHistory.add(prefs, AlertEvent(System.currentTimeMillis(), profile.id, profile.name, alert.key, alert.title, alert.message, false))
            }
            for (resolved in delta.recovered) {
                applicationContext.getSystemService(NotificationManager::class.java).cancel(("${profile.id}:$resolved").hashCode())
                val prior = AlertHistory.read(prefs).firstOrNull { it.serverId == profile.id && it.key == resolved && !it.recovered }
                val title = prior?.title ?: resolved.substringBefore(':').replaceFirstChar(Char::titlecase)
                val message = "The previously reported issue has cleared."
                AlertHistory.add(prefs, AlertEvent(System.currentTimeMillis(), profile.id, profile.name, resolved, title, message, true))
                if (prefs.recoveryAlerts) ServerNotifications.show(applicationContext, ("${profile.id}:$resolved:recovered").hashCode(), "${profile.name} · Recovered", "$title. $message", target, profile.id)
            }
            prefs.store.edit().putStringSet(key, current).apply()
        }
        fun silence(section: String) {
            val key = "alerts:${profile.id}:$section"
            prefs.store.getStringSet(key, emptySet()).orEmpty().forEach { alert ->
                applicationContext.getSystemService(NotificationManager::class.java).cancel(("${profile.id}:$alert").hashCode())
            }
            prefs.store.edit().remove(key).apply()
        }
        if (!prefs.storageAlerts) { silence("capacity"); silence("smart") }
        if (!prefs.temperatureAlerts) silence("temperature")
        if (!prefs.serviceAlerts) silence("services")
        if (!prefs.containerAlerts) silence("containers")
        if (!prefs.imageAlerts) silence("images")
        if (!prefs.updateAlerts) silence("system")
        try {
            withTimeout(120_000) {
                val info = container.systemRepository.getInformation(profile.id)
                prefs.store.edit().putInt("failures:${profile.id}", 0).apply()
                updateSection("connection", emptyList())
                updateSection("system", buildList {
                    if (prefs.updateAlerts && info.updatesAvailable > 0) add(ServerAlert("updates", "Updates available", "${info.updatesAvailable} package updates are listed. Open Updates to review and install them."))
                    if (prefs.updateAlerts && info.rebootRequired) add(ServerAlert("reboot", "Restart required", "Restart the server when convenient to complete its updates."))
                })
                val errors = mutableListOf<String>()
                if (prefs.storageAlerts) {
                    try { updateSection("capacity", storageAlerts(container.storageRepository.getFileSystems(profile.id), prefs.storageThreshold)) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "Storage unavailable" }
                }
                if (prefs.storageAlerts || prefs.temperatureAlerts) {
                    try {
                        val all = container.storageRepository.getSmart(profile.id)
                        val devices = all.filter { it.deviceFile !in prefs.ignoredDrives }
                        val healthReadable = devices.filter { !it.unavailable }.map { "smart:${it.deviceFile}" }.toSet()
                        val temperatureReadable = devices.filter { !it.unavailable && it.temperatureCelsius != null }.map { "temperature:${it.deviceFile}" }.toSet()
                        fun unknown(section: String, known: Set<String>): Set<String> = prefs.store.getStringSet("alerts:${profile.id}:$section", emptySet()).orEmpty().filter { key -> key !in known && key.substringAfter(':') !in prefs.ignoredDrives }.toSet()
                        if (prefs.storageAlerts) updateSection("smart", smartAlerts(devices), unknown("smart", healthReadable))
                        if (prefs.temperatureAlerts) updateSection("temperature", temperatureAlerts(devices, prefs.temperatureThreshold), unknown("temperature", temperatureReadable))
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "SMART or temperatures unavailable" }
                }
                if (prefs.serviceAlerts) {
                    try {
                        val services = container.servicesRepository.getServices(profile.id)
                        val baselineKey = "baseline:services:${profile.id}"
                        val running = services.filter { it.running }.map { it.name }.toSet()
                        val baseline = prefs.store.getStringSet(baselineKey, emptySet()).orEmpty().toSet() + running
                        prefs.store.edit().putStringSet(baselineKey, baseline).apply()
                        updateSection("services", services.filter { !it.running && it.name in baseline }.map { ServerAlert("service:${it.name}", "Service stopped", "${it.title} was previously running and has stopped.") })
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "Services unavailable" }
                }
                if (prefs.containerAlerts) {
                    try {
                        val rows = com.omvcompanion.app.data.repo.records(container.sessionManager.client(profile.id).call("Compose", "getContainerList", com.omvcompanion.app.data.repo.listParams()))
                        val running = rows.filter { it.str("state").equals("running", true) }.mapNotNull { it.str("id") }.toSet()
                        val baselineKey = "baseline:containers:${profile.id}"
                        val baseline = prefs.store.getStringSet(baselineKey, emptySet()).orEmpty().toSet() + running
                        prefs.store.edit().putStringSet(baselineKey, baseline).apply()
                        updateSection("containers", rows.filter { (it.str("id") in baseline && !it.str("state").equals("running", true)) || it.str("status").orEmpty().contains("unhealthy", true) || it.str("state").equals("restarting", true) }.map {
                            ServerAlert("container:${it.str("id")}", "Container needs attention", "${it.str("name")}: ${it.str("status") ?: it.str("state")}. Review the container logs.")
                        })
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "Containers unavailable" }
                }
                if (prefs.imageAlerts) {
                    try {
                        val rows = container.managementRepository.list(profile.id, "Images")
                        val unknown = rows.filter { it.str("status")?.uppercase() in setOf("NA", "FAILED", "TIMEOUT") }.map { "image:${it.str("image") ?: it.str("name")}" }.toSet()
                        updateSection("images", rows.filter { com.omvcompanion.app.data.repo.imageUpdateAvailable(it) }.map { ServerAlert("image:${it.str("image") ?: it.str("name")}", "Container image update available", "${it.str("image") ?: it.str("name")}. Review dependencies and back up persistent data before updating.") }, unknown)
                    }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "Image checks unavailable" }
                }
                if (prefs.ongoingMonitoring) ServerNotifications.show(applicationContext, 98702, "OMV Control monitoring", "${profile.name} · Checks about every ${prefs.intervalMinutes} minutes.", ongoing = true)
                else applicationContext.getSystemService(NotificationManager::class.java).cancel(98702)
                com.omvcompanion.app.diagnostics.AppLog.write("Monitor", if (errors.isEmpty()) "Check completed" else errors.joinToString("; "))
                prefs.store.edit().putString("monitor_status", if (errors.isEmpty()) "Check complete for ${profile.name}." else "Checked ${profile.name}. ${errors.joinToString("; ")}").putLong("monitor_checked", System.currentTimeMillis()).apply()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            prefs.store.edit().putString("monitor_status", "The check timed out. It will run again at the next scheduled check.").putLong("monitor_checked", System.currentTimeMillis()).apply()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            monitoringSignInAlert(e)?.let { updateSection("connection", listOf(it)) }
            when (e) {
                is OmvConnectionException -> {
                    val failures = prefs.store.getInt("failures:${profile.id}", 0) + 1
                    prefs.store.edit().putInt("failures:${profile.id}", failures).apply()
                    if (failures >= 2) updateSection("connection", listOf(ServerAlert("connection", "Server connection failed", "The server could not be reached on two consecutive checks. Check the server and your network or VPN connection.")))
                }
            }
            prefs.store.edit().putString("monitor_status", "Last check failed: ${e.message}").putLong("monitor_checked", System.currentTimeMillis()).apply()
        }
        Result.success()
    }
    companion object { private val monitorMutex = Mutex() }
}

internal fun monitoringSignInAlert(error: Exception): ServerAlert? = when (error) {
    is com.omvcompanion.app.data.local.CredentialUnavailableException -> ServerAlert("sign-in", "Monitoring needs sign-in", "Open OMV Control and re-enter the server password.")
    is OmvAuthException, is UntrustedCertificateException -> ServerAlert("sign-in", "Monitoring needs sign-in", "Open OMV Control and sign in again. Server authentication or certificate approval is required.")
    else -> null
}
