package com.srvmanager.app.ssh

import android.os.SystemClock
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AuthType
import com.srvmanager.app.data.Server
import com.srvmanager.app.data.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0

    val combined: String
        get() = listOf(stdout.trimEnd(), stderr.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
}

/** Ключ хоста не совпадает с сохранённым: возможна переустановка сервера или MITM. */
class HostKeyMismatchException(
    val serverId: String,
    val keyBase64: String,
    val keyType: String,
    val fingerprint: String,
    val expectedFingerprint: String?,
) : Exception("Ключ хоста изменился")

class SudoException(message: String) : Exception(message)

enum class AuthMode { AUTO, PASSWORD_ONLY, KEY_ONLY }

data class HostKeyInfo(val base64: String, val type: String, val fingerprint: String)

object HostKeys {
    fun fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob),
            Base64.NO_WRAP or Base64.NO_PADDING,
        )

    /** Тип ключа — первая строка SSH-blob, например "ssh-ed25519". */
    fun keyType(blob: ByteArray): String {
        if (blob.size < 4) return "unknown"
        val len = ((blob[0].toInt() and 0xFF) shl 24) or ((blob[1].toInt() and 0xFF) shl 16) or
            ((blob[2].toInt() and 0xFF) shl 8) or (blob[3].toInt() and 0xFF)
        if (len <= 0 || 4 + len > blob.size) return "unknown"
        return String(blob, 4, len, Charsets.US_ASCII)
    }

    fun info(blob: ByteArray) = HostKeyInfo(
        base64 = Base64.encodeToString(blob, Base64.NO_WRAP),
        type = keyType(blob),
        fingerprint = fingerprint(blob),
    )

    /** Пользователь подтвердил новый ключ хоста. */
    fun trust(e: HostKeyMismatchException) {
        Graph.repo.updateServer(e.serverId) {
            it.copy(hostKey = e.keyBase64, hostKeyType = e.keyType, hostKeyFingerprint = e.fingerprint)
        }
        SshManager.disconnect(e.serverId)
    }
}

/**
 * Trust-on-first-use: при отсутствии сохранённого ключа принимает любой и запоминает его,
 * иначе требует точного совпадения. Проверка выполняется до аутентификации,
 * поэтому пароль не уходит на подменённый сервер.
 */
private class TofuHostKeyRepository(private val expected: String?) : HostKeyRepository {
    @Volatile
    var presentedKey: ByteArray? = null

    @Volatile
    var mismatch = false

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        presentedKey = key.copyOf()
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        return if (expected.isNullOrEmpty() || expected == b64) {
            HostKeyRepository.OK
        } else {
            mismatch = true
            HostKeyRepository.CHANGED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "srvmanager"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

class SshConnection internal constructor(val server: Server, internal val session: Session) {
    val isConnected: Boolean get() = session.isConnected

    val serverVersion: String? get() = session.serverVersion

    /** Выполняет команду через exec-канал и собирает stdout/stderr/код выхода. */
    suspend fun exec(command: String, stdin: ByteArray? = null, timeoutMs: Long = 120_000): ExecResult =
        withContext(Dispatchers.IO) {
            val channel = session.openChannel("exec") as ChannelExec
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            try {
                channel.setCommand(command)
                channel.setOutputStream(out, true)
                channel.setErrStream(err, true)
                channel.connect(15_000)
                try {
                    // Закрытие потока отправляет EOF, команды не будут ждать ввода.
                    channel.outputStream.use { os ->
                        if (stdin != null) {
                            os.write(stdin)
                            os.flush()
                        }
                    }
                } catch (e: IOException) {
                    // Команда могла завершиться раньше, чем мы успели записать stdin.
                }
                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                while (!channel.isClosed) {
                    if (SystemClock.elapsedRealtime() > deadline) {
                        throw IOException("Команда не завершилась за ${timeoutMs / 1000} с")
                    }
                    delay(40)
                }
                ExecResult(
                    exitCode = channel.exitStatus,
                    stdout = String(out.toByteArray(), Charsets.UTF_8),
                    stderr = String(err.toByteArray(), Charsets.UTF_8),
                )
            } finally {
                channel.disconnect()
            }
        }

    /**
     * Запускает POSIX-скрипт независимо от login shell пользователя: текст передаётся в base64
     * и исполняется через `sh -c 'eval ...'` со stdin=/dev/null. При [asRoot] используется sudo,
     * пароль передаётся через stdin (sudo -S), а не в командной строке.
     */
    suspend fun runScript(script: String, asRoot: Boolean = false, timeoutMs: Long = 120_000): ExecResult {
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val inner = Sh.quote("eval \"\$(echo $b64 | base64 -d)\" </dev/null")
        if (!asRoot || server.isRoot) return exec("sh -c $inner", null, timeoutMs)
        val password = server.password
        val result = if (password.isNotEmpty()) {
            exec("env LC_ALL=C sudo -S -p '' sh -c $inner", (password + "\n").toByteArray(Charsets.UTF_8), timeoutMs)
        } else {
            exec("env LC_ALL=C sudo -n sh -c $inner", null, timeoutMs)
        }
        checkSudo(result)
        return result
    }

    private fun checkSudo(result: ExecResult) {
        if (result.ok) return
        val err = result.stderr
        val message = when {
            result.exitCode == 127 && err.contains("sudo") ->
                "На сервере нет sudo. Подключайтесь как root или установите sudo."
            err.contains("incorrect password", ignoreCase = true) || err.contains("Sorry, try again") ->
                "sudo: неверный пароль пользователя ${server.username}"
            err.contains("a password is required") || err.contains("no password was provided") ->
                "sudo требует пароль: сохраните пароль пользователя в настройках сервера"
            err.contains("is not in the sudoers") || err.contains("not allowed to execute") ->
                "Пользователь ${server.username} не имеет прав sudo"
            err.contains("a terminal is required") || err.contains("must have a tty") ->
                "sudo требует терминал (requiretty). Отключите requiretty или используйте root"
            else -> null
        }
        if (message != null) throw SudoException(message)
    }

    suspend fun openSftp(): ChannelSftp = withContext(Dispatchers.IO) {
        (session.openChannel("sftp") as ChannelSftp).also { it.connect(15_000) }
    }

    suspend fun openShell(cols: Int, rows: Int, widthPx: Int, heightPx: Int): Pair<ChannelShell, InputStream> =
        withContext(Dispatchers.IO) {
            val channel = session.openChannel("shell") as ChannelShell
            channel.setPtyType("xterm-256color", cols, rows, widthPx, heightPx)
            // Поток нужно получить до connect(), иначе начало вывода будет потеряно.
            val input = channel.inputStream
            channel.connect(15_000)
            channel to input
        }

    fun disconnect() {
        runCatching { session.disconnect() }
    }
}

data class ConnectionTest(val latencyMs: Long, val serverVersion: String?, val hostKey: HostKeyInfo?)

object SshManager {
    private val connections = ConcurrentHashMap<String, SshConnection>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())

    /** Идентификаторы серверов с открытой SSH-сессией. */
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    /** Общая (переиспользуемая) сессия для сервера из репозитория. */
    suspend fun connection(serverId: String): SshConnection {
        val mutex = locks.getOrPut(serverId) { Mutex() }
        return mutex.withLock {
            val existing = connections[serverId]
            if (existing != null && existing.isConnected) {
                existing
            } else {
                markDisconnected(serverId)
                val server = Graph.repo.server(serverId) ?: throw IllegalStateException("Сервер не найден")
                val opened = openSession(server, AuthMode.AUTO)
                SshConnection(server, opened.session).also {
                    connections[serverId] = it
                    _active.update { set -> set + serverId }
                }
            }
        }
    }

    /** Выполняет блок с общей сессией; если сессия успела оборваться, переподключается один раз. */
    suspend fun <T> withConnection(serverId: String, block: suspend (SshConnection) -> T): T {
        val conn = connection(serverId)
        return try {
            block(conn)
        } catch (e: JSchException) {
            if (!conn.isConnected && e.message?.contains("session is down") == true) {
                markDisconnected(serverId)
                block(connection(serverId))
            } else {
                if (!conn.isConnected) markDisconnected(serverId)
                throw e
            }
        }
    }

    private fun markDisconnected(serverId: String) {
        connections.remove(serverId)?.disconnect()
        _active.update { it - serverId }
    }

    fun disconnect(serverId: String) = markDisconnected(serverId)

    fun disconnectAll() {
        connections.keys.toList().forEach { markDisconnected(it) }
    }

    class OpenedSession(val session: Session, val hostKey: HostKeyInfo?)

    /**
     * Открывает новую сессию (не кэшируется). При первом подключении к сохранённому серверу
     * запоминает ключ хоста.
     */
    suspend fun openSession(
        server: Server,
        mode: AuthMode = AuthMode.AUTO,
        keyOverride: SshKey? = null,
        persistHostKey: Boolean = true,
    ): OpenedSession = withContext(Dispatchers.IO) {
        val jsch = JSch()
        val key = keyOverride ?: if (server.authType == AuthType.KEY) Graph.repo.key(server.keyId) else null
        if (mode == AuthMode.KEY_ONLY && key == null) throw IllegalStateException("SSH-ключ не выбран")
        if (mode != AuthMode.PASSWORD_ONLY && key != null) {
            jsch.addIdentity(
                key.name.ifBlank { key.id },
                key.privateKey.toByteArray(Charsets.UTF_8),
                null,
                key.passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        }
        if (mode == AuthMode.PASSWORD_ONLY && server.password.isEmpty()) {
            throw IllegalStateException("Для этого действия нужен пароль сервера")
        }
        val repo = TofuHostKeyRepository(server.hostKey)
        val session = jsch.getSession(server.username.trim(), server.host.trim(), server.port)
        session.setHostKeyRepository(repo)
        if (mode != AuthMode.KEY_ONLY && server.password.isNotEmpty()) session.setPassword(server.password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            when (mode) {
                AuthMode.KEY_ONLY -> "publickey"
                AuthMode.PASSWORD_ONLY -> "keyboard-interactive,password"
                AuthMode.AUTO -> "publickey,keyboard-interactive,password"
            },
        )
        session.setServerAliveInterval(15_000)
        session.setServerAliveCountMax(3)
        try {
            session.connect(15_000)
        } catch (e: JSchException) {
            val presented = repo.presentedKey
            if (repo.mismatch && presented != null) {
                val info = HostKeys.info(presented)
                throw HostKeyMismatchException(server.id, info.base64, info.type, info.fingerprint, server.hostKeyFingerprint)
            }
            throw e
        }
        val hostKey = repo.presentedKey?.let { HostKeys.info(it) }
        if (persistHostKey && server.hostKey.isNullOrEmpty() && hostKey != null) {
            Graph.repo.updateServer(server.id) {
                it.copy(hostKey = hostKey.base64, hostKeyType = hostKey.type, hostKeyFingerprint = hostKey.fingerprint)
            }
        }
        OpenedSession(session, hostKey)
    }

    /**
     * Проверка подключения с отдельной сессией (подходит и для ещё не сохранённого сервера).
     * Ключ хоста не сохраняется: при необходимости его запоминает вызывающий экран.
     */
    suspend fun test(server: Server, persistHostKey: Boolean = false): ConnectionTest {
        val start = SystemClock.elapsedRealtime()
        val opened = openSession(server, AuthMode.AUTO, persistHostKey = persistHostKey)
        try {
            val conn = SshConnection(server, opened.session)
            val r = conn.exec("echo ok", timeoutMs = 20_000)
            if (!r.stdout.contains("ok")) throw IOException("Сервер не выполнил тестовую команду: ${r.combined}")
            return ConnectionTest(SystemClock.elapsedRealtime() - start, conn.serverVersion, opened.hostKey)
        } finally {
            opened.session.disconnect()
        }
    }
}
