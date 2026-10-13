package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.rpc.str
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class LiveNetworkTest {
    @Test fun dhcpAddressComesFromRuntimeButEditingUsesSavedIdentity() {
        val configured = buildJsonObject { put("devicename","eth0"); put("uuid","saved-id"); put("method","dhcp"); put("address","") }
        val runtime = buildJsonObject { put("devicename","eth0"); put("uuid","runtime-id"); put("address","192.168.1.22"); put("gateway","192.168.1.1") }
        val result = mergeLiveInterfaces(listOf(configured),listOf(runtime)).single()
        assertEquals("192.168.1.22",result.str("address"))
        assertEquals("saved-id",result.str("uuid"))
        assertEquals("dhcp",result.str("method"))
        assertTrue(result["_runtime_available"]!!.jsonPrimitive.boolean)
    }
    @Test fun missingRuntimeIsExplicitAndDoesNotInventAnAddress() {
        val result=mergeLiveInterfaces(listOf(buildJsonObject { put("devicename","eth0"); put("address","") }),emptyList()).single()
        assertFalse(result["_runtime_available"]!!.jsonPrimitive.boolean)
        assertEquals("",result.str("address"))
    }
}
