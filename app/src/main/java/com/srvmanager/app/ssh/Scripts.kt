package com.srvmanager.app.ssh

import com.srvmanager.app.Graph
import java.util.concurrent.ConcurrentHashMap

/** Безопасное экранирование аргументов для POSIX shell. */
object Sh {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun join(args: List<String>): String = args.joinToString(" ") { quote(it) }
}

/** Shell-скрипты из assets/scripts, параметры передаются присваиванием переменных в начале. */
object Scripts {
    private val cache = ConcurrentHashMap<String, String>()

    fun asset(path: String): String = cache.getOrPut(path) {
        Graph.app.assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) }
    }

    fun build(name: String, vars: Map<String, String> = emptyMap()): String = buildString {
        vars.forEach { (key, value) ->
            require(key.matches(Regex("^[A-Z_][A-Z0-9_]*$"))) { "Bad variable name: $key" }
            append(key).append('=').append(Sh.quote(value)).append('\n')
        }
        append(asset("scripts/$name"))
    }
}
