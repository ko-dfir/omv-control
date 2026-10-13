package com.omvcompanion.app.ui.dashboard

import kotlinx.serialization.json.*

private fun JsonObject.value(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun readableContainerField(raw: String, ports: Boolean): String {
    if (raw.isBlank() || raw == "null") return if (ports) "No published ports" else "No folders attached"
    val clean = raw.replace(Regex("<[^>]*>"), "").replace("&gt;", ">").replace("&amp;", "&")
    val parsed = runCatching { Json.parseToJsonElement(clean) }.getOrNull()
    if (parsed == null) {
        return clean.lines().joinToString("\n") { line ->
            if (line.trim().startsWith("{")) readableContainerField("[${line.trim()}]", ports)
            else if (ports && line.contains("->")) "Server ${line.substringBefore("->")} → application ${line.substringAfter("->")}" else line
        }
    }
    if (parsed is JsonArray) return parsed.joinToString("\n") { element ->
        val o = element as? JsonObject ?: return@joinToString (element as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (ports) "Server port ${o.value("PublicPort").ifBlank { o.value("host_port").ifBlank { o.value("HostPort") } }.ifBlank { "not published" }} → application port ${o.value("PrivatePort").ifBlank { o.value("container_port").ifBlank { o.value("ContainerPort") } }} (${o.value("Type").ifBlank { o.value("protocol").ifBlank { "TCP" } }})"
        else "${o.value("Source")} → ${o.value("Destination")} · ${if (o["RW"]?.jsonPrimitive?.booleanOrNull == false) "Read only" else "Read and write"}"
    }.ifBlank { if (ports) "No published ports" else "No folders attached" }
    if (ports && parsed is JsonObject) return parsed.entries.joinToString("\n") { (port, bindings) ->
        val list = bindings as? JsonArray
        if (list.isNullOrEmpty()) "Application port $port · not published on the server"
        else list.joinToString("\n") { val o = it.jsonObject; "Server port ${o.value("HostPort")} (${when(o.value("HostIp")) { "0.0.0.0" -> "all IPv4 addresses"; "::" -> "all IPv6 addresses"; "" -> "all addresses"; else -> o.value("HostIp") }}) → application $port" }
    }.ifBlank { "No published ports" }
    return "Details not reported in a supported format"
}
internal fun readableInspection(raw: String): List<Pair<String, String>> {
    val start = raw.indexOf('[').takeIf { it >= 0 } ?: raw.indexOf('{')
    val end = maxOf(raw.lastIndexOf(']'), raw.lastIndexOf('}'))
    val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) }.getOrNull()
    val o = (if (root is JsonArray) root.firstOrNull() else root) as? JsonObject
        ?: return listOf("Configuration unavailable" to "The server returned an unreadable response. Try refreshing the container.")
    val config = o["Config"] as? JsonObject ?: JsonObject(emptyMap())
    val host = o["HostConfig"] as? JsonObject ?: JsonObject(emptyMap())
    val network = o["NetworkSettings"] as? JsonObject ?: JsonObject(emptyMap())
    val memory = host.value("Memory").toLongOrNull() ?: 0
    val cpus = host.value("NanoCpus").toDoubleOrNull() ?: 0.0
    val restart = (host["RestartPolicy"] as? JsonObject)?.value("Name").orEmpty()
    return listOf(
        "Application" to "${o.value("Name").removePrefix("/")}\nImage: ${config.value("Image")}\nStatus: ${(o["State"] as? JsonObject)?.value("Status")}\nCreated: ${o.value("Created")}",
        "Access and ports" to readableContainerField(network["Ports"]?.toString().orEmpty(), true),
        "Attached folders" to readableContainerField(o["Mounts"]?.toString().orEmpty(), false),
        "Resources" to "Memory limit: ${if (memory == 0L) "No fixed limit" else "${memory / 1048576} MB"}\nCPU limit: ${if (cpus == 0.0) "No fixed limit" else "${cpus / 1e9} cores"}",
        "Restart behavior" to when(restart) { "always" -> "Automatically restart whenever the container stops"; "unless-stopped" -> "Automatically restart unless you stop it yourself"; "on-failure" -> "Restart after an application error"; else -> "No automatic restart" },
        "Network" to ("Mode: ${host.value("NetworkMode")}\n" + ((network["Networks"] as? JsonObject)?.entries?.joinToString("\n") { (name, value) -> "$name: ${(value as? JsonObject)?.value("IPAddress").orEmpty().ifBlank { "No address assigned" }}" }.orEmpty()))
    )
}
