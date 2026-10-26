package com.srvmanager.app.ui.servers

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AuthType
import com.srvmanager.app.data.Server
import com.srvmanager.app.ssh.ConnectionTest
import com.srvmanager.app.ssh.HostKeyMismatchException
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.launch

@Composable
fun ServerEditScreen(serverId: String?, onDone: () -> Unit, onDeleted: () -> Unit) {
    val existing = remember(serverId) { serverId?.let { Graph.repo.server(it) } }
    val data by Graph.repo.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(existing?.host.orEmpty()) }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by rememberSaveable { mutableStateOf(existing?.username ?: "root") }
    var password by rememberSaveable { mutableStateOf(existing?.password.orEmpty()) }
    var authType by rememberSaveable { mutableStateOf(existing?.authType ?: AuthType.PASSWORD) }
    var keyId by rememberSaveable { mutableStateOf(existing?.keyId) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
    var hostKey by rememberSaveable { mutableStateOf(existing?.hostKey) }
    var hostKeyType by rememberSaveable { mutableStateOf(existing?.hostKeyType) }
    var hostKeyFp by rememberSaveable { mutableStateOf(existing?.hostKeyFingerprint) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTest?>(null) }
    var testError by remember { mutableStateOf<Throwable?>(null) }

    val portValue = port.trim().toIntOrNull()
    val hostError = when {
        host.isBlank() -> "Укажите IP или домен"
        host.contains("://") || host.contains('/') || host.trim().contains(' ') -> "Только адрес, без протокола и пути"
        else -> null
    }
    val portError = if (portValue == null || portValue !in 1..65535) "Порт 1–65535" else null
    val userError = if (username.isBlank()) "Укажите пользователя" else null
    val keyError = if (authType == AuthType.KEY && data.keys.none { it.id == keyId }) "Выберите ключ" else null
    val valid = hostError == null && portError == null && userError == null && keyError == null

    fun build(): Server {
        val base = existing ?: Server()
        val trimmedHost = host.trim()
        val endpointChanged = existing != null && (existing.host != trimmedHost || existing.port != portValue)
        return base.copy(
            name = name.trim(),
            host = trimmedHost,
            port = portValue ?: 22,
            username = username.trim(),
            password = password,
            authType = authType,
            keyId = if (authType == AuthType.KEY) keyId else base.keyId,
            notes = notes.trim(),
            // При смене адреса ключ хоста будет запомнен заново.
            hostKey = if (endpointChanged) null else hostKey,
            hostKeyType = if (endpointChanged) null else hostKeyType,
            hostKeyFingerprint = if (endpointChanged) null else hostKeyFp,
            geo = if (existing != null && existing.host != trimmedHost) null else base.geo,
        )
    }

    fun save() {
        val server = build()
        Graph.repo.upsertServer(server)
        SshManager.disconnect(server.id)
        Reachability.check(server)
        onDone()
    }

    AppScaffold(
        title = if (existing == null) "Новый сервер" else "Редактирование",
        subtitle = existing?.displayName,
        onBack = onDone,
        actions = {
            if (existing != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
            IconButton(onClick = { save() }, enabled = valid) {
                Icon(Icons.Filled.Check, contentDescription = "Сохранить")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP или домен") },
                    singleLine = true,
                    isError = hostError != null && host.isNotEmpty(),
                    supportingText = { if (hostError != null && host.isNotEmpty()) Text(hostError) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Порт") },
                    singleLine = true,
                    isError = portError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Пользователь") },
                singleLine = true,
                isError = userError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (authType == AuthType.KEY) "Пароль (для sudo, необязательно)" else "Пароль") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (showPassword) "Скрыть" else "Показать",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionCard(title = "Способ входа") {
                ChipRow {
                    FilterChip(
                        selected = authType == AuthType.PASSWORD,
                        onClick = { authType = AuthType.PASSWORD },
                        label = { Text("Пароль") },
                    )
                    FilterChip(
                        selected = authType == AuthType.KEY,
                        onClick = { authType = AuthType.KEY },
                        label = { Text("SSH-ключ") },
                    )
                }
                if (authType == AuthType.KEY) {
                    Spacer(Modifier.height(8.dp))
                    if (data.keys.isEmpty()) {
                        Text(
                            "Ключей нет. Создайте ключ в разделе «SSH-ключи» или используйте " +
                                "«Настроить вход по ключу» на странице сервера — ключ будет создан и установлен автоматически.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    data.keys.forEach { key ->
                        Row(
                            Modifier.fillMaxWidth().clickable { keyId = key.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = keyId == key.id, onClick = { keyId = key.id })
                            Column {
                                Text(key.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    key.type,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Заметки") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!hostKeyFp.isNullOrBlank()) {
                SectionCard(title = "Ключ хоста") {
                    Text(
                        "${hostKeyType.orEmpty()}\n$hostKeyFp",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = {
                        hostKey = null
                        hostKeyType = null
                        hostKeyFp = null
                    }) { Text("Забыть (запомнить заново при подключении)") }
                }
            }

            OutlinedButton(
                onClick = {
                    testing = true
                    testError = null
                    testResult = null
                    scope.launch {
                        try {
                            val draft = build()
                            val result = SshManager.test(draft)
                            testResult = result
                            if (hostKey.isNullOrEmpty() && result.hostKey != null) {
                                hostKey = result.hostKey.base64
                                hostKeyType = result.hostKey.type
                                hostKeyFp = result.hostKey.fingerprint
                            }
                        } catch (e: Exception) {
                            testError = e
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = valid && !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Проверить подключение")
            }
            testResult?.let { r ->
                Text(
                    "Подключение успешно: ${r.latencyMs} мс\n${r.serverVersion.orEmpty()}",
                    color = StatusColors.ok,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            testError?.let { e ->
                ErrorCard(
                    e,
                    onRetry = if (e is HostKeyMismatchException) {
                        {
                            // Новый ключ сохраняется и в черновике, иначе «Сохранить» вернёт старый.
                            hostKey = e.keyBase64
                            hostKeyType = e.keyType
                            hostKeyFp = e.fingerprint
                            testError = null
                        }
                    } else {
                        null
                    },
                )
            }
            Button(onClick = { save() }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = "Удалить сервер?",
            text = "«${existing.displayName}» будет удалён из приложения. На самом сервере ничего не изменится.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = {
                SshManager.disconnect(existing.id)
                Graph.repo.deleteServer(existing.id)
                onDeleted()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
