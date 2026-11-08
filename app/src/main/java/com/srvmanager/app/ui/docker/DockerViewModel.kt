package com.srvmanager.app.ui.docker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srvmanager.app.Graph
import com.srvmanager.app.docker.ComposeProject
import com.srvmanager.app.docker.Container
import com.srvmanager.app.docker.ContainerStats
import com.srvmanager.app.docker.DockerClient
import com.srvmanager.app.docker.DockerImage
import com.srvmanager.app.docker.DockerNetwork
import com.srvmanager.app.docker.DockerProbe
import com.srvmanager.app.docker.DockerVolume
import com.srvmanager.app.ssh.ExecResult
import com.srvmanager.app.ssh.friendlyMessage
import com.srvmanager.app.ui.common.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DockerViewModel(private val serverId: String) : ViewModel() {
    enum class Tab(val title: String) { CONTAINERS("Контейнеры"), IMAGES("Образы"), COMPOSE("Compose"), STORAGE("Тома и сети") }

    /** Открытый на весь экран объект: логи/inspect контейнера или логи compose-проекта. */
    data class Detail(val title: String, val text: String?, val loading: Boolean = true)

    data class UiState(
        val probe: DockerProbe? = null,
        val probing: Boolean = true,
        val probeError: Throwable? = null,
        val tab: Tab = Tab.CONTAINERS,
        val loading: Boolean = false,
        val error: Throwable? = null,
        val containers: List<Container> = emptyList(),
        val stats: Map<String, ContainerStats> = emptyMap(),
        val images: List<DockerImage> = emptyList(),
        val projects: List<ComposeProject> = emptyList(),
        val volumes: List<DockerVolume> = emptyList(),
        val networks: List<DockerNetwork> = emptyList(),
        val busy: String? = null,
        val lastStep: Step? = null,
        val detail: Detail? = null,
    )

    val client = DockerClient(serverId)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        probe()
    }

    fun probe() {
        _state.update { it.copy(probing = true, probeError = null) }
        viewModelScope.launch {
            try {
                val probe = client.probe()
                _state.update { it.copy(probing = false, probe = probe) }
                if (probe.agentInstalled && probe.dockerInstalled) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(probing = false, probeError = e) }
            }
        }
    }

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab, error = null) }
        refresh()
    }

    fun refresh() {
        val probe = _state.value.probe ?: return
        if (!probe.agentInstalled || !probe.dockerInstalled) return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                when (_state.value.tab) {
                    Tab.CONTAINERS -> {
                        val containers = client.containers()
                        _state.update { it.copy(containers = containers) }
                        // Статистика собирается ~2 с, поэтому загружается вторым шагом.
                        val stats = if (containers.any { it.running }) client.stats() else emptyMap()
                        _state.update { it.copy(stats = stats) }
                    }
                    Tab.IMAGES -> {
                        val images = client.images()
                        _state.update { it.copy(images = images) }
                    }
                    Tab.COMPOSE -> {
                        val projects = if (probe.composeKind == "v2") client.composeProjects() else emptyList()
                        _state.update { it.copy(projects = projects) }
                    }
                    Tab.STORAGE -> {
                        val volumes = client.volumes()
                        val networks = client.networks()
                        _state.update { it.copy(volumes = volumes, networks = networks) }
                    }
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun operation(title: String, reprobe: Boolean = false, block: suspend () -> ExecResult) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = title, lastStep = Step(title, null)) }
        Graph.appScope.launch {
            val step = try {
                val r = block()
                Step(title, r.ok, r.combined.trim().takeLast(4000))
            } catch (e: Exception) {
                Step(title, false, e.friendlyMessage())
            }
            _state.update { it.copy(busy = null, lastStep = step) }
            if (reprobe) probe() else refresh()
        }
    }

    fun dismissStep() = _state.update { it.copy(lastStep = null) }

    fun installAgent() = operation("Установка модуля srvmgr-docker ${DockerClient.bundledVersion}", reprobe = true) {
        client.installAgent()
    }

    fun installDocker(addUser: String?) = operation("Установка Docker (get.docker.com)", reprobe = true) {
        client.installDocker(addUser)
    }

    fun addToGroup(user: String) = operation("Добавление $user в группу docker", reprobe = true) {
        client.addToDockerGroup(user)
    }

    fun containerAction(action: String, container: Container) =
        operation("docker $action ${container.name}") { client.containerAction(action, container.name) }

    fun pull(ref: String) = operation("docker pull $ref") { client.pullImage(ref) }

    fun removeImage(image: DockerImage) = operation("docker rmi ${image.title}") { client.removeImage(image.ref) }

    fun prune(kind: String) = operation("Очистка: $kind") { client.prune(kind) }

    fun compose(action: String, project: ComposeProject) =
        operation("docker compose $action (${project.name})") { client.compose(action, project) }

    private fun showDetail(title: String, load: suspend () -> String) {
        _state.update { it.copy(detail = Detail(title, null)) }
        viewModelScope.launch {
            val text = try {
                load().ifBlank { "(пусто)" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Ошибка: ${e.friendlyMessage()}"
            }
            _state.update { s -> if (s.detail?.title == title) s.copy(detail = Detail(title, text, loading = false)) else s }
        }
    }

    fun showLogs(container: Container) = showDetail("Логи ${container.name}") { client.logs(container.name) }

    fun showInspect(container: Container) = showDetail("inspect ${container.name}") { client.inspect(container.name) }

    fun showComposeLogs(project: ComposeProject) = showDetail("Логи ${project.name}") { client.composeLogs(project) }

    fun closeDetail() = _state.update { it.copy(detail = null) }
}
