package com.lex.nimbusvm

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

class DebugActivity : AppCompatActivity() {
    private lateinit var engine: QemuEngine
    private lateinit var status: TextView
    private lateinit var serialView: TextView
    private var serialFile: File? = null
    private var tail = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        engine = QemuEngine(this)
        status = findViewById(R.id.status)
        serialView = findViewById(R.id.serial)

        val qemu = engine.qemuBinary(QemuEngine.Arch.ARM64)
        status.text = buildString {
            append("native dir: ${engine.nativeDir()}\n")
            append("qemu aarch64: ${qemu.exists()} ${qemu.absolutePath}\n")
            append("qemu-img: ${engine.qemuImg().exists()}\n")
            append("This is a real QEMU engine debug screen (no fake desktop).")
        }

        findViewById<Button>(R.id.boot).setOnClickListener { bootFirmware() }
        findViewById<Button>(R.id.powerdown).setOnClickListener {
            thread {
                val r = runCatching { engine.powerdown() }.getOrElse { it.toString() }
                runOnUiThread { status.append("\nQMP powerdown: $r") }
            }
        }
        findViewById<Button>(R.id.stop).setOnClickListener {
            thread {
                engine.stop()
                runOnUiThread { status.append("\nstopped") }
            }
        }
        engine.unpackFirmware()
    }

    private fun bootFirmware() {
        tail = true
        serialFile = File(engine.workDir(), "serial.log").also { it.writeText("") }
        thread {
            try {
                val cfg = QemuEngine.Config(
                    arch = QemuEngine.Arch.ARM64,
                    memoryMb = 512,
                    cpus = 2,
                    tpm = false,
                )
                val proc = engine.start(cfg, serialFile!!)
                runOnUiThread { status.append("\nstarted pid-like alive=${proc.isAlive}") }
                thread { tailSerial() }
                val err = proc.inputStream.bufferedReader().readText()
                val code = proc.waitFor()
                tail = false
                runOnUiThread { status.append("\nqemu exited $code\n$err") }
            } catch (e: Exception) {
                tail = false
                runOnUiThread { status.append("\nerror: $e") }
            }
        }
    }

    private fun tailSerial() {
        val f = serialFile ?: return
        var pos = 0L
        while (tail) {
            if (f.exists() && f.length() > pos) {
                val bytes = f.readBytes()
                if (bytes.size > pos) {
                    val chunk = String(bytes, pos.toInt(), bytes.size - pos.toInt(), Charsets.UTF_8)
                    pos = bytes.size.toLong()
                    runOnUiThread {
                        serialView.text = String(bytes, Charsets.UTF_8).takeLast(8000)
                    }
                    chunk.toString()
                }
            }
            Thread.sleep(400)
        }
    }

    override fun onDestroy() {
        tail = false
        engine.stop()
        super.onDestroy()
    }
}
