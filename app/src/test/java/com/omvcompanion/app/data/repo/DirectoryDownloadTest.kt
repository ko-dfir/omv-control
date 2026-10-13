package com.omvcompanion.app.data.repo

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryDownloadTest {
    @Test fun `archive preserves nested unicode hidden and empty entries and byte progress`() = runTest {
        val source = object : DirectorySource {
            override suspend fun children(path: String) = when (path) {
                "" -> listOf(ArchiveItem("empty", true, false, 0), ArchiveItem("nested", true, false, 0), ArchiveItem(".hidden", false, true, 3), ArchiveItem("link", false, false, 0))
                "nested" -> listOf(ArchiveItem("温度.txt", false, true, 3))
                else -> emptyList()
            }
            override suspend fun copy(path: String, output: OutputStream, bytes: (Long) -> Unit) { output.write("abc".toByteArray()); bytes(3) }
        }
        val destination = ByteArrayOutputStream()
        var done = 0L; var total = 0L
        downloadDirectoryZip(source, "My folder", destination) { d, t -> done = d; total = t }
        val entries = mutableMapOf<String, String>()
        ZipInputStream(destination.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(setOf("My folder/", "My folder/empty/", "My folder/nested/", "My folder/nested/温度.txt", "My folder/.hidden"), entries.keys)
        assertEquals("abc", entries["My folder/nested/温度.txt"])
        assertEquals(6L, done); assertEquals(6L, total)
    }
    @Test fun `unsafe names fail before destination receives archive data`() = runTest {
        val source = object : DirectorySource {
            override suspend fun children(path: String) = listOf(ArchiveItem("../escape", false, true, 1))
            override suspend fun copy(path: String, output: OutputStream, bytes: (Long) -> Unit) { fail("Unsafe path copied") }
        }
        val destination = ByteArrayOutputStream()
        try { downloadDirectoryZip(source, "folder", destination) { _, _ -> }; fail("Traversal accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, destination.size())
    }
    @Test fun `cancellation stops before copying another file`() = runTest {
        var copied = 0
        val source = object : DirectorySource {
            override suspend fun children(path: String) = listOf(ArchiveItem("first", false, true, 1), ArchiveItem("second", false, true, 1))
            override suspend fun copy(path: String, output: OutputStream, bytes: (Long) -> Unit) { copied++; delay(10_000); output.write(1); bytes(1) }
        }
        val job = launch { downloadDirectoryZip(source, "folder", ByteArrayOutputStream()) { _, _ -> } }
        runCurrent(); job.cancelAndJoin()
        assertEquals(1, copied)
    }
}
