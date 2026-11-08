package com.srvmanager.app.ui.procs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srvmanager.app.Graph
import com.srvmanager.app.procs.ProcClient
import com.srvmanager.app.procs.ProcessInfo
import com.srvmanager.app.procs.ServiceDetails
import com.srvmanager.app.procs.ServiceInfo
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.Step
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProcessesViewModel(private val serverId: String) : ViewModel() {
    enum class Mode { PROCESSES, SERVICES }

    data class UiState(
        val mode: Mode = Mode.PROCESSES,
        val loading: Boolean = false,
        val error: Throwable? = null,
        val me: String = "",
        val processes: List<ProcessInfo> = emptyList(),
        val processesAsRoot: Boolean = false,
        val services: List<ServiceInfo>? = null,
        val noSystemd: Boolean = false,
        val busy: String? = null,
        val lastStep: Step? = null,
        val selectedService: ServiceInfo? = null,
        val details: ServiceDetails? = null,
        val detailsLoading: Boolean = false,
        val detailsError: String? = null,
    )

    private val client = ProcClient(serverId)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun setMode(mode: Mode) {
        _state.update { it.copy(mode = mode, error = null) }
        val s = _state.value
        if ((mode == Mode.SERVICES && s.services == null && !s.noSystemd) ||
            (mode == Mode.PROCESSES && s.processes.isEmpty())
        ) {
            refresh()
        }
    }

    fun setProcessesAsRoot(value: Boolean) {
        _state.update { it.copy(processesAsRoot = value) }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                if (_state.value.mode == Mode.PROCESSES) {
                    val list = client.processes(_state.value.processesAsRoot)
                    _state.update { it.copy(loading = false, me = list.me, processes = list.processes) }
                } else {
                    val services = client.services()
                    _state.update { it.copy(loading = false, services = services ?: emptyList(), noSystemd = services == null) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun operation(title: String, afterwards: () -> Unit, block: suspend () -> com.srvmanager.app.ssh.ExecResult) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = title, lastStep = Step(title, null)) }
        Graph.appScope.launch {
            val step = try {
                val r = block()
                Step(title, r.ok, r.combined.trim())
            } catch (e: Exception) {
                Step(title, false, e.friendlyMessage())
            }
            _state.update { it.copy(busy = null, lastStep = step) }
            afterwards()
        }
    }

    fun dismissStep() = _state.update { it.copy(lastStep = null) }

    /** Свои процессы останавливаются без root, чужие — через выбранный способ получения root. */
    fun signal(process: ProcessInfo, signal: String) {
        val server = Graph.repo.server(serverId)
        // Сравниваем с пользователем входа: при списке «от root» `id -un` вернул бы root.
        val own = server != null && !server.isRoot && process.user == server.username.trim()
        operation("kill -$signal ${process.pid} (${process.name})", afterwards = { refresh() }) {
            client.signal(process, signal, asRoot = !own)
        }
    }

    fun openService(service: ServiceInfo) {
        _state.update { it.copy(selectedService = service, details = null, detailsError = null) }
        loadDetails()
    }

    fun closeService() = _state.update { it.copy(selectedService = null, details = null, detailsError = null) }

    fun loadDetails() {
        val unit = _state.value.selectedService?.unit ?: return
        _state.update { it.copy(detailsLoading = true, detailsError = null) }
        viewModelScope.launch {
            try {
                val details = client.serviceDetails(unit)
                _state.update { it.copy(detailsLoading = false, details = details) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(detailsLoading = false, detailsError = e.friendlyMessage()) }
            }
        }
    }

    fun serviceAction(unit: String, action: String) {
        operation("systemctl $action $unit", afterwards = {
            refresh()
            if (_state.value.selectedService?.unit == unit) loadDetails()
        }) {
            client.serviceAction(unit, action)
        }
    }
}
