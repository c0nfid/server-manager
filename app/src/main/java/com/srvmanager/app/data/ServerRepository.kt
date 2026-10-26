package com.srvmanager.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerRepository(
    private val store: SecureStore,
    private val scope: CoroutineScope,
) {
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> = _data.asStateFlow()

    /** Сообщение, если сохранённые данные не удалось прочитать при запуске. */
    var loadError: String? = null
        private set

    private val saveMutex = Mutex()

    init {
        try {
            store.read()?.let { _data.value = AppJson.decodeFromString(AppData.serializer(), it) }
        } catch (e: Exception) {
            loadError = "Не удалось расшифровать сохранённые данные (${e.javaClass.simpleName}). " +
                "Повреждённый файл отложен, восстановите данные из резервной копии."
            store.quarantine()
        }
    }

    private fun persist() {
        scope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                // Всегда пишем актуальное состояние: последняя запись содержит все изменения.
                runCatching { store.write(AppJson.encodeToString(AppData.serializer(), _data.value)) }
                    .onFailure { Log.e("ServerRepository", "Не удалось сохранить данные", it) }
            }
        }
    }

    private fun mutate(block: (AppData) -> AppData) {
        _data.update(block)
        persist()
    }

    fun server(id: String): Server? = _data.value.servers.firstOrNull { it.id == id }

    fun key(id: String?): SshKey? = id?.let { keyId -> _data.value.keys.firstOrNull { it.id == keyId } }

    fun serverFlow(id: String): Flow<Server?> =
        data.map { d -> d.servers.firstOrNull { it.id == id } }.distinctUntilChanged()

    fun upsertServer(server: Server) = mutate { d ->
        val list = d.servers.toMutableList()
        val index = list.indexOfFirst { it.id == server.id }
        if (index >= 0) list[index] = server else list.add(server)
        d.copy(servers = list)
    }

    fun updateServer(id: String, transform: (Server) -> Server) = mutate { d ->
        d.copy(servers = d.servers.map { if (it.id == id) transform(it) else it })
    }

    fun deleteServer(id: String) = mutate { d -> d.copy(servers = d.servers.filterNot { it.id == id }) }

    fun upsertKey(key: SshKey) = mutate { d ->
        val list = d.keys.toMutableList()
        val index = list.indexOfFirst { it.id == key.id }
        if (index >= 0) list[index] = key else list.add(key)
        d.copy(keys = list)
    }

    /** Удаляет ключ; серверы, которые его использовали, переводятся на вход по паролю. */
    fun deleteKey(id: String) = mutate { d ->
        d.copy(
            keys = d.keys.filterNot { it.id == id },
            servers = d.servers.map {
                if (it.keyId == id) it.copy(keyId = null, authType = AuthType.PASSWORD) else it
            },
        )
    }

    fun replaceAll(newData: AppData) = mutate { newData }

    /** Объединение с импортом: записи с совпадающим id заменяются импортированными. */
    fun merge(incoming: AppData) = mutate { d ->
        val servers = LinkedHashMap<String, Server>()
        d.servers.forEach { servers[it.id] = it }
        incoming.servers.forEach { servers[it.id] = it }
        val keys = LinkedHashMap<String, SshKey>()
        d.keys.forEach { keys[it.id] = it }
        incoming.keys.forEach { keys[it.id] = it }
        AppData(servers = servers.values.toList(), keys = keys.values.toList())
    }
}
