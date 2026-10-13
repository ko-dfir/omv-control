package com.omvcompanion.app.data.repo

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream

data class SshResult(val output: String, val error: String, val exitCode: Int)

class SshRunner(private val servers: ServerRepository) {
    private data class Connection(val profile: com.omvcompanion.app.data.model.ServerProfile, val session: com.jcraft.jsch.Session)
    private val connections = java.util.concurrent.ConcurrentHashMap<String, Connection>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private suspend fun connection(id: String, profile: com.omvcompanion.app.data.model.ServerProfile): com.jcraft.jsch.Session {
        val mutex = locks.computeIfAbsent(id) { kotlinx.coroutines.sync.Mutex() }
        mutex.lock()
        try {
            connections[id]?.let { if (it.profile == profile && it.session.isConnected) return it.session; it.session.disconnect() }
            val session = com.omvcompanion.app.security.trustedSshSession(profile, servers)
            session.setServerAliveInterval(30_000)
            session.setServerAliveCountMax(2)
            connections[id] = Connection(profile, session)
            return session
        } finally { mutex.unlock() }
    }
    suspend fun session(id: String): com.jcraft.jsch.Session = withContext(Dispatchers.IO) {
        connection(id, servers.get(id) ?: error("Server not found"))
    }
    suspend fun adopt(id: String, profile: com.omvcompanion.app.data.model.ServerProfile, session: com.jcraft.jsch.Session) {
        val mutex = locks.computeIfAbsent(id) { kotlinx.coroutines.sync.Mutex() }
        mutex.lock()
        try { connections.put(id, Connection(profile, session))?.session?.let { if (it !== session) it.disconnect() } }
        finally { mutex.unlock() }
    }
    fun disconnect(id: String) { connections.remove(id)?.session?.disconnect() }
    suspend fun execute(serverId: String, command: String, stdin: String? = null, timeoutMs: Long = 90_000): SshResult = withContext(Dispatchers.IO) {
        val profile = servers.get(serverId) ?: error("Server not found")
        require(profile.sshUsername != null) { "Sign in to SSH first to use service controls and hardware details." }
        val session = connection(serverId, profile)
        var channel: ChannelExec? = null
        try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(stdin?.byteInputStream())
            val stderr = ByteArrayOutputStream()
            channel.setErrStream(stderr)
            val input = channel.inputStream
            val stdout = ByteArrayOutputStream()
            channel.connect(10000)
            withTimeout(timeoutMs) {
                val buffer = ByteArray(4096)
                while (isActive) {
                    while (input.available() > 0) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (stdout.size() < 1024 * 1024) stdout.write(buffer, 0, count)
                    }
                    if (channel.isClosed && input.available() == 0) break
                    delay(100)
                }
            }
            SshResult(stdout.toString("UTF-8"), stderr.toString("UTF-8"), channel.exitStatus)
        } finally { channel?.disconnect() }
    }
}

/** Only known OMV service names can become systemctl arguments. */
internal fun serviceUnits(name: String): List<String> = when (name.lowercase()) {
    "ssh", "sshd" -> listOf("ssh.service")
    "smb", "smbd", "samba" -> listOf("smbd.service", "nmbd.service")
    "nfs", "nfsd" -> listOf("nfs-server.service")
    "ftp", "proftpd" -> listOf("proftpd.service")
    "rsync", "rsyncd" -> listOf("rsync.service")
    "smart", "smartd", "smartmontools" -> listOf("smartmontools.service")
    "docker" -> listOf("docker.service")
    "snmp", "snmpd" -> listOf("snmpd.service")
    "tftp", "tftpd-hpa" -> listOf("tftpd-hpa.service")
    "avahi", "avahi-daemon" -> listOf("avahi-daemon.service")
    "clamav", "clamav-daemon" -> listOf("clamav-daemon.service")
    "nut", "ups" -> listOf("nut-server.service", "nut-monitor.service")
    else -> emptyList()
}
