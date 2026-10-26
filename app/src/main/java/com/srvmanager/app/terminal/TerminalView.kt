package com.srvmanager.app.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Отрисовка экрана эмулятора и приём ввода с программной/аппаратной клавиатуры. */
class TerminalView(context: Context) : View(context) {
    private var session: TerminalSession? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private val asciiRun = CharArray(1024)

    private var charWidth = 1f
    private var lineHeight = 1f
    private var baseline = 0f
    private var lastCols = -1
    private var lastRows = -1
    private var scrollOffset = 0
    private var scrollRemainder = 0f

    var fontSizeSp = 13f
        private set

    var onFontSizeChanged: ((Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            onLongPress?.invoke()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val s = session ?: return false
            scrollRemainder += distanceY
            val lines = (scrollRemainder / lineHeight).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * lineHeight
            if (s.emulator.isAltScreen) {
                // В полноэкранных программах (less, vim, mc) прокрутка = стрелки.
                val key = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                val seq = KeyEncoder.encode(key, false, false, false, s.emulator.appCursorKeys) ?: return true
                repeat(min(abs(lines), 10)) { s.writeText(seq) }
            } else {
                scrollOffset = (scrollOffset - lines).coerceIn(0, s.emulator.scrollbackSize)
                invalidate()
            }
            return true
        }
    })

    private var pendingScale = 1f

    private val scaleGestures = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pendingScale = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pendingScale *= detector.scaleFactor
            val target = (fontSizeSp * pendingScale).coerceIn(MIN_FONT, MAX_FONT)
            if (abs(target - fontSizeSp) >= 1f) {
                setFontSize(target)
                pendingScale = 1f
                onFontSizeChanged?.invoke(fontSizeSp)
            }
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setFontSize(fontSizeSp)
    }

    fun attach(newSession: TerminalSession) {
        if (session === newSession) return
        session?.onScreenUpdate = null
        session = newSession
        newSession.onScreenUpdate = { postInvalidate() }
        lastCols = -1
        lastRows = -1
        reportSize()
        invalidate()
    }

    fun detach() {
        session?.onScreenUpdate = null
    }

    fun setFontSize(sp: Float) {
        fontSizeSp = sp.coerceIn(MIN_FONT, MAX_FONT)
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        charWidth = textPaint.measureText("M")
        val metrics = textPaint.fontMetrics
        lineHeight = ceil(metrics.descent - metrics.ascent)
        baseline = -metrics.ascent
        reportSize()
        invalidate()
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportSize()
    }

    private fun reportSize() {
        val s = session ?: return
        if (width <= 0 || height <= 0) return
        val cols = max(2, (width / charWidth).toInt())
        val rows = max(2, (height / lineHeight).toInt())
        if (cols != lastCols || rows != lastRows) {
            lastCols = cols
            lastRows = rows
            s.onViewSize(cols, rows, width, height)
        }
    }

    // ------------------------------------------------------------------ отрисовка

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(TerminalColors.BACKGROUND)
        val s = session ?: return
        val emu = s.emulator
        synchronized(emu.lock) {
            val maxOffset = if (emu.isAltScreen) 0 else emu.scrollbackSize
            if (scrollOffset > maxOffset) scrollOffset = maxOffset
            val showCursor = scrollOffset == 0 && emu.cursorVisible &&
                s.status.value == TerminalSession.Status.CONNECTED
            for (row in 0 until emu.rows) {
                val line = emu.visibleLine(row, scrollOffset)
                val cursorCol = if (showCursor && row == emu.cursorY) emu.cursorX else -1
                drawLine(canvas, line, min(emu.cols, line.text.size), row * lineHeight, cursorCol)
            }
        }
        if (scrollOffset > 0) {
            // Индикатор просмотра истории.
            fillPaint.color = TerminalColors.CURSOR
            canvas.drawRect(width - 6f, 0f, width.toFloat(), 6f * resources.displayMetrics.density, fillPaint)
        }
    }

    private fun drawLine(canvas: Canvas, line: TermLine, count: Int, top: Float, cursorCol: Int) {
        var col = 0
        while (col < count) {
            val style = line.style[col]
            var end = col + 1
            if (col != cursorCol) {
                while (end < count && end != cursorCol && line.style[end] == style) end++
            }
            drawRun(canvas, line, col, end, style, col == cursorCol, top)
            col = end
        }
    }

    private fun drawRun(canvas: Canvas, line: TermLine, start: Int, end: Int, style: Long, cursor: Boolean, top: Float) {
        val attrs = TerminalEmulator.attrsOf(style)
        val bold = (attrs and TerminalEmulator.ATTR_BOLD) != 0
        var fg = TerminalColors.resolve(TerminalEmulator.fgOf(style), true, bold)
        var bg = TerminalColors.resolve(TerminalEmulator.bgOf(style), false, false)
        if ((attrs and TerminalEmulator.ATTR_INVERSE) != 0) {
            val t = fg; fg = bg; bg = t
        }
        if (cursor) {
            fg = TerminalColors.BACKGROUND
            bg = TerminalColors.CURSOR
        }
        val left = start * charWidth
        val right = end * charWidth
        if (bg != TerminalColors.BACKGROUND) {
            fillPaint.color = bg
            canvas.drawRect(left, top, right, top + lineHeight, fillPaint)
        }
        if ((attrs and TerminalEmulator.ATTR_HIDDEN) != 0) return

        textPaint.color = fg
        if ((attrs and TerminalEmulator.ATTR_DIM) != 0) textPaint.alpha = 150
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if ((attrs and TerminalEmulator.ATTR_ITALIC) != 0) -0.25f else 0f
        val y = top + baseline

        var i = start
        while (i < end) {
            val cp = line.text[i]
            when {
                cp == 0 || cp == ' '.code -> i++
                cp in 0x21..0x7E -> {
                    // Строки ASCII рисуем одним вызовом: моноширинный шрифт гарантирует шаг.
                    var j = i
                    while (j < end && j - i < asciiRun.size && line.text[j] in 0x21..0x7E) {
                        asciiRun[j - i] = line.text[j].toChar()
                        j++
                    }
                    canvas.drawText(asciiRun, 0, j - i, i * charWidth, y, textPaint)
                    i = j
                }
                else -> {
                    canvas.drawText(String(Character.toChars(cp)), i * charWidth, y, textPaint)
                    i++
                }
            }
        }
        if ((attrs and TerminalEmulator.ATTR_UNDERLINE) != 0) {
            fillPaint.color = fg
            canvas.drawRect(left, y + 2f, right, y + 2f + max(1f, lineHeight / 16f), fillPaint)
        }
        if ((attrs and TerminalEmulator.ATTR_STRIKE) != 0) {
            fillPaint.color = fg
            val mid = top + lineHeight / 2f
            canvas.drawRect(left, mid, right, mid + max(1f, lineHeight / 16f), fillPaint)
        }
    }

    // ------------------------------------------------------------------ касания

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestures.onTouchEvent(event)
        if (!scaleGestures.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------ клавиатура

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            super.commitText(text, newCursorPosition)
            flushEditable()
            return true
        }

        override fun finishComposingText(): Boolean {
            super.finishComposingText()
            flushEditable()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val editable = editable
            if (editable != null && editable.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
            // Буфер пуст — значит пользователь нажал Backspace.
            repeat(max(1, beforeLength)) { sendKey(KeyEvent.KEYCODE_DEL, ctrl = false, alt = false, shift = false) }
            return true
        }

        private fun flushEditable() {
            val editable = editable ?: return
            if (editable.isEmpty()) return
            val text = editable.toString()
            editable.clear()
            inputText(text)
        }
    }

    /** Текст с клавиатуры или панели: учитывает «залипающие» Ctrl/Alt. */
    fun inputText(text: String) {
        val s = session ?: return
        scrollToBottom()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) cp = '\r'.code
            if (s.ctrl.value) {
                cp = KeyEncoder.ctrl(cp)
                s.ctrl.value = false
            }
            if (s.alt.value) {
                sb.append('\u001b')
                s.alt.value = false
            }
            sb.appendCodePoint(cp)
        }
        s.writeText(sb.toString())
    }

    /** Специальная клавиша (стрелки, Esc, Tab, F1…) с учётом модификаторов. */
    fun sendKey(keyCode: Int, ctrl: Boolean, alt: Boolean, shift: Boolean): Boolean {
        val s = session ?: return false
        val useCtrl = ctrl || s.ctrl.value
        val useAlt = alt || s.alt.value
        val seq = KeyEncoder.encode(keyCode, useCtrl, useAlt, shift, s.emulator.appCursorKeys) ?: return false
        s.ctrl.value = false
        s.alt.value = false
        scrollToBottom()
        s.writeText(seq)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (session == null) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU ->
                return super.onKeyDown(keyCode, event)
        }
        if (sendKey(keyCode, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed)) return true
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val unicode = event.getUnicodeChar(meta)
        if (unicode != 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) == 0) {
            val s = session ?: return true
            var cp = unicode
            val ctrl = event.isCtrlPressed || s.ctrl.value
            val alt = event.isAltPressed || s.alt.value
            if (ctrl) cp = KeyEncoder.ctrl(cp)
            s.ctrl.value = false
            s.alt.value = false
            scrollToBottom()
            s.writeText((if (alt) "\u001b" else "") + String(Character.toChars(cp)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val MIN_FONT = 7f
        const val MAX_FONT = 30f
    }
}
