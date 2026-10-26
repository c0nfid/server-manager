package com.srvmanager.app.terminal

import android.view.KeyEvent

/** Палитра терминала (Tomorrow Night) и 256-цветная таблица xterm. */
object TerminalColors {
    const val BACKGROUND = 0xFF15171C.toInt()
    const val FOREGROUND = 0xFFD5D8DC.toInt()
    const val CURSOR = 0xFF8AB4F8.toInt()

    private val ANSI = intArrayOf(
        0xFF1D1F21.toInt(), 0xFFCC6666.toInt(), 0xFFB5BD68.toInt(), 0xFFF0C674.toInt(),
        0xFF81A2BE.toInt(), 0xFFB294BB.toInt(), 0xFF8ABEB7.toInt(), 0xFFC5C8C6.toInt(),
        0xFF707880.toInt(), 0xFFE06C75.toInt(), 0xFFC3E88D.toInt(), 0xFFFFCB6B.toInt(),
        0xFF82AAFF.toInt(), 0xFFC792EA.toInt(), 0xFF89DDFF.toInt(), 0xFFFFFFFF.toInt(),
    )

    val PALETTE: IntArray = IntArray(256) { i ->
        when {
            i < 16 -> ANSI[i]
            i < 232 -> {
                val c = i - 16
                val levels = intArrayOf(0, 95, 135, 175, 215, 255)
                val r = levels[c / 36]
                val g = levels[(c / 6) % 6]
                val b = levels[c % 6]
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> {
                val v = 8 + (i - 232) * 10
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
    }

    fun resolve(color: Int, foreground: Boolean, bold: Boolean): Int = when {
        color == TerminalEmulator.COLOR_DEFAULT -> if (foreground) FOREGROUND else BACKGROUND
        (color and TerminalEmulator.TRUECOLOR) != 0 -> (0xFF shl 24) or (color and 0xFFFFFF)
        foreground && bold && color < 8 -> PALETTE[color + 8]
        else -> PALETTE[color and 0xFF]
    }
}

/** Преобразование клавиш Android в escape-последовательности xterm. */
object KeyEncoder {
    fun encode(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean, appCursorKeys: Boolean): String? {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun cursor(c: Char): String = when {
            mod > 1 -> "\u001b[1;$mod$c"
            appCursorKeys -> "\u001bO$c"
            else -> "\u001b[$c"
        }
        fun tilde(n: Int): String = if (mod > 1) "\u001b[$n;$mod~" else "\u001b[$n~"
        fun ss3(c: Char): String = if (mod > 1) "\u001b[1;$mod$c" else "\u001bO$c"
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> cursor('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> cursor('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursor('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> cursor('D')
            KeyEvent.KEYCODE_MOVE_HOME -> cursor('H')
            KeyEvent.KEYCODE_MOVE_END -> cursor('F')
            KeyEvent.KEYCODE_PAGE_UP -> tilde(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> tilde(6)
            KeyEvent.KEYCODE_INSERT -> tilde(2)
            KeyEvent.KEYCODE_FORWARD_DEL -> tilde(3)
            KeyEvent.KEYCODE_F1 -> ss3('P')
            KeyEvent.KEYCODE_F2 -> ss3('Q')
            KeyEvent.KEYCODE_F3 -> ss3('R')
            KeyEvent.KEYCODE_F4 -> ss3('S')
            KeyEvent.KEYCODE_F5 -> tilde(15)
            KeyEvent.KEYCODE_F6 -> tilde(17)
            KeyEvent.KEYCODE_F7 -> tilde(18)
            KeyEvent.KEYCODE_F8 -> tilde(19)
            KeyEvent.KEYCODE_F9 -> tilde(20)
            KeyEvent.KEYCODE_F10 -> tilde(21)
            KeyEvent.KEYCODE_F11 -> tilde(23)
            KeyEvent.KEYCODE_F12 -> tilde(24)
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (alt) "\u001b\r" else "\r"
            KeyEvent.KEYCODE_DEL -> (if (alt) "\u001b" else "") + (if (ctrl) "\u0008" else "\u007f")
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            else -> null
        }
    }

    /** Ctrl+символ → управляющий код (Ctrl+C = 0x03 и т.д.). */
    fun ctrl(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '2'.code, '@'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code, '-'.code -> 31
        '8'.code, '?'.code -> 127
        else -> cp
    }
}
