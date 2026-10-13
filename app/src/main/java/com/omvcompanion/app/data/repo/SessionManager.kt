package com.omvcompanion.app.data.repo

import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.rpc.OmvException
import com.omvcompanion.app.data.rpc.OmvRpcClient
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns one [OmvRpcClient] (and thus one OMV session) per saved server. */
class SessionManager(private val servers: ServerRepository, private val onLogout: (String) -> Unit = {}) {

    /** Shared base client so all servers reuse one connection pool / dispatcher. */
    private val baseHttp by lazy { OkHttpClient() }

    private val clients = ConcurrentHashMap<String, Pair<ServerProfile, OmvRpcClient>>()
    private val creation = Mutex()

    suspend fun client(serverId: String): OmvRpcClient {
        val profile = servers.get(serverId) ?: throw OmvException("Server not found")
        clients[serverId]?.let { (cachedProfile, client) -> if (cachedProfile.addresses == profile.addresses && cachedProfile.username == profile.username && cachedProfile.encryptedPassword == profile.encryptedPassword && cachedProfile.pinnedCertSha256 == profile.pinnedCertSha256 && cachedProfile.allowInsecureHttp == profile.allowInsecureHttp) return client }
        return creation.withLock {
            clients[serverId]?.let { (cached, client) -> if (cached.addresses == profile.addresses && cached.username == profile.username && cached.encryptedPassword == profile.encryptedPassword && cached.pinnedCertSha256 == profile.pinnedCertSha256 && cached.allowInsecureHttp == profile.allowInsecureHttp) return@withLock client }
            create(profile).also { clients[serverId] = profile to it }
        }
    }

    suspend fun create(profile: ServerProfile, password: String? = null): OmvRpcClient = withContext(Dispatchers.IO) {
        val resolvedPassword = password ?: servers.password(profile)
        if (profile.encryptedPassword.isNotEmpty() && resolvedPassword.isEmpty()) throw com.omvcompanion.app.data.local.CredentialUnavailableException()
        OmvRpcClient(
            baseUrls = profile.addresses,
            username = profile.username,
            password = resolvedPassword,
            pinnedCertSha256 = profile.pinnedCertSha256,
            baseClient = baseHttp,
            allowInsecureHttp = profile.allowInsecureHttp,
        )
    }

    suspend fun logout(serverId: String) {
        onLogout(serverId)
        clients.remove(serverId)?.second?.logout()
    }

    fun forget(serverId: String) {
        clients.remove(serverId)
    }
    fun adopt(profile: ServerProfile, client: OmvRpcClient) { clients[profile.id] = profile to client }
}
