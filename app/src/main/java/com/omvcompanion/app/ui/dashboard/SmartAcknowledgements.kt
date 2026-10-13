package com.omvcompanion.app.ui.dashboard

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.omvcompanion.app.data.repo.SmartData

internal fun smartWarningSignature(smart: SmartData) = "${smart.deviceFile}|${smart.overallStatus?.uppercase()}"
internal fun retainedAcknowledgements(entries: Set<String>, readings: List<SmartData>): Set<String> {
    val observed = readings.filter { !it.unavailable && !it.overallStatus.isNullOrBlank() && !it.overallStatus.equals("UNKNOWN", true) }
        .associate { it.deviceFile to smartWarningSignature(it) }
    return entries.filter { key -> observed[key.substringBeforeLast('|')]?.let { it == key } ?: true }.toSet()
}
internal data class SmartAcknowledgements(val entries: Set<String>, val acknowledge: (SmartData) -> Unit, val restore: (SmartData) -> Unit) {
    fun contains(smart: SmartData) = smartWarningSignature(smart) in entries
}

@Composable internal fun rememberSmartAcknowledgements(serverId: String, readings: List<SmartData>): SmartAcknowledgements {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("smart_acknowledgements", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs, serverId) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == serverId) revision++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val entries = remember(serverId, revision) { prefs.getStringSet(serverId, emptySet()).orEmpty().toSet() }
    LaunchedEffect(readings, entries) {
        val retained = retainedAcknowledgements(entries, readings)
        if (retained != entries) prefs.edit().putStringSet(serverId, retained).apply()
    }
    return SmartAcknowledgements(entries,
        { smart -> prefs.edit().putStringSet(serverId, entries + smartWarningSignature(smart)).apply() },
        { smart -> prefs.edit().putStringSet(serverId, entries - smartWarningSignature(smart)).apply() })
}
