package com.omvcompanion.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.repo.ServicesRepository
import com.omvcompanion.app.data.repo.SystemService
import com.omvcompanion.app.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

class ServicesViewModel(
    private val serverId: String,
    private val servicesRepo: ServicesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<SystemService>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<SystemService>>> = _uiState.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun control(service: SystemService, action: String) {
        if (_busy.value) return
        _busy.value = true; _message.value = null
        viewModelScope.launch {
            try {
                _message.value = servicesRepo.control(serverId, service, action)
                _uiState.value = UiState.Success(servicesRepo.getServices(serverId))
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _message.value = e.message
            } finally { _busy.value = false }
        }
    }

    private var pollJob: Job? = null

    init {
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val services = servicesRepo.getServices(serverId)
                    _uiState.value = UiState.Success(services)
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (_uiState.value !is UiState.Success) {
                        _uiState.value = UiState.Error(e.message ?: "Failed to fetch services")
                    }
                }
                delay(15_000)
            }
        }
    }

    fun retry() {
        _uiState.value = UiState.Loading
        startPolling()
    }
}
