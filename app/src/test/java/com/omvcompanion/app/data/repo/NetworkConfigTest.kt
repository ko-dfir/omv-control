package com.omvcompanion.app.data.repo

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NetworkConfigTest {
    private val original = Json.parseToJsonElement("""{"uuid":"id","devicename":"eth0","type":"ethernet","method":"static","address":"192.168.1.10","netmask":"255.255.255.0","gateway":"192.168.1.1","method6":"static","address6":"fd00::10","netmask6":64,"routemetric6":100,"mtu":1500,"wol":true,"dnsnameservers":"192.168.1.1"}""").jsonObject

    @Test fun `DNS edit retains typed IPv6 and hardware configuration`() {
        val result = ethernetUpdate(original, mapOf("dnsnameservers" to "1.1.1.1,9.9.9.9"))
        listOf("method6", "address6", "netmask6", "routemetric6", "mtu", "wol").forEach { assertEquals(original[it], result[it]) }
        assertEquals("1.1.1.1,9.9.9.9", result["dnsnameservers"]!!.jsonPrimitive.content)
        assertFalse(result.containsKey("type"))
    }
    @Test fun `switching to DHCP clears the static IPv4 fields`() {
        val result = ethernetUpdate(original, mapOf("method" to "dhcp"))
        listOf("address", "netmask", "gateway").forEach { assertEquals("", result[it]!!.jsonPrimitive.content) }
        assertEquals(original["address6"], result["address6"])
    }
    @Test(expected = IllegalArgumentException::class)
    fun `invalid static address is rejected before RPC`() {
        ethernetUpdate(original, mapOf("address" to "999.168.1.10"))
    }
}
