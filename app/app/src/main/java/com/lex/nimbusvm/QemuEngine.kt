package com.lex.nimbusvm

import android.content.Context
import android.util.Log
import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Control layer: start/stop a real QEMU process, talk QMP, unpack firmware and pc-bios.
 * ARM64 is the default. x86_64 is an explicit compatibility (slow) path.
 *
 * Every invocation passes -L <qemuDataDir>. TEMP/HOME/XDG_* stay under the
 * app's cache/files dirs. Graphical boots use a unix-socket VNC display.
 */
class QemuEngine(private val context: Context) {

    data class Config(
        val vmId: String = "debug",
        val arch: Arch = Arch.ARM64,
        val memoryMb: Int = 1024,
        val cpus: Int = 2,
        val disk: File? = null,
        val iso: File? = null,
        val sharedDir: File? = null,
        val tpm: Boolean = false,
        val secureBoot: Boolean = false,
        val graphics: Boolean = true,
        val extraArgs: List<String> = emptyList(),
    )

    enum class Arch { ARM64, X86_64 }

    var process: Process? = null
        private set
    private var swtpmProcess: Process? = null
    private var qmpFile: File? = null
    private var currentWorkDir: File? = null
    var lastConfig: Config? = null
        private set

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

    /** Versioned QEMU datadir passed as -L. Re-extracted when DATA_PACK_VERSION changes. */
    fun qemuDataDir(): File = File(context.filesDir, "qemu-data/$DATA_PACK_VERSION").apply { mkdirs() }

    fun workDir(vmId: String = "debug"): File {
        val dir = if (vmId == "debug") File(context.filesDir, "vm")
        else File(context.filesDir, "vms/$vmId")
        dir.mkdirs()
        return dir
    }

    fun tmpDir(): File = File(context.cacheDir, "tmp").apply { mkdirs() }

    fun runtimeDir(): File = File(context.cacheDir, "runtime").apply { mkdirs() }

    fun qemuLogFile(vmId: String = lastConfig?.vmId ?: "debug"): File =
        File(workDir(vmId), "qemu-stderr.log")

    fun vncSock(vmId: String = lastConfig?.vmId ?: "debug"): File =
        File(workDir(vmId), "vnc.sock")

    fun qmpSock(vmId: String = lastConfig?.vmId ?: "debug"): File =
        File(workDir(vmId), "qmp.sock")

    fun unpackAll() {
        unpackFirmware()
        unpackQemuData()
    }

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

    fun unpackQemuData() {
        val out = qemuDataDir()
        val stamp = File(out, ".stamp")
        val rom = File(out, "efi-virtio.rom")
        if (stamp.exists() && stamp.readText().trim() == DATA_PACK_VERSION && rom.exists() && rom.length() > 0) {
            return
        }
        out.deleteRecursively()
        out.mkdirs()
        extractAssetTree("qemu-data", out)
        stamp.writeText(DATA_PACK_VERSION)
        Log.i(TAG, "unpacked QEMU datadir into $out (efi-virtio.rom=${rom.length()})")
    }

    private fun extractAssetTree(assetDir: String, dest: File) {
        dest.mkdirs()
        val kids = try {
            context.assets.list(assetDir)
        } catch (_: Exception) {
            null
        } ?: return
        for (name in kids) {
            val assetPath = "$assetDir/$name"
            val sub = try {
                context.assets.list(assetPath)
            } catch (_: Exception) {
                null
            }
            if (sub != null && sub.isNotEmpty()) {
                extractAssetTree(assetPath, File(dest, name.removeSuffix(".gz")))
                continue
            }
            val destFile = File(dest, name.removeSuffix(".gz"))
            val copied = if (name.endsWith(".gz")) {
                tryAsset(destFile, assetPath, gzip = true)
                    || tryAsset(destFile, assetPath, gzip = false)
            } else {
                tryAsset(destFile, assetPath, gzip = false)
            }
            if (!copied) Log.w(TAG, "missing qemu-data asset $assetPath")
        }
    }

    private fun tryAsset(dest: File, asset: String, gzip: Boolean): Boolean {
        return try {
            dest.parentFile?.mkdirs()
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

    fun applyQemuEnv(pb: ProcessBuilder, vmId: String = "debug") {
        val env = pb.environment()
        val tmp = tmpDir()
        val runtime = runtimeDir()
        val home = workDir(vmId)
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = tmp.absolutePath
        env["TMP"] = tmp.absolutePath
        env["TEMP"] = tmp.absolutePath
        env["XDG_CACHE_HOME"] = File(context.cacheDir, "xdg-cache").apply { mkdirs() }.absolutePath
        env["XDG_RUNTIME_DIR"] = runtime.absolutePath
        env["XDG_DATA_HOME"] = File(context.filesDir, "xdg-data").apply { mkdirs() }.absolutePath
        env["XDG_CONFIG_HOME"] = File(context.filesDir, "xdg-config").apply { mkdirs() }.absolutePath
        env.remove("QEMU_DATA_DIR")
        pb.directory(home)
    }

    fun buildArgv(cfg: Config, serialFile: File, qmp: File, vnc: File? = null): List<String> {
        val qemu = qemuBinary(cfg.arch)
        require(qemu.exists()) { "QEMU binary missing: ${qemu.absolutePath}" }
        val fw = firmwareDir()
        val data = qemuDataDir()
        val work = workDir(cfg.vmId)
        val args = mutableListOf(
            qemu.absolutePath,
            "-L", data.absolutePath,
            "-accel", "tcg,thread=multi,tb-size=128",
            "-smp", cfg.cpus.toString(),
            "-m", cfg.memoryMb.toString(),
            "-display", "none",
            "-serial", "file:${serialFile.absolutePath}",
            "-qmp", "unix:${qmp.absolutePath},server,nowait",
            "-netdev", "user,id=net0",
            // romfile= is belt-and-braces: UEFI already has virtio drivers.
            // -L still ships efi-virtio.rom so a BIOS boot can use it.
            "-device", "virtio-net-pci,netdev=net0,romfile=",
            "-device", "qemu-xhci,id=xhci",
            "-device", "usb-kbd,bus=xhci.0",
            "-device", "usb-tablet,bus=xhci.0",
            "-device", "virtio-multitouch-pci,id=touch0",
        )
        // Bind VNC after the GPU is on the argv so display=gpu0 can resolve,
        // and attach it at the end of buildArgv (see below).
        val vncSock = if (cfg.graphics) {
            val sock = vnc ?: File(work, "vnc.sock")
            if (sock.exists()) sock.delete()
            sock
        } else null
        args += if (cfg.iso != null) listOf("-boot", "order=cd") else listOf("-boot", "order=c")
        when (cfg.arch) {
            Arch.ARM64 -> {
                args += listOf(
                    "-machine", "virt,gic-version=3,virtualization=off,acpi=on",
                    "-cpu", "max",
                    "-device", "virtio-gpu-pci,id=gpu0,xres=1280,yres=800",
                )
                val code = if (cfg.secureBoot) "AAVMF_CODE.secboot.fd" else "AAVMF_CODE.fd"
                val varsTemplate = if (cfg.secureBoot) "AAVMF_VARS.ms.fd" else "AAVMF_VARS.fd"
                val vars = File(work, "vars-arm64.fd")
                if (!vars.exists()) File(fw, varsTemplate).copyTo(vars, overwrite = false)
                args += listOf(
                    "-drive", "if=pflash,format=raw,readonly=on,file=${File(fw, code).absolutePath}",
                    "-drive", "if=pflash,format=qcow2,file=${ensureQcowVars(vars, cfg.vmId).absolutePath}",
                )
                if (cfg.tpm) {
                    val sock = File(work, "swtpm.sock")
                    args += listOf(
                        "-chardev", "socket,id=chrtpm,path=${sock.absolutePath}",
                        "-tpmdev", "emulator,id=tpm0,chardev=chrtpm",
                        "-device", "tpm-tis-device,tpmdev=tpm0",
                    )
                }
            }
            Arch.X86_64 -> {
                args += listOf(
                    "-machine", "q35,accel=tcg",
                    "-cpu", "qemu64",
                    "-device", "virtio-vga,id=gpu0",
                )
                val code = if (cfg.secureBoot) "OVMF_CODE_4M.secboot.fd" else "OVMF_CODE_4M.fd"
                val varsTemplate = if (cfg.secureBoot) "OVMF_VARS_4M.ms.fd" else "OVMF_VARS_4M.fd"
                val vars = File(work, "vars-x64.fd")
                if (!vars.exists()) File(fw, varsTemplate).copyTo(vars, overwrite = false)
                args += listOf(
                    "-drive", "if=pflash,format=raw,readonly=on,file=${File(fw, code).absolutePath}",
                    "-drive", "if=pflash,format=qcow2,file=${ensureQcowVars(vars, cfg.vmId).absolutePath}",
                )
                if (cfg.tpm) {
                    val sock = File(work, "swtpm.sock")
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
        // VNC last so display=gpu0 refers to the GPU we just added.
        vncSock?.let {
            args += listOf("-vnc", "unix:${it.absolutePath},display=gpu0")
        }
        args += cfg.extraArgs
        return args
    }

    private fun ensureQcowVars(rawVars: File, vmId: String): File {
        val qcow = File(workDir(vmId), rawVars.nameWithoutExtension + ".qcow2")
        if (!qcow.exists()) {
            val img = qemuImg()
            if (img.exists()) {
                val pb = ProcessBuilder(
                    img.absolutePath, "create", "-f", "qcow2", "-F", "raw",
                    "-b", rawVars.absolutePath, qcow.absolutePath,
                )
                applyQemuEnv(pb, vmId)
                pb.redirectErrorStream(true)
                pb.start().waitFor()
            } else {
                return rawVars
            }
        }
        return if (qcow.exists()) qcow else rawVars
    }

    fun createDisk(file: File, sizeGb: Int, vmId: String = "debug") {
        val img = qemuImg()
        require(img.exists()) { "qemu-img missing at ${img.absolutePath}" }
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > 0) return
        val pb = ProcessBuilder(
            img.absolutePath, "create", "-f", "qcow2", file.absolutePath, "${sizeGb}G",
        )
        applyQemuEnv(pb, vmId)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "qemu-img failed: $out" }
    }

    fun start(cfg: Config, serialFile: File): Process {
        stop()
        unpackAll()
        lastConfig = cfg
        currentWorkDir = workDir(cfg.vmId)
        qmpFile = File(currentWorkDir, "qmp.sock").also { if (it.exists()) it.delete() }
        val vnc = File(currentWorkDir, "vnc.sock").also { if (it.exists()) it.delete() }
        val log = qemuLogFile(cfg.vmId)
        log.writeText("")
        stopSwtpm()
        val swtpm = if (cfg.tpm) startSwtpm(cfg.vmId) else null
        swtpmProcess = swtpm
        if (cfg.tpm && swtpm == null) {
            Log.w(TAG, "swtpm not available; starting without TPM")
        }
        val effective = if (cfg.tpm && swtpm == null) cfg.copy(tpm = false) else cfg
        val argv = buildArgv(effective, serialFile, qmpFile!!, vnc)
        Log.i(TAG, "argv: ${argv.joinToString(" ")}")
        log.appendText("argv: ${argv.joinToString(" ")}\n")
        val pb = ProcessBuilder(argv)
        applyQemuEnv(pb, cfg.vmId)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        val proc = pb.start()
        process = proc
        return proc
    }

    /** Start bundled swtpm if present (libswtpm.so). Returns the process, or null. */
    private fun startSwtpm(vmId: String): Process? {
        val bin = File(nativeDir(), "libswtpm.so")
        if (!bin.exists()) return null
        val work = workDir(vmId)
        val state = File(work, "tpm").apply { mkdirs() }
        val sock = File(work, "swtpm.sock")
        if (sock.exists()) sock.delete()
        val log = File(work, "swtpm.log")
        val pb = ProcessBuilder(
            bin.absolutePath, "socket",
            "--tpm2",
            "--tpmstate", "dir=${state.absolutePath}",
            "--ctrl", "type=unixio,path=${sock.absolutePath}",
            "--log", "file=${log.absolutePath}",
        )
        applyQemuEnv(pb, vmId)
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(File(work, "swtpm-stderr.log")))
        return try {
            val proc = pb.start()
            // Wait briefly for the control socket; QEMU will fail immediately if it is missing.
            repeat(50) {
                if (!proc.isAlive) {
                    Log.w(TAG, "swtpm exited early: ${log.takeIf { it.exists() }?.readText()?.takeLast(1000)}")
                    return null
                }
                if (sock.exists()) return proc
                Thread.sleep(100)
            }
            Log.w(TAG, "swtpm socket did not appear")
            proc.destroy()
            null
        } catch (e: Exception) {
            Log.w(TAG, "swtpm failed to start: $e")
            null
        }
    }

    fun qmp(execute: String, arguments: JSONObject? = null): String {
        val sock = qmpFile ?: error("no QMP socket")
        return qmpAt(sock, execute, arguments)
    }

    fun qmpAt(sock: File, execute: String, arguments: JSONObject? = null): String {
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
    fun screendump(dest: File): String {
        dest.parentFile?.mkdirs()
        return qmp("screendump", JSONObject().put("filename", dest.absolutePath).put("format", "png"))
    }

    /**
     * Forward Android pointers to virtio-multitouch-pci (id=touch0) via QMP.
     * Coordinates are framebuffer pixels; they are scaled to QEMU's 0..0x7fff abs range.
     * [contacts] is the full current set of active slots (empty = all up).
     */
    data class TouchContact(
        val slot: Int,
        val trackingId: Int,
        val x: Int,
        val y: Int,
        /** begin, update, or end */
        val phase: String,
    )

    fun sendMultitouch(contacts: List<TouchContact>, fbW: Int, fbH: Int) {
        if (fbW <= 0 || fbH <= 0) return
        val events = JSONArray()
        val maxX = (fbW - 1).coerceAtLeast(1)
        val maxY = (fbH - 1).coerceAtLeast(1)
        fun absX(x: Int) = (x.coerceIn(0, maxX) * 0x7FFF) / maxX
        fun absY(y: Int) = (y.coerceIn(0, maxY) * 0x7FFF) / maxY
        fun mtt(type: String, slot: Int, trackingId: Int, axis: String, value: Int) {
            events.put(
                JSONObject()
                    .put("type", "mtt")
                    .put(
                        "data",
                        JSONObject()
                            .put("type", type)
                            .put("slot", slot)
                            .put("tracking-id", trackingId)
                            .put("axis", axis)
                            .put("value", value),
                    ),
            )
        }
        if (contacts.isEmpty()) {
            mtt("end", 0, -1, "x", 0)
        } else {
            for (c in contacts) {
                if (c.phase == "end") {
                    mtt("end", c.slot, -1, "x", 0)
                } else {
                    val typ = if (c.phase == "begin") "begin" else "update"
                    mtt(typ, c.slot, c.trackingId, "x", 0)
                    mtt("data", c.slot, c.trackingId, "x", absX(c.x))
                    mtt("data", c.slot, c.trackingId, "y", absY(c.y))
                }
            }
        }
        // Do not pass device=touch0: QEMU treats that argument as a *display*
        // console name and qemu_console_lookup_by_device() aborts on the
        // serial text console (missing "device" link). The mtt events still
        // reach virtio-multitouch-pci because it is the only MTT handler.
        qmp(
            "input-send-event",
            JSONObject().put("events", events),
        )
    }

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
        lastConfig = null
        stopSwtpm()
    }

    private fun stopSwtpm() {
        swtpmProcess?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        swtpmProcess = null
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
        /** Bump this whenever bundled pc-bios assets change so they are re-extracted. */
        const val DATA_PACK_VERSION = "2"
    }
}
