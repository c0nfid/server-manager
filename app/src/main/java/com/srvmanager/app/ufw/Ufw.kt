package com.srvmanager.app.ufw

import com.srvmanager.app.portbind.PortBindClient
import com.srvmanager.app.portbind.PortRule
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.Sh
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/** Правило из `ufw show added`; [index] — номер строки (с 1) для удаления. */
data class UfwRule(val index: Int, val line: String) {
    val comment: String? = Regex(" comment '(.*)'$").find(line)?.groupValues?.get(1)
    val spec: String = line.removePrefix("ufw ").replace(Regex(" comment '.*'$"), "")
    val isRoute: Boolean = spec.startsWith("route ")
    val action: String = spec.removePrefix("route ").substringBefore(' ')
}

data class UfwStatus(
    val installed: Boolean,
    val packageManager: String?,
    val version: String?,
    val active: Boolean,
    val statusText: String,
    val rules: List<UfwRule>,
    /** allow / deny / reject */
    val defaultIncoming: String,
    val defaultOutgoing: String,
    val defaultRouted: String,
    val logLevel: String,
    val apps: List<String>,
    val sshPorts: List<Int>,
    val dockerInstalled: Boolean,
    val portBindRules: List<PortRule>,
)

/** Параметры нового правила из диалога. */
data class UfwRuleInput(
    val action: String = "allow",
    val direction: String = "in",
    val useApp: Boolean = false,
    val port: String = "",
    val proto: String = "tcp",
    val app: String = "",
    val from: String = "",
    val comment: String = "",
)

object UfwValidation {
    private val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(/(3[0-2]|[12]?\\d))?$")
    private val ipv6 = Regex("^[0-9a-fA-F:]*:[0-9a-fA-F:]*(/(12[0-8]|1[01]\\d|[1-9]?\\d))?$")

    /** Диапазоны портов (from..to) из записи вида «80», «6000:6007», «80,443». null — ошибка. */
    fun portRanges(input: String): List<IntRange>? {
        val parts = input.trim().split(',')
        if (parts.isEmpty() || parts.size > 15) return null
        return parts.map { part ->
            val bounds = part.trim().split(':')
            val numbers = bounds.map { b -> b.toIntOrNull()?.takeIf { it in 1..65535 && b.all(Char::isDigit) } ?: return null }
            when (numbers.size) {
                1 -> numbers[0]..numbers[0]
                2 -> if (numbers[0] < numbers[1]) numbers[0]..numbers[1] else return null
                else -> return null
            }
        }
    }

    fun isSource(input: String): Boolean {
        val v = input.trim()
        return v.isEmpty() || v == "any" || ipv4.matches(v) || ipv6.matches(v)
    }

    fun cleanComment(input: String): String = input.replace("'", "").replace(Regex("[\\r\\n]"), " ").trim().take(100)

    /** Текст ошибки или null, если правило корректно. */
    fun validate(input: UfwRuleInput, sshPorts: List<Int>): String? {
        if (input.action !in listOf("allow", "deny", "reject", "limit")) return "Неверное действие"
        if (!isSource(input.from)) return "Источник: IPv4/IPv6 или подсеть, например 192.168.1.0/24"
        if (input.useApp) {
            if (input.app.isBlank()) return "Выберите профиль приложения"
            return null
        }
        val ranges = portRanges(input.port) ?: return "Порт: 80, диапазон 6000:6007 или список 80,443"
        val multi = input.port.contains(',') || input.port.contains(':')
        if (multi && input.proto == "any") return "Для диапазона или списка портов укажите tcp или udp"
        val blocking = input.action == "deny" || input.action == "reject"
        val fromAnywhere = input.from.isBlank() || input.from.trim() == "any"
        if (blocking && input.direction == "in" && fromAnywhere && input.proto != "udp") {
            val ssh = sshPorts.firstOrNull { p -> ranges.any { p in it } }
            if (ssh != null) return "Это правило заблокирует SSH (порт $ssh) и доступ к серверу"
        }
        return null
    }

    /** Аргументы команды ufw для правила. */
    fun buildArgs(input: UfwRuleInput): List<String> {
        val args = mutableListOf(input.action)
        val from = input.from.trim().takeIf { it.isNotEmpty() && it != "any" }
        if (input.useApp) {
            if (from == null) args += input.app else args += listOf("from", from, "to", "any", "app", input.app)
        } else {
            val port = input.port.trim()
            if (from == null) {
                if (input.direction == "out") args += "out"
                args += if (input.proto == "any") port else "$port/${input.proto}"
            } else {
                args += listOf(input.direction, "from", from, "to", "any", "port", port)
                if (input.proto != "any") args += listOf("proto", input.proto)
            }
        }
        val comment = cleanComment(input.comment)
        if (comment.isNotEmpty()) args += listOf("comment", comment)
        return args
    }
}

class UfwClient(private val serverId: String) {

    private suspend fun root(script: String, timeoutMs: Long = 120_000): ExecResult =
        withContext(NonCancellable) {
            SshManager.withConnection(serverId) { it.runScript(script, asRoot = true, timeoutMs = timeoutMs) }
        }

    private suspend fun action(name: String, vars: Map<String, String> = emptyMap(), timeoutMs: Long = 120_000) =
        root(Scripts.build("ufw_action.sh", mapOf("ACTION" to name) + vars), timeoutMs)

    suspend fun status(): UfwStatus {
        val r = root(Scripts.build("ufw_status.sh"), 60_000)
        if (!r.stdout.contains("@@END")) throw IOException(r.combined.ifBlank { "Нет ответа от сервера (код ${r.exitCode})" })
        return parseStatus(r.stdout)
    }

    suspend fun enable() = action("enable")
    suspend fun disable() = action("disable")
    suspend fun add(input: UfwRuleInput) = action("add", mapOf("RULE" to Sh.join(UfwValidation.buildArgs(input))))
    suspend fun delete(rule: UfwRule) = action("delete", mapOf("INDEX" to rule.index.toString(), "EXPECT" to rule.line))
    suspend fun setDefault(direction: String, policy: String) = action("default", mapOf("DIRECTION" to direction, "POLICY" to policy))
    suspend fun setLogging(level: String) = action("logging", mapOf("LEVEL" to level))
    suspend fun reset() = action("reset")
    suspend fun install() = action("install", timeoutMs = 900_000)
    suspend fun routePortBind() = action("route_portbind")

    companion object {
        private fun policy(raw: String?): String = when (raw?.uppercase()) {
            "ACCEPT" -> "allow"
            "REJECT" -> "reject"
            "DROP" -> "deny"
            else -> "?"
        }

        fun parseStatus(output: String): UfwStatus {
            val sections = HashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (raw in output.lines()) {
                val line = raw.trimEnd()
                if (line.startsWith("@@")) {
                    val list = mutableListOf<String>()
                    sections[line.removePrefix("@@").trim()] = list
                    current = list
                } else if (line.isNotBlank()) {
                    current?.add(line)
                }
            }
            val status = sections["STATUS"].orEmpty()
            val defaults = sections["DEFAULTS"].orEmpty().associate {
                it.substringBefore('=').trim() to it.substringAfter('=').trim()
            }
            val rules = sections["ADDED"].orEmpty().map { it.trim() }.filter { it.startsWith("ufw ") }
                .mapIndexed { i, line -> UfwRule(i + 1, line) }
            return UfwStatus(
                installed = sections["INSTALLED"]?.firstOrNull()?.trim() == "yes",
                packageManager = sections["PKG"]?.firstOrNull()?.trim(),
                version = sections["VERSION"]?.firstOrNull()?.trim(),
                active = status.firstOrNull()?.trim() == "Status: active",
                statusText = status.joinToString("\n"),
                rules = rules,
                defaultIncoming = policy(defaults["DEFAULT_INPUT_POLICY"]),
                defaultOutgoing = policy(defaults["DEFAULT_OUTPUT_POLICY"]),
                defaultRouted = policy(defaults["DEFAULT_FORWARD_POLICY"]),
                logLevel = sections["LOGLEVEL"]?.firstOrNull()?.substringAfter('=')?.trim().orEmpty().ifEmpty { "?" },
                apps = sections["APPS"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
                sshPorts = sections["SSHPORTS"].orEmpty().mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(22) },
                dockerInstalled = sections["DOCKER"]?.firstOrNull()?.trim() == "yes",
                portBindRules = PortBindClient.parseTsv(sections["PORTBIND"].orEmpty().joinToString("\n")),
            )
        }
    }
}
