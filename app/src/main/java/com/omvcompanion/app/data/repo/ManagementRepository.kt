package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.*
import kotlinx.serialization.json.*

internal fun records(value: JsonElement): List<JsonObject> = when (value) {
    is JsonArray -> value.filterIsInstance<JsonObject>()
    is JsonObject -> (value["data"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: listOf(value)
    else -> emptyList()
}
internal fun stringItems(value: JsonElement?): List<String> = when (value) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.str("name") }
    is JsonPrimitive -> value.contentOrNull.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
    else -> emptyList()
}
internal fun listParams(sort: String = "name", start: Int = 0, limit: Int = -1) = buildJsonObject {
    put("start", start); put("limit", limit); put("sortfield", sort); put("sortdir", "ASC")
}
internal fun cronParams(original: JsonObject, fields: Map<String, String>, enabled: Boolean, email: Boolean): JsonObject = buildJsonObject {
    val defaults = mapOf("uuid" to "00000000-0000-0000-0000-000000000000", "execution" to "exactly", "type" to "userdefined", "username" to "root", "command" to "", "comment" to "")
    defaults.forEach { (k, v) -> put(k, fields[k] ?: original.str(k) ?: v) }
    for ((key, range) in mapOf("minute" to 0..59, "hour" to 0..23, "dayofmonth" to 1..31, "month" to 1..12, "dayofweek" to 1..7)) {
        val values = fields[key]?.split(',')?.map(String::trim) ?: stringItems(original[key]).ifEmpty { listOf("*") }
        require(values.isNotEmpty() && values.all { it == "*" || it.toIntOrNull() in range }) { "Invalid $key: use * or comma-separated numbers in ${range.first}–${range.last}." }
        put(key, JsonArray(values.map(::JsonPrimitive)))
    }
    for (key in listOf("everynminute", "everynhour", "everyndayofmonth")) put(key, fields[key]?.toBooleanStrictOrNull() ?: original.bool(key) ?: false)
    put("enable", enabled); put("sendemail", email)
    require((fields["command"] ?: original.str("command")).orEmpty().isNotBlank()) { "Enter a command." }
}

class ManagementRepository(private val sessions: SessionManager) {
    suspend fun call(id: String, service: String, method: String, params: JsonObject = JsonObject(emptyMap())) = sessions.client(id).call(service, method, params)
    suspend fun list(id: String, area: String, logId: String = "syslog", page: Int = 0): List<JsonObject> = when (area) {
        "Users" -> records(call(id, "UserMgmt", "getUserList", listParams()))
        "Groups" -> records(call(id, "UserMgmt", "getGroupList", listParams()))
        "Jobs" -> records(call(id, "Cron", "getList", buildJsonObject { listParams("comment").forEach { (k, v) -> put(k, v) }; put("type", JsonArray(listOf(JsonPrimitive("userdefined")))) }))
        "Processes" -> records((call(id, "System", "getTopInfo", buildJsonObject { put("format", "json") }) as? JsonObject)?.get("processes") ?: JsonArray(emptyList()))
        "Logs" -> records(call(id, "LogFile", "getList", buildJsonObject { put("id", logId); put("start", page * 100); put("limit", 100); put("sortfield", "rownum"); put("sortdir", "DESC") }))
        "Stacks" -> stacks(id)
        "Images" -> images(id)
        else -> emptyList()
    }
    private fun missingMethod(error: Exception): Boolean = error is OmvRpcException && (error.message.orEmpty().contains("not found", true) || error.message.orEmpty().contains("does not exist", true) || error.message.orEmpty().contains("not exist", true))
    private suspend fun stacks(id: String): List<JsonObject> = try { records(call(id, "Compose", "getFileList", listParams())) }
        catch (e: Exception) { if (!missingMethod(e)) throw e; records(call(id, "Compose", "getServicesList", listParams())) }
    suspend fun images(id: String): List<JsonObject> {
        val rows = try { records(call(id, "Compose", "getImages", listParams("repo"))) }
        catch (e: Exception) { if (!missingMethod(e)) throw e; records(call(id, "ImageCheck", "getUpdateList")) }
        val containers = try { records(call(id, "Compose", "getContainerList", listParams())) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        return rows.filter { it.str("repo") != "<none>" && it.str("tag") != "<none>" }.map { row -> buildJsonObject {
            row.forEach { (k,v) -> put(k,v) }
            val image = row.str("image") ?: row.str("repo")?.let { "$it:${row.str("tag") ?: "latest"}" } ?: row.str("name").orEmpty()
            put("image", image)
            val used = containers.filter { it.str("image") == image || it.str("image") in row.str("id").orEmpty().split("<br/>") }
            put("containers", JsonArray(used.map { JsonPrimitive(it.str("name").orEmpty()) }))
            put("persistent_data", used.any { !it.str("mounts").isNullOrBlank() })
            val networks = used.flatMap { it.str("network").orEmpty().split(',').filter(String::isNotBlank) }.toSet()
            put("network_neighbours", JsonArray(containers.filter { it !in used && it.str("network").orEmpty().split(',').any { network -> network in networks } }.map { JsonPrimitive(it.str("name").orEmpty()) }))
            put("update_available", imageUpdateAvailable(row))
            put("check_result", when(row.str("status")?.uppercase()) { "CURRENT" -> "Up to date"; "AVAILABLE" -> "New image available"; "NA" -> "Registry check unavailable"; "FAILED" -> "Registry check failed"; "TIMEOUT" -> "Registry check timed out"; else -> if (imageUpdateAvailable(row)) "New image available" else "See server result" })
            put("tag_policy", if (image.substringAfterLast(':') in listOf("latest", "stable", "main", "master", "nightly", "edge")) "Floating tag: releases may introduce breaking changes" else "Review release notes before updating")
            put("data_protection", "Back up persistent volumes and check services that depend on this image")
        } }
    }
    suspend fun detail(id: String, area: String, row: JsonObject): JsonObject = call(id,
        if (area == "Jobs") "Cron" else "UserMgmt",
        when (area) { "Jobs" -> "get"; "Groups" -> "getGroup"; else -> "getUser" },
        buildJsonObject { if (area == "Jobs") put("uuid", row.str("uuid").orEmpty()) else put("name", row.str("name").orEmpty()) }) as? JsonObject ?: error("Unable to read details")
    suspend fun save(id: String, area: String, params: JsonObject, output: (String) -> Unit) {
        val client = sessions.client(id)
        client.call(if (area == "Jobs") "Cron" else "UserMgmt", when (area) { "Jobs" -> "set"; "Groups" -> "setGroup"; else -> "setUser" }, params)
        output("Saved. Applying configuration…\n")
        try { client.awaitJob(client.call("Config", "applyChangesBg", buildJsonObject { put("modules", JsonArray(listOf(JsonPrimitive(if (area == "Jobs") "cron" else "users")))); put("force", false) }), output) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error("Saved on the server, but configuration could not be applied: ${e.message}. Review pending changes in OMV before retrying.") }
    }
    suspend fun runJob(id: String, row: JsonObject, output: (String) -> Unit) { val c = sessions.client(id); c.awaitJob(c.call("Cron", "execute", buildJsonObject { put("uuid", row.str("uuid").orEmpty()) }), output) }
    suspend fun stack(id: String, row: JsonObject, action: String, output: (String) -> Unit) {
        require(action in listOf("up -d", "stop", "restart"))
        val c = sessions.client(id)
        val method = if (row.str("uuid") != null) "doCommand" else "doServiceCommand"
        c.awaitJob(c.call("Compose", method, buildJsonObject {
            if (method == "doCommand") put("uuid", row.str("uuid").orEmpty())
            else {
                put("path", row.str("filepath") ?: row.str("path") ?: error("Stack path missing"))
                put("overridepath", row.str("overridepath").orEmpty()); put("envpath", row.str("envpath").orEmpty()); put("service", "")
            }
            put("command", action); put("command2", "")
        }), output)
    }
}

internal fun imageUpdateAvailable(row: JsonObject): Boolean = row.bool("update_available") == true || row.bool("updateAvailable") == true || row.str("status").equals("AVAILABLE", true)
