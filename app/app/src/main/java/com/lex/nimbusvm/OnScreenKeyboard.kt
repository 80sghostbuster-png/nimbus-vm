package com.lex.nimbusvm

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout

/** Compact QWERTY plus modifiers, sending X11 keysyms through [VncClient]. */
class OnScreenKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var client: VncClient? = null
    var shift = false
        private set
    var ctrl = false
        private set
    var alt = false
        private set

    private val sticky = mutableMapOf<Int, Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(0xF016181E.toInt())
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        val rows = listOf(
            listOf("Esc", "Tab", "Ctrl", "Alt", "Win", "↑", "↓", "←", "→", "Del"),
            listOf("1","2","3","4","5","6","7","8","9","0","-","="),
            listOf("q","w","e","r","t","y","u","i","o","p","⌫"),
            listOf("a","s","d","f","g","h","j","k","l",";","'","Ent"),
            listOf("Shift","z","x","c","v","b","n","m",",",".","/","Sp"),
        )
        for (row in rows) {
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (label in row) {
                line.addView(makeKey(label))
            }
            addView(line)
        }
    }

    private fun makeKey(label: String): Button {
        val keysym = keysymFor(label)
        val b = Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xFF2A2D36.toInt())
            setPadding(0, 0, 0, 0)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weightFor(label)).apply {
                val m = dp(2)
                setMargins(m, m, m, m)
            }
        }
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    when (label) {
                        "Shift" -> toggle(b, KeysymMapper.SHIFT) { shift = it }
                        "Ctrl" -> toggle(b, KeysymMapper.CTRL) { ctrl = it }
                        "Alt" -> toggle(b, KeysymMapper.ALT) { alt = it }
                        else -> {
                            val ks = if (shift && label.length == 1) shifted(label) else keysym
                            client?.sendKey(ks, true)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (label !in listOf("Shift", "Ctrl", "Alt")) {
                        val ks = if (shift && label.length == 1) shifted(label) else keysym
                        client?.sendKey(ks, false)
                    }
                    true
                }
                else -> true
            }
        }
        if (label in listOf("Shift", "Ctrl", "Alt")) sticky[keysym] = b
        return b
    }

    private fun toggle(b: Button, keysym: Int, set: (Boolean) -> Unit) {
        val on = b.tag as? Boolean ?: false
        val next = !on
        b.tag = next
        set(next)
        b.setBackgroundColor(if (next) 0xFFC7A452.toInt() else 0xFF2A2D36.toInt())
        b.setTextColor(if (next) 0xFF0E1014.toInt() else Color.WHITE)
        client?.sendKey(keysym, next)
    }

    private fun weightFor(label: String): Float = when (label) {
        "Sp", "Shift", "Ent", "⌫" -> 1.6f
        "Esc", "Tab", "Ctrl", "Alt", "Win" -> 1.3f
        else -> 1f
    }

    private fun keysymFor(label: String): Int = when (label) {
        "Esc" -> KeysymMapper.ESC
        "Tab" -> KeysymMapper.TAB
        "Ctrl" -> KeysymMapper.CTRL
        "Alt" -> KeysymMapper.ALT
        "Win" -> KeysymMapper.META
        "↑" -> KeysymMapper.UP
        "↓" -> KeysymMapper.DOWN
        "←" -> KeysymMapper.LEFT
        "→" -> KeysymMapper.RIGHT
        "Del" -> KeysymMapper.DEL
        "⌫" -> KeysymMapper.BS
        "Ent" -> KeysymMapper.ENTER
        "Sp" -> KeysymMapper.SPACE
        "Shift" -> KeysymMapper.SHIFT
        else -> label[0].code
    }

    private fun shifted(label: String): Int {
        val map = mapOf(
            "1" to '!'.code, "2" to '@'.code, "3" to '#'.code, "4" to '$'.code,
            "5" to '%'.code, "6" to '^'.code, "7" to '&'.code, "8" to '*'.code,
            "9" to '('.code, "0" to ')'.code, "-" to '_'.code, "=" to '+'.code,
            "[" to '{'.code, "]" to '}'.code, ";" to ':'.code, "'" to '"'.code,
            "," to '<'.code, "." to '>'.code, "/" to '?'.code,
        )
        if (label.length == 1 && label[0] in 'a'..'z') return label[0].uppercaseChar().code
        return map[label] ?: label[0].code
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
