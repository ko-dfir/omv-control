package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.OmvRpcClient
import com.omvcompanion.app.data.rpc.OmvConnectionException
import com.omvcompanion.app.data.rpc.arr
import com.omvcompanion.app.data.rpc.dbl
import com.omvcompanion.app.data.rpc.lng
import com.omvcompanion.app.data.rpc.obj
import com.omvcompanion.app.data.rpc.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import com.omvcompanion.app.data.rpc.OmvRpcException

data class FileSystem(
    val uuid: String,
    val deviceFile: String,
    val description: String?,
    val dir: String?, // mountpoint
    val type: String?,
    val sizeBytes: Long,
    val usedBytes: Long,
    val percentage: Int, // 0-100
    val warningThreshold: Int = 85,
) {
    val availableBytes = maxOf(0L, sizeBytes - usedBytes)
    val isMounted: Boolean get() = dir != null && dir.isNotBlank()
    val usedFraction: Float get() = if (sizeBytes > 0) usedBytes.toFloat() / sizeBytes else 0f
}

data class Disk(
    val deviceFile: String,
    val model: String?,
    val vendor: String?,
    val serialNumber: String?,
    val capacityBytes: Long,
    val isRaid: Boolean,
)

data class SmartData(
    val deviceFile: String,
    val model: String?,
    val temperatureCelsius: Int?,
    val overallStatus: String?,
    val unavailable: Boolean = false,
)

data class SharedFolder(
    val uuid: String,
    val name: String,
    val description: String?,
    val absolutePath: String?,
    val device: String?,
    val status: String?,
)

data class StorageInfo(
    val fileSystems: List<FileSystem>,
    val disks: List<Disk>,
    val smarts: List<SmartData>,
    val sharedFolders: List<SharedFolder>,
    val errors: Map<String, String> = emptyMap(),
    val loadingSections: Set<String> = emptySet(),
)

class StorageRepository(private val sessions: SessionManager) {
    private suspend fun list(serverId: String, service: String): List<JsonObject> {
        val response = sessions.client(serverId).call(service, "getList", JsonObject(mapOf("start" to JsonPrimitive(0), "limit" to JsonPrimitive(-1)))) as? JsonObject
            ?: throw OmvConnectionException("Unexpected $service response")
        return response.arr("data")?.filterIsInstance<JsonObject>() ?: throw OmvConnectionException("Missing $service data")
    }
    suspend fun getFileSystems(serverId: String) = list(serverId, "FileSystemMgmt").map { o ->
        val size = o.lng("size") ?: 0L
        val available = o.lng("available") ?: 0L
        val used = (size - available).coerceIn(0L, size.coerceAtLeast(0))
        FileSystem(o.str("uuid").orEmpty(), o.str("devicefile") ?: o.str("deviceFile").orEmpty(), o.str("description"), o.str("dir") ?: o.str("mountpoint"), o.str("type"), size, used, o.lng("percentage")?.toInt() ?: 0, o.lng("usagewarnthreshold")?.toInt()?.coerceIn(1, 100) ?: 85)
    }
    suspend fun getDisks(serverId: String) = list(serverId, "DiskMgmt").map { o ->
        Disk(o.str("devicefile").orEmpty(), o.str("model"), o.str("vendor"), o.str("serialnumber"), o.lng("capacity") ?: o.lng("size") ?: 0L, o.str("israid")?.toBooleanStrictOrNull() ?: false)
    }
    suspend fun getSmart(serverId: String) = list(serverId, "Smart").map { o ->
        val device = o.str("devicefile").orEmpty()
        val status = o.str("overallstatus") ?: o.str("status")
        val unavailable = if (status.equals("BAD_STATUS", true)) {
            val report = try {
                (sessions.client(serverId).call("Smart", "getExtendedInformation", JsonObject(mapOf("devicefile" to JsonPrimitive(device)))) as? JsonPrimitive)?.contentOrNull.orEmpty()
            } catch (e: CancellationException) { throw e
            } catch (e: OmvRpcException) { ""
            } catch (e: OmvConnectionException) { "" }
            !hasExplicitSmartFailure(report)
        } else status.isNullOrBlank() || status.equals("UNKNOWN", true)
        SmartData(device, o.str("model"), if (unavailable) null else o.lng("temperature")?.toInt() ?: o.str("temperature")?.toIntOrNull(), if (unavailable) null else status, unavailable)
    }
    suspend fun getSharedFolders(serverId: String) = list(serverId, "ShareMgmt").map { o ->
        SharedFolder(o.str("uuid").orEmpty(), o.str("name").orEmpty(), o.str("description"), o.str("absdirpath"), o.str("device"), o.str("status"))
    }
}
