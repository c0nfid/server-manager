package com.srvmanager.app.ui.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.srvmanager.app.Graph
import com.srvmanager.app.data.BackupCrypto
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_PASSWORD = 8

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val data by Graph.repo.data.collectAsStateWithLifecycle()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var decrypted by remember { mutableStateOf<BackupCrypto.Bundle?>(null) }

    val passwordError = when {
        password.isEmpty() -> null
        password.length < MIN_PASSWORD -> "Минимум $MIN_PASSWORD символов"
        confirm.isNotEmpty() && confirm != password -> "Пароли не совпадают"
        else -> null
    }
    val canExport = password.length >= MIN_PASSWORD && password == confirm && !busy

    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        return "servers-$stamp.${BackupCrypto.FILE_EXTENSION}"
    }

    suspend fun encrypt(): ByteArray = withContext(Dispatchers.Default) {
        BackupCrypto.encrypt(Graph.repo.data.value, password.toCharArray())
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val bytes = encrypt()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IllegalStateException("Не удалось открыть файл")
                }
                snackbar.showSnackbar("Резервная копия сохранена")
            } catch (e: Exception) {
                snackbar.showSnackbar("Ошибка: ${e.friendlyMessage()}")
            } finally {
                busy = false
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: throw IllegalStateException("Не удалось прочитать файл")
                require(bytes.size < 20 * 1024 * 1024) { "Файл слишком большой" }
                importBytes = bytes
                importPassword = ""
                importError = null
                decrypted = null
            } catch (e: Exception) {
                snackbar.showSnackbar("Ошибка: ${e.friendlyMessage()}")
            }
        }
    }

    AppScaffold(title = "Перенос данных", onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Экспорт", icon = Icons.Filled.Upload) {
                Text(
                    "Серверов: ${data.servers.size}, ключей: ${data.keys.size}. Файл содержит пароли и приватные ключи, " +
                        "поэтому шифруется AES-256-GCM ключом из вашего пароля (PBKDF2, 150 000 итераций). " +
                        "Без пароля восстановить данные невозможно.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль для файла") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = passwordError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Повторите пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = passwordError != null,
                    supportingText = { passwordError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { saveLauncher.launch(fileName()) }, enabled = canExport) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Сохранить файл")
                    }
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val bytes = encrypt()
                                    val uri = withContext(Dispatchers.IO) {
                                        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        dir.listFiles()?.forEach { it.delete() }
                                        val file = File(dir, fileName()).apply { writeBytes(bytes) }
                                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    }
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Передать резервную копию"))
                                } catch (e: Exception) {
                                    snackbar.showSnackbar("Ошибка: ${e.friendlyMessage()}")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = canExport,
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Поделиться")
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            SectionCard(title = "Импорт", icon = Icons.Filled.Download) {
                Text(
                    "Выберите файл .${BackupCrypto.FILE_EXTENSION}, созданный на другом устройстве, и введите его пароль.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }) { Text("Выбрать файл") }
            }

            Text(
                "Как перенести: на старом устройстве сохраните или отправьте файл (мессенджер, почта, облако, USB), " +
                    "на новом установите приложение и импортируйте файл здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (importBytes != null && decrypted == null) {
        AlertDialog(
            onDismissRequest = { if (!busy) importBytes = null },
            title = { Text("Пароль резервной копии") },
            text = {
                Column {
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (busy) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {
                TextButton(enabled = importPassword.isNotEmpty() && !busy, onClick = {
                    importBytes?.let { bytes ->
                        busy = true
                        importError = null
                        scope.launch {
                            try {
                                decrypted = withContext(Dispatchers.Default) {
                                    BackupCrypto.decrypt(bytes, importPassword.toCharArray())
                                }
                            } catch (e: Exception) {
                                importError = e.message ?: "Ошибка расшифровки"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }) { Text("Расшифровать") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { importBytes = null }) { Text("Отмена") } },
        )
    }

    decrypted?.let { bundle ->
        AlertDialog(
            onDismissRequest = { decrypted = null; importBytes = null },
            title = { Text("Импорт данных") },
            text = {
                Text(
                    "Копия от ${formatDateTime(bundle.exportedAt)}: серверов ${bundle.data.servers.size}, " +
                        "ключей ${bundle.data.keys.size}.\n\n«Объединить» добавит новые записи и обновит совпадающие. " +
                        "«Заменить всё» удалит текущие данные на этом устройстве.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Graph.repo.merge(bundle.data)
                    SshManager.disconnectAll()
                    decrypted = null
                    importBytes = null
                    scope.launch { snackbar.showSnackbar("Данные объединены") }
                }) { Text("Объединить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        Graph.repo.replaceAll(bundle.data)
                        SshManager.disconnectAll()
                        decrypted = null
                        importBytes = null
                        scope.launch { snackbar.showSnackbar("Данные заменены") }
                    }) { Text("Заменить всё", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { decrypted = null; importBytes = null }) { Text("Отмена") }
                }
            },
        )
    }
}
