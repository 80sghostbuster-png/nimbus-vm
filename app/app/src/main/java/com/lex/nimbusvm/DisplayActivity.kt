package com.lex.nimbusvm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var serialPanel: ScrollView
    private lateinit var serialLog: TextView
    private lateinit var btnTouch: Button
    private lateinit var btnSerial: Button
    private var bound = false
    private var placeholder = true
    private val ui = Handler(Looper.getMainLooper())
    private var serialFile: File? = null
    private var serialIo: SerialConsole? = null
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

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VmService.LocalBinder).service()
            bound = true
            bindSerialFile()
            if (startOnBind) {
                startOnBind = false
                actuallyStart()
            } else {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_display)
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
        serialLog = findViewById(R.id.serialLog)
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

    private fun refreshSerial() {
        val svc = service
        thread {
            val parts = mutableListOf<String>()
            val extract = svc?.engine?.workDir(vmId)?.let { File(it, "installer-extract.txt") }
            if (extract != null && extract.exists()) {
                parts += "--- installer ---\n" + extract.readText().trim()
            }
            val qlog = svc?.engine?.qemuLogFile(vmId)
            if (qlog != null && qlog.exists()) {
                parts += "--- qemu ---\n" + qlog.readText().takeLast(4000).trim()
            }
            val serial = serialFile
            if (serial != null && serial.exists()) {
                parts += "--- serial ---\n" + stripAnsi(serial.readText()).takeLast(8000).trim()
            }
            val text = parts.joinToString("\n\n")
            val serialOnly = if (serial != null && serial.exists()) stripAnsi(serial.readText()) else ""
            val extractText = extract?.takeIf { it.exists() }?.readText().orEmpty()
            runOnUiThread {
                if (text.isNotEmpty() && serialLog.text != text) {
                    serialLog.text = text
                    serialPanel.post { serialPanel.fullScroll(View.FOCUS_DOWN) }
                }
                updateBootOverlay(extractText, serialOnly)
            }
        }
    }

    private fun updateBootOverlay(extractLog: String, serialText: String) {
        val low = (extractLog + "\n" + serialText).lowercase()
        val extracted = extractLog.startsWith("Extracted ARM64")
        val extractFailed = extractLog.contains("failed", ignoreCase = true) ||
            extractLog.startsWith("No /install.a64")
        val title = when {
            "select a language" in low || "choose the language" in low -> "Installer is running"
            "linux version" in low || "efi stub:" in low -> "Booting… (kernel)"
            extracted -> "Booting… (installer)"
            else -> "Booting… (firmware)"
        }
        val body = when {
            extractFailed -> extractLog.trim() + " Open Serial for the QEMU command line and firmware log."
            extracted -> "Installer kernel extracted. The display can stay dark until the guest paints — open Serial for kernel logs and the QEMU command line."
            else -> "Guest display is not active yet. The machine is still booting — open Serial for the live firmware log and the QEMU command line."
        }
        bootOverlayTitle.text = title
        bootOverlayBody.text = body
    }

    private fun actuallyStart() {
        val svc = service ?: return
        status.visibility = View.VISIBLE
        status.text = "Starting…"
        bootOverlay.visibility = View.VISIBLE
        placeholder = true
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

    override fun onDestroy() {
        ui.removeCallbacks(refreshRunnable)
        ui.removeCallbacks(serialRunnable)
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
