package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.omvcompanion.app.data.repo.*
import androidx.compose.ui.platform.LocalContext
import com.omvcompanion.app.OmvApp
import kotlinx.coroutines.launch
import com.omvcompanion.app.ui.components.ControlPanel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable fun PerformanceScreen(serverId: String, padding: PaddingValues) {
    val repository = (LocalContext.current.applicationContext as OmvApp).container.performanceRepository
    var samples by remember(serverId) { mutableStateOf<List<PerformanceSample>>(emptyList()) }
    LaunchedEffect(serverId) { repository.observe(serverId).collect { samples = it } }
    var range by rememberSaveable(serverId) { mutableStateOf("1 hour") }
    var clear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hours = when (range) { "24 hours" -> 24; "7 days" -> 168; else -> 1 }
    val end = maxOf(System.currentTimeMillis(), samples.lastOrNull()?.time ?: 0)
    val start = end - hours * 3_600_000L
    val visible = samples.filter { it.time >= start }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { Text("Performance history", style = MaterialTheme.typography.headlineMedium)
                    Text("Recorded on this device · retained for 7 days", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton({ clear = true }, enabled = samples.isNotEmpty() && serverId != "demo") { Icon(Icons.Filled.DeleteOutline, "Clear this server's history") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 hour", "24 hours", "7 days").forEach { FilterChip(range == it, { range = it }, label = { Text(it) }) }
            }
            Text("Samples are collected every 30 seconds while the app is open, and when enabled background monitoring or the widget checks the server. Gaps mean no observation was recorded. Tap a chart to inspect a reading.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Network and disk throughput require configured, verified SSH and Python 3. They cover physical network interfaces and disks; virtual interfaces and logical RAID devices are excluded to avoid counting traffic twice. Two observations are needed for a rate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (visible.isEmpty()) item { Text("History starts with your next successful server check. Leave the app open or enable monitoring in App preferences.") }
        listOf("CPU", "RAM", "Network received", "Network sent", "Disk read", "Disk written", "Temperature", "Load").forEach { metric -> item { HistoryChart(metric, visible, start, end) } }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear performance history?") }, text = { Text("Only this server's history on this device will be deleted. New readings will continue to be recorded.") },
        confirmButton = { TextButton({ clear = false; scope.launchClear(repository, serverId) }) { Text("Clear") } }, dismissButton = { TextButton({ clear = false }) { Text("Cancel") } })
}

private fun kotlinx.coroutines.CoroutineScope.launchClear(repository: PerformanceRepository, id: String) = launch { repository.clear(id) }

@Composable private fun HistoryChart(metric: String, samples: List<PerformanceSample>, start: Long, end: Long) {
    val points = samples.mapNotNull { s -> performanceValue(s, metric)?.let { s.time to it } }
    val isRate = metric !in listOf("CPU", "RAM", "Temperature", "Load")
    val peak = points.maxOfOrNull { it.second } ?: 0.0
    val factor = if (isRate && peak < 1) 1024.0 else 1.0
    val unit = when (metric) { "CPU", "RAM" -> "%"; "Temperature" -> "°C"; "Load" -> ""; else -> if (factor > 1) " KiB/s" else " MiB/s" }
    val upper = when (metric) { "CPU", "RAM" -> 100.0; "Temperature" -> maxOf(60.0, peak * 1.1); else -> maxOf(1.0 / factor, peak * 1.2) }
    fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.1f%s", it * factor, unit) } ?: "—"
    var selected by remember(metric, start) { mutableStateOf<Pair<Long, Double>?>(null) }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (metric == "Load") "CPU load · 1 minute" else metric, style = MaterialTheme.typography.titleLarge)
                Text(number(points.lastOrNull()?.second), style = MaterialTheme.typography.titleLarge, color = color)
            }
            if (points.isEmpty()) Text("No readings available in this period", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                Text("Average ${number(points.map { it.second }.average())} · Peak ${number(points.maxOf { it.second })} · ${points.size} readings", style = MaterialTheme.typography.bodySmall)
                Row { Text(number(upper), style = MaterialTheme.typography.labelSmall) }
                Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(points, start, end) {
                    detectTapGestures { position ->
                        val target = start + ((end - start) * (position.x / size.width).coerceIn(0f, 1f)).toLong()
                        selected = points.minByOrNull { abs(it.first - target) }
                    }
                }) {
                    repeat(5) { n -> val y = size.height * n / 4; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
                    fun offset(p: Pair<Long, Double>) = Offset(((p.first - start).toDouble() / (end - start) * size.width).toFloat(), (size.height * (1 - p.second.coerceIn(0.0, upper) / upper)).toFloat())
                    points.zipWithNext().forEach { (a, b) -> if (b.first - a.first <= 6 * 60_000L) drawLine(color, offset(a), offset(b), 2.dp.toPx()) }
                    points.forEach { drawCircle(color, 2.dp.toPx(), offset(it)) }
                    selected?.let { val o = offset(it); drawLine(color.copy(alpha = .5f), Offset(o.x, 0f), Offset(o.x, size.height)); drawCircle(color, 5.dp.toPx(), o) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(start)), style = MaterialTheme.typography.labelSmall)
                    Text("Now", style = MaterialTheme.typography.labelSmall)
                }
                selected?.let { Text("${DateFormat.getDateTimeInstance().format(Date(it.first))} · ${number(it.second)}", color = color, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
