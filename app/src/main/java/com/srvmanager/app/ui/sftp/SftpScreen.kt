package com.srvmanager.app.ui.sftp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.TextInputDialog
import com.srvmanager.app.ui.common.formatBytes
import com.srvmanager.app.ui.common.formatDateTime

private sealed interface SftpDialog {
    data object Mkdir : SftpDialog
    data class Rename(val entry: RemoteEntry) : SftpDialog
    data class Chmod(val entry: RemoteEntry) : SftpDialog
    data class Delete(val entry: RemoteEntry) : SftpDialog
}

@Composable
fun SftpScreen(serverId: String, onBack: () -> Unit) {
    val vm: SftpViewModel = viewModel(key = "sftp-$serverId") { SftpViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<SftpDialog?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }

    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.download(entry, uri)
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val editor = state.editor
    if (editor != null) {
        TextEditor(editor, vm, snackbar)
        return
    }

    BackHandler(enabled = state.path.isNotEmpty() && state.path != "/") { vm.up() }

    AppScaffold(
        title = "Файлы",
        subtitle = server?.displayName,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { vm.toggleHidden() }) {
                Icon(
                    if (state.showHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = "Скрытые файлы",
                )
            }
            IconButton(onClick = { dialog = SftpDialog.Mkdir }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Новая папка")
            }
            IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Upload, contentDescription = "Загрузить файл")
            }
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Обновить") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.up() }, enabled = state.path.isNotEmpty() && state.path != "/") {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Вверх")
                    }
                    Text(
                        state.path.ifEmpty { "…" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    )
                }
            }
            state.transfer?.let { t ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (t.upload) "Отправка: " else "Скачивание: ") + t.name + " — " + formatBytes(t.done) +
                                if (t.total > 0) " / " + formatBytes(t.total) else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { vm.cancelTransfer() }) { Text("Отмена") }
                    }
                    if (t.total > 0) {
                        LinearProgressIndicator(
                            progress = { (t.done.toFloat() / t.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorCard(it, onRetry = { vm.refresh() }, modifier = Modifier.padding(16.dp)) }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.entries, key = { it.path }) { entry ->
                    EntryRow(
                        entry = entry,
                        onOpen = {
                            if (entry.isDir) vm.load(entry.path) else vm.openEditor(entry)
                        },
                        onDownload = {
                            pendingDownload = entry
                            downloadLauncher.launch(entry.name)
                        },
                        onEdit = { vm.openEditor(entry) },
                        onRename = { dialog = SftpDialog.Rename(entry) },
                        onChmod = { dialog = SftpDialog.Chmod(entry) },
                        onDelete = { dialog = SftpDialog.Delete(entry) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    val nameValidator: (String) -> String? = { v ->
        when {
            v.isBlank() -> "Введите имя"
            v.contains('/') -> "Имя не должно содержать /"
            v == "." || v == ".." -> "Недопустимое имя"
            else -> null
        }
    }
    when (val d = dialog) {
        SftpDialog.Mkdir -> TextInputDialog(
            title = "Новый каталог",
            label = "Имя",
            confirmText = "Создать",
            validate = nameValidator,
            onConfirm = { vm.mkdir(it.trim()) },
            onDismiss = { dialog = null },
        )
        is SftpDialog.Rename -> TextInputDialog(
            title = "Переименовать",
            label = "Новое имя",
            initial = d.entry.name,
            confirmText = "Переименовать",
            validate = nameValidator,
            onConfirm = { vm.rename(d.entry, it.trim()) },
            onDismiss = { dialog = null },
        )
        is SftpDialog.Chmod -> TextInputDialog(
            title = "Права доступа",
            label = "Восьмеричные права, например 644",
            initial = Integer.toOctalString(d.entry.mode and 0xFFF).padStart(3, '0'),
            confirmText = "Применить",
            keyboardType = KeyboardType.Number,
            validate = { v -> if (v.matches(Regex("^[0-7]{3,4}$"))) null else "3–4 цифры 0–7" },
            onConfirm = { vm.chmod(d.entry, it) },
            onDismiss = { dialog = null },
        )
        is SftpDialog.Delete -> ConfirmDialog(
            title = "Удалить?",
            text = if (d.entry.isDir && !d.entry.isLink) {
                "Каталог «${d.entry.name}» будет удалён вместе со всем содержимым."
            } else {
                "Файл «${d.entry.name}» будет удалён."
            },
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { vm.delete(d.entry) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun EntryRow(
    entry: RemoteEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onChmod: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                entry.isDir -> Icons.Filled.Folder
                entry.isLink -> Icons.Filled.Link
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (entry.isDir) null else formatBytes(entry.size),
                    entry.permissions,
                    if (entry.mtime > 0) formatDateTime(entry.mtime) else null,
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Действия") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!entry.isDir) {
                    DropdownMenuItem(text = { Text("Скачать") }, onClick = { menu = false; onDownload() })
                    DropdownMenuItem(text = { Text("Открыть в редакторе") }, onClick = { menu = false; onEdit() })
                }
                DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Права (chmod)") }, onClick = { menu = false; onChmod() })
                DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TextEditor(editor: EditorState, vm: SftpViewModel, snackbar: SnackbarHostState) {
    var confirmClose by remember { mutableStateOf(false) }
    // Текст живёт в локальном состоянии: ввод не проходит через StateFlow и не теряет символы.
    var text by rememberSaveable(editor.path) { mutableStateOf(editor.text) }
    val dirty = text != editor.original
    fun close() {
        if (dirty) confirmClose = true else vm.closeEditor()
    }
    BackHandler { close() }
    AppScaffold(
        title = editor.path.substringAfterLast('/'),
        subtitle = if (dirty) "изменён" else editor.path,
        onBack = { close() },
        snackbarHostState = snackbar,
        actions = {
            if (editor.saving) {
                CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { vm.saveEditor(text) }, enabled = dirty) {
                    Icon(Icons.Filled.Save, contentDescription = "Сохранить")
                }
            }
        },
    ) { padding ->
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxSize().padding(padding),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
    if (confirmClose) {
        ConfirmDialog(
            title = "Закрыть без сохранения?",
            text = "Изменения в файле будут потеряны.",
            confirmText = "Закрыть",
            destructive = true,
            onConfirm = { vm.closeEditor() },
            onDismiss = { confirmClose = false },
        )
    }
}
