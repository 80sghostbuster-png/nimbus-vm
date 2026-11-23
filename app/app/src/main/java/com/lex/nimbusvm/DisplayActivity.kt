package com.lex.nimbusvm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VmService.LocalBinder).service()
            bound = true
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

        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            osk.visibility = if (osk.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnPower).setOnClickListener { powerMenu() }
        findViewById<Button>(R.id.btnHide).setOnClickListener {
            toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        screen.setOnClickListener { toolbar.visibility = View.VISIBLE }

        val i = Intent(this, VmService::class.java)
        startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
        if (startOnBind) VmService.start(this, vmId)
    }

    private fun actuallyStart() {
        val svc = service ?: return
        status.text = "Starting…"
        thread {
            // Service may already have been asked to start via startForegroundService
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
                            status.visibility = View.GONE
                            screen.onFrame(bitmap, width, height)
                        }
                    }
                    override fun onConnected(width: Int, height: Int, desktopName: String) {
                        runOnUiThread { status.text = "${width}×$height" }
                    }
                    override fun onDisconnected(reason: String) {
                        runOnUiThread { status.visibility = View.VISIBLE; status.text = "Display disconnected: $reason" }
                    }
                })
                client.connect()
                vnc = client
                runOnUiThread {
                    screen.client = client
                    osk.client = client
                    status.text = "Display connected"
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
        val c = vnc
        if (c != null && event.deviceId >= 0) {
            // Hardware / Bluetooth / USB HID keyboards arrive here.
            val ks = KeysymMapper.fromKeyEvent(event) ?: return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) c.sendKey(ks, true)
            if (event.action == KeyEvent.ACTION_UP) c.sendKey(ks, false)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        vnc?.close()
        if (bound) unbindService(conn)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VM_ID = "vm_id"
        const val EXTRA_START = "start"
    }
}
