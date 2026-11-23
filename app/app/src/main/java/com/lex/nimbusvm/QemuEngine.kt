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
        val cpuModel: String = BootSettings.defaultProfile.cpuModel,
        val tcgThread: String = BootSettings.defaultProfile.tcgThread,
        val rawVars: Boolean = false,
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

    /**
     * Guest framebuffer sized to the phone's landscape aspect so the installer
     * can fill the display without wide letterbox bars. Long side is capped
     * at 1920 to keep TCG cost reasonable. ramfb itself has no xres property
     * (firmware programs the mode via fw_cfg); virtio-gpu uses these values
     * directly.
     */
    fun landscapeFbSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val long = maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(640)
        val short = minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(360)
        val cap = 1920
        val scale = if (long > cap) cap.toFloat() / long else 1f
        fun even(v: Int) = (v and 0x7ffffffe).coerceAtLeast(2)
        val w = even((long * scale).toInt().coerceIn(640, cap))
        val h = even((short * scale).toInt().coerceIn(360, cap))
        return w to h
    }

    fun serialSock(vmId: String = lastConfig?.vmId ?: "debug"): File =
        File(workDir(vmId), "serial.sock")

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
        // Extract before choosing -m / display: installer boots cap RAM and
        // use ramfb. Re-runs every ISO boot so a 0.2.0/0.2.1/0.2.3 VM is
        // re-detected.
        val extractFile = File(work, "installer-extract.txt")
        val extracted = if (cfg.arch == Arch.ARM64 && cfg.iso != null) {
            IsoInspector.tryExtractArm64Installer(cfg.iso, File(work, "installer")).also {
                extractFile.writeText(it.log + "\n")
                Log.i(TAG, it.log)
            }
        } else {
            extractFile.writeText("No ARM64 ISO attached; not extracting an installer kernel.\n")
            null
        }
        val installer = extracted?.installer
        // 5–6 GB (half of a typical phone) plus -cpu max is a heavy TCG
        // combination for a 46 MB vmlinuz. Cap installer RAM; keep the
        // user's setting for disk/firmware boots.
        val memoryMb = if (installer != null) minOf(cfg.memoryMb, 2048) else cfg.memoryMb
        val args = mutableListOf(
            qemu.absolutePath,
            "-L", data.absolutePath,
            "-accel", "tcg,thread=${cfg.tcgThread},tb-size=128",
            "-smp", cfg.cpus.toString(),
            "-m", memoryMb.toString(),
            "-display", "none",
            "-chardev", "socket,id=serial0,path=${File(work, "serial.sock").absolutePath},server=on,wait=off,logfile=${serialFile.absolutePath},logappend=on",
            "-serial", "chardev:serial0",
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
        // ARM64 debian-installer ISOs: boot the gtk (or text) kernel directly
        // so efifb/ramfb stay alive. GRUB's linux loader does not fill
        // screen_info, and ACPI SPCR would otherwise make d-i prefer the UART.
        // ramfb is programmed by AAVMF via fw_cfg; do not drop pflash here
        // (a no-firmware boot leaves "Guest has not initialized the display").
        args += if (cfg.iso != null) listOf("-boot", "order=cd") else listOf("-boot", "order=c")
        when (cfg.arch) {
            Arch.ARM64 -> {
                val acpi = if (installer != null) "off" else "on"
                args += listOf(
                    "-machine", "virt,gic-version=3,virtualization=off,acpi=$acpi",
                    "-cpu", cfg.cpuModel,
                )
                if (installer != null) {
                    // -kernel installer (gtk or text): ramfb provides /dev/fb0
                    // (efifb). virtio-gpu alone has DRM but no fbdev, so the
                    // gtk initrd prints "Framebuffer not available; disabling
                    // graphical frontend" and falls back to a text UI.
                    args += listOf("-device", "ramfb,id=ramfb0")
                } else {
                    // Firmware/GRUB and disk boots use virtio-gpu.
                    val (fbW, fbH) = landscapeFbSize()
                    args += listOf("-device", "virtio-gpu-pci,id=gpu0,xres=$fbW,yres=$fbH")
                }
                val code = if (cfg.secureBoot) "AAVMF_CODE.secboot.fd" else "AAVMF_CODE.fd"
                val varsTemplate = if (cfg.secureBoot) "AAVMF_VARS.ms.fd" else "AAVMF_VARS.fd"
                val vars = File(work, "vars-arm64.fd")
                if (!vars.exists()) File(fw, varsTemplate).copyTo(vars, overwrite = false)
                val varsDrive = if (cfg.rawVars) {
                    val raw = ensureRawVars(vars, cfg.vmId)
                    "if=pflash,format=raw,file=${raw.absolutePath}"
                } else {
                    "if=pflash,format=qcow2,file=${ensureQcowVars(vars, cfg.vmId).absolutePath}"
                }
                args += listOf(
                    "-drive", "if=pflash,format=raw,readonly=on,file=${File(fw, code).absolutePath}",
                    "-drive", varsDrive,
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
                    "-device", "virtio-vga,id=gpu0,xres=${landscapeFbSize().first},yres=${landscapeFbSize().second}",
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
        // VNC last so the display= device is already on the argv.
        // ARM64 installer -kernel boots bind VNC to ramfb0 (efifb). Disk
        // and generic ISO (firmware/GRUB) boots use virtio-gpu (gpu0).
        vncSock?.let {
            val head = when {
                cfg.arch != Arch.ARM64 -> "gpu0"
                installer != null -> "ramfb0"
                else -> "gpu0"
            }
            args += listOf("-vnc", "unix:${it.absolutePath},display=$head")
        }
        if (installer != null) {
            args += listOf(
                "-kernel", installer.kernel.absolutePath,
                "-initrd", installer.initrd.absolutePath,
                "-append", installer.append,
            )
        }
        args += cfg.extraArgs
        return args
    }

    /** Writable raw VARS copy (no qcow2 overlay). Fresh file so a prior qcow2 is not reused. */
    private fun ensureRawVars(template: File, vmId: String): File {
        val raw = File(workDir(vmId), template.nameWithoutExtension + "-raw.fd")
        if (!raw.exists() || raw.length() == 0L) {
            template.copyTo(raw, overwrite = true)
        }
        return raw
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
        File(currentWorkDir, "serial.sock").let { if (it.exists()) it.delete() }
        val log = qemuLogFile(cfg.vmId)
        if (log.exists() && log.length() > 0) {
            log.appendText("\n======= qemu restart ${java.util.Date()} =======\n")
        } else {
            log.writeText("")
        }
        val self = selfTest()
        File(workDir(cfg.vmId), "selftest.txt").writeText(self)
        appendNote(serialFile, "[nimbus self-test]\n$self")
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
        File(workDir(cfg.vmId), "installer-extract.txt").takeIf { it.exists() }?.let {
            log.appendText("extract: ${it.readText()}\n")
        }
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
        val (greeting, cap, result) = qmpRaw(sock, execute, arguments)
        return "greeting=$greeting cap=$cap result=$result"
    }

    /** QMP reply object (events are skipped). */
    fun qmpResult(execute: String, arguments: JSONObject? = null): JSONObject {
        val sock = qmpFile ?: error("no QMP socket")
        val (_, _, result) = qmpRaw(sock, execute, arguments)
        return JSONObject(result)
    }

    private fun qmpRaw(sock: File, execute: String, arguments: JSONObject? = null): Triple<String, String, String> {
        LocalSocket().use { ls ->
            ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            val input = ls.inputStream
            val output = ls.outputStream
            val greeting = readJsonLine(input)
            writeJson(output, JSONObject().put("execute", "qmp_capabilities"))
            val cap = readQmpReply(input)
            val cmd = JSONObject().put("execute", execute)
            if (arguments != null) cmd.put("arguments", arguments)
            writeJson(output, cmd)
            val result = readQmpReply(input)
            return Triple(greeting, cap, result)
        }
    }

    private fun readQmpReply(input: java.io.InputStream): String {
        repeat(16) {
            val line = readJsonLine(input)
            if (line.isBlank()) return line
            val obj = try { JSONObject(line) } catch (_: Exception) { return line }
            if (obj.has("event")) return@repeat
            return line
        }
        return ""
    }

    /**
     * Compact query-status + CPU PC (via HMP "info registers") for the Serial log.
     */
    fun diagnosticSnapshot(): String {
        val status = try {
            val r = qmpResult("query-status")
            r.optJSONObject("return")?.optString("status") ?: r.toString().take(200)
        } catch (e: Exception) {
            "status-error: ${e.message}"
        }
        val regs = try {
            val r = qmpResult(
                "human-monitor-command",
                JSONObject().put("command-line", "info registers"),
            )
            r.optString("return")
        } catch (e: Exception) {
            "regs-error: ${e.message}"
        }
        val pc = Regex("""(?i)\bPC[=:]\s*([0-9A-Fa-f]+)""").find(regs)?.groupValues?.get(1)
            ?: Regex("""(?i)\bpc[=:]\s*([0-9A-Fa-f]+)""").find(regs)?.groupValues?.get(1)
        val pstate = Regex("""(?i)PSTATE[=:]\s*([0-9A-Fa-fx]+)""").find(regs)?.groupValues?.get(1)
        val compactRegs = if (pc != null) {
            "PC=0x$pc" + (if (pstate != null) " PSTATE=$pstate" else "")
        } else {
            regs.replace("\n", " ").take(400)
        }
        return "status=$status $compactRegs"
    }

    fun selfTest(): String {
        unpackAll()
        val lines = mutableListOf<String>()
        fun runVersion(bin: File, label: String) {
            if (!bin.exists()) {
                lines += "$label: MISSING ${bin.absolutePath}"
                return
            }
            lines += "$label: ${bin.absolutePath} ${bin.length()} bytes"
            try {
                val pb = ProcessBuilder(bin.absolutePath, "-version")
                applyQemuEnv(pb, "debug")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val ok = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (!ok) {
                    proc.destroyForcibly()
                    lines += "$label -version: timeout"
                } else {
                    lines += "$label -version (exit ${proc.exitValue()}): ${out.take(400)}"
                }
            } catch (e: Exception) {
                lines += "$label -version failed: ${e.message}"
            }
        }
        runVersion(qemuBinary(Arch.ARM64), "qemu-system-aarch64")
        runVersion(qemuImg(), "qemu-img")
        val fw = firmwareDir()
        for (name in listOf("AAVMF_CODE.fd", "AAVMF_VARS.fd", "QEMU_EFI.fd")) {
            val f = File(fw, name)
            lines += if (f.exists()) "firmware $name: ${f.length()} bytes" else "firmware $name: MISSING"
        }
        val rom = File(qemuDataDir(), "efi-virtio.rom")
        lines += if (rom.exists() && rom.length() > 0) {
            "efi-virtio.rom: ${rom.length()} bytes"
        } else {
            "efi-virtio.rom: MISSING"
        }
        val text = lines.joinToString("\n")
        File(context.filesDir, "selftest.txt").writeText(text + "\n")
        Log.i(TAG, "self-test:\n$text")
        return text
    }

    fun appendNote(file: File, text: String) {
        try {
            file.parentFile?.mkdirs()
            file.appendText("\n$text\n")
        } catch (e: Exception) {
            Log.w(TAG, "appendNote failed: $e")
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
