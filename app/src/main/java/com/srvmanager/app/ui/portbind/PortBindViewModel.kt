package com.srvmanager.app.ui.portbind

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srvmanager.app.portbind.BackupEntry
import com.srvmanager.app.portbind.PortBindClient
import com.srvmanager.app.portbind.PortBindStatus
import com.srvmanager.app.portbind.PortRule
import com.srvmanager.app.portbind.portbindExitHint
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.friendlyMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LogEntry(
    val time: Long,
    val title: String,
    val exitCode: Int?,
    val output: String,
    val hint: String,
) {
    val ok: Boolean get() = exitCode == 0
}

class PortBindViewModel(serverId: String) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val busy: String? = null,
        val status: PortBindStatus? = null,
        val rules: List<PortRule> = emptyList(),
        val rulesError: String? = null,
        val error: Throwable? = null,
        val log: List<LogEntry> = emptyList(),
        val kernelTable: String? = null,
        val backups: List<BackupEntry>? = null,
        val backupPreview: Pair<String, List<PortRule>>? = null,
    )

    private val client = PortBindClient(serverId)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        reload()
    }

    /** Перечитывает статус установки и, если portbind установлен, список правил. */
    fun reload() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val status = client.status()
                _state.update { it.copy(status = status) }
                if (status.installed) loadRules() else _state.update { it.copy(rules = emptyList(), rulesError = null) }
                _state.update { it.copy(loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private suspend fun loadRules() {
        try {
            val rules = client.list()
            _state.update { it.copy(rules = rules, rulesError = null) }
        } catch (e: Exception) {
            _state.update { it.copy(rulesError = e.friendlyMessage()) }
        }
    }

    private fun addLog(title: String, result: ExecResult?, command: String, error: Throwable? = null) {
        val entry = if (result != null) {
            LogEntry(System.currentTimeMillis(), title, result.exitCode, result.combined, portbindExitHint(result.exitCode, command))
        } else {
            LogEntry(System.currentTimeMillis(), title, null, error?.friendlyMessage().orEmpty(), "Команда не выполнена")
        }
        _state.update { it.copy(log = (listOf(entry) + it.log).take(30)) }
    }

    /**
     * Выполняет изменяющую операцию: одна за раз, с записью в журнал
     * и последующим обновлением статуса/правил.
     */
    private fun operation(
        title: String,
        command: String,
        reloadStatus: Boolean = false,
        block: suspend () -> ExecResult,
    ) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = title) }
        viewModelScope.launch {
            try {
                val result = block()
                addLog(title, result, command)
            } catch (e: Exception) {
                addLog(title, null, command, e)
            }
            try {
                if (reloadStatus) {
                    val status = client.status()
                    _state.update { it.copy(status = status) }
                }
                if (_state.value.status?.installed == true) loadRules()
            } catch (e: Exception) {
                // Ошибка обновления уже видна в журнале.
            }
            _state.update { it.copy(busy = null) }
        }
    }

    fun install() = operation("Установка portbind ${PortBindClient.bundledVersion}", "install", reloadStatus = true) {
        client.install()
    }

    fun installDependencies() = operation("Установка зависимостей", "deps", reloadStatus = true) {
        client.installDependencies()
    }

    fun setService(enabled: Boolean) = operation(
        if (enabled) "Включение автозапуска (systemd)" else "Отключение автозапуска",
        "service",
        reloadStatus = true,
    ) { client.setService(enabled) }

    fun setCron(minutes: Int) = operation(
        if (minutes == 0) "Удаление расписания refresh" else "Расписание refresh каждые $minutes мин",
        "cron",
        reloadStatus = true,
    ) { client.setCron(minutes) }

    fun add(inPort: Int, target: String, outPort: Int, protocol: String, bind: String) =
        operation("add $inPort → $target:$outPort/$protocol", "add") {
            client.add(inPort, target, outPort, protocol, bind)
        }

    fun delete(rule: PortRule) = operation("delete ${rule.inPort}", "delete") { client.delete(rule.inPort) }

    /**
     * Изменение записи = delete + add (атомарного update в portbind 1.0.0 нет).
     * Если add не удался, пытаемся вернуть прежнюю запись.
     */
    fun replace(old: PortRule, inPort: Int, target: String, outPort: Int, protocol: String, bind: String) =
        operation("Изменение ${old.inPort}", "add") {
            val deleted = client.delete(old.inPort)
            if (!deleted.ok) return@operation deleted
            val added = client.add(inPort, target, outPort, protocol, bind)
            if (added.ok) {
                added
            } else {
                val restored = client.add(old.inPort, old.targetHost, old.targetPort, old.protocol, old.bindHost)
                added.copy(
                    stderr = added.stderr + "\n" + if (restored.ok) {
                        "Прежняя запись ${old.inPort} восстановлена."
                    } else {
                        "ВНИМАНИЕ: не удалось восстановить прежнюю запись ${old.inPort}: ${restored.combined}"
                    },
                )
            }
        }

    fun refreshDns(domain: String?) =
        operation(if (domain.isNullOrBlank()) "refresh (все домены)" else "refresh $domain", "refresh") {
            client.refresh(domain)
        }

    fun apply() = operation("apply", "apply") { client.apply() }

    fun reset() = operation("reset --yes", "reset") { client.reset() }

    fun loadKernelTable() {
        viewModelScope.launch {
            try {
                val r = client.kernelTable()
                _state.update { it.copy(kernelTable = if (r.ok) r.stdout else r.combined.ifBlank { "Таблица ip portbind отсутствует" }) }
            } catch (e: Exception) {
                _state.update { it.copy(kernelTable = e.friendlyMessage()) }
            }
        }
    }

    fun hideKernelTable() = _state.update { it.copy(kernelTable = null) }

    fun loadBackups() {
        viewModelScope.launch {
            try {
                val list = client.backups()
                _state.update { it.copy(backups = list) }
            } catch (e: Exception) {
                addLog("Список резервных копий", null, "backups", e)
            }
        }
    }

    fun hideBackups() = _state.update { it.copy(backups = null, backupPreview = null) }

    fun previewBackup(name: String) {
        viewModelScope.launch {
            try {
                val rules = client.backupRules(name)
                _state.update { it.copy(backupPreview = name to rules) }
            } catch (e: Exception) {
                addLog("Просмотр копии $name", null, "backups", e)
            }
        }
    }

    fun closePreview() = _state.update { it.copy(backupPreview = null) }

    private suspend fun refreshBackupsList() {
        val list = runCatching { client.backups() }.getOrNull()
        if (list != null) _state.update { it.copy(backups = list) }
    }

    fun restoreBackup(name: String) = operation("Восстановление из $name", "apply") {
        client.restoreBackup(name).also { refreshBackupsList() }
    }

    fun pruneBackups(keep: Int) = operation("Удаление старых копий (оставить $keep)", "prune") {
        client.pruneBackups(keep).also { refreshBackupsList() }
    }
}
