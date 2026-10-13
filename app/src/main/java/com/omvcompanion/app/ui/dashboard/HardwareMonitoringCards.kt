package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.components.ControlPanel
import com.omvcompanion.app.ui.components.TaskOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

data class HardwareMonitoringState(val snapshot: HardwareSnapshot? = null, val busy: Boolean = false, val error: String? = null,
    val installing: Boolean = false, val output: String = "", val installError: String? = null)
class HardwareMonitoringViewModel(private val id: String, private val repository: HardwareMonitoringRepository) : ViewModel() {
    val state = MutableStateFlow(HardwareMonitoringState())
    private var poll: Job? = null
    private var reading: Job? = null
    fun start() { if (poll?.isActive != true) poll = viewModelScope.launch { while (isActive) { refresh(); delay(10000) } } }
    fun stop() { poll?.cancel(); poll = null; reading?.cancel(); reading = null }
    fun refresh() {
        if (reading?.isActive == true || state.value.installing) return
        reading = viewModelScope.launch {
            state.update { it.copy(busy = true, error = null) }
            try { val snapshot = repository.sample(id); state.update { it.copy(snapshot = snapshot) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message) } }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun install(packages: List<String>) {
        if (state.value.installing) return
        state.update { it.copy(installing = true, output = "", installError = null) }
        viewModelScope.launch {
            try { repository.installTools(id, packages) { text -> state.update { it.copy(output = (it.output + text).takeLast(20000)) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(installError = e.message) } }
            finally { state.update { it.copy(installing = false) }; refresh() }
        }
    }
}

private fun speed(mhz: Double?) = mhz?.let { String.format(Locale.ROOT, "%.2f GHz", it / 1000) } ?: "Not reported"
private fun temperature(value: Double?) = value?.let { String.format(Locale.ROOT, "%.0f°C", it) } ?: "Not reported"

@Composable fun CpuOverviewCard(info: SystemInfo, monitoring: HardwareMonitoringState, samples: List<PerformanceSample>, history: () -> Unit, setup: () -> Unit) {
    val cpu = monitoring.snapshot?.cpu
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Processor", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(history) { Text("History") }
            }
            Text(cpu?.model ?: info.cpuModel ?: "CPU model not reported", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { Text("Usage", style = MaterialTheme.typography.labelMedium); Text(info.cpuUsagePercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "—", fontSize = 32.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Text("Temperature", style = MaterialTheme.typography.labelMedium); Text(temperature(cpu?.temperature ?: info.cpuTemp), style = MaterialTheme.typography.titleLarge) }
            }
            val points = samples.takeLast(30).filter { it.cpu != null }
            val upper = maxOf(20.0, (points.maxOfOrNull { it.cpu!! } ?: 0.0) * 1.2).coerceAtMost(100.0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recent CPU usage", style = MaterialTheme.typography.labelMedium)
                Text("0–${kotlin.math.ceil(upper).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val line = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            if (points.size < 2) Text("Collecting readings…", style = MaterialTheme.typography.bodySmall)
            else Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val padding = 6.dp.toPx()
                drawLine(grid, androidx.compose.ui.geometry.Offset(padding, size.height - padding), androidx.compose.ui.geometry.Offset(size.width - padding, size.height - padding), 1.dp.toPx())
                val span = (points.last().time - points.first().time).coerceAtLeast(1).toFloat()
                val path = Path()
                points.forEachIndexed { i, p ->
                    val position = cpuSparklineOffset((p.time - points.first().time) / span, p.cpu!! / upper * 100, size.width, size.height, padding)
                    if (i == 0 || p.time - points[i - 1].time > 360000) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
                }
                drawPath(path, line, style = Stroke(3.dp.toPx()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { Text("Current clock", style = MaterialTheme.typography.labelMedium); Text(speed(cpu?.mhz), style = MaterialTheme.typography.bodyMedium) }
                Column(Modifier.weight(1f)) { Text("Cores / threads", style = MaterialTheme.typography.labelMedium); Text("${cpu?.cores ?: "—"} / ${cpu?.threads ?: "—"}", style = MaterialTheme.typography.bodyMedium) }
            }
            cpu?.maxMhz?.let { Text("Maximum reported clock: ${speed(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            cpu?.architecture?.let { Text(it + (cpu.sockets?.let { count -> " · $count socket(s)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (cpu?.temperature == null || monitoring.error != null) TextButton(setup) { Text("Sensor & monitoring setup") }
            monitoring.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable fun GpuMonitoringCard(state: HardwareMonitoringState, refresh: () -> Unit, setup: () -> Unit) {
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GPU monitoring", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(refresh, enabled = !state.busy && !state.installing) { Text("Refresh") }
            }
            if (state.busy && state.snapshot == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val devices = state.snapshot?.gpus.orEmpty()
            if (devices.isEmpty()) Text(state.error ?: state.snapshot?.warnings?.lastOrNull() ?: "Detecting graphics hardware…", style = MaterialTheme.typography.bodyMedium)
            devices.forEachIndexed { i, gpu ->
                if (i > 0) HorizontalDivider()
                Text(gpu.name, style = MaterialTheme.typography.titleMedium)
                Text("${gpu.vendor} · ${gpu.device}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                gpu.utilization?.let { Text(String.format(Locale.ROOT, "GPU usage: %.1f%%", it)); LinearProgressIndicator(progress = { (it / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                gpu.engines.forEach { (name, usage) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name.replace("VideoEnhance", "Video enhancement"), style = MaterialTheme.typography.bodySmall); Text(String.format(Locale.ROOT, "%.1f%%", usage), style = MaterialTheme.typography.bodySmall) }
                    LinearProgressIndicator(progress = { (usage / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp), drawStopIndicator = {})
                }
                if (gpu.mhz != null || gpu.temperature != null) Text(listOfNotNull(gpu.mhz?.let { "Clock: ${speed(it)}" }, gpu.temperature?.let { "Temperature: ${temperature(it)}" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (gpu.memoryUsedMb != null && gpu.memoryTotalMb != null) Text(String.format(Locale.ROOT, "VRAM: %.0f / %.0f MiB", gpu.memoryUsedMb, gpu.memoryTotalMb), style = MaterialTheme.typography.bodySmall)
                gpu.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("Samples every 10 seconds while the overview is visible. Unsupported readings remain unavailable.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(setup) { Text("Monitoring setup & tools") }
            if (state.installing || state.output.isNotBlank()) TaskOutput("Monitoring tools", state.output, state.installing)
            state.installError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable fun MonitoringSetupDialog(serverId: String, state: HardwareMonitoringState, ssh: () -> Unit, install: (List<String>) -> Unit, close: () -> Unit) {
    val needed = state.snapshot?.tools.orEmpty()
    AlertDialog(onDismissRequest = close, title = { Text("Monitoring setup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect and verify SSH first. CPU temperatures use exposed Linux CPU sensors; Intel uses intel_gpu_top, AMD uses amdgpu counters, and NVIDIA uses nvidia-smi matching its installed driver.")
            state.snapshot?.warnings.orEmpty().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.snapshot?.gpus.orEmpty().mapNotNull { it.note }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (needed.isNotEmpty()) {
                Text("Available tool installation: ${needed.joinToString()}", style = MaterialTheme.typography.titleMedium)
                Text("These packages and their dependencies will be installed on the server. Drivers and perf/sensor permissions are not changed automatically. Tools cannot guarantee hardware support.")
                Button({ install(needed); close() }, enabled = !state.installing && serverId != "demo") { Text("Install monitoring tools") }
            }
            TextButton({ close(); ssh() }) { Text("Open SSH") }
        }
    }, confirmButton = { TextButton(close) { Text("Close") } })
}
