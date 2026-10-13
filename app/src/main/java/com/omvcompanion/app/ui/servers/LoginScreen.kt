package com.omvcompanion.app.ui.servers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omvcompanion.app.ui.components.ControlPanel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.OmvApp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

@Composable
fun LoginScreen(onConnected: (String) -> Unit) {
    com.omvcompanion.app.security.SensitiveScreen()
    val activity = androidx.activity.compose.LocalActivity.current as androidx.fragment.app.FragmentActivity
    var biometricBusy by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var preferencesOpen by remember { mutableStateOf(false) }
    if (preferencesOpen) com.omvcompanion.app.ui.AppPreferencesDialog { preferencesOpen = false }
    val container = (LocalContext.current.applicationContext as OmvApp).container
    var settingsError by remember { mutableStateOf<String?>(null) }
    val serverFlow = remember(container) { container.serverRepository.servers.catch { settingsError = it.message ?: "Cannot read saved servers" } }
    val servers by serverFlow.collectAsStateWithLifecycle(initialValue = null)
    settingsError?.let { message ->
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(message, color = MaterialTheme.colorScheme.error) }
        return
    }
    val selection by remember(container) { container.serverRepository.selectedServerId.map { it to true } }.collectAsStateWithLifecycle(initialValue = null to false)
    val lastId = selection.first
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var chooseOpen by remember { mutableStateOf(false) }
    val pendingDestination by com.omvcompanion.app.ui.PendingNavigation.destination.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDestination) { pendingDestination?.serverId?.let { chosenId = it; adding = false } }
    val list = servers
    if (list == null || !selection.second) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val selected = if (adding) null else (list.find { it.id == chosenId }
        ?: list.find { it.id == lastId } ?: list.firstOrNull())
    val vm = appViewModel(key = "login-${selected?.id ?: "new"}") {
        ServerEditViewModel(selected?.id, it.serverRepository, it.sessionManager, it.systemRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val unlockSignIn = com.omvcompanion.app.security.LocalUnlockSignIn.current
    var seamlessSignIn by remember(selected?.id) { mutableStateOf(unlockSignIn.pending && selected != null) }
    LaunchedEffect(state.error, state.totpRequired, state.untrustedCert) {
        if (state.error != null || state.totpRequired || state.untrustedCert != null) seamlessSignIn = false
    }
    LaunchedEffect(unlockSignIn.pending, selected?.id, state.credentialsLoaded, state.testing) {
        if (com.omvcompanion.app.security.shouldAutoSignIn(unlockSignIn.pending, selected != null, state.credentialsLoaded, state.testing)) {
            seamlessSignIn = true
            unlockSignIn.consume()
            vm.signIn()
        } else if (unlockSignIn.pending && selected == null) unlockSignIn.consume()
    }
    var visible by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(state.connectedId) { state.connectedId?.let { id -> vm.consumeConnection(); onConnected(id) } }
    @Composable
    fun Brand() {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = androidx.compose.ui.graphics.Color(0xFF161F26), shape = RoundedCornerShape(16.dp)) { androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.omvcompanion.app.R.drawable.ic_brand), "OMV Control", Modifier.padding(8.dp).size(64.dp)) }
            Text("Your server.\nUnder control.", style = MaterialTheme.typography.headlineLarge)
            Text("OMV Control", style = MaterialTheme.typography.titleLarge)
            Text("Monitor storage, manage containers and keep your server current from one workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable
    fun Form() {
                ControlPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(selected?.name ?: "New connection", style = MaterialTheme.typography.titleMedium)
                                Text("Server connection", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box {
                                TextButton(onClick = { chooseOpen = true }, enabled = !state.testing) { Text("Change") }
                                DropdownMenu(chooseOpen, { chooseOpen = false }) {
                                    list.forEach { server ->
                                        DropdownMenuItem(text = { Text(server.name) }, onClick = {
                                            chosenId = server.id; adding = false; chooseOpen = false
                                        })
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Add another server") }, onClick = {
                                        adding = true; chosenId = null; chooseOpen = false
                                    })
                                }
                            }
                        }
                        OutlinedTextField(state.url, vm::onUrl, label = { Text("Server address") }, placeholder = { Text("https://nas.local") }, singleLine = true, enabled = !state.testing, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        OutlinedTextField(state.username, vm::onUsername, label = { Text("Username") }, singleLine = true, enabled = !state.testing, modifier = Modifier.fillMaxWidth())
                        if (com.omvcompanion.app.BuildConfig.DEBUG) Text("Development build — do not sign in with real server credentials", color = MaterialTheme.colorScheme.error)
                HttpConsent(state.url, state.altUrl, state.allowInsecureHttp, vm::onAllowHttp, state.error)
                OutlinedTextField(state.password, vm::onPassword, label = { Text("Password") },
                    placeholder = { if (state.hasSavedPassword) Text("Saved password (leave empty to keep)") }, singleLine = true, enabled = !state.testing,
                            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Hide password" else "Show password") } })
                        TextButton(onClick = { advanced = !advanced }, enabled = !state.testing) { Text(if (advanced) "Hide connection options" else "Connection options") }
                        if (advanced) {
                            OutlinedTextField(state.name, vm::onName, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(state.altUrl, vm::onAltUrl, label = { Text("Fallback address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        biometricError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = vm::signIn, enabled = !state.testing && state.url.isNotBlank() && state.username.isNotBlank(), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            if (state.testing) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)) }
                            Text(if (state.testing) "Connecting..." else "Sign in")
                        }
                        if (selected != null) OutlinedButton(onClick = {
                            seamlessSignIn = true; biometricBusy = true; biometricError = null
                            com.omvcompanion.app.security.authenticate(activity, {
                                biometricBusy = false; vm.signIn()
                            }, { seamlessSignIn = false; biometricBusy = false; biometricError = it })
                        }, enabled = !state.testing && !biometricBusy && state.url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Biometric sign-in")
                        }

                    }
                }
        TextButton({ onConnected("demo") }) { Text("Explore demo") }
        TextButton({ preferencesOpen = true }) { Text("App preferences") }
        Text("Credentials are encrypted on this device.", modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (seamlessSignIn && state.error == null && !state.totpRequired && state.untrustedCert == null) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.omvcompanion.app.R.drawable.ic_brand), null, Modifier.size(56.dp))
                CircularProgressIndicator()
                Text("Connecting to ${selected?.name ?: "your server"}…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else Surface(color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.width(340.dp)) { Brand() }
                    Spacer(Modifier.width(64.dp))
                    Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Form() }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Brand()
                    Form()
                }
            }
        }
    }
    state.untrustedCert?.let { CertificateDialog(it, state.untrustedHostnameMismatch, vm::trustCertificate, vm::dismissCertificate, previousSha256 = state.previousSha256) }
    if (state.totpRequired) TotpDialog(state, vm)
}

@Composable
fun TotpDialog(state: ServerEditState, vm: ServerEditViewModel) {
    AlertDialog(onDismissRequest = { if (!state.testing) vm.cancelTotp() }, title = { Text("Authenticator verification") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter the current six-digit code from your authenticator app for ${state.username}.")
            OutlinedTextField(state.totpCode, vm::onTotpCode, label = { Text("Authenticator code") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !state.testing, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.testing) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton(vm::verifyTotp, enabled = !state.testing && state.totpCode.length == 6) { Text("Verify & sign in") } },
        dismissButton = { TextButton(vm::cancelTotp, enabled = !state.testing) { Text("Cancel") } })
}
