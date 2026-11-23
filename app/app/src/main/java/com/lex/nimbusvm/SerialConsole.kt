package com.lex.nimbusvm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.view.KeyEvent
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interactive connection to QEMU's serial chardev unix socket.
 * Guest output is also copied by QEMU into the logfile the Serial panel tails.
 */
class SerialConsole(private val unixPath: String) {
    private var sock: LocalSocket? = null
    private var out: OutputStream? = null
    private val lock = Any()
    private val open = AtomicBoolean(false)

    val isConnected: Boolean get() = open.get()

    fun connect() {
        close()
        val ls = LocalSocket()
        ls.connect(LocalSocketAddress(unixPath, LocalSocketAddress.Namespace.FILESYSTEM))
        sock = ls
        out = ls.outputStream
        open.set(true)
    }

    fun close() {
        open.set(false)
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        out = null
    }

    fun write(data: ByteArray) {
        if (data.isEmpty() || !open.get()) return
        synchronized(lock) {
            try {
                out?.write(data)
                out?.flush()
            } catch (_: Exception) {
                open.set(false)
            }
        }
    }

    fun sendKeysym(keysym: Int, down: Boolean) {
        if (!down) return
        bytesForKeysym(keysym)?.let { write(it) }
    }

    fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.action == KeyEvent.ACTION_UP
        }
        val bytes = bytesForKeyEvent(event) ?: return false
        write(bytes)
        return true
    }

    companion object {
        fun bytesForKeysym(keysym: Int): ByteArray? {
            when (keysym) {
                0xff0d, 0xff8d -> return byteArrayOf('\r'.code.toByte()) // Return / KP Enter
                0xff08 -> return byteArrayOf(0x7f) // BackSpace
                0xffff -> return byteArrayOf(0x1b, 0x5b, 0x33, 0x7e) // Delete
                0xff09 -> return byteArrayOf('\t'.code.toByte())
                0xff1b -> return byteArrayOf(0x1b)
                0xff51 -> return byteArrayOf(0x1b, 0x5b, 'D'.code.toByte()) // Left
                0xff52 -> return byteArrayOf(0x1b, 0x5b, 'A'.code.toByte()) // Up
                0xff53 -> return byteArrayOf(0x1b, 0x5b, 'C'.code.toByte()) // Right
                0xff54 -> return byteArrayOf(0x1b, 0x5b, 'B'.code.toByte()) // Down
                0xff50 -> return byteArrayOf(0x1b, 0x5b, 'H'.code.toByte()) // Home
                0xff57 -> return byteArrayOf(0x1b, 0x5b, 'F'.code.toByte()) // End
                0xff55 -> return byteArrayOf(0x1b, 0x5b, '5'.code.toByte(), 0x7e) // PageUp
                0xff56 -> return byteArrayOf(0x1b, 0x5b, '6'.code.toByte(), 0x7e) // PageDown
            }
            if (keysym in 0x20..0x7e) return byteArrayOf(keysym.toByte())
            // Latin-1 mapped the same way as X keysyms
            if (keysym in 0xa0..0xff) return byteArrayOf(keysym.toByte())
            return null
        }

        fun bytesForKeyEvent(event: KeyEvent): ByteArray? {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    return byteArrayOf('\r'.code.toByte())
                KeyEvent.KEYCODE_DEL -> return byteArrayOf(0x7f)
                KeyEvent.KEYCODE_FORWARD_DEL -> return byteArrayOf(0x1b, 0x5b, 0x33, 0x7e)
                KeyEvent.KEYCODE_TAB -> return byteArrayOf('\t'.code.toByte())
                KeyEvent.KEYCODE_ESCAPE -> return byteArrayOf(0x1b)
                KeyEvent.KEYCODE_DPAD_LEFT -> return byteArrayOf(0x1b, 0x5b, 'D'.code.toByte())
                KeyEvent.KEYCODE_DPAD_UP -> return byteArrayOf(0x1b, 0x5b, 'A'.code.toByte())
                KeyEvent.KEYCODE_DPAD_RIGHT -> return byteArrayOf(0x1b, 0x5b, 'C'.code.toByte())
                KeyEvent.KEYCODE_DPAD_DOWN -> return byteArrayOf(0x1b, 0x5b, 'B'.code.toByte())
                KeyEvent.KEYCODE_MOVE_HOME -> return byteArrayOf(0x1b, 0x5b, 'H'.code.toByte())
                KeyEvent.KEYCODE_MOVE_END -> return byteArrayOf(0x1b, 0x5b, 'F'.code.toByte())
                KeyEvent.KEYCODE_PAGE_UP -> return byteArrayOf(0x1b, 0x5b, '5'.code.toByte(), 0x7e)
                KeyEvent.KEYCODE_PAGE_DOWN -> return byteArrayOf(0x1b, 0x5b, '6'.code.toByte(), 0x7e)
            }
            val ch = event.unicodeChar
            if (ch != 0) {
                val s = String(intArrayOf(ch), 0, 1)
                return s.toByteArray(Charsets.UTF_8)
            }
            val ks = KeysymMapper.fromKeyEvent(event) ?: return null
            return bytesForKeysym(ks)
        }
    }
}
