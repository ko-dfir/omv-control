package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BackgroundJobTest {
    private fun reply(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `drains output after the process stops and advances byte position`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(reply("""{"response":{"authenticated":true},"error":null}"""))
            server.enqueue(reply("""{"response":{"running":false,"pendingOutput":true,"pos":5,"output":"first"},"error":null}"""))
            server.enqueue(reply("""{"response":{"running":false,"pendingOutput":false,"pos":9,"output":"last"},"error":null}"""))
            val client = OmvRpcClient(listOf(server.url("/").toString()), "admin", "test", allowInsecureHttp = true)
            val output = StringBuilder()
            client.awaitJob(JsonPrimitive("/tmp/task")) { output.append(it) }
            assertEquals("firstlast", output.toString())
            server.takeRequest()
            server.takeRequest()
            val params = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["params"]!!.jsonObject
            assertEquals("5", params["pos"]!!.jsonPrimitive.content)
        } finally { server.shutdown() }
    }

    @Test
    fun `server task failure propagates to the caller`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(reply("""{"response":{"authenticated":true},"error":null}"""))
            server.enqueue(reply("""{"response":null,"error":{"code":100,"message":"Package database locked"}}"""))
            val client = OmvRpcClient(listOf(server.url("/").toString()), "admin", "test", allowInsecureHttp = true)
            try {
                client.awaitJob(JsonPrimitive("/tmp/task"))
                fail("Expected task failure")
            } catch (e: OmvRpcException) { assertTrue(e.message.orEmpty().contains("locked")) }
        } finally { server.shutdown() }
    }
}
