package com.omvcompanion.app.ui.dashboard

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.omvcompanion.app.diagnostics.AppLog

@Composable fun DiagnosticsScreen() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(AppLog.read()) }
    var clear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("App diagnostics", style = MaterialTheme.typography.headlineSmall)
        Text("Connection outcomes and crashes are recorded locally. Server passwords and command output are excluded. Review the log before sharing.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ log = AppLog.read() }) { Text("Refresh") }
            OutlinedButton({
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", AppLog.export(context))
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share diagnostics"))
            }) { Text("Export log") }
            TextButton({ clear = true }) { Text("Clear log") }
        }
        androidx.compose.foundation.text.selection.SelectionContainer { Text(log.ifBlank { "No events recorded." }, Modifier.fillMaxSize().verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear app log?") }, confirmButton = { TextButton({ AppLog.clear(); log = ""; clear = false }) { Text("Clear") } }, dismissButton = { TextButton({ clear = false }) { Text("Cancel") } })
}
