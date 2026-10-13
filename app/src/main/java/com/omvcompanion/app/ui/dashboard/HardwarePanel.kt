package com.omvcompanion.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.SshRunner
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.components.ControlPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HardwareState(val busy: Boolean = false, val sections: List<Pair<String, String>> = emptyList(), val error: String? = null)
class HardwareViewModel(private val serverId: String, private val ssh: SshRunner) : ViewModel() {
    private val mutable = MutableStateFlow(HardwareState())
    val state = mutable.asStateFlow()
    fun refresh() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val sections = mutableListOf<Pair<String, String>>()
                for ((title, command) in listOf(
                    "Processor" to "LC_ALL=C lscpu",
                    "Graphics" to "LC_ALL=C lspci -nn | grep -Ei 'VGA compatible|3D controller|Display controller'",
                    "Memory modules" to "LC_ALL=C dmidecode --type memory"
                )) {
                    val result = ssh.execute(serverId, command)
                    val text = when (title) {
                        "Processor" -> result.output.lines().filter { line -> listOf("Model name:", "CPU(s):", "Socket(s):", "Core(s) per socket:", "Thread(s) per core:", "CPU max MHz:", "CPU min MHz:", "Architecture:").any { line.trimStart().startsWith(it) } }.joinToString("\n") { it.replace(Regex("\\s{2,}"), " ").trim() }
                        "Graphics" -> result.output.lines().filter { it.isNotBlank() }.joinToString("\n") { it.substringAfter(": ", it) }
                        else -> readableMemory(result.output)
                    }
                    sections += title to text.ifBlank { "Unavailable. ${result.error.trim().ifBlank { if (title == "Memory modules") "Firmware did not report installed modules; administrative SSH access and dmidecode are required." else "The server did not report this information; check that the hardware tool is installed." }}" }
                }
                mutable.value = HardwareState(sections = sections)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = HardwareState(error = e.message) }
        }
    }
}
internal fun readableMemory(raw: String): String = raw.split("Memory Device").drop(1).mapNotNull { block ->
    val fields = block.lines().mapNotNull { line -> val key = line.substringBefore(':').trim(); val value = line.substringAfter(':', "").trim(); if (value.isBlank()) null else key to value }.toMap()
    val size = fields["Size"] ?: return@mapNotNull null
    if (size.contains("No Module", true)) return@mapNotNull null
    listOf("Locator", "Size", "Type", "Speed", "Configured Memory Speed", "Manufacturer", "Part Number").mapNotNull { key -> fields[key]?.let { "$key: $it" } }.joinToString("\n")
}.joinToString("\n\n")

@Composable fun HardwarePanel(serverId: String) {
    val vm = appViewModel(key = "hardware-$serverId") { HardwareViewModel(serverId, it.sshRunner) }
    val state by vm.state.collectAsStateWithLifecycle()
    ControlPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hardware details", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(vm::refresh, enabled = !state.busy) { Text(if (state.sections.isEmpty()) "Read hardware" else "Refresh") }
            }
            Text("Processor, graphics and RAM speed from the server’s saved SSH connection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.sections.forEach { (title, value) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
