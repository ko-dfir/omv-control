package com.omvcompanion.app.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.rpc.CertificateInfo
import com.omvcompanion.app.ui.appViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(serverId: String?, onDone: () -> Unit) {
    com.omvcompanion.app.security.SensitiveScreen()
    val vm = appViewModel(key = "edit-$serverId") {
        ServerEditViewModel(serverId, it.serverRepository, it.sessionManager, it.systemRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = vm::save, enabled = !state.testing) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.url,
                onValueChange = vm::onUrl,
                label = { Text("Server address") },
                placeholder = { Text("192.168.10.221") },
                supportingText = { Text("IP, host name or URL. https is used if no scheme is given.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.altUrl,
                onValueChange = vm::onAltUrl,
                label = { Text("Fallback address (optional)") },
                placeholder = { Text("https://nas.example.com or Tailscale IP") },
                supportingText = { Text("Used automatically when the primary address is unreachable.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::onUsername,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (com.omvcompanion.app.BuildConfig.DEBUG) {
                Text("Development build — do not sign in with real server credentials", color = MaterialTheme.colorScheme.error)
            }
            HttpConsent(state.url, state.altUrl, state.allowInsecureHttp, vm::onAllowHttp, state.error)
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPassword,
                label = { Text("Password") },
                    placeholder = { if (state.hasSavedPassword) Text("Saved password (leave empty to keep)") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = vm::onName,
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            state.pinnedCertSha256?.let { pin ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Trusted certificate", style = MaterialTheme.typography.titleSmall)
                            Text(pin.take(23) + "…", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        TextButton(onClick = vm::forgetCertificate) { Text("Forget") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.testConnection() }, enabled = !state.testing) { Text("Test connection") }
                if (state.testing) {
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            state.testSuccess?.let {
                StatusCard(it, success = true)
            }
            state.error?.let {
                StatusCard(it, success = false)
            }

            Spacer(Modifier.padding(4.dp))
            Button(onClick = vm::save, enabled = !state.testing, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }

    state.untrustedCert?.let { cert ->
        CertificateDialog(cert, state.untrustedHostnameMismatch, onTrust = vm::trustCertificate, onDismiss = vm::dismissCertificate, previousSha256 = state.previousSha256)
    }
    if (state.totpRequired) TotpDialog(state, vm)
}

@Composable
private fun StatusCard(text: String, success: Boolean) {
    val container = if (success) Color(0xFF2E7D32).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CertificateDialog(cert: CertificateInfo, hostnameMismatch: Boolean, onTrust: () -> Unit, onDismiss: () -> Unit, previousSha256: String? = null) {
    val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(if (previousSha256 != null) "Server certificate changed" else "Untrusted certificate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previousSha256 != null) {
                    Text("A reinstall or certificate renewal can cause this — so can an intercepted connection. Verify the new fingerprint in the OMV web UI (System > Certificates) before replacing it.", color = MaterialTheme.colorScheme.error)
                    LabeledValue("Previously trusted SHA-256", previousSha256, monospace = true)
                }
                Text(
                    if (hostnameMismatch) "The certificate is valid but issued for a different host name."
                    else "The server uses a certificate that is not trusted by Android (OMV uses a self-signed certificate by default).",
                )
                Text("Only trust it if you are sure this is your server.", style = MaterialTheme.typography.bodySmall)
                LabeledValue("Subject", cert.subject)
                LabeledValue("Issuer", cert.issuer)
                LabeledValue("Valid", "${df.format(Date(cert.notBefore))} – ${df.format(Date(cert.notAfter))}")
                LabeledValue("SHA-256", cert.sha256, monospace = true)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(if (previousSha256 != null) "Replace trusted certificate" else "Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabeledValue(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}
