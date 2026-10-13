package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.data.rpc.*
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.formatBytes
import com.omvcompanion.app.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class ManagementState(val rows: List<JsonObject> = emptyList(), val busy: Boolean = false, val error: String? = null,
    val message: String? = null, val output: String = "", val editing: JsonObject? = null)
class ManagementViewModel(private val id: String, private val repo: ManagementRepository) : ViewModel() {
    val state = MutableStateFlow(ManagementState())
    fun operation(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch { try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "Request failed") } }
            finally { state.update { it.copy(busy = false) } } }
    }
    fun load(area: String, log: String = "syslog", page: Int = 0) = operation {
        state.update { it.copy(rows = emptyList()) }
        val rows = repo.list(id, area, log, page)
        state.update { it.copy(rows = rows) }
    }
    fun edit(area: String, row: JsonObject?) { if (row == null) state.update { it.copy(editing = JsonObject(emptyMap())) }
        else operation { val detail = repo.detail(id, area, row); state.update { it.copy(editing = detail) } } }
    fun save(area: String, params: JsonObject) = operation {
        state.update { it.copy(output = "") }
        repo.save(id, area, params) { text -> state.update { it.copy(output = (it.output + text).takeLast(30000)) } }
        val rows = repo.list(id, area)
        state.update { it.copy(rows = rows, editing = null, message = "Saved and applied.") }
    }
    fun run(area: String, row: JsonObject, action: String) = operation {
        state.update { it.copy(output = "") }
        val append: (String) -> Unit = { text -> state.update { it.copy(output = (it.output + text).takeLast(30000)) } }
        if (area == "Jobs") repo.runJob(id, row, append) else repo.stack(id, row, action, append)
        val rows = repo.list(id, area)
        state.update { it.copy(rows = rows, message = "Action completed.") }
    }
    fun images() = operation {
        val rows = repo.images(id)
        state.update { it.copy(rows = rows, message = "Image availability checked using the server’s registry cache.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ManagementScreen(serverId: String, padding: PaddingValues, initial: String = "Users", dockerOnly: Boolean = false, initialSearch: String = "", showTabs: Boolean = true) {
    val areas = if (dockerOnly) listOf("Stacks", "Images") else listOf("Users", "Groups", "Jobs", "Processes", "Logs", "Alerts", "Diagnostics")
    var area by rememberSaveable(serverId, initial) { mutableStateOf(initial) }
    val vm = appViewModel(key = "management-$serverId-$area") { ManagementViewModel(serverId, it.managementRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var search by rememberSaveable(area) { mutableStateOf("") }
    LaunchedEffect(initialSearch, initial) { area = initial; search = initialSearch }
    var log by rememberSaveable { mutableStateOf("syslog") }
    var page by rememberSaveable(log) { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<JsonObject, String>?>(null) }
    LaunchedEffect(area, log, page) { if (area !in listOf("Alerts", "Diagnostics")) vm.load(area, log, page) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        if (showTabs) ScrollableTabRow(areas.indexOf(area).coerceAtLeast(0), edgePadding = 16.dp) {
            areas.forEach { title -> Tab(area == title, { area = title }, text = { Text(if (title == "Jobs") "Scheduled jobs" else if (title == "Images") "Image updates" else title) }) }
        }
        if (area == "Alerts") { AlertHistoryScreen(serverId); return@Column }
        if (area == "Diagnostics") { DiagnosticsScreen(); return@Column }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(area, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (area in listOf("Users", "Groups", "Jobs")) OutlinedButton({ vm.edit(area, null) }, enabled = !state.busy) { Text("Create") }
            OutlinedButton({ vm.load(area, log, page) }, enabled = !state.busy) { Text("Refresh") }
        }
        if (area == "Logs") Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(log, { log = it; page = 0 }, label = { Text("Log source (e.g. syslog)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton({ page = (page - 1).coerceAtLeast(0) }, enabled = page > 0 && !state.busy) { Text("Newer") }
            TextButton({ page++ }, enabled = state.rows.size >= 100 && !state.busy) { Text("Older") }
        }
        if (area == "Images") Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Uses Compose registry checks when supported, or the ImageCheck plugin on older servers. Existing containers are not replaced automatically.", style = MaterialTheme.typography.bodySmall)
            TextButton(vm::images, enabled = !state.busy) { Text("Refresh image availability") }
        }
        OutlinedTextField(search, { search = it }, label = { Text("Search $area") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), singleLine = true)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.primary) }
        val rows = state.rows.filter { it.values.joinToString(" ") { value -> displayValue(value) }.contains(search, true) }
            .let { if (area == "Stacks") it.distinctBy { row -> row.str("uuid") ?: row.str("filepath") ?: row.str("project") ?: row.str("name") } else if (area == "Images") it.sortedByDescending { row -> imageUpdateAvailable(row) } else if (area == "Processes") it.sortedByDescending { row -> row.dbl("%CPU") ?: 0.0 } else it }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.output.isNotBlank()) item { ControlPanel(Modifier.fillMaxWidth()) { Text(state.output, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) } }
            if (!state.busy && state.error == null && rows.isEmpty()) item { EmptyPanel(if (area == "Stacks") "No OMV Compose stacks" else "No $area to show", if (area == "Stacks") "Stacks configured in OMV Compose appear here. Containers created in Portainer or manually remain available in Containers." else "Try refreshing or changing the search.") }
            items(rows) { row ->
                ControlPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (area) { "Processes" -> row.str("COMMAND") ?: "Process"; "Stacks" -> row.str("project") ?: row.str("name") ?: "Stack"; "Jobs" -> row.str("comment")?.takeIf { it.isNotBlank() } ?: "Scheduled command"; "Images" -> row.str("image") ?: row.str("name") ?: "Image"; else -> row.str("name") ?: row.str("date") ?: "Entry" }, style = MaterialTheme.typography.titleMedium)
                        when (area) {
                            "Users" -> { Text("Groups: ${displayValue(row["groups"])}"); Text(row.str("comment").orEmpty()) }
                            "Groups" -> Text("Members: ${displayValue(row["members"])}")
                            "Jobs" -> { Text(row.str("command").orEmpty()); Text("${if (row.bool("enable") == true) "Enabled" else "Disabled"} · ${row.str("execution") ?: "exactly"} · ${row.str("username") ?: "root"}") }
                            "Processes" -> { Text("PID ${row.str("PID")} · ${row.str("USER")} · CPU ${row.str("%CPU") ?: "—"}% · Memory ${row.str("%MEM") ?: "—"}%"); Text("Resident memory: ${row.lng("RES")?.let(::formatBytes) ?: displayValue(row["RES"])}") }
                            "Images" -> {
                                Text(row.str("check_result") ?: "Review availability", color = if (imageUpdateAvailable(row)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                                Text("Used by: ${displayValue(row["containers"])}")
                                row.lng("size")?.let { Text("Image size: ${formatBytes(it)}", style = MaterialTheme.typography.bodySmall) }
                                if (row.bool("persistent_data") == true) Text("Stores persistent data. Back up its volumes before upgrading.", style = MaterialTheme.typography.bodySmall)
                                if ((row["network_neighbours"] as? JsonArray)?.isNotEmpty() == true) Text("Shared-network services: ${displayValue(row["network_neighbours"])}. Check whether they depend on this image before upgrading.", style = MaterialTheme.typography.bodySmall)
                                Text(row.str("tag_policy").orEmpty(), style = MaterialTheme.typography.bodySmall)
                            }
                            "Logs" -> Text(row.str("message") ?: displayValue(row["text"]), style = MaterialTheme.typography.bodyMedium)
                            "Stacks" -> { Text("Status: ${row.str("status") ?: row.str("state") ?: "Not reported"}"); Text(row.str("filepath")?.let { "Compose file: $it" } ?: "Services: ${displayValue(row["svcname"])}", style = MaterialTheme.typography.bodySmall) }
                            else -> ReadableFields(row, skip = setOf("rownum", "name"))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (area in listOf("Users", "Groups", "Jobs")) TextButton({ vm.edit(area, row) }, enabled = !state.busy) { Text("Edit") }
                            if (area == "Jobs") TextButton({ confirm = row to "Run now" }, enabled = !state.busy) { Text("Run now") }
                            if (area == "Stacks") listOf("up -d" to "Start", "stop" to "Stop", "restart" to "Restart").forEach { (action, label) -> TextButton({ confirm = row to action }, enabled = !state.busy) { Text(label) } }
                        }
                        if (area == "Images") Text("Before updating, back up persistent data and check dependent services. Floating tags such as latest can introduce breaking changes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    state.editing?.let { record -> ManagementEditor(serverId, area, record, state.busy, state.error,
        close = { if (!state.busy) vm.state.update { it.copy(editing = null) } }, save = { vm.save(area, it) }) }
    confirm?.let { (row, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("$action?") }, text = { Text(if (area == "Jobs") "Runs this command now as ${row.str("username") ?: "root"}:\n${row.str("command")}" else "This affects all services in ${row.str("project") ?: "this Compose stack"} and may interrupt connections.") },
        confirmButton = { TextButton({ confirm = null; vm.run(area, row, action) }) { Text("Confirm") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
}

internal fun displayValue(value: JsonElement?): String = when (value) {
    null, JsonNull -> "Not reported"
    is JsonPrimitive -> value.booleanOrNull?.let { if (it) "Yes" else "No" } ?: value.content.replace(Regex("<[^>]+>"), " ").trim()
    is JsonArray -> value.joinToString(", ") { displayValue(it) }.ifBlank { "None" }
    is JsonObject -> value.entries.joinToString(" · ") { "${it.key.replace('_', ' ')}: ${displayValue(it.value)}" }
}
@Composable internal fun ReadableFields(record: JsonObject, skip: Set<String> = emptySet()) {
    record.filterKeys { it !in skip && !it.contains("password", true) && !it.contains("token", true) && !it.contains("secret", true) }.forEach { (key, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(key.replace('_', ' ').replaceFirstChar(Char::titlecase), Modifier.weight(0.35f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(displayValue(value), Modifier.weight(0.65f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ManagementEditor(serverId: String, area: String, original: JsonObject, busy: Boolean, error: String?, close: () -> Unit, save: (JsonObject) -> Unit) {
    val keys = when (area) { "Users" -> listOf("name", "password", "email", "comment", "shell", "groups"); "Groups" -> listOf("name", "comment", "members"); else -> listOf("comment", "command", "username", "execution", "minute", "hour", "dayofmonth", "month", "dayofweek") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = (context.applicationContext as com.omvcompanion.app.OmvApp).container.managementRepository
    var options by remember { mutableStateOf<List<String>>(emptyList()) }
    var optionError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(area, serverId) {
        if (area != "Jobs") try {
            val result = repo.call(serverId, "UserMgmt", if (area == "Users") "enumerateAllGroups" else "enumerateUsers")
            options = stringItems(result).ifEmpty { records(result).mapNotNull { it.str("name") } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { optionError = "Account choices unavailable. Existing memberships are retained." }
    }
    val values = remember(original, area) { mutableStateMapOf<String, String>().apply { keys.forEach { k -> this[k] = when { k == "password" -> ""; original[k] is JsonArray -> stringItems(original[k]).joinToString(","); else -> original.str(k) ?: when(k) { "shell" -> "/usr/sbin/nologin"; "username" -> "root"; "execution" -> "exactly"; "minute", "hour", "dayofmonth", "month", "dayofweek" -> "*"; "groups" -> "users"; else -> "" } } } } }
    var enabled by remember(original) { mutableStateOf(original.bool(if (area == "Users") "disallowusermod" else "enable") ?: (area == "Jobs")) }
    var email by remember(original) { mutableStateOf(original.bool("sendemail") ?: false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<JsonObject?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("${if (original.isEmpty()) "Create" else "Edit"} ${area.lowercase().removeSuffix("s")}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            keys.forEach { key ->
                when(key) {
                    "groups", "members" -> {
                        var choicesOpen by remember { mutableStateOf(false) }
                        val selected = values[key].orEmpty().split(',').map(String::trim).filter(String::isNotBlank).toSet()
                        Text(if (key == "groups") "Group memberships" else "Group members", style = MaterialTheme.typography.labelLarge)
                        Text(selected.joinToString(", ").ifBlank { "None selected" }, style = MaterialTheme.typography.bodySmall)
                        Box { OutlinedButton({ choicesOpen = true }, enabled = !busy) { Text(if (key == "groups") "Choose groups" else "Choose members") }
                            DropdownMenu(choicesOpen, { choicesOpen = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                                (options + selected).distinct().sorted().forEach { name -> DropdownMenuItem(text = { Text(name) }, leadingIcon = { Checkbox(name in selected, null) }, onClick = {
                                    values[key] = (if (name in selected) selected - name else selected + name).joinToString(",")
                                }) }
                                DropdownMenuItem(text = { Text("Done") }, onClick = { choicesOpen = false })
                            }
                        }
                        optionError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    "execution" -> {
                        var scheduleOpen by remember { mutableStateOf(false) }
                        Box { OutlinedButton({ scheduleOpen = true }, enabled = !busy) { Text("Schedule: ${values[key]}") }
                            DropdownMenu(scheduleOpen, { scheduleOpen = false }) { listOf("exactly", "hourly", "daily", "weekly", "monthly", "yearly", "reboot").forEach { option -> DropdownMenuItem(text = { Text(if (option == "exactly") "Custom schedule" else option.replaceFirstChar(Char::titlecase)) }, onClick = { values[key] = option; scheduleOpen = false }) } }
                        }
                    }
                    else -> OutlinedTextField(values[key].orEmpty(), { values[key] = it }, label = { Text(when(key) { "password" -> if (original.isEmpty()) "Password" else "Password (blank keeps current)"; "dayofmonth" -> "Day of month (1–31)"; "dayofweek" -> "Day of week (1=Monday, 7=Sunday)"; else -> key.replaceFirstChar(Char::titlecase) }) }, enabled = !busy && (key != "name" || original.isEmpty()), modifier = Modifier.fillMaxWidth(), visualTransformation = if (key == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                }
            }
            if (area == "Jobs") listOf("everynminute" to "Repeat every selected number of minutes", "everynhour" to "Repeat every selected number of hours", "everyndayofmonth" to "Repeat every selected number of days").forEach { (key, label) ->
                if (key !in values) values[key] = (original.bool(key) ?: false).toString()
                Row { Checkbox(values[key] == "true", { values[key] = it.toString() }, enabled = !busy); Text(label, style = MaterialTheme.typography.bodySmall) }
            }

            if (area != "Groups") Row { Checkbox(enabled, { enabled = it }, enabled = !busy); Text(if (area == "Users") "Disallow user changing their settings" else "Enabled") }
            if (area == "Jobs") { Row { Checkbox(email, { email = it }, enabled = !busy); Text("Email command output") }; Text("Use * or comma-separated values for schedule fields. Interval options use the selected number as the repeat interval.", style = MaterialTheme.typography.bodySmall) }
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton({
        try {
            val params = if (area == "Jobs") cronParams(original, values, enabled, email) else buildJsonObject {
                require(values["name"].orEmpty().matches(Regex("[a-z_][a-z0-9_-]*[$]?"))) { "Enter a valid Linux account name." }
                put("name", values["name"].orEmpty()); put("comment", values["comment"].orEmpty())
                if (area == "Users") {
                    put("password", values["password"].orEmpty()); put("email", values["email"].orEmpty()); put("shell", values["shell"].orEmpty())
                    put("groups", JsonArray(values["groups"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive)))
                    put("disallowusermod", enabled); put("sshpubkeys", original["sshpubkeys"] ?: JsonArray(emptyList()))
                    original["uid"]?.let { put("uid", it) }
                    require(original.isNotEmpty() || values["password"].orEmpty().isNotBlank()) { "Enter a password for the new user." }
                } else { put("members", JsonArray(values["members"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive))); original["gid"]?.let { put("gid", it) } }
            }
            localError = null; review = params
        } catch (e: Exception) { localError = e.message }
    }, enabled = !busy) { Text("Review and save") } }, dismissButton = { TextButton(close, enabled = !busy) { Text("Cancel") } })
    review?.let { params -> AlertDialog(onDismissRequest = { review = null }, title = { Text("Apply these changes?") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { ReadableFields(params); Text("Settings will be saved and the relevant OMV configuration applied.", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton({ review = null; save(params) }) { Text("Save and apply") } }, dismissButton = { TextButton({ review = null }) { Text("Back") } }) }
}
