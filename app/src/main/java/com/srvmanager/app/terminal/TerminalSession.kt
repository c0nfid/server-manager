package com.srvmanager.app.terminal

import androidx.compose.runtime.mutableStateOf
import com.jcraft.jsch.ChannelShell
import com.srvmanager.app.ssh.SshManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Интерактивная SSH-сессия (канал shell с PTY), связанная с эмулятором. */
class TerminalSession(private val serverId: String) {
    enum class Status { IDLE, CONNECTING, CONNECTED, CLOSED, ERROR }

    val emulator = TerminalEmulator(80, 24)
    val status = MutableStateFlow(Status.IDLE)
    val error = MutableStateFlow<Throwable?>(null)

    /** «Залипающие» модификаторы с панели дополнительных клавиш. */
    val ctrl = mutableStateOf(false)
    val alt = mutableStateOf(false)

    @Volatile
    var onScreenUpdate: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()
    private var readerJob: Job? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var cols = 80

    @Volatile
    private var rows = 24

    @Volatile
    private var widthPx = 0

    @Volatile
    private var heightPx = 0

    init {
        emulator.responder = { bytes -> write(bytes) }
    }

    /** Вызывается View при изменении размера; первое измерение запускает подключение. */
    fun onViewSize(newCols: Int, newRows: Int, width: Int, height: Int) {
        cols = newCols
        rows = newRows
        widthPx = width
        heightPx = height
        emulator.resize(newCols, newRows)
        onScreenUpdate?.invoke()
        when (status.value) {
            Status.IDLE -> connect()
            Status.CONNECTED -> execute { channel?.setPtySize(newCols, newRows, width, height) }
            else -> Unit
        }
    }

    /** Номер текущего подключения: устаревшие корутины чтения не трогают состояние. */
    @Volatile
    private var generation = 0

    fun connect() {
        val current = status.value
        if (current == Status.CONNECTING || current == Status.CONNECTED) return
        status.value = Status.CONNECTING
        error.value = null
        val gen = ++generation
        readerJob = scope.launch {
            var opened: ChannelShell? = null
            try {
                val (shell, input) = SshManager.withConnection(serverId) { it.openShell(cols, rows, widthPx, heightPx) }
                opened = shell
                if (gen != generation) return@launch
                channel = shell
                output = shell.outputStream
                status.value = Status.CONNECTED
                // Размер View мог измениться, пока открывался канал (анимация клавиатуры).
                execute { shell.setPtySize(cols, rows, widthPx, heightPx) }
                val buffer = ByteArray(16 * 1024)
                while (isActive) {
                    val n = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        -1
                    }
                    if (n < 0) break
                    if (n > 0) {
                        emulator.append(buffer, 0, n)
                        onScreenUpdate?.invoke()
                    }
                }
                if (gen == generation) status.value = Status.CLOSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) {
                    error.value = e
                    status.value = Status.ERROR
                }
            } finally {
                if (gen == generation) {
                    output = null
                    channel = null
                }
                opened?.disconnect()
                onScreenUpdate?.invoke()
            }
        }
    }

    fun reconnect() {
        generation++
        readerJob?.cancel()
        val old = channel
        channel = null
        output = null
        runCatching { old?.disconnect() }
        status.value = Status.IDLE
        connect()
    }

    private fun execute(task: () -> Unit) {
        if (writer.isShutdown) return
        try {
            writer.execute {
                try {
                    task()
                } catch (e: Exception) {
                    // Канал закрыт — ошибка отобразится через статус сессии.
                }
            }
        } catch (e: Exception) {
            // Исполнитель уже остановлен.
        }
    }

    fun write(bytes: ByteArray) {
        execute {
            val out = output ?: return@execute
            out.write(bytes)
            out.flush()
        }
    }

    fun writeText(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun paste(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        if (emulator.bracketedPaste) writeText("\u001b[200~$normalized\u001b[201~") else writeText(normalized)
    }

    fun close() {
        readerJob?.cancel()
        runCatching { channel?.disconnect() }
        writer.shutdownNow()
        scope.cancel()
    }
}
