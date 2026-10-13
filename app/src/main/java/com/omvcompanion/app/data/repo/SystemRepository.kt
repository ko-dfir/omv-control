package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.OmvRpcClient
import com.omvcompanion.app.data.rpc.OmvConnectionException
import com.omvcompanion.app.data.rpc.bool
import com.omvcompanion.app.data.rpc.dbl
import com.omvcompanion.app.data.rpc.lng
import com.omvcompanion.app.data.rpc.obj
import com.omvcompanion.app.data.rpc.str
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch

/** Parsed result of `System.getInformation`. */
data class SystemInfo(
    val hostname: String,
    val version: String?,
    val kernel: String?,
    val cpuModel: String?,
    val cpuUsagePercent: Double?,
    val memTotal: Long?,
    val memUsed: Long?,
    val load1: Double?,
    val load5: Double?,
    val load15: Double?,
    val uptimeSeconds: Long?,
    val uptimeText: String?,
    val configDirty: Boolean,
    val rebootRequired: Boolean,
    val updatesAvailable: Int,
    val cpuTemp: Double?,
    val tempDebug: String? = null,
) {
    val memUsedFraction: Float?
        get() = if (memTotal != null && memUsed != null && memTotal > 0) (memUsed.toDouble() / memTotal).toFloat() else null

    companion object {
        fun parse(o: JsonObject): SystemInfo {
            val memTotal = o.lng("memTotal")
            val memUsed = o.lng("memUsed")
                ?: o.lng("memAvailable")?.let { avail -> memTotal?.minus(avail) }
            // OMV 7: {"1min":..,"5min":..,"15min":..}; older: "0.10, 0.20, 0.30"
            val loadObj = o.obj("loadAverage")
            val loadList = if (loadObj == null) o.str("loadAverage")?.split(',', ' ')
                ?.mapNotNull { it.trim().toDoubleOrNull() } else null
            val uptimeNumber = o.dbl("uptime")
            val updates = o.lng("availablePkgUpdates")?.toInt()
                ?: if (o.bool("pkgUpdatesAvailable") == true) 1 else 0
            return SystemInfo(
                hostname = o.str("hostname") ?: "?",
                version = o.str("version"),
                kernel = o.str("kernel"),
                cpuModel = o.str("cpuModelName") ?: o.str("cpuModel"),
                cpuUsagePercent = o.dbl("cpuUsage") ?: o.dbl("cpuUtilization"),
                memTotal = memTotal,
                memUsed = memUsed,
                load1 = loadObj?.dbl("1min") ?: loadList?.getOrNull(0),
                load5 = loadObj?.dbl("5min") ?: loadList?.getOrNull(1),
                load15 = loadObj?.dbl("15min") ?: loadList?.getOrNull(2),
                uptimeSeconds = uptimeNumber?.toLong(),
                uptimeText = if (uptimeNumber == null) o.str("uptime") else null,
                configDirty = o.bool("configDirty") == true,
                rebootRequired = o.bool("rebootRequired") == true,
                updatesAvailable = updates,
                cpuTemp = o.dbl("cpuTemp") ?: o.dbl("cpuTemperature") ?: o.dbl("temperature") ?: o.str("cpuTemp")?.toDoubleOrNull(),
            )
        }
    }
}

class SystemRepository(private val sessions: SessionManager, private val onSample: suspend (String, SystemInfo) -> Unit = { _, _ -> }) {

    suspend fun getInformation(serverId: String): SystemInfo {
        val info = fetch(sessions.client(serverId))
        try { onSample(serverId, info) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { /* History storage failure must not hide live server status. */ }
        return info
    }

    private data class TemperatureReading(val checked: Long, val value: Double?)
    private val temperatures = java.util.concurrent.ConcurrentHashMap<OmvRpcClient, TemperatureReading>()
    private val probes = java.util.concurrent.ConcurrentHashMap<OmvRpcClient, kotlinx.coroutines.Job>()
    private val sensorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    suspend fun fetch(client: OmvRpcClient): SystemInfo {
        val response = client.call("System", "getInformation") as? JsonObject
            ?: throw OmvConnectionException("Unexpected System.getInformation response")
        val info = SystemInfo.parse(response)
        if (info.cpuTemp != null) return info
        val cached = temperatures[client]
        if (cached == null || System.currentTimeMillis() - cached.checked > 60_000) {
            probes.compute(client) { _, running ->
                if (running != null && !running.isCompleted) running else sensorScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    var temperature: Double? = null
                    try {
                        val sensors = client.call("Sensors", "getTemperatures") as? kotlinx.serialization.json.JsonArray
                        val first = sensors?.firstOrNull() as? JsonObject
                        temperature = first?.dbl("temp") ?: first?.str("temp")?.toDoubleOrNull()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                    if (temperature == null) try {
                        val cpu = client.call("CpuTemp", "get") as? JsonObject
                        temperature = cpu?.dbl("temperature") ?: cpu?.str("temperature")?.toDoubleOrNull()
                            ?: cpu?.dbl("cputemp") ?: cpu?.str("cputemp")?.toDoubleOrNull()
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                    temperatures[client] = TemperatureReading(System.currentTimeMillis(), temperature)
                }
            }?.start()
        }
        // Optional plugins never delay the primary system response.
        return info.copy(cpuTemp = cached?.value)
    }

    suspend fun reboot(serverId: String) {
        val client = sessions.client(serverId)
        client.call("System", "reboot")
    }

    suspend fun shutdown(serverId: String) {
        val client = sessions.client(serverId)
        try {
            client.call("System", "shutdown")
        } catch (e: Exception) {
            client.call("System", "halt")
        }
    }
}
