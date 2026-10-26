package com.srvmanager.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Файл с данными приложения, зашифрованный AES-256-GCM ключом из Android Keystore.
 * Ключ не покидает устройство, поэтому для переноса используется отдельный экспорт.
 */
class SecureStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "vault.bin")
    private val tmp = File(dir, "vault.bin.tmp")

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return null
        val ivLen = bytes[0].toInt() and 0xFF
        require(ivLen in 12..32 && bytes.size > 1 + ivLen) { "Повреждённый файл хранилища" }
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen)
        return String(plain, Charsets.UTF_8)
    }

    @Synchronized
    fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        FileOutputStream(tmp).use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(encrypted)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Откладывает нерасшифровываемый файл в сторону, чтобы не потерять его окончательно. */
    @Synchronized
    fun quarantine() {
        if (file.exists()) file.renameTo(File(dir, "vault.corrupt.${System.currentTimeMillis()}"))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "srvmanager_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
