package com.omvcompanion.app.data.rpc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * JSON-RPC client for one openmediavault server (OMV 7+), talking to `<base>/rpc.php`.
 *
 * - Logs in lazily with `Session.login` and keeps the session cookie in memory.
 * - Transparently re-logs in once when the session expired (HTTP 401).
 * - Fails over to the next base URL (e.g. LAN -> VPN/public) when a server is unreachable.
 */
class OmvRpcClient(
    baseUrls: List<String>,
    private val username: String,
    private val password: String,
    pinnedCertSha256: String? = null,
    baseClient: OkHttpClient = OkHttpClient(),
    private val allowInsecureHttp: Boolean = false,
) {
    private val endpoints: MutableList<HttpUrl> = baseUrls
        .filter { it.isNotBlank() }
        .map { normalizeBaseUrl(it) }
        .distinct()
        .map { "$it/rpc.php".toHttpUrl() }
        .let { java.util.concurrent.CopyOnWriteArrayList(it) }
        .also { require(it.isNotEmpty()) { "No server address configured" } }

    @Volatile
    private var activeEndpoint = 0

    val activeBaseUrl: String get() = endpoints[activeEndpoint].toString().removeSuffix("/rpc.php")

    private val cookieJar = MemoryCookieJar()
    private val hostnameVerifier = PinningHostnameVerifier(pinnedCertSha256, baseClient.hostnameVerifier)
    private val http: OkHttpClient = run {
        val trustManager = PinningTrustManager(pinnedCertSha256)
        baseClient.newBuilder()
            .cookieJar(cookieJar)
            .sslSocketFactory(trustManager.sslContext().socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .followRedirects(false) // handled manually: OkHttp would turn the POST into a GET
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    private val sessionMutex = Mutex()
    private var loggedIn = false
    private var sessionGeneration = 0

    /** Role reported by OMV at login (`admin` or `user`), null before login. */
    @Volatile
    var role: String? = null
        private set

    /** Calls `service.method(params)`. Logs in first if needed; retries once after session expiry. */
    suspend fun call(
        service: String,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        options: JsonObject? = null,
    ): JsonElement {
        if (endpoints.first().host == "demo.invalid") return DemoRpc.call(service, method, params)
        val generation = ensureSession()
        val request = RpcRequest(service, method, params, options)
        val (status, envelope) = post(request)
        val error = envelope.error ?: return envelope.response ?: JsonNull
        if (!isSessionError(status, error)) throw error.toException(status)

        ensureSession(staleGeneration = generation)
        val (status2, envelope2) = post(request)
        envelope2.error?.let { throw it.toException(status2) }
        return envelope2.response ?: JsonNull
    }

    /** Forces a fresh login (used to validate credentials). */
    suspend fun login() {
        if (endpoints.first().host == "demo.invalid") { role = "admin"; loggedIn = true; return }
        sessionMutex.withLock {
            loggedIn = false
            doLogin()
        }
    }

    suspend fun logout() {
        sessionMutex.withLock {
            if (loggedIn) runCatching { post(RpcRequest("Session", "logout")) }
            loggedIn = false
            role = null
            cookieJar.clear()
        }
    }

    private suspend fun ensureSession(staleGeneration: Int? = null): Int = sessionMutex.withLock {
        if (loggedIn && sessionGeneration != staleGeneration) return@withLock sessionGeneration
        loggedIn = false
        doLogin()
        sessionGeneration
    }

    /** Must be called with [sessionMutex] held. */
    private suspend fun doLogin() {
        cookieJar.clear()
        val params = buildJsonObject {
            put("username", username)
            put("password", password)
        }
        val (status, envelope) = post(RpcRequest("Session", "login", params))
        envelope.error?.let { throw OmvAuthException(it.message.ifBlank { "Login failed (HTTP $status)" }) }
        val response = envelope.response as? JsonObject
        if (response?.str("status") == "challengeRequired") {
            if (response.obj("challenge")?.str("kind") == "totp") throw OmvTotpRequiredException()
            throw OmvAuthException("This server requires an authentication method not supported by this app.")
        }
        val authenticated = response?.get("authenticated")?.jsonPrimitive?.booleanOrNull ?: true
        if (!authenticated) throw OmvAuthException("Incorrect username or password.")
        role = response?.obj("permissions")?.str("role")
        loggedIn = true
        sessionGeneration++
    }

    suspend fun verifyTotp(code: String) = sessionMutex.withLock {
        require(code.matches(Regex("[0-9]{6}"))) { "Enter a six-digit authenticator code" }
        val (_, envelope) = post(RpcRequest("Session", "verify", buildJsonObject { put("code", code) }))
        envelope.error?.let { throw OmvAuthException(it.message) }
        val response = envelope.response as? JsonObject
        if (response?.get("authenticated")?.jsonPrimitive?.booleanOrNull != true && response?.str("status") != "authenticated") throw OmvAuthException("Verification failed. Try a fresh authenticator code.")
        role = response?.obj("permissions")?.str("role")
        loggedIn = true
        sessionGeneration++
    }

    private suspend fun post(body: RpcRequest): Pair<Int, RpcEnvelope> = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(RpcRequest.serializer(), body).toRequestBody(JSON_MEDIA)
        var lastError: IOException? = null
        for (offset in endpoints.indices) {
            val index = (activeEndpoint + offset) % endpoints.size
            try {
                val result = execute(index, payload)
                activeEndpoint = index
                com.omvcompanion.app.diagnostics.AppLog.write("RPC", "${body.service}.${body.method}: HTTP ${result.first}")
                return@withContext result
            } catch (e: IOException) {
                mapTlsError(e)?.let { throw it }
                if (!isUnreachable(e)) throw OmvConnectionException(describe(e), e)
                lastError = e
            }
        }
        throw OmvConnectionException(describe(lastError), lastError)
    }

    private fun execute(index: Int, payload: okhttp3.RequestBody): Pair<Int, RpcEnvelope> {
        repeat(MAX_REDIRECTS + 1) {
            requireEncryptedEndpoint(endpoints[index], allowInsecureHttp)
            val request = Request.Builder()
                .url(endpoints[index])
                .post(payload)
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val target = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: throw OmvConnectionException("Server sent an invalid redirect (HTTP ${response.code}).")
                    requireSafeRedirect(response.request.url, target)
                    endpoints[index] = target
                    return@repeat
                }
                val text = response.body.string()
                val envelope = runCatching { json.decodeFromString(RpcEnvelope.serializer(), text) }
                    .getOrElse {
                        throw OmvConnectionException(
                            "Unexpected response (HTTP ${response.code}). Is this an openmediavault server?",
                        )
                    }
                return response.code to envelope
            }
        }
        throw OmvConnectionException("Too many redirects.")
    }

    private fun mapTlsError(e: IOException): OmvException? {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is UntrustedServerCertificate) {
                return UntrustedCertificateException(cause.info, hostnameMismatch = false, previousSha256 = cause.previousSha256, cause = e)
            }
            cause = cause.cause
        }
        if (e is SSLPeerUnverifiedException) {
            hostnameVerifier.lastRejected?.let { return UntrustedCertificateException(it, hostnameMismatch = true, cause = e) }
        }
        return null
    }

    private fun isUnreachable(e: IOException): Boolean = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
        // Only connect timeouts: a read timeout may mean the request already ran on the server.
        is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> false
    }

    private fun describe(e: Throwable?): String = when (e) {
        is UnknownHostException -> "Unknown host: ${e.message}"
        is ConnectException -> "Cannot connect to the server (refused or unreachable)."
        is NoRouteToHostException -> "No route to host."
        is SocketTimeoutException -> "Connection timed out."
        is SSLException -> "Secure connection failed: ${e.message}"
        null -> "Network error"
        else -> e.message ?: e.toString()
    }

    private fun isSessionError(httpStatus: Int, error: RpcErrorBody): Boolean =
        httpStatus == 401 || error.code in 5001..5005 ||
            error.message.startsWith("Session ", ignoreCase = true)

    private fun RpcErrorBody.toException(httpStatus: Int): OmvException =
        if (isSessionError(httpStatus, this)) OmvAuthException(message.ifBlank { "Session expired" })
        else OmvRpcException(code, message.ifBlank { "RPC error $code (HTTP $httpStatus)" }, httpStatus, trace)

    companion object {
        const val USER_AGENT = "OMVCompanion/1.0 (Android)"
        private const val MAX_REDIRECTS = 3
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true // always send "params" and "options": null like the web UI
        }
    }
}

/**
 * Turns user input like `192.168.10.221`, `nas.local:8080/`, or a pasted web UI URL
 * (`https://nas/#/dashboard`) into a base URL without trailing slash. Defaults to https.
 */
fun normalizeBaseUrl(input: String): String {
    var s = input.trim()
    require(s.isNotEmpty()) { "Server address is empty" }
    if (!s.contains("://")) s = "https://$s"
    val url = s.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid server address: $input")
    val segments = url.pathSegments.filter { it.isNotEmpty() }.toMutableList()
    if (segments.lastOrNull()?.endsWith(".php") == true) segments.removeAt(segments.lastIndex)
    val builder = url.newBuilder().query(null).fragment(null).encodedPath("/")
    segments.forEach { builder.addPathSegment(it) }
    return builder.build().toString().trimEnd('/')
}

/** Per-client in-memory cookie store (holds the OMV session cookie). */
internal class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, ConcurrentHashMap<String, Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val hostCookies = store.getOrPut(url.host) { ConcurrentHashMap() }
        cookies.forEach { if (it.expiresAt < now) hostCookies.remove(it.name) else hostCookies[it.name] = it }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.values?.filter { it.expiresAt >= now && it.matches(url) }.orEmpty()
    }

    fun clear() = store.clear()
}

internal fun requireSafeRedirect(source: okhttp3.HttpUrl, target: okhttp3.HttpUrl) {
    if (source.host != target.host || source.scheme != "http" || target.scheme != "https" || target.username.isNotEmpty() || target.password.isNotEmpty()) {
        throw OmvConnectionException("Unsafe redirect blocked. Only an HTTP to HTTPS upgrade on the same host is allowed. Update the saved server address explicitly.")
    }
}

internal fun requireEncryptedEndpoint(url: HttpUrl, allowed: Boolean) {
    if (url.scheme == "http" && !allowed) throw OmvConnectionException("Unencrypted HTTP is disabled for this server. Use HTTPS or explicitly allow HTTP in the server form.")
}
