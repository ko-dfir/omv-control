package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.AppContainer
import com.omvcompanion.app.data.repo.records
import com.omvcompanion.app.data.repo.listParams
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class NavigationHit(val title: String, val subtitle: String, val tab: Int, val query: String = "", val section: String? = null)
data class NavigationSearchState(val hits: List<NavigationHit> = emptyList(), val loading: Boolean = true, val failed: List<String> = emptyList())
class NavigationSearchViewModel(private val serverId: String, private val app: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(NavigationSearchState())
    val state = _state.asStateFlow()
    init { refresh() }
    fun refresh() {
        viewModelScope.launch {
            _state.value = NavigationSearchState()
            coroutineScope {
                suspend fun add(category: String, load: suspend () -> List<NavigationHit>) {
                    try { val result = load(); _state.update { it.copy(hits = it.hits + result) } }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { _state.update { it.copy(failed = it.failed + category) } }
                }
                launch { add("containers") { records(app.sessionManager.client(serverId).call("Compose", "getContainerList", listParams())).map { NavigationHit(it.str("name") ?: "Container", "Container · ${it.str("image").orEmpty()}", 4, it.str("name").orEmpty()) } } }
                launch { add("drives") { app.storageRepository.getDisks(serverId).map { NavigationHit(it.model ?: it.deviceFile, "Drive · ${it.deviceFile}", 1, it.deviceFile, "Drives") } } }
                launch { add("shared folders") { app.storageRepository.getSharedFolders(serverId).map { NavigationHit(it.name, "Shared folder · ${it.description.orEmpty()}", 9, section = it.uuid) } } }
                launch { add("services") { app.servicesRepository.getServices(serverId).map { NavigationHit(it.title, "Service · ${if (it.running) "Running" else "Stopped"}", 2, it.name) } } }
                launch { add("users") { app.managementRepository.list(serverId, "Users").map { NavigationHit(it.str("name") ?: "User", "User · ${it.str("comment").orEmpty()}", 7, it.str("name").orEmpty(), "Users") } } }
                launch { add("scheduled jobs") { app.managementRepository.list(serverId, "Jobs").map { val command = it.str("command").orEmpty(); NavigationHit(it.str("comment")?.takeIf(String::isNotBlank) ?: command, "Scheduled job · ${it.str("username").orEmpty()}", 7, command, "Jobs") } } }
            }
            _state.update { it.copy(loading = false) }
        }
    }
}

@Composable fun NavigationSearchDialog(serverId: String, destinations: List<String>, dismiss: () -> Unit, navigate: (NavigationHit) -> Unit) {
    val vm = appViewModel(key = "navigation-search-$serverId") { NavigationSearchViewModel(serverId, it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Search your server") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Containers, drives, shares, services…") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Filled.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear search") } })
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val pages = destinations.mapIndexed { index, label -> NavigationHit(label, "Open page", index) }
            val matches = (pages + state.hits).filter { query.isBlank() && it.subtitle == "Open page" || query.isNotBlank() && (it.title.contains(query, true) || it.subtitle.contains(query, true) || it.query.contains(query, true)) }.take(60)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(matches) { hit -> Row(Modifier.fillMaxWidth().clickable { navigate(hit); dismiss() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (hit.tab) { 1 -> Icons.Filled.Storage; 2 -> Icons.Filled.Settings; 4 -> Icons.Filled.Widgets; 8 -> Icons.Filled.QueryStats; 9 -> Icons.Filled.Folder; else -> Icons.Filled.ChevronRight }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(hit.title, style = MaterialTheme.typography.titleSmall); Text(hit.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
                if (matches.isEmpty() && !state.loading) item { Text("No matches. Try a name, image or device path.") }
            }
            if (state.failed.isNotEmpty()) Text("Could not search: ${state.failed.joinToString()}. You can still open their pages.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(dismiss) { Text("Close") } }, dismissButton = { TextButton(vm::refresh, enabled = !state.loading) { Text("Refresh index") } })
}
