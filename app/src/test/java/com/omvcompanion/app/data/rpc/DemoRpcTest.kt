package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemoRpcTest {
    @Test fun demoDashboardWorksWithoutNetwork() = runTest {
        val client = OmvRpcClient(listOf("http://demo.invalid"), "demo", "")
        val info = client.call("System", "getInformation") as JsonObject
        assertEquals("demo-nas", info.str("hostname"))
    }
    @Test fun demoCannotMutateAnyServer() = runTest {
        val client = OmvRpcClient(listOf("http://demo.invalid"), "demo", "")
        for ((service, method) in listOf("System" to "shutdown", "UserMgmt" to "setUser", "Compose" to "doCommand", "Apt" to "install")) {
            try { client.call(service, method); fail("Demo action should be rejected") }
            catch (e: OmvException) { assertTrue(e.message.orEmpty().contains("demo mode")) }
        }
    }
}
