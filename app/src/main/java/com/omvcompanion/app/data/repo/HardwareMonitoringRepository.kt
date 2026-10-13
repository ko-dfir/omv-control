package com.omvcompanion.app.data.repo

import android.content.Context
import com.omvcompanion.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class CpuReading(val model: String? = null, val architecture: String? = null,
    val cores: Int? = null, val threads: Int? = null, val sockets: Int? = null,
    val mhz: Double? = null, val minMhz: Double? = null, val maxMhz: Double? = null, val temperature: Double? = null)
@Serializable data class GpuReading(val name: String, val vendor: String, val device: String,
    val engines: Map<String, Double> = emptyMap(), val utilization: Double? = null,
    val temperature: Double? = null, val mhz: Double? = null, val memoryUsedMb: Double? = null,
    val memoryTotalMb: Double? = null, val note: String? = null)
@Serializable data class HardwareSnapshot(val cpu: CpuReading = CpuReading(), val gpus: List<GpuReading> = emptyList(),
    val tools: List<String> = emptyList(), val warnings: List<String> = emptyList())

internal val monitoringPackages = setOf("python3", "intel-gpu-tools", "lm-sensors", "pciutils", "util-linux")
internal fun validatedMonitoringPackages(packages: List<String>): List<String> {
    require(packages.isNotEmpty() && packages.all { it in monitoringPackages }) { "Unsupported monitoring package" }
    return packages.distinct()
}

class HardwareMonitoringRepository(private val context: Context, private val servers: ServerRepository,
    private val ssh: SshRunner, private val software: SoftwareRepository) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun sample(id: String): HardwareSnapshot = withContext(Dispatchers.IO) {
        if (id == "demo") return@withContext HardwareSnapshot(CpuReading("Intel Core i5", "x86_64", 6, 12, 1, 2400.0, 800.0, 4200.0, 43.0),
            listOf(GpuReading("Intel UHD Graphics", "Intel", "card0", mapOf("Render/3D/0" to 12.0, "Video/0" to 7.0), mhz = 650.0)))
        val profile = servers.get(id) ?: error("Server not found")
        require(profile.sshUsername != null && profile.sshFingerprint != null) { "Configure and verify SSH to read CPU sensors and GPU counters." }
        val script = context.resources.openRawResource(R.raw.hardware_monitor).bufferedReader().use { it.readText() }
        withTimeout(25000) {
            val result = ssh.execute(id, "LC_ALL=C python3 -", stdin = script, timeoutMs = 18000)
            if (result.exitCode == 127) return@withTimeout HardwareSnapshot(tools = listOf("python3"), warnings = listOf("Install Python 3 to enable hardware monitoring."))
            require(result.exitCode == 0) { result.error.ifBlank { "Hardware reader failed (${result.exitCode})" }.take(1000) }
            json.decodeFromString<HardwareSnapshot>(result.output)
        }
    }
    suspend fun installTools(id: String, packages: List<String>, output: (String) -> Unit) {
        software.installUpdates(id, validatedMonitoringPackages(packages), output)
    }
}
