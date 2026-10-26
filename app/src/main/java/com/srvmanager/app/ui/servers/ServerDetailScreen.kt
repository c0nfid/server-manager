package com.srvmanager.app.ui.servers

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AuthType
import com.srvmanager.app.data.Server
import com.srvmanager.app.geo.GeoLocator
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.LabeledValue
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.formatDateTime
import com.srvmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class ServerDetailViewModel(private val serverId: String) : ViewModel() {
    data class UiState(
        val testing: Boolean = false,
        val testMessage: String? = null,
        val testError: Throwable? = null,
        val geoLoading: Boolean = false,
        val geoError: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var autoLocated = false

    fun test() {
        val server = Graph.repo.server(serverId) ?: return
        _state.update { it.copy(testing = true, testMessage = null, testError = null) }
        viewModelScope.launch {
            try {
                val r = SshManager.test(server, persistHostKey = true)
                _state.update {
                    it.copy(testing = false, testMessage = "Подключение успешно: ${r.latencyMs} мс · ${r.serverVersion.orEmpty()}")
                }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, testError = e) }
            }
        }
    }

    fun locate(viaServer: Boolean) {
        val server = Graph.repo.server(serverId) ?: return
        _state.update { it.copy(geoLoading = true, geoError = null) }
        viewModelScope.launch {
            try {
                val geo = GeoLocator.locate(server, viaServer)
                Graph.repo.updateServer(serverId) { it.copy(geo = geo) }
                _state.update { it.copy(geoLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(geoLoading = false, geoError = e.friendlyMessage()) }
            }
        }
    }

    fun autoLocateOnce(server: Server) {
        if (autoLocated || server.geo != null) return
        autoLocated = true
        locate(viaServer = false)
    }

    fun clearTestError() = _state.update { it.copy(testError = null) }
}

@Composable
fun ServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onTerminal: () -> Unit,
    onSftp: () -> Unit,
    onMonitor: () -> Unit,
    onPortBind: () -> Unit,
    onKeySetup: () -> Unit,
) {
    val vm: ServerDetailViewModel = viewModel(key = "detail-$serverId") { ServerDetailViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val serverFlow = remember(serverId) { Graph.repo.serverFlow(serverId) }
    val server by serverFlow.collectAsStateWithLifecycle(initialValue = Graph.repo.server(serverId))
    val active by SshManager.active.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val current = server
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(serverId) { vm.autoLocateOnce(current) }

    AppScaffold(
        title = current.displayName,
        subtitle = current.address,
        onBack = onBack,
        actions = {
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Редактировать") }
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile("Терминал", Icons.Filled.Terminal, Modifier.weight(1f), onTerminal)
                ActionTile("Файлы SFTP", Icons.Filled.Folder, Modifier.weight(1f), onSftp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile("Мониторинг", Icons.Filled.Speed, Modifier.weight(1f), onMonitor)
                ActionTile("PortBind", Icons.Filled.Router, Modifier.weight(1f), onPortBind)
            }

            SectionCard(title = "Подключение", icon = Icons.Filled.NetworkCheck) {
                LabeledValue("Адрес", "${current.host}:${current.port}", monospace = true)
                LabeledValue("Пользователь", current.username)
                LabeledValue(
                    "Вход",
                    if (current.authType == AuthType.KEY) {
                        "SSH-ключ «${Graph.repo.key(current.keyId)?.name ?: "не найден"}»"
                    } else {
                        "Пароль"
                    },
                )
                current.hostKeyFingerprint?.let {
                    LabeledValue("Ключ хоста", "${current.hostKeyType.orEmpty()}\n$it", monospace = true)
                }
                LabeledValue("Сессия", if (serverId in active) "открыта" else "нет")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.test() }, enabled = !state.testing) {
                        if (state.testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text("Проверить")
                    }
                    if (serverId in active) {
                        TextButton(onClick = { SshManager.disconnect(serverId) }) {
                            Icon(Icons.Filled.LinkOff, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Отключить")
                        }
                    }
                }
                state.testMessage?.let { Text(it, color = StatusColors.ok, style = MaterialTheme.typography.bodySmall) }
                state.testError?.let {
                    Spacer(Modifier.height(8.dp))
                    ErrorCard(it, onRetry = { vm.clearTestError(); vm.test() })
                }
            }

            SectionCard(title = "SSH-ключ", icon = Icons.Filled.VpnKey) {
                Text(
                    if (current.authType == AuthType.KEY) {
                        "Сервер использует вход по ключу. Можно установить другой ключ."
                    } else {
                        "Приложение создаст ключ, добавит его в ~/.ssh/authorized_keys на сервере, " +
                            "проверит вход и переключит сервер на авторизацию по ключу."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onKeySetup) { Text("Настроить вход по ключу") }
            }

            SectionCard(title = "Местоположение", icon = Icons.Filled.LocationOn) {
                val geo = current.geo
                if (geo != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (geo.flag.isNotEmpty()) {
                            Text(geo.flag, fontSize = 32.sp)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(geo.country.ifBlank { "Неизвестно" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(listOf(geo.city, geo.region).filter { it.isNotBlank() }.distinct().joinToString(", "))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LabeledValue("IP", geo.ip, monospace = true)
                    if (geo.org.isNotBlank()) LabeledValue("Провайдер", geo.org)
                    if (geo.timezone.isNotBlank()) LabeledValue("Часовой пояс", geo.timezone)
                    if (geo.latitude != null && geo.longitude != null) {
                        LabeledValue("Координаты", String.format(Locale.ROOT, "%.4f, %.4f", geo.latitude, geo.longitude))
                    }
                    LabeledValue("Источник", "${geo.source}, ${formatDateTime(geo.updatedAt)}")
                } else if (!state.geoLoading) {
                    Text("Местоположение ещё не определено", style = MaterialTheme.typography.bodyMedium)
                }
                state.geoError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.geoLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = { vm.locate(false) }, enabled = !state.geoLoading) { Text("Определить") }
                    TextButton(onClick = { vm.locate(true) }, enabled = !state.geoLoading) { Text("Через сервер") }
                    if (geo?.latitude != null && geo.longitude != null) {
                        IconButton(onClick = {
                            val lat = geo.latitude
                            val lon = geo.longitude
                            val label = Uri.encode(current.displayName)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)"))
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=8/$lat/$lon")),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "Нет приложения для открытия карты", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Icon(Icons.Filled.Map, contentDescription = "Открыть на карте") }
                    }
                }
            }

            if (current.notes.isNotBlank()) {
                SectionCard(title = "Заметки") { Text(current.notes) }
            }
        }
    }
}

@Composable
private fun ActionTile(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}
