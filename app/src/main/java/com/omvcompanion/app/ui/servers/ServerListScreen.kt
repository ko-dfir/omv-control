package com.omvcompanion.app.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.omvcompanion.app.data.model.ServerProfile
import com.omvcompanion.app.data.repo.ServerRepository
import com.omvcompanion.app.data.repo.SessionManager
import com.omvcompanion.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class ServerListViewModel(
    private val repo: ServerRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    val readError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** `null` while loading. */
    val servers: StateFlow<List<ServerProfile>?> =
        repo.servers.catch { readError.value = it.message ?: "Cannot read saved servers" }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { sessions.logout(id) }
            sessions.forget(id)
            try { repo.delete(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { readError.value = e.message ?: "Cannot update saved servers" }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val vm = appViewModel { ServerListViewModel(it.serverRepository, it.sessionManager) }
    val servers by vm.servers.collectAsStateWithLifecycle()
    val readError by vm.readError.collectAsStateWithLifecycle()
    if (readError != null) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(readError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        return
    }
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("OMV Control") }) },
        floatingActionButton = {
            if (!servers.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add server") },
                )
            }
        },
    ) { padding ->
        val list = servers
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> EmptyState(Modifier.fillMaxSize().padding(padding), onAdd)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Your servers", style = MaterialTheme.typography.headlineLarge)
                        Text("Select a server to monitor and manage", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(list, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onOpen = { onOpen(server.id) },
                        onEdit = { onEdit(server.id) },
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove server?") },
            text = { Text("\"${server.name}\" and its saved credentials will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(server.id); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerCard(server: ServerProfile, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    server.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(server.username)
                        if (!server.altUrl.isNullOrBlank()) append(" · fallback address")
                        if (server.pinnedCertSha256 != null) append(" · pinned cert")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("No servers yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add your openmediavault server to monitor and manage it from your phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add server")
        }
    }
}
