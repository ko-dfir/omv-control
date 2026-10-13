package com.omvcompanion.app.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.ServerRepository
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.data.repo.SystemRepository
import com.omvcompanion.app.data.rpc.CertificateInfo
import com.omvcompanion.app.data.rpc.UntrustedCertificateException
import com.omvcompanion.app.data.rpc.normalizeBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.omvcompanion.app.data.rpc.OmvRpcClient
import com.omvcompanion.app.data.rpc.OmvTotpRequiredException

data class ServerEditState(
    val isNew: Boolean = true,
    val name: String = "",
    val url: String = "",
    val altUrl: String = "",
    val username: String = "admin",
    val password: String = "",
    val pinnedCertSha256: String? = null,
    val testing: Boolean = false,
    val testSuccess: String? = null,
    val error: String? = null,
    val untrustedCert: CertificateInfo? = null,
    val untrustedHostnameMismatch: Boolean = false,
    val previousSha256: String? = null,
    val saved: Boolean = false,
    val connectedId: String? = null,
    val totpRequired: Boolean = false,
    val totpCode: String = "",
    val credentialsLoaded: Boolean = false,
    val allowInsecureHttp: Boolean = false,
    val hasSavedPassword: Boolean = false,
)

class ServerEditViewModel(
    private val serverId: String?,
    private val repo: ServerRepository,
    private val sessions: SessionManager,
    private val system: SystemRepository,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerEditState(isNew = serverId == null))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()
    private var existing: ServerProfile? = null
    private var signingIn = false
    private var connectionAdopted = false
    fun consumeConnection() { _state.update { it.copy(connectedId = null) } }
    private var pendingClient: OmvRpcClient? = null
    private var pendingProfile: ServerProfile? = null
    fun onTotpCode(value: String) = _state.update { it.copy(totpCode = value.filter(Char::isDigit).take(6), error = null) }
    fun cancelTotp() {
        val client = pendingClient
        pendingClient = null; pendingProfile = null
        _state.update { it.copy(totpRequired = false, totpCode = "", error = null) }
        if (client != null) viewModelScope.launch { runCatching { client.logout() } }
    }
    fun verifyTotp() {
        if (_state.value.testing || (serverId != null && !_state.value.credentialsLoaded)) return
        val client = pendingClient ?: return
        val profile = pendingProfile ?: return
        _state.update { it.copy(testing = true, error = null) }
        viewModelScope.launch {
            try {
                client.verifyTotp(_state.value.totpCode)
                finishConnection(client, profile, signingIn)
                pendingClient = null; pendingProfile = null
                _state.update { it.copy(totpRequired = false, totpCode = "") }
                if (!signingIn) client.logout()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.update { it.copy(error = e.message, totpCode = "") }
            } finally { _state.update { it.copy(testing = false) } }
        }
    }
    private suspend fun finishConnection(client: OmvRpcClient, profile: ServerProfile, connect: Boolean) {
        if (connect) {
            val saved = profile
            repo.upsert(saved); repo.select(saved.id); sessions.adopt(saved, client)
            connectionAdopted = true
            _state.update { it.copy(testing = false, connectedId = saved.id) }
        } else {
            val info = system.fetch(client)
            _state.update { it.copy(testing = false, testSuccess = "Connected to ${info.hostname} (${info.version ?: "openmediavault"})", name = it.name.ifBlank { info.hostname }) }
        }
    }

    init {
        if (serverId != null) viewModelScope.launch(ioDispatcher) {
            try {
            val profile = repo.get(serverId) ?: return@launch
            existing = profile
            _state.update {
                it.copy(
                    name = profile.name,
                    url = profile.url,
                    altUrl = profile.altUrl.orEmpty(),
                    username = profile.username,
                    password = "",
                    hasSavedPassword = profile.encryptedPassword.isNotEmpty(),
                    allowInsecureHttp = profile.allowInsecureHttp,
                    pinnedCertSha256 = profile.pinnedCertSha256,
                    credentialsLoaded = true,
                )
            }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot read saved server settings") } }
        }
    }

    fun onName(v: String) = _state.update { it.copy(name = v) }
    fun onUrl(v: String) = _state.update { it.copy(url = v, testSuccess = null, error = null) }
    fun onAltUrl(v: String) = _state.update { it.copy(altUrl = v, testSuccess = null, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, testSuccess = null, error = null) }
    fun onAllowHttp(v: Boolean) = _state.update { it.copy(allowInsecureHttp = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, testSuccess = null, error = null) }
    fun forgetCertificate() = _state.update { it.copy(pinnedCertSha256 = null, testSuccess = null) }
    fun dismissCertificate() = _state.update { it.copy(untrustedCert = null) }

    fun trustCertificate() {
        val cert = _state.value.untrustedCert ?: return
        val changed = _state.value.previousSha256 != null
        _state.update { it.copy(pinnedCertSha256 = cert.sha256, untrustedCert = null, previousSha256 = null) }
        if (!changed) testConnection(signingIn)
    }

    fun signIn() = testConnection(true)

    fun testConnection(connect: Boolean = false) {
        if (_state.value.testing || (serverId != null && !_state.value.credentialsLoaded)) return
        signingIn = connect
        connectionAdopted = false
        _state.update { it.copy(testing = true, testSuccess = null, error = null) }
        viewModelScope.launch(ioDispatcher) {
            val profile = buildProfile() ?: run { _state.update { it.copy(testing = false) }; return@launch }
            var client: OmvRpcClient? = null
            try {
                val connection = sessions.create(profile, password = _state.value.password.takeIf { it.isNotEmpty() } ?: existing?.let { repo.password(it) }.orEmpty())
                client = connection
                connection.login()
                finishConnection(connection, profile, connect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UntrustedCertificateException) {
                _state.update {
                    it.copy(testing = false, untrustedCert = e.info, untrustedHostnameMismatch = e.hostnameMismatch, previousSha256 = e.previousSha256)
                }
            } catch (e: OmvTotpRequiredException) {
                pendingClient = client; pendingProfile = profile
                _state.update { it.copy(testing = false, totpRequired = true, totpCode = "") }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, error = e.message ?: e.toString()) }
            } finally {
                client?.takeIf { pendingClient !== it && !connectionAdopted }?.let { connection -> runCatching { connection.logout() } }
            }
        }
    }

    fun save() {
        if (serverId != null && !_state.value.credentialsLoaded) return
        viewModelScope.launch(ioDispatcher) {
            try {
                val profile = buildProfile() ?: return@launch
                repo.upsert(profile)
                sessions.forget(profile.id)
                _state.update { it.copy(saved = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot save server settings") } }
        }
    }

    /** Validates the form; returns null and sets [ServerEditState.error] if invalid. */
    private fun buildProfile(): ServerProfile? {
        val s = _state.value
        return try {
            val url = normalizeBaseUrl(s.url)
            val alt = s.altUrl.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) }
            require(s.allowInsecureHttp || listOfNotNull(url, alt).none { it.startsWith("http://") }) { "Explicitly allow unencrypted HTTP or use HTTPS." }
            require(s.username.isNotBlank()) { "Username is required" }
            val name = s.name.ifBlank { url.substringAfter("://").substringBefore('/').substringBefore(':') }
            val base = existing ?: ServerProfile(name = name, url = url, username = s.username)
            base.copy(
                name = name,
                url = url,
                altUrl = alt,
                username = s.username.trim(),
                encryptedPassword = if (s.password.isEmpty() && s.hasSavedPassword) existing?.encryptedPassword.orEmpty() else repo.encryptPassword(s.password),
                allowInsecureHttp = s.allowInsecureHttp,
                pinnedCertSha256 = s.pinnedCertSha256,
            )
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(error = e.message) }
            null
        }
    }
}
