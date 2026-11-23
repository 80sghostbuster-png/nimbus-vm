package com.lex.nimbusvm

import android.content.Context
import android.util.Log
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/**
 * Phase-1 control layer: start/stop a real QEMU process, talk QMP, unpack firmware.
 * ARM64 is the default. x86_64 is an explicit compatibility (slow) path.
 */
class QemuEngine(private val context: Context) {

    data class Config(
        val arch: Arch = Arch.ARM64,
        val memoryMb: Int = 1024,
        val cpus: Int = 2,
        val disk: File? = null,
        val iso: File? = null,
        val sharedDir: File? = null,
        val tpm: Boolean = false,
        val secureBoot: Boolean = false,
        val extraArgs: List<String> = emptyList(),
    )

    enum class Arch { ARM64, X86_64 }

    var process: Process? = null
        private set
    private var qmpFile: File? = null

    val isRunning: Boolean get() = process?.isAlive == true

    fun nativeDir(): File = File(context.applicationInfo.nativeLibraryDir)

    fun qemuBinary(arch: Arch): File {
        val name = when (arch) {
            Arch.ARM64 -> "libqemu-system-aarch64.so"
            Arch.X86_64 -> "libqemu-system-x86_64.so"
        }
        return File(nativeDir(), name)
    }

    fun qemuImg(): File = File(nativeDir(), "libqemu-img.so")

    fun firmwareDir(): File = File(context.filesDir, "firmware").apply { mkdirs() }

    fun workDir(): File = File(context.filesDir, "vm").apply { mkdirs() }

    fun unpackFirmware() {
        val out = firmwareDir()
        val names = listOf(
            "AAVMF_CODE.fd",
            "AAVMF_CODE.secboot.fd",
            "AAVMF_VARS.fd",
            "AAVMF_VARS.ms.fd",
            "QEMU_EFI.fd",
            "OVMF_CODE_4M.fd",
            "OVMF_CODE_4M.secboot.fd",
            "OVMF_VARS_4M.fd",
            "OVMF_VARS_4M.ms.fd",
        )
        for (name in names) {
            val dest = File(out, name)
            if (dest.exists() && dest.length() > 0) continue
            val copied = tryAsset(dest, "firmware/$name.gz", gzip = true)
                || tryAsset(dest, "firmware/$name", gzip = false)
            if (!copied) {
                Log.w(TAG, "firmware asset $name not packaged")
            }
        }
    }

    private fun tryAsset(dest: File, asset: String, gzip: Boolean): Boolean {
        return try {
            context.assets.open(asset).use { raw ->
                FileOutputStream(dest).use { fos ->
                    if (gzip) GZIPInputStream(raw).use { it.copyTo(fos) } else raw.copyTo(fos)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun buildArgv(cfg: Config, serialFile: File, qmp: File): List<String> {
        val qemu = qemuBinary(cfg.arch)
        require(qemu.exists()) { "QEMU binary missing: ${qemu.absolutePath}" }
        val fw = firmwareDir()
        val args = mutableListOf(
            qemu.absolutePath,
            "-accel", "tcg,thread=multi,tb-size=128",
            "-smp", cfg.cpus.toString(),
            "-m", cfg.memoryMb.toString(),
            "-display", "none",
            "-serial", "file:${serialFile.absolutePath}",
            "-qmp", "unix:${qmp.absolutePath},server,nowait",
            "-netdev", "user,id=net0",
            "-device", "virtio-net-pci,netdev=net0",
            "-device", "qemu-xhci,id=xhci",
            "-device", "usb-kbd,bus=xhci.0",
            "-device", "usb-tablet,bus=xhci.0",
        )
        when (cfg.arch) {
            Arch.ARM64 -> {
                args += listOf(
                    "-machine", "virt,gic-version=3,virtualization=off,acpi=on",
                    "-cpu", "max",
                    "-device", "virtio-gpu-pci",
                )
                val code = if (cfg.secureBoot) "AAVMF_CODE.secboot.fd" else "AAVMF_CODE.fd"
                val varsTemplate = if (cfg.secureBoot) "AAVMF_VARS.ms.fd" else "AAVMF_VARS.fd"
                val vars = File(workDir(), "vars-arm64.fd")
                if (!vars.exists()) File(fw, varsTemplate).copyTo(vars, overwrite = false)
                args += listOf(
                    "-drive", "if=pflash,format=raw,readonly=on,file=${File(fw, code).absolutePath}",
                    "-drive", "if=pflash,format=qcow2,file=${ensureQcowVars(vars).absolutePath}",
                )
                if (cfg.tpm) {
                    // swtpm socket is started by the caller; this is the QEMU side
                    val sock = File(workDir(), "swtpm.sock")
                    args += listOf(
                        "-chardev", "socket,id=chrtpm,path=${sock.absolutePath}",
                        "-tpmdev", "emulator,id=tpm0,chardev=chrtpm",
                        "-device", "tpm-tis-device,tpmdev=tpm0",
                    )
                }
            }
            Arch.X86_64 -> {
                // Compatibility mode — TCG x86 on an ARM phone is slow.
                args += listOf(
                    "-machine", "q35,accel=tcg",
                    "-cpu", "qemu64",
                    "-device", "virtio-vga",
                )
                val code = if (cfg.secureBoot) "OVMF_CODE_4M.secboot.fd" else "OVMF_CODE_4M.fd"
                val varsTemplate = if (cfg.secureBoot) "OVMF_VARS_4M.ms.fd" else "OVMF_VARS_4M.fd"
                val vars = File(workDir(), "vars-x64.fd")
                if (!vars.exists()) File(fw, varsTemplate).copyTo(vars, overwrite = false)
                args += listOf(
                    "-drive", "if=pflash,format=raw,readonly=on,file=${File(fw, code).absolutePath}",
                    "-drive", "if=pflash,format=raw,file=${vars.absolutePath}",
                )
                if (cfg.tpm) {
                    val sock = File(workDir(), "swtpm.sock")
                    args += listOf(
                        "-chardev", "socket,id=chrtpm,path=${sock.absolutePath}",
                        "-tpmdev", "emulator,id=tpm0,chardev=chrtpm",
                        "-device", "tpm-tis,tpmdev=tpm0",
                    )
                }
            }
        }
        cfg.disk?.let {
            args += listOf(
                "-drive",
                "if=virtio,format=qcow2,file=${it.absolutePath},cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap",
            )
        }
        cfg.iso?.let {
            args += listOf("-drive", "if=virtio,format=raw,media=cdrom,readonly=on,file=${it.absolutePath}")
        }
        cfg.sharedDir?.let {
            it.mkdirs()
            args += listOf(
                "-fsdev", "local,id=fsdev0,path=${it.absolutePath},security_model=none",
                "-device", "virtio-9p-pci,fsdev=fsdev0,mount_tag=share",
            )
        }
        args += cfg.extraArgs
        return args
    }

    private fun ensureQcowVars(rawVars: File): File {
        val qcow = File(workDir(), rawVars.nameWithoutExtension + ".qcow2")
        if (!qcow.exists()) {
            val img = qemuImg()
            if (img.exists()) {
                ProcessBuilder(
                    img.absolutePath, "create", "-f", "qcow2", "-F", "raw",
                    "-b", rawVars.absolutePath, qcow.absolutePath,
                ).inheritIO().start().waitFor()
            } else {
                return rawVars
            }
        }
        return if (qcow.exists()) qcow else rawVars
    }

    fun createDisk(file: File, sizeGb: Int) {
        val img = qemuImg()
        require(img.exists()) { "qemu-img missing at ${img.absolutePath}" }
        file.parentFile?.mkdirs()
        val proc = ProcessBuilder(
            img.absolutePath, "create", "-f", "qcow2", file.absolutePath, "${sizeGb}G",
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "qemu-img failed: $out" }
    }

    fun start(cfg: Config, serialFile: File): Process {
        stop()
        unpackFirmware()
        qmpFile = File(workDir(), "qmp.sock").also { if (it.exists()) it.delete() }
        val argv = buildArgv(cfg, serialFile, qmpFile!!)
        Log.i(TAG, "argv: ${argv.joinToString(" ")}")
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        pb.environment()["HOME"] = workDir().absolutePath
        pb.directory(workDir())
        val proc = pb.start()
        process = proc
        return proc
    }

    fun qmp(execute: String, arguments: JSONObject? = null): String {
        val sock = qmpFile ?: error("no QMP socket")
        LocalSocket().use { ls ->
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val input = ls.inputStream
            val output = ls.outputStream
            val greeting = readJsonLine(input)
            writeJson(output, JSONObject().put("execute", "qmp_capabilities"))
            val cap = readJsonLine(input)
            val cmd = JSONObject().put("execute", execute)
            if (arguments != null) cmd.put("arguments", arguments)
            writeJson(output, cmd)
            val result = readJsonLine(input)
            return "greeting=$greeting cap=$cap result=$result"
        }
    }

    fun powerdown() = qmp("system_powerdown")
    fun reset() = qmp("system_reset")
    fun pause() = qmp("stop")
    fun resume() = qmp("cont")

    fun stop() {
        process?.let { p ->
            if (p.isAlive) {
                try { powerdown() } catch (_: Exception) {}
                if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }
        process = null
    }

    private fun writeJson(output: java.io.OutputStream, obj: JSONObject) {
        val data = (obj.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        output.write(data)
        output.flush()
    }

    private fun readJsonLine(input: java.io.InputStream): String {
        val acc = StringBuilder()
        while (true) {
            val n = input.read()
            if (n < 0) break
            val c = n.toChar()
            if (c == '\n') {
                val line = acc.toString().trim()
                if (line.isNotEmpty()) return line
                acc.clear()
            } else acc.append(c)
        }
        return acc.toString()
    }

    companion object {
        private const val TAG = "NimbusVM"
    }
}
