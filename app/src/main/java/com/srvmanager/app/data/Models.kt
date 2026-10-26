package com.srvmanager.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID

@Serializable
enum class AuthType { PASSWORD, KEY }

@Serializable
data class GeoInfo(
    val ip: String = "",
    val country: String = "",
    val countryCode: String = "",
    val region: String = "",
    val city: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val org: String = "",
    val timezone: String = "",
    val source: String = "",
    val updatedAt: Long = 0L,
) {
    val flag: String get() = countryFlag(countryCode)

    val shortLabel: String
        get() = listOf(city, country).filter { it.isNotBlank() }.joinToString(", ")
}

@Serializable
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val keyId: String? = null,
    /** Публичный ключ хоста (base64 SSH-blob), сохранённый при первом подключении (TOFU). */
    val hostKey: String? = null,
    val hostKeyType: String? = null,
    val hostKeyFingerprint: String? = null,
    val notes: String = "",
    val geo: GeoInfo? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val displayName: String get() = name.ifBlank { host }
    val address: String get() = "$username@$host" + if (port != 22) ":$port" else ""
    val isRoot: Boolean get() = username == "root"
}

@Serializable
data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val privateKey: String,
    val publicKey: String,
    val passphrase: String = "",
    val fingerprint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppData(
    val servers: List<Server> = emptyList(),
    val keys: List<SshKey> = emptyList(),
)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun countryFlag(code: String): String {
    val up = code.uppercase(Locale.ROOT)
    if (up.length != 2 || !up.all { it in 'A'..'Z' }) return ""
    val first = up[0].code - 'A'.code + 0x1F1E6
    val second = up[1].code - 'A'.code + 0x1F1E6
    return String(Character.toChars(first)) + String(Character.toChars(second))
}

@Suppress("DEPRECATION")
fun countryName(code: String): String {
    if (code.length != 2) return code
    return try {
        Locale("", code.uppercase(Locale.ROOT)).getDisplayCountry(Locale("ru")).ifBlank { code }
    } catch (e: Exception) {
        code
    }
}
