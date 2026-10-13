package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SystemService
import com.omvcompanion.app.ui.UiState
import com.omvcompanion.app.ui.appViewModel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.omvcompanion.app.ui.components.*
import com.omvcompanion.app.data.repo.serviceUnits

@Composable
fun ServicesScreen(serverId: String, paddingValues: PaddingValues, initialSearch: String = "") {
    val vm = appViewModel<ServicesViewModel>(key = "services-$serverId") {
        ServicesViewModel(serverId, it.servicesRepository)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<SystemService?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        when (val st = state) {
            is UiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is UiState.Error -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(st.message)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::retry) { Text("Retry") }
                }
            }
            is UiState.Success -> {
                ServicesContent(st.data, busy, message, initialSearch) { pending = it }
            }
        }
    }
    pending?.let { service ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("${if (service.running) "Stop" else "Start"} ${service.title}?") },
            text = { Text("This uses your saved SSH credentials and changes the running service. Startup settings are unchanged. ${if (service.running) "Connected applications may be interrupted." else ""} ${if (service.running && service.name.lowercase() in setOf("ssh", "sshd")) "Stopping SSH also disables app service controls. Re-enable SSH from the OMV web interface or the server console." else ""}") },
            confirmButton = { TextButton({ pending = null; vm.control(service, if (service.running) "stop" else "start") }) { Text("Confirm") } }, dismissButton = { TextButton({ pending = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ServicesContent(services: List<SystemService>, busy: Boolean, message: String?, initialSearch: String, control: (SystemService) -> Unit) {
    var search by remember { mutableStateOf(initialSearch) }
    LaunchedEffect(initialSearch) { search = initialSearch }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(search, { search = it }, placeholder = { Text("Search services") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            PageHeading("Services", "${services.count { it.running }} running · ${services.count { it.enabled }} enabled at startup")
            Text("Start and stop require SSH sign-in and permission to manage system services.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp)) }
        }
        if (services.isEmpty()) {
            item {
                Text("No services found.", modifier = Modifier.padding(16.dp))
            }
        } else {
            items(services.filter { it.name.contains(search, true) || it.title.contains(search, true) }) { svc ->
                ServiceCard(svc, busy, control)
            }
        }
    }
}

@Composable
private fun ServiceCard(svc: SystemService, busy: Boolean, control: (SystemService) -> Unit) {
    ControlPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(svc.title, style = MaterialTheme.typography.titleMedium)
                Text(if (svc.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (serviceUnits(svc.name).isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                OutlinedButton({ control(svc) }, enabled = !busy, shape = MaterialTheme.shapes.small) { Text(if (svc.running) "Stop" else "Start") }
            } else Text("View only", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            if (svc.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Running", tint = MaterialTheme.colorScheme.primary) // Green
                    Spacer(Modifier.width(4.dp))
                    Text("Running", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val stoppedColor = if (svc.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Error, contentDescription = null, tint = stoppedColor)
                    Spacer(Modifier.width(4.dp))
                    Text(if (svc.enabled) "Stopped" else "Inactive", style = MaterialTheme.typography.bodyMedium, color = stoppedColor)
                }
            }
        }
    }
}
