package com.omvcompanion.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class StorageViewModel(private val serverId: String, private val repo: StorageRepository) : ViewModel() {
    private var info = StorageInfo(emptyList(), emptyList(), emptyList(), emptyList())
    private val loaded = mutableSetOf<String>()
    private val _uiState = MutableStateFlow<UiState<StorageInfo>>(UiState.Success(info))
    val uiState = _uiState.asStateFlow()
    fun loadSection(section: String, force: Boolean = false) {
        if (section in info.loadingSections || (!force && section in loaded)) return
        info = info.copy(loadingSections = info.loadingSections + section, errors = info.errors - section)
        _uiState.value = UiState.Success(info)
        viewModelScope.launch {
            try {
                when (section) {
                    "Drives" -> { val data = repo.getDisks(serverId); info = info.copy(disks = data) }
                    "SMART" -> { val data = repo.getSmart(serverId); info = info.copy(smarts = data) }
                    "Filesystem" -> { val data = repo.getFileSystems(serverId); info = info.copy(fileSystems = data) }
                    "Shared folders" -> { val data = repo.getSharedFolders(serverId); info = info.copy(sharedFolders = data) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { info = info.copy(errors = info.errors + (section to (e.message ?: "Unable to load $section"))) }
            finally {
                loaded.add(section)
                info = info.copy(loadingSections = info.loadingSections - section)
                _uiState.value = UiState.Success(info)
            }
        }
    }
    fun retry() { loaded.clear(); loadSection("Drives", true) }
}
