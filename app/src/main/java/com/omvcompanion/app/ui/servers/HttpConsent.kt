package com.omvcompanion.app.ui.servers

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import com.omvcompanion.app.data.rpc.normalizeBaseUrl

@Composable internal fun HttpConsent(primary: String, fallback: String, allowed: Boolean, change: (Boolean) -> Unit, error: String? = null) {
    if (listOf(primary, fallback).any { runCatching { normalizeBaseUrl(it).startsWith("http://") }.getOrDefault(false) }) {
        Text("This address is not encrypted. Your OMV password will be sent in plain text on the network.", color = MaterialTheme.colorScheme.error)
        httpConsentError(primary, fallback, allowed, error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row { Checkbox(allowed, change); Text("I understand — allow unencrypted HTTP for this server") }
    }
}

internal fun httpConsentError(primary: String, fallback: String, allowed: Boolean, error: String?): String? {
    val http = listOf(primary, fallback).any { runCatching { normalizeBaseUrl(it).startsWith("http://") }.getOrDefault(false) }
    return if (http && !allowed) error?.takeIf { it.contains("HTTP", true) } ?: "Unencrypted HTTP is disabled for this saved server. Tick the checkbox below to opt in, or use HTTPS." else null
}
