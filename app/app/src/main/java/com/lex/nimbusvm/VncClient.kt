package com.lex.nimbusvm

import android.graphics.Bitmap
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.DataInputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RFB 3.8 client (None auth, Raw + CopyRect + DesktopSize) for QEMU's VNC display.
 */
class VncClient(
    private val unixPath: String? = null,
    private val host: String = "127.0.0.1",
    private val port: Int = 5900,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(bitmap: Bitmap, width: Int, height: Int)
        fun onConnected(width: Int, height: Int, desktopName: String)
        fun onDisconnected(reason: String)
    }

    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var local: LocalSocket? = null
    private var tcp: Socket? = null
    private val running = AtomicBoolean(false)
    private var fb: Bitmap? = null
    private var pixels = IntArray(0)
    private var fbW = 0
    private var fbH = 0
    private var bytesPerPixel = 4
    private var redShift = 16
    private var blueShift = 0
    private val outLock = Any()

    val width: Int get() = fbW
    val height: Int get() = fbH
    val isConnected: Boolean get() = running.get()

    fun connect() {
        close()
        if (unixPath != null) {
            val ls = LocalSocket()
            ls.connect(LocalSocketAddress(unixPath, LocalSocketAddress.Namespace.FILESYSTEM))
            local = ls
            input = DataInputStream(ls.inputStream)
            output = ls.outputStream
        } else {
            val s = Socket(host, port)
            s.tcpNoDelay = true
            tcp = s
            input = DataInputStream(s.getInputStream())
            output = s.getOutputStream()
        }
        handshake()
        running.set(true)
        Thread({ readLoop() }, "vnc-read").start()
        requestUpdate(false)
    }

    fun close() {
        running.set(false)
        try { local?.close() } catch (_: Exception) {}
        try { tcp?.close() } catch (_: Exception) {}
        local = null
        tcp = null
        input = null
        output = null
    }

    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val buf = ByteArray(6)
        buf[0] = 5
        buf[1] = buttons.toByte()
        buf[2] = (x shr 8).toByte(); buf[3] = x.toByte()
        buf[4] = (y shr 8).toByte(); buf[5] = y.toByte()
        write(buf)
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val buf = ByteArray(8)
        buf[0] = 4
        buf[1] = if (down) 1 else 0
        // 2 bytes padding
        buf[4] = (keysym shr 24).toByte()
        buf[5] = (keysym shr 16).toByte()
        buf[6] = (keysym shr 8).toByte()
        buf[7] = keysym.toByte()
        write(buf)
    }

    fun requestUpdate(incremental: Boolean) {
        if (fbW == 0 || fbH == 0) return
        val buf = ByteArray(10)
        buf[0] = 3
        buf[1] = if (incremental) 1 else 0
        buf[6] = (fbW shr 8).toByte(); buf[7] = fbW.toByte()
        buf[8] = (fbH shr 8).toByte(); buf[9] = fbH.toByte()
        write(buf)
    }

    private fun handshake() {
        val inn = input ?: error("not connected")
        val verBytes = ByteArray(12)
        inn.readFully(verBytes)
        write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        val nSec = inn.readUnsignedByte()
        if (nSec == 0) {
            val len = inn.readInt()
            val reason = ByteArray(len)
            inn.readFully(reason)
            error("VNC security failure: ${String(reason)}")
        }
        val types = ByteArray(nSec)
        inn.readFully(types)
        if (1 !in types.map { it.toInt() }) error("VNC server does not allow unauthenticated access")
        write(byteArrayOf(1))
        val result = inn.readInt()
        if (result != 0) {
            val len = inn.readInt()
            val reason = ByteArray(len)
            inn.readFully(reason)
            error("VNC auth failed: ${String(reason)}")
        }
        write(byteArrayOf(1)) // shared
        fbW = inn.readUnsignedShort()
        fbH = inn.readUnsignedShort()
        val pf = ByteArray(16)
        inn.readFully(pf)
        bytesPerPixel = (pf[0].toInt() and 0xff) / 8
        redShift = pf[8].toInt() and 0xff
        blueShift = pf[10].toInt() and 0xff
        val nameLen = inn.readInt()
        val nameBuf = ByteArray(nameLen)
        inn.readFully(nameBuf)
        val name = String(nameBuf, Charsets.UTF_8)
        // Force 32bpp little-endian RGB so our raw decoder is simple
        setPixelFormat()
        setEncodings()
        allocFb(fbW, fbH)
        listener.onConnected(fbW, fbH, name)
        Log.i(TAG, "VNC connected ${fbW}x${fbH} '$name'")
    }

    private fun setPixelFormat() {
        val buf = ByteArray(20)
        buf[0] = 0
        buf[4] = 32 // bpp
        buf[5] = 24 // depth
        buf[6] = 0 // little endian
        buf[7] = 1 // true colour
        buf[8] = 0; buf[9] = 255.toByte() // rmax
        buf[10] = 0; buf[11] = 255.toByte()
        buf[12] = 0; buf[13] = 255.toByte()
        buf[14] = 16 // rshift
        buf[15] = 8
        buf[16] = 0
        write(buf)
        bytesPerPixel = 4
        redShift = 16
        blueShift = 0
    }

    private fun setEncodings() {
        val enc = intArrayOf(0, 1, -223) // raw, copyrect, desktop size
        val buf = ByteArray(4 + enc.size * 4)
        buf[0] = 2
        buf[2] = (enc.size shr 8).toByte(); buf[3] = enc.size.toByte()
        var o = 4
        for (e in enc) {
            buf[o] = (e shr 24).toByte(); buf[o + 1] = (e shr 16).toByte()
            buf[o + 2] = (e shr 8).toByte(); buf[o + 3] = e.toByte()
            o += 4
        }
        write(buf)
    }

    private fun allocFb(w: Int, h: Int) {
        fbW = w; fbH = h
        pixels = IntArray(w * h)
        fb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private fun readLoop() {
        try {
            val inn = input ?: return
            while (running.get()) {
                val type = inn.readUnsignedByte()
                when (type) {
                    0 -> readFramebufferUpdate(inn)
                    1 -> { // SetColourMapEntries
                        inn.readByte()
                        inn.readUnsignedShort()
                        val n = inn.readUnsignedShort()
                        inn.skipBytes(n * 6)
                    }
                    2 -> { /* Bell */ }
                    3 -> {
                        inn.readByte(); inn.readByte(); inn.readByte()
                        val n = inn.readInt()
                        inn.skipBytes(n)
                    }
                    else -> {
                        Log.w(TAG, "unknown VNC server message $type")
                        running.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) listener.onDisconnected(e.message ?: e.toString())
        } finally {
            running.set(false)
        }
    }

    private fun readFramebufferUpdate(inn: DataInputStream) {
        inn.readByte()
        val nRects = inn.readUnsignedShort()
        var dirty = false
        repeat(nRects) {
            val x = inn.readUnsignedShort()
            val y = inn.readUnsignedShort()
            val w = inn.readUnsignedShort()
            val h = inn.readUnsignedShort()
            val encoding = inn.readInt()
            when (encoding) {
                0 -> { readRaw(inn, x, y, w, h); dirty = true }
                1 -> { copyRect(inn, x, y, w, h); dirty = true }
                -223 -> { allocFb(w, h); dirty = true }
                else -> error("unsupported VNC encoding $encoding")
            }
        }
        if (dirty) {
            val bmp = fb ?: return
            bmp.setPixels(pixels, 0, fbW, 0, 0, fbW, fbH)
            listener.onFrame(bmp, fbW, fbH)
        }
        requestUpdate(true)
    }

    private fun readRaw(inn: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val raw = ByteArray(w * h * 4)
        inn.readFully(raw)
        var i = 0
        for (row in 0 until h) {
            var dst = (y + row) * fbW + x
            for (col in 0 until w) {
                val b = raw[i].toInt() and 0xff
                val g = raw[i + 1].toInt() and 0xff
                val r = raw[i + 2].toInt() and 0xff
                pixels[dst] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
                dst++
            }
        }
    }

    private fun copyRect(inn: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inn.readUnsignedShort()
        val srcY = inn.readUnsignedShort()
        val copy = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(pixels, (srcY + row) * fbW + srcX, copy, row * w, w)
        }
        for (row in 0 until h) {
            System.arraycopy(copy, row * w, pixels, (y + row) * fbW + x, w)
        }
    }

    private fun write(data: ByteArray) {
        synchronized(outLock) {
            output?.write(data)
            output?.flush()
        }
    }

    companion object { private const val TAG = "NimbusVnc" }
}
