package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omvcompanion.app.notifications.*
import com.omvcompanion.app.security.AppPreferences
import java.text.DateFormat
import java.util.Date

@Composable fun AlertHistoryScreen(serverId: String) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var events by remember { mutableStateOf(AlertHistory.read(prefs)) }
    var clear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Alert history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton({ events = AlertHistory.read(prefs) }) { Text("Refresh") }
            TextButton({ clear = true }) { Text("Clear history") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val filtered = events.filter { it.serverId == serverId }
            if (filtered.isEmpty()) item { Text("No alert events recorded for this server.") }
            items(filtered) { event -> ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${if (event.recovered) "Recovered" else "Attention"} · ${event.title}", style = MaterialTheme.typography.titleMedium)
                    Text(event.message); Text(DateFormat.getDateTimeInstance().format(Date(event.time)), style = MaterialTheme.typography.bodySmall)
                }
            } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear alert history?") }, text = { Text("Clears history for all saved servers. Current alert states are retained.") }, confirmButton = { TextButton({ prefs.store.edit().remove("alert_history").apply(); events = emptyList(); clear = false }) { Text("Clear") } }, dismissButton = { TextButton({ clear = false }) { Text("Cancel") } })
}
