package com.srvmanager.app.geo

import com.srvmanager.app.data.AppJson
import com.srvmanager.app.data.GeoInfo
import com.srvmanager.app.data.Server
import com.srvmanager.app.data.countryName
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/** Определение местоположения сервера по IP через публичные GeoIP-сервисы. */
object GeoLocator {

    suspend fun resolve(host: String): String = withContext(Dispatchers.IO) {
        val addresses = InetAddress.getAllByName(host.trim())
        val chosen = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()
        chosen.hostAddress ?: host
    }

    fun isPrivate(ip: String): Boolean {
        val address = try {
            InetAddress.getByName(ip)
        } catch (e: Exception) {
            return false
        }
        if (address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet4Address) {
            val a = bytes[0].toInt() and 0xFF
            val b = bytes[1].toInt() and 0xFF
            return a == 100 && b in 64..127 // CGNAT 100.64.0.0/10
        }
        if (address is Inet6Address) return (bytes[0].toInt() and 0xFE) == 0xFC // ULA fc00::/7
        return false
    }

    /**
     * Определяет местоположение. Если адрес частный (сервер за NAT/VPN) или [viaServer] = true,
     * внешний IP запрашивается с самого сервера по SSH.
     */
    suspend fun locate(server: Server, viaServer: Boolean): GeoInfo {
        if (!viaServer) {
            val ip = resolve(server.host)
            if (!isPrivate(ip)) return lookup(ip)
        }
        val result = SshManager.withConnection(server.id) { it.runScript(Scripts.build("geo.sh"), timeoutMs = 30_000) }
        if (!result.ok) throw IOException(result.stderr.ifBlank { "Сервер не смог выполнить запрос геолокации" })
        return parseIpinfo(result.stdout).copy(source = "ipinfo.io (запрос с сервера)")
    }

    suspend fun lookup(ip: String): GeoInfo = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        try {
            return@withContext parseIpwho(httpGet("https://ipwho.is/$ip"))
        } catch (e: Exception) {
            errors += "ipwho.is: ${e.message}"
        }
        try {
            return@withContext parseIpinfo(httpGet("https://ipinfo.io/$ip/json"))
        } catch (e: Exception) {
            errors += "ipinfo.io: ${e.message}"
        }
        throw IOException("Не удалось определить местоположение (${errors.joinToString("; ")})")
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "ServerManager-Android")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun parseIpwho(body: String): GeoInfo {
        val o = AppJson.parseToJsonElement(body).jsonObject
        if ((o["success"] as? JsonPrimitive)?.booleanOrNull == false) {
            throw IOException(o.str("message").ifBlank { "ошибка сервиса" })
        }
        val connection = o.obj("connection")
        val code = o.str("country_code")
        return GeoInfo(
            ip = o.str("ip"),
            country = countryName(code).ifBlank { o.str("country") },
            countryCode = code,
            region = o.str("region"),
            city = o.str("city"),
            latitude = (o["latitude"] as? JsonPrimitive)?.doubleOrNull,
            longitude = (o["longitude"] as? JsonPrimitive)?.doubleOrNull,
            org = listOf(connection?.str("org"), connection?.str("isp")).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            timezone = o.obj("timezone")?.str("id").orEmpty(),
            source = "ipwho.is",
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun parseIpinfo(body: String): GeoInfo {
        val o = AppJson.parseToJsonElement(body).jsonObject
        if ((o["bogon"] as? JsonPrimitive)?.booleanOrNull == true) throw IOException("частный (bogon) адрес")
        val location = o.str("loc").split(',')
        val code = o.str("country")
        return GeoInfo(
            ip = o.str("ip"),
            country = countryName(code),
            countryCode = code,
            region = o.str("region"),
            city = o.str("city"),
            latitude = location.getOrNull(0)?.trim()?.toDoubleOrNull(),
            longitude = location.getOrNull(1)?.trim()?.toDoubleOrNull(),
            org = o.str("org"),
            timezone = o.str("timezone"),
            source = "ipinfo.io",
            updatedAt = System.currentTimeMillis(),
        )
    }
}
