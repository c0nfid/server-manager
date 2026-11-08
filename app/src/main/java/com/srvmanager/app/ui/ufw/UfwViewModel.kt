package com.srvmanager.app.ui.ufw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srvmanager.app.Graph
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ufw.UfwClient
import com.srvmanager.app.ufw.UfwRule
import com.srvmanager.app.ufw.UfwRuleInput
import com.srvmanager.app.ufw.UfwStatus
import com.srvmanager.app.ui.common.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UfwViewModel(serverId: String) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val busy: String? = null,
        val status: UfwStatus? = null,
        val error: Throwable? = null,
        val steps: List<Step> = emptyList(),
    )

    private val client = UfwClient(serverId)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val status = client.status()
                _state.update { it.copy(loading = false, status = status) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    /** Одна операция за раз; выполняется в области приложения и не обрывается при уходе с экрана. */
    private fun operation(title: String, block: suspend () -> ExecResult) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = title, steps = listOf(Step(title, null))) }
        Graph.appScope.launch {
            val step = try {
                val r = block()
                Step(title, r.ok, r.combined.trim())
            } catch (e: Exception) {
                Step(title, false, e.friendlyMessage())
            }
            _state.update { it.copy(busy = null, steps = listOf(step)) }
            refresh()
        }
    }

    fun install() = operation("Установка UFW") { client.install() }
    fun enable() = operation("Включение UFW (SSH разрешается автоматически)") { client.enable() }
    fun disable() = operation("Выключение UFW") { client.disable() }
    fun add(input: UfwRuleInput) = operation("Добавление правила") { client.add(input) }
    fun delete(rule: UfwRule) = operation("Удаление правила: ${rule.spec}") { client.delete(rule) }
    fun setDefault(direction: String, policy: String) =
        operation("Политика по умолчанию: $policy ($direction)") { client.setDefault(direction, policy) }
    fun setLogging(level: String) = operation("Логирование: $level") { client.setLogging(level) }
    fun reset() = operation("Сброс UFW") { client.reset() }
    fun routePortBind() = operation("Разрешение пересылки для PortBind") { client.routePortBind() }
}
