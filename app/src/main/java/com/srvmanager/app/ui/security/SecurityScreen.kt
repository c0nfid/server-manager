package com.srvmanager.app.ui.security

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.security.SecurityStatus
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.LabeledValue
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.theme.StatusColors

@Composable
fun SecurityScreen(serverId: String, onBack: () -> Unit) {
    val vm: SecurityViewModel = viewModel(key = "security-$serverId") { SecurityViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    val busy = state.busy != null
    var confirmPort by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    AppScaffold(
        title = "Безопасность",
        subtitle = server?.displayName,
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.refresh() }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
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
            if (state.loading || busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.busy?.let { Text("Выполняется: $it", style = MaterialTheme.typography.bodySmall) }
            }
            state.error?.let { ErrorCard(it, onRetry = { vm.refresh() }) }
            state.status?.let { status ->
                PingCard(status, busy, onSet = { disabled, ipv6 -> vm.setPing(disabled, ipv6) })
                SshPortCard(status, busy, onChange = { port, closeOld -> confirmPort = port to closeOld })
            }
            if (state.steps.isNotEmpty()) StepsCard(state.steps)
        }
    }

    confirmPort?.let { (port, closeOld) ->
        AlertDialog(
            onDismissRequest = { confirmPort = null },
            title = { Text("Перенести SSH на порт $port?") },
            text = {
                Text(
                    "1. Порт $port добавится к текущим и откроется в UFW/firewalld (если включены).\n" +
                        "2. Приложение проверит вход на новом порту.\n" +
                        "3. Только после успешного входа старый порт будет отключён" +
                        (if (closeOld) " и закрыт в файрволе" else "") + ".\n\n" +
                        "Если вход не удастся, изменения откатятся. Если у провайдера есть свой файрвол " +
                        "(security group), откройте в нём порт $port заранее.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPort = null
                    vm.changeSshPort(port, closeOld)
                }) { Text("Перенести") }
            },
            dismissButton = { TextButton(onClick = { confirmPort = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PingCard(status: SecurityStatus, busy: Boolean, onSet: (Boolean, Boolean) -> Unit) {
    var ipv6 by rememberSaveable { mutableStateOf(true) }
    SectionCard(title = "Ping (ICMP echo)", icon = Icons.Filled.NetworkCheck) {
        val disabled = status.pingDisabled
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (disabled) {
                        true -> "Сервер не отвечает на ping"
                        false -> "Сервер отвечает на ping"
                        null -> "Состояние неизвестно"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "net.ipv4.icmp_echo_ignore_all = ${if (disabled == true) 1 else 0}" +
                        (status.ping6Disabled?.let { "\nnet.ipv6.icmp.echo_ignore_all = ${if (it) 1 else 0}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = disabled == false,
                onCheckedChange = { answer -> onSet(!answer, ipv6) },
                enabled = !busy && disabled != null,
            )
        }
        if (status.ping6Disabled != null) {
            Row(
                Modifier.fillMaxWidth().clickable { ipv6 = !ipv6 },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ipv6, onCheckedChange = { ipv6 = it })
                Text("Отключать ping и для IPv6", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (status.pingOtherFiles.isNotEmpty()) {
            Text(
                "Параметр также задан в: ${status.pingOtherFiles.joinToString(", ")}. " +
                    "Настройка приложения применяется последней и переопределяет их.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Сохраняется в /etc/sysctl.d/99-zz-srvmanager-icmp.conf и переживает перезагрузку.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SshPortCard(status: SecurityStatus, busy: Boolean, onChange: (Int, Boolean) -> Unit) {
    var portText by rememberSaveable { mutableStateOf("") }
    var closeOld by rememberSaveable { mutableStateOf(true) }
    val port = portText.toIntOrNull()
    val error = when {
        portText.isEmpty() -> null
        port == null || port !in 1..65535 -> "Порт 1–65535"
        port in status.sshPorts -> "SSH уже на этом порту"
        else -> null
    }
    SectionCard(title = "Порт SSH", icon = Icons.Filled.VpnKey) {
        LabeledValue("Сейчас", status.sshPorts.joinToString(", "))
        LabeledValue(
            "Запуск",
            if (status.socketActivated) "через ssh.socket (Ubuntu 22.10+)" else "служба sshd",
        )
        LabeledValue(
            "UFW",
            when (status.ufw) {
                "active" -> "включён — порт откроется автоматически"
                "inactive" -> "выключен"
                else -> "не установлен"
            },
        )
        if (status.firewalld != "none") LabeledValue("firewalld", status.firewalld)
        if (status.selinux != "none") LabeledValue("SELinux", status.selinux)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text("Новый порт") },
            placeholder = { Text("например 2222") },
            singleLine = true,
            isError = error != null,
            supportingText = {
                when {
                    error != null -> Text(error)
                    port != null && port < 1024 -> Text("Порты до 1024 системные; обычно выбирают 1024–65535")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().clickable { closeOld = !closeOld },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = closeOld, onCheckedChange = { closeOld = it })
            Text("Закрыть старый порт в UFW/firewalld", style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = { if (port != null) onChange(port, closeOld) },
            enabled = !busy && port != null && error == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Перенести SSH") }
        Text(
            "Перенос безопасный: старый порт отключается только после успешного входа на новом, иначе — откат. " +
                "Порт сервера в приложении обновится автоматически.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepsCard(steps: List<Step>) {
    SectionCard(title = "Ход выполнения") {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                when (s.ok) {
                    null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    true -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusColors.ok, modifier = Modifier.size(18.dp))
                    false -> Icon(Icons.Filled.Error, contentDescription = null, tint = StatusColors.bad, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.text, style = MaterialTheme.typography.bodyMedium)
                    if (s.output.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        OutputBlock(s.output, maxLines = 20)
                    }
                }
            }
        }
    }
}
