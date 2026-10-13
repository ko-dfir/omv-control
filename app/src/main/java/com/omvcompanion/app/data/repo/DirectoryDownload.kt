package com.omvcompanion.app.data.repo

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ArchiveItem(val name: String, val directory: Boolean, val regular: Boolean, val size: Long)
internal interface DirectorySource {
    suspend fun children(path: String): List<ArchiveItem>
    suspend fun copy(path: String, output: OutputStream, bytes: (Long) -> Unit)
}

/** Streams a ZIP locally; keeps empty/hidden folders and excludes links/special files. */
internal suspend fun downloadDirectoryZip(source: DirectorySource, name: String, output: OutputStream,
    progress: (Long, Long) -> Unit) {
    data class Entry(val path: String, val directory: Boolean, val size: Long)
    val entries = mutableListOf(Entry("", true, 0))
    suspend fun scan(path: String, depth: Int) {
        require(depth <= 128) { "Folder nesting exceeds the download limit." }
        currentCoroutineContext().ensureActive()
        val children = source.children(path)
        require(children.map { it.name }.distinct().size == children.size) { "Duplicate folder entries reported by server." }
        for (item in children) {
            currentCoroutineContext().ensureActive()
            safeFileName(item.name)
            if (!item.directory && !item.regular) continue
            val child = listOf(path, item.name).filter(String::isNotEmpty).joinToString("/")
            require(entries.size < 100_000) { "Folder exceeds the 100,000-entry download limit." }
            entries.add(Entry(child, item.directory, item.size.coerceAtLeast(0)))
            if (item.directory) scan(child, depth + 1)
        }
    }
    scan("", 0)
    val total = entries.filterNot { it.directory }.sumOf { it.size }
    var transferred = 0L
    progress(0, total)
    val root = safeFileName(name)
    // The caller owns the destination stream; only finish the archive here.
    val zip = ZipOutputStream(output).apply { setLevel(1) }
    try {
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val path = if (entry.path.isEmpty()) root else "$root/${entry.path}"
            zip.putNextEntry(ZipEntry(path + if (entry.directory) "/" else ""))
            if (!entry.directory) source.copy(entry.path, zip) { bytes ->
                transferred += bytes; progress(transferred, total)
            }
            zip.closeEntry()
        }
        zip.finish()
    } finally { zip.close() }
}
