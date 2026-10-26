package com.srvmanager.app.ui.servers

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AuthType
import com.srvmanager.app.data.Server
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.StatusDot
import com.srvmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

/** Быстрая проверка доступности SSH-порта (TCP connect) для списка серверов. */
object Reachability {
    sealed interface State {
        data object Checking : State
        data class Up(val ms: Long) : State
        data class Down(val reason: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun checkAll(servers: List<Server>) {
        servers.forEach { check(it) }
    }

    fun check(server: Server) {
        if (_states.value[server.id] == State.Checking) return
        _states.update { it + (server.id to State.Checking) }
        Graph.appScope.launch(Dispatchers.IO) {
            val state = try {
                val start = SystemClock.elapsedRealtime()
                Socket().use { it.connect(InetSocketAddress(server.host.trim(), server.port), 5_000) }
                State.Up(SystemClock.elapsedRealtime() - start)
            } catch (e: Exception) {
                State.Down(e.javaClass.simpleName)
            }
            _states.update { it + (server.id to state) }
        }
    }
}

@Composable
fun ServerListScreen(
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onKeys: () -> Unit,
    onBackup: () -> Unit,
) {
    val data by Graph.repo.data.collectAsStateWithLifecycle()
    val reach by Reachability.states.collectAsStateWithLifecycle()
    val active by SshManager.active.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(data.servers.map { it.id to it.host }) {
        Reachability.checkAll(data.servers.filter { reach[it.id] == null })
    }

    val filtered = data.servers.filter { s ->
        query.isBlank() || listOf(s.name, s.host, s.username, s.geo?.shortLabel.orEmpty(), s.notes)
            .any { it.contains(query.trim(), ignoreCase = true) }
    }

    AppScaffold(
        title = "Серверы",
        subtitle = "${data.servers.size} в списке",
        actions = {
            IconButton(onClick = { Reachability.checkAll(data.servers) }) {
                Icon(Icons.Filled.NetworkCheck, contentDescription = "Проверить доступность")
            }
            IconButton(onClick = onKeys) { Icon(Icons.Filled.Key, contentDescription = "SSH-ключи") }
            IconButton(onClick = onBackup) { Icon(Icons.Filled.ImportExport, contentDescription = "Перенос данных") }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Сервер") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Graph.repo.loadError?.let {
                ErrorCard(IllegalStateException(it), onRetry = null, modifier = Modifier.padding(16.dp))
            }
            if (data.servers.isEmpty()) {
                EmptyState(onAdd)
            } else {
                if (data.servers.size > 4) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Поиск по имени, адресу, стране") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filtered, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            state = reach[server.id],
                            connected = server.id in active,
                            onClick = { onOpen(server.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Dns,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text("Серверов пока нет", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Добавьте сервер (IP, пользователь, пароль) или импортируйте данные с другого устройства.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            AssistChip(onClick = onAdd, label = { Text("Добавить сервер") }, leadingIcon = {
                Icon(Icons.Filled.Add, contentDescription = null)
            })
        }
    }
}

@Composable
private fun ServerCard(server: Server, state: Reachability.State?, connected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val flag = server.geo?.flag.orEmpty()
            Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                if (flag.isNotEmpty()) {
                    Text(flag, fontSize = 28.sp)
                } else {
                    Icon(Icons.Filled.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val location = server.geo?.shortLabel.orEmpty()
                if (location.isNotEmpty()) {
                    Text(location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, label) = when (state) {
                        is Reachability.State.Up -> StatusColors.ok to "${state.ms} мс"
                        is Reachability.State.Down -> StatusColors.bad to "недоступен"
                        Reachability.State.Checking -> StatusColors.warn to "…"
                        null -> MaterialTheme.colorScheme.outline to ""
                    }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    StatusDot(color)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (connected) {
                        Text("SSH", style = MaterialTheme.typography.labelSmall, color = StatusColors.ok)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(
                        if (server.authType == AuthType.KEY) Icons.Filled.VpnKey else Icons.Filled.Lock,
                        contentDescription = if (server.authType == AuthType.KEY) "Вход по ключу" else "Вход по паролю",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
