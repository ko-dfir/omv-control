package com.omvcompanion.app.ui.dashboard

import java.io.IOException
import kotlinx.coroutines.*

internal suspend fun cleanupIncompleteDownload(archive: Boolean, action: suspend () -> Unit, remove: () -> Boolean) {
    try { action() }
    catch (e: Exception) {
        val removed = withContext(NonCancellable + Dispatchers.IO) { runCatching(remove).getOrDefault(false) }
        val item = if (archive) "ZIP" else "file"
        val detail = if (removed) "the incomplete $item was removed." else "the document provider could not remove the incomplete $item."
        if (e is CancellationException) throw CancellationException("Download cancelled; $detail", e)
        throw IOException("Download failed; $detail ${e.message.orEmpty()}", e)
    }
}
