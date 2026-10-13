package com.omvcompanion.app

import android.app.Application

class OmvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.omvcompanion.app.diagnostics.AppLog.init(this)
        container = AppContainer(this)
        com.omvcompanion.app.widget.ServerWidget.schedule(this)
        com.omvcompanion.app.notifications.NetworkArrivalMonitor.start(this)
        com.omvcompanion.app.notifications.ServerNotifications.schedule(this, com.omvcompanion.app.security.AppPreferences(this).notifications)
    }
}
