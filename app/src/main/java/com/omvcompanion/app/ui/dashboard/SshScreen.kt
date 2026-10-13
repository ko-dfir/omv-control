package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import com.omvcompanion.app.ui.components.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.omvcompanion.app.data.repo.ServerRepository
import com.omvcompanion.app.ui.appViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Properties

class SshViewModel(
    private val serverId: String,
    private val serverRepo: ServerRepository,
    private val runner: com.omvcompanion.app.data.repo.SshRunner
) : ViewModel() {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _needsLogin = MutableStateFlow(false)
    val needsLogin: StateFlow<Boolean> = _needsLogin.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private var session: Session? = null
    @Volatile private var commandChannel: ChannelExec? = null
    private var commandJob: kotlinx.coroutines.Job? = null
    val trust = MutableStateFlow<com.omvcompanion.app.security.SshTrustRequired?>(null)
    val interactive = MutableStateFlow(false)
    val terminal = MutableStateFlow("")
    private var shell: com.jcraft.jsch.ChannelShell? = null
    private var shellInput: java.io.OutputStream? = null
    private var reader: kotlinx.coroutines.Job? = null
    private val emulator = TerminalBuffer()

    fun approveTrust() {
        val challenge = trust.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = serverRepo.get(serverId) ?: error("Server not found")
                serverRepo.upsert(profile.copy(sshFingerprint = challenge.fingerprint))
                trust.value = null
                pendingLogin?.let { connect(it.first, it.second, it.third) } ?: checkAndConnect()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { appendLog(e.message ?: "Cannot save SSH trust") }
        }
    }
    fun interactiveMode(enabled: Boolean) {
        if (_busy.value) return
        shell?.disconnect(); reader?.cancel(); shellInput = null; interactive.value = enabled
        if (!enabled) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val channel = session?.openChannel("shell") as? com.jcraft.jsch.ChannelShell ?: error("Connect first")
                shell = channel; channel.setPtyType("xterm", 100, 28, 0, 0)
                val output = channel.inputStream; shellInput = channel.outputStream; channel.connect(10000)
                reader = viewModelScope.launch(Dispatchers.IO) {
                    val decoder = output.reader(Charsets.UTF_8); val chars = CharArray(2048)
                    while (isActive) { val n = decoder.read(chars); if (n < 0) break; terminal.value = emulator.feed(String(chars, 0, n)) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { appendLog(e.message.orEmpty()); interactive.value = false }
        }
    }
    fun sendTerminal(text: String) { viewModelScope.launch(Dispatchers.IO) { try { shellInput?.write(text.toByteArray()); shellInput?.flush() } catch (e: Exception) { appendLog(e.message.orEmpty()) } } }

    init {
        checkAndConnect()
    }

    private fun appendLog(line: String) {
        _logs.update { (it + line).takeLast(500) }
    }

    private fun checkAndConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val server = serverRepo.get(serverId) ?: error("Server not found")
                if (server.sshUsername == null) { _needsLogin.value = true; return@launch }
                session = runner.session(serverId)
                _isConnected.value = true; _needsLogin.value = false
                appendLog("SSH connected as ${server.sshUsername}@${session?.host}.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _needsLogin.value = true
                if (e is com.omvcompanion.app.security.SshTrustRequired) trust.value = e
                appendLog(e.message ?: "Cannot load SSH connection settings")
            }
        }
    }

    private var pendingLogin: Triple<String, String, Int>? = null

    fun loginAndConnect(user: String, pass: String, portText: String = "22") {
        val port = portText.toIntOrNull()
        if (port == null || port !in 1..65535) { appendLog("Enter an SSH port from 1 to 65535."); return }
        viewModelScope.launch(Dispatchers.IO) {
            pendingLogin = Triple(user, pass, port)
            connect(user, pass, port)
        }
    }

    private suspend fun connect(user: String, pass: String, port: Int? = null) {
        try {
            withContext(Dispatchers.Main) { appendLog("Connecting to $serverId via SSH as $user...") }
            val server = serverRepo.get(serverId) ?: throw Exception("Server not found")
            val candidate = server.copy(sshUsername = user, sshEncryptedPassword = serverRepo.encryptPassword(pass), sshPort = port ?: server.sshPort)
            
            val connected = com.omvcompanion.app.security.trustedSshSession(candidate, serverRepo)
            try { serverRepo.upsert(candidate) } catch (e: Exception) { connected.disconnect(); throw e }
            runner.adopt(serverId, candidate, connected)
            session = connected
            pendingLogin = null

            withContext(Dispatchers.Main) {
                _isConnected.value = true; _needsLogin.value = false
                appendLog("SSH Connected as $user@${connected.host}.")
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (e is com.omvcompanion.app.security.SshTrustRequired) trust.value = e
            withContext(Dispatchers.Main) {
                _needsLogin.value = true
                appendLog("Connection failed: ${e.message}")
            }
        }
    }

    fun sendCommand(cmd: String) {
        if (cmd.isBlank() || _busy.value) return
        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            _isConnected.value = false; _needsLogin.value = true
            appendLog("Not connected. Sign in again."); return
        }
        _busy.value = true
        appendLog("> $cmd")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            var channel: ChannelExec? = null
            try {
                channel = currentSession.openChannel("exec") as ChannelExec
                commandChannel = channel
                channel.setCommand(cmd); channel.setInputStream(null)
                val errors = java.io.ByteArrayOutputStream()
                channel.setErrStream(errors)
                val input = channel.inputStream
                channel.connect(10000)
                val status = streamCommandOutput(input, { channel.isClosed }, { channel.exitStatus }, { channel.disconnect() }, ::appendLog)
                if (errors.size() > 0) appendLog(errors.toString("UTF-8"))
                appendLog(if (status >= 0) "Exit code: $status" else "Command finished; the server did not report an exit code.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { appendLog("Error: ${e.message}")
            } finally {
                channel?.disconnect()
                if (commandChannel === channel) commandChannel = null
                if (commandJob === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) _busy.value = false
            }
        }
    }

    fun stopCommands() {
        commandJob?.cancel(); commandChannel?.disconnect()
        shell?.disconnect(); reader?.cancel(); shellInput = null
        interactive.value = false; _busy.value = false
    }
    override fun onCleared() { stopCommands() }
}

@Composable
fun SshScreen(serverId: String, padding: PaddingValues) {
    com.omvcompanion.app.security.SensitiveScreen()
    if (serverId == "demo") { Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) { PageHeading("SSH workspace", "Connect to your server to use the terminal and OMV commands."); EmptyPanel("Unavailable in demo", "Interactive SSH requires a real Linux account and a verified server fingerprint.") }; return }
    val vm = appViewModel(key = "ssh-$serverId") {
        SshViewModel(serverId, it.serverRepository, it.sshRunner)
    }
    androidx.compose.runtime.DisposableEffect(vm) { onDispose { vm.stopCommands() } }
    val logs by vm.logs.collectAsStateWithLifecycle()
    val challenge by vm.trust.collectAsStateWithLifecycle()
    val interactive by vm.interactive.collectAsStateWithLifecycle()
    val terminal by vm.terminal.collectAsStateWithLifecycle()
    challenge?.let { item -> AlertDialog(onDismissRequest = { vm.trust.value = null }, title = { Text(if (item.changed) "SSH identity changed" else "Trust SSH server?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${item.host}\n${item.fingerprint}", fontFamily = FontFamily.Monospace)
        Text("Compare this fingerprint with ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub on the server. Trust only after it matches.")
        if (item.changed) Text("The saved identity no longer matches. A reinstall or an intercepted connection may cause this.", color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton(vm::approveTrust) { Text(if (item.changed) "Replace verified identity" else "Trust verified identity") } }, dismissButton = { TextButton({ vm.trust.value = null }) { Text("Cancel") } }) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    var libraryOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<OmvCommand?>(null) }
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) scroll.animateScrollToItem(logs.lastIndex) }
    val needsLogin by vm.needsLogin.collectAsState()
    val isConnected by vm.isConnected.collectAsState()
    var input by remember { mutableStateOf("") }
    
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }

    if (libraryOpen) AlertDialog(onDismissRequest = { libraryOpen = false }, title = { Text("OMV command library") },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(omvCommands) { preset ->
                ControlPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(preset.title, style = MaterialTheme.typography.titleMedium)
                        Text(preset.description, style = MaterialTheme.typography.bodySmall)
                        Text(preset.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        TextButton({ input = preset.command; libraryOpen = false }) { Text("Use command") }
                    }
                }
            }
        } }, confirmButton = { TextButton({ libraryOpen = false }) { Text("Close") } })
    pending?.let { preset -> AlertDialog(onDismissRequest = { pending = null }, title = { Text(preset.title) }, text = { Text("This restarts the OMV RPC engine and may interrupt connections.") },
        confirmButton = { TextButton({ pending = null; vm.sendCommand(preset.command); input = "" }) { Text("Run") } }, dismissButton = { TextButton({ pending = null }) { Text("Cancel") } }) }
    fun runInput() {
        if (!isConnected || busy || input.isBlank()) return
        if (interactive) { vm.sendTerminal(input + "\n"); input = ""; return }
        val preset = omvCommands.find { it.command == input && it.modifiesServer }
        if (preset != null) pending = preset else { vm.sendCommand(input); input = "" }
    }
    if (needsLogin) {
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.Center) {
            PageHeading("SSH sign in", "Connect with a Linux account on your server")
            Spacer(Modifier.height(16.dp))
            Text("Use a non-root account. Service controls need sudo rights for systemctl; container editing needs membership in the docker group.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("SSH Username") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("SSH Password") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(port, { port = it }, label = { Text("SSH port") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.loginAndConnect(user, pass, port) }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
            Spacer(Modifier.height(32.dp))
            LazyColumn(Modifier.fillMaxWidth().height(150.dp)) {
                items(logs) { line -> Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
        PageHeading("SSH workspace", if (isConnected) "Connected / ${if (busy) "Command running" else "Ready"}" else "Connecting...")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton({ libraryOpen = true }) { Text("OMV command library") }
            FilterChip(interactive, { vm.interactiveMode(!interactive) }, { Text("Interactive shell") }, enabled = isConnected && !busy)
        }
        Spacer(Modifier.height(16.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ElevatedCard(Modifier.weight(1f).fillMaxWidth()) {
            if (interactive) androidx.compose.foundation.text.selection.SelectionContainer { Text(terminal, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(20.dp)) {
                items(logs) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (interactive) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ vm.sendTerminal("\u0003") }) { Text("Ctrl+C") }; TextButton({ vm.sendTerminal("\t") }) { Text("Tab") }
            TextButton({ vm.sendTerminal("\u001b[A") }) { Text("↑") }; TextButton({ vm.sendTerminal("\u001b[B") }) { Text("↓") }; TextButton({ vm.sendTerminal("q") }) { Text("Quit (q)") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command or shell input") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { 
                    runInput()
                })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { runInput() }, enabled = isConnected && !busy && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

data class OmvCommand(val title: String, val description: String, val command: String, val modifiesServer: Boolean = false)
private val omvCommands = listOf(
    OmvCommand("OMV configuration models", "List configuration sections managed by OMV", "omv-confdbadm list-ids"),
    OmvCommand("OMV network configuration", "Read the saved OMV network settings", "omv-confdbadm read conf.system.network"),
    OmvCommand("OMV system information", "Query the OMV engine using its command-line client", "omv-rpc -u admin System getInformation '{}'"),
    OmvCommand("OMV Salt stages", "List the available configuration stages", "omv-salt stage list"),
    OmvCommand("OMV version", "Installed OpenMediaVault package", "dpkg-query -W openmediavault"),
    OmvCommand("System overview", "Uptime, load and memory", "uptime; free -h"),
    OmvCommand("Disk capacity", "Mounted file systems and free space", "df -hT"),
    OmvCommand("Drive inventory", "Block devices, file systems and mount points", "lsblk -o NAME,SIZE,MODEL,FSTYPE,MOUNTPOINTS"),
    OmvCommand("Network addresses", "Interface addresses and routes", "ip -brief address; ip route"),
    OmvCommand("Failed services", "Services needing attention", "systemctl --failed --no-pager"),
    OmvCommand("OMV engine status", "RPC engine health", "systemctl status openmediavault-engined --no-pager"),
    OmvCommand("OMV engine logs", "Last 100 engine log entries", "journalctl -u openmediavault-engined -n 100 --no-pager"),
    OmvCommand("Docker containers", "Containers and published ports", "docker ps -a"),
    OmvCommand("Docker disk usage", "Image, container and volume storage", "docker system df"),
    OmvCommand("Deployment modules", "Available OMV deployment modules", "omv-salt deploy list"),
    OmvCommand("Restart OMV engine", "Restart the RPC service", "systemctl restart openmediavault-engined", true),
)
