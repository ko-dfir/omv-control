package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ContainerEditorState(val snapshot: ContainerEditSnapshot? = null, val loading: Boolean = true, val busy: Boolean = false, val error: String? = null, val result: ContainerEditResult? = null, val restored: Boolean = false)
class ContainerEditorViewModel(private val serverId: String, private val container: DockerContainer, private val repository: ContainerEditorRepository) : ViewModel() {
    private val _state = MutableStateFlow(ContainerEditorState())
    val state = _state.asStateFlow()
    init { reload() }
    fun reload() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try { val snapshot = repository.inspect(serverId, container.id); _state.update { it.copy(snapshot = snapshot, loading = false, result = repository.lastResult(serverId)?.takeIf { r -> r.originalName == container.name.trimStart('/') }) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(loading = false, error = e.message) } }
        }
    }
    fun apply(draft: ContainerDraft, onChanged: () -> Unit) {
        if (_state.value.busy || _state.value.result != null) return
        val snapshot = _state.value.snapshot ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { val result = repository.apply(serverId, snapshot, draft); _state.update { it.copy(result = result) }; onChanged() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = e.message) }; onChanged() }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun restore(onChanged: () -> Unit) {
        val result = _state.value.result ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { repository.rollback(serverId, result); _state.update { it.copy(result = null, restored = true) }; onChanged() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(error = e.message) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun accept() { if (!_state.value.busy) repository.accept(serverId) }
}

@Composable fun ContainerEditorDialog(serverId: String, container: DockerContainer, onDismiss: () -> Unit, onChanged: () -> Unit, onSsh: () -> Unit) {
    val vm = appViewModel(key = "container-edit-$serverId-${container.id}") { ContainerEditorViewModel(serverId, container, it.containerEditorRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember(state.snapshot) { mutableStateOf(state.snapshot?.draft) }
    var tab by remember { mutableStateOf("Environment") }
    var review by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var acknowledge by remember { mutableStateOf(false) }
    val changed = draft != null && draft != state.snapshot?.draft
    val validation = draft?.let { runCatching { validateContainerDraft(it) }.exceptionOrNull()?.message }
    Dialog(onDismissRequest = { if (!state.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy)) {
        Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(.94f).fillMaxHeight(.92f), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Edit ${container.name}", style = MaterialTheme.typography.headlineSmall); Text("${container.image} · ${container.state}", style = MaterialTheme.typography.bodySmall) }
                    IconButton(onDismiss, enabled = !state.busy) { Icon(Icons.Filled.Close, "Close container editor") }
                }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); Row { TextButton(vm::reload, enabled = !state.busy && state.result == null) { Text("Reload configuration") }; TextButton({ onDismiss(); onSsh() }, enabled = !state.busy) { Text("Configure SSH") } } }
                    state.snapshot?.unsupported?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.restored) Text("Previous container restored. Close this editor to reload its current settings.", color = MaterialTheme.colorScheme.primary)
                    state.result?.let { result ->
                        Text("A replacement container was created. The previous container is stopped and retained as ${result.backupName}; its automatic restart is disabled.", color = MaterialTheme.colorScheme.primary)
                        Text("Check the application and its logs before removing the backup. Restoring switches containers and retains the replacement too. Files in shared volumes are live data: this is not a data backup.")
                        OutlinedButton({ restore = true }, enabled = !state.busy) { Text("Restore previous container") }
                        OutlinedButton({ vm.accept(); onDismiss(); onChanged() }, enabled = !state.busy) { Text("Keep replacement and finish") }
                    }
                    if (state.result == null && !state.restored && draft != null) {
                        Text("Changes to environment variables, ports or mounts recreate the container using its current local image. It keeps its current running/stopped state. Configure verified SSH with Docker socket access before applying.", style = MaterialTheme.typography.bodySmall)
                        state.snapshot?.stack?.let { Text("Managed by Compose stack: $it. These edits affect the current container only. Update the stack definition separately so a later deployment retains your changes.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Environment", "Ports", "Mounts", "Restart").forEach { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
                        }
                        val d = draft!!
                        when (tab) {
                            "Environment" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) { Switch(reveal, { reveal = it }); Text("Show variable values") }
                                Text("Existing values are retained exactly. Variable values may contain passwords; they are hidden by default.", style = MaterialTheme.typography.bodySmall)
                                d.environment.forEachIndexed { index, entry -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(entry.key, { value -> draft = d.copy(environment = d.environment.toMutableList().apply { set(index, entry.copy(key = value)) }) }, label = { Text("Variable") }, modifier = Modifier.weight(1f), singleLine = true, enabled = !state.busy)
                                    OutlinedTextField(entry.value, { value -> draft = d.copy(environment = d.environment.toMutableList().apply { set(index, entry.copy(value = value)) }) }, label = { Text("Value") }, modifier = Modifier.weight(1f), visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), enabled = !state.busy)
                                    IconButton({ draft = d.copy(environment = d.environment.filterIndexed { i, _ -> i != index }) }, enabled = !state.busy) { Icon(Icons.Filled.DeleteOutline, "Remove variable ${entry.key}") }
                                } }
                                TextButton({ draft = d.copy(environment = d.environment + EnvironmentEntry("", "")) }, enabled = !state.busy) { Text("Add variable") }
                            }
                            "Ports" -> {
                                Text("Host port is the port you open on the NAS. Container port is the application's internal port. Blank host address uses Docker's default binding.", style = MaterialTheme.typography.bodySmall)
                                if (state.snapshot?.networkMode in listOf("host", "none")) Text("Published ports do not apply to ${state.snapshot?.networkMode} networking. Change the deployment's network mode to publish ports.", color = MaterialTheme.colorScheme.tertiary)
                                d.ports.forEachIndexed { index, entry -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(entry.hostPort, { draft = d.copy(ports = d.ports.toMutableList().apply { set(index, entry.copy(hostPort = it)) }) }, label = { Text("Host port") }, singleLine = true, modifier = Modifier.weight(1f))
                                        OutlinedTextField(entry.containerPort, { draft = d.copy(ports = d.ports.toMutableList().apply { set(index, entry.copy(containerPort = it)) }) }, label = { Text("Container port") }, singleLine = true, modifier = Modifier.weight(1f))
                                        IconButton({ draft = d.copy(ports = d.ports.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Filled.DeleteOutline, "Remove port mapping") }
                                    }
                                    OutlinedTextField(entry.hostAddress, { draft = d.copy(ports = d.ports.toMutableList().apply { set(index, entry.copy(hostAddress = it)) }) }, label = { Text("Host IP address (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("tcp", "udp", "sctp").forEach { protocol -> FilterChip(entry.protocol == protocol, { draft = d.copy(ports = d.ports.toMutableList().apply { set(index, entry.copy(protocol = protocol)) }) }, label = { Text(protocol.uppercase()) }) } }
                                    HorizontalDivider()
                                } }
                                TextButton({ draft = d.copy(ports = d.ports + PortMapping("", "")) }, enabled = !state.busy && state.snapshot?.networkMode !in listOf("host", "none")) { Text("Add port mapping") }
                            }
                            "Mounts" -> {
                                Text("Bind mounts use a path on your server. Named volumes use a Docker volume name. Existing named and anonymous volumes retain their data. Files outside mounts stay in the old container and are not copied into its replacement. Removing a mapping does not delete the source data.", style = MaterialTheme.typography.bodySmall)
                                d.mounts.forEachIndexed { index, entry -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("bind", "volume").forEach { type -> FilterChip(entry.type == type, { draft = d.copy(mounts = d.mounts.toMutableList().apply { set(index, entry.copy(type = type)) }) }, label = { Text(if (type == "bind") "Server path" else "Named volume") }) } }
                                    OutlinedTextField(entry.source, { draft = d.copy(mounts = d.mounts.toMutableList().apply { set(index, entry.copy(source = it)) }) }, label = { Text(if (entry.type == "bind") "Server path" else "Docker volume name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(entry.target, { draft = d.copy(mounts = d.mounts.toMutableList().apply { set(index, entry.copy(target = it)) }) }, label = { Text("Path inside container") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(entry.readOnly, { draft = d.copy(mounts = d.mounts.toMutableList().apply { set(index, entry.copy(readOnly = it)) }) }); Text("Read only", Modifier.weight(1f)); IconButton({ draft = d.copy(mounts = d.mounts.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Filled.DeleteOutline, "Remove mount ${entry.target}") } }
                                    HorizontalDivider()
                                } }
                                TextButton({ draft = d.copy(mounts = d.mounts + ContainerMount("", "")) }, enabled = !state.busy) { Text("Add mount") }
                            }
                            else -> {
                                listOf("no" to "Manual start", "unless-stopped" to "Restart automatically unless you stop it", "always" to "Always restart", "on-failure" to "Restart after an error").forEach { (policy, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(d.restart == policy, { draft = d.copy(restart = policy) }); Text(label) } }
                                if (d.restart == "on-failure") OutlinedTextField(d.retries.toString(), { draft = d.copy(retries = it.toIntOrNull() ?: -1) }, label = { Text("Maximum retries (0 = unlimited)") }, singleLine = true)
                            }
                        }
                        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !state.busy) { Text("Close") }
                    Button({ review = true; acknowledge = false }, enabled = serverId != "demo" && changed && validation == null && !state.busy && !state.loading && state.snapshot?.unsupported == null && state.result == null && !state.restored) { Text("Review changes") }
                }
            }
        }
    }
    if (review) AlertDialog(onDismissRequest = { review = false }, title = { Text("Recreate ${container.name}?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val original = state.snapshot!!.draft; val d = draft!!
            if (d.environment != original.environment) Text("Environment variables: ${d.environment.size} entries. Values are hidden in this review.")
            if (d.ports != original.ports) Text("Published ports:\n${d.ports.joinToString("\n") { "${it.hostAddress.ifBlank { "Default IP" }}:${it.hostPort.ifBlank { "Automatic" }} → ${it.containerPort}/${it.protocol}" }.ifBlank { "None" }}")
            if (d.mounts != original.mounts) Text("Mounts:\n${d.mounts.joinToString("\n") { "${it.source} → ${it.target}${if (it.readOnly) " (read only)" else ""}" }.ifBlank { "None" }}")
            if (d.restart != original.restart || d.retries != original.retries) Text("Restart policy: ${d.restart}")
            Text("The application may be interrupted. Its old container will be kept stopped for recovery; files in shared volumes are not rolled back. Files outside mounts remain only in the old container. Verify that important data is stored in volumes before continuing.")
            state.snapshot?.stack?.let { Text("Compose stack '$it' will keep its previous definition until you update it separately.") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledge, { acknowledge = it }); Text("I reviewed the changes and data locations.") }
        }
    }, confirmButton = { TextButton({ review = false; vm.apply(draft!!, onChanged) }, enabled = acknowledge) { Text("Recreate container") } }, dismissButton = { TextButton({ review = false }) { Text("Cancel") } })
    if (restore) AlertDialog(onDismissRequest = { restore = false }, title = { Text("Restore previous container?") }, text = { Text("This stops the replacement and restores the previous container's name, restart policy and running state. Both containers are retained; shared-volume data stays as it is now.") }, confirmButton = { TextButton({ restore = false; vm.restore(onChanged) }) { Text("Restore") } }, dismissButton = { TextButton({ restore = false }) { Text("Cancel") } })
}
