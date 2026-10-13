package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.omvcompanion.app.ui.components.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.FileSystem
import com.omvcompanion.app.data.repo.Disk
import com.omvcompanion.app.data.repo.SmartData
import com.omvcompanion.app.data.repo.StorageInfo
import com.omvcompanion.app.data.repo.SharedFolder
import androidx.compose.material.icons.filled.FolderShared
import com.omvcompanion.app.ui.UiState
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.formatBytes
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun StorageScreen(serverId: String, paddingValues: PaddingValues, initialSearch: String = "", initialTab: String = "Drives", onBrowse: (String) -> Unit = {}) {
    val vm = appViewModel<StorageViewModel>(key = "storage-$serverId") {
        StorageViewModel(serverId, it.storageRepository)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        when (val st = state) {
            is UiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is UiState.Error -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(st.message)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = vm::retry) { Text("Retry") }
                }
            }
            is UiState.Success -> {
                StorageContent(serverId, st.data, vm::loadSection, initialSearch, initialTab, onBrowse)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageContent(serverId: String, info: StorageInfo, load: (String, Boolean) -> Unit, initialSearch: String, initialTab: String, onBrowse: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Drives", "SMART", "Filesystem", "Shared folders")
    var search by rememberSaveable(serverId) { mutableStateOf("") }
    LaunchedEffect(initialSearch, initialTab) { search = initialSearch; tab = tabs.indexOf(initialTab).coerceAtLeast(0) }
    val columns = if (LocalConfiguration.current.screenWidthDp >= 840) 2 else 1
    LaunchedEffect(tab) { load(tabs[tab], false); if (tab == 0) { load("SMART", false); load("Filesystem", false) } }
    Column(Modifier.fillMaxSize()) {
        ExpressiveTabs(listOf("Drives", "SMART", "Filesystems", "Shares"), tab, { tab = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            ExpressiveSearch(search, { search = it }, "Find ${tabs[tab].lowercase()}", Modifier.weight(1f))
            IconButton({ load(tabs[tab], true) }, enabled = tabs[tab] !in info.loadingSections) { Icon(androidx.compose.material.icons.Icons.Filled.Refresh, "Refresh storage") }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0 && "Drives" !in info.loadingSections && info.errors["Drives"] == null) item {
                TonalHero(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${info.disks.size} drives", style = MaterialTheme.typography.headlineLarge)
                            Text("Connected to your NAS", style = MaterialTheme.typography.bodyMedium)
                        }
                        StorageGlyph()
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text(formatBytes(info.disks.sumOf { it.capacityBytes }), style = MaterialTheme.typography.titleMedium); Text("Drive capacity", style = MaterialTheme.typography.labelSmall) }
                        val readable = "Filesystem" !in info.loadingSections && info.errors["Filesystem"] == null
                        Column(Modifier.weight(1f)) { Text(if (readable) formatBytes(info.fileSystems.sumOf { it.usedBytes }) else "—", style = MaterialTheme.typography.titleMedium); Text("Volume used", style = MaterialTheme.typography.labelSmall) }
                        Column(Modifier.weight(1f)) { Text(if (readable) formatBytes(info.fileSystems.sumOf { it.availableBytes }) else "—", style = MaterialTheme.typography.titleMedium); Text("Volume free", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            val warnings = com.omvcompanion.app.notifications.smartAlerts(info.smarts).size
            if (tab == 0 && warnings > 0) item {
                Surface(color = Color(0xFFFDF2B3), contentColor = Color(0xFF4D2E25), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().clickable { tab = 1 }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Warning, null)
                        Column(Modifier.weight(1f)) { Text("$warnings ${if (warnings == 1) "drive needs" else "drives need"} review", style = MaterialTheme.typography.titleMedium); Text("Check SMART data for more details.", style = MaterialTheme.typography.bodySmall) }
                        Text("View SMART", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val error = info.errors[tabs[tab]]
            if (tabs[tab] in info.loadingSections) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item {
                EmptyPanel("Unable to load ${tabs[tab]}", error)
                TextButton(onClick = { load(tabs[tab], true) }) { Text("Retry") }
            }
            if (error == null && tabs[tab] !in info.loadingSections && search.isNotBlank()) {
                val matches = when (tab) {
                    0 -> info.disks.any { listOf(it.model, it.deviceFile, it.serialNumber).joinToString(" ").contains(search, true) }
                    1 -> info.smarts.any { listOf(it.model, it.deviceFile).joinToString(" ").contains(search, true) }
                    2 -> info.fileSystems.any { listOf(it.deviceFile, it.description, it.dir).joinToString(" ").contains(search, true) }
                    else -> info.sharedFolders.any { listOf(it.name, it.description, it.absolutePath).joinToString(" ").contains(search, true) }
                }
                if (!matches) item { EmptyPanel("No matching ${tabs[tab].lowercase()}", "Try another name or clear the search.") }
            }
            if (error == null && tabs[tab] !in info.loadingSections) when (tab) {
                0 -> {
                    if (info.disks.isEmpty()) item { EmptyPanel("No drives", "No physical disks were returned by the server.") }
                    items(info.disks.filter { listOf(it.model, it.deviceFile, it.serialNumber).joinToString(" ").contains(search, true) }.chunked(columns)) { disks ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            disks.forEach { disk -> Box(Modifier.weight(1f)) { DiskCard(disk, matchingSmart(disk, info.smarts), "SMART" in info.loadingSections, info.errors["SMART"]) } }
                            if (disks.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                1 -> {
                    if (info.smarts.isEmpty()) item { EmptyPanel("No SMART readings", "Enable SMART monitoring in OpenMediaVault for supported drives.") }
                    items(info.smarts.filter { listOf(it.model, it.deviceFile).joinToString(" ").contains(search, true) }.chunked(columns)) { smarts ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            smarts.forEach { smart -> Box(Modifier.weight(1f)) { SmartCard(serverId, smart) } }
                            if (smarts.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                2 -> {
                    if (info.fileSystems.isEmpty()) item { EmptyPanel("No file systems", "No file systems were returned by the server.") }
                    items(info.fileSystems.filter { listOf(it.deviceFile, it.description, it.dir).joinToString(" ").contains(search, true) }.chunked(columns)) { systems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            systems.forEach { fs -> Box(Modifier.weight(1f)) { FileSystemCard(fs) } }
                            if (systems.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                3 -> {
                    if (info.sharedFolders.isEmpty()) item { EmptyPanel("No shared folders", "Shared folders configured in OpenMediaVault will appear here.") }
                    items(info.sharedFolders.filter { listOf(it.name, it.description, it.absolutePath).joinToString(" ").contains(search, true) }.chunked(columns)) { folders ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            folders.forEach { folder -> Box(Modifier.weight(1f)) { SharedFolderCard(folder) { onBrowse(folder.uuid) } } }
                            if (folders.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartCard(serverId: String, smart: SmartData) {
    val acknowledgements = rememberSmartAcknowledgements(serverId, listOf(smart))
    var details by remember { mutableStateOf(false) }
    if (details) SmartDetailsDialog(serverId, smart) { details = false }
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(smart.model ?: smart.deviceFile.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge)
            Text(smart.deviceFile, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = smart.overallStatus
            val healthColor = when (status?.uppercase()) {
                "GOOD", "PASSED" -> MaterialTheme.colorScheme.primary
                null, "UNKNOWN" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!smart.unavailable && healthColor == MaterialTheme.colorScheme.error) Icon(Icons.Filled.Warning, null, tint = healthColor)
                Text(if (smart.unavailable) "SMART unavailable" else "Health: ${status?.replace('_', ' ') ?: "Not reported"}", style = MaterialTheme.typography.labelLarge, color = healthColor)
            }
            if (com.omvcompanion.app.notifications.smartAlerts(listOf(smart)).isNotEmpty()) {
                if (acknowledgements.contains(smart)) Text("Warning acknowledged · hidden from overview", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ if (acknowledgements.contains(smart)) acknowledgements.restore(smart) else acknowledgements.acknowledge(smart) }) {
                    Text(if (acknowledgements.contains(smart)) "Show warning on overview" else "Acknowledge & hide from overview")
                }
            }
            if (smart.unavailable) Text("The device or connection does not expose readable health data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ details = true }, enabled = !smart.unavailable) { Text("Information, attributes & self-tests") }
            Text(smart.temperatureCelsius?.let { "Temperature: $it\u00b0C" } ?: "Temperature unavailable", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SharedFolderCard(sf: SharedFolder, browse: () -> Unit) {
    ControlPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderShared, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(sf.name, style = MaterialTheme.typography.titleMedium)
                TextButton(browse) { Text("Browse files") }
                if (sf.description != null) {
                    Text(sf.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                if (sf.absolutePath != null) {
                    Text("Path: ${sf.absolutePath}", style = MaterialTheme.typography.bodySmall)
                }
                if (sf.device != null) {
                    Text("Device: ${sf.device}", style = MaterialTheme.typography.bodySmall)
                }
                if (sf.status != null) {
                    Text("Status: ${sf.status}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FileSystemCard(fs: FileSystem) {
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Save, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(fs.description?.substringBefore(" [")?.takeIf { it.isNotBlank() } ?: "${fs.type?.uppercase() ?: "Filesystem"} volume", style = MaterialTheme.typography.titleLarge)
            }
            Text(fs.dir?.takeIf { it.isNotBlank() } ?: "Not mounted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${(fs.usedFraction * 100).roundToInt()}% used / ${formatBytes(fs.sizeBytes)} total", style = MaterialTheme.typography.titleMedium)
            
            val color = if (fs.usedFraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { fs.usedFraction.coerceIn(0f, 1f) }, color = color, modifier = Modifier.fillMaxWidth().height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Used: ${formatBytes(fs.usedBytes)}", style = MaterialTheme.typography.bodySmall)
                    Text("Free: ${formatBytes(fs.availableBytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiskCard(disk: Disk, smart: SmartData?, loading: Boolean, error: String?) {
    var expanded by remember { mutableStateOf(false) }
    ControlPanel(Modifier.fillMaxWidth().clickable(onClickLabel = "Drive details") { expanded = !expanded }) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Filled.Storage, null, Modifier.padding(12.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(disk.model?.takeIf { it.isNotBlank() } ?: disk.deviceFile.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val shortDevice = disk.deviceFile.takeUnless { it.startsWith("/dev/disk/by-id/") }
                    Text(listOfNotNull(shortDevice, formatBytes(disk.capacityBytes), smart?.temperatureCelsius?.let { "$it°C" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val failed = smart != null && com.omvcompanion.app.notifications.smartAlerts(listOf(smart)).isNotEmpty()
            val healthy = smart?.overallStatus?.uppercase() in setOf("GOOD", "PASSED") && smart?.unavailable == false
            StatusPill(when { loading -> "Reading health…"; error != null -> "Health request unavailable"; smart == null -> "Check SMART tab"; smart.unavailable -> "SMART unavailable"; failed -> "Needs attention"; healthy -> "● Healthy"; else -> "Health not reported" },
                when { failed -> Color(0xFFAF6A00); healthy -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            if (smart?.unavailable == true) Text("Device does not report health", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Text("Device: ${disk.deviceFile}", style = MaterialTheme.typography.bodySmall)
                disk.serialNumber?.let { Text("Serial: $it", style = MaterialTheme.typography.bodySmall) }
                disk.vendor?.let { Text("Vendor: $it", style = MaterialTheme.typography.bodySmall) }
                if (disk.isRaid) Text("RAID device", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
