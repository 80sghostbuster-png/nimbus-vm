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

        engine.unpackAll()
        val qemu = engine.qemuBinary(QemuEngine.Arch.ARM64)
        val rom = File(engine.qemuDataDir(), "efi-virtio.rom")
        status.text = buildString {
            append("native dir: ${engine.nativeDir()}\n")
            append("qemu aarch64: ${qemu.exists()} ${qemu.absolutePath}\n")
            append("qemu-img: ${engine.qemuImg().exists()}\n")
            append("datadir -L: ${engine.qemuDataDir()}\n")
            append("efi-virtio.rom: ${rom.exists()} ${rom.length()} bytes\n")
            append("This boots real UEFI firmware through QEMU (no fake desktop).")
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
                runOnUiThread { status.append("\nstarted alive=${proc.isAlive}") }
                thread { tailOutput() }
                val code = proc.waitFor()
                tail = false
                val err = engine.qemuLogFile().takeIf { it.exists() }?.readText().orEmpty().takeLast(4000)
                runOnUiThread { status.append("\nqemu exited $code\n$err") }
            } catch (e: Exception) {
                tail = false
                runOnUiThread { status.append("\nerror: $e") }
            }
        }
    }

    private fun tailOutput() {
        val serial = serialFile
        val log = engine.qemuLogFile()
        var serialPos = 0L
        var logPos = 0L
        while (tail) {
            val serialText = readFrom(serial, serialPos).also { serialPos = it.first }.second
            val logText = readFrom(log, logPos).also { logPos = it.first }.second
            if (serialText.isNotEmpty() || logText.isNotEmpty()) {
                val combined = buildString {
                    if (logText.isNotBlank()) {
                        append("-- QEMU --\n")
                        append(logText.takeLast(2000))
                        append("\n")
                    }
                    append("-- serial --\n")
                    append((serial?.takeIf { it.exists() }?.readText() ?: "").takeLast(6000))
                }
                runOnUiThread { serialView.text = combined }
            }
            Thread.sleep(300)
        }
    }

    private fun readFrom(file: File?, pos: Long): Pair<Long, String> {
        if (file == null || !file.exists() || file.length() <= pos) return pos to ""
        val bytes = file.readBytes()
        if (bytes.size <= pos) return pos to ""
        val chunk = String(bytes, pos.toInt(), bytes.size - pos.toInt(), Charsets.UTF_8)
        return bytes.size.toLong() to chunk
    }

    override fun onDestroy() {
        tail = false
        engine.stop()
        super.onDestroy()
    }
}
