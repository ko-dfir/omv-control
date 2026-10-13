package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpConsentTest {
    @Test fun `HTTP login sends nothing without explicit consent`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val client = OmvRpcClient(listOf(server.url("/").toString()), "admin", "secret")
            try { client.login(); fail("Sent password without consent") }
            catch (e: OmvConnectionException) { assertTrue(e.message!!.contains("Unencrypted HTTP")) }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun `HTTP login works after explicit consent`() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"response":{"authenticated":true},"error":null}"""))
            OmvRpcClient(listOf(server.url("/").toString()), "admin", "secret", allowInsecureHttp = true).login()
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
