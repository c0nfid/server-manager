package com.srvmanager.app.ui.docker

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.srvmanager.app.docker.ComposeProject
import com.srvmanager.app.docker.Container
import com.srvmanager.app.docker.DockerClient
import com.srvmanager.app.docker.DockerProbe
import com.srvmanager.app.docker.DockerValidation
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ConfirmDialog
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.LabeledValue
import com.srvmanager.app.ui.common.OutputBlock
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.StatusDot
import com.srvmanager.app.ui.common.StepsCard
import com.srvmanager.app.ui.common.TextInputDialog
import com.srvmanager.app.ui.theme.StatusColors

private data class Confirm(val title: String, val text: String, val destructive: Boolean, val action: () -> Unit)

@Composable
fun DockerScreen(serverId: String, onBack: () -> Unit, onOpenShell: (String) -> Unit) {
    val vm: DockerViewModel = viewModel(key = "docker-$serverId") { DockerViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    val busy = state.busy != null
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    var selectedProject by remember { mutableStateOf<ComposeProject?>(null) }
    var pullDialog by remember { mutableStateOf(false) }
    var installDialog by remember { mutableStateOf(false) }

    val detail = state.detail
    BackHandler(enabled = detail != null) { vm.closeDetail() }

    AppScaffold(
        title = detail?.title ?: "Docker",
        subtitle = server?.displayName,
        onBack = { if (detail != null) vm.closeDetail() else onBack() },
        actions = {
            if (detail == null) {
                IconButton(onClick = { if (state.probe?.agentInstalled == true) vm.refresh() else vm.probe() }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
            }
        },
    ) { padding ->
        if (detail != null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (detail.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    OutputBlock(detail.text ?: "Загрузка…")
                }
            }
        } else LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.probing || state.loading || busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            state.lastStep?.let { step ->
                item {
                    Column {
                        StepsCard(listOf(step), title = "Последнее действие")
                        if (step.ok != null) TextButton(onClick = { vm.dismissStep() }) { Text("Скрыть") }
                    }
                }
            }
            state.probeError?.let { e -> item { ErrorCard(e, onRetry = { vm.probe() }) } }
            val probe = state.probe
            if (probe != null) {
                item {
                    SetupCard(
                        probe = probe,
                        useRoot = vm.client.useRoot,
                        busy = busy,
                        onInstallDocker = { installDialog = true },
                        onInstallAgent = { vm.installAgent() },
                        onAddToGroup = {
                            confirm = Confirm(
                                "Добавить ${probe.user} в группу docker?",
                                "Команды Docker будут работать без root. Учтите: группа docker фактически даёт права root " +
                                    "на сервере. SSH-сессия переподключится, чтобы группа применилась.",
                                false,
                            ) { vm.addToGroup(probe.user) }
                        },
                    )
                }
            }
            if (probe != null && probe.agentInstalled && probe.dockerInstalled) {
                item {
                    ChipRow {
                        DockerViewModel.Tab.entries.forEach { tab ->
                            FilterChip(selected = state.tab == tab, onClick = { vm.setTab(tab) }, label = { Text(tab.title) })
                        }
                    }
                }
                state.error?.let { e -> item { ErrorCard(e, onRetry = { vm.refresh() }) } }
                when (state.tab) {
                    DockerViewModel.Tab.CONTAINERS -> {
                        if (state.containers.isEmpty() && !state.loading) item { EmptyText("Контейнеров нет") }
                        items(state.containers, key = { it.id }) { c ->
                            ContainerRow(c, state.stats[c.name], onClick = { selectedContainer = c })
                        }
                    }
                    DockerViewModel.Tab.IMAGES -> {
                        item {
                            ChipRow {
                                Button(onClick = { pullDialog = true }, enabled = !busy) { Text("Скачать образ") }
                                OutlinedButton(onClick = {
                                    confirm = Confirm("Удалить «висячие» образы?", "docker image prune -f — удаляет образы без тега, не используемые контейнерами.", false) {
                                        vm.prune("images")
                                    }
                                }, enabled = !busy) { Text("Очистить <none>") }
                                OutlinedButton(onClick = {
                                    confirm = Confirm("Удалить все неиспользуемые образы?", "docker image prune -a -f — удаляет все образы, не используемые контейнерами. Их придётся скачивать заново.", true) {
                                        vm.prune("images-all")
                                    }
                                }, enabled = !busy) { Text("Все неиспользуемые") }
                            }
                        }
                        if (state.images.isEmpty() && !state.loading) item { EmptyText("Образов нет") }
                        items(state.images, key = { it.id + it.repository + it.tag }) { image ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(image.title, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${image.size} · ${image.createdSince}" + if (image.containers.isNotBlank() && image.containers != "N/A") " · контейнеров: ${image.containers}" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    confirm = Confirm("Удалить образ?", "docker rmi ${image.ref}", true) { vm.removeImage(image) }
                                }, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = "Удалить") }
                            }
                            HorizontalDivider()
                        }
                    }
                    DockerViewModel.Tab.COMPOSE -> {
                        if (probe.composeKind != "v2") {
                            item { EmptyText("Нужен Docker Compose v2 (команда «docker compose»). Сейчас: ${probe.composeKind}.") }
                        } else {
                            if (state.projects.isEmpty() && !state.loading) item { EmptyText("Compose-проектов нет") }
                            items(state.projects, key = { it.name }) { p ->
                                Column(Modifier.fillMaxWidth().clickable { selectedProject = p }.padding(vertical = 6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusDot(if (p.status.startsWith("running")) StatusColors.ok else MaterialTheme.colorScheme.outline)
                                        Spacer(Modifier.width(8.dp))
                                        Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        Text(p.status, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(p.configFiles, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    DockerViewModel.Tab.STORAGE -> {
                        item {
                            ChipRow {
                                OutlinedButton(onClick = {
                                    confirm = Confirm("Удалить неиспользуемые тома?", "docker volume prune -f — данные в удалённых томах будут потеряны безвозвратно.", true) {
                                        vm.prune("volumes")
                                    }
                                }, enabled = !busy) { Text("Очистить тома") }
                                OutlinedButton(onClick = {
                                    confirm = Confirm("Удалить неиспользуемые сети?", "docker network prune -f", false) { vm.prune("networks") }
                                }, enabled = !busy) { Text("Очистить сети") }
                                OutlinedButton(onClick = {
                                    confirm = Confirm("Удалить остановленные контейнеры?", "docker container prune -f", true) { vm.prune("containers") }
                                }, enabled = !busy) { Text("Остановленные контейнеры") }
                            }
                        }
                        item { Text("Тома (${state.volumes.size})", style = MaterialTheme.typography.titleMedium) }
                        items(state.volumes, key = { "v-" + it.name }) { v ->
                            Column {
                                Text(v.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${v.driver} · ${v.mountpoint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { Text("Сети (${state.networks.size})", style = MaterialTheme.typography.titleMedium) }
                        items(state.networks, key = { "n-" + it.id }) { n ->
                            Column {
                                Text(n.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                Text("${n.driver} · ${n.scope} · ${n.id.take(12)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    selectedContainer?.let { c ->
        ContainerDialog(
            container = c,
            stats = state.stats[c.name],
            busy = busy,
            onDismiss = { selectedContainer = null },
            onAction = { action ->
                selectedContainer = null
                val destructive = action == "rm" || action == "rm-force" || action == "kill"
                if (destructive) {
                    confirm = Confirm("docker $action ${c.name}?", if (action.startsWith("rm")) "Контейнер будет удалён. Данные вне томов пропадут." else "Процессы контейнера будут убиты сигналом KILL.", true) {
                        vm.containerAction(action, c)
                    }
                } else {
                    vm.containerAction(action, c)
                }
            },
            onLogs = { selectedContainer = null; vm.showLogs(c) },
            onInspect = { selectedContainer = null; vm.showInspect(c) },
            onShell = {
                selectedContainer = null
                if (server != null) onOpenShell(DockerClient.execCommand(server, c.name, vm.client.useRoot))
            },
        )
    }

    selectedProject?.let { p ->
        AlertDialog(
            onDismissRequest = { selectedProject = null },
            title = { Text(p.name) },
            text = {
                Column {
                    LabeledValue("Состояние", p.status)
                    LabeledValue("Файлы", p.configFiles, monospace = true)
                    ChipRow {
                        listOf("up" to "up -d", "pull" to "pull", "restart" to "restart", "stop" to "stop", "start" to "start").forEach { (action, label) ->
                            OutlinedButton(onClick = { selectedProject = null; vm.compose(action, p) }, enabled = !busy) { Text(label) }
                        }
                    }
                    ChipRow {
                        OutlinedButton(onClick = { selectedProject = null; vm.showComposeLogs(p) }) { Text("Логи") }
                        OutlinedButton(onClick = {
                            selectedProject = null
                            confirm = Confirm("docker compose down (${p.name})?", "Контейнеры и сети проекта будут остановлены и удалены (тома сохраняются).", true) {
                                vm.compose("down", p)
                            }
                        }, enabled = !busy) { Text("down", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedProject = null }) { Text("Закрыть") } },
        )
    }

    if (pullDialog) {
        TextInputDialog(
            title = "Скачать образ",
            label = "Например nginx:latest",
            confirmText = "Скачать",
            validate = { if (DockerValidation.isRef(it.trim())) null else "Имя образа: repo:tag" },
            onConfirm = { vm.pull(it.trim()) },
            onDismiss = { pullDialog = false },
        )
    }

    if (installDialog) {
        val user = state.probe?.user.orEmpty()
        var addUser by remember { mutableStateOf(user.isNotEmpty() && user != "root") }
        AlertDialog(
            onDismissRequest = { installDialog = false },
            title = { Text("Установить Docker?") },
            text = {
                Column {
                    Text(
                        "Будет выполнен официальный скрипт установки https://get.docker.com от root. " +
                            "Он добавит репозиторий Docker и установит Docker Engine и Compose v2. Это может занять несколько минут.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (user.isNotEmpty() && user != "root") {
                        Row(Modifier.clickable { addUser = !addUser }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = addUser, onCheckedChange = { addUser = it })
                            Text("Добавить $user в группу docker")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    installDialog = false
                    vm.installDocker(if (addUser) user else null)
                }) { Text("Установить") }
            },
            dismissButton = { TextButton(onClick = { installDialog = false }) { Text("Отмена") } },
        )
    }

    confirm?.let { c ->
        ConfirmDialog(
            title = c.title,
            text = c.text,
            confirmText = "Выполнить",
            destructive = c.destructive,
            onConfirm = c.action,
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SetupCard(
    probe: DockerProbe,
    useRoot: Boolean,
    busy: Boolean,
    onInstallDocker: () -> Unit,
    onInstallAgent: () -> Unit,
    onAddToGroup: () -> Unit,
) {
    SectionCard(title = "Docker") {
        if (!probe.dockerInstalled) {
            Text("Docker на сервере не установлен.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(4.dp))
            Button(onClick = onInstallDocker, enabled = !busy) { Text("Установить Docker") }
        } else if (!probe.agentInstalled) {
            Text(
                "Для работы нужен модуль srvmgr-docker: небольшой скрипт в /usr/local/bin, " +
                    "через который приложение получает данные Docker. Устанавливается от root с проверкой SHA-256.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onInstallAgent, enabled = !busy) { Text("Установить модуль ${DockerClient.bundledVersion}") }
        } else {
            InstalledInfo(probe, useRoot, busy, onInstallAgent, onAddToGroup)
        }
    }
}

@Composable
private fun InstalledInfo(
    probe: DockerProbe,
    useRoot: Boolean,
    busy: Boolean,
    onInstallAgent: () -> Unit,
    onAddToGroup: () -> Unit,
) {
    Column {
        LabeledValue("Docker", probe.serverVersion ?: probe.clientVersion ?: "?")
        LabeledValue(
            "Compose",
            when (probe.composeKind) {
                "v2" -> "v2 ${probe.composeVersion.orEmpty()}"
                "v1" -> "v1 ${probe.composeVersion.orEmpty()} (нужен v2)"
                else -> "не установлен"
            },
        )
        LabeledValue("Доступ", if (useRoot) "через root (${probe.user} не в группе docker)" else "пользователь ${probe.user}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Модуль: ${probe.agentVersion ?: "?"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (probe.agentVersion != DockerClient.bundledVersion) {
                TextButton(onClick = onInstallAgent, enabled = !busy) { Text("Обновить до ${DockerClient.bundledVersion}") }
            }
        }
        if (useRoot && probe.user.isNotEmpty() && probe.user != "root") {
            TextButton(onClick = onAddToGroup, enabled = !busy) { Text("Добавить ${probe.user} в группу docker") }
        }
    }
}

@Composable
private fun containerColor(c: Container): Color = when {
    c.state == "running" -> StatusColors.ok
    c.state == "paused" || c.state == "restarting" -> StatusColors.warn
    c.state == "dead" || (c.state == "exited" && !c.status.contains("(0)")) -> StatusColors.bad
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun ContainerRow(c: Container, stats: com.srvmanager.app.docker.ContainerStats?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(containerColor(c))
            Spacer(Modifier.width(8.dp))
            Text(
                c.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (stats != null) Text("${stats.cpu} · ${stats.memPerc}", style = MaterialTheme.typography.labelMedium)
        }
        Text(c.image, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(c.status, c.ports.takeIf { it.isNotBlank() }, c.composeProject?.let { "compose: $it" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ContainerDialog(
    container: Container,
    stats: com.srvmanager.app.docker.ContainerStats?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onLogs: () -> Unit,
    onInspect: () -> Unit,
    onShell: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(container.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                LabeledValue("Образ", container.image, monospace = true)
                LabeledValue("Состояние", "${container.state} · ${container.status}")
                if (container.ports.isNotBlank()) LabeledValue("Порты", container.ports, monospace = true)
                container.composeProject?.let { LabeledValue("Compose", "$it / ${container.composeService.orEmpty()}") }
                if (stats != null) {
                    LabeledValue("CPU", stats.cpu)
                    LabeledValue("Память", "${stats.memUsage} (${stats.memPerc})")
                    LabeledValue("Сеть", stats.netIo)
                    LabeledValue("Диск", stats.blockIo)
                    LabeledValue("Процессов", stats.pids)
                }
                LabeledValue("Команда", container.command, monospace = true)
                LabeledValue("ID", container.id.take(12), monospace = true)
                ChipRow {
                    if (container.running) {
                        OutlinedButton(onClick = onShell) { Text("Shell") }
                        OutlinedButton(onClick = { onAction("restart") }, enabled = !busy) { Text("restart") }
                        OutlinedButton(onClick = { onAction("stop") }, enabled = !busy) { Text("stop") }
                        OutlinedButton(onClick = { onAction("pause") }, enabled = !busy) { Text("pause") }
                        OutlinedButton(onClick = { onAction("kill") }, enabled = !busy) { Text("kill") }
                    } else if (container.state == "paused") {
                        OutlinedButton(onClick = { onAction("unpause") }, enabled = !busy) { Text("unpause") }
                    } else {
                        OutlinedButton(onClick = { onAction("start") }, enabled = !busy) { Text("start") }
                    }
                }
                ChipRow {
                    OutlinedButton(onClick = onLogs) { Text("Логи") }
                    OutlinedButton(onClick = onInspect) { Text("inspect") }
                    OutlinedButton(onClick = { onAction(if (container.running) "rm-force" else "rm") }, enabled = !busy) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}
