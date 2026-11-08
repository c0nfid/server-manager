package com.srvmanager.app.docker

import android.util.Base64
import com.srvmanager.app.Graph
import com.srvmanager.app.data.AppJson
import com.srvmanager.app.data.Elevation
import com.srvmanager.app.data.Server
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.Scripts
import com.srvmanager.app.ssh.Sh
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.security.MessageDigest

data class DockerProbe(
    val agentInstalled: Boolean,
    val agentVersion: String?,
    val dockerInstalled: Boolean,
    val clientVersion: String?,
    val serverVersion: String?,
    /** Есть ли доступ к Docker у пользователя входа (без root). */
    val userAccess: Boolean,
    /** v2 / v1 / none */
    val composeKind: String,
    val composeVersion: String?,
    val user: String,
    val inDockerGroup: Boolean,
)

data class Container(
    val id: String,
    val name: String,
    val image: String,
    val command: String,
    val state: String,
    val status: String,
    val ports: String,
    val createdAt: String,
    val labels: Map<String, String>,
) {
    val running: Boolean get() = state == "running"
    val composeProject: String? get() = labels["com.docker.compose.project"]
    val composeService: String? get() = labels["com.docker.compose.service"]
}

data class ContainerStats(val cpu: String, val memUsage: String, val memPerc: String, val netIo: String, val blockIo: String, val pids: String)

data class DockerImage(
    val id: String,
    val repository: String,
    val tag: String,
    val size: String,
    val createdSince: String,
    val containers: String,
) {
    val dangling: Boolean get() = repository == "<none>"
    /** Что передавать в docker rmi / pull. */
    val ref: String get() = if (dangling || tag == "<none>") id else "$repository:$tag"
    val title: String get() = if (dangling) "<none> ($id)" else "$repository:$tag"
}

data class DockerVolume(val name: String, val driver: String, val mountpoint: String)

data class DockerNetwork(val id: String, val name: String, val driver: String, val scope: String)

data class ComposeProject(val name: String, val status: String, val configFiles: String)

object DockerValidation {
    private val name = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val ref = Regex("^[A-Za-z0-9][A-Za-z0-9._/:@-]*$")
    private val project = Regex("^[a-z0-9][a-z0-9_-]*$")

    fun isName(v: String) = name.matches(v)
    fun isRef(v: String) = ref.matches(v)
    fun isProject(v: String) = project.matches(v)
}

class DockerClient(private val serverId: String) {
    /** true — у пользователя нет доступа к docker.sock, команды идут от root. */
    @Volatile
    var useRoot: Boolean = false
        private set

    private suspend fun run(script: String, asRoot: Boolean, timeoutMs: Long): ExecResult =
        SshManager.withConnection(serverId) { it.runScript(script, asRoot = asRoot, timeoutMs = timeoutMs) }

    private suspend fun agent(vararg args: String, timeoutMs: Long = 60_000): ExecResult =
        run("$AGENT ${Sh.join(args.toList())}", useRoot, timeoutMs)

    /** Изменяющие команды не прерываются при уходе с экрана. */
    private suspend fun agentChange(vararg args: String, timeoutMs: Long = 600_000): ExecResult =
        withContext(NonCancellable) { agent(*args, timeoutMs = timeoutMs) }

    private fun requireOk(r: ExecResult): String {
        if (!r.ok) throw IOException(r.stderr.trim().ifBlank { r.stdout.trim() }.ifBlank { "код ${r.exitCode}" })
        return r.stdout
    }

    suspend fun probe(): DockerProbe {
        val server = Graph.repo.server(serverId)
        var r = run(Scripts.build("docker_probe.sh"), asRoot = false, timeoutMs = 30_000)
        var probe = parseProbe(r.stdout)
        useRoot = false
        if (probe.agentInstalled && probe.dockerInstalled && !probe.userAccess && server != null && !server.isRoot) {
            // Нет доступа к Docker у пользователя — повторяем от root, чтобы узнать версии и работать дальше.
            r = run(Scripts.build("docker_probe.sh"), asRoot = true, timeoutMs = 30_000)
            val asRoot = parseProbe(r.stdout)
            useRoot = true
            probe = asRoot.copy(userAccess = false, user = probe.user, inDockerGroup = probe.inDockerGroup)
        }
        return probe
    }

    suspend fun containers(): List<Container> =
        requireOk(agent("ps")).lines().mapNotNull { line -> obj(line)?.let { parseContainer(it) } }

    suspend fun stats(): Map<String, ContainerStats> =
        requireOk(agent("stats", timeoutMs = 60_000)).lines().mapNotNull { line ->
            val o = obj(line) ?: return@mapNotNull null
            o.str("Name") to ContainerStats(
                cpu = o.str("CPUPerc"),
                memUsage = o.str("MemUsage"),
                memPerc = o.str("MemPerc"),
                netIo = o.str("NetIO"),
                blockIo = o.str("BlockIO"),
                pids = o.str("PIDs"),
            )
        }.toMap()

    suspend fun images(): List<DockerImage> =
        requireOk(agent("images")).lines().mapNotNull { line ->
            val o = obj(line) ?: return@mapNotNull null
            DockerImage(o.str("ID"), o.str("Repository"), o.str("Tag"), o.str("Size"), o.str("CreatedSince"), o.str("Containers"))
        }

    suspend fun volumes(): List<DockerVolume> =
        requireOk(agent("volumes")).lines().mapNotNull { line ->
            val o = obj(line) ?: return@mapNotNull null
            DockerVolume(o.str("Name"), o.str("Driver"), o.str("Mountpoint"))
        }

    suspend fun networks(): List<DockerNetwork> =
        requireOk(agent("networks")).lines().mapNotNull { line ->
            val o = obj(line) ?: return@mapNotNull null
            DockerNetwork(o.str("ID"), o.str("Name"), o.str("Driver"), o.str("Scope"))
        }

    suspend fun composeProjects(): List<ComposeProject> {
        val text = requireOk(agent("compose-ls")).trim()
        if (text.isEmpty()) return emptyList()
        val array = AppJson.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ComposeProject(o.str("Name"), o.str("Status"), o.str("ConfigFiles"))
        }
    }

    suspend fun logs(container: String, lines: Int = 200): String {
        require(DockerValidation.isName(container)) { "Неверное имя контейнера" }
        return requireOk(agent("logs", container, lines.toString(), timeoutMs = 60_000))
    }

    suspend fun inspect(container: String): String {
        require(DockerValidation.isName(container)) { "Неверное имя контейнера" }
        return requireOk(agent("inspect", container))
    }

    suspend fun containerAction(action: String, container: String): ExecResult {
        require(action in CONTAINER_ACTIONS) { "Неизвестное действие" }
        require(DockerValidation.isName(container)) { "Неверное имя контейнера" }
        return agentChange("container", action, container, timeoutMs = 180_000)
    }

    suspend fun pullImage(ref: String): ExecResult {
        require(DockerValidation.isRef(ref)) { "Неверное имя образа" }
        return agentChange("image-pull", ref)
    }

    suspend fun removeImage(ref: String): ExecResult {
        require(DockerValidation.isRef(ref)) { "Неверное имя образа" }
        return agentChange("image-rm", ref, timeoutMs = 180_000)
    }

    suspend fun prune(kind: String): ExecResult {
        require(kind in listOf("images", "images-all", "containers", "volumes", "networks"))
        return agentChange("prune", kind)
    }

    suspend fun compose(action: String, project: ComposeProject): ExecResult {
        require(action in COMPOSE_ACTIONS) { "Неизвестное действие" }
        require(DockerValidation.isProject(project.name)) { "Неверное имя проекта" }
        return agentChange("compose", action, project.name, project.configFiles)
    }

    suspend fun composeLogs(project: ComposeProject): String {
        require(DockerValidation.isProject(project.name)) { "Неверное имя проекта" }
        return requireOk(agent("compose", "logs", project.name, project.configFiles, timeoutMs = 60_000))
    }

    suspend fun installAgent(): ExecResult {
        val bytes = bundledAgent()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val script = Scripts.build(
            "docker_agent_install.sh",
            mapOf("AG_B64" to Base64.encodeToString(bytes, Base64.NO_WRAP), "AG_SHA256" to sha),
        )
        return withContext(NonCancellable) { run(script, asRoot = true, timeoutMs = 120_000) }
    }

    suspend fun installDocker(addUser: String?): ExecResult {
        val vars = if (addUser.isNullOrBlank()) emptyMap() else mapOf("ADDUSER" to addUser)
        return withContext(NonCancellable) { run(Scripts.build("docker_install.sh", vars), asRoot = true, timeoutMs = 1_200_000) }
    }

    suspend fun addToDockerGroup(user: String): ExecResult {
        val r = withContext(NonCancellable) {
            run(Scripts.build("docker_group.sh", mapOf("ADDUSER" to user)), asRoot = true, timeoutMs = 60_000)
        }
        // Новая группа действует только в новой SSH-сессии.
        if (r.ok) SshManager.disconnect(serverId)
        return r
    }

    companion object {
        const val AGENT = "/usr/local/bin/srvmgr-docker"
        val CONTAINER_ACTIONS = listOf("start", "stop", "restart", "kill", "pause", "unpause", "rm", "rm-force")
        val COMPOSE_ACTIONS = listOf("up", "down", "pull", "restart", "stop", "start")

        fun bundledAgent(): ByteArray = Graph.app.assets.open("docker/srvmgr-docker.sh").use { it.readBytes() }

        val bundledVersion: String by lazy {
            Regex("^VERSION=([0-9.]+)", RegexOption.MULTILINE).find(String(bundledAgent(), Charsets.UTF_8))
                ?.groupValues?.get(1) ?: "?"
        }

        /** Команда для терминала: shell внутри контейнера (bash, если есть) с учётом способа получения root. */
        fun execCommand(server: Server, container: String, useRoot: Boolean): String {
            require(DockerValidation.isName(container))
            val exec = "docker exec -it $container sh -c \"command -v bash >/dev/null && exec bash || exec sh\""
            if (!useRoot || server.isRoot) return exec
            return when (server.elevation) {
                Elevation.SUDO -> "sudo $exec"
                Elevation.SUDO_SU -> "sudo su -c ${Sh.quote(exec)}"
                Elevation.SU -> "su -c ${Sh.quote(exec)}"
            }
        }

        private fun obj(line: String): JsonObject? {
            val t = line.trim()
            if (!t.startsWith("{")) return null
            return try {
                AppJson.parseToJsonElement(t) as? JsonObject
            } catch (e: Exception) {
                null
            }
        }

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        private fun parseContainer(o: JsonObject): Container {
            val labels = o.str("Labels").split(',').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            return Container(
                id = o.str("ID"),
                name = o.str("Names").substringBefore(','),
                image = o.str("Image"),
                command = o.str("Command").trim('"'),
                state = o.str("State"),
                status = o.str("Status"),
                ports = o.str("Ports"),
                createdAt = o.str("CreatedAt"),
                labels = labels,
            )
        }

        fun parseProbe(output: String): DockerProbe {
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
            fun first(key: String) = sections[key]?.firstOrNull()
            val compose = first("COMPOSE").orEmpty()
            return DockerProbe(
                agentInstalled = !sections.containsKey("NOAGENT"),
                agentVersion = first("AGENT"),
                dockerInstalled = first("DOCKER") == "yes",
                clientVersion = first("CLIENT"),
                serverVersion = first("SERVER"),
                userAccess = first("ACCESS") == "ok",
                composeKind = compose.substringBefore(' ').ifEmpty { "none" },
                composeVersion = compose.substringAfter(' ', "").ifBlank { null },
                user = first("USER").orEmpty(),
                inDockerGroup = first("GROUP") == "yes",
            )
        }
    }
}
