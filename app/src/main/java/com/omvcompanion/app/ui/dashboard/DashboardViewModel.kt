package com.omvcompanion.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.repo.ServerRepository
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.data.repo.SystemInfo
import com.omvcompanion.app.data.repo.SystemRepository
import com.omvcompanion.app.data.rpc.OmvAuthException
import com.omvcompanion.app.data.rpc.UntrustedCertificateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardState(
    val serverName: String = "",
    val info: SystemInfo? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Auth/certificate problems stop polling and need user action (edit server). */
    val needsAttention: Boolean = false,
    val lastUpdated: Long? = null,
)

class DashboardViewModel(
    private val serverId: String,
    private val servers: ServerRepository,
    private val sessions: SessionManager,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Starts auto-refresh (called when the screen becomes visible). */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            try { servers.get(serverId)?.let { p -> _state.update { it.copy(serverName = p.name) } } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot read saved server settings", needsAttention = true) }; return@launch }
            while (isActive) {
                if (!load()) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        if (pollJob?.isActive == true) {
            viewModelScope.launch { load() }
        } else {
            start()
        }
    }

    fun logout(onDone: () -> Unit) {
        stop()
        viewModelScope.launch {
            runCatching { sessions.logout(serverId) }
            onDone()
        }
    }

    fun rebootSystem() {
        viewModelScope.launch {
            try {
                system.reboot(serverId)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Reboot failed: ${e.message}") }
            }
        }
    }

    fun shutdownSystem() {
        viewModelScope.launch {
            try {
                system.shutdown(serverId)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Shutdown failed: ${e.message}") }
            }
        }
    }

    /** @return false if polling should stop (needs user action). */
    private suspend fun load(): Boolean = try {
        val info = system.getInformation(serverId)
        _state.update {
            it.copy(
                info = info, loading = false, refreshing = false, error = null,
                needsAttention = false, lastUpdated = System.currentTimeMillis(),
            )
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val attention = e is OmvAuthException || e is UntrustedCertificateException
        _state.update {
            it.copy(loading = false, refreshing = false, error = e.message ?: e.toString(), needsAttention = attention)
        }
        !attention
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}
