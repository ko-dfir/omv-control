package com.omvcompanion.app.ui

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

data class ServerDestination(val serverId: String?, val tab: Int)
object PendingNavigation {
    val destination = MutableStateFlow<ServerDestination?>(null)
    fun accept(intent: Intent?, expectedToken: String, savedIds: Set<String>) {
        if (intent?.hasExtra("nav_target") == true && authorizedNavigation(intent.getStringExtra("nav_token"), expectedToken, intent.getStringExtra("server_id"), savedIds)) destination.value = ServerDestination(intent.getStringExtra("server_id"), intent.getIntExtra("nav_target", 0).coerceIn(0, 9))
    }
}

internal fun authorizedNavigation(token: String?, expected: String, serverId: String?, savedIds: Set<String>): Boolean =
    token != null && expected.isNotEmpty() && java.security.MessageDigest.isEqual(token.toByteArray(), expected.toByteArray()) && serverId != null && serverId in savedIds
