package com.srvmanager.app.ssh

import com.jcraft.jsch.SftpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Человекочитаемое описание ошибки подключения или выполнения. */
fun Throwable.friendlyMessage(): String {
    val chain = generateSequence(this) { it.cause }.take(8).toList()
    fun has(cls: Class<*>) = chain.any { cls.isInstance(it) }
    val msg = message.orEmpty()
    return when {
        this is HostKeyMismatchException -> "Ключ хоста изменился! Было: ${expectedFingerprint ?: "—"}, стало: $fingerprint"
        this is SudoException -> message.orEmpty()
        this is SftpException -> "SFTP: " + when (id) {
            2 -> "файл или каталог не найден"
            3 -> "доступ запрещён"
            4 -> "ошибка операции (${msg.ifBlank { "failure" }})"
            else -> msg.ifBlank { "код $id" }
        }
        msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
            "Ошибка аутентификации: проверьте имя пользователя, пароль или SSH-ключ"
        has(UnknownHostException::class.java) -> "Хост не найден (ошибка DNS)"
        has(ConnectException::class.java) -> "Соединение отклонено: порт закрыт или SSH не запущен"
        has(NoRouteToHostException::class.java) -> "Нет маршрута до хоста"
        has(SocketTimeoutException::class.java) || msg.contains("timeout", true) ->
            "Превышено время ожидания подключения"
        msg.contains("Algorithm negotiation fail", true) ->
            "Не удалось согласовать алгоритмы шифрования с сервером"
        msg.contains("invalid privatekey", true) -> "Неверный формат приватного ключа"
        msg.contains("session is down", true) -> "Сессия разорвана, повторите действие"
        msg.isNotBlank() -> msg
        else -> javaClass.simpleName
    }
}
