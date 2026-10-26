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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.ssh.KeyAlgorithm
import com.srvmanager.app.ssh.KeyInstaller
import com.srvmanager.app.ssh.KeyTools
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KeySetupViewModel(private val serverId: String) : ViewModel() {
    data class UiState(
        val running: Boolean = false,
        val log: List<String> = emptyList(),
        val done: Boolean = false,
        val error: Throwable? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private fun log(line: String) = _state.update { it.copy(log = it.log + line) }

    /** [existingKeyId] = null — сгенерировать новый ключ алгоритмом [algorithm]. */
    fun start(existingKeyId: String?, algorithm: KeyAlgorithm, removePassword: Boolean) {
        val server = Graph.repo.server(serverId) ?: return
        _state.value = UiState(running = true)
        // Операция не прерывается при выходе с экрана, чтобы не оставить сервер в промежуточном состоянии.
        Graph.appScope.launch {
            try {
                var key = Graph.repo.key(existingKeyId)
                if (key == null) {
                    log("Генерация ключа ${algorithm.title}…")
                    key = KeyTools.generate(algorithm, "${server.displayName} (${algorithm.name.lowercase()})")
                    Graph.repo.upsertKey(key)
                    log("Ключ создан: ${key.fingerprint}")
                }
                KeyInstaller.install(server, key, removePassword) { log(it) }
                _state.update { it.copy(running = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e) }
            }
        }
    }
}

@Composable
fun KeySetupScreen(serverId: String, onBack: () -> Unit) {
    val vm: KeySetupViewModel = viewModel(key = "keysetup-$serverId") { KeySetupViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val data by Graph.repo.data.collectAsStateWithLifecycle()
    val server = data.servers.firstOrNull { it.id == serverId }

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var algorithm by rememberSaveable { mutableStateOf(KeyAlgorithm.ECDSA_256) }
    var removePassword by rememberSaveable { mutableStateOf(false) }

    AppScaffold(title = "Вход по SSH-ключу", subtitle = server?.address, onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (server == null) {
                Text("Сервер не найден")
            } else {
                Text(
                    "Шаги: подключение по паролю → запись публичного ключа в ~/.ssh/authorized_keys " +
                        "(с правильными правами) → проверка входа только по ключу → переключение сервера на ключ.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (server.password.isEmpty()) {
                    ErrorCard(
                        IllegalStateException("Для первичной установки ключа нужен пароль. Укажите его в настройках сервера."),
                        onRetry = null,
                    )
                }

                SectionCard(title = "Ключ") {
                    KeyOption(
                        selected = selectedKey == null,
                        title = "Создать новый ключ",
                        subtitle = null,
                        onClick = { selectedKey = null },
                    )
                    if (selectedKey == null) {
                        Column(Modifier.padding(start = 40.dp)) {
                            KeyAlgorithm.entries.forEach { algo ->
                                KeyOption(
                                    selected = algorithm == algo,
                                    title = algo.title,
                                    subtitle = null,
                                    onClick = { algorithm = algo },
                                )
                            }
                        }
                    }
                    data.keys.forEach { key ->
                        KeyOption(
                            selected = selectedKey == key.id,
                            title = key.name,
                            subtitle = "${key.type} · ${key.fingerprint}",
                            onClick = { selectedKey = key.id },
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().clickable { removePassword = !removePassword },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = removePassword, onCheckedChange = { removePassword = it })
                    Column {
                        Text("Удалить сохранённый пароль после настройки")
                        Text(
                            if (server.isRoot) {
                                "Для root пароль не нужен для sudo."
                            } else {
                                "Не рекомендуется: пароль нужен для sudo (PortBind, установка зависимостей)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = { vm.start(selectedKey, algorithm, removePassword) },
                    enabled = !state.running && server.password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Настроить")
                }

                if (state.log.isNotEmpty()) OutputBlock(state.log.joinToString("\n"))
                if (state.done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusColors.ok)
                        Spacer(Modifier.width(8.dp))
                        Text("Вход по ключу настроен", color = StatusColors.ok)
                    }
                }
                state.error?.let { ErrorCard(it, onRetry = null) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun KeyOption(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
