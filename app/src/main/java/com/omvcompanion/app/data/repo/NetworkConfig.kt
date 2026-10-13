package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.*

/** Preserve unedited IPv6, MTU, Wake-on-LAN and routing fields when updating IPv4/DNS. */
fun ethernetUpdate(original: JsonObject, changes: Map<String, String>): JsonObject {
    val fields = setOf("uuid", "devicename", "method", "address", "netmask", "gateway", "routemetric", "method6", "address6", "netmask6", "gateway6", "routemetric6", "dnsnameservers", "dnssearch", "mtu", "wol", "comment", "altmacaddress")
    val editable = setOf("method", "address", "netmask", "gateway", "dnsnameservers", "dnssearch")
    require(changes.keys.all { it in editable }) { "Unsupported interface field" }
    val params = original.filterKeys { it in fields }.toMutableMap()
    changes.forEach { (key, value) -> params[key] = JsonPrimitive(value.trim()) }
    fun field(key: String) = (params[key] as? JsonPrimitive)?.content.orEmpty()
    val method = field("method")
    require(method in setOf("dhcp", "static", "manual")) { "Invalid IPv4 method" }
    if (method == "static") {
        fun ipv4(value: String) = value.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
        require(ipv4(field("address")) && ipv4(field("netmask"))) { "Enter a valid IPv4 address and subnet mask" }
        require(field("gateway").isBlank() || ipv4(field("gateway"))) { "Enter a valid gateway" }
    } else {
        listOf("address", "netmask", "gateway").forEach { params[it] = JsonPrimitive("") }
    }
    return JsonObject(params)
}
