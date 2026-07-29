package dev.jaui.squeezessh.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalRenderer
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Zeichnet ein Terminal und nimmt Eingaben entgegen.
 *
 * Termux' eigene `TerminalView` laesst sich nicht verwenden: sie haengt fest an
 * `TerminalSession`, die einen lokalen Prozess startet. Verwendet werden deshalb die
 * beiden Bausteine, die auch dort die Arbeit machen — [TerminalRenderer] fuers Zeichnen
 * und [KeyHandler] fuer die Umsetzung von Tasten in Escape-Sequenzen. Der Rest, also
 * Groessenrechnung, Gesten und Tastatur-Anbindung, steht hier.
 *
 * Die Geometrie folgt bewusst exakt der von Termux: eine abweichende Zeilenzahl faellt
 * sonst erst auf, wenn `vim` krumm zeichnet.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bridge: TerminalBridge? = null
        private set

    /** Wird gerufen, wenn der Nutzer scrollt — die Oberflaeche kann dann „springe ans Ende" anbieten. */
    var onScrollPositionChanged: ((atBottom: Boolean) -> Unit)? = null

    private var typeface: Typeface = Typeface.MONOSPACE
    private var renderer = TerminalRenderer(DEFAULT_TEXT_SIZE_PX, Typeface.MONOSPACE)
    private var lineSpacingAndAscent = 0

    /** 0 = unterster Rand, negative Werte blaettern in den Verlauf zurueck. */
    private var topRow = 0

    private var textSizePx = DEFAULT_TEXT_SIZE_PX

    /** „Klebrige" Modifikatoren aus der Zusatztastenleiste: gelten fuer genau einen Anschlag. */
    private var pendingControl = false
    private var pendingAlt = false

    private val backgroundPaint = Paint().apply { color = Color.BLACK }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        recomputeFontMetrics()
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setTextSize((textSizePx * detector.scaleFactor).roundToInt())
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                showSoftKeyboard()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val emulator = bridge?.emulator ?: return false
                val rows = (distanceY / renderer.fontLineSpacing).roundToInt()
                if (rows != 0) scrollRows(rows, emulator)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                performLongClick()
            }
        },
    )

    fun attach(bridge: TerminalBridge) {
        this.bridge = bridge
        topRow = 0
        updateSize()
        invalidate()
    }

    fun detach() {
        bridge = null
        invalidate()
    }

    /** Schriftgroesse in Pixeln; wird auf einen bedienbaren Bereich begrenzt. */
    fun setTextSize(sizePx: Int) {
        val clamped = sizePx.coerceIn(MIN_TEXT_SIZE_PX, MAX_TEXT_SIZE_PX)
        if (clamped == textSizePx) return
        textSizePx = clamped
        recomputeFontMetrics()
        updateSize()
        invalidate()
    }

    fun setTerminalTypeface(typeface: Typeface) {
        this.typeface = typeface
        recomputeFontMetrics()
        updateSize()
        invalidate()
    }

    /** Setzt Vordergrund, Hintergrund, Cursor und die 16 Grundfarben. */
    fun applyTheme(theme: TerminalTheme) {
        backgroundPaint.color = theme.background
        bridge?.emulator?.mColors?.mCurrentColors?.let { palette ->
            theme.applyTo(palette)
        }
        invalidate()
    }

    private fun recomputeFontMetrics() {
        renderer = TerminalRenderer(textSizePx, typeface)
        // TerminalRenderer haelt diesen Wert intern, gibt ihn aber nicht heraus. Er wird
        // hier mit derselben Rechnung ermittelt, sonst weicht die Zeilenzahl ab.
        val paint = Paint().apply {
            typeface = this@TerminalView.typeface
            isAntiAlias = true
            textSize = textSizePx.toFloat()
        }
        val lineSpacing = ceil(paint.fontSpacing.toDouble()).toInt()
        val ascent = ceil(paint.ascent().toDouble()).toInt()
        lineSpacingAndAscent = lineSpacing + ascent
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateSize()
    }

    /** Rechnet die Pixelmasse in Zeilen und Spalten um und meldet sie weiter. */
    fun updateSize() {
        val bridge = bridge ?: return
        if (width == 0 || height == 0) return

        val columns = (width / renderer.fontWidth).toInt().coerceAtLeast(MIN_COLUMNS)
        val rows = ((height - lineSpacingAndAscent) / renderer.fontLineSpacing)
            .coerceAtLeast(MIN_ROWS)

        bridge.resize(columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
        topRow = 0
        notifyScrollPosition()
    }

    override fun onDraw(canvas: Canvas) {
        val emulator = bridge?.emulator
        if (emulator == null) {
            canvas.drawColor(backgroundPaint.color)
            return
        }
        canvas.drawColor(backgroundPaint.color)
        renderer.render(emulator, canvas, topRow, NO_SELECTION, NO_SELECTION, NO_SELECTION, NO_SELECTION)
    }

    /** Vom Bridge-Listener aufgerufen, wenn neue Ausgabe da ist. */
    fun onScreenUpdated() = invalidate()

    // --- Scrollen ---------------------------------------------------------------------

    private fun scrollRows(rowsDown: Int, emulator: TerminalEmulator) {
        if (emulator.isAlternateBufferActive) {
            // Im Alternativpuffer (vim, less) gibt es keinen Verlauf — dort erwarten
            // Programme stattdessen Pfeiltasten.
            repeat(kotlin.math.abs(rowsDown)) {
                sendKeyCode(if (rowsDown < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            }
            return
        }
        val minimum = -emulator.screen.activeTranscriptRows
        topRow = (topRow + rowsDown).coerceIn(minimum, 0)
        notifyScrollPosition()
        invalidate()
    }

    fun scrollToBottom() {
        topRow = 0
        notifyScrollPosition()
        invalidate()
    }

    private fun notifyScrollPosition() {
        onScrollPositionChanged?.invoke(topRow == 0)
    }

    // --- Eingabe ----------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * `TYPE_NULL` sorgt dafuer, dass Tastaturen echte Tastenereignisse schicken statt
     * bearbeitbaren Text. Genau das braucht ein Terminal — es gibt hier kein Textfeld,
     * in dem sich der Cursor bewegen liesse.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.takeIf { it.isNotEmpty() }?.let(::sendText)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Zwischenstaende einer Worterkennung interessieren nicht; erst das
                // Ergebnis geht an den Server.
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { sendKeyCode(KeyEvent.KEYCODE_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bridge = bridge ?: return super.onKeyDown(keyCode, event)

        val control = pendingControl || event.isCtrlPressed
        val alt = pendingAlt || event.isAltPressed
        val shift = event.isShiftPressed

        var modifiers = 0
        if (control) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (alt) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shift) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT

        val code = KeyHandler.getCode(
            keyCode,
            modifiers,
            bridge.emulator.isCursorKeysApplicationMode,
            bridge.emulator.isKeypadApplicationMode,
        )
        if (code != null) {
            bridge.send(code)
            clearStickyModifiers()
            return true
        }

        val codePoint = event.getUnicodeChar(if (shift) KeyEvent.META_SHIFT_ON else 0)
        if (codePoint != 0) {
            inputCodePoint(codePoint, control, alt)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Setzt ein Zeichen samt Modifikatoren in Bytes um.
     *
     * Strg bildet auf die Steuerzeichen 0x00–0x1F ab, Alt stellt ESC voran — das ist die
     * Konvention, auf die sich Shells und Editoren verlassen.
     */
    fun inputCodePoint(codePoint: Int, control: Boolean, alt: Boolean) {
        val bridge = bridge ?: return
        var value = codePoint

        if (control) {
            value = when (value) {
                in 'a'.code..'z'.code -> value - 'a'.code + 1
                in 'A'.code..'Z'.code -> value - 'A'.code + 1
                ' '.code, '2'.code -> 0
                '['.code, '3'.code -> 27
                '\\'.code, '4'.code -> 28
                ']'.code, '5'.code -> 29
                '^'.code, '6'.code -> 30
                '_'.code, '7'.code, '/'.code -> 31
                '8'.code -> 127
                else -> value
            }
        }

        val bytes = if (value >= 0) {
            String(Character.toChars(value)).toByteArray(Charsets.UTF_8)
        } else {
            return
        }

        bridge.send(if (alt) byteArrayOf(ESCAPE) + bytes else bytes)
        clearStickyModifiers()
    }

    fun sendText(text: String) {
        bridge?.send(text)
        clearStickyModifiers()
    }

    fun sendKeyCode(keyCode: Int) {
        val bridge = bridge ?: return
        val code = KeyHandler.getCode(
            keyCode,
            0,
            bridge.emulator.isCursorKeysApplicationMode,
            bridge.emulator.isKeypadApplicationMode,
        ) ?: return
        bridge.send(code)
    }

    // --- Zusatztasten -----------------------------------------------------------------

    fun toggleControl(): Boolean {
        pendingControl = !pendingControl
        return pendingControl
    }

    fun toggleAlt(): Boolean {
        pendingAlt = !pendingAlt
        return pendingAlt
    }

    private fun clearStickyModifiers() {
        pendingControl = false
        pendingAlt = false
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Sichtbaren Bildschirminhalt als Text — Grundlage fuers Kopieren. */
    fun visibleText(): String {
        val emulator = bridge?.emulator ?: return ""
        return emulator.screen.getSelectedText(0, topRow, emulator.mColumns, topRow + emulator.mRows)
    }

    companion object {
        const val DEFAULT_TEXT_SIZE_PX = 32
        const val MIN_TEXT_SIZE_PX = 12
        const val MAX_TEXT_SIZE_PX = 96
        private const val MIN_COLUMNS = 4
        private const val MIN_ROWS = 4
        private const val NO_SELECTION = -1
        private const val ESCAPE: Byte = 27
    }
}
