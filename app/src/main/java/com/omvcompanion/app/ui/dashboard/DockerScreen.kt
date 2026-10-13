package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class DockerContainer(val id: String, val name: String, val image: String, val state: String,
    val status: String, val ports: String = "", val mounts: String = "", val network: String = "")
data class DockerState(val containers: List<DockerContainer> = emptyList(), val loading: Boolean = true,
    val error: String? = null, val busy: Boolean = false, val task: String? = null, val output: String = "", val inspection: List<Pair<String, String>>? = null)

class DockerViewModel(private val serverId: String, private val sessions: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow(DockerState())
    val state = _state.asStateFlow()
    init { refresh() }
    private suspend fun fetch() {
        val response = sessions.client(serverId).call("Compose", "getContainerList", buildJsonObject {
            put("start", 0); put("limit", -1); put("sortfield", "name"); put("sortdir", "ASC")
        }) as? JsonObject ?: error("Unexpected container list response")
        val data = response.arr("data") ?: error("Container data missing. Check the OMV Compose plugin.")
        val containers = data.filterIsInstance<JsonObject>().mapNotNull { o ->
            val id = o.str("id") ?: o.str("ID") ?: return@mapNotNull null
            fun field(lower: String, upper: String = lower) = (o.str(lower) ?: o.str(upper)).orEmpty().replace("<br/>", "\n").replace("<br>", "\n")
            DockerContainer(id, field("name", "Names"), field("image", "Image"), field("state", "State"), field("status", "Status"), field("ports", "Ports"), field("mounts", "Mounts"), field("network", "Networks"))
        }
        _state.update { it.copy(containers = containers, loading = false) }
    }
    fun refresh() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try { fetch() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun command(container: DockerContainer, action: String) {
        if (_state.value.busy) return
        val allowed = setOf("start", "stop", "restart", "pause", "unpause", "rm", "logs", "inspect", "stats")
        require(action in allowed)
        _state.update { it.copy(busy = true, error = null, task = "$action: ${container.name}", output = "", inspection = null) }
        viewModelScope.launch {
            try {
                val client = sessions.client(serverId)
                val job = client.call("Compose", "doContainerCommand", buildJsonObject {
                    put("command", action)
                    put("command2", when (action) { "logs" -> "--tail=200"; "stats" -> "--no-stream"; else -> "" })
                    put("id", container.id)
                })
                client.awaitJob(job) { output -> _state.update { it.copy(output = (it.output + output).takeLast(60000)) } }
                _state.update { it.copy(task = "$action completed: ${container.name}", inspection = if (action == "inspect") readableInspection(it.output) else null) }
                if (action !in setOf("logs", "inspect", "stats")) fetch()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message, task = "$action failed") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerScreen(serverId: String, padding: PaddingValues, initialSearch: String = "", onSsh: () -> Unit = {}, tabs: @Composable () -> Unit = {}) {
    val vm = appViewModel(key = "docker-$serverId") { DockerViewModel(serverId, it.sessionManager) }
    val state by vm.state.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var pending by remember { mutableStateOf<Pair<DockerContainer, String>?>(null) }
    var detail by remember { mutableStateOf<DockerContainer?>(null) }
    var selected by remember { mutableStateOf<DockerContainer?>(null) }
    var editing by remember { mutableStateOf<DockerContainer?>(null) }
    LaunchedEffect(initialSearch) { search = initialSearch; filter = "All" }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.task) { if (state.task != null) listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(if (state.loading) "Loading…" else if (state.error != null) "Docker unavailable" else "${state.containers.count { it.state == "running" }} running", style = MaterialTheme.typography.headlineLarge)
                    Text("${state.containers.size} total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) { Icon(Icons.Filled.ViewInAr, null, Modifier.padding(14.dp).size(44.dp), tint = MaterialTheme.colorScheme.primary) }
                IconButton(vm::refresh, enabled = !state.busy && !state.loading) { Icon(Icons.Filled.Refresh, "Refresh containers") }
            }
            ExpressiveSearch(search, { search = it }, "Find a container")
            tabs()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Running", "Stopped").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { item { EmptyPanel("Container request failed", it); TextButton(vm::refresh, enabled = !state.busy) { Text("Retry") } } }
            state.task?.let { item { if (state.inspection != null) ControlPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Container configuration", style = MaterialTheme.typography.titleLarge); state.inspection!!.forEach { (label, value) -> Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Text(value) } } } else TaskOutput(it, if (it.startsWith("inspect")) "Reading configuration…" else state.output, state.busy) } }
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val filtered = state.containers.filter { (search.isBlank() || it.name.contains(search, true) || it.image.contains(search, true)) && (filter == "All" || (filter == "Running") == (it.state == "running")) }
            if (!state.loading && state.error == null && filtered.isEmpty()) item { EmptyPanel("No matching containers", "Try another filter or search. Container management requires the OMV Compose plugin.") }
            items(filtered, key = { it.id }) { container ->
                ControlPanel(Modifier.fillMaxWidth().clickable(onClickLabel = "Actions for ${container.name}") { selected = container }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ContainerAvatar(container.name, Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(container.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(container.image, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (container.state == "running") "● Running" else container.state.replaceFirstChar { it.uppercase() }} · ${container.status}", style = MaterialTheme.typography.labelLarge, color = if (container.state == "running") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ selected = container }, enabled = !state.busy) { Icon(Icons.Filled.MoreVert, "Actions for ${container.name}") }
                    }
                }
            }
        }
    }
    selected?.let { original ->
        val container = state.containers.find { it.id == original.id } ?: original
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContainerAvatar(container.name)
                    Column(Modifier.weight(1f)) { Text(container.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(container.image, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    StatusPill(container.state.replaceFirstChar { it.uppercase() })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ selected = null; if (container.state == "running") pending = container to "stop" else vm.command(container, if (container.state == "paused") "unpause" else "start") }, enabled = !state.busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                        Icon(if (container.state == "running") Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (container.state == "running") "Stop" else "Start")
                    }
                    FilledTonalButton({ selected = null; pending = container to "restart" }, enabled = !state.busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)) { Icon(Icons.Filled.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Restart") }
                    FilledTonalButton({ selected = null; editing = container }, enabled = !state.busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)) { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
                }
                FilledTonalButton({ selected = null; detail = container }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Description, null); Text("Details and ports", Modifier.weight(1f).padding(10.dp)); Icon(Icons.Filled.ChevronRight, null) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ selected = null; vm.command(container, "logs") }, enabled = !state.busy) { Text("View logs") }
                    TextButton({ selected = null; vm.command(container, "inspect") }, enabled = !state.busy) { Text("Configuration") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (container.state == "running") {
                        TextButton({ selected = null; pending = container to "pause" }, enabled = !state.busy) { Text("Pause") }
                        TextButton({ selected = null; vm.command(container, "stats") }, enabled = !state.busy) { Text("Resource usage") }
                    } else TextButton({ selected = null; pending = container to "rm" }, enabled = !state.busy) { Text("Remove container") }
                }
            }
        }
    }
    pending?.let { (container, action) ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("${if (action == "rm") "Remove" else action.replaceFirstChar { it.uppercase() }} ${container.name}?") },
            text = { Text(if (action == "rm") "The stopped container will be removed. Its image and volumes are retained." else "This can interrupt applications using this container.") },
            confirmButton = { TextButton({ pending = null; vm.command(container, action) }) { Text("Confirm") } }, dismissButton = { TextButton({ pending = null }) { Text("Cancel") } })
    }
    detail?.let { container ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(container.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ID: ${container.id}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Image: ${container.image}")
                Text("Network: ${container.network.ifBlank { "Not reported" }}")
                Text("Ports\n${readableContainerField(container.ports, true)}")
                Text("Mounts\n${readableContainerField(container.mounts, false)}")
            } }, confirmButton = { TextButton({ detail = null }) { Text("Close") } })
    }
    editing?.let { ContainerEditorDialog(serverId, it, { editing = null }, vm::refresh, onSsh) }
}

@Composable private fun ContainerAvatar(name: String, modifier: Modifier = Modifier) {
    val color = when { name.contains("jellyfin", true) -> Color(0xFF7466EA); name.contains("nextcloud", true) -> Color(0xFF009ADA); name.contains("redis", true) -> Color(0xFFCF5353); name.contains("portainer", true) -> Color(0xFF17A7D5); else -> MaterialTheme.colorScheme.primary }
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small, modifier = modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(name.trimStart('/').take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 26.sp, color = color) }
    }
}
