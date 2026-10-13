package com.omvcompanion.app.security

import com.jcraft.jsch.*
import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.ServerRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.util.Base64

fun sshFingerprint(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

class SshTrustRequired(val host: String, val fingerprint: String, val changed: Boolean) : Exception(
    if (changed) "SSH identity changed for $host. Connection refused. Verify the new fingerprint on your server before replacing trust."
    else "Verify the SSH fingerprint for $host in the SSH screen before using SSH controls."
)

/** JSch checks the key during key exchange, before sending any password. */
suspend fun trustedSshSession(profile: ServerProfile, servers: ServerRepository): Session {
    require(profile.id != "demo") { "SSH is unavailable in demo mode." }
    val username = profile.sshUsername ?: error("Sign in to SSH first.")
    var last: Exception? = null
    for (host in profile.addresses.map { it.toHttpUrl().host }.distinct()) {
        val jsch = JSch()
        val keys = CheckingHostKeys(host, profile.sshFingerprint)
        jsch.setHostKeyRepository(keys)
        val session = jsch.getSession(username, host, profile.sshPort)
        session.setPassword(servers.sshPassword(profile).orEmpty())
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        try { session.connect(10_000); return session }
        catch (e: Exception) {
            session.disconnect()
            keys.rejected?.let { throw it }
            // Do not retry authentication failures or changed identities on other addresses.
            var cause: Throwable? = e
            var unreachable = false
            while (cause != null) {
                if (cause is java.net.ConnectException || cause is java.net.UnknownHostException || cause is java.net.NoRouteToHostException || cause is java.net.SocketTimeoutException) unreachable = true
                cause = cause.cause
            }
            if (!unreachable) throw e
            last = e
        }
    }
    throw last ?: IllegalStateException("No SSH address configured")
}

internal class CheckingHostKeys(private val host: String, private val expected: String?) : HostKeyRepository {
    var rejected: SshTrustRequired? = null
        private set
    override fun check(hostname: String, key: ByteArray): Int {
        val fingerprint = sshFingerprint(key)
        if (expected == fingerprint) return HostKeyRepository.OK
        rejected = SshTrustRequired(host, fingerprint, expected != null)
        return if (expected == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
    }
    override fun add(key: HostKey?, userInfo: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "OMV Control"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
