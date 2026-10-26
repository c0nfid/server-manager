package com.srvmanager.app.ui.keys

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.srvmanager.app.Graph
import com.srvmanager.app.data.SshKey
import com.srvmanager.app.ssh.KeyAlgorithm
import com.srvmanager.app.ssh.KeyTools
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.copyToClipboard
import com.srvmanager.app.ui.common.formatDateTime
import kotlinx.coroutines.launch

@Composable
fun KeysScreen(onBack: () -> Unit) {
    val data by Graph.repo.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleteKey by remember { mutableStateOf<SshKey?>(null) }
    var revealKey by remember { mutableStateOf<SshKey?>(null) }

    AppScaffold(
        title = "SSH-ключи",
        subtitle = "Хранятся в зашифрованном виде на устройстве",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { showImport = true }) {
                Icon(Icons.Filled.FileUpload, contentDescription = "Импорт ключа")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showGenerate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Создать ключ") },
            )
        },
    ) { padding ->
        if (data.keys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ключей нет. Создайте ключ или импортируйте существующий (OpenSSH, PEM, PuTTY; " +
                        "Ed25519, ECDSA, RSA). Проще всего — «Настроить вход по ключу» на странице сервера.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(data.keys, key = { it.id }) { key ->
                    val usedBy = data.servers.count { it.keyId == key.id }
                    KeyCard(
                        key = key,
                        usedBy = usedBy,
                        onCopy = { copyToClipboard(context, "public key", key.publicKey) },
                        onShare = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, key.publicKey)
                            }
                            context.startActivity(Intent.createChooser(intent, "Публичный ключ"))
                        },
                        onReveal = { revealKey = key },
                        onDelete = { deleteKey = key },
                    )
                }
            }
        }
    }

    if (showGenerate) {
        GenerateKeyDialog(
            onDismiss = { showGenerate = false },
            onGenerate = { name, algorithm ->
                scope.launch {
                    try {
                        Graph.repo.upsertKey(KeyTools.generate(algorithm, name))
                        snackbar.showSnackbar("Ключ «$name» создан")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Ошибка: ${e.friendlyMessage()}")
                    }
                }
            },
        )
    }
    if (showImport) {
        ImportKeyDialog(onDismiss = { showImport = false }, onImported = { scope.launch { snackbar.showSnackbar("Ключ импортирован") } })
    }
    deleteKey?.let { key ->
        val usedBy = data.servers.count { it.keyId == key.id }
        ConfirmDialog(
            title = "Удалить ключ?",
            text = "Ключ «${key.name}» будет удалён с устройства." +
                if (usedBy > 0) " $usedBy сервер(ов) переключатся на вход по паролю." else "",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { Graph.repo.deleteKey(key.id) },
            onDismiss = { deleteKey = null },
        )
    }
    revealKey?.let { key ->
        AlertDialog(
            onDismissRequest = { revealKey = null },
            title = { Text("Приватный ключ") },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Никому не передавайте приватный ключ. Копируйте его только на свои устройства.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(key.privateKey, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, "private key", key.privateKey)
                    revealKey = null
                }) { Text("Копировать") }
            },
            dismissButton = { TextButton(onClick = { revealKey = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun KeyCard(
    key: SshKey,
    usedBy: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(key.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${key.type} · создан ${formatDateTime(key.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    key.fingerprint,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (usedBy > 0) {
                    Text("Используется: $usedBy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать публичный ключ") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Поделиться публичным ключом") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menu = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text("Показать приватный ключ") },
                        leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                        onClick = { menu = false; onReveal() },
                    )
                    DropdownMenuItem(
                        text = { Text("Удалить") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerateKeyDialog(onDismiss: () -> Unit, onGenerate: (String, KeyAlgorithm) -> Unit) {
    var name by remember { mutableStateOf("Ключ ${android.os.Build.MODEL ?: "Android"}") }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.ECDSA_256) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый ключ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                KeyAlgorithm.entries.forEach { algo ->
                    FilterChip(selected = algorithm == algo, onClick = { algorithm = algo }, label = { Text(algo.title) })
                }
                Text(
                    "Ed25519 можно импортировать, но не сгенерировать: библиотека JSch не умеет его сохранять.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onDismiss()
                onGenerate(name.trim(), algorithm)
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ImportKeyDialog(onDismiss: () -> Unit, onImported: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    require(bytes.size < 64 * 1024) { "Файл слишком большой для ключа" }
                    String(bytes, Charsets.UTF_8)
                }
                if (text != null) privateKey = text
            } catch (e: Exception) {
                error = e.friendlyMessage()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Импорт ключа") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    label = { Text("Приватный ключ") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Выбрать файл ключа") }
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Парольная фраза (если есть)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                ChipRow {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = privateKey.isNotBlank() && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        Graph.repo.upsertKey(KeyTools.importKey(name.trim(), privateKey, passphrase))
                        onDismiss()
                        onImported()
                    } catch (e: Exception) {
                        error = e.friendlyMessage()
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Импортировать") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}
