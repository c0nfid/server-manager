package com.srvmanager.app.security

import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

data class SecurityStatus(
    /** null — значение не удалось прочитать. */
    val pingDisabled: Boolean?,
    /** null — IPv6 недоступен на сервере. */
    val ping6Disabled: Boolean?,
    /** Другие файлы sysctl, где задан icmp_echo_ignore_all. */
    val pingOtherFiles: List<String>,
    val sshPorts: List<Int>,
    val socketActivated: Boolean,
    /** active / inactive / none */
    val ufw: String,
    /** running / not running / none */
    val firewalld: String,
    /** Enforcing / Permissive / Disabled / none */
    val selinux: String,
)

/** Результат первого шага переноса порта: старые порты и копия sshd_config для отката. */
data class SshPortAdded(val oldPorts: List<Int>, val backup: String, val result: ExecResult)

class SecurityClient(private val serverId: String) {

    /** Изменения конфигурации не прерываются при уходе с экрана. */
    private suspend fun root(script: String, timeoutMs: Long = 120_000): ExecResult =
        withContext(NonCancellable) {
            SshManager.withConnection(serverId) { it.runScript(script, asRoot = true, timeoutMs = timeoutMs) }
        }

    suspend fun status(): SecurityStatus {
        val r = root(Scripts.build("security_status.sh"), 60_000)
        if (!r.stdout.contains("@@END")) throw IOException(r.combined.ifBlank { "Нет ответа от сервера (код ${r.exitCode})" })
        return parseStatus(r.stdout)
    }

    suspend fun setPing(disabled: Boolean, ipv6: Boolean): ExecResult = root(
        Scripts.build("ping_set.sh", mapOf("DISABLE" to if (disabled) "1" else "0", "IPV6" to if (ipv6) "1" else "0")),
        60_000,
    )

    suspend fun addSshPort(newPort: Int): SshPortAdded {
        val r = root(Scripts.build("ssh_port.sh", mapOf("ACTION" to "add", "NEW" to newPort.toString())), 180_000)
        val lines = r.stdout.lines().map { it.trim() }
        val backup = lines.firstOrNull { it.startsWith("BACKUP=") }?.removePrefix("BACKUP=").orEmpty()
        val old = lines.firstOrNull { it.startsWith("OLD=") }?.removePrefix("OLD=").orEmpty()
            .split(' ').mapNotNull { it.toIntOrNull() }
        return SshPortAdded(old, backup, r)
    }

    suspend fun finalizeSshPort(newPort: Int, oldPorts: List<Int>, closeOld: Boolean): ExecResult = root(
        Scripts.build(
            "ssh_port.sh",
            mapOf(
                "ACTION" to "finalize",
                "NEW" to newPort.toString(),
                "OLD" to oldPorts.joinToString(" "),
                "CLOSE_OLD" to if (closeOld) "1" else "0",
            ),
        ),
        180_000,
    )

    suspend fun revertSshPort(newPort: Int, backup: String): ExecResult {
        require(backup.matches(Regex("^/etc/ssh/sshd_config\\.srvmanager\\.[0-9]{14}$"))) { "Некорректный путь резервной копии" }
        return root(
            Scripts.build("ssh_port.sh", mapOf("ACTION" to "revert", "NEW" to newPort.toString(), "BACKUP" to backup)),
            180_000,
        )
    }

    companion object {
        fun parseStatus(output: String): SecurityStatus {
            val sections = HashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            for (raw in output.lines()) {
                val line = raw.trim()
                if (line.startsWith("@@")) {
                    val list = mutableListOf<String>()
                    sections[line.removePrefix("@@")] = list
                    current = list
                } else if (line.isNotEmpty()) {
                    current?.add(line)
                }
            }
            fun first(name: String) = sections[name]?.firstOrNull()
            val ufwLine = first("UFW").orEmpty()
            return SecurityStatus(
                pingDisabled = first("PING4")?.let { it == "1" },
                ping6Disabled = first("PING6")?.let { it == "1" },
                pingOtherFiles = sections["PINGOTHER"].orEmpty(),
                sshPorts = sections["SSHPORTS"].orEmpty().mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(22) },
                socketActivated = first("SOCKET") == "active",
                ufw = when {
                    ufwLine == "none" -> "none"
                    ufwLine.contains("inactive") -> "inactive"
                    ufwLine.contains("active") -> "active"
                    else -> "none"
                },
                firewalld = first("FIREWALLD") ?: "none",
                selinux = first("SELINUX") ?: "none",
            )
        }
    }
}
