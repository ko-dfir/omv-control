package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omvcompanion.app.OmvApp
import com.omvcompanion.app.data.repo.records
import com.omvcompanion.app.data.repo.SmartData
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

@Composable fun SmartDetailsDialog(serverId: String, smart: SmartData, close: () -> Unit) {
    val container = (LocalContext.current.applicationContext as OmvApp).container
    var tab by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val methods = listOf("getInformation", "getAttributes", "getSelfTestLogs")
    LaunchedEffect(tab, smart.deviceFile) {
        loading = true; error = null; data = null
        try { data = container.managementRepository.call(serverId, "Smart", methods[tab], buildJsonObject { put("devicefile", smart.deviceFile) }) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = "This device does not provide this SMART information. ${e.message}" }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = close, title = { Text(smart.model ?: smart.deviceFile) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TabRow(tab) { listOf("Information", "Attributes", "Self-tests").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) } }
            Text(smart.deviceFile, style = MaterialTheme.typography.bodySmall)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            data?.let { value ->
                val rows = records(value)
                if (rows.isEmpty()) Text(displayValue(value))
                rows.forEach { row -> ReadableFields(row); HorizontalDivider() }
            }
        }
    }, confirmButton = { TextButton(close) { Text("Close") } })
}
