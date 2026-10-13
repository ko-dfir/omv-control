package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.OmvRpcClient
import com.omvcompanion.app.data.rpc.arr
import com.omvcompanion.app.data.rpc.bool
import com.omvcompanion.app.data.rpc.str
import com.omvcompanion.app.data.rpc.awaitJob
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AptPackage(
    val name: String,
    val version: String,
    val repository: String,
)

data class PluginPackage(
    val name: String,
    val version: String,
    val description: String,
    val installed: Boolean,
)

interface SoftwareSource {
    suspend fun getUpdatablePackages(serverId: String): List<AptPackage>
    suspend fun getPlugins(serverId: String): List<PluginPackage>
    suspend fun installUpdates(serverId: String, packages: List<String>, onOutput: (String) -> Unit)
    suspend fun installPlugin(serverId: String, packageName: String, onOutput: (String) -> Unit)
    suspend fun checkUpdates(serverId: String, onOutput: (String) -> Unit)
}

class SoftwareRepository(private val sessions: SessionManager) : SoftwareSource {

    override suspend fun getUpdatablePackages(serverId: String): List<AptPackage> {
        val client = sessions.client(serverId)
        val params = JsonObject(mapOf(
            "start" to JsonPrimitive(0),
            "limit" to JsonPrimitive(-1)
        ))
        
        var response: JsonObject?
        try {
            response = client.call("Apt", "getUpgradedList", params) as? JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: com.omvcompanion.app.data.rpc.OmvRpcException) {
            if (!e.message.orEmpty().contains("not found", true) && !e.message.orEmpty().contains("does not exist", true) && !e.message.orEmpty().contains("not exist", true)) throw e
            // Fallback for OMV 7
            response = client.call("Apt", "getUpgraded", params) as? JsonObject
        }
        
        if (response == null) {
            throw Exception("Failed to cast response to JsonObject or method failed")
        }
        
        val array = response.arr("data") ?: emptyList()
        return array.filterIsInstance<JsonObject>().map { o ->
            AptPackage(
                name = o.str("name") ?: "",
                version = o.str("version") ?: "",
                repository = o.str("repository") ?: ""
            )
        }
    }

    override suspend fun installUpdates(serverId: String, packages: List<String>, onOutput: (String) -> Unit) {
        require(packages.isNotEmpty()) { "Select at least one update" }
        val client = sessions.client(serverId)
        val job = client.call("Apt", "install", kotlinx.serialization.json.buildJsonObject {
            put("packages", kotlinx.serialization.json.JsonArray(packages.map { JsonPrimitive(it) }))
        })
        client.awaitJob(job, onOutput)
    }

    override suspend fun checkUpdates(serverId: String, onOutput: (String) -> Unit) {
        val client = sessions.client(serverId)
        client.awaitJob(client.call("Apt", "update"), onOutput)
    }

    override suspend fun installPlugin(serverId: String, packageName: String, onOutput: (String) -> Unit) {
        require(packageName.matches(Regex("openmediavault-[a-z0-9][a-z0-9+.-]*"))) { "Invalid OMV plugin package" }
        val available = getPlugins(serverId).firstOrNull { it.name == packageName }
            ?: error("This plugin is no longer available. Refresh the catalogue.")
        require(!available.installed) { "This plugin is already installed" }
        val client = sessions.client(serverId)
        client.awaitJob(client.call("Plugin", "install", kotlinx.serialization.json.buildJsonObject {
            put("packages", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(packageName))))
        }), onOutput)
    }

    override suspend fun getPlugins(serverId: String): List<PluginPackage> {
        val client = sessions.client(serverId)
        val params = JsonObject(mapOf(
            "start" to JsonPrimitive(0),
            "limit" to JsonPrimitive(-1)
        ))
        
        val response = client.call("Plugin", "getList", params) as? JsonObject
        val array = response?.arr("data") ?: emptyList()
        return array.filterIsInstance<JsonObject>().map { o ->
            PluginPackage(
                name = o.str("name") ?: "",
                version = o.str("version") ?: "",
                description = o.str("abstract") ?: o.str("description") ?: "",
                installed = o.bool("installed") ?: false
            )
        }
    }
}
