package com.omvcompanion.app.data.repo

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.omvcompanion.app.security.trustedSshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.serialization.json.*
import java.util.Base64

data class RemoteFile(val name: String, val directory: Boolean, val symbolicLink: Boolean, val size: Long, val modified: Long, val permissions: String)
internal fun fileErrorMessage(error: Exception, operation: String): String = when {
    error is SftpException && error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED -> "Your SSH account does not have permission to $operation here. Check the folder's ownership and permissions in OMV."
    error is SftpException && error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE -> "This file or folder is no longer available. Refresh the folder and try again."
    error is SftpException && operation == "delete" -> "Delete failed. Folders must be empty, and your SSH account must have permission to remove this entry."
    else -> error.message?.takeIf(String::isNotBlank) ?: "Could not $operation. Check the connection and your SSH permissions."
}
internal fun safeFileName(name: String): String {
    require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && name.none { it.code < 32 }) { "Enter a name without slashes or control characters." }
    require(name.toByteArray().size <= 255) { "The name is too long." }
    return name
}
internal fun insideSharedFolder(root: String, path: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
internal fun literalSftpPath(path: String): String = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?")
internal fun relativeFilePath(path: String): String {
    require(!path.startsWith('/') && path.split('/').none { it == ".." || it == "." } && '\\' !in path && path.none { it.code < 32 }) { "Invalid shared-folder path." }
    return path.trimEnd('/')
}

/** Every operation resolves the OMV share again and confines paths to its canonical root. */
class FileBrowserRepository(private val servers: ServerRepository, private val storage: StorageRepository, private val ssh: SshRunner) {
    private suspend fun <T> access(id: String, share: SharedFolder, path: String, operation: suspend (ChannelSftp, String, String) -> T): T = withContext(Dispatchers.IO) {
        require(id != "demo") { "File changes are disabled in demo mode." }
        val current = storage.getSharedFolders(id).firstOrNull { it.uuid == share.uuid } ?: error("This shared folder no longer exists.")
        val rootPath = current.absolutePath?.takeIf { it.startsWith('/') && it != "/" } ?: error("OMV did not report a usable path for this shared folder.")
        val session = ssh.session(id)
        var channel: ChannelSftp? = null
        try {
            channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(10_000)
            val root = channel.realpath(rootPath).trimEnd('/')
            require(root.isNotEmpty()) { "Browsing the server root is not allowed." }
            val relative = relativeFilePath(path)
            val target = if (relative.isEmpty()) root else "$root/$relative"
            // Reject symlinks in every traversed component, including the final entry.
            var checked = root
            relative.split('/').filter(String::isNotEmpty).forEach { part -> checked += "/$part"; require(!channel.lstat(literalSftpPath(checked)).isLink) { "Symbolic links are displayed but cannot be followed or changed." } }
            val canonical = channel.realpath(target)
            require(insideSharedFolder(root, canonical)) { "The path leaves this shared folder." }
            operation(channel, root, canonical)
        } finally { channel?.disconnect() }
    }
    suspend fun list(id: String, share: SharedFolder, path: String): List<RemoteFile> {
        if (id == "demo") return if (path.isBlank()) listOf(RemoteFile("Photos", true, false, 0, System.currentTimeMillis(), "drwxr-xr-x"), RemoteFile("welcome.txt", false, false, 1024, System.currentTimeMillis(), "-rw-r--r--")) else emptyList()
        return access(id, share, path) { channel, _, target ->
            channel.ls(literalSftpPath(target)).mapNotNull { value ->
                val entry = value as? ChannelSftp.LsEntry ?: return@mapNotNull null
                if (entry.filename in listOf(".", "..")) return@mapNotNull null
                RemoteFile(entry.filename, entry.attrs.isDir, entry.attrs.isLink, entry.attrs.size, entry.attrs.mTime.toLong() * 1000, entry.attrs.permissionsString)
            }
        }
    }
    suspend fun mkdir(id: String, share: SharedFolder, path: String, name: String) = access(id, share, path) { channel, _, parent -> channel.mkdir("$parent/${safeFileName(name)}") }
    suspend fun rename(id: String, share: SharedFolder, path: String, entry: RemoteFile, name: String) = access(id, share, path) { channel, root, parent ->
        val old = "$parent/${safeFileName(entry.name)}"
        require(!channel.lstat(literalSftpPath(old)).isLink) { "Symbolic links cannot be changed." }
        val new = "$parent/${safeFileName(name)}"
        require(old != new) { "Choose a different name." }
        require(!exists(channel, new)) { "An entry with that name already exists." }
        renameWithoutOverwrite(id, root, old, new)
    }
    suspend fun delete(id: String, share: SharedFolder, path: String, entry: RemoteFile) = access(id, share, path) { channel, _, parent ->
        val target = "$parent/${safeFileName(entry.name)}"
        val attrs = channel.lstat(literalSftpPath(target))
        require(!attrs.isLink) { "Symbolic links cannot be changed." }
        if (attrs.isDir) channel.rmdir(literalSftpPath(target)) else channel.rm(literalSftpPath(target))
    }
    suspend fun download(id: String, share: SharedFolder, path: String, entry: RemoteFile, output: OutputStream, progress: (Long, Long) -> Unit) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        access(id, share, path) { channel, _, parent ->
            val target = "$parent/${safeFileName(entry.name)}"
            val attrs = channel.lstat(literalSftpPath(target))
            require(!attrs.isLink && !attrs.isDir) { "Only regular files can be downloaded." }
            channel.get(literalSftpPath(target), output, monitor(attrs.size, job, progress))
        }
    }
    suspend fun downloadDirectory(id: String, share: SharedFolder, path: String, entry: RemoteFile?, output: OutputStream, progress: (Long, Long) -> Unit) {
        val selected = if (entry == null) path else listOf(path, safeFileName(entry.name)).filter(String::isNotEmpty).joinToString("/")
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        access(id, share, selected) { channel, root, directory ->
            require(channel.lstat(literalSftpPath(directory)).isDir) { "The selected folder no longer exists." }
            fun checked(relative: String): String {
                val safe = relativeFilePath(relative)
                var target = directory
                safe.split('/').filter(String::isNotEmpty).forEach { part ->
                    target += "/$part"
                    require(!channel.lstat(literalSftpPath(target)).isLink) { "Folder changed during download: symbolic link detected." }
                }
                val canonical = channel.realpath(target)
                require(insideSharedFolder(root, canonical) && insideSharedFolder(directory, canonical)) { "Folder path changed during download." }
                return canonical
            }
            val source = object : DirectorySource {
                override suspend fun children(path: String): List<ArchiveItem> {
                    job?.ensureActive()
                    return channel.ls(literalSftpPath(checked(path))).mapNotNull { value ->
                        val item = value as? ChannelSftp.LsEntry ?: return@mapNotNull null
                        if (item.filename in listOf(".", "..") || item.attrs.isLink) return@mapNotNull null
                        ArchiveItem(item.filename, item.attrs.isDir, item.attrs.isReg, item.attrs.size)
                    }
                }
                override suspend fun copy(path: String, output: OutputStream, bytes: (Long) -> Unit) {
                    job?.ensureActive()
                    val target = checked(path)
                    require(channel.lstat(literalSftpPath(target)).isReg) { "File changed during download." }
                    channel.get(literalSftpPath(target), output, object : SftpProgressMonitor {
                        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
                        override fun count(count: Long): Boolean { job?.ensureActive(); bytes(count); return true }
                        override fun end() = Unit
                    })
                }
            }
            downloadDirectoryZip(source, entry?.name ?: path.substringAfterLast('/').ifBlank { share.name }, output, progress)
        }
    }
    suspend fun upload(id: String, share: SharedFolder, path: String, name: String, size: Long, input: InputStream, progress: (Long, Long) -> Unit) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        access(id, share, path) { channel, root, parent ->
            val target = "$parent/${safeFileName(name)}"
            require(!exists(channel, target)) { "A file with this name already exists. Rename it first; existing files are never overwritten." }
            val temporary = "$parent/.omv-upload-${UUID.randomUUID()}.partial"
            try {
                channel.put(input, literalSftpPath(temporary), monitor(size, job, progress), ChannelSftp.OVERWRITE)
                job?.ensureActive()
                require(!exists(channel, target)) { "A file with this name was created during upload." }
                renameWithoutOverwrite(id, root, temporary, target)
            } finally { if (exists(channel, temporary)) runCatching { channel.rm(literalSftpPath(temporary)) } }
        }
    }
    private fun exists(channel: ChannelSftp, path: String): Boolean = try { channel.lstat(literalSftpPath(path)); true } catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e }
    private suspend fun renameWithoutOverwrite(id: String, root: String, source: String, target: String) {
        val script = """
import ctypes,json,os,sys
try:
 p=json.load(sys.stdin); root=p['root']; source=p['source']; target=p['target']
 for path in (source,target):
  assert os.path.commonpath([root,os.path.realpath(os.path.dirname(path))])==root, 'Path leaves shared folder'
 assert not os.path.islink(source), 'Symbolic links cannot be changed'
 libc=ctypes.CDLL(None,use_errno=True)
 rename=libc.renameat2
 rename.argtypes=[ctypes.c_int,ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_uint]
 if rename(-100,os.fsencode(source),-100,os.fsencode(target),1)!=0:
  errno=ctypes.get_errno(); raise OSError(errno,os.strerror(errno))
 print(json.dumps({'ok':True}))
except Exception as e:
 print(json.dumps({'ok':False,'error':str(e)})); sys.exit(1)
""".trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray())
        val input = buildJsonObject { put("root", root); put("source", source); put("target", target) }
        val result = ssh.execute(id, "python3 -c \"import base64;exec(base64.b64decode('$encoded'))\"", input.toString(), 30_000)
        val response = runCatching { Json.parseToJsonElement(result.output).jsonObject }.getOrNull()
        check(result.exitCode == 0 && response?.get("ok")?.jsonPrimitive?.booleanOrNull == true) { response?.get("error")?.jsonPrimitive?.contentOrNull ?: "Safe rename requires Python 3 and Linux renameat2 on the server. No existing file was overwritten." }
    }
    private fun monitor(size: Long, job: kotlinx.coroutines.Job?, progress: (Long, Long) -> Unit) = object : SftpProgressMonitor {
        var count = 0L
        override fun init(op: Int, src: String?, dest: String?, max: Long) { progress(0, size) }
        override fun count(bytes: Long): Boolean { job?.ensureActive(); count += bytes; progress(count, size); return true }
        override fun end() { progress(count, size) }
    }
}
