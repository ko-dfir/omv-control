package com.omvcompanion.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SoftwareInfo(val updates: List<AptPackage>, val plugins: List<PluginPackage>, val pluginError: String? = null)
data class SoftwareTask(val busy: Boolean = false, val title: String? = null, val output: String = "", val error: String? = null)
class SoftwareViewModel(private val serverId: String, private val repo: SoftwareSource) : ViewModel() {
    private val _uiState = MutableStateFlow<UiState<SoftwareInfo>>(UiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _task = MutableStateFlow(SoftwareTask())
    val task = _task.asStateFlow()
    init {
        _task.value = SoftwareTask(busy = true)
        viewModelScope.launch {
            try { load() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.value = UiState.Error(e.message ?: "Unable to load updates") }
            finally { _task.update { it.copy(busy = false) } }
        }
    }
    private suspend fun load() {
        val updates = repo.getUpdatablePackages(serverId)
        var pluginError: String? = null
        val plugins = try { repo.getPlugins(serverId) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { pluginError = e.message; emptyList() }
        _uiState.value = UiState.Success(SoftwareInfo(updates, plugins, pluginError))
    }
    fun retry() = runTask("Refreshing package index", "Package list refreshed") { output -> repo.checkUpdates(serverId, output) }
    fun install(packages: List<String>) = runTask("Installing ${packages.size} updates", "Updates installed", true) { output -> repo.installUpdates(serverId, packages, output) }
    fun installPlugin(name: String) = runTask("Installing $name", "$name installed", true) { output -> repo.installPlugin(serverId, name, output) }
    fun checkUpdates() = runTask("Checking for updates", "Update check complete") { output -> repo.checkUpdates(serverId, output) }
    private fun runTask(title: String, done: String, refreshAfterInstall: Boolean = false, operation: suspend ((String) -> Unit) -> Unit) {
        if (_task.value.busy) return
        _task.value = SoftwareTask(busy = true, title = title)
        viewModelScope.launch {
            var installed = false
            try {
                operation { output -> _task.update { it.copy(output = (it.output + output).takeLast(60000)) } }
                installed = refreshAfterInstall
                if (refreshAfterInstall) {
                    _task.update { it.copy(title = "Refreshing package index after installation") }
                    repo.checkUpdates(serverId) { output -> _task.update { it.copy(output = (it.output + output).takeLast(60000)) } }
                }
                _task.update { it.copy(title = done) }
                load()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _task.update { it.copy(error = if (installed) "$done, but refreshing the package list failed: ${e.message}. Use Refresh list to retry." else e.message, title = if (installed) done else "Task needs attention") } }
            finally { _task.update { it.copy(busy = false) } }
        }
    }
}
