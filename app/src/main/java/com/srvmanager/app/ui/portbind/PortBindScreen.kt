package com.srvmanager.app.ui.portbind

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.portbind.PortBindClient
import com.srvmanager.app.portbind.PortBindStatus
import com.srvmanager.app.portbind.PortBindValidation
import com.srvmanager.app.portbind.PortRule
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.StatusDot
import com.srvmanager.app.ui.common.TextInputDialog
import com.srvmanager.app.ui.common.formatDateTime
import com.srvmanager.app.ui.common.formatKb
import com.srvmanager.app.ui.theme.StatusColors

private sealed interface PbDialog {
    data object Add : PbDialog
    data class Edit(val rule: PortRule) : PbDialog
    data class Delete(val rule: PortRule) : PbDialog
    data object Reset : PbDialog
    data object RefreshDomain : PbDialog
    data object Cron : PbDialog
    data class Restore(val name: String) : PbDialog
}

@Composable
fun PortBindScreen(serverId: String, onBack: () -> Unit) {
    val vm: PortBindViewModel = viewModel(key = "portbind-$serverId") { PortBindViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    var dialog by remember { mutableStateOf<PbDialog?>(null) }
    val busy = state.busy != null
    val status = state.status

    AppScaffold(
        title = "PortBind",
        subtitle = server?.displayName,
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.reload() }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading || busy) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.busy?.let {
                            Spacer(Modifier.height(4.dp))
                            Text("Выполняется: $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            state.error?.let { e -> item { ErrorCard(e, onRetry = { vm.reload() }) } }

            if (status != null) {
                item { InstallCard(status, busy, vm) }
                if (status.installed) {
                    item { AutomationCard(status, busy, vm, onCron = { dialog = PbDialog.Cron }) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Перенаправления (${state.rules.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Button(onClick = { dialog = PbDialog.Add }, enabled = !busy) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Бинд порта")
                            }
                        }
                    }
                    state.rulesError?.let { item { ErrorCard(IllegalStateException(it), onRetry = { vm.reload() }) } }
                    if (state.rules.isEmpty() && state.rulesError == null && !state.loading) {
                        item {
                            Text(
                                "Нет перенаправлений. Нажмите «Бинд порта», чтобы пробросить входной порт этого сервера на другую ноду.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.rules, key = { it.inPort }) { rule ->
                        RuleCard(
                            rule = rule,
                            enabled = !busy,
                            onEdit = { dialog = PbDialog.Edit(rule) },
                            onDelete = { dialog = PbDialog.Delete(rule) },
                            onRefresh = {
                                // refresh принимает только домен: выбираем доменное имя записи,
                                // а если доменов два разных — обновляем все.
                                val domain = when {
                                    rule.targetIsDomain && rule.bindIsDomain && rule.targetHost != rule.bindHost -> null
                                    rule.targetIsDomain -> rule.targetHost
                                    else -> rule.bindHost
                                }
                                vm.refreshDns(domain)
                            },
                        )
                    }
                    item { ActionsCard(state, busy, vm, onReset = { dialog = PbDialog.Reset }, onRefreshDomain = { dialog = PbDialog.RefreshDomain }) }
                    state.backups?.let { backups ->
                        item { BackupsCard(backups, busy, vm, onRestore = { dialog = PbDialog.Restore(it) }) }
                    }
                }
            }

            if (state.log.isNotEmpty()) {
                item {
                    Text("Журнал операций", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(state.log) { entry -> LogCard(entry) }
            }
        }
    }

    when (val d = dialog) {
        PbDialog.Add -> RuleDialog(null, onDismiss = { dialog = null }) { p, h, o, proto, b -> vm.add(p, h, o, proto, b) }
        is PbDialog.Edit -> RuleDialog(d.rule, onDismiss = { dialog = null }) { p, h, o, proto, b ->
            vm.replace(d.rule, p, h, o, proto, b)
        }
        is PbDialog.Delete -> ConfirmDialog(
            title = "Удалить перенаправление?",
            text = "Порт ${d.rule.inPort} (${d.rule.protocol}) → ${d.rule.targetHost}:${d.rule.targetPort}. " +
                "Удаление влияет на новые соединения; активные могут жить до таймаута conntrack.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { vm.delete(d.rule) },
            onDismiss = { dialog = null },
        )
        PbDialog.Reset -> TextInputDialog(
            title = "Сбросить все правила portbind?",
            label = "Введите YES для подтверждения",
            confirmText = "Сбросить",
            validate = { if (it == "YES") null else "Нужно ввести YES" },
            onConfirm = { vm.reset() },
            onDismiss = { dialog = null },
        )
        PbDialog.RefreshDomain -> TextInputDialog(
            title = "Обновить IP домена",
            label = "Домен (пусто = все)",
            confirmText = "Обновить",
            validate = { v ->
                when {
                    v.isBlank() -> null
                    PortBindValidation.host(v) == null || PortBindValidation.isIpv4(v.trim()) -> "Укажите домен, не IP"
                    else -> null
                }
            },
            onConfirm = { vm.refreshDns(PortBindValidation.host(it)) },
            onDismiss = { dialog = null },
        )
        PbDialog.Cron -> CronDialog(
            current = PortBindClient.cronInterval(state.status?.cronLine),
            onDismiss = { dialog = null },
            onSelect = { vm.setCron(it) },
        )
        is PbDialog.Restore -> ConfirmDialog(
            title = "Восстановить копию?",
            text = "rules.tsv будет заменён копией ${d.name}, затем выполнится portbind apply. " +
                "Текущее состояние сохранится в новой резервной копии.",
            confirmText = "Восстановить",
            onConfirm = { vm.restoreBackup(d.name) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    state.backupPreview?.let { (name, rules) ->
        AlertDialog(
            onDismissRequest = { vm.closePreview() },
            title = { Text("Копия $name") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (rules.isEmpty()) Text("Записей нет")
                    rules.forEach { Text(ruleLine(it), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.closePreview() }) { Text("Закрыть") } },
        )
    }
}

private fun ruleLine(rule: PortRule): String =
    "${rule.inPort}/${rule.protocol} → ${rule.targetHost}:${rule.targetPort}" +
        if (rule.bindHost != "*") " (вход ${rule.bindHost})" else ""

@Composable
private fun InstallCard(status: PortBindStatus, busy: Boolean, vm: PortBindViewModel) {
    val bundled = PortBindClient.bundledVersion
    SectionCard(title = "Установка", icon = Icons.Filled.Router) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (status.installed) StatusColors.ok else StatusColors.bad)
            Spacer(Modifier.width(8.dp))
            Text(
                if (status.installed) "Установлен: ${status.installedVersion} (в приложении $bundled)" else "portbind не установлен",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (status.privilege == "none") {
            Spacer(Modifier.height(6.dp))
            Text(
                "На сервере нет sudo, а пользователь не root — управление portbind невозможно.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!status.bashOk) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Нужен Bash 4.4+ (найден: ${status.bashVersion ?: "нет"}).",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.missingDeps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Не хватает утилит: ${status.missingDeps.joinToString(", ")}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        ChipRow {
            Button(onClick = { vm.install() }, enabled = !busy) {
                Text(
                    when {
                        !status.installed -> "Установить"
                        status.installedVersion != bundled -> "Обновить до $bundled"
                        else -> "Переустановить"
                    },
                )
            }
            if (status.missingDeps.isNotEmpty() || !status.bashOk) {
                OutlinedButton(onClick = { vm.installDependencies() }, enabled = !busy && status.packageManager != null) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Зависимости (${status.packageManager ?: "нет пакетного менеджера"})")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Скрипт копируется в /usr/local/sbin/portbind (root, 0755) с проверкой SHA-256.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AutomationCard(status: PortBindStatus, busy: Boolean, vm: PortBindViewModel, onCron: () -> Unit) {
    SectionCard(title = "Автоматизация", icon = Icons.Filled.Schedule) {
        Text("После перезагрузки (systemd)", style = MaterialTheme.typography.labelLarge)
        if (!status.systemdAvailable) {
            Text("systemd не обнаружен — настройте запуск «portbind apply» при старте вручную.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (status.serviceEnabled == "enabled") StatusColors.ok else StatusColors.warn)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (status.serviceInstalled) "portbind.service: ${status.serviceEnabled}, ${status.serviceActive}" else "Не настроено",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.setService(!status.serviceInstalled) }, enabled = !busy) {
                    Text(if (status.serviceInstalled) "Отключить" else "Включить")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Обновление IP доменов (cron)", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val interval = PortBindClient.cronInterval(status.cronLine)
            StatusDot(if (status.cronLine != null) StatusColors.ok else StatusColors.warn)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    status.cronLine == null -> "Не настроено"
                    interval != null -> "Каждые $interval мин"
                    else -> status.cronLine.orEmpty()
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCron, enabled = !busy && status.cronDirExists) { Text("Настроить") }
        }
        if (!status.cronDirExists) {
            Text("/etc/cron.d отсутствует — установите cron.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RuleCard(rule: PortRule, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ":${rule.inPort}  →  ${rule.targetHost}:${rule.targetPort}",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${rule.protocol.uppercase()} · вход ${if (rule.bindHost == "*") "все IPv4" else "${rule.bindHost} (${rule.bindIp})"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (rule.targetIsDomain) {
                        Text(
                            "выходной IP: ${rule.targetIp}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row {
                Spacer(Modifier.weight(1f))
                if (rule.targetIsDomain || rule.bindIsDomain) {
                    IconButton(onClick = onRefresh, enabled = enabled) { Icon(Icons.Filled.Sync, contentDescription = "Обновить DNS") }
                }
                IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Filled.Edit, contentDescription = "Изменить") }
                IconButton(onClick = onDelete, enabled = enabled) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    state: PortBindViewModel.UiState,
    busy: Boolean,
    vm: PortBindViewModel,
    onReset: () -> Unit,
    onRefreshDomain: () -> Unit,
) {
    SectionCard(title = "Действия", icon = Icons.Filled.Terminal) {
        ChipRow {
            OutlinedButton(onClick = { vm.refreshDns(null) }, enabled = !busy) { Text("refresh все") }
            OutlinedButton(onClick = onRefreshDomain, enabled = !busy) { Text("refresh домен") }
            OutlinedButton(onClick = { vm.apply() }, enabled = !busy) { Text("apply") }
        }
        Spacer(Modifier.height(4.dp))
        ChipRow {
            OutlinedButton(onClick = { if (state.kernelTable == null) vm.loadKernelTable() else vm.hideKernelTable() }) {
                Text(if (state.kernelTable == null) "Таблица в ядре" else "Скрыть таблицу")
            }
            OutlinedButton(onClick = { if (state.backups == null) vm.loadBackups() else vm.hideBackups() }) {
                Icon(Icons.Filled.Backup, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (state.backups == null) "Резервные копии" else "Скрыть копии")
            }
            OutlinedButton(onClick = onReset, enabled = !busy) {
                Text("reset", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "list показывает сохранённую конфигурацию; фактические правила ядра — «Таблица в ядре» (nft list table ip portbind).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.kernelTable?.let {
            Spacer(Modifier.height(8.dp))
            OutputBlock(it)
        }
    }
}

@Composable
private fun BackupsCard(
    backups: List<com.srvmanager.app.portbind.BackupEntry>,
    busy: Boolean,
    vm: PortBindViewModel,
    onRestore: (String) -> Unit,
) {
    SectionCard(title = "Резервные копии (${backups.size})", icon = Icons.Filled.Backup) {
        if (backups.isEmpty()) Text("Копий нет", style = MaterialTheme.typography.bodyMedium)
        backups.take(30).forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(b.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(
                        "записей: ${b.rules} · ${formatKb(b.sizeKb)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.previewBackup(b.name) }) { Text("Смотреть") }
                TextButton(onClick = { onRestore(b.name) }, enabled = !busy) { Text("Восстановить") }
            }
        }
        if (backups.size > 10) {
            OutlinedButton(onClick = { vm.pruneBackups(10) }, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Оставить 10 последних")
            }
        }
        Text(
            "Каждая копия содержит полный снимок nftables; portbind не удаляет их автоматически.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogCard(entry: LogEntry) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.ok) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (entry.ok) StatusColors.ok else StatusColors.bad,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(entry.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(formatDateTime(entry.time), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                (entry.exitCode?.let { "Код $it: " } ?: "") + entry.hint,
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.output.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                OutputBlock(entry.output.trim(), maxLines = 30)
            }
        }
    }
}

@Composable
private fun CronDialog(current: Int?, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var selected by remember { mutableStateOf(current ?: 5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Расписание portbind refresh") },
        text = {
            Column {
                Text(
                    "Создаётся /etc/cron.d/portbind-refresh (от root) с логом /var/log/portbind-refresh.log и ротацией.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                ChipRow {
                    PortBindClient.CRON_INTERVALS.forEach { m ->
                        FilterChip(selected = selected == m, onClick = { selected = m }, label = { Text("$m мин") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onSelect(selected)
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onSelect(0)
                    }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/** Диалог добавления/изменения записи с проверкой по правилам portbind. */
@Composable
private fun RuleDialog(
    existing: PortRule?,
    onDismiss: () -> Unit,
    onSubmit: (Int, String, Int, String, String) -> Unit,
) {
    var inPort by remember { mutableStateOf(existing?.inPort?.toString().orEmpty()) }
    var target by remember { mutableStateOf(existing?.targetHost.orEmpty()) }
    var outPort by remember { mutableStateOf(existing?.targetPort?.toString().orEmpty()) }
    var protocol by remember { mutableStateOf(existing?.protocol ?: "both") }
    var bind by remember { mutableStateOf(existing?.bindHost ?: "*") }

    val inPortValue = PortBindValidation.port(inPort)
    val outPortValue = PortBindValidation.port(outPort)
    val targetValue = PortBindValidation.host(target)
    val bindValue = PortBindValidation.bindHost(bind)
    val targetError = when {
        target.isBlank() -> null
        target.contains("://") || target.contains('/') -> "Без протокола и пути: только IP или домен"
        target.contains(':') -> "Порт указывается отдельно; IPv6 не поддерживается"
        targetValue == null -> "IPv4 или домен (IDN — в punycode)"
        else -> null
    }
    val valid = inPortValue != null && outPortValue != null && targetValue != null && bindValue != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Бинд порта" else "Изменить :${existing.inPort}") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inPort,
                    onValueChange = { inPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Входной порт") },
                    singleLine = true,
                    isError = inPort.isNotEmpty() && inPortValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it.trim() },
                    label = { Text("Выходная нода (IP или домен)") },
                    singleLine = true,
                    isError = targetError != null,
                    supportingText = { targetError?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = outPort,
                    onValueChange = { outPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Выходной порт") },
                    singleLine = true,
                    isError = outPort.isNotEmpty() && outPortValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text("Протокол", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    listOf("tcp", "udp", "both").forEach { p ->
                        FilterChip(selected = protocol == p, onClick = { protocol = p }, label = { Text(p) })
                    }
                }
                OutlinedTextField(
                    value = bind,
                    onValueChange = { bind = it.trim() },
                    label = { Text("Входной адрес (* = все IPv4 сервера)") },
                    singleLine = true,
                    isError = bindValue == null,
                    supportingText = {
                        Text("IP/домен этого сервера (после внешнего NAT), либо *")
                    },
                )
                if (existing != null) {
                    Text(
                        "Изменение выполняется как delete + add: на короткое время порт не перенаправляется. " +
                            "При ошибке add прежняя запись будет восстановлена.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                if (inPortValue != null && outPortValue != null && targetValue != null && bindValue != null) {
                    onDismiss()
                    onSubmit(inPortValue, targetValue, outPortValue, protocol, bindValue)
                }
            }) { Text(if (existing == null) "Добавить" else "Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
