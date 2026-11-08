package com.srvmanager.app.ui.procs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.procs.ProcValidation
import com.srvmanager.app.procs.ProcessInfo
import com.srvmanager.app.procs.ServiceInfo
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.LabeledValue
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.StatusDot
import com.srvmanager.app.ui.common.StepsCard
import com.srvmanager.app.ui.common.formatKb
import com.srvmanager.app.ui.common.formatUptime
import com.srvmanager.app.ui.theme.StatusColors
import java.util.Locale

private enum class ProcSort(val title: String) { CPU("CPU"), MEM("RAM"), PID("PID"), NAME("Имя") }

private enum class SvcFilter(val title: String) { ALL("Все"), RUNNING("Работают"), FAILED("Ошибки"), ENABLED("Включены"), DISABLED("Отключены") }

private data class PendingAction(val unit: String, val action: String)

@Composable
fun ProcessesScreen(serverId: String, onBack: () -> Unit) {
    val vm: ProcessesViewModel = viewModel(key = "procs-$serverId") { ProcessesViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    var selectedProcess by remember { mutableStateOf<ProcessInfo?>(null) }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val service = state.selectedService
    BackHandler(enabled = service != null) { vm.closeService() }

    AppScaffold(
        title = service?.name ?: "Процессы и службы",
        subtitle = if (service != null) service.description.ifBlank { service.unit } else server?.displayName,
        onBack = { if (service != null) vm.closeService() else onBack() },
        actions = {
            IconButton(onClick = { if (service != null) vm.loadDetails() else vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || state.busy != null || state.detailsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.lastStep?.let {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    StepsCard(listOf(it), title = "Последнее действие")
                    if (it.ok != null) TextButton(onClick = { vm.dismissStep() }) { Text("Скрыть") }
                }
            }
            if (service != null) {
                ServiceDetail(
                    service = state.services?.firstOrNull { it.unit == service.unit } ?: service,
                    state = state,
                    onAction = { action -> pendingAction = PendingAction(service.unit, action) },
                )
            } else {
                ChipRow {
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = state.mode == ProcessesViewModel.Mode.PROCESSES,
                        onClick = { vm.setMode(ProcessesViewModel.Mode.PROCESSES) },
                        label = { Text("Процессы") },
                    )
                    FilterChip(
                        selected = state.mode == ProcessesViewModel.Mode.SERVICES,
                        onClick = { vm.setMode(ProcessesViewModel.Mode.SERVICES) },
                        label = { Text("Службы") },
                    )
                }
                state.error?.let { ErrorCard(it, onRetry = { vm.refresh() }, modifier = Modifier.padding(16.dp)) }
                if (state.mode == ProcessesViewModel.Mode.PROCESSES) {
                    ProcessList(state, server?.username?.trim().orEmpty(), vm, onSelect = { selectedProcess = it })
                } else {
                    ServiceList(state, onSelect = { vm.openService(it) })
                }
            }
        }
    }

    selectedProcess?.let { p ->
        ProcessDialog(
            process = p,
            busy = state.busy != null,
            onDismiss = { selectedProcess = null },
            onSignal = { sig ->
                selectedProcess = null
                vm.signal(p, sig)
            },
        )
    }

    pendingAction?.let { a ->
        val critical = ProcValidation.isCritical(a.unit) && a.action in listOf("stop", "disable", "disable-now", "restart")
        ConfirmDialog(
            title = "systemctl ${a.action} ${a.unit}?",
            text = if (critical) {
                "Это системная служба. Её остановка или отключение может оборвать доступ к серверу или сеть. Продолжить?"
            } else {
                "Команда будет выполнена от root."
            },
            confirmText = "Выполнить",
            destructive = critical,
            onConfirm = { vm.serviceAction(a.unit, a.action) },
            onDismiss = { pendingAction = null },
        )
    }
}

@Composable
private fun ProcessList(state: ProcessesViewModel.UiState, login: String, vm: ProcessesViewModel, onSelect: (ProcessInfo) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ProcSort.CPU) }
    var onlyMine by rememberSaveable { mutableStateOf(false) }
    var hideKernel by rememberSaveable { mutableStateOf(true) }

    val q = query.trim()
    val list = state.processes
        .filter { !hideKernel || !it.isKernelThread }
        .filter { !onlyMine || it.user == login }
        .filter { q.isEmpty() || it.args.contains(q, ignoreCase = true) || it.user.contains(q, ignoreCase = true) || it.pid.toString() == q }
        .let { l ->
            when (sort) {
                ProcSort.CPU -> l.sortedByDescending { it.cpu }
                ProcSort.MEM -> l.sortedByDescending { it.rssKb }
                ProcSort.PID -> l.sortedBy { it.pid }
                ProcSort.NAME -> l.sortedBy { it.name.lowercase() }
            }
        }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("Поиск: имя, команда, пользователь, PID") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
    )
    ChipRow {
        Spacer(Modifier.width(8.dp))
        ProcSort.entries.forEach { s ->
            FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.title) })
        }
        FilterChip(selected = onlyMine, onClick = { onlyMine = !onlyMine }, label = { Text("Только $login") })
        FilterChip(selected = hideKernel, onClick = { hideKernel = !hideKernel }, label = { Text("Без потоков ядра") })
        FilterChip(selected = state.processesAsRoot, onClick = { vm.setProcessesAsRoot(!state.processesAsRoot) }, label = { Text("От root") })
    }
    Text(
        "Показано ${list.size} из ${state.processes.size}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.pid }) { p ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(p) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format(Locale.ROOT, "%.1f%%", p.cpu),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (p.cpu >= 50) StatusColors.bad else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    "PID ${p.pid} · ${p.user} · ${formatKb(p.rssKb)} · ${formatUptime(p.elapsedSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    p.args,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProcessDialog(process: ProcessInfo, busy: Boolean, onDismiss: () -> Unit, onSignal: (String) -> Unit) {
    val dangerous = process.pid == 1 || process.name == "sshd" || process.name == "systemd"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${process.name} (PID ${process.pid})") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                LabeledValue("Пользователь", process.user)
                LabeledValue("Родитель", process.ppid.toString())
                LabeledValue("CPU / RAM", String.format(Locale.ROOT, "%.1f%% / %.1f%%", process.cpu, process.mem))
                LabeledValue("Память (RSS)", formatKb(process.rssKb))
                LabeledValue("Работает", formatUptime(process.elapsedSec))
                LabeledValue("Состояние", process.stat)
                Spacer(Modifier.width(4.dp))
                OutputBlock(process.args)
                if (dangerous) {
                    Text(
                        "Это системный процесс: его завершение может оборвать SSH или остановить сервер.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    "TERM — вежливое завершение, KILL — принудительное, HUP — перечитать конфигурацию, STOP/CONT — пауза и продолжение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ChipRow {
                    ProcValidation.SIGNALS.forEach { sig ->
                        OutlinedButton(onClick = { onSignal(sig) }, enabled = !busy) {
                            Text(sig, color = if (sig == "KILL") MaterialTheme.colorScheme.error else Color.Unspecified)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun ServiceList(state: ProcessesViewModel.UiState, onSelect: (ServiceInfo) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SvcFilter.RUNNING) }

    if (state.noSystemd) {
        Text(
            "На сервере нет systemd — управление службами недоступно.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    val all = state.services.orEmpty()
    val q = query.trim()
    val list = all
        .filter {
            when (filter) {
                SvcFilter.ALL -> true
                SvcFilter.RUNNING -> it.active == "active"
                SvcFilter.FAILED -> it.active == "failed"
                SvcFilter.ENABLED -> it.fileState == "enabled"
                SvcFilter.DISABLED -> it.fileState == "disabled"
            }
        }
        .filter { q.isEmpty() || it.unit.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("Поиск службы") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
    )
    ChipRow {
        Spacer(Modifier.width(8.dp))
        SvcFilter.entries.forEach { f ->
            val count = when (f) {
                SvcFilter.FAILED -> all.count { it.active == "failed" }
                else -> null
            }
            FilterChip(
                selected = filter == f,
                onClick = { filter = f },
                label = { Text(if (count != null && count > 0) "${f.title} ($count)" else f.title) },
            )
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.unit }) { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(s) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(serviceColor(s))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.description.isNotBlank()) {
                        Text(
                            s.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "${s.active}/${s.sub}" + if (s.fileState.isNotEmpty()) "\n${s.fileState}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun serviceColor(s: ServiceInfo): Color = when (s.active) {
    "active" -> StatusColors.ok
    "failed" -> StatusColors.bad
    "activating", "deactivating", "reloading" -> StatusColors.warn
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun ServiceDetail(service: ServiceInfo, state: ProcessesViewModel.UiState, onAction: (String) -> Unit) {
    val busy = state.busy != null
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = service.unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(serviceColor(service))
                Spacer(Modifier.width(8.dp))
                Text("${service.active} (${service.sub})", style = MaterialTheme.typography.bodyLarge)
            }
            if (service.fileState.isNotEmpty()) LabeledValue("Автозапуск", service.fileState)
            if (service.load.isNotEmpty() && service.load != "loaded") LabeledValue("Загрузка", service.load)
            ChipRow {
                if (service.active == "active") {
                    OutlinedButton(onClick = { onAction("restart") }, enabled = !busy) { Text("restart") }
                    OutlinedButton(onClick = { onAction("reload") }, enabled = !busy) { Text("reload") }
                    OutlinedButton(onClick = { onAction("stop") }, enabled = !busy) { Text("stop") }
                } else {
                    OutlinedButton(onClick = { onAction("start") }, enabled = !busy) { Text("start") }
                }
            }
            ChipRow {
                when (service.fileState) {
                    "enabled" -> OutlinedButton(onClick = { onAction("disable") }, enabled = !busy) { Text("disable") }
                    "disabled" -> {
                        OutlinedButton(onClick = { onAction("enable") }, enabled = !busy) { Text("enable") }
                        OutlinedButton(onClick = { onAction("enable-now") }, enabled = !busy) { Text("enable --now") }
                    }
                    else -> Unit
                }
            }
            if (service.fileState == "static" || service.fileState == "indirect") {
                Text(
                    "Служба ${service.fileState}: запускается другими юнитами, enable/disable к ней не применяются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.detailsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.details?.let { d ->
            SectionCard(title = "systemctl status") { OutputBlock(d.status) }
            SectionCard(title = "Журнал (последние 100 строк)") { OutputBlock(d.log) }
        }
    }
}
