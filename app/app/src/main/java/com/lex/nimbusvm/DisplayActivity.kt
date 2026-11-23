package com.lex.nimbusvm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.concurrent.thread

class DisplayActivity : AppCompatActivity() {
    private var service: VmService? = null
    private var vmId: String = ""
    private var startOnBind = false
    private var vnc: VncClient? = null
    private lateinit var screen: VncView
    private lateinit var osk: OnScreenKeyboard
    private lateinit var toolbar: LinearLayout
    private lateinit var status: TextView
    private lateinit var bootOverlay: View
    private lateinit var bootOverlayTitle: TextView
    private lateinit var bootOverlayBody: TextView
    private lateinit var serialPanel: android.widget.LinearLayout
    private lateinit var serialScroll: ScrollView
    private lateinit var serialLog: TextView
    private lateinit var btnJumpLatest: Button
    private lateinit var btnTouch: Button
    private lateinit var btnSerial: Button
    private var bound = false
    private var placeholder = true
    private val ui = Handler(Looper.getMainLooper())
    private var serialFile: File? = null
    private var serialIo: SerialConsole? = null
    private var bootStartedAt = 0L
    private var lastExtractLog = ""
    private var lastSerialText = ""
    private var serialFollow = true
    private var serialBytesShown = 0L
    private var qemuBytesShown = 0L
    private var extractShown = false
    private val refreshRunnable = object : Runnable {
        override fun run() {
            val c = vnc
            if (c != null && c.isConnected) {
                c.requestFullUpdate()
            }
            val delay = if (placeholder) 2000L else 10000L
            ui.postDelayed(this, delay)
        }
    }
    private val serialRunnable = object : Runnable {
        override fun run() {
            if (serialPanel.visibility == View.VISIBLE || bootOverlay.visibility == View.VISIBLE) {
                refreshSerial()
                if (serialOpen() && serialIo?.isConnected != true) connectSerialIo()
            }
            ui.postDelayed(this, 500)
        }
    }
    private val elapsedRunnable = object : Runnable {
        override fun run() {
            if (bootOverlay.visibility == View.VISIBLE && bootStartedAt != 0L) {
                updateBootOverlay(lastExtractLog, lastSerialText)
            }
            ui.postDelayed(this, 1000)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VmService.LocalBinder).service()
            bound = true
            bindSerialFile()
            if (startOnBind) {
                startOnBind = false
                actuallyStart()
            } else {
                if (bootStartedAt == 0L) bootStartedAt = SystemClock.elapsedRealtime()
                connectVnc()
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveFullscreen()
        setContentView(R.layout.activity_display)
        applyImmersiveFullscreen()
        vmId = intent.getStringExtra(EXTRA_VM_ID) ?: ""
        startOnBind = intent.getBooleanExtra(EXTRA_START, false)
        screen = findViewById(R.id.screen)
        osk = findViewById(R.id.osk)
        toolbar = findViewById(R.id.toolbar)
        status = findViewById(R.id.status)
        bootOverlay = findViewById(R.id.bootOverlay)
        bootOverlayTitle = findViewById(R.id.bootOverlayTitle)
        bootOverlayBody = findViewById(R.id.bootOverlayBody)
        serialPanel = findViewById(R.id.serialPanel)
        serialScroll = findViewById(R.id.serialScroll)
        serialLog = findViewById(R.id.serialLog)
        btnJumpLatest = findViewById(R.id.btnJumpLatest)
        btnJumpLatest.setOnClickListener { jumpToLatestSerial() }
        serialScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            val atBottom = serialIsAtBottom()
            if (atBottom) {
                serialFollow = true
                btnJumpLatest.visibility = View.GONE
            } else {
                serialFollow = false
                if (serialPanel.visibility == View.VISIBLE) btnJumpLatest.visibility = View.VISIBLE
            }
        }
        btnTouch = findViewById(R.id.btnTouch)
        btnSerial = findViewById(R.id.btnSerial)

        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            osk.visibility = if (osk.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            layoutSerialAndOsk()
        }
        findViewById<Button>(R.id.btnPower).setOnClickListener { powerMenu() }
        findViewById<Button>(R.id.btnHide).setOnClickListener {
            toolbar.visibility = View.GONE
            screen.toolbarIsHidden = true
        }
        btnTouch.setOnClickListener {
            val mode = screen.cycleMode()
            btnTouch.text = screen.modeLabel()
            val hint = when (mode) {
                VncView.Mode.DIRECT -> "Direct touch: tap to click, long-press for right click, pinch to zoom the viewer"
                VncView.Mode.TRACKPAD -> "Trackpad: move the cursor, tap to click, long-press for right click"
                VncView.Mode.MULTITOUCH -> "Guest multi-touch: pointers go to virtio-multitouch (Linux gestures)"
            }
            Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
        }
        btnTouch.setOnLongClickListener {
            screen.resetViewTransform()
            Toast.makeText(this, "Viewer zoom reset", Toast.LENGTH_SHORT).show()
            true
        }
        btnSerial.setOnClickListener {
            val show = serialPanel.visibility != View.VISIBLE
            serialPanel.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                serialFollow = true
                btnJumpLatest.visibility = View.GONE
                refreshSerial()
                connectSerialIo()
            }
            routeOskKeys()
            layoutSerialAndOsk()
        }
        screen.onRevealToolbar = {
            toolbar.visibility = View.VISIBLE
            screen.toolbarIsHidden = false
        }
        screen.onPlaceholderChanged = { blank ->
            placeholder = blank
            bootOverlay.visibility = if (blank) View.VISIBLE else View.GONE
            if (!blank && status.visibility == View.VISIBLE) {
                status.visibility = View.GONE
            }
        }
        screen.multiTouchHandler = handler@{ contacts ->
            val svc = service ?: return@handler
            val w = screen.client?.width ?: 0
            val h = screen.client?.height ?: 0
            thread {
                runCatching { svc.engine.sendMultitouch(contacts, w, h) }
            }
        }

        val i = Intent(this, VmService::class.java)
        startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
        if (startOnBind) VmService.start(this, vmId)
        ui.post(serialRunnable)
        ui.post(elapsedRunnable)
    }

    private fun bindSerialFile() {
        val svc = service ?: return
        serialFile = svc.store.get(vmId)?.serialFile(this)
            ?: File(svc.engine.workDir(vmId), "serial.log")
    }

    private fun serialOpen(): Boolean = serialPanel.visibility == View.VISIBLE

    private fun connectSerialIo() {
        val svc = service ?: return
        val sock = svc.engine.serialSock(vmId)
        if (!sock.exists()) return
        thread {
            try {
                val io = SerialConsole(sock.absolutePath)
                io.connect()
                serialIo = io
            } catch (_: Exception) { /* retried from the serial poller */ }
        }
    }

    private fun routeOskKeys() {
        osk.onKey = if (serialOpen()) {
            { ks, down -> serialIo?.sendKeysym(ks, down) }
        } else null
    }

    private fun layoutSerialAndOsk() {
        val lp = serialPanel.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.bottomMargin = if (osk.visibility == View.VISIBLE && serialOpen()) osk.layoutParams.height else 0
        serialPanel.layoutParams = lp
    }

    private fun stripAnsi(raw: String): String =
        raw.replace("\u001B\\[[0-9;?]*[A-Za-z]".toRegex(), "").replace("\r", "")

    private fun serialIsAtBottom(): Boolean {
        val child = serialScroll.getChildAt(0) ?: return true
        val slop = (24 * resources.displayMetrics.density).toInt()
        return child.bottom - (serialScroll.height + serialScroll.scrollY) <= slop
    }

    private fun scrollSerialToBottom() {
        serialScroll.post {
            val child = serialScroll.getChildAt(0) ?: return@post
            val y = (child.height - serialScroll.height).coerceAtLeast(0)
            serialScroll.scrollTo(0, y)
        }
    }

    private fun jumpToLatestSerial() {
        serialFollow = true
        btnJumpLatest.visibility = View.GONE
        scrollSerialToBottom()
    }

    /** Read newly appended bytes from [file] since [already], handling truncation. */
    private fun readNewText(file: File?, already: Long, capFirst: Int = 0): Pair<String, Long> {
        if (file == null || !file.exists()) return "" to 0L
        val len = file.length()
        var start = already
        if (len < start) start = 0L
        if (start == 0L && capFirst > 0 && len > capFirst) start = len - capFirst
        if (len <= start) return "" to len
        val n = (len - start).toInt()
        val buf = ByteArray(n)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(buf)
        }
        return stripAnsi(String(buf, Charsets.UTF_8)) to len
    }

    private fun refreshSerial() {
        val svc = service
        val follow = serialFollow
        val needExtract = !extractShown
        val serOff = serialBytesShown
        val qemuOff = qemuBytesShown
        thread {
            val extract = svc?.engine?.workDir(vmId)?.let { File(it, "installer-extract.txt") }
            val extractText = extract?.takeIf { it.exists() }?.readText().orEmpty()
            val qlog = svc?.engine?.qemuLogFile(vmId)
            val serial = serialFile
            val truncated = (serial != null && serial.exists() && serial.length() < serOff) ||
                (qlog != null && qlog.exists() && qlog.length() < qemuOff)
            val (newQemu, qemuEnd) = readNewText(qlog, if (truncated) 0L else qemuOff, capFirst = 4000)
            val (newSerial, serEnd) = readNewText(serial, if (truncated) 0L else serOff, capFirst = 8000)
            val serialOnly = if (serial != null && serial.exists()) {
                // Overlay only needs a tail, not the whole file.
                stripAnsi(serial.readText().takeLast(16_000))
            } else ""
            runOnUiThread {
                if (truncated) {
                    serialLog.text = ""
                    extractShown = false
                    qemuBytesShown = 0L
                    serialBytesShown = 0L
                }
                val chunks = StringBuilder()
                if (needExtract && extractText.isNotEmpty() && !extractShown) {
                    chunks.append("--- installer ---\n").append(extractText.trim()).append("\n\n")
                    extractShown = true
                }
                if (newQemu.isNotEmpty()) {
                    if (qemuBytesShown == 0L) chunks.append("--- qemu ---\n")
                    chunks.append(newQemu)
                    qemuBytesShown = qemuEnd
                }
                if (newSerial.isNotEmpty()) {
                    if (serialBytesShown == 0L) chunks.append("--- serial ---\n")
                    chunks.append(newSerial)
                    serialBytesShown = serEnd
                } else {
                    serialBytesShown = serEnd
                }
                if (chunks.isNotEmpty()) {
                    serialLog.append(chunks)
                    if (follow || serialFollow) {
                        serialFollow = true
                        btnJumpLatest.visibility = View.GONE
                        scrollSerialToBottom()
                    }
                }
                updateBootOverlay(extractText, serialOnly)
            }
        }
    }

    private fun elapsedLabel(): String {
        if (bootStartedAt == 0L) return ""
        val sec = ((SystemClock.elapsedRealtime() - bootStartedAt) / 1000L).coerceAtLeast(0L)
        val m = sec / 60L
        val s = sec % 60L
        return "Elapsed %d:%02d.".format(m, s)
    }

    private fun updateBootOverlay(extractLog: String, serialText: String) {
        lastExtractLog = extractLog
        lastSerialText = serialText
        val low = (extractLog + "\n" + serialText).lowercase()
        val extracted = extractLog.startsWith("Extracted ARM64")
        val extractFailed = extractLog.contains("failed", ignoreCase = true) ||
            extractLog.startsWith("No /install.a64")
        val kernelStarted = "booting linux" in low || "linux version" in low ||
            "efi stub:" in low || "earlycon:" in low
        val title = when {
            "select a language" in low || "choose the language" in low -> "Installer is running"
            kernelStarted -> "Booting… (kernel)"
            extracted -> "Booting… (installer)"
            else -> "Booting… (firmware)"
        }
        val stalled = "auto-fallback" in low || "serial quiet" in low
        val wait = " The display can stay dark for several minutes on a phone — watch this timer and open Serial for live QMP / ramfb counts."
        val body = when {
            extractFailed -> extractLog.trim() + " Open Serial for the QEMU command line and firmware log."
            stalled -> "Serial stopped growing, so the kernel may have stalled. Auto-fallback will restart with the next CPU profile (default is cortex-a72 + thread=single)." + wait
            kernelStarted -> "Kernel has started. Unpacking the installer initrd (~68 MB) on phone TCG can take many minutes; Serial should keep growing. A QMP line every 5s reports the CPU PC and, every 30s, how many non-black ramfb pixels have been painted." + wait
            extracted -> "Installer kernel extracted. Serial should show “Booting Linux” once the kernel starts." + wait
            else -> "Guest display is not active yet. The machine is still booting — open Serial for the live firmware log and the QEMU command line."
        }
        val elapsed = elapsedLabel()
        bootOverlayTitle.text = if (elapsed.isEmpty()) title else "$title  $elapsed"
        bootOverlayBody.text = if (elapsed.isEmpty()) body else "$body $elapsed"
    }

    private fun actuallyStart() {
        val svc = service ?: return
        status.visibility = View.VISIBLE
        status.text = "Starting…"
        bootOverlay.visibility = View.VISIBLE
        placeholder = true
        if (bootStartedAt == 0L) bootStartedAt = SystemClock.elapsedRealtime()
        bindSerialFile()
        thread {
            var tries = 0
            while (tries < 40 && !(svc.isThisVmRunning(vmId) || svc.engine.isRunning)) {
                Thread.sleep(250)
                tries++
            }
            if (!svc.engine.isRunning) {
                val r = svc.startVm(vmId)
                if (r.isFailure) {
                    val err = r.exceptionOrNull()?.message ?: "failed"
                    val log = svc.engine.qemuLogFile(vmId).takeIf { it.exists() }?.readText().orEmpty().takeLast(2000)
                    runOnUiThread {
                        status.text = "Failed to start\n$err\n$log"
                    }
                    return@thread
                }
            }
            runOnUiThread { status.text = "Connecting display…" }
            connectVnc()
        }
    }

    private fun connectVnc() {
        val svc = service ?: return
        bindSerialFile()
        thread {
            val sock = svc.engine.vncSock(vmId)
            var tries = 0
            while (tries < 80 && !sock.exists()) {
                Thread.sleep(250)
                tries++
                if (!svc.engine.isRunning && tries > 8) {
                    val log = svc.engine.qemuLogFile(vmId).takeIf { it.exists() }?.readText().orEmpty().takeLast(2000)
                    runOnUiThread { status.text = "QEMU exited before the display came up.\n$log" }
                    return@thread
                }
            }
            try {
                val client = VncClient(unixPath = sock.absolutePath, listener = object : VncClient.Listener {
                    override fun onFrame(bitmap: android.graphics.Bitmap, width: Int, height: Int) {
                        runOnUiThread {
                            screen.onFrame(bitmap, width, height)
                        }
                    }
                    override fun onConnected(width: Int, height: Int, desktopName: String) {
                        runOnUiThread { status.text = "${width}×$height" }
                    }
                    override fun onDisconnected(reason: String) {
                        runOnUiThread {
                            status.visibility = View.VISIBLE
                            status.text = "Display disconnected: $reason"
                            // Auto-retry while QEMU is still running.
                            ui.postDelayed({
                                if (svc.engine.isRunning) connectVnc()
                            }, 750)
                        }
                    }
                })
                client.connect()
                vnc = client
                runOnUiThread {
                    screen.client = client
                    osk.client = client
                    status.text = "Display connected"
                    ui.removeCallbacks(refreshRunnable)
                    ui.post(refreshRunnable)
                    connectSerialIo()
                    routeOskKeys()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "VNC: ${e.message}" }
            }
        }
    }

    private fun powerMenu() {
        val svc = service ?: return
        val items = arrayOf("Shutdown", "Restart", "Suspend", "Resume", "Force stop")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Power")
            .setItems(items) { _, which ->
                thread {
                    val msg = runCatching {
                        when (which) {
                            0 -> svc.engine.powerdown()
                            1 -> svc.engine.reset()
                            2 -> svc.engine.pause()
                            3 -> svc.engine.resume()
                            4 -> { svc.stopVm(); "stopped" }
                            else -> ""
                        }
                    }.getOrElse { it.toString() }
                    runOnUiThread { Toast.makeText(this, msg.take(80), Toast.LENGTH_SHORT).show() }
                    if (which == 4) finish()
                }
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (serialOpen() && event.deviceId >= 0) {
            val io = serialIo
            if (io != null && io.isConnected) {
                return io.sendKeyEvent(event) || super.dispatchKeyEvent(event)
            }
        }
        val c = vnc
        if (c != null && event.deviceId >= 0) {
            val ks = KeysymMapper.fromKeyEvent(event) ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) c.sendKey(ks, true)
            if (event.action == KeyEvent.ACTION_UP) c.sendKey(ks, false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    /**
     * Lock landscape, hide the Android status and nav bars, and draw under
     * the display cutout so the guest can fill the phone's landscape screen.
     * The slim toolbar stays overlaid at the top and is not part of the
     * system bars — Hide still tucks it until the user swipes the top edge.
     */
    private fun applyImmersiveFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // layoutInDisplayCutoutMode is API 28+; a direct field write crashes
        // with NoSuchFieldError on API 27 (the route-1 proof emulator).
        // Keep the access in a separately-loaded class so ART on 27 never
        // verifies the missing field. Behavior on API 28+ is unchanged.
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutoutApi28.applyShortEdges(window)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        ui.removeCallbacks(refreshRunnable)
        ui.removeCallbacks(serialRunnable)
        ui.removeCallbacks(elapsedRunnable)
        vnc?.close()
        serialIo?.close()
        if (bound) unbindService(conn)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VM_ID = "vm_id"
        const val EXTRA_START = "start"
    }
}


/** API 28+ cutout mode. Loaded only when SDK_INT >= 28. */
@RequiresApi(28)
internal object DisplayCutoutApi28 {
    fun applyShortEdges(window: Window) {
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
    }
}
