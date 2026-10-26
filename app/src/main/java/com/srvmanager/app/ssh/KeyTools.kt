package com.srvmanager.app.ssh

import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AuthType
import com.srvmanager.app.data.Server
import com.srvmanager.app.data.SshKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class KeyAlgorithm(val title: String) {
    ECDSA_256("ECDSA P-256 (быстро, рекомендуется)"),
    ECDSA_521("ECDSA P-521"),
    RSA_4096("RSA 4096 (максимальная совместимость)"),
}

object KeyTools {
    fun defaultComment(): String =
        "srvmanager@" + (Build.MODEL ?: "android").replace(Regex("[^A-Za-z0-9._-]"), "-")

    suspend fun generate(algorithm: KeyAlgorithm, name: String): SshKey = withContext(Dispatchers.Default) {
        val jsch = JSch()
        val pair = when (algorithm) {
            KeyAlgorithm.ECDSA_256 -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
            KeyAlgorithm.ECDSA_521 -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 521)
            KeyAlgorithm.RSA_4096 -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
        }
        try {
            val privateOut = ByteArrayOutputStream()
            pair.writePrivateKey(privateOut)
            val publicOut = ByteArrayOutputStream()
            pair.writePublicKey(publicOut, defaultComment())
            SshKey(
                name = name,
                type = pair.keyTypeString,
                privateKey = String(privateOut.toByteArray(), Charsets.UTF_8),
                publicKey = String(publicOut.toByteArray(), Charsets.UTF_8).trim(),
                fingerprint = HostKeys.fingerprint(pair.publicKeyBlob),
            )
        } finally {
            pair.dispose()
        }
    }

    /** Импорт приватного ключа (OpenSSH, PEM, PuTTY), в том числе Ed25519 и зашифрованных. */
    suspend fun importKey(name: String, privateKey: String, passphrase: String): SshKey = withContext(Dispatchers.Default) {
        val text = privateKey.trim().replace("\r\n", "\n") + "\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val jsch = JSch()
        val pair = KeyPair.load(jsch, bytes, null)
        try {
            if (pair.isEncrypted) {
                require(passphrase.isNotEmpty()) { "Ключ зашифрован — укажите парольную фразу" }
                require(pair.decrypt(passphrase)) { "Неверная парольная фраза" }
            }
            val blob = pair.publicKeyBlob ?: throw IllegalArgumentException("Не удалось получить публичный ключ")
            val publicOut = ByteArrayOutputStream()
            pair.writePublicKey(publicOut, name.ifBlank { defaultComment() }.replace(Regex("\\s+"), "_"))
            // Проверяем, что JSch сможет использовать ключ при подключении.
            jsch.addIdentity("check", bytes, null, passphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8))
            SshKey(
                name = name.ifBlank { pair.keyTypeString },
                type = pair.keyTypeString,
                privateKey = text,
                publicKey = String(publicOut.toByteArray(), Charsets.UTF_8).trim(),
                passphrase = passphrase,
                fingerprint = HostKeys.fingerprint(blob),
            )
        } finally {
            pair.dispose()
        }
    }
}

/** Автоматическая настройка входа по ключу: добавление ключа на сервер и проверка входа. */
object KeyInstaller {
    suspend fun install(server: Server, key: SshKey, removePassword: Boolean, log: (String) -> Unit) {
        log("Подключение к ${server.address} по паролю…")
        val opened = SshManager.openSession(server, AuthMode.PASSWORD_ONLY)
        try {
            val conn = SshConnection(server, opened.session)
            log("Добавление ключа в ~/.ssh/authorized_keys…")
            val result = conn.runScript(Scripts.build("install_key.sh", mapOf("PUBKEY" to key.publicKey.trim())))
            if (!result.ok) throw IllegalStateException("Не удалось записать ключ: ${result.combined}")
            log(if (result.stdout.contains("EXISTS")) "Ключ уже был добавлен ранее" else "Ключ добавлен")
        } finally {
            opened.session.disconnect()
        }

        log("Проверка входа по ключу…")
        val keyServer = server.copy(authType = AuthType.KEY, keyId = key.id)
        val check = try {
            SshManager.openSession(keyServer, AuthMode.KEY_ONLY, keyOverride = key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "Ключ записан, но сервер не принял его (${e.friendlyMessage()}). " +
                    "Проверьте PubkeyAuthentication в /etc/ssh/sshd_config. Вход по паролю сохранён."
            )
        }
        try {
            val r = SshConnection(keyServer, check.session).exec("echo ok", timeoutMs = 20_000)
            if (!r.stdout.contains("ok")) throw IllegalStateException("Тестовая команда не выполнена: ${r.combined}")
        } finally {
            check.session.disconnect()
        }
        Graph.repo.updateServer(server.id) {
            it.copy(authType = AuthType.KEY, keyId = key.id, password = if (removePassword) "" else it.password)
        }
        SshManager.disconnect(server.id)
        log("Готово: сервер переключён на вход по ключу «${key.name}»")
    }
}
