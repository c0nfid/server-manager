package com.srvmanager.app.portbind

import android.util.Base64
import com.srvmanager.app.Graph
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.Sh
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Запись rules.tsv (см. docs/portbind/INTEGRATION.md, «Модель данных»). */
data class PortRule(
    val inPort: Int,
    val protocol: String,
    val bindHost: String,
    val bindIp: String,
    val targetHost: String,
    val targetIp: String,
    val targetPort: Int,
) {
    val targetIsDomain: Boolean get() = !PortBindValidation.isIpv4(targetHost)
    val bindIsDomain: Boolean get() = bindHost != "*" && !PortBindValidation.isIpv4(bindHost)
}

data class PortBindStatus(
    val installedVersion: String?,
    val bashVersion: String?,
    val missingDeps: List<String>,
    val systemdAvailable: Boolean,
    val serviceEnabled: String,
    val serviceActive: String,
    val cronLine: String?,
    val cronDirExists: Boolean,
    val packageManager: String?,
    val privilege: String,
) {
    val installed: Boolean get() = installedVersion != null
    val serviceInstalled: Boolean get() = serviceEnabled.isNotBlank() && serviceEnabled != "not-found"

    val bashOk: Boolean
        get() {
            val parts = bashVersion?.split('.') ?: return false
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return major > 4 || (major == 4 && minor >= 4)
        }
}

data class BackupEntry(val name: String, val rules: Int, val sizeKb: Long)

class PortBindException(val result: ExecResult, val operation: String) :
    Exception(result.stderr.trim().ifBlank { result.stdout.trim() }.ifBlank { "код ${result.exitCode}" })

/** Клиентская проверка аргументов — повторяет правила portbind.sh (функции ipv4/host/port). */
object PortBindValidation {
    private val label = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    fun port(input: String): Int? {
        val v = input.trim()
        if (v.isEmpty() || v.length > 5 || !v.all { it in '0'..'9' }) return null
        return v.toInt().takeIf { it in 1..65535 }
    }

    fun isIpv4(v: String): Boolean {
        val parts = v.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } &&
                (p == "0" || !p.startsWith("0")) && p.toInt() <= 255
        }
    }

    /** Нормализованный IPv4/домен или null. */
    fun host(input: String): String? {
        var v = input.trim().lowercase()
        if (v.endsWith('.')) v = v.dropLast(1)
        if (isIpv4(v)) return v
        if (v.isEmpty() || v.length > 253 || v.contains("..")) return null
        if (v.all { it in '0'..'9' || it == '.' }) return null
        return if (v.split('.').all { it.length <= 63 && label.matches(it) }) v else null
    }

    fun bindHost(input: String): String? {
        val v = input.trim()
        return if (v.isEmpty() || v == "*") "*" else host(v)
    }

    fun backupName(name: String): Boolean = name.matches(Regex("^[0-9]{8}T[0-9]{6}Z\\.[A-Za-z0-9]{3,}$"))
}

/** Смысл кодов возврата по контракту CLI. */
fun portbindExitHint(code: Int, command: String): String = when (code) {
    0 -> "Успешно"
    1 -> "Ошибка: неверные аргументы, конфликт порта, ошибка DNS при add, нет зависимости, ошибка nft/sysctl или занята блокировка"
    2 -> if (command == "refresh") {
        "Часть доменов не разрешилась; успешно разрешённые обновления применены"
    } else {
        "Код 2: операция не выполнена"
    }
    126, 127 -> "portbind не установлен или не исполняемый"
    130 -> "Прервано (SIGINT); выполнялся откат"
    143 -> "Прервано (SIGTERM/SIGHUP); выполнялся откат"
    else -> "Ошибка внешней утилиты (код $code) — проверьте состояние"
}

class PortBindClient(private val serverId: String) {

    /**
     * Root-операции не отменяются при уходе с экрана: обрыв канала посреди изменения
     * мог бы прервать portbind до отката.
     */
    private suspend fun root(script: String, timeoutMs: Long = 180_000): ExecResult =
        withContext(NonCancellable) {
            SshManager.withConnection(serverId) { it.runScript(script, asRoot = true, timeoutMs = timeoutMs) }
        }

    suspend fun status(): PortBindStatus {
        val r = SshManager.withConnection(serverId) { it.runScript(Scripts.build("portbind_status.sh"), timeoutMs = 30_000) }
        return parseStatus(r.stdout)
    }

    /** Вызов CLI portbind с аргументами (каждый аргумент экранируется отдельно). */
    suspend fun cli(vararg args: String, timeoutMs: Long = 180_000): ExecResult =
        root("$PATH_EXPORT\n$BINARY ${Sh.join(args.toList())}", timeoutMs)

    suspend fun list(): List<PortRule> {
        val r = cli("list", "--tsv", timeoutMs = 60_000)
        if (!r.ok) throw PortBindException(r, "list")
        return parseTsv(r.stdout)
    }

    suspend fun add(inPort: Int, target: String, outPort: Int, protocol: String, bind: String): ExecResult =
        cli("add", inPort.toString(), target, outPort.toString(), protocol, bind)

    suspend fun delete(inPort: Int): ExecResult = cli("delete", inPort.toString())

    suspend fun refresh(domain: String?): ExecResult =
        if (domain.isNullOrBlank()) cli("refresh", timeoutMs = 600_000) else cli("refresh", domain, timeoutMs = 120_000)

    suspend fun apply(): ExecResult = cli("apply")

    suspend fun reset(): ExecResult = cli("reset", "--yes")

    suspend fun kernelTable(): ExecResult = root("$PATH_EXPORT\nnft list table ip portbind", 60_000)

    suspend fun install(): ExecResult {
        val bytes = bundledScript()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val script = Scripts.build(
            "portbind_install.sh",
            mapOf("PB_B64" to Base64.encodeToString(bytes, Base64.NO_WRAP), "PB_SHA256" to sha),
        )
        return root(script, 120_000)
    }

    suspend fun installDependencies(): ExecResult = root(Scripts.build("portbind_deps.sh"), 900_000)

    suspend fun setService(enabled: Boolean): ExecResult =
        root(Scripts.build("portbind_service.sh", mapOf("ACTION" to if (enabled) "install" else "remove")), 120_000)

    suspend fun setCron(intervalMinutes: Int): ExecResult {
        require(intervalMinutes in CRON_INTERVALS || intervalMinutes == 0)
        return root(Scripts.build("portbind_cron.sh", mapOf("INTERVAL" to intervalMinutes.toString())), 60_000)
    }

    suspend fun backups(): List<BackupEntry> {
        val r = root(Scripts.build("portbind_backups.sh", mapOf("ACTION" to "list")), 60_000)
        if (!r.ok) throw PortBindException(r, "backups")
        return r.stdout.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 3 || p[0].isBlank()) null else BackupEntry(p[0], p[1].trim().toIntOrNull() ?: 0, p[2].trim().toLongOrNull() ?: 0L)
        }.sortedByDescending { it.name }
    }

    suspend fun backupRules(name: String): List<PortRule> {
        require(PortBindValidation.backupName(name)) { "Некорректное имя копии" }
        val r = root(Scripts.build("portbind_backups.sh", mapOf("ACTION" to "show", "NAME" to name)), 60_000)
        if (!r.ok) throw PortBindException(r, "backup show")
        return parseTsv(r.stdout)
    }

    suspend fun restoreBackup(name: String): ExecResult {
        require(PortBindValidation.backupName(name)) { "Некорректное имя копии" }
        return root(Scripts.build("portbind_backups.sh", mapOf("ACTION" to "restore", "NAME" to name)))
    }

    suspend fun pruneBackups(keep: Int): ExecResult =
        root(Scripts.build("portbind_backups.sh", mapOf("ACTION" to "prune", "KEEP" to keep.coerceAtLeast(1).toString())))

    companion object {
        const val BINARY = "/usr/local/sbin/portbind"
        private const val PATH_EXPORT = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val CRON_INTERVALS = listOf(1, 5, 10, 15, 30, 60)

        fun bundledScript(): ByteArray = Graph.app.assets.open("portbind/portbind.sh").use { it.readBytes() }

        /** Версия встроенного в приложение скрипта (из строки `--version`). */
        val bundledVersion: String by lazy {
            val text = String(bundledScript(), Charsets.UTF_8)
            Regex("portbind (\\d+\\.\\d+\\.\\d+)").find(text)?.groupValues?.get(1) ?: "?"
        }

        fun parseTsv(text: String): List<PortRule> = text.lines().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            val inPort = f[0].toIntOrNull() ?: return@mapNotNull null
            PortRule(
                inPort = inPort,
                protocol = f[1],
                bindHost = f[2],
                bindIp = f[3],
                targetHost = f[4],
                targetIp = f[5],
                targetPort = f[6].trim().toIntOrNull() ?: 0,
            )
        }

        fun parseStatus(output: String): PortBindStatus {
            val sections = HashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (line in output.lines()) {
                if (line.startsWith("@@")) {
                    val list = mutableListOf<String>()
                    sections[line.removePrefix("@@").trim()] = list
                    current = list
                } else if (line.isNotBlank()) {
                    current?.add(line.trim())
                }
            }
            val versionLine = sections["VERSION"]?.firstOrNull()
            val version = versionLine?.let { Regex("(\\d+\\.\\d+\\.\\d+)").find(it)?.value ?: "неизвестна" }
            val systemd = sections["SYSTEMD"].orEmpty()
            fun kv(key: String) = systemd.firstOrNull { it.startsWith("$key=") }?.substringAfter('=').orEmpty()
            return PortBindStatus(
                installedVersion = version,
                bashVersion = sections["BASH"]?.firstOrNull(),
                missingDeps = sections["DEPS"].orEmpty().filter { it.endsWith(" missing") }.map { it.substringBefore(' ') },
                systemdAvailable = systemd.isNotEmpty() && systemd.first() != "none",
                serviceEnabled = kv("enabled"),
                serviceActive = kv("active"),
                cronLine = sections["CRON"]?.firstOrNull(),
                cronDirExists = sections["CROND"]?.firstOrNull() == "yes",
                packageManager = sections["PKG"]?.firstOrNull(),
                privilege = sections["PRIV"]?.firstOrNull() ?: "unknown",
            )
        }

        /** Интервал в минутах из строки cron (для отображения). */
        fun cronInterval(line: String?): Int? {
            if (line == null) return null
            val trimmed = line.trim()
            val spec = trimmed.split(Regex("\\s+")).firstOrNull() ?: return null
            return when {
                trimmed.startsWith("* * * * *") -> 1
                trimmed.startsWith("0 * * * *") -> 60
                spec.startsWith("*/") -> spec.removePrefix("*/").toIntOrNull()
                else -> null
            }
        }
    }
}
