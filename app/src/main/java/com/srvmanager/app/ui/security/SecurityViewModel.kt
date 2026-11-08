package com.srvmanager.app.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srvmanager.app.Graph
import com.srvmanager.app.security.SecurityClient
import com.srvmanager.app.security.SecurityStatus
import com.srvmanager.app.ssh.SshManager
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SecurityViewModel(private val serverId: String) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val busy: String? = null,
        val status: SecurityStatus? = null,
        val error: Throwable? = null,
        val steps: List<Step> = emptyList(),
    )

    private val client = SecurityClient(serverId)
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

    private fun begin(title: String): Boolean {
        if (_state.value.busy != null) return false
        _state.update { it.copy(busy = title, steps = emptyList()) }
        return true
    }

    private fun step(text: String, ok: Boolean?, output: String = "") =
        _state.update { it.copy(steps = it.steps + Step(text, ok, output.trim())) }

    /** Заменяет последний шаг (например, «выполняется…» → результат). */
    private fun finishStep(ok: Boolean, output: String = "") = _state.update { s ->
        val last = s.steps.lastOrNull() ?: return@update s
        s.copy(steps = s.steps.dropLast(1) + last.copy(ok = ok, output = output.trim()))
    }

    private fun end() {
        _state.update { it.copy(busy = null) }
        refresh()
    }

    fun setPing(disabled: Boolean, ipv6: Boolean) {
        if (!begin(if (disabled) "Отключение ping" else "Включение ping")) return
        Graph.appScope.launch {
            step(if (disabled) "Отключение ответа на ping" else "Включение ответа на ping", null)
            try {
                val r = client.setPing(disabled, ipv6)
                finishStep(r.ok, r.combined)
            } catch (e: Exception) {
                finishStep(false, e.friendlyMessage())
            }
            end()
        }
    }

    /**
     * Безопасный перенос SSH: новый порт добавляется к старому, приложение проверяет вход
     * на новом порту и только потом убирает старый. При неудаче конфигурация откатывается.
     * Выполняется в области приложения, чтобы не оборваться на полпути при уходе с экрана.
     */
    fun changeSshPort(newPort: Int, closeOld: Boolean) {
        val server = Graph.repo.server(serverId) ?: return
        if (!begin("Перенос SSH на порт $newPort")) return
        Graph.appScope.launch {
            try {
                step("Добавление порта $newPort (старый продолжает работать)", null)
                val added = client.addSshPort(newPort)
                finishStep(added.result.ok, added.result.combined)
                if (!added.result.ok) {
                    step("Перенос отменён, SSH работает на прежнем порту", false)
                    return@launch
                }

                step("Проверка входа на порту $newPort", null)
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    delay(if (attempt == 1) 1_500 else 3_000)
                    try {
                        val test = SshManager.test(server.copy(port = newPort))
                        finishStep(true, "Вход выполнен за ${test.latencyMs} мс")
                        lastError = null
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                if (lastError != null) {
                    finishStep(
                        false,
                        "Не удалось подключиться: ${lastError.friendlyMessage()}. " +
                            "Возможно, порт закрыт файрволом провайдера (security group).",
                    )
                    step("Откат изменений", null)
                    if (added.backup.isNotEmpty()) {
                        val revert = client.revertSshPort(newPort, added.backup)
                        finishStep(revert.ok, revert.combined)
                    } else {
                        finishStep(false, "Нет пути резервной копии — проверьте /etc/ssh/sshd_config вручную")
                    }
                    return@launch
                }

                step("Отключение старого порта ${added.oldPorts.joinToString(", ")}", null)
                val fin = client.finalizeSshPort(newPort, added.oldPorts, closeOld)
                finishStep(fin.ok, fin.combined)
                if (!fin.ok) {
                    step("SSH слушает и старый, и новый порт — проверьте конфигурацию", false)
                    return@launch
                }
                Graph.repo.updateServer(serverId) { it.copy(port = newPort) }
                SshManager.disconnect(serverId)
                step("Готово: порт сервера в приложении изменён на $newPort", true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step("Ошибка: ${e.friendlyMessage()}", false)
            } finally {
                end()
            }
        }
    }
}
