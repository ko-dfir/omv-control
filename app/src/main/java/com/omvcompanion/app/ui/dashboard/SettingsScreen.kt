package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.data.repo.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class NetworkState(val hostname: String = "", val domain: String = "", val interfaces: List<JsonObject> = emptyList(),
    val loading: Boolean = true, val busy: Boolean = false, val dirty: Boolean = false,
    val message: String? = null, val error: String? = null, val editor: JsonObject? = null, val output: String = "", val runtimeError: String? = null, val dnsByInterface: Map<String, String> = emptyMap(), val dnsBusy: Boolean = false, val dnsNote: String? = null)
class SettingsViewModel(private val serverId: String, private val sessions: SessionManager, private val ssh: SshRunner? = null, private val servers: ServerRepository? = null) : ViewModel() {
    private val _state = MutableStateFlow(NetworkState())
    val state = _state.asStateFlow()
    private var dnsJob: Job? = null
    private val modules = listOf("hostname", "hosts", "systemd-networkd")
    private fun moduleParams() = buildJsonObject { put("modules", JsonArray(modules.map(::JsonPrimitive))); put("force", false) }
    init { refresh() }
    private suspend fun fetch() {
        val client = sessions.client(serverId)
        val general = client.call("Network", "getGeneralSettings") as? JsonObject ?: error("Invalid network response")
        val interfaces = client.call("Network", "getInterfaceList", buildJsonObject {
            put("start", 0); put("limit", -1); put("sortfield", "devicename"); put("sortdir", "ASC")
        }) as? JsonObject ?: error("Invalid interface list")
        var runtimeError: String? = null
        val live = try { (client.call("Network", "enumerateDevices") as? JsonArray)?.filterIsInstance<JsonObject>() ?: error("Invalid live interface response") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { runtimeError = "Live network readings unavailable. Saved configuration is shown. ${e.message}"; emptyList() }
        val dirty = (client.call("Config", "isDirty", buildJsonObject { put("modules", JsonArray(modules.map(::JsonPrimitive))) }) as? JsonPrimitive)?.booleanOrNull == true
        _state.update { it.copy(hostname = general.str("hostname").orEmpty(), domain = general.str("domainname").orEmpty(), interfaces = mergeLiveInterfaces(interfaces.arr("data").orEmpty().filterIsInstance<JsonObject>(), live), dirty = dirty, loading = false, runtimeError = runtimeError) }
    }
    fun refresh() = operation { _state.update { it.copy(message = null) }; fetch(); refreshDns() }
    fun refreshDns() {
        if (dnsJob?.isActive == true) return
        dnsJob = viewModelScope.launch {
            if (serverId == "demo") { _state.update { it.copy(dnsByInterface = mapOf("eth0" to "192.168.1.1")) }; return@launch }
            val profile = try { servers?.get(serverId) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { _state.update { it.copy(dnsNote = e.message ?: "Cannot read saved server settings", dnsBusy = false) }; return@launch }
            if (ssh == null || profile?.sshFingerprint == null || profile.sshUsername == null) {
                _state.update { it.copy(dnsNote = "DHCP-provided DNS requires a configured, verified SSH connection. Saved DNS is shown when available.") }; return@launch
            }
            _state.update { it.copy(dnsBusy = true, dnsNote = null) }
            try {
                withTimeout(15000) {
                    val result = ssh.execute(serverId, "python3 -", stdin = LIVE_DNS_SCRIPT, timeoutMs = 8000)
                    require(result.exitCode == 0) { "Unable to read active DNS settings" }
                    val data = Json.parseToJsonElement(result.output).jsonObject
                    val entries = (data["interfaces"] as? JsonObject ?: error("DNS reader returned no interface map")).mapValues { (_, ips) -> ips.jsonArray.joinToString(", ") { it.jsonPrimitive.content } }.toMutableMap()
                    entries["*"] = (data["global"] as? kotlinx.serialization.json.JsonArray ?: error("DNS reader returned no global resolver list")).joinToString(", ") { it.jsonPrimitive.content }
                    _state.update { it.copy(dnsByInterface = entries) }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { _state.update { it.copy(dnsNote = "Active DNS lookup timed out. Refresh to retry.") } }
              catch (e: CancellationException) { throw e }
              catch (e: Exception) { _state.update { it.copy(dnsNote = "Active DNS unavailable: ${e.message ?: "Unexpected DNS reader response"}") } }
            finally { _state.update { it.copy(dnsBusy = false) } }
        }
    }
    fun saveIdentity(hostname: String, domain: String) = operation {
        require(hostname.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))) { "Enter a valid hostname without spaces or dots" }
        sessions.client(serverId).call("Network", "setGeneralSettings", buildJsonObject { put("hostname", hostname.trim()); put("domainname", domain.trim()) })
        _state.update { it.copy(message = "Network identity saved. Apply changes to activate it.") }; fetch()
    }
    fun editInterface(uuid: String) = operation {
        val editor = sessions.client(serverId).call("Network", "getEthernetIface", buildJsonObject { put("uuid", uuid) }) as? JsonObject ?: error("Invalid interface configuration")
        _state.update { it.copy(editor = editor) }
    }
    fun closeEditor() { _state.update { it.copy(editor = null) } }
    fun saveInterface(changes: Map<String, String>) = operation {
        val original = _state.value.editor ?: error("Select an interface first")
        sessions.client(serverId).call("Network", "setEthernetIface", ethernetUpdate(original, changes))
        _state.update { it.copy(editor = null, message = "Interface saved. Apply changes to activate it.") }; fetch()
    }
    fun apply() = operation {
        _state.update { it.copy(message = "Applying network changes...", output = "") }
        val client = sessions.client(serverId)
        client.awaitJob(client.call("Config", "applyChangesBg", moduleParams())) { text -> _state.update { it.copy(output = (it.output + text).takeLast(16000)) } }
        fetch()
        _state.update { it.copy(message = if (it.dirty) "OMV still reports pending network changes. Check the deployment output below and retry after resolving the server error." else "Network changes applied and verified.") }
    }
    private fun operation(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Network request failed", loading = false, message = null) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

@Composable
fun SettingsScreen(serverId: String, padding: PaddingValues) {
    val vm = appViewModel(key = "settings-$serverId") { SettingsViewModel(serverId, it.sessionManager, it.sshRunner, it.serverRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleStartEffect(vm) {
        vm.refresh()
        onStopOrDispose { }
    }
    var hostname by remember(state.hostname) { mutableStateOf(state.hostname) }
    var domain by remember(state.domain) { mutableStateOf(state.domain) }
    var applyConfirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row {
                Box(Modifier.weight(1f)) { PageHeading("Network", "Server identity and network interfaces") }
                TextButton(vm::refresh, enabled = !state.busy) { Text("Refresh") }
            }
        }
        if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { item { EmptyPanel("Network request failed", "$it. If the address changed, use Change server to update your connection."); TextButton(vm::refresh, enabled = !state.busy) { Text("Retry") } } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (state.output.isNotBlank()) item { TaskOutput("Network deployment output", state.output, state.busy) }
        if (state.dirty) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Network changes pending", style = MaterialTheme.typography.titleMedium)
                    Text("Saved settings take effect when applied.")
                    Button({ applyConfirm = true }, enabled = !state.busy) { Text("Apply network changes") }
                }
            }
        }
        item { Text("Interfaces", style = MaterialTheme.typography.titleLarge) }
        state.runtimeError?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        state.dnsNote?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (state.dnsBusy) item { Text("Reading active DNS…", style = MaterialTheme.typography.bodySmall) }
        items(state.interfaces) { iface ->
            ControlPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(iface.str("devicename") ?: "Interface", style = MaterialTheme.typography.titleMedium)
                    Text("${iface.str("type").orEmpty()} · ${when (iface.str("method")) { "dhcp" -> "IPv4 via DHCP"; "static" -> "Static IPv4"; else -> "Manual configuration" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (iface.bool("_runtime_available") == true) "Live interface · ${iface.str("state") ?: "State not reported"}" else "Saved configuration · live data not available", style = MaterialTheme.typography.labelMedium)
                    Text("IPv4 address: ${iface.str("address").orEmpty().ifBlank { "Not reported" }}")
                    Text("Gateway: ${iface.str("gateway").orEmpty().ifBlank { "Not reported" }}")
                    iface.str("netmask")?.takeIf { it.isNotBlank() }?.let { Text("Subnet mask: $it") }
                    iface.str("address6")?.takeIf { it.isNotBlank() }?.let { Text("IPv6: $it") }
                    val name = iface.str("devicename").orEmpty()
                    val activeDns = state.dnsByInterface[name]?.takeIf { it.isNotBlank() }
                    val systemDns = state.dnsByInterface["*"]?.takeIf { it.isNotBlank() }
                    Text(when { activeDns != null -> "Active DNS: $activeDns"; systemDns != null -> "System DNS: $systemDns"; else -> "Saved DNS: ${iface.str("dnsnameservers").orEmpty().ifBlank { "Automatic / not configured" }}" })
                    iface.str("dnssearch")?.takeIf { it.isNotBlank() }?.let { Text("DNS search domains: $it") }
                    iface.str("ether")?.takeIf { it.isNotBlank() }?.let { Text("MAC address: $it", style = MaterialTheme.typography.bodySmall) }
                    iface.lng("speed")?.takeIf { it > 0 }?.let { Text("Link speed: $it Mb/s", style = MaterialTheme.typography.bodySmall) }
                    if (iface.str("type") == "ethernet") TextButton({ iface.str("uuid")?.let(vm::editInterface) }, enabled = !state.busy) { Text("Edit IPv4 and DNS") }
                }
            }
        }
        if (!state.loading) item {
            ControlPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Server identity", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(hostname, { hostname = it }, label = { Text("Hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    OutlinedTextField(domain, { domain = it }, label = { Text("Domain") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                    Button({ vm.saveIdentity(hostname, domain) }, enabled = !state.busy && (hostname != state.hostname || domain != state.domain)) { Text("Save identity") }
                }
            }
        }

    }
    if (applyConfirm) AlertDialog(onDismissRequest = { applyConfirm = false }, title = { Text("Apply network changes?") },
        text = { Text("Changing the server address, DNS or gateway can disconnect this app. Check the new settings before continuing. Only network modules will be applied.") },
        confirmButton = { TextButton({ applyConfirm = false; vm.apply() }) { Text("Apply") } }, dismissButton = { TextButton({ applyConfirm = false }) { Text("Cancel") } })
    state.editor?.let { config ->
        var method by remember(config) { mutableStateOf(config.str("method") ?: "dhcp") }
        var address by remember(config) { mutableStateOf(config.str("address").orEmpty()) }
        var mask by remember(config) { mutableStateOf(config.str("netmask").orEmpty()) }
        var gateway by remember(config) { mutableStateOf(config.str("gateway").orEmpty()) }
        var dns by remember(config) { mutableStateOf(config.str("dnsnameservers").orEmpty()) }
        var search by remember(config) { mutableStateOf(config.str("dnssearch").orEmpty()) }
        AlertDialog(onDismissRequest = { if (!state.busy) vm.closeEditor() }, title = { Text("Edit ${config.str("devicename")}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("dhcp", "static", "manual").forEach { value -> FilterChip(method == value, { method = value }, label = { Text(value.uppercase()) }, enabled = !state.busy) } }
                if (method == "static") {
                    OutlinedTextField(address, { address = it }, label = { Text("IPv4 address") }, singleLine = true)
                    OutlinedTextField(mask, { mask = it }, label = { Text("Subnet mask") }, singleLine = true)
                    OutlinedTextField(gateway, { gateway = it }, label = { Text("Gateway") }, singleLine = true)
                }
                OutlinedTextField(dns, { dns = it }, label = { Text("DNS servers (comma separated)") }, singleLine = true)
                OutlinedTextField(search, { search = it }, label = { Text("DNS search domains") }, singleLine = true)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton({ vm.saveInterface(mapOf("method" to method, "address" to if (method == "static") address else "", "netmask" to if (method == "static") mask else "", "gateway" to if (method == "static") gateway else "", "dnsnameservers" to dns, "dnssearch" to search)) }, enabled = !state.busy) { Text("Save") } }, dismissButton = { TextButton(vm::closeEditor, enabled = !state.busy) { Text("Cancel") } })
    }
}
