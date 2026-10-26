package com.srvmanager.app.ui.terminal

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srvmanager.app.Graph
import com.srvmanager.app.Prefs
import com.srvmanager.app.terminal.TerminalColors
import com.srvmanager.app.terminal.TerminalSession
import com.srvmanager.app.terminal.TerminalView
import com.srvmanager.app.ui.common.AppScaffold
import com.srvmanager.app.ui.common.ErrorCard
import com.srvmanager.app.ui.common.copyToClipboard

class TerminalViewModel(serverId: String) : ViewModel() {
    val session = TerminalSession(serverId)

    override fun onCleared() {
        session.close()
    }
}

/** Ссылка на View без участия в рекомпозиции. */
private class ViewRef {
    var view: TerminalView? = null
}

private sealed interface ExtraKey {
    val label: String

    data class Special(override val label: String, val keyCode: Int) : ExtraKey
    data class Chars(override val label: String, val text: String) : ExtraKey
    data object Ctrl : ExtraKey {
        override val label = "CTRL"
    }
    data object Alt : ExtraKey {
        override val label = "ALT"
    }
}

private val EXTRA_KEYS: List<ExtraKey> = listOf(
    ExtraKey.Special("ESC", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Ctrl,
    ExtraKey.Alt,
    ExtraKey.Special("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Special("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Special("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Special("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Special("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Chars("-", "-"),
    ExtraKey.Chars("/", "/"),
    ExtraKey.Chars("|", "|"),
    ExtraKey.Chars("~", "~"),
    ExtraKey.Special("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Special("END", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Special("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Special("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Special("DEL", KeyEvent.KEYCODE_FORWARD_DEL),
    ExtraKey.Special("F1", KeyEvent.KEYCODE_F1),
    ExtraKey.Special("F2", KeyEvent.KEYCODE_F2),
    ExtraKey.Special("F3", KeyEvent.KEYCODE_F3),
    ExtraKey.Special("F4", KeyEvent.KEYCODE_F4),
    ExtraKey.Special("F5", KeyEvent.KEYCODE_F5),
    ExtraKey.Special("F6", KeyEvent.KEYCODE_F6),
    ExtraKey.Special("F7", KeyEvent.KEYCODE_F7),
    ExtraKey.Special("F8", KeyEvent.KEYCODE_F8),
    ExtraKey.Special("F9", KeyEvent.KEYCODE_F9),
    ExtraKey.Special("F10", KeyEvent.KEYCODE_F10),
    ExtraKey.Special("F11", KeyEvent.KEYCODE_F11),
    ExtraKey.Special("F12", KeyEvent.KEYCODE_F12),
)

@Composable
fun TerminalScreen(serverId: String, onBack: () -> Unit) {
    val vm: TerminalViewModel = viewModel(key = "terminal-$serverId") { TerminalViewModel(serverId) }
    val session = vm.session
    val status by session.status.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    val server = remember(serverId) { Graph.repo.server(serverId) }
    val context = LocalContext.current
    val viewRef = remember { ViewRef() }
    var menu by remember { mutableStateOf(false) }

    val statusText = when (status) {
        TerminalSession.Status.IDLE, TerminalSession.Status.CONNECTING -> "подключение…"
        TerminalSession.Status.CONNECTED -> server?.address.orEmpty()
        TerminalSession.Status.CLOSED -> "сессия завершена"
        TerminalSession.Status.ERROR -> "ошибка"
    }

    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) session.paste(text)
    }

    fun changeFont(delta: Float) {
        val view = viewRef.view ?: return
        view.setFontSize(view.fontSizeSp + delta)
        Prefs.terminalFontSp = view.fontSizeSp
    }

    AppScaffold(
        title = server?.displayName ?: "Терминал",
        subtitle = statusText,
        onBack = onBack,
        actions = {
            IconButton(onClick = { paste() }) { Icon(Icons.Filled.ContentPaste, contentDescription = "Вставить") }
            IconButton(onClick = { viewRef.view?.showKeyboard() }) {
                Icon(Icons.Filled.Keyboard, contentDescription = "Клавиатура")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Крупнее шрифт") }, onClick = { changeFont(1f) })
                    DropdownMenuItem(text = { Text("Мельче шрифт") }, onClick = { changeFont(-1f) })
                    DropdownMenuItem(text = { Text("Копировать экран") }, onClick = {
                        menu = false
                        copyToClipboard(context, "terminal", session.emulator.screenText())
                    })
                    DropdownMenuItem(text = { Text("Копировать весь вывод") }, onClick = {
                        menu = false
                        copyToClipboard(context, "terminal", session.emulator.fullText())
                    })
                    DropdownMenuItem(text = { Text("Переподключиться") }, onClick = {
                        menu = false
                        session.reconnect()
                    })
                    DropdownMenuItem(text = { Text("Скрыть клавиатуру") }, onClick = {
                        menu = false
                        viewRef.view?.hideKeyboard()
                    })
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color(TerminalColors.BACKGROUND))) {
            if (status == TerminalSession.Status.CONNECTING || status == TerminalSession.Status.IDLE) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            val err = error
            if (status == TerminalSession.Status.ERROR && err != null) {
                ErrorCard(err, onRetry = { session.reconnect() }, modifier = Modifier.padding(8.dp))
            }
            if (status == TerminalSession.Status.CLOSED) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Сессия завершена", color = Color(TerminalColors.FOREGROUND))
                    Button(onClick = { session.reconnect() }) { Text("Переподключиться") }
                }
            }
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx).apply {
                        setFontSize(Prefs.terminalFontSp)
                        onFontSizeChanged = { Prefs.terminalFontSp = it }
                        onLongPress = { paste() }
                        attach(session)
                        viewRef.view = this
                        post { showKeyboard() }
                    }
                },
                update = { it.attach(session) },
                onRelease = {
                    it.detach()
                    if (viewRef.view === it) viewRef.view = null
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ExtraKeysBar(
                session = session,
                onSpecial = { code -> viewRef.view?.sendKey(code, ctrl = false, alt = false, shift = false) },
                onText = { text -> viewRef.view?.inputText(text) },
            )
        }
    }
}

@Composable
private fun ExtraKeysBar(session: TerminalSession, onSpecial: (Int) -> Unit, onText: (String) -> Unit) {
    val ctrl by session.ctrl
    val alt by session.alt
    Surface(color = Color(0xFF22252B)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EXTRA_KEYS.forEach { key ->
                val active = (key is ExtraKey.Ctrl && ctrl) || (key is ExtraKey.Alt && alt)
                Box(
                    Modifier
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                        .widthIn(min = 44.dp)
                        .height(36.dp)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary else Color(0xFF30343B),
                            MaterialTheme.shapes.small,
                        )
                        .clickable {
                            when (key) {
                                is ExtraKey.Special -> onSpecial(key.keyCode)
                                is ExtraKey.Chars -> onText(key.text)
                                ExtraKey.Ctrl -> {
                                    session.ctrl.value = !session.ctrl.value
                                }
                                ExtraKey.Alt -> {
                                    session.alt.value = !session.alt.value
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        key.label,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else Color(0xFFE6E6E6),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
