package com.srvmanager.app.data

import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Формат файла переноса (.smbak):
 * "SMBK" | версия (1 байт) | итерации PBKDF2 (int) | соль (16) | IV (12) | AES-256-GCM(JSON)
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION = 1
    private const val ITERATIONS = 150_000
    const val FILE_EXTENSION = "smbak"

    @Serializable
    data class Bundle(
        val format: Int = 1,
        val app: String = "ServerManager",
        val exportedAt: Long = System.currentTimeMillis(),
        val data: AppData,
    )

    fun encrypt(data: AppData, password: CharArray): ByteArray {
        val json = AppJson.encodeToString(Bundle.serializer(), Bundle(data = data)).toByteArray(Charsets.UTF_8)
        val random = SecureRandom()
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val encrypted = cipher.doFinal(json)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.writeInt(ITERATIONS)
            out.write(salt)
            out.write(iv)
            out.write(encrypted)
        }
        return bytes.toByteArray()
    }

    fun decrypt(bytes: ByteArray, password: CharArray): Bundle {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(4)
        try {
            input.readFully(magic)
        } catch (e: Exception) {
            throw IllegalArgumentException("Файл слишком короткий")
        }
        require(magic.contentEquals(MAGIC)) { "Это не резервная копия Server Manager" }
        val version = input.readUnsignedByte()
        require(version == VERSION) { "Неподдерживаемая версия формата: $version" }
        val iterations = input.readInt()
        require(iterations in 1_000..10_000_000) { "Повреждённый заголовок файла" }
        val salt = ByteArray(16).also { input.readFully(it) }
        val iv = ByteArray(12).also { input.readFully(it) }
        val encrypted = input.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val plain = try {
            cipher.doFinal(encrypted)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Неверный пароль или файл повреждён")
        }
        return AppJson.decodeFromString(Bundle.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
