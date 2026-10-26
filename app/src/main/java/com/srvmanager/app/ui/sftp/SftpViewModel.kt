package com.srvmanager.app.ui.sftp

import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import com.srvmanager.app.Graph
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ssh.friendlyMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,
    val permissions: String,
    val mode: Int,
)

data class Transfer(val name: String, val upload: Boolean, val done: Long, val total: Long)

data class EditorState(val path: String, val text: String, val original: String, val saving: Boolean = false)

class SftpViewModel(private val serverId: String) : ViewModel() {
    data class UiState(
        val path: String = "",
        val entries: List<RemoteEntry> = emptyList(),
        val loading: Boolean = true,
        val error: Throwable? = null,
        val showHidden: Boolean = false,
        val transfer: Transfer? = null,
        val message: String? = null,
        val editor: EditorState? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val mutex = Mutex()
    private var channel: ChannelSftp? = null
    private var allEntries: List<RemoteEntry> = emptyList()

    @Volatile
    private var cancelTransfer = false

    init {
        viewModelScope.launch {
            try {
                val home = withSftp { it.home }
                load(home)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    /** ChannelSftp не потокобезопасен: все операции идут последовательно. */
    private suspend fun <T> withSftp(block: (ChannelSftp) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            var ch = channel
            if (ch == null || !ch.isConnected || ch.isClosed) {
                ch = SshManager.withConnection(serverId) { it.openSftp() }
                channel = ch
            }
            try {
                block(ch)
            } catch (e: Exception) {
                if (!ch.isConnected) channel = null
                throw e
            }
        }
    }

    private fun join(dir: String, name: String) = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    private fun parent(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }

    private fun applyFilter(entries: List<RemoteEntry>, showHidden: Boolean) =
        if (showHidden) entries else entries.filterNot { it.name.startsWith(".") }

    fun load(path: String) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = withSftp { sftp ->
                    val resolved = sftp.realpath(path)
                    val list = sftp.ls(resolved).mapNotNull { e ->
                        val name = e.filename
                        if (name == "." || name == "..") return@mapNotNull null
                        val attrs: SftpATTRS = e.attrs
                        val full = join(resolved, name)
                        val isLink = attrs.isLink
                        val isDir = if (isLink) {
                            try {
                                sftp.stat(full).isDir
                            } catch (ex: SftpException) {
                                false
                            }
                        } else {
                            attrs.isDir
                        }
                        RemoteEntry(
                            name = name,
                            path = full,
                            isDir = isDir,
                            isLink = isLink,
                            size = attrs.size,
                            mtime = attrs.getMTime().toLong() * 1000L,
                            permissions = attrs.permissionsString ?: "",
                            mode = attrs.permissions and 0xFFF,
                        )
                    }.sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
                    resolved to list
                }
                allEntries = entries.second
                _state.update {
                    it.copy(
                        path = entries.first,
                        entries = applyFilter(allEntries, it.showHidden),
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    fun refresh() = load(_state.value.path.ifEmpty { "." })

    fun up() {
        val path = _state.value.path
        if (path.isNotEmpty() && path != "/") load(parent(path))
    }

    fun toggleHidden() {
        _state.update {
            val show = !it.showHidden
            it.copy(showHidden = show, entries = applyFilter(allEntries, show))
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun operation(successMessage: String?, block: (ChannelSftp) -> Unit) {
        viewModelScope.launch {
            try {
                withSftp(block)
                if (successMessage != null) _state.update { it.copy(message = successMessage) }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(message = e.friendlyMessage()) }
            }
        }
    }

    fun mkdir(name: String) {
        if (_state.value.path.isEmpty()) return
        operation("Каталог создан") { it.mkdir(join(_state.value.path, name)) }
    }

    fun rename(entry: RemoteEntry, newName: String) =
        operation("Переименовано") { it.rename(entry.path, join(parent(entry.path), newName)) }

    fun chmod(entry: RemoteEntry, octal: String) =
        operation("Права изменены") { it.chmod(octal.toInt(8), entry.path) }

    fun delete(entry: RemoteEntry) = operation("Удалено") { deleteRecursive(it, entry.path, entry.isDir && !entry.isLink, 0) }

    private fun deleteRecursive(sftp: ChannelSftp, path: String, isDir: Boolean, depth: Int) {
        if (!isDir) {
            sftp.rm(path)
            return
        }
        if (depth > 32) throw IOException("Слишком глубокая вложенность каталогов")
        for (e in sftp.ls(path)) {
            val name = e.filename
            if (name == "." || name == "..") continue
            val attrs = e.attrs
            deleteRecursive(sftp, join(path, name), attrs.isDir && !attrs.isLink, depth + 1)
        }
        sftp.rmdir(path)
    }

    private inner class Progress(private val name: String, private val upload: Boolean, private val total: Long) :
        SftpProgressMonitor {
        private var done = 0L
        private var last = 0L

        override fun init(op: Int, src: String?, dest: String?, max: Long) {
            publish()
        }

        override fun count(count: Long): Boolean {
            done += count
            val now = SystemClock.elapsedRealtime()
            if (now - last > 150) {
                last = now
                publish()
            }
            return !cancelTransfer
        }

        override fun end() {
            publish()
        }

        private fun publish() = _state.update { it.copy(transfer = Transfer(name, upload, done, total)) }
    }

    fun cancelTransfer() {
        cancelTransfer = true
    }

    fun download(entry: RemoteEntry, target: Uri) {
        cancelTransfer = false
        viewModelScope.launch {
            try {
                withSftp { sftp ->
                    Graph.app.contentResolver.openOutputStream(target)?.use { out ->
                        sftp.get(entry.path, out, Progress(entry.name, false, entry.size))
                    } ?: throw IOException("Не удалось открыть файл для записи")
                }
                _state.update {
                    it.copy(transfer = null, message = if (cancelTransfer) "Загрузка отменена" else "Файл «${entry.name}» сохранён")
                }
            } catch (e: Exception) {
                _state.update { it.copy(transfer = null, message = e.friendlyMessage()) }
            }
        }
    }

    fun upload(source: Uri) {
        if (_state.value.path.isEmpty()) {
            _state.update { it.copy(message = "Каталог ещё не загружен") }
            return
        }
        cancelTransfer = false
        val resolver = Graph.app.contentResolver
        var name = "upload.bin"
        var size = -1L
        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = c.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
        val safeName = name.replace('/', '_')
        val dest = join(_state.value.path, safeName)
        viewModelScope.launch {
            try {
                withSftp { sftp ->
                    val input: InputStream = resolver.openInputStream(source) ?: throw IOException("Не удалось прочитать файл")
                    input.use { sftp.put(it, dest, Progress(safeName, true, size), ChannelSftp.OVERWRITE) }
                }
                _state.update {
                    it.copy(transfer = null, message = if (cancelTransfer) "Отправка отменена" else "Файл «$safeName» загружен")
                }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(transfer = null, message = e.friendlyMessage()) }
            }
        }
    }

    fun openEditor(entry: RemoteEntry) {
        if (entry.size > MAX_EDIT_SIZE) {
            _state.update { it.copy(message = "Файл больше 1 МБ — скачайте его для просмотра") }
            return
        }
        viewModelScope.launch {
            try {
                val bytes = withSftp { sftp ->
                    val out = ByteArrayOutputStream()
                    sftp.get(entry.path, out)
                    out.toByteArray()
                }
                if (bytes.contains(0.toByte())) {
                    _state.update { it.copy(message = "Похоже, это двоичный файл — откройте его скачиванием") }
                    return@launch
                }
                val text = String(bytes, Charsets.UTF_8)
                _state.update { it.copy(editor = EditorState(entry.path, text, text)) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.friendlyMessage()) }
            }
        }
    }

    fun closeEditor() = _state.update { it.copy(editor = null) }

    fun saveEditor(text: String) {
        val editor = _state.value.editor?.copy(text = text) ?: return
        _state.update { it.copy(editor = editor.copy(saving = true)) }
        viewModelScope.launch {
            try {
                withSftp { sftp ->
                    sftp.put(ByteArrayInputStream(editor.text.toByteArray(Charsets.UTF_8)), editor.path, ChannelSftp.OVERWRITE)
                }
                _state.update {
                    it.copy(editor = it.editor?.copy(saving = false, original = editor.text), message = "Сохранено")
                }
            } catch (e: Exception) {
                _state.update { it.copy(editor = it.editor?.copy(saving = false), message = e.friendlyMessage()) }
            }
        }
    }

    override fun onCleared() {
        val ch = channel
        channel = null
        Graph.appScope.launch(Dispatchers.IO) { runCatching { ch?.disconnect() } }
    }

    companion object {
        const val MAX_EDIT_SIZE = 1024L * 1024L
    }
}
