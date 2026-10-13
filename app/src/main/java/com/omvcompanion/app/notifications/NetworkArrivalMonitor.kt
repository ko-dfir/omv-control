package com.omvcompanion.app.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import com.omvcompanion.app.security.AppPreferences

object NetworkArrivalMonitor {
    private var last = -180_000L
    fun start(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(this@NetworkArrivalMonitor) {
                    val now = SystemClock.elapsedRealtime()
                    if (AppPreferences(context).notifications && now - last >= 180_000) {
                        last = now; ServerNotifications.checkNow(context)
                    }
                }
            }
        })
    }
}
