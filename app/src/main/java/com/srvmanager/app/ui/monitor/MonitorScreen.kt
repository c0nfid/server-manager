package com.srvmanager.app.ui.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.Prefs
import com.srvmanager.app.monitor.MonitorParser
import com.srvmanager.app.monitor.MonitorSnapshot
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ChipRow
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.LabeledValue
import com.srvmanager.app.ui.common.SectionCard
import com.srvmanager.app.ui.common.formatKb
import com.srvmanager.app.ui.common.formatRate
import com.srvmanager.app.ui.common.formatUptime
import com.srvmanager.app.ui.theme.StatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val HISTORY = 60

class MonitorViewModel(private val serverId: String) : ViewModel() {
    data class UiState(
        val snapshot: MonitorSnapshot? = null,
        val cpu: List<Float> = emptyList(),
        val mem: List<Float> = emptyList(),
        val rx: List<Float> = emptyList(),
        val tx: List<Float> = emptyList(),
        val loading: Boolean = true,
        val error: Throwable? = null,
        val paused: Boolean = false,
        val intervalSec: Int = Prefs.monitorIntervalSec,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var visible = false

    /** Опрос идёт только пока экран виден, чтобы не нагружать сервер и батарею. */
    fun setVisible(value: Boolean) {
        visible = value
        if (value && !_state.value.paused && _state.value.error == null) start() else if (!value) stop()
    }

    private fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            while (isActive) {
                poll()
                if (_state.value.error != null) break
                delay(_state.value.intervalSec * 1000L)
            }
        }
    }

    private fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun poll() {
        try {
            val result = SshManager.withConnection(serverId) {
                it.runScript(Scripts.build("monitor.sh"), timeoutMs = 30_000)
            }
            val snap = MonitorParser.parse(result.stdout)
            _state.update {
                it.copy(
                    snapshot = snap,
                    cpu = (it.cpu + snap.cpuUsage).takeLast(HISTORY),
                    mem = (it.mem + snap.memUsage).takeLast(HISTORY),
                    rx = (it.rx + snap.rxRate.toFloat()).takeLast(HISTORY),
                    tx = (it.tx + snap.txRate.toFloat()).takeLast(HISTORY),
                    loading = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // После ошибки опрос останавливается: повторные неудачные входы могут привести к бану (fail2ban).
            _state.update { it.copy(loading = false, error = e) }
        }
    }

    fun retry() {
        _state.update { it.copy(error = null, loading = it.snapshot == null) }
        if (visible) start()
    }

    fun togglePause() {
        val paused = !_state.value.paused
        _state.update { it.copy(paused = paused) }
        if (paused) stop() else if (visible) start()
    }

    fun setInterval(seconds: Int) {
        Prefs.monitorIntervalSec = seconds
        _state.update { it.copy(intervalSec = seconds) }
    }
}

@Composable
fun MonitorScreen(serverId: String, onBack: () -> Unit) {
    val vm: MonitorViewModel = viewModel(key = "monitor-$serverId") { MonitorViewModel(serverId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val server = Graph.repo.server(serverId)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.setVisible(false)
        }
    }

    AppScaffold(
        title = "Мониторинг",
        subtitle = server?.displayName,
        onBack = onBack,
        actions = {
            IconButton(onClick = { vm.togglePause() }) {
                Icon(
                    if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (state.paused) "Продолжить" else "Пауза",
                )
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
            ChipRow {
                listOf(2, 3, 5, 10, 30).forEach { sec ->
                    FilterChip(
                        selected = state.intervalSec == sec,
                        onClick = { vm.setInterval(sec) },
                        label = { Text("$sec с") },
                    )
                }
            }
            if (state.loading && state.snapshot == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Сбор данных…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let { ErrorCard(it, onRetry = { vm.retry() }) }
            state.snapshot?.let { snap -> SnapshotContent(snap, state) }
        }
    }
}

@Composable
private fun SnapshotContent(snap: MonitorSnapshot, state: MonitorViewModel.UiState) {
    SectionCard(title = snap.hostname.ifBlank { "Сервер" }, icon = Icons.Filled.Computer) {
        LabeledValue("ОС", snap.os)
        LabeledValue("Ядро", snap.kernel)
        if (snap.cpuModel.isNotBlank()) LabeledValue("Процессор", "${snap.cpuModel.trim()} × ${snap.cores}")
        LabeledValue("Аптайм", formatUptime(snap.uptimeSec))
        LabeledValue(
            "Нагрузка",
            String.format(Locale.ROOT, "%.2f  %.2f  %.2f", snap.load1, snap.load5, snap.load15),
        )
        LabeledValue("Сессий", snap.users.toString())
    }

    SectionCard(title = "Процессор", icon = Icons.Filled.Speed) {
        UsageRow("Загрузка CPU", snap.cpuUsage, "${(snap.cpuUsage * 100).toInt()}%")
        Spacer(Modifier.height(8.dp))
        Sparkline(state.cpu, max = 1f, color = MaterialTheme.colorScheme.primary)
    }

    SectionCard(title = "Память", icon = Icons.Filled.Memory) {
        UsageRow("RAM", snap.memUsage, "${formatKb(snap.memUsedKb)} / ${formatKb(snap.memTotalKb)}")
        Spacer(Modifier.height(6.dp))
        Sparkline(state.mem, max = 1f, color = MaterialTheme.colorScheme.tertiary)
        if (snap.swapTotalKb > 0) {
            Spacer(Modifier.height(8.dp))
            UsageRow("Swap", snap.swapUsage, "${formatKb(snap.swapUsedKb)} / ${formatKb(snap.swapTotalKb)}")
        }
    }

    SectionCard(title = "Сеть", icon = Icons.Filled.SwapHoriz) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("↓ Входящий", style = MaterialTheme.typography.labelMedium)
                Text(formatRate(snap.rxRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("↑ Исходящий", style = MaterialTheme.typography.labelMedium)
                Text(formatRate(snap.txRate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        val peak = maxOf(state.rx.maxOrNull() ?: 0f, state.tx.maxOrNull() ?: 0f, 1f)
        Box {
            Sparkline(state.rx, max = peak, color = StatusColors.ok)
            Sparkline(state.tx, max = peak, color = StatusColors.warn, fill = false)
        }
    }

    if (snap.disks.isNotEmpty()) {
        SectionCard(title = "Диски", icon = Icons.Filled.Storage) {
            snap.disks.forEach { disk ->
                UsageRow(
                    disk.mount,
                    disk.usage,
                    "${formatKb(disk.usedKb)} / ${formatKb(disk.totalKb)}",
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (snap.processes.isNotEmpty()) {
        SectionCard(title = "Топ процессов", icon = Icons.AutoMirrored.Filled.ViewList) {
            Row {
                Text("PID", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
                Text("Процесс", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("CPU%", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                Text("MEM%", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
            }
            snap.processes.forEach { p ->
                Row {
                    Text(p.pid, Modifier.width(64.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(p.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.cpu, Modifier.width(56.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(p.mem, Modifier.width(56.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            if (state.paused) "Обновление приостановлено" else "Обновление каждые ${state.intervalSec} с",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsageRow(label: String, fraction: Float, value: String) {
    val color = when {
        fraction >= 0.9f -> StatusColors.bad
        fraction >= 0.75f -> StatusColors.warn
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
private fun Sparkline(values: List<Float>, max: Float, color: Color, fill: Boolean = true) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        if (values.size < 2 || max <= 0f) return@Canvas
        val step = size.width / (HISTORY - 1)
        val startX = size.width - step * (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = startX + i * step
            val y = size.height - (v / max).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(startX + (values.size - 1) * step, size.height)
                lineTo(startX, size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.15f))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(startX + (values.size - 1) * step, size.height - (values.last() / max).coerceIn(0f, 1f) * size.height))
    }
}
