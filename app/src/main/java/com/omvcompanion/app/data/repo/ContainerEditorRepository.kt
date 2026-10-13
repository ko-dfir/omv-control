package com.omvcompanion.app.data.repo

import android.content.Context
import com.omvcompanion.app.data.rpc.*
import kotlinx.serialization.json.*
import java.util.Base64

data class EnvironmentEntry(val key: String, val value: String)
data class PortMapping(val containerPort: String, val hostPort: String, val hostAddress: String = "", val protocol: String = "tcp")
data class ContainerMount(val source: String, val target: String, val type: String = "bind", val readOnly: Boolean = false)
data class ContainerDraft(val environment: List<EnvironmentEntry>, val ports: List<PortMapping>, val mounts: List<ContainerMount>, val restart: String, val retries: Int)
data class ContainerEditSnapshot(val inspection: JsonObject, val draft: ContainerDraft, val stack: String?, val networkMode: String, val unsupported: String?)
data class ContainerEditResult(val newId: String, val backupId: String, val backupName: String, val originalName: String, val wasRunning: Boolean, val oldRestart: String, val oldRetries: Int)

internal fun draftFromInspection(o: JsonObject): ContainerEditSnapshot {
    val config = o.obj("Config") ?: error("Container configuration missing")
    val host = o.obj("HostConfig") ?: error("Container host configuration missing")
    val env = config.arr("Env").orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.map { EnvironmentEntry(it.substringBefore('='), it.substringAfter('=', "")) }
    val ports = host.obj("PortBindings").orEmpty().flatMap { (key, values) ->
        (values as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { PortMapping(key.substringBefore('/'), it.str("HostPort").orEmpty(), it.str("HostIp").orEmpty(), key.substringAfter('/', "tcp")) }
    }
    val mounts = o.arr("Mounts").orEmpty().filterIsInstance<JsonObject>().filter { it.str("Type") in listOf("bind", "volume") }.map {
        ContainerMount(if (it.str("Type") == "volume") it.str("Name").orEmpty() else it.str("Source").orEmpty(), it.str("Destination").orEmpty(), it.str("Type").orEmpty(), it.bool("RW") == false)
    }
    val restart = host.obj("RestartPolicy")
    val labels = config.obj("Labels")
    val network = host.str("NetworkMode").orEmpty()
    val unsupported = when {
        labels?.str("com.docker.swarm.service.id") != null -> "This container is managed by Docker Swarm. Edit its service instead."
        network.startsWith("container:") -> "This container shares another container's network namespace. Edit its deployment definition instead."
        o.obj("State")?.str("Status") !in listOf("running", "exited", "created") -> "Stop or resume this container before editing its configuration."
        else -> null
    }
    return ContainerEditSnapshot(o, ContainerDraft(env, ports, mounts, restart?.str("Name") ?: "no", restart?.lng("MaximumRetryCount")?.toInt() ?: 0), labels?.str("com.docker.compose.project"), network, unsupported)
}
internal fun validateContainerDraft(draft: ContainerDraft) {
    require(draft.environment.all { it.key.isNotBlank() && '=' !in it.key && it.key.none { c -> c.code < 32 } && '\u0000' !in it.value }) { "Environment variable names cannot be empty or contain '=' or control characters." }
    require(draft.environment.map { it.key }.distinct().size == draft.environment.size) { "Environment variable names must be unique." }
    draft.ports.forEach {
        require(it.containerPort.toIntOrNull() in 1..65535 && (it.hostPort.isBlank() || it.hostPort.toIntOrNull() in 1..65535)) { "Ports must be between 1 and 65535. Leave host port blank for automatic assignment." }
        require(it.protocol in listOf("tcp", "udp", "sctp")) { "Invalid port protocol." }
        require(it.hostAddress.isBlank() || it.hostAddress.matches(Regex("[0-9a-fA-F:.]+"))) { "Use an IP address for the host binding." }
    }
    require(draft.ports.map { Triple(it.hostAddress, it.hostPort, it.protocol) }.filter { it.second.isNotBlank() }.distinct().size == draft.ports.count { it.hostPort.isNotBlank() }) { "Host ports cannot be bound twice on the same address and protocol." }
    draft.mounts.forEach {
        require(it.type in listOf("bind", "volume") && it.source.isNotBlank() && it.target.startsWith('/') && it.target != "/" && it.source.none { c -> c.code < 32 } && it.target.none { c -> c.code < 32 }) { "Mounts need a source and an absolute container path." }
        require(if (it.type == "bind") it.source.startsWith('/') else it.source.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*"))) { "Bind sources must be absolute server paths; volume sources must be Docker volume names." }
    }
    require(draft.mounts.map { it.target }.distinct().size == draft.mounts.size) { "Container mount destinations must be unique." }
    require(draft.restart in listOf("no", "always", "unless-stopped", "on-failure") && draft.retries >= 0) { "Invalid restart policy." }
}
internal fun draftJson(draft: ContainerDraft): JsonObject {
    validateContainerDraft(draft)
    return buildJsonObject {
        put("environment", JsonArray(draft.environment.map { JsonPrimitive("${it.key}=${it.value}") }))
        put("ports", JsonArray(draft.ports.map { buildJsonObject { put("container", it.containerPort); put("host", it.hostPort); put("address", it.hostAddress); put("protocol", it.protocol) } }))
        put("mounts", JsonArray(draft.mounts.map { buildJsonObject { put("source", it.source); put("target", it.target); put("type", it.type); put("readonly", it.readOnly) } }))
        put("restart", draft.restart); put("retries", draft.retries)
    }
}

class ContainerEditorRepository(context: Context, private val sessions: SessionManager, private val ssh: SshRunner) {
    private val recovery = context.getSharedPreferences("container_recovery", Context.MODE_PRIVATE)
    private val script = context.assets.open("docker_control.py").bufferedReader().use { it.readText() }
    suspend fun inspect(serverId: String, id: String): ContainerEditSnapshot {
        if (serverId == "demo") return draftFromInspection(Json.parseToJsonElement(DEMO_INSPECTION).jsonObject)
        var output = ""
        val client = sessions.client(serverId)
        kotlinx.coroutines.withTimeout(90_000) {
            client.awaitJob(client.call("Compose", "doContainerCommand", buildJsonObject { put("command", "inspect"); put("command2", ""); put("id", id) })) {
                require(output.length + it.length <= 1_048_576) { "This container's configuration is too large to edit on this device." }; output += it
            }
        }
        val start = output.indexOf('['); val end = output.lastIndexOf(']')
        require(start >= 0 && end > start) { "Docker did not return an inspectable container." }
        return draftFromInspection(Json.parseToJsonElement(output.substring(start, end + 1)).jsonArray.first().jsonObject)
    }
    suspend fun apply(serverId: String, snapshot: ContainerEditSnapshot, draft: ContainerDraft): ContainerEditResult {
        require(snapshot.unsupported == null) { snapshot.unsupported.orEmpty() }
        val result = remote(serverId, buildJsonObject { put("action", "recreate"); put("inspection", snapshot.inspection); put("draft", draftJson(draft)) })
        recovery.edit().putString(serverId, result.toString()).apply()
        return parseResult(result)
    }
    suspend fun rollback(serverId: String, result: ContainerEditResult) {
        remote(serverId, buildJsonObject { put("action", "rollback"); put("new_id", result.newId); put("backup_id", result.backupId); put("original_name", result.originalName); put("was_running", result.wasRunning); put("restart", result.oldRestart); put("retries", result.oldRetries) })
        recovery.edit().remove(serverId).apply()
    }
    fun lastResult(serverId: String): ContainerEditResult? = recovery.getString(serverId, null)?.let { runCatching { parseResult(Json.parseToJsonElement(it).jsonObject) }.getOrNull() }
    fun accept(serverId: String) { recovery.edit().remove(serverId).apply() }
    private fun parseResult(result: JsonObject) = ContainerEditResult(result.str("new_id").orEmpty(), result.str("backup_id").orEmpty(), result.str("backup_name").orEmpty(), result.str("original_name").orEmpty(), result.bool("was_running") == true, result.str("old_restart") ?: "no", result.lng("old_retries")?.toInt() ?: 0)
    private suspend fun remote(serverId: String, request: JsonObject): JsonObject {
        require(serverId != "demo") { "Container changes are disabled in demo mode." }
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray())
        val result = ssh.execute(serverId, "python3 -c \"import base64;exec(base64.b64decode('$encoded'))\"", request.toString(), 120_000)
        val response = runCatching { Json.parseToJsonElement(result.output).jsonObject }.getOrNull()
        require(result.exitCode == 0 && response?.bool("ok") == true) { response?.str("error") ?: "Docker editing requires Python 3 and an SSH account with permission to access /var/run/docker.sock. The operation could not complete; inspect your containers before retrying." }
        return response
    }
    companion object {
        private const val DEMO_INSPECTION = """{"Id":"demo-nextcloud","Name":"/Nextcloud","Image":"sha256:demo","State":{"Status":"running","Running":true},"Config":{"Image":"nextcloud:stable","Env":["TZ=Europe/Bucharest","POSTGRES_HOST=database"],"Labels":{"com.docker.compose.project":"cloud"},"Volumes":{"/var/www/html":{}}},"HostConfig":{"NetworkMode":"cloud","RestartPolicy":{"Name":"unless-stopped","MaximumRetryCount":0},"PortBindings":{"80/tcp":[{"HostPort":"8080","HostIp":"0.0.0.0"}]}},"Mounts":[{"Type":"bind","Source":"/srv/media/cloud","Destination":"/var/www/html","RW":true}],"NetworkSettings":{"Networks":{"cloud":{"Aliases":["Nextcloud"]}}}}"""
    }
}
