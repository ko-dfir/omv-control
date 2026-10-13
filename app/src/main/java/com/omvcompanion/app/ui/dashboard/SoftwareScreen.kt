package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.ui.*
import com.omvcompanion.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoftwareScreen(serverId: String, paddingValues: PaddingValues) {
    val vm = appViewModel(key = "software-$serverId") { SoftwareViewModel(serverId, it.softwareRepository) }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    var selected by rememberSaveable(serverId) { mutableStateOf(listOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var pendingPlugin by remember { mutableStateOf<com.omvcompanion.app.data.repo.PluginPackage?>(null) }
    var pluginSearch by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) }
    val info = (state as? UiState.Success)?.data
    val installNames = info?.updates?.map { it.name }?.filter { it in selected }.orEmpty()
    Column(Modifier.fillMaxSize().padding(paddingValues)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) { PageHeading("Updates & plugins", "Keep your OpenMediaVault server current") }
        SecondaryTabRow(tab, containerColor = MaterialTheme.colorScheme.background) {
            listOf("Updates", "Plugins").forEachIndexed { index, label -> Tab(tab == index, { tab = index }, text = { Text(label) }) }
        }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(vm::retry, enabled = !task.busy, modifier = Modifier.weight(1f)) { Text("Refresh list") }
                    OutlinedButton(vm::checkUpdates, enabled = !task.busy, modifier = Modifier.weight(1f)) { Text("Check for updates") }
                    }
                    if (tab == 0) Button({ confirm = true }, modifier = Modifier.fillMaxWidth(), enabled = !task.busy && installNames.isNotEmpty()) { Text(if (task.busy) "Working…" else "Install (${installNames.size})") }
                }

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LaunchedEffect(task.busy) { if (task.busy) listState.scrollToItem(0) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            task.title?.let { item { TaskOutput(it, task.output, task.busy) } }
            task.error?.let { item { EmptyPanel("Task failed", it) } }
            when (val st = state) {
                is UiState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is UiState.Error -> item { EmptyPanel("Unable to load software", st.message); TextButton(vm::retry, enabled = !task.busy) { Text("Retry") } }
                is UiState.Success -> {
                    if (tab == 0) {
                        if (st.data.updates.isEmpty()) item { EmptyPanel("No updates listed", "Use Check for updates to refresh the package database.") }
                        else item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(installNames.size == st.data.updates.size, { selected = if (it) st.data.updates.map { pkg -> pkg.name } else emptyList() }, enabled = !task.busy)
                                Text("Select all ${st.data.updates.size} updates", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        items(st.data.updates, key = { it.name }) { pkg ->
                            ControlPanel(Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(pkg.name in selected, { checked -> selected = if (checked) selected + pkg.name else selected - pkg.name }, enabled = !task.busy)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                                        Text(pkg.version, color = MaterialTheme.colorScheme.primary)
                                        Text(pkg.repository, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    } else {
                        item { OutlinedTextField(pluginSearch, { pluginSearch = it }, label = { Text("Search plugins") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                        st.data.pluginError?.let { item { EmptyPanel("Plugin list unavailable", it) } }
                        items(st.data.plugins.filter { it.name.contains(pluginSearch, true) || it.description.contains(pluginSearch, true) }.sortedByDescending { it.installed }, key = { it.name }) { pkg ->
                            ControlPanel(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (pkg.installed) "Installed / ${pkg.version}" else "Available / ${pkg.version}", color = MaterialTheme.colorScheme.primary)
                                    if (!pkg.installed) OutlinedButton({ pendingPlugin = pkg }, enabled = !task.busy) { Text("Install plugin") }
                                    Text(pkg.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingPlugin?.let { plugin ->
        AlertDialog(onDismissRequest = { pendingPlugin = null }, title = { Text("Install ${plugin.name}?") },
            text = { Text("Version ${plugin.version} and required dependencies will be installed on your OMV server. Services may restart. Keep the app open to follow installation progress.") },
            confirmButton = { TextButton({ pendingPlugin = null; vm.installPlugin(plugin.name) }) { Text("Install plugin") } },
            dismissButton = { TextButton({ pendingPlugin = null }) { Text("Cancel") } })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Install ${installNames.size} updates?") },
        text = { Text("Selected packages and their required dependencies will be installed. Services may restart. Keep the app open to follow progress; a reboot may be required afterward.") },
        confirmButton = { TextButton({ confirm = false; vm.install(installNames) }) { Text("Install updates") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
