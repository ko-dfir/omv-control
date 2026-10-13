package com.omvcompanion.app.notifications

import com.omvcompanion.app.security.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable data class AlertEvent(val time: Long, val serverId: String, val server: String, val key: String, val title: String, val message: String, val recovered: Boolean)
object AlertHistory {
    @Synchronized fun read(prefs: AppPreferences): List<AlertEvent> = runCatching {
        Json.decodeFromString(ListSerializer(AlertEvent.serializer()), prefs.store.getString("alert_history", "[]").orEmpty())
    }.getOrDefault(emptyList())
    @Synchronized fun add(prefs: AppPreferences, event: AlertEvent) {
        prefs.store.edit().putString("alert_history", Json.encodeToString(ListSerializer(AlertEvent.serializer()), (listOf(event) + read(prefs)).take(300))).apply()
    }
}
