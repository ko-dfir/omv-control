package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OmvRpcClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OmvRpcClient(listOf(server.url("/").toString()), "admin", "secret", allowInsecureHttp = true)

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private val loginOk =
        """{"response":{"authenticated":true,"username":"admin","permissions":{"role":"admin"}},"error":null}"""

    @Test
    fun `rejected authenticator code does not authenticate client`() = runTest {
        server.enqueue(json("""{"response":{"status":"challengeRequired","challenge":{"kind":"totp"}},"error":null}"""))
        server.enqueue(json("""{"response":null,"error":{"code":0,"message":"Invalid code"}}""", 400))
        val c = client()
        try { c.login(); fail("Expected challenge") } catch (_: OmvTotpRequiredException) { }
        try { c.verifyTotp("123456"); fail("Expected rejection") } catch (e: OmvAuthException) { assertEquals("Invalid code", e.message) }
        assertEquals(null, c.role)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `totp keeps challenge cookie and verifies before requesting data`() = runTest {
        server.enqueue(json("""{"response":{"status":"challengeRequired","challenge":{"kind":"totp"}},"error":null}""").addHeader("Set-Cookie", "X-OPENMEDIAVAULT-SESSIONID=challenge; path=/"))
        server.enqueue(json("""{"response":{"status":"authenticated","permissions":{"role":"admin"}},"error":null}"""))
        server.enqueue(json("""{"response":{"hostname":"nas"},"error":null}"""))
        val c = client()
        try { c.login(); fail("Expected challenge") } catch (_: OmvTotpRequiredException) { }
        assertEquals(1, server.requestCount)
        c.verifyTotp("123456")
        c.call("System", "getInformation")
        server.takeRequest()
        val verify = server.takeRequest()
        assertEquals("X-OPENMEDIAVAULT-SESSIONID=challenge", verify.getHeader("Cookie"))
        val body = verify.body.readUtf8()
        assertTrue(body.contains("\"method\":\"verify\""))
        assertTrue(body.contains("\"code\":\"123456\""))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `logs in then calls method with session cookie`() = runTest {
        server.enqueue(json(loginOk).addHeader("Set-Cookie", "X-OPENMEDIAVAULT-SESSIONID=abc; path=/"))
        server.enqueue(json("""{"response":{"hostname":"nas"},"error":null}"""))

        val c = client()
        val result = c.call("System", "getInformation")

        assertEquals("nas", result.jsonObject["hostname"]!!.jsonPrimitive.content)
        assertEquals("admin", c.role)
        val login = server.takeRequest().body.readUtf8()
        assertTrue(login.contains("\"service\":\"Session\"") && login.contains("\"method\":\"login\""))
        assertTrue(login.contains("\"password\":\"secret\""))
        val call = server.takeRequest()
        assertEquals("/rpc.php", call.path)
        assertEquals("X-OPENMEDIAVAULT-SESSIONID=abc", call.getHeader("Cookie"))
        assertTrue(call.body.readUtf8().contains("\"options\":null"))
    }

    @Test
    fun `re-logs in once after session expiry`() = runTest {
        server.enqueue(json(loginOk))
        server.enqueue(json("""{"response":{"a":1},"error":null}"""))
        // OMV answers an expired/unauthenticated session with HTTP 401 and code 0.
        server.enqueue(json("""{"response":null,"error":{"code":0,"message":"Session not authenticated."}}""", 401))
        server.enqueue(json(loginOk))
        server.enqueue(json("""{"response":{"a":2},"error":null}"""))

        val c = client()
        c.call("System", "getInformation")
        val second = c.call("System", "getInformation")

        assertEquals("2", second.jsonObject["a"]!!.jsonPrimitive.content)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `bad credentials raise auth exception`() = runTest {
        server.enqueue(json("""{"response":null,"error":{"code":0,"message":"Incorrect username or password."}}""", 400))
        try {
            client().call("System", "getInformation")
            fail("expected OmvAuthException")
        } catch (e: OmvAuthException) {
            assertEquals("Incorrect username or password.", e.message)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rpc errors are mapped`() = runTest {
        server.enqueue(json(loginOk))
        server.enqueue(json("""{"response":null,"error":{"code":1234,"message":"Boom","trace":"t"}}""", 500))
        try {
            client().call("Foo", "bar")
            fail("expected OmvRpcException")
        } catch (e: OmvRpcException) {
            assertEquals(1234, e.code)
            assertEquals("Boom", e.message)
            assertEquals(500, e.httpStatus)
        }
    }

    @Test
    fun `blocks redirects that do not upgrade to HTTPS`() = runTest {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", server.url("/omv/rpc.php").toString()))
        try { client().call("Config", "isDirty"); fail("Unsafe redirect followed") }
        catch (e: OmvConnectionException) { assertTrue(e.message!!.contains("Unsafe redirect")) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fails over to fallback address`() = runTest {
        server.enqueue(json(loginOk))
        server.enqueue(json("""{"response":{"ok":true},"error":null}"""))
        // Port 1 on localhost is closed -> ConnectException -> fallback
        val c = OmvRpcClient(listOf("http://127.0.0.1:1", server.url("/").toString()), "admin", "secret", allowInsecureHttp = true)

        c.call("System", "getInformation")

        assertTrue(c.activeBaseUrl.contains(server.port.toString()))
    }

    @Test
    fun `normalizes base urls`() {
        assertEquals("https://192.168.10.221", normalizeBaseUrl("192.168.10.221"))
        assertEquals("http://nas.local:8080", normalizeBaseUrl(" http://nas.local:8080/ "))
        assertEquals("https://nas.example.com/omv", normalizeBaseUrl("https://nas.example.com/omv/#/dashboard"))
        assertEquals("https://nas", normalizeBaseUrl("https://nas/rpc.php"))
        assertEquals("https://nas", normalizeBaseUrl("https://nas:443"))
    }
}
