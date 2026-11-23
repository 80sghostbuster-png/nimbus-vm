package com.lex.nimbusvm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Guest framebuffer stretched to fill the whole view (no bars, no crop)
 * with first-class touch.
 *
 * Direct (default): tap = left click at the finger (usb-tablet via VNC),
 * long-press = right click, two-finger drag = scroll, pinch = zoom the viewer.
 * Trackpad: relative pointer. Multitouch: forward pointers to virtio-multitouch.
 * Mapping always accounts for letterboxing, viewer zoom and pan.
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), VncClient.Listener {

    enum class Mode { DIRECT, TRACKPAD, MULTITOUCH }

    var client: VncClient? = null
    var mode: Mode = Mode.DIRECT
        set(value) {
            field = value
            if (value != Mode.TRACKPAD) {
                // keep cursor where it is for a later toggle
            }
            invalidate()
        }

    /** Called on a background-safe lambda; the activity should hop to a worker for QMP. */
    var multiTouchHandler: ((List<QemuEngine.TouchContact>) -> Unit)? = null
    var onPlaceholderChanged: ((Boolean) -> Unit)? = null
    var onRevealToolbar: (() -> Unit)? = null
    var toolbarIsHidden: Boolean = false

    private var frame: Bitmap? = null
    private var fbW = 0
    private var fbH = 0
    private val src = Rect()
    private val letterbox = Rect()
    private val dest = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66C7A452
        style = Paint.Style.FILL
    }

    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pointerDown = false
    private var dragging = false
    private var longPressed = false
    private var leftDown = false
    private var rightDown = false
    private var twoFinger = false
    private var startSpan = 0f
    private var startScale = 1f
    private var startPanX = 0f
    private var startPanY = 0f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var lastSpan = 0f
    private var scrollAcc = 0f
    private var pinching = false
    private var toolbarEdge = false

    /** Trackpad cursor in framebuffer pixels. */
    private var cursorX = 0
    private var cursorY = 0
    private val activeSlots = HashSet<Int>()

    private val longPressRunnable = Runnable {
        if (!pointerDown || dragging || twoFinger) return@Runnable
        longPressed = true
        val pos = guestPoint(downX, downY) ?: return@Runnable
        when (mode) {
            Mode.DIRECT, Mode.TRACKPAD -> {
                val (x, y) = pos
                if (mode == Mode.TRACKPAD) {
                    // click at the cursor, not the finger
                }
                val cx = if (mode == Mode.TRACKPAD) cursorX else x
                val cy = if (mode == Mode.TRACKPAD) cursorY else y
                client?.sendPointer(cx, cy, 4)
                rightDown = true
            }
            Mode.MULTITOUCH -> { /* guest handles long-press */ }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    override fun onFrame(bitmap: Bitmap, width: Int, height: Int) {
        frame = bitmap
        fbW = width
        fbH = height
        if (cursorX == 0 && cursorY == 0) {
            cursorX = width / 2
            cursorY = height / 2
        }
        val blank = isPlaceholderOrBlank(bitmap, width, height)
        onPlaceholderChanged?.invoke(blank)
        postInvalidate()
    }

    override fun onConnected(width: Int, height: Int, desktopName: String) {
        fbW = width
        fbH = height
        cursorX = width / 2
        cursorY = height / 2
        postInvalidate()
    }

    override fun onDisconnected(reason: String) { /* activity handles */ }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = frame ?: return
        if (fbW <= 0 || fbH <= 0) return
        layoutDest()
        src.set(0, 0, fbW, fbH)
        canvas.drawBitmap(bmp, src, dest, paint)
        if (mode == Mode.TRACKPAD && fbW > 0) {
            val p = fbToView(cursorX, cursorY)
            canvas.drawCircle(p[0], p[1], 16f, cursorFill)
            canvas.drawCircle(p[0], p[1], 16f, cursorPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutDest()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (toolbarHiddenEdge(event, action)) return true

        if (mode == Mode.MULTITOUCH) {
            handleMultitouch(event)
            return true
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                pointerDown = true
                dragging = false
                longPressed = false
                twoFinger = false
                pinching = false
                scrollAcc = 0f
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, longPressTimeout)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    handler.removeCallbacks(longPressRunnable)
                    if (leftDown) {
                        releaseButtons()
                    }
                    twoFinger = true
                    dragging = false
                    startSpan = span(event)
                    lastSpan = startSpan
                    startScale = userScale
                    startPanX = panX
                    startPanY = panY
                    val mid = midpoint(event)
                    pinchFocusX = mid[0]
                    pinchFocusY = mid[1]
                    lastMidX = mid[0]
                    lastMidY = mid[1]
                    pinching = false
                    scrollAcc = 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && event.pointerCount >= 2) {
                    handleTwoFingerMove(event)
                } else if (pointerDown && !twoFinger) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && hypot(dx, dy) > touchSlop) {
                        dragging = true
                        handler.removeCallbacks(longPressRunnable)
                        if (!longPressed) beginLeft(event.x, event.y)
                    }
                    if (dragging && !longPressed) {
                        moveLeft(event.x, event.y)
                    } else if (!dragging && !longPressed && mode == Mode.TRACKPAD) {
                        // wait for slop or long-press
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    twoFinger = false
                    pinching = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (action == MotionEvent.ACTION_UP && !twoFinger && !dragging && !longPressed) {
                    // tap
                    tapAt(event.x, event.y)
                }
                releaseButtons()
                pointerDown = false
                dragging = false
                longPressed = false
                twoFinger = false
            }
        }
        return true
    }

    fun cycleMode(): Mode {
        mode = when (mode) {
            Mode.DIRECT -> Mode.TRACKPAD
            Mode.TRACKPAD -> Mode.MULTITOUCH
            Mode.MULTITOUCH -> Mode.DIRECT
        }
        if (mode != Mode.MULTITOUCH) {
            // lift any leftover guest contacts
            multiTouchHandler?.invoke(emptyList())
            activeSlots.clear()
        }
        invalidate()
        return mode
    }

    fun modeLabel(): String = when (mode) {
        Mode.DIRECT -> "Touch"
        Mode.TRACKPAD -> "Trackpad"
        Mode.MULTITOUCH -> "Multitouch"
    }

    fun resetViewTransform() {
        userScale = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun beginLeft(vx: Float, vy: Float) {
        val (x, y) = pointerTarget(vx, vy) ?: return
        client?.sendPointer(x, y, 1)
        leftDown = true
    }

    private fun moveLeft(vx: Float, vy: Float) {
        val (x, y) = if (mode == Mode.TRACKPAD) {
            moveCursorBy(vx - lastX, vy - lastY)
            cursorX to cursorY
        } else {
            pointerTarget(vx, vy) ?: return
        }
        client?.sendPointer(x, y, if (rightDown) 4 else 1)
        if (mode != Mode.TRACKPAD) {
            // keep
        }
        leftDown = !rightDown
    }

    private fun tapAt(vx: Float, vy: Float) {
        val (x, y) = pointerTarget(vx, vy) ?: return
        client?.sendClick(x, y, 1)
    }

    private fun releaseButtons() {
        if (leftDown || rightDown) {
            val x = if (mode == Mode.TRACKPAD) cursorX else pointerTarget(lastX, lastY)?.first ?: cursorX
            val y = if (mode == Mode.TRACKPAD) cursorY else pointerTarget(lastX, lastY)?.second ?: cursorY
            client?.sendPointer(x, y, 0)
        }
        leftDown = false
        rightDown = false
    }

    private fun handleTwoFingerMove(event: MotionEvent) {
        val newSpan = span(event).coerceAtLeast(1f)
        val mid = midpoint(event)
        val spanRatio = newSpan / lastSpan.coerceAtLeast(1f)
        val distChange = abs(newSpan - startSpan)
        val midMove = hypot(mid[0] - lastMidX, mid[1] - lastMidY)
        // Pinch dominates if the span changed more than the midpoint moved.
        if (!pinching && distChange > touchSlop * 2 && distChange > midMove) {
            pinching = true
        }
        if (pinching) {
            val focusFb = viewToFb(pinchFocusX, pinchFocusY)
            val factor = newSpan / startSpan.coerceAtLeast(1f)
            userScale = (startScale * factor).coerceIn(1f, 8f)
            if (userScale <= 1.01f) {
                userScale = 1f
                panX = 0f
                panY = 0f
            } else if (focusFb != null) {
                layoutDest()
                val now = fbToView(focusFb[0], focusFb[1])
                panX += pinchFocusX - now[0]
                panY += pinchFocusY - now[1]
                clampPan()
            }
            invalidate()
        } else {
            // two-finger drag = scroll the guest (wheel)
            val dy = mid[1] - lastMidY
            scrollAcc += dy
            val step = 32f
            while (scrollAcc <= -step) {
                scrollAcc += step
                val (x, y) = scrollTarget()
                client?.sendScroll(x, y, true)
            }
            while (scrollAcc >= step) {
                scrollAcc -= step
                val (x, y) = scrollTarget()
                client?.sendScroll(x, y, false)
            }
        }
        lastMidX = mid[0]
        lastMidY = mid[1]
        lastSpan = newSpan
    }

    private fun handleMultitouch(event: MotionEvent) {
        val action = event.actionMasked
        val contacts = ArrayList<QemuEngine.TouchContact>(event.pointerCount)
        val liftingIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        val allUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        if (allUp) {
            for (id in activeSlots.toList()) {
                contacts.add(QemuEngine.TouchContact(id, id + 1, 0, 0, "end"))
            }
            activeSlots.clear()
            multiTouchHandler?.invoke(contacts)
            return
        }
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            if (id >= 10) continue
            val fb = viewToFb(event.getX(i), event.getY(i)) ?: continue
            val lifting = (i == liftingIndex)
            val was = id in activeSlots
            val phase = when {
                lifting -> "end"
                !was -> "begin"
                else -> "update"
            }
            if (lifting) activeSlots.remove(id) else activeSlots.add(id)
            contacts.add(QemuEngine.TouchContact(id, id + 1, fb[0], fb[1], phase))
        }
        if (contacts.isNotEmpty()) multiTouchHandler?.invoke(contacts)
    }

    private fun pointerTarget(vx: Float, vy: Float): Pair<Int, Int>? {
        if (mode == Mode.TRACKPAD) {
            if (pointerDown && (dragging || longPressed)) {
                // already updated elsewhere for move; for beginLeft on drag start, move by delta
            }
            return cursorX to cursorY
        }
        val p = viewToFb(vx, vy) ?: return null
        return p[0] to p[1]
    }

    private fun moveCursorBy(dx: Float, dy: Float): Pair<Int, Int> {
        layoutDest()
        if (dest.width() <= 0 || dest.height() <= 0 || fbW <= 0) return cursorX to cursorY
        val gx = dx * fbW / dest.width()
        val gy = dy * fbH / dest.height()
        cursorX = (cursorX + gx).toInt().coerceIn(0, fbW - 1)
        cursorY = (cursorY + gy).toInt().coerceIn(0, fbH - 1)
        invalidate()
        return cursorX to cursorY
    }

    private fun scrollTarget(): Pair<Int, Int> {
        return if (mode == Mode.TRACKPAD) cursorX to cursorY
        else viewToFb(lastMidX, lastMidY)?.let { it[0] to it[1] } ?: (fbW / 2 to fbH / 2)
    }

    private fun guestPoint(vx: Float, vy: Float): Pair<Int, Int>? {
        return if (mode == Mode.TRACKPAD) cursorX to cursorY
        else viewToFb(vx, vy)?.let { it[0] to it[1] }
    }

    private fun toolbarHiddenEdge(event: MotionEvent, action: Int): Boolean {
        if (!toolbarIsHidden) return false
        if (action == MotionEvent.ACTION_DOWN && event.y <= dp(48f)) {
            toolbarEdge = true
        }
        if (toolbarEdge && action == MotionEvent.ACTION_UP && event.y <= dp(64f) &&
            abs(event.x - downX) < touchSlop * 3
        ) {
            toolbarEdge = false
            onRevealToolbar?.invoke()
            return true
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            val was = toolbarEdge
            toolbarEdge = false
            if (was && event.y <= dp(64f)) return false
        }
        return false
    }

    private fun layoutDest() {
        cover(width, height, fbW, fbH, letterbox)
        val cx = letterbox.exactCenterX()
        val cy = letterbox.exactCenterY()
        val l = cx + (letterbox.left - cx) * userScale + panX
        val t = cy + (letterbox.top - cy) * userScale + panY
        val r = cx + (letterbox.right - cx) * userScale + panX
        val b = cy + (letterbox.bottom - cy) * userScale + panY
        dest.set(l, t, r, b)
    }

    private fun clampPan() {
        layoutDest()
        // Keep some of the guest visible.
        val minVisible = 64f
        if (dest.right < minVisible) panX += minVisible - dest.right
        if (dest.bottom < minVisible) panY += minVisible - dest.bottom
        if (dest.left > width - minVisible) panX -= dest.left - (width - minVisible)
        if (dest.top > height - minVisible) panY -= dest.top - (height - minVisible)
        layoutDest()
    }

    private fun viewToFb(vx: Float, vy: Float): IntArray? {
        if (fbW <= 0 || fbH <= 0) return null
        layoutDest()
        if (dest.width() <= 1f || dest.height() <= 1f) return null
        val x = ((vx - dest.left) * fbW / dest.width()).toInt()
        val y = ((vy - dest.top) * fbH / dest.height()).toInt()
        if (x < 0 || y < 0 || x >= fbW || y >= fbH) return null
        return intArrayOf(x, y)
    }

    private fun fbToView(x: Int, y: Int): FloatArray {
        layoutDest()
        if (fbW <= 0 || fbH <= 0 || dest.width() <= 0) return floatArrayOf(0f, 0f)
        return floatArrayOf(
            dest.left + x * dest.width() / fbW,
            dest.top + y * dest.height() / fbH,
        )
    }

    /**
     * Fill the whole view edge to edge: no black bars and nothing cropped.
     * The guest picks its own mode (ramfb/efifb is 800x600), so on a wide
     * phone this stretches horizontally; touch mapping uses dest's own
     * width/height, so taps still land on the right guest pixel.
     */
    private fun cover(vw: Int, vh: Int, iw: Int, ih: Int, out: Rect) {
        if (vw <= 0 || vh <= 0 || iw <= 0 || ih <= 0) {
            out.set(0, 0, 0, 0)
            return
        }
        out.set(0, 0, vw, vh)
    }

    private fun span(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    private fun midpoint(event: MotionEvent): FloatArray {
        return floatArrayOf(
            (event.getX(0) + event.getX(1)) / 2f,
            (event.getY(0) + event.getY(1)) / 2f,
        )
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        fun isPlaceholderOrBlank(bmp: Bitmap, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return true
            var sum = 0L
            var sum2 = 0L
            var n = 0
            val stepX = (width / 32).coerceAtLeast(1)
            val stepY = (height / 32).coerceAtLeast(1)
            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val c = bmp.getPixel(x, y)
                    val l = ((c ushr 16 and 0xff) + (c ushr 8 and 0xff) + (c and 0xff)) / 3
                    sum += l
                    sum2 += l * l
                    n++
                    x += stepX
                }
                y += stepY
            }
            if (n == 0) return true
            val mean = sum.toDouble() / n
            val variance = sum2.toDouble() / n - mean * mean
            val sd = sqrt(variance.coerceAtLeast(0.0))
            // QEMU "Display output is not active." is nearly black (mean ~0.1, sd ~4).
            // GRUB is also dark but richer (mean ~6.5, sd ~15). GTK installers are bright.
            return mean < 2.0 || (mean < 5.0 && sd < 8.0)
        }
    }
}
