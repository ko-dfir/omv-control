package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SystemInfo
import com.omvcompanion.app.ui.UiState
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.components.ControlPanel
import com.omvcompanion.app.ui.formatBytes
import com.omvcompanion.app.ui.formatUptime
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import com.omvcompanion.app.data.repo.PerformanceSample

@Composable
fun ControlRoomOverview(serverId: String, info: SystemInfo, state: DashboardState, navigate: (Int) -> Unit, onChangeServer: () -> Unit = {}, onPreferences: () -> Unit = {}, onReviewHealth: () -> Unit = { navigate(1) }) {
    val storageVm = appViewModel<StorageViewModel>(key = "storage-$serverId") { StorageViewModel(serverId, it.storageRepository) }
    val dockerVm = appViewModel<DockerViewModel>(key = "docker-$serverId") { DockerViewModel(serverId, it.sessionManager) }
    val servicesVm = appViewModel<ServicesViewModel>(key = "services-$serverId") { ServicesViewModel(serverId, it.servicesRepository) }
    val storageState by storageVm.uiState.collectAsStateWithLifecycle()
    val docker by dockerVm.state.collectAsStateWithLifecycle()
    val serviceState by servicesVm.uiState.collectAsStateWithLifecycle()
    val storage = (storageState as? UiState.Success)?.data
    val services = (serviceState as? UiState.Success)?.data
    val acknowledgements = rememberSmartAcknowledgements(serverId, storage?.smarts.orEmpty())
    val unacknowledged = storage?.smarts.orEmpty().filter { !acknowledgements.contains(it) && com.omvcompanion.app.notifications.smartAlerts(listOf(it)).isNotEmpty() }
    var acknowledgeOpen by remember { mutableStateOf(false) }
    if (acknowledgeOpen) AlertDialog(onDismissRequest = { acknowledgeOpen = false }, title = { Text("Acknowledge drive warnings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Acknowledging hides this warning from the overview. Drive health remains visible in Storage. A different reported failure will appear again.")
            unacknowledged.forEach { smart ->
                Text(smart.model ?: smart.deviceFile, style = MaterialTheme.typography.titleMedium)
                Text(smart.overallStatus.orEmpty().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                TextButton({ acknowledgements.acknowledge(smart); if (unacknowledged.size == 1) acknowledgeOpen = false }) { Text("Acknowledge & hide") }
            }
        }
    }, confirmButton = { TextButton({ acknowledgeOpen = false }) { Text("Close") } })
    val locale = LocalConfiguration.current.locales[0]
    val context = androidx.compose.ui.platform.LocalContext.current
    val history = (context.applicationContext as com.omvcompanion.app.OmvApp).container.performanceRepository
    var samples by remember(serverId) { mutableStateOf<List<PerformanceSample>>(emptyList()) }
    LaunchedEffect(serverId) { history.observe(serverId).collect { samples = it } }
    var layoutOpen by remember { mutableStateOf(false) }
    var layoutRevision by remember { mutableIntStateOf(0) }
    val cardOrder = remember(layoutRevision) { dashboardOrder(context) }
    val hidden = remember(layoutRevision) { dashboardHidden(context) }
    val monitorVm = appViewModel(key = "hardware-monitor-$serverId") { HardwareMonitoringViewModel(serverId, it.hardwareMonitoringRepository) }
    val monitoring by monitorVm.state.collectAsStateWithLifecycle()
    val showMonitor = "cpu" !in hidden || "gpu" !in hidden
    androidx.lifecycle.compose.LifecycleStartEffect(monitorVm, showMonitor) {
        if (showMonitor) monitorVm.start()
        onStopOrDispose { monitorVm.stop() }
    }
    var monitorSetup by remember { mutableStateOf(false) }
    if (monitorSetup) MonitoringSetupDialog(serverId, monitoring, { navigate(5) }, monitorVm::install) { monitorSetup = false }
    if (layoutOpen) DashboardLayoutDialog({ layoutOpen = false }, { layoutRevision++ })
    LaunchedEffect(serverId) { storageVm.loadSection("Drives"); storageVm.loadSection("Filesystem"); storageVm.loadSection("SMART") }
    @Composable fun StoragePanel(modifier: Modifier) {
        ControlPanel(modifier) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { navigate(1) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Storage", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("${storage?.fileSystems?.size ?: 0} volumes", style = MaterialTheme.typography.bodySmall)
                    Icon(Icons.Filled.ChevronRight, "Open storage")
                }
                Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val files = storage?.fileSystems.orEmpty()
                if (files.isEmpty()) Text(storage?.errors?.get("Filesystem") ?: if ("Filesystem" in storage?.loadingSections.orEmpty()) "Loading volumes…" else "No mounted volumes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                files.forEach { fs ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(fs.description?.substringBefore(" [") ?: fs.deviceFile.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f), maxLines = 1)
                        LinearProgressIndicator(progress = { fs.usedFraction.coerceIn(0f, 1f) }, modifier = Modifier.weight(1.4f).height(10.dp), color = if (fs.percentage >= fs.warningThreshold) Color(0xFFF8D400) else MaterialTheme.colorScheme.primary, drawStopIndicator = {})
                        Text(if (fs.isMounted) "${fs.percentage}%" else "—", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("${storage?.disks?.size ?: 0} physical drives · ${files.size} file systems", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                storage?.disks.orEmpty().forEach { drive ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(drive.model ?: drive.deviceFile.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(formatBytes(drive.capacityBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
                Text("Scroll for all drives and volumes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            }
        }
    }
    @Composable fun ActivityPanel(modifier: Modifier) {
        ControlPanel(modifier) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Server activity", style = MaterialTheme.typography.titleLarge)
                ActivityRow("Docker engine", when { docker.loading -> "Loading…"; docker.error != null -> "Unavailable"; else -> "Available" }, docker.error == null && !docker.loading)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val ssh = services?.find { it.title.contains("SSH", true) || it.name.contains("ssh", true) }
                ActivityRow("SSH service", when { ssh?.running == true -> "Running"; ssh != null -> "Stopped"; serviceState is UiState.Loading -> "Loading…"; else -> "Not reported" }, ssh?.running == true)
                if (info.configDirty || info.rebootRequired || info.updatesAvailable > 0) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when { info.configDirty -> "Pending server configuration"; info.rebootRequired -> "Reboot required"; else -> "Updates available" }, style = MaterialTheme.typography.labelLarge)
                            Text(when { info.configDirty -> "OMV reports unapplied server changes. These may belong to services other than networking; review them in the OMV web interface."; info.rebootRequired -> "Restart the server to finish applying updates."; else -> "${info.updatesAvailable} package updates are available." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!info.configDirty) TextButton({ navigate(3) }) { Text("Open updates") }
                        }
                    }
                } else {
                    ActivityRow("System configuration", "Applied", true)
                    Text("Uptime · ${info.uptimeSeconds?.let(::formatUptime) ?: info.uptimeText ?: "Not reported"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val visible = cardOrder.filter { it !in hidden }
        val groups = mutableListOf<String>()
        var index = 0
        while (index < visible.size) {
            val card = visible[index]
            if (card == "memory") {
                val metrics = mutableListOf<String>()
                while (index < visible.size && visible[index] == "memory" && metrics.size < 2) { metrics += visible[index]; index++ }
                groups += "metric:${metrics.joinToString(",")}"; continue
            }
            if (wide && card in listOf("storage", "activity") && visible.getOrNull(index + 1) in listOf("storage", "activity")) { groups += "$card+${visible[index + 1]}"; index += 2 }
            else { groups += card; index++ }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.omvcompanion.app.R.drawable.ic_brand), null, Modifier.size(34.dp))
                        Text("OMV Control", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        FilledTonalIconButton(onPreferences) { Icon(Icons.Filled.ManageAccounts, "App preferences") }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Change server", onClick = onChangeServer)) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.Storage, null, Modifier.size(20.dp))
                            Text("●", color = if (state.error == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(state.serverName.ifBlank { info.hostname }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Filled.ExpandMore, null)
                        }
                    }
                    Text("Your server,\nat a glance.", fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    val warnings = unacknowledged.size
                    if (warnings > 0) Surface(color = Color(0xFFF8D400), contentColor = Color(0xFF4D2E25), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onReviewHealth)) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Filled.HealthAndSafety, null, Modifier.size(38.dp))
                            Column(Modifier.weight(1f)) {
                                Text("$warnings ${if (warnings == 1) "drive needs" else "drives need"} attention", style = MaterialTheme.typography.titleMedium)
                                Text("SMART warning · Review health", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton({ acknowledgeOpen = true }) { Icon(Icons.Filled.CheckCircle, "Acknowledge drive warnings") }
                            Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            groups.forEach { card -> item(key = card) { when(card) {
                "storage+activity", "activity+storage" -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (card.startsWith("storage")) { StoragePanel(Modifier.weight(1f)); ActivityPanel(Modifier.weight(1f)) }
                    else { ActivityPanel(Modifier.weight(1f)); StoragePanel(Modifier.weight(1f)) }
                }
                "storage" -> StoragePanel(Modifier.fillMaxWidth())
                "activity" -> ActivityPanel(Modifier.fillMaxWidth())
                "hardware" -> HardwarePanel(serverId)
                "cpu" -> CpuOverviewCard(info, monitoring, samples, { navigate(8) }, { monitorSetup = true })
                "gpu" -> GpuMonitoringCard(monitoring, monitorVm::refresh) { monitorSetup = true }
                "containers" -> Surface(color = Color(0xFF5DACDF), contentColor = Color(0xFF161F26), shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open Docker containers") { navigate(4) }) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = Color(0xFF1B7BBA), shape = RoundedCornerShape(22.dp)) { Icon(Icons.Filled.ViewInAr, null, Modifier.padding(12.dp).size(36.dp), tint = Color(0xFFFFFFFF)) }
                        Column(Modifier.weight(1f)) {
                            Text(if (docker.loading || docker.error != null) "—" else docker.containers.count { it.state == "running" }.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                            Text(if (docker.loading) "Loading containers…" else if (docker.error != null) "Docker unavailable" else "containers running", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Open Docker", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
                else -> if (card.startsWith("metric:")) {
                    @Composable fun Metric(id: String, modifier: Modifier) {
                        when(id) {
                            "cpu" -> ConsoleMetric("CPU", info.cpuUsagePercent?.let { String.format(locale, "%.0f", it) } ?: "—", "%", null, info.cpuUsagePercent?.div(100)?.toFloat(), modifier.clickable(onClickLabel = "Open performance history") { navigate(8) }, samples.takeLast(30))
                            "memory" -> ConsoleMetric("Memory", info.memUsedFraction?.let { "${(it * 100).toInt()}" } ?: "—", "%", "${formatBytes(info.memUsed)} / ${formatBytes(info.memTotal)}", info.memUsedFraction, modifier.clickable(onClickLabel = "Open performance history") { navigate(8) })
                            "containers" -> ConsoleMetric("Containers running", if (docker.loading || docker.error != null) "—" else docker.containers.count { it.state == "running" }.toString(), if (docker.loading || docker.error != null) "" else "/ ${docker.containers.size}", when { docker.loading -> "Loading…"; docker.error != null -> "Unavailable"; docker.containers.isEmpty() -> "No containers"; docker.containers.all { it.state == "running" } -> "All running"; else -> "Some containers stopped" }, null, modifier.clickable(onClickLabel = "Open Docker containers") { navigate(4) })
                        }
                    }
                    val metrics = card.removePrefix("metric:").split(',')
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { metrics.forEach { Metric(it, Modifier.weight(1f)) } }
                }

            } } }
            item { Row { TextButton({ layoutOpen = true }) { Text("Customize") }; TextButton({ navigate(8) }) { Text("Performance history") } } }
        }
    }
}

@Composable private fun ActivityRow(label: String, status: String, available: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ConsoleMetric(label: String, value: String, unit: String, status: String?, fraction: Float?, modifier: Modifier, samples: List<PerformanceSample> = emptyList()) {
    ControlPanel(modifier.heightIn(min = 164.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp)
                Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (label == "CPU" && samples.count { it.cpu != null } >= 2) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(40.dp)) {
                    val span = ((samples.last().time - samples.first().time).coerceAtLeast(1)).toFloat()
                    val path = Path()
                    var connected = false
                    samples.forEachIndexed { i, sample ->
                        val cpu = sample.cpu
                        if (cpu == null) connected = false else {
                            val point = cpuSparklineOffset((sample.time - samples.first().time) / span, cpu, size.width, size.height, 3.dp.toPx())
                            val x = point.x
                            val y = point.y
                            if (i > 0 && sample.time - samples[i - 1].time > 360000) connected = false
                            if (connected) path.lineTo(x, y) else path.moveTo(x, y)
                            connected = true
                        }
                    }
                    drawPath(path, color, style = Stroke(2.5.dp.toPx()))
                }
            } else if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(10.dp), drawStopIndicator = {})
            else Text(status ?: "Reading unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
