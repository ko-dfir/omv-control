package com.omvcompanion.app.ui.dashboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omvcompanion.app.data.repo.*
import com.omvcompanion.app.ui.appViewModel
import com.omvcompanion.app.ui.components.*
import com.omvcompanion.app.ui.formatBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.DateFormat
import java.util.Date

data class FileBrowserState(val shares: List<SharedFolder> = emptyList(), val share: SharedFolder? = null, val path: String = "",
    val files: List<RemoteFile> = emptyList(), val loading: Boolean = true, val busy: Boolean = false, val error: String? = null,
    val message: String? = null, val transferred: Long = 0, val total: Long = 0, val operation: String? = null)

class FileBrowserViewModel(private val id: String, private val storage: StorageRepository, private val files: FileBrowserRepository) : ViewModel() {
    private val _state = MutableStateFlow(FileBrowserState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    init { refresh() }
    fun refresh() {
        if (_state.value.busy || _state.value.loading && work?.isActive == true) return
        val snapshot = _state.value
        work = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (snapshot.share == null) _state.update { it.copy(shares = storage.getSharedFolders(id), files = emptyList(), loading = false) }
                else { val entries = files.list(id, snapshot.share, snapshot.path); _state.update { it.copy(files = entries, loading = false) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _state.update { it.copy(loading = false, files = emptyList(), error = fileErrorMessage(e, "read files")) } }
        }
    }
    fun open(share: SharedFolder?, path: String = "") {
        if (_state.value.busy || _state.value.loading) return
        _state.update { it.copy(share = share, path = path, files = emptyList(), error = null, message = null) }; refresh()
    }
    fun cancel() { work?.cancel() }
    private fun perform(label: String, action: suspend (SharedFolder, String) -> Unit) {
        if (_state.value.busy || _state.value.loading) return
        val snapshot = _state.value
        val share = snapshot.share ?: return
        _state.update { it.copy(busy = true, error = null, message = null, operation = label, transferred = 0, total = 0) }
        work = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action(share, snapshot.path); _state.update { it.copy(message = "$label completed") } }
            catch (e: CancellationException) { _state.update { it.copy(message = if (label == "Download") e.message ?: "Download cancelled" else "$label cancelled.") }; throw e }
            catch (e: Exception) { _state.update { it.copy(error = fileErrorMessage(e, label.lowercase())) } }
            finally { _state.update { it.copy(busy = false, operation = null) }; refresh() }
        }
    }
    fun mkdir(name: String) = perform("Create folder") { share, path -> files.mkdir(id, share, path, name) }
    fun rename(entry: RemoteFile, name: String) = perform("Rename") { share, path -> files.rename(id, share, path, entry, name) }
    fun delete(entry: RemoteFile) = perform("Delete") { share, path -> files.delete(id, share, path, entry) }
    fun upload(context: Context, uri: Uri, name: String, size: Long) = perform("Upload") { share, path ->
        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> files.upload(id, share, path, name, size, input) { done, total -> _state.update { it.copy(transferred = done, total = total) } } } ?: error("Could not open the selected file.") }
    }
    fun downloadFolder(context: Context, uri: Uri) = perform("Download") { share, path ->
        cleanDownload(context, uri, true) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    files.downloadDirectory(id, share, path, null, output) { done, total -> _state.update { it.copy(transferred = done, total = total) } }
                } ?: error("Could not write to the selected destination.")
            }
        }
    }
    fun download(context: Context, uri: Uri, entry: RemoteFile) = perform("Download") { share, path ->
        cleanDownload(context, uri, entry.directory) {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    val progress: (Long, Long) -> Unit = { done, total -> _state.update { it.copy(transferred = done, total = total) } }
                    if (entry.directory) files.downloadDirectory(id, share, path, entry, output, progress)
                    else files.download(id, share, path, entry, output, progress)
                } ?: error("Could not write to the selected destination.")
            }
        }
    }
}

private suspend fun cleanDownload(context: Context, uri: Uri, archive: Boolean, action: suspend () -> Unit) =
    cleanupIncompleteDownload(archive, action) { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }

@Composable fun FileBrowserScreen(serverId: String, padding: PaddingValues, onSsh: () -> Unit, initialShare: String? = null) {
    val vm = appViewModel(key = "files-$serverId") { FileBrowserViewModel(serverId, it.storageRepository, it.fileBrowserRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var shareOpened by remember(initialShare) { mutableStateOf(false) }
    LaunchedEffect(initialShare, state.shares, state.loading, state.busy) { if (initialShare != null && !shareOpened && !state.loading && !state.busy) state.shares.firstOrNull { it.uuid == initialShare }?.let { shareOpened = true; vm.open(it) } }
    val context = LocalContext.current.applicationContext
    var search by rememberSaveable(serverId) { mutableStateOf("") }
    var sort by rememberSaveable(serverId) { mutableStateOf("Name") }
    var hidden by rememberSaveable(serverId) { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var name by remember { mutableStateOf("") }
    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var inputSize by remember { mutableLongStateOf(0) }
    var download by remember { mutableStateOf<RemoteFile?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        download?.let { if (uri != null) vm.download(context, uri, it) }; download = null
    }
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { val entry = download; if (entry != null) vm.download(context, uri, entry) else vm.downloadFolder(context, uri) }
        download = null
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inputUri = uri; name = "uploaded-file"; inputSize = -1
            runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; inputSize = if (cursor.isNull(1)) -1 else cursor.getLong(1) }
            } }
            dialog = "Upload file"
        }
    }
    val enabled = !state.busy && !state.loading && download == null
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(state.share?.uuid, state.path) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Files", style = MaterialTheme.typography.headlineMedium)
                    Text(state.share?.name ?: "Choose an OMV shared folder", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(vm::refresh, enabled = enabled) { Icon(Icons.Filled.Refresh, "Refresh files") }
            }
            if (state.share == null) {
                Text("Browse and transfer files using your SSH account's permissions. Configure SSH and verify the server fingerprint before connecting.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search shared folders") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton({ search = ""; vm.open(null) }, enabled = enabled) { Text("Shared folders") }
                    TextButton({ search = ""; vm.open(state.share) }, enabled = enabled) { Text(state.share!!.name) }
                    val parts = state.path.split('/').filter(String::isNotBlank)
                    parts.forEachIndexed { index, part -> TextButton({ search = ""; vm.open(state.share, parts.take(index + 1).joinToString("/")) }, enabled = enabled) { Text("/ $part") } }
                }
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search this folder") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Icons.Filled.Close, "Clear search") } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Folder downloads include hidden files and empty folders as a ZIP. Symbolic links and special files are excluded.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ selected = null; name = ""; dialog = "New folder" }, enabled = enabled && serverId != "demo") { Icon(Icons.Filled.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("New folder") }
                    OutlinedButton({ pick.launch(arrayOf("*/*")) }, enabled = enabled && serverId != "demo") { Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Upload") }
                    OutlinedButton({ download = null; saveFolder.launch((state.path.substringAfterLast('/').ifBlank { state.share!!.name }) + ".zip") }, enabled = enabled && serverId != "demo") { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Download folder (ZIP)") }
                    listOf("Name", "Newest", "Largest").forEach { label -> FilterChip(sort == label, { sort = label }, label = { Text(label) }) }
                    FilterChip(hidden, { hidden = !hidden }, label = { Text("Hidden files") })
                }
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { item { EmptyPanel("File request failed", it); Row { TextButton(vm::refresh, enabled = !state.busy) { Text("Retry") }; TextButton(onSsh) { Text("Configure SSH") } } } }
            state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (state.busy) item {
                ControlPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
                    Text(state.operation.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    if (state.total > 0) LinearProgressIndicator(progress = { (state.transferred.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${formatBytes(state.transferred)}${if (state.total > 0) " / ${formatBytes(state.total)}" else ""}")
                    TextButton(vm::cancel) { Text("Cancel transfer") }
                } }
            }
            if (state.share == null) {
                if (!state.loading && state.error == null && state.shares.isEmpty()) item { EmptyPanel("No shared folders", "Create a shared folder in OMV to browse it here.") }
                val shares = state.shares.filter { "${it.name} ${it.description.orEmpty()}".contains(search, true) }.sortedBy { it.name.lowercase() }
                if (state.shares.isNotEmpty() && shares.isEmpty()) item { EmptyPanel("No matching shared folders", "Try another name or clear the search.") }
                items(shares, key = { it.uuid }) { share -> ControlPanel(Modifier.fillMaxWidth().clickable(enabled = enabled) { search = ""; vm.open(share) }) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.FolderShared, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) { Text(share.name, style = MaterialTheme.typography.titleLarge); Text(share.description?.takeIf(String::isNotBlank) ?: share.absolutePath.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Filled.ChevronRight, "Open ${share.name}") }
                } }
            } else {
                val visible = state.files.filter { (hidden || !it.name.startsWith('.')) && it.name.contains(search, true) }.sortedWith(compareBy<RemoteFile> { !it.directory }.thenComparator { a, b -> when (sort) { "Newest" -> b.modified.compareTo(a.modified); "Largest" -> b.size.compareTo(a.size); else -> a.name.lowercase().compareTo(b.name.lowercase()) } })
                if (!state.loading && state.error == null && visible.isEmpty()) item { EmptyPanel(if (search.isBlank()) "This folder is empty" else "No matching files", if (search.isBlank()) "Upload a file or create a folder to get started." else "Try another search or show hidden files.") }
                items(visible, key = { it.name }) { entry ->
                    var menu by remember { mutableStateOf(false) }
                    ControlPanel(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (entry.symbolicLink) Icons.Filled.Link else if (entry.directory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).clickable(enabled = enabled && !entry.symbolicLink) { if (entry.directory) { search = ""; vm.open(state.share, listOf(state.path, entry.name).filter(String::isNotBlank).joinToString("/")) } else { selected = entry; dialog = "File details" } }.padding(horizontal = 16.dp)) {
                            Text(entry.name, style = MaterialTheme.typography.titleMedium)
                            Text("${if (entry.symbolicLink) "Symbolic link · navigation disabled" else if (entry.directory) "Folder" else formatBytes(entry.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modified))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box { IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, "Actions for ${entry.name}") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Details") }, onClick = { selected = entry; dialog = "File details"; menu = false })
                                if (!entry.symbolicLink && serverId != "demo") {
                                    DropdownMenuItem(text = { Text(if (entry.directory) "Download folder (ZIP)" else "Download") }, onClick = { menu = false; download = entry; if (entry.directory) saveFolder.launch(entry.name + ".zip") else save.launch(entry.name) })
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { selected = entry; name = entry.name; dialog = "Rename"; menu = false })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { selected = entry; dialog = "Delete"; menu = false })
                                }
                            }
                        }
                    } }
                }
            }
        }
    }
    dialog?.let { kind ->
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(kind) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (kind) {
                    "Delete" -> Text("Delete ${selected?.name}? ${if (selected?.directory == true) "Only empty folders can be deleted." else "This permanently removes the file from the server."}")
                    "File details" -> { Text(selected?.name.orEmpty()); Text("Size: ${formatBytes(selected?.size)}"); Text("Permissions: ${selected?.permissions}"); Text("Modified: ${DateFormat.getDateTimeInstance().format(Date(selected?.modified ?: 0))}") }
                    else -> { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true); Text(if (kind == "Upload file") "Upload into ${state.share?.name}/${state.path}. Existing files are never overwritten." else "Names must not contain slashes.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { TextButton({
            when (kind) { "New folder" -> vm.mkdir(name); "Rename" -> selected?.let { vm.rename(it, name) }; "Delete" -> selected?.let(vm::delete); "Upload file" -> inputUri?.let { vm.upload(context, it, name, inputSize) } }
            dialog = null
        }, enabled = kind in listOf("Delete", "File details") || runCatching { safeFileName(name) }.isSuccess) { Text(when (kind) { "File details" -> "Close"; "Delete" -> "Delete"; "Upload file" -> "Upload"; else -> "Save" }) } },
            dismissButton = { if (kind != "File details") TextButton({ dialog = null }) { Text("Cancel") } })
    }
}
