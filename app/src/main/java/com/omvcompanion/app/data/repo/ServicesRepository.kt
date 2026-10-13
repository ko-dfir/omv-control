package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.OmvRpcClient
import com.omvcompanion.app.data.rpc.arr
import com.omvcompanion.app.data.rpc.bool
import com.omvcompanion.app.data.rpc.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SystemService(
    val name: String,
    val title: String,
    val enabled: Boolean,
    val running: Boolean,
)

class ServicesRepository(private val sessions: SessionManager, private val ssh: SshRunner) {
    suspend fun control(serverId: String, service: SystemService, action: String): String {
        require(action in setOf("start", "stop"))
        val units = serviceUnits(service.name)
        require(units.isNotEmpty()) { "Control is not supported for this service" }
        val result = ssh.execute(serverId, "${if (ssh.session(serverId).userName == "root") "" else "sudo -n "}systemctl $action ${units.joinToString(" ")}")
        if (result.error.contains("a password is required", true) || result.error.contains("not allowed to execute", true)) error("Administrative SSH access (root or sudo) is required. Allow passwordless sudo for systemctl $action ${units.joinToString(" ")} for this account, or use root.")
        check(result.exitCode == 0) { result.error.ifBlank { result.output.ifBlank { "Service command failed (exit ${result.exitCode}). Administrative SSH access (root or sudo) is required." } } }
        return "${service.title} ${if (action == "start") "started" else "stopped"}. Startup settings are unchanged."
    }

    suspend fun getServices(serverId: String): List<SystemService> {
        val client = sessions.client(serverId)
        val params = JsonObject(mapOf(
            "start" to JsonPrimitive(0),
            "limit" to JsonPrimitive(-1)
        ))
        
        val response = client.call("Services", "getStatus", params) as? JsonObject
        val array = response?.arr("data") ?: emptyList()
        return array.filterIsInstance<JsonObject>().map { o ->
            SystemService(
                name = o.str("name") ?: "",
                title = o.str("title") ?: o.str("name") ?: "",
                enabled = o.bool("enabled") ?: false,
                running = o.bool("running") ?: false
            )
        }
    }
}
