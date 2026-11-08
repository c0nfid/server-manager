package com.srvmanager.app.procs

import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

data class ProcessInfo(
    val pid: Int,
    val ppid: Int,
    val user: String,
    val cpu: Double,
    val mem: Double,
    val rssKb: Long,
    val elapsedSec: Long,
    val stat: String,
    val args: String,
) {
    /** Короткое имя: «[kworker/0:1]» для потоков ядра, иначе имя программы без пути. */
    val name: String
        get() = if (args.startsWith("[")) {
            args
        } else {
            args.substringBefore(' ').substringAfterLast('/').trimEnd(':').ifEmpty { args }
        }

    val isKernelThread: Boolean get() = args.startsWith("[")
}

data class ProcessList(val me: String, val processes: List<ProcessInfo>)

data class ServiceInfo(
    val unit: String,
    /** loaded / not-found / masked; пусто — unit-файл не загружен */
    val load: String,
    /** active / inactive / failed / activating … */
    val active: String,
    /** running / exited / dead … */
    val sub: String,
    val description: String,
    /** enabled / disabled / static / masked / indirect …; пусто — неизвестно */
    val fileState: String,
) {
    val name: String get() = unit.removeSuffix(".service")
}

data class ServiceDetails(val status: String, val log: String)

object ProcValidation {
    val SIGNALS = listOf("TERM", "KILL", "HUP", "INT", "STOP", "CONT")
    private val unitPattern = Regex("^[A-Za-z0-9@._:-]+\\.service$")

    fun isUnit(name: String) = unitPattern.matches(name)

    /** Службы, остановка которых может отрезать доступ к серверу. */
    fun isCritical(unit: String): Boolean {
        val n = unit.removeSuffix(".service")
        return n == "ssh" || n == "sshd" || n == "dbus" || n == "networking" || n == "NetworkManager" ||
            n.startsWith("systemd-") || n == "ufw" || n == "firewalld"
    }
}

class ProcClient(private val serverId: String) {

    private suspend fun run(script: String, asRoot: Boolean, timeoutMs: Long = 60_000): ExecResult =
        SshManager.withConnection(serverId) { it.runScript(script, asRoot = asRoot, timeoutMs = timeoutMs) }

    suspend fun processes(asRoot: Boolean): ProcessList {
        val r = run(Scripts.build("proc_list.sh"), asRoot)
        if (!r.stdout.contains("@@END")) throw IOException(r.combined.ifBlank { "Нет ответа от сервера" })
        val lines = r.stdout.lines()
        val me = lines.dropWhile { it.trim() != "@@ME" }.drop(1).firstOrNull()?.trim().orEmpty()
        val ps = lines.dropWhile { it.trim() != "@@PS" }.drop(1).takeWhile { it.trim() != "@@END" }
        ps.firstOrNull { it.startsWith("ERROR:") }?.let { throw IOException(it.removePrefix("ERROR:").trim()) }
        return ProcessList(me, ps.mapNotNull { parseProcess(it) })
    }

    /** Свои процессы — без root; чужие — через выбранный способ получения root. */
    suspend fun signal(process: ProcessInfo, signal: String, asRoot: Boolean): ExecResult {
        require(signal in ProcValidation.SIGNALS) { "Неверный сигнал" }
        val script = Scripts.build(
            "proc_signal.sh",
            mapOf("PID" to process.pid.toString(), "SIG" to signal, "EXPECT" to process.args),
        )
        return withContext(NonCancellable) { run(script, asRoot, 30_000) }
    }

    /** null — на сервере нет systemd. */
    suspend fun services(): List<ServiceInfo>? {
        val r = run(Scripts.build("svc_list.sh"), asRoot = false)
        if (!r.stdout.contains("@@END")) throw IOException(r.combined.ifBlank { "Нет ответа от сервера" })
        if (r.stdout.contains("@@NOSYSTEMD")) return null
        return parseServices(r.stdout)
    }

    suspend fun serviceAction(unit: String, action: String): ExecResult {
        require(ProcValidation.isUnit(unit)) { "Недопустимое имя службы" }
        require(action in listOf("start", "stop", "restart", "reload", "enable", "disable", "enable-now", "disable-now"))
        val script = Scripts.build("svc_action.sh", mapOf("UNIT" to unit, "ACTION" to action))
        return withContext(NonCancellable) { run(script, asRoot = true, timeoutMs = 120_000) }
    }

    suspend fun serviceDetails(unit: String, lines: Int = 100): ServiceDetails {
        require(ProcValidation.isUnit(unit)) { "Недопустимое имя службы" }
        val r = run(Scripts.build("svc_status.sh", mapOf("UNIT" to unit, "LINES" to lines.toString())), asRoot = true)
        val out = r.stdout
        val status = out.substringAfter("@@STATUS", "").substringBefore("@@LOG").trim('\n', ' ')
        val log = out.substringAfter("@@LOG", "").substringBefore("@@END").trim('\n', ' ')
        if (status.isEmpty() && log.isEmpty()) throw IOException(r.combined.ifBlank { "Нет ответа от сервера" })
        return ServiceDetails(status, log)
    }

    companion object {
        private val ws = Regex("\\s+")

        fun parseProcess(line: String): ProcessInfo? {
            val p = line.trim().split(ws, limit = 9)
            if (p.size < 9) return null
            val pid = p[0].toIntOrNull() ?: return null
            return ProcessInfo(
                pid = pid,
                ppid = p[1].toIntOrNull() ?: 0,
                user = p[2],
                cpu = p[3].toDoubleOrNull() ?: 0.0,
                mem = p[4].toDoubleOrNull() ?: 0.0,
                rssKb = p[5].toLongOrNull() ?: 0L,
                elapsedSec = p[6].toLongOrNull() ?: 0L,
                stat = p[7],
                args = p[8].trim(),
            )
        }

        fun parseServices(output: String): List<ServiceInfo> {
            val units = LinkedHashMap<String, ServiceInfo>()
            val files = HashMap<String, String>()
            var section = ""
            for (raw in output.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("@@") -> section = line
                    line.isEmpty() -> Unit
                    section == "@@UNITS" -> {
                        val p = line.split(ws, limit = 5)
                        if (p.size >= 4 && p[0].endsWith(".service")) {
                            units[p[0]] = ServiceInfo(p[0], p[1], p[2], p[3], p.getOrNull(4).orEmpty(), "")
                        }
                    }
                    section == "@@FILES" -> {
                        val p = line.split(ws)
                        if (p.size >= 2 && p[0].endsWith(".service")) files[p[0]] = p[1]
                    }
                }
            }
            val merged = units.values.map { it.copy(fileState = files[it.unit].orEmpty()) }.toMutableList()
            // Unit-файлы, которые сейчас не загружены (например, отключённые службы), кроме шаблонов name@.service.
            files.filterKeys { it !in units && !it.contains("@.") }.forEach { (unit, state) ->
                merged += ServiceInfo(unit, "", "inactive", "dead", "", state)
            }
            return merged.sortedBy { it.unit.lowercase() }
        }
    }
}
