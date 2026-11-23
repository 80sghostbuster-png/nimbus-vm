package com.lex.nimbusvm

import android.view.KeyEvent

/** Android KeyEvent / OSK labels → X11 keysyms for VNC and QMP. */
object KeysymMapper {
    const val SHIFT = 0xffe1
    const val CTRL = 0xffe3
    const val ALT = 0xffe9
    const val META = 0xffeb
    const val ESC = 0xff1b
    const val TAB = 0xff09
    const val ENTER = 0xff0d
    const val BS = 0xff08
    const val DEL = 0xffff
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val HOME = 0xff50
    const val END = 0xff57
    const val PGUP = 0xff55
    const val PGDN = 0xff56
    const val SPACE = 0x20

    fun fromKeyEvent(event: KeyEvent): Int? {
        val ch = event.unicodeChar
        if (ch > 0 && ch < 0x100 && ch != 8 && ch != 9 && ch != 10 && ch != 13 && ch != 27) {
            return ch
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
            KeyEvent.KEYCODE_DEL -> BS
            KeyEvent.KEYCODE_FORWARD_DEL -> DEL
            KeyEvent.KEYCODE_TAB -> TAB
            KeyEvent.KEYCODE_ESCAPE -> ESC
            KeyEvent.KEYCODE_SPACE -> SPACE
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> HOME
            KeyEvent.KEYCODE_MOVE_END -> END
            KeyEvent.KEYCODE_PAGE_UP -> PGUP
            KeyEvent.KEYCODE_PAGE_DOWN -> PGDN
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> SHIFT
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> CTRL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> ALT
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT, KeyEvent.KEYCODE_WINDOW -> META
            KeyEvent.KEYCODE_F1 -> 0xffbe
            KeyEvent.KEYCODE_F2 -> 0xffbf
            KeyEvent.KEYCODE_F3 -> 0xffc0
            KeyEvent.KEYCODE_F4 -> 0xffc1
            KeyEvent.KEYCODE_F5 -> 0xffc2
            KeyEvent.KEYCODE_F6 -> 0xffc3
            KeyEvent.KEYCODE_F7 -> 0xffc4
            KeyEvent.KEYCODE_F8 -> 0xffc5
            KeyEvent.KEYCODE_F9 -> 0xffc6
            KeyEvent.KEYCODE_F10 -> 0xffc7
            KeyEvent.KEYCODE_F11 -> 0xffc8
            KeyEvent.KEYCODE_F12 -> 0xffc9
            else -> if (ch > 0) ch else null
        }
    }
}
