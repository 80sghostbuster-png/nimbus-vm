package com.lex.nimbusvm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Letterboxed guest framebuffer. Touch is mapped to an absolute USB tablet via VNC. */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), VncClient.Listener {

    var client: VncClient? = null
    private var frame: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private val src = Rect()
    private val dst = Rect()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint().apply { color = Color.BLACK }

    override fun onFrame(bitmap: Bitmap, width: Int, height: Int) {
        frame = bitmap
        fbW = width
        fbH = height
        postInvalidate()
    }

    override fun onConnected(width: Int, height: Int, desktopName: String) {
        fbW = width
        fbH = height
        postInvalidate()
    }

    override fun onDisconnected(reason: String) { /* activity handles */ }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = frame ?: return
        if (fbW <= 0 || fbH <= 0) return
        letterbox(width, height, fbW, fbH, dst)
        src.set(0, 0, fbW, fbH)
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = client ?: return false
        if (fbW <= 0 || fbH <= 0 || dst.isEmpty) return false
        val x = ((event.x - dst.left) * fbW / dst.width()).toInt().coerceIn(0, fbW - 1)
        val y = ((event.y - dst.top) * fbH / dst.height()).toInt().coerceIn(0, fbH - 1)
        val buttons = if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) 0 else 1
        c.sendPointer(x, y, buttons)
        return true
    }

    private fun letterbox(vw: Int, vh: Int, iw: Int, ih: Int, out: Rect) {
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) {
            out.set(0, 0, 0, 0)
            return
        }
        val scale = minOf(vw.toFloat() / iw, vh.toFloat() / ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val l = (vw - w) / 2
        val t = (vh - h) / 2
        out.set(l, t, l + w, t + h)
    }
}
