package com.srvmanager.app.terminal

import kotlin.math.max
import kotlin.math.min

/** Строка экрана: кодовые точки (0 — вторая половина широкого символа) и стили ячеек. */
class TermLine(cols: Int, fill: Long) {
    var text = IntArray(cols) { ' '.code }
    var style = LongArray(cols) { fill }
    var wrapped = false

    fun clear(from: Int, to: Int, fill: Long) {
        val end = min(to, text.size)
        for (i in max(from, 0) until end) {
            text[i] = ' '.code
            style[i] = fill
        }
    }

    fun resize(cols: Int, fill: Long) {
        if (cols == text.size) return
        val newText = IntArray(cols) { ' '.code }
        val newStyle = LongArray(cols) { fill }
        val n = min(cols, text.size)
        System.arraycopy(text, 0, newText, 0, n)
        System.arraycopy(style, 0, newStyle, 0, n)
        text = newText
        style = newStyle
    }

    fun toText(): String {
        val sb = StringBuilder()
        for (cp in text) if (cp != 0) sb.appendCodePoint(cp)
        return sb.toString().trimEnd()
    }
}

/**
 * Эмулятор терминала, совместимый с xterm-256color в объёме, нужном для shell,
 * top/htop, less, nano, vim, mc. Все публичные методы синхронизированы по [lock].
 */
class TerminalEmulator(initialCols: Int, initialRows: Int, private val maxScrollback: Int = 3000) {

    companion object {
        const val COLOR_DEFAULT = 256
        const val TRUECOLOR = 0x1000000

        const val ATTR_BOLD = 1
        const val ATTR_DIM = 2
        const val ATTR_ITALIC = 4
        const val ATTR_UNDERLINE = 8
        const val ATTR_INVERSE = 16
        const val ATTR_HIDDEN = 32
        const val ATTR_STRIKE = 64

        fun pack(fg: Int, bg: Int, attrs: Int): Long =
            (fg.toLong() and 0x1FFFFFFL) or ((bg.toLong() and 0x1FFFFFFL) shl 25) or ((attrs.toLong() and 0xFFL) shl 50)

        fun fgOf(style: Long): Int = (style and 0x1FFFFFFL).toInt()
        fun bgOf(style: Long): Int = ((style ushr 25) and 0x1FFFFFFL).toInt()
        fun attrsOf(style: Long): Int = ((style ushr 50) and 0xFFL).toInt()

        val DEFAULT_STYLE: Long = pack(COLOR_DEFAULT, COLOR_DEFAULT, 0)

        private const val ST_GROUND = 0
        private const val ST_ESC = 1
        private const val ST_CSI = 2
        private const val ST_OSC = 3
        private const val ST_OSC_ESC = 4
        private const val ST_STRING = 5
        private const val ST_STRING_ESC = 6
        private const val ST_CHARSET = 7
        private const val ST_SKIP_ONE = 8

        const val CS_ASCII = 0
        const val CS_DEC = 1

        /** DEC Special Graphics для 0x5F..0x7E (псевдографика рамок). */
        private val DEC_GRAPHICS = intArrayOf(
            0x0020, 0x25C6, 0x2592, 0x2409, 0x240C, 0x240D, 0x240A, 0x00B0,
            0x00B1, 0x2424, 0x240B, 0x2518, 0x2510, 0x250C, 0x2514, 0x253C,
            0x23BA, 0x23BB, 0x2500, 0x23BC, 0x23BD, 0x251C, 0x2524, 0x2534,
            0x252C, 0x2502, 0x2264, 0x2265, 0x03C0, 0x2260, 0x00A3, 0x00B7,
        )
    }

    val lock = Any()

    var cols = max(2, initialCols)
        private set
    var rows = max(2, initialRows)
        private set

    private var mainScreen = Array(rows) { TermLine(cols, DEFAULT_STYLE) }
    private var altScreen = Array(rows) { TermLine(cols, DEFAULT_STYLE) }
    private var screen = mainScreen
    private val scrollback = ArrayDeque<TermLine>()

    var isAltScreen = false
        private set
    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorVisible = true
        private set
    var appCursorKeys = false
        private set
    var appKeypad = false
        private set
    var bracketedPaste = false
        private set
    var title = ""
        private set

    /** Ответы терминала (DA, DSR) отправляются обратно на сервер. */
    var responder: ((ByteArray) -> Unit)? = null

    private var wrapPending = false
    private var autowrap = true
    private var insertMode = false
    private var originMode = false
    private var fg = COLOR_DEFAULT
    private var bg = COLOR_DEFAULT
    private var attrs = 0
    private var curStyle = DEFAULT_STYLE
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var tabStops = BooleanArray(cols) { it > 0 && it % 8 == 0 }
    private val charsets = intArrayOf(CS_ASCII, CS_ASCII)
    private var activeCharset = 0
    private var lastChar = ' '.code

    /** Сохранённый курсор (DECSC/DECRC); у основного и альтернативного экрана — свой, как в xterm. */
    private class SavedCursor {
        var x = 0
        var y = 0
        var fg = COLOR_DEFAULT
        var bg = COLOR_DEFAULT
        var attrs = 0
        var origin = false
        val charsets = intArrayOf(CS_ASCII, CS_ASCII)
        var activeCharset = 0
    }

    private val savedMain = SavedCursor()
    private val savedAlt = SavedCursor()
    private val saved: SavedCursor get() = if (isAltScreen) savedAlt else savedMain

    private var state = ST_GROUND
    private val params = IntArray(32)
    private var paramCount = 0
    private var curParam = -1
    private var privateMarker = 0
    private var intermediate = 0
    private var charsetTarget = 0
    private val oscBuf = StringBuilder()

    private var utf8Remaining = 0
    private var utf8Codepoint = 0
    private var utf8Min = 0

    val scrollbackSize: Int
        get() = synchronized(lock) { if (isAltScreen) 0 else scrollback.size }

    // ------------------------------------------------------------------ ввод данных

    fun append(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in offset until offset + length) decodeByte(data[i].toInt() and 0xFF)
        }
    }

    private fun decodeByte(b: Int) {
        if (utf8Remaining > 0) {
            if ((b and 0xC0) == 0x80) {
                utf8Codepoint = (utf8Codepoint shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) {
                    val cp = utf8Codepoint
                    process(if (cp < utf8Min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) 0xFFFD else cp)
                }
                return
            }
            utf8Remaining = 0
            process(0xFFFD)
        }
        when {
            b < 0x80 -> process(b)
            (b and 0xE0) == 0xC0 -> {
                utf8Remaining = 1; utf8Codepoint = b and 0x1F; utf8Min = 0x80
            }
            (b and 0xF0) == 0xE0 -> {
                utf8Remaining = 2; utf8Codepoint = b and 0x0F; utf8Min = 0x800
            }
            (b and 0xF8) == 0xF0 -> {
                utf8Remaining = 3; utf8Codepoint = b and 0x07; utf8Min = 0x10000
            }
            else -> process(0xFFFD)
        }
    }

    private fun process(cp: Int) {
        when (state) {
            ST_GROUND -> when {
                cp < 0x20 || cp == 0x7F -> control(cp)
                cp in 0x80..0x9F -> Unit
                else -> printChar(cp)
            }
            ST_ESC -> escape(cp)
            ST_CSI -> csi(cp)
            ST_OSC -> when (cp) {
                0x07 -> { finishOsc(); state = ST_GROUND }
                0x1B -> state = ST_OSC_ESC
                else -> if (oscBuf.length < 4096) oscBuf.appendCodePoint(cp)
            }
            ST_OSC_ESC -> {
                finishOsc()
                if (cp == '\\'.code) state = ST_GROUND else { state = ST_ESC; escape(cp) }
            }
            ST_STRING -> if (cp == 0x1B) state = ST_STRING_ESC else if (cp == 0x07) state = ST_GROUND
            ST_STRING_ESC -> state = if (cp == '\\'.code) ST_GROUND else ST_STRING
            ST_CHARSET -> {
                charsets[charsetTarget] = if (cp == '0'.code) CS_DEC else CS_ASCII
                state = ST_GROUND
            }
            ST_SKIP_ONE -> state = ST_GROUND
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            0x08 -> {
                wrapPending = false
                if (cursorX > 0) cursorX--
            }
            0x09 -> cursorX = nextTab(cursorX)
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> {
                cursorX = 0
                wrapPending = false
            }
            0x0E -> activeCharset = 1
            0x0F -> activeCharset = 0
            0x18, 0x1A -> state = ST_GROUND
            0x1B -> state = ST_ESC
            else -> Unit
        }
    }

    private fun escape(cp: Int) {
        state = ST_GROUND
        when (cp) {
            '['.code -> {
                state = ST_CSI
                paramCount = 0
                curParam = -1
                privateMarker = 0
                intermediate = 0
            }
            ']'.code -> {
                state = ST_OSC
                oscBuf.setLength(0)
            }
            'P'.code, '_'.code, '^'.code, 'X'.code -> state = ST_STRING
            '('.code -> { charsetTarget = 0; state = ST_CHARSET }
            ')'.code -> { charsetTarget = 1; state = ST_CHARSET }
            '*'.code, '+'.code, '-'.code, '.'.code, '/'.code, '#'.code, '%'.code, ' '.code -> state = ST_SKIP_ONE
            '7'.code -> saveCursor()
            '8'.code -> restoreCursor()
            'D'.code -> lineFeed()
            'E'.code -> {
                cursorX = 0
                lineFeed()
            }
            'H'.code -> if (cursorX < cols) tabStops[cursorX] = true
            'M'.code -> reverseIndex()
            'c'.code -> fullReset()
            '='.code -> appKeypad = true
            '>'.code -> appKeypad = false
            0x1B -> state = ST_ESC
            else -> Unit
        }
    }

    private fun finishOsc() {
        val s = oscBuf.toString()
        val sep = s.indexOf(';')
        if (sep > 0) {
            val code = s.substring(0, sep)
            if (code == "0" || code == "2") title = s.substring(sep + 1)
        }
        oscBuf.setLength(0)
    }

    // ------------------------------------------------------------------ CSI

    private fun pushParam() {
        if (paramCount < params.size) params[paramCount++] = curParam
        curParam = -1
    }

    private fun csi(cp: Int) {
        when {
            cp in 0x30..0x39 -> {
                val digit = cp - 0x30
                curParam = if (curParam < 0) digit else min(curParam * 10 + digit, 99_999)
            }
            cp == ';'.code || cp == ':'.code -> pushParam()
            cp in 0x3C..0x3F -> if (paramCount == 0 && curParam < 0) privateMarker = cp
            cp in 0x20..0x2F -> intermediate = cp
            cp in 0x40..0x7E -> {
                if (curParam >= 0 || paramCount > 0) pushParam()
                state = ST_GROUND
                dispatchCsi(cp)
            }
            cp == 0x1B -> state = ST_ESC
            cp < 0x20 -> control(cp)
            else -> Unit
        }
    }

    private fun p(index: Int, default: Int): Int {
        val v = if (index < paramCount) params[index] else -1
        return if (v < 0) default else v
    }

    /** Параметр-счётчик: отсутствующий или 0 означает 1. */
    private fun n(index: Int): Int = max(1, p(index, 1))

    private fun dispatchCsi(finalChar: Int) {
        when (privateMarker) {
            '?'.code -> {
                when (finalChar) {
                    'h'.code -> setPrivateModes(true)
                    'l'.code -> setPrivateModes(false)
                    'n'.code -> if (p(0, 0) == 6) respond("\u001b[?${cursorY + 1};${cursorX + 1}R")
                }
                return
            }
            '>'.code -> {
                if (finalChar == 'c'.code) respond("\u001b[>0;276;0c")
                return
            }
            0 -> Unit
            else -> return
        }
        if (intermediate != 0) {
            if (intermediate == '!'.code && finalChar == 'p'.code) softReset()
            return
        }
        when (finalChar) {
            '@'.code -> insertChars(n(0))
            'A'.code -> cursorUp(n(0))
            'B'.code, 'e'.code -> cursorDown(n(0))
            'C'.code, 'a'.code -> cursorForward(n(0))
            'D'.code -> cursorBack(n(0))
            'E'.code -> {
                cursorDown(n(0)); cursorX = 0
            }
            'F'.code -> {
                cursorUp(n(0)); cursorX = 0
            }
            'G'.code, '`'.code -> setCursorX(n(0) - 1)
            'H'.code, 'f'.code -> {
                setCursorX(n(1) - 1)
                setCursorY(n(0) - 1)
            }
            'I'.code -> repeat(min(n(0), cols)) { cursorX = nextTab(cursorX) }
            'J'.code -> eraseInDisplay(p(0, 0))
            'K'.code -> eraseInLine(p(0, 0))
            'L'.code -> insertLines(n(0))
            'M'.code -> deleteLines(n(0))
            'P'.code -> deleteChars(n(0))
            'S'.code -> scrollUp(n(0))
            'T'.code -> if (paramCount <= 1) scrollDown(n(0))
            'X'.code -> eraseChars(n(0))
            'Z'.code -> repeat(min(n(0), cols)) { cursorX = prevTab(cursorX) }
            'b'.code -> repeat(min(n(0), cols * rows)) { printChar(lastChar) }
            'c'.code -> respond("\u001b[?62;22c")
            'd'.code -> setCursorY(n(0) - 1)
            'g'.code -> when (p(0, 0)) {
                0 -> if (cursorX < cols) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h'.code -> setAnsiModes(true)
            'l'.code -> setAnsiModes(false)
            'm'.code -> sgr()
            'n'.code -> when (p(0, 0)) {
                5 -> respond("\u001b[0n")
                6 -> {
                    val row = if (originMode) cursorY - scrollTop else cursorY
                    respond("\u001b[${row + 1};${cursorX + 1}R")
                }
            }
            'r'.code -> setScrollRegion(p(0, 0), p(1, 0))
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
            't'.code -> if (p(0, 0) == 18) respond("\u001b[8;$rows;${cols}t")
            else -> Unit
        }
    }

    private fun respond(s: String) {
        responder?.invoke(s.toByteArray(Charsets.UTF_8))
    }

    private fun setPrivateModes(on: Boolean) {
        for (k in 0 until paramCount) {
            when (params[k]) {
                1 -> appCursorKeys = on
                6 -> {
                    originMode = on
                    setCursorX(0)
                    setCursorY(0)
                }
                7 -> autowrap = on
                25 -> cursorVisible = on
                47 -> switchScreen(on, clear = false)
                1047 -> switchScreen(on, clear = on)
                1048 -> if (on) saveCursor() else restoreCursor()
                1049 -> if (on) {
                    saveCursor()
                    switchScreen(true, clear = true)
                } else {
                    switchScreen(false, clear = false)
                    restoreCursor()
                }
                2004 -> bracketedPaste = on
                else -> Unit
            }
        }
    }

    private fun setAnsiModes(on: Boolean) {
        for (k in 0 until paramCount) {
            if (params[k] == 4) insertMode = on
        }
    }

    private fun switchScreen(alt: Boolean, clear: Boolean) {
        if (alt != isAltScreen) {
            isAltScreen = alt
            screen = if (alt) altScreen else mainScreen
        }
        if (alt && clear) {
            for (line in altScreen) {
                line.clear(0, cols, DEFAULT_STYLE)
                line.wrapped = false
            }
        }
        wrapPending = false
    }

    private fun sgr() {
        if (paramCount == 0) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < paramCount) {
            val v = max(0, params[i])
            when {
                v == 0 -> resetAttributes()
                v == 1 -> attrs = attrs or ATTR_BOLD
                v == 2 -> attrs = attrs or ATTR_DIM
                v == 3 -> attrs = attrs or ATTR_ITALIC
                v == 4 || v == 21 -> attrs = attrs or ATTR_UNDERLINE
                v == 7 -> attrs = attrs or ATTR_INVERSE
                v == 8 -> attrs = attrs or ATTR_HIDDEN
                v == 9 -> attrs = attrs or ATTR_STRIKE
                v == 22 -> attrs = attrs and (ATTR_BOLD or ATTR_DIM).inv()
                v == 23 -> attrs = attrs and ATTR_ITALIC.inv()
                v == 24 -> attrs = attrs and ATTR_UNDERLINE.inv()
                v == 27 -> attrs = attrs and ATTR_INVERSE.inv()
                v == 28 -> attrs = attrs and ATTR_HIDDEN.inv()
                v == 29 -> attrs = attrs and ATTR_STRIKE.inv()
                v in 30..37 -> fg = v - 30
                v == 38 -> {
                    val color = extendedColor(i)
                    if (color.first >= 0) fg = color.first
                    i += color.second
                }
                v == 39 -> fg = COLOR_DEFAULT
                v in 40..47 -> bg = v - 40
                v == 48 -> {
                    val color = extendedColor(i)
                    if (color.first >= 0) bg = color.first
                    i += color.second
                }
                v == 49 -> bg = COLOR_DEFAULT
                v in 90..97 -> fg = v - 90 + 8
                v in 100..107 -> bg = v - 100 + 8
                else -> Unit
            }
            i++
        }
        updateStyle()
    }

    /** Цвет 38/48;5;N или 38/48;2;R;G;B. Возвращает (цвет, сколько параметров использовано). */
    private fun extendedColor(i: Int): Pair<Int, Int> {
        if (i + 1 >= paramCount) return Pair(-1, 0)
        return when (params[i + 1]) {
            5 -> if (i + 2 < paramCount) Pair(params[i + 2].coerceIn(0, 255), 2) else Pair(-1, 1)
            2 -> if (i + 4 < paramCount) {
                val r = params[i + 2].coerceIn(0, 255)
                val g = params[i + 3].coerceIn(0, 255)
                val b = params[i + 4].coerceIn(0, 255)
                Pair(TRUECOLOR or (r shl 16) or (g shl 8) or b, 4)
            } else {
                Pair(-1, paramCount - i - 1)
            }
            else -> Pair(-1, 1)
        }
    }

    private fun resetAttributes() {
        fg = COLOR_DEFAULT
        bg = COLOR_DEFAULT
        attrs = 0
        updateStyle()
    }

    private fun updateStyle() {
        curStyle = pack(fg, bg, attrs)
    }

    /** Стиль стираемых ячеек: текущий фон (BCE), без атрибутов. */
    private fun eraseStyle(): Long = pack(COLOR_DEFAULT, bg, 0)

    // ------------------------------------------------------------------ печать и курсор

    private fun printChar(input: Int) {
        var cp = input
        if (charsets[activeCharset] == CS_DEC && cp in 0x5F..0x7E) cp = DEC_GRAPHICS[cp - 0x5F]
        val width = CharWidth.of(cp)
        if (width == 0) return
        lastChar = cp
        if (wrapPending && autowrap) {
            screen[cursorY].wrapped = true
            cursorX = 0
            lineFeed()
        }
        wrapPending = false
        if (width == 2 && cursorX >= cols - 1) {
            if (cols < 2) return
            if (autowrap) {
                screen[cursorY].text[cursorX] = ' '.code
                screen[cursorY].wrapped = true
                cursorX = 0
                lineFeed()
            } else {
                cursorX = cols - 2
            }
        }
        if (insertMode) insertChars(width)
        val line = screen[cursorY]
        // Не оставляем «половинки» широких символов.
        if (line.text[cursorX] == 0 && cursorX > 0) line.text[cursorX - 1] = ' '.code
        if (cursorX + width < cols && line.text[cursorX + width] == 0) line.text[cursorX + width] = ' '.code
        line.text[cursorX] = cp
        line.style[cursorX] = curStyle
        if (width == 2) {
            line.text[cursorX + 1] = 0
            line.style[cursorX + 1] = curStyle
        }
        val next = cursorX + width
        if (next >= cols) {
            cursorX = cols - 1
            wrapPending = autowrap
        } else {
            cursorX = next
        }
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == scrollBottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == scrollTop) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun nextTab(x: Int): Int {
        var i = x + 1
        while (i < cols - 1 && !tabStops[i]) i++
        return min(i, cols - 1)
    }

    private fun prevTab(x: Int): Int {
        var i = x - 1
        while (i > 0 && !tabStops[i]) i--
        return max(i, 0)
    }

    private fun cursorUp(count: Int) {
        val top = if (cursorY >= scrollTop) scrollTop else 0
        cursorY = max(top, cursorY - count)
        wrapPending = false
    }

    private fun cursorDown(count: Int) {
        val bottom = if (cursorY <= scrollBottom) scrollBottom else rows - 1
        cursorY = min(bottom, cursorY + count)
        wrapPending = false
    }

    private fun cursorForward(count: Int) {
        cursorX = min(cols - 1, cursorX + count)
        wrapPending = false
    }

    private fun cursorBack(count: Int) {
        cursorX = max(0, cursorX - count)
        wrapPending = false
    }

    private fun setCursorX(x: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun setCursorY(y: Int) {
        cursorY = if (originMode) (y + scrollTop).coerceIn(scrollTop, scrollBottom) else y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    private fun saveCursor() {
        val s = saved
        s.x = cursorX
        s.y = cursorY
        s.fg = fg
        s.bg = bg
        s.attrs = attrs
        s.origin = originMode
        charsets.copyInto(s.charsets)
        s.activeCharset = activeCharset
    }

    private fun restoreCursor() {
        val s = saved
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        fg = s.fg
        bg = s.bg
        attrs = s.attrs
        originMode = s.origin
        s.charsets.copyInto(charsets)
        activeCharset = s.activeCharset
        updateStyle()
        wrapPending = false
    }

    private fun setScrollRegion(top1: Int, bottom1: Int) {
        val top = (if (top1 <= 0) 1 else top1) - 1
        val bottom = (if (bottom1 <= 0) rows else min(bottom1, rows)) - 1
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
        }
        setCursorX(0)
        setCursorY(0)
    }

    // ------------------------------------------------------------------ редактирование

    private fun eraseInLine(mode: Int) {
        val line = screen[cursorY]
        val fill = eraseStyle()
        when (mode) {
            0 -> line.clear(cursorX, cols, fill)
            1 -> line.clear(0, cursorX + 1, fill)
            2 -> line.clear(0, cols, fill)
        }
        if (mode != 1) line.wrapped = false
        wrapPending = false
    }

    private fun eraseInDisplay(mode: Int) {
        val fill = eraseStyle()
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (y in cursorY + 1 until rows) {
                    screen[y].clear(0, cols, fill)
                    screen[y].wrapped = false
                }
            }
            1 -> {
                for (y in 0 until cursorY) screen[y].clear(0, cols, fill)
                eraseInLine(1)
            }
            2 -> for (y in 0 until rows) {
                screen[y].clear(0, cols, fill)
                screen[y].wrapped = false
            }
            3 -> if (!isAltScreen) scrollback.clear()
        }
        wrapPending = false
    }

    private fun insertChars(count: Int) {
        val line = screen[cursorY]
        val c = min(count, cols - cursorX)
        System.arraycopy(line.text, cursorX, line.text, cursorX + c, cols - cursorX - c)
        System.arraycopy(line.style, cursorX, line.style, cursorX + c, cols - cursorX - c)
        line.clear(cursorX, cursorX + c, eraseStyle())
        wrapPending = false
    }

    private fun deleteChars(count: Int) {
        val line = screen[cursorY]
        val c = min(count, cols - cursorX)
        System.arraycopy(line.text, cursorX + c, line.text, cursorX, cols - cursorX - c)
        System.arraycopy(line.style, cursorX + c, line.style, cursorX, cols - cursorX - c)
        line.clear(cols - c, cols, eraseStyle())
        wrapPending = false
    }

    private fun eraseChars(count: Int) {
        screen[cursorY].clear(cursorX, min(cols, cursorX + count), eraseStyle())
        wrapPending = false
    }

    private fun insertLines(count: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val c = min(count, scrollBottom - cursorY + 1)
        for (i in scrollBottom downTo cursorY + c) screen[i] = screen[i - c]
        for (i in cursorY until cursorY + c) screen[i] = TermLine(cols, eraseStyle())
        cursorX = 0
        wrapPending = false
    }

    private fun deleteLines(count: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val c = min(count, scrollBottom - cursorY + 1)
        for (i in cursorY..scrollBottom - c) screen[i] = screen[i + c]
        for (i in scrollBottom - c + 1..scrollBottom) screen[i] = TermLine(cols, eraseStyle())
        cursorX = 0
        wrapPending = false
    }

    private fun scrollUp(count: Int) {
        val c = min(count, scrollBottom - scrollTop + 1)
        repeat(c) {
            val top = screen[scrollTop]
            if (!isAltScreen && scrollTop == 0) addScrollback(top)
            for (i in scrollTop until scrollBottom) screen[i] = screen[i + 1]
            screen[scrollBottom] = TermLine(cols, eraseStyle())
        }
    }

    private fun scrollDown(count: Int) {
        val c = min(count, scrollBottom - scrollTop + 1)
        repeat(c) {
            for (i in scrollBottom downTo scrollTop + 1) screen[i] = screen[i - 1]
            screen[scrollTop] = TermLine(cols, eraseStyle())
        }
    }

    private fun addScrollback(line: TermLine) {
        scrollback.addLast(line)
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
    }

    private fun softReset() {
        cursorVisible = true
        originMode = false
        autowrap = true
        insertMode = false
        appCursorKeys = false
        appKeypad = false
        scrollTop = 0
        scrollBottom = rows - 1
        charsets[0] = CS_ASCII
        charsets[1] = CS_ASCII
        activeCharset = 0
        resetAttributes()
        wrapPending = false
    }

    private fun fullReset() {
        softReset()
        bracketedPaste = false
        isAltScreen = false
        screen = mainScreen
        for (line in mainScreen) {
            line.clear(0, cols, DEFAULT_STYLE); line.wrapped = false
        }
        for (line in altScreen) {
            line.clear(0, cols, DEFAULT_STYLE); line.wrapped = false
        }
        tabStops = BooleanArray(cols) { it > 0 && it % 8 == 0 }
        cursorX = 0
        cursorY = 0
    }

    // ------------------------------------------------------------------ размер и доступ для отрисовки

    fun resize(newCols: Int, newRows: Int) {
        synchronized(lock) {
            val nc = max(2, newCols)
            val nr = max(2, newRows)
            if (nc == cols && nr == rows) return
            if (nc != cols) {
                for (line in mainScreen) line.resize(nc, DEFAULT_STYLE)
                for (line in altScreen) line.resize(nc, DEFAULT_STYLE)
                val newTabs = BooleanArray(nc) { it > 0 && it % 8 == 0 }
                System.arraycopy(tabStops, 0, newTabs, 0, min(nc, tabStops.size))
                tabStops = newTabs
            }
            if (nr != rows) {
                // Основной экран: при уменьшении верхние строки уходят в историю,
                // при увеличении строки возвращаются из истории.
                val mainCursor = if (isAltScreen) lastUsedRow(mainScreen) else cursorY
                val newMain = ArrayList<TermLine>(nr)
                var shift = 0
                if (nr < rows) {
                    shift = max(0, mainCursor + 1 - nr)
                    for (i in 0 until shift) addScrollback(mainScreen[i])
                    for (i in shift until min(rows, shift + nr)) newMain.add(mainScreen[i])
                } else {
                    val pull = if (isAltScreen) 0 else min(nr - rows, scrollback.size)
                    val pulled = ArrayList<TermLine>(pull)
                    repeat(pull) { pulled.add(0, scrollback.removeLast()) }
                    pulled.forEach { it.resize(nc, DEFAULT_STYLE) }
                    newMain.addAll(pulled)
                    newMain.addAll(mainScreen)
                    shift = -pull
                }
                while (newMain.size < nr) newMain.add(TermLine(nc, DEFAULT_STYLE))
                mainScreen = newMain.toTypedArray()

                val newAlt = ArrayList<TermLine>(nr)
                for (i in 0 until min(nr, rows)) newAlt.add(altScreen[i])
                while (newAlt.size < nr) newAlt.add(TermLine(nc, DEFAULT_STYLE))
                altScreen = newAlt.toTypedArray()

                if (!isAltScreen) cursorY -= shift
                // Сохранённый курсор основного экрана сдвигается вместе с его строками.
                savedMain.y -= shift
                screen = if (isAltScreen) altScreen else mainScreen
            }
            cols = nc
            rows = nr
            scrollTop = 0
            scrollBottom = nr - 1
            cursorX = cursorX.coerceIn(0, nc - 1)
            cursorY = cursorY.coerceIn(0, nr - 1)
            for (s in arrayOf(savedMain, savedAlt)) {
                s.x = s.x.coerceIn(0, nc - 1)
                s.y = s.y.coerceIn(0, nr - 1)
            }
            wrapPending = false
        }
    }

    private fun lastUsedRow(lines: Array<TermLine>): Int {
        for (i in lines.indices.reversed()) {
            if (lines[i].text.any { it != ' '.code && it != 0 }) return i
        }
        return 0
    }

    /** Строка для отрисовки при прокрутке истории на [offset] строк назад. Вызывать под [lock]. */
    fun visibleLine(row: Int, offset: Int): TermLine {
        if (offset <= 0 || isAltScreen) return screen[row]
        val index = scrollback.size - offset + row
        return if (index < scrollback.size) scrollback[max(index, 0)] else screen[index - scrollback.size]
    }

    fun screenText(): String = synchronized(lock) {
        screen.joinToString("\n") { it.toText() }.trimEnd()
    }

    /** История + экран (для копирования всего вывода). */
    fun fullText(): String = synchronized(lock) {
        val sb = StringBuilder()
        if (!isAltScreen) scrollback.forEach { sb.append(it.toText()).append('\n') }
        screen.forEach { sb.append(it.toText()).append('\n') }
        sb.toString().trimEnd()
    }
}

/** Ширина символа в ячейках (упрощённый wcwidth). */
object CharWidth {
    fun of(cp: Int): Int {
        if (cp < 0x300) return 1
        if (isCombining(cp)) return 0
        if (isWide(cp)) return 2
        return 1
    }

    private fun isCombining(cp: Int): Boolean =
        cp in 0x0300..0x036F || cp in 0x0483..0x0489 || cp in 0x0591..0x05BD || cp in 0x0610..0x061A ||
            cp in 0x064B..0x065F || cp in 0x200B..0x200F || cp in 0x20D0..0x20FF || cp in 0xFE00..0xFE0F ||
            cp in 0xFE20..0xFE2F || cp in 0xE0100..0xE01EF

    private fun isWide(cp: Int): Boolean =
        cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
            cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
            cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1F64F ||
            cp in 0x1F680..0x1F6FF || cp in 0x1F900..0x1F9FF || cp in 0x20000..0x2FFFD || cp in 0x30000..0x3FFFD
}
