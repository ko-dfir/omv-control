package com.omvcompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.*
import com.omvcompanion.app.MainActivity
import com.omvcompanion.app.OmvApp
import com.omvcompanion.app.R
import com.omvcompanion.app.security.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ServerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { render(context); schedule(context); refresh(context) }
    override fun onEnabled(context: Context) { schedule(context); refresh(context) }
    override fun onDisabled(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("omv-widget"); WorkManager.getInstance(context).cancelUniqueWork("omv-widget-now") }
    override fun onReceive(context: Context, intent: Intent) { super.onReceive(context, intent); if (intent.action == REFRESH) refresh(context) }
    companion object {
        const val REFRESH = "com.omvcompanion.app.WIDGET_REFRESH"
        fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ServerWidget::class.java))
        fun schedule(context: Context) { if (ids(context).isEmpty()) return
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("omv-widget", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun refresh(context: Context) { if (ids(context).isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork("omv-widget-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun render(context: Context) {
            val prefs = AppPreferences(context)
            val views = RemoteViews(context.packageName, R.layout.server_widget)
            val locked = prefs.biometricLock
            views.setTextViewText(R.id.widget_title, if (locked) "OMV Control" else prefs.store.getString("widget_title", "OMV Control"))
            views.setTextViewText(R.id.widget_summary, if (locked) "Unlock the app to view your server." else prefs.store.getString("widget_summary", "Sign in to a server to see its status here."))
            views.setTextViewText(R.id.widget_time, prefs.store.getString("widget_time", "Tap Refresh to check"))
            val open = PendingIntent.getActivity(context, 1801, Intent(context, MainActivity::class.java).putExtra("nav_token", AppPreferences(context).navigationToken).putExtra("nav_target", 0).putExtra("server_id", prefs.store.getString("widget_server", null)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            val refresh = PendingIntent.getBroadcast(context, 1802, Intent(context, ServerWidget::class.java).setAction(REFRESH), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, ServerWidget::class.java), views)
        }
    }
}

class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (ServerWidget.ids(applicationContext).isEmpty()) return Result.success()
        val prefs = AppPreferences(applicationContext)
        val container = (applicationContext as OmvApp).container
        val profile = try {
            container.serverRepository.selectedServerId.first()?.let { container.serverRepository.get(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            prefs.store.edit().putString("widget_summary", e.message ?: "Cannot read saved server settings").apply()
            ServerWidget.render(applicationContext); return Result.success()
        }
        if (profile == null) { prefs.store.edit().remove("widget_title").remove("widget_summary").remove("widget_server").remove("widget_time").apply(); ServerWidget.render(applicationContext); return Result.success() }
        prefs.store.edit().putString("widget_server", profile.id).apply()
        try {
            withTimeout(100_000) {
                val system = container.systemRepository.getInformation(profile.id)
                val files = try { container.storageRepository.getFileSystems(profile.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val smart = try { container.storageRepository.getSmart(profile.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
                val status = if (smart.isEmpty()) "SMART unavailable" else "${smart.count { !it.unavailable }} readable drives · ${com.omvcompanion.app.notifications.smartAlerts(smart).size} health warnings"
                val mounted = files.filter { it.isMounted }.sortedByDescending { it.percentage }
                val volumes = mounted.take(5).joinToString("\n") { "${it.description?.substringBefore(" [") ?: it.deviceFile}: ${it.percentage}% used" } +
                    if (mounted.size > 5) "\n+${mounted.size - 5} more volumes · open app to view all" else ""
                val cpu = system.cpuUsagePercent?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
                prefs.store.edit().putString("widget_server", profile.id).putString("widget_title", profile.name)
                    .putString("widget_summary", "CPU $cpu% · RAM ${system.memUsedFraction?.times(100)?.toInt() ?: "—"}%\n$status\n${volumes.ifBlank { "Storage unavailable" }}")
                    .putString("widget_time", "Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}").apply()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            prefs.store.edit().putString("widget_summary", "Check timed out. Open the app to review your connection.").putString("widget_time", "Latest check failed").apply()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { prefs.store.edit().putString("widget_title", profile.name).putString("widget_summary", if (e is com.omvcompanion.app.data.local.CredentialUnavailableException) e.message.orEmpty() else if (e is com.omvcompanion.app.data.rpc.UntrustedCertificateException) "Certificate approval is required. Open the app." else "Unable to refresh. Open the app to check your connection or sign in again.").putString("widget_time", "Latest check failed").apply() }
        ServerWidget.render(applicationContext)
        return Result.success()
    }
}
