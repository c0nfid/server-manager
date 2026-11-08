package com.srvmanager.app.ui.ufw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.ufw.UfwRule
import com.srvmanager.app.ufw.UfwRuleInput
import com.srvmanager.app.ufw.UfwStatus
import com.srvmanager.app.ufw.UfwValidation
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.StatusDot
import com.srvmanager.app.ui.common.StepsCard
import com.srvmanager.app.ui.common.TextInputDialog
import com.srvmanager.app.ui.theme.StatusColors

private sealed interface UfwDialog {
    data object Enable : UfwDialog
    data object Disable : UfwDialog
    data object Add : UfwDialog
    data object Reset : UfwDialog
    data object RoutePortBind : UfwDialog
    data class Delete(val rule: UfwRule) : UfwDialog
    data class Default(val direction: String, val policy: String) : UfwDialog
}

@Composable
fun UfwScreen(serverId: String, onBack: () -> Unit) {
    val vm: UfwViewModel = viewModel(key = "ufw-$serverId") { UfwViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    val busy = state.busy != null
    var dialog by remember { mutableStateOf<UfwDialog?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    AppScaffold(
        title = "Файрвол UFW",
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
            if (state.steps.isNotEmpty()) StepsCard(state.steps, title = "Последняя операция")

            val status = state.status
            if (status != null && !status.installed) {
                SectionCard(title = "UFW не установлен", icon = Icons.Filled.Security) {
                    Text(
                        "UFW — простой интерфейс к файрволу Linux. Установка через ${status.packageManager ?: "пакетный менеджер"}; " +
                            "после установки он остаётся выключенным, пока вы не включите его здесь.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.install() }, enabled = !busy && status.packageManager != null) { Text("Установить UFW") }
                }
            }
            if (status != null && status.installed) {
                StatusCard(status, busy, onToggle = { on -> dialog = if (on) UfwDialog.Enable else UfwDialog.Disable })
                RulesCard(status, busy, onAdd = { dialog = UfwDialog.Add }, onDelete = { dialog = UfwDialog.Delete(it) })
                PoliciesCard(
                    status,
                    busy,
                    onPolicy = { direction, policy -> dialog = UfwDialog.Default(direction, policy) },
                    onLogging = { vm.setLogging(it) },
                )
                if (status.portBindRules.isNotEmpty()) {
                    SectionCard(title = "PortBind", icon = Icons.Filled.Router) {
                        Text(
                            "На сервере ${status.portBindRules.size} перенаправлений PortBind. Пересылка по умолчанию " +
                                (if (status.defaultRouted == "allow") "разрешена." else "запрещена (${status.defaultRouted}) — при включённом UFW трафик не пройдёт.") +
                                " Можно добавить правила «ufw route allow» для каждого выходного адреса.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { dialog = UfwDialog.RoutePortBind }, enabled = !busy) {
                            Text("Разрешить пересылку для PortBind")
                        }
                    }
                }
                if (status.dockerInstalled) {
                    SectionCard(title = "Docker", icon = Icons.Filled.Warning) {
                        Text(
                            "Docker публикует порты контейнеров в обход UFW (через свою цепочку iptables). " +
                                "Правила UFW не закрывают такие порты — ограничивайте их в docker run/compose (например, 127.0.0.1:порт).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                SectionCard(title = "Подробно", icon = Icons.AutoMirrored.Filled.List) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "Скрыть ufw status verbose" else "Показать ufw status verbose")
                    }
                    if (showRaw) OutputBlock(status.statusText)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { dialog = UfwDialog.Reset }, enabled = !busy) {
                        Text("Сбросить UFW", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    val status = state.status
    when (val d = dialog) {
        UfwDialog.Enable -> ConfirmDialog(
            title = "Включить UFW?",
            text = "Перед включением будут разрешены SSH-порты ${status?.sshPorts?.joinToString(", ") ?: "22"}/tcp, " +
                "чтобы не потерять доступ. Остальные входящие подключения будут обрабатываться по правилам " +
                "и политике «${status?.defaultIncoming ?: "deny"}».",
            confirmText = "Включить",
            onConfirm = { vm.enable() },
            onDismiss = { dialog = null },
        )
        UfwDialog.Disable -> ConfirmDialog(
            title = "Выключить UFW?",
            text = "Все правила UFW перестанут действовать до повторного включения (сами правила сохранятся).",
            confirmText = "Выключить",
            destructive = true,
            onConfirm = { vm.disable() },
            onDismiss = { dialog = null },
        )
        UfwDialog.Add -> AddRuleDialog(
            apps = status?.apps.orEmpty(),
            sshPorts = status?.sshPorts ?: listOf(22),
            onDismiss = { dialog = null },
            onAdd = { vm.add(it) },
        )
        UfwDialog.Reset -> TextInputDialog(
            title = "Сбросить UFW?",
            label = "Введите YES для подтверждения",
            confirmText = "Сбросить",
            validate = { if (it == "YES") null else "Нужно ввести YES" },
            onConfirm = { vm.reset() },
            onDismiss = { dialog = null },
        )
        UfwDialog.RoutePortBind -> ConfirmDialog(
            title = "Разрешить пересылку?",
            text = "Для каждой записи PortBind будет добавлено правило «ufw route allow proto … to <выходной IP> port <порт>».",
            confirmText = "Добавить",
            onConfirm = { vm.routePortBind() },
            onDismiss = { dialog = null },
        )
        is UfwDialog.Delete -> ConfirmDialog(
            title = "Удалить правило?",
            text = d.rule.spec + "\n\nУдаляются версии правила для IPv4 и IPv6.",
            confirmText = "Удалить",
            destructive = true,
            onConfirm = { vm.delete(d.rule) },
            onDismiss = { dialog = null },
        )
        is UfwDialog.Default -> ConfirmDialog(
            title = "Изменить политику?",
            text = "ufw default ${d.policy} ${d.direction}" +
                when {
                    d.direction == "outgoing" && d.policy != "allow" ->
                        "\n\nЗапрет исходящих сломает обновления, DNS и другие подключения сервера, если не добавить разрешающие правила."
                    d.direction == "incoming" && d.policy == "allow" ->
                        "\n\nВсе входящие подключения станут разрешены, кроме явно запрещённых."
                    else -> ""
                },
            confirmText = "Применить",
            onConfirm = { vm.setDefault(d.direction, d.policy) },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun StatusCard(status: UfwStatus, busy: Boolean, onToggle: (Boolean) -> Unit) {
    SectionCard(title = "Состояние", icon = Icons.Filled.Security) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(if (status.active) StatusColors.ok else StatusColors.warn)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (status.active) "UFW включён" else "UFW выключен", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(status.version, "SSH: ${status.sshPorts.joinToString(", ")}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = status.active, onCheckedChange = { onToggle(it) }, enabled = !busy)
        }
    }
}

@Composable
private fun RulesCard(status: UfwStatus, busy: Boolean, onAdd: () -> Unit, onDelete: (UfwRule) -> Unit) {
    SectionCard(
        title = "Правила (${status.rules.size})",
        icon = Icons.AutoMirrored.Filled.List,
        trailing = {
            TextButton(onClick = onAdd, enabled = !busy) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Добавить")
            }
        },
    ) {
        if (status.rules.isEmpty()) {
            Text("Правил нет", style = MaterialTheme.typography.bodyMedium)
        }
        if (!status.active && status.rules.isNotEmpty()) {
            Text(
                "UFW выключен — правила сохранены, но не действуют.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status.rules.forEachIndexed { i, rule ->
            if (i > 0) HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                StatusDot(
                    when (rule.action) {
                        "allow" -> StatusColors.ok
                        "limit" -> StatusColors.warn
                        else -> StatusColors.bad
                    },
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(rule.spec, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    rule.comment?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onDelete(rule) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                }
            }
        }
    }
}

@Composable
private fun PoliciesCard(
    status: UfwStatus,
    busy: Boolean,
    onPolicy: (String, String) -> Unit,
    onLogging: (String) -> Unit,
) {
    SectionCard(title = "Политики по умолчанию", icon = Icons.Filled.Settings) {
        listOf(
            Triple("incoming", "Входящие", status.defaultIncoming),
            Triple("outgoing", "Исходящие", status.defaultOutgoing),
            Triple("routed", "Пересылка", status.defaultRouted),
        ).forEach { (direction, label, current) ->
            Text(label, style = MaterialTheme.typography.labelLarge)
            ChipRow {
                listOf("allow", "deny", "reject").forEach { p ->
                    FilterChip(
                        selected = current == p,
                        onClick = { if (current != p) onPolicy(direction, p) },
                        label = { Text(p) },
                        enabled = !busy,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text("Логирование", style = MaterialTheme.typography.labelLarge)
        ChipRow {
            listOf("off", "low", "medium", "high", "full").forEach { level ->
                FilterChip(
                    selected = status.logLevel == level,
                    onClick = { if (status.logLevel != level) onLogging(level) },
                    label = { Text(level) },
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
private fun AddRuleDialog(
    apps: List<String>,
    sshPorts: List<Int>,
    onDismiss: () -> Unit,
    onAdd: (UfwRuleInput) -> Unit,
) {
    var input by remember { mutableStateOf(UfwRuleInput()) }
    val error = UfwValidation.validate(input, sshPorts)
    val showError = error != null && (input.port.isNotEmpty() || input.useApp || input.from.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новое правило") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Действие", style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    listOf("allow" to "разрешить", "deny" to "запретить", "reject" to "отклонить", "limit" to "лимит").forEach { (a, label) ->
                        FilterChip(
                            selected = input.action == a,
                            onClick = { input = input.copy(action = a, direction = if (a == "limit") "in" else input.direction) },
                            label = { Text(label) },
                        )
                    }
                }
                if (!input.useApp) {
                    Text("Направление", style = MaterialTheme.typography.labelLarge)
                    ChipRow {
                        FilterChip(selected = input.direction == "in", onClick = { input = input.copy(direction = "in") }, label = { Text("входящие") })
                        FilterChip(
                            selected = input.direction == "out",
                            onClick = { input = input.copy(direction = "out") },
                            label = { Text("исходящие") },
                            enabled = input.action != "limit",
                        )
                    }
                }
                if (apps.isNotEmpty()) {
                    ChipRow {
                        FilterChip(selected = !input.useApp, onClick = { input = input.copy(useApp = false) }, label = { Text("Порт") })
                        FilterChip(
                            selected = input.useApp,
                            onClick = { input = input.copy(useApp = true, direction = "in") },
                            label = { Text("Профиль приложения") },
                        )
                    }
                }
                if (input.useApp) {
                    ChipRow {
                        apps.forEach { app ->
                            FilterChip(selected = input.app == app, onClick = { input = input.copy(app = app) }, label = { Text(app) })
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = input.port,
                        onValueChange = { v -> input = input.copy(port = v.filter { it.isDigit() || it == ',' || it == ':' }.take(80)) },
                        label = { Text("Порт, диапазон 6000:6007 или 80,443") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Text("Протокол", style = MaterialTheme.typography.labelLarge)
                    ChipRow {
                        listOf("tcp", "udp", "any").forEach { p ->
                            FilterChip(selected = input.proto == p, onClick = { input = input.copy(proto = p) }, label = { Text(p) })
                        }
                    }
                }
                OutlinedTextField(
                    value = input.from,
                    onValueChange = { input = input.copy(from = it.trim()) },
                    label = { Text("Источник (пусто — откуда угодно)") },
                    placeholder = { Text("203.0.113.5 или 10.0.0.0/8") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = input.comment,
                    onValueChange = { input = input.copy(comment = it.take(100)) },
                    label = { Text("Комментарий") },
                    singleLine = true,
                )
                Text(
                    "ufw " + UfwValidation.buildArgs(input).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showError) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = {
                onDismiss()
                onAdd(input)
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
