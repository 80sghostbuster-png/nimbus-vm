package com.lex.nimbusvm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.RandomAccessFile

/** Owns the running QEMU process so it survives DisplayActivity recreation. */
class VmService : Service() {
    inner class LocalBinder : Binder() { fun service(): VmService = this@VmService }

    lateinit var engine: QemuEngine
        private set
    lateinit var store: VmStore
        private set
    var currentId: String? = null
        private set
    var currentProfile: BootProfile = BootSettings.defaultProfile
        private set
    @Volatile var savingState: Boolean = false
    @Volatile var resumedFromSave: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var monitorGen = 0
    private var monitorThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        engine = QemuEngine(this)
        store = VmStore(this)
        engine.unpackAll()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_VM_ID) ?: return START_NOT_STICKY
                startVm(id)
            }
            ACTION_STOP -> stopVm()
        }
        return START_STICKY
    }

    fun startVm(
        id: String,
        clearSerial: Boolean = true,
        profile: BootProfile = BootSettings.initialProfile(this),
        fresh: Boolean = false,
    ): Result<Unit> {
        // Reload store so metadata changed by other activities is current.
        store = VmStore(this)
        val vm = store.get(id) ?: return Result.failure(IllegalArgumentException("Unknown VM $id"))
        return try {
            if (fresh && vm.hasSavedState(this)) {
                discardSavedState(id)
            }
            val savedInfo = if (!fresh) vm.savedStateInfo(this) else null
            var cfg = store.toConfig(vm)
            val effectiveProfile: BootProfile
            if (savedInfo != null) {
                val tag = savedInfo.optString("tag", QemuEngine.SNAPSHOT_TAG)
                val cpuModel = savedInfo.optString("cpuModel", profile.cpuModel)
                val tcgThread = savedInfo.optString("tcgThread", profile.tcgThread)
                val rawVars = savedInfo.optBoolean("rawVars", profile.rawVars)
                val mem = savedInfo.optInt("memoryMb", vm.memoryMb)
                val cpus = savedInfo.optInt("cpus", vm.cpus)
                val isoAttached = savedInfo.optBoolean("isoAttached", vm.isoAttached)
                val isoFile = vm.isoFile(this).takeIf { isoAttached && it.exists() }
                cfg = cfg.copy(
                    memoryMb = mem,
                    cpus = cpus,
                    iso = isoFile,
                    cpuModel = if (vm.arch == QemuEngine.Arch.ARM64) cpuModel else cfg.cpuModel,
                    tcgThread = if (vm.arch == QemuEngine.Arch.ARM64) tcgThread else cfg.tcgThread,
                    rawVars = if (vm.arch == QemuEngine.Arch.ARM64) rawVars else cfg.rawVars,
                    loadSnapshot = tag,
                )
                effectiveProfile = profile.copy(
                    id = savedInfo.optString("profileId", profile.id),
                    cpuModel = cpuModel,
                    tcgThread = tcgThread,
                    rawVars = rawVars,
                )
                resumedFromSave = true
                lastFbDiag = "guest fb (QMP) resuming…"
            } else {
                if (vm.arch == QemuEngine.Arch.ARM64) {
                    cfg = cfg.copy(
                        cpuModel = profile.cpuModel,
                        tcgThread = profile.tcgThread,
                        rawVars = profile.rawVars,
                    )
                }
                effectiveProfile = profile
                resumedFromSave = false
                lastFbDiag = "guest fb (QMP) pending"
            }
            if (cfg.disk != null && !cfg.disk.exists()) engine.createDisk(cfg.disk, vm.diskGb, vm.id)
            val serial = vm.serialFile(this)
            if (clearSerial) serial.writeText("")
            val resumeNote = if (resumedFromSave) " (resuming saved state ${cfg.loadSnapshot})" else ""
            engine.appendNote(serial, "[nimbus] starting profile ${effectiveProfile.describe()}$resumeNote")
            startForeground(NOTIF_ID, notification(vm.name, if (resumedFromSave) "Resuming…" else "Starting…"))
            acquireWakeLock()
            engine.start(cfg, serial)
            currentId = id
            currentProfile = effectiveProfile
            vm.lastUsedAt = System.currentTimeMillis()
            store.update(vm)
            startForeground(NOTIF_ID, notification(vm.name, "Running (${effectiveProfile.id})"))
            startMonitor(id, serial, effectiveProfile)
            Result.success(Unit)
        } catch (e: Exception) {
            stopMonitor()
            stopForeground(STOP_FOREGROUND_REMOVE)
            Result.failure(e)
        }
    }

    fun stopVm() {
        stopMonitor()
        engine.stop()
        currentId = null
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun saveVmState(id: String): QemuEngine.SaveResult {
        if (!isThisVmRunning(id)) return QemuEngine.SaveResult(false, "VM is not running")
        val vm = store.get(id) ?: return QemuEngine.SaveResult(false, "Unknown VM $id")
        val cfg = engine.lastConfig ?: store.toConfig(vm)
        savingState = true
        return try {
            val res = engine.saveSnapshot(QemuEngine.SNAPSHOT_TAG)
            if (res.ok) {
                val meta = org.json.JSONObject()
                    .put("tag", QemuEngine.SNAPSHOT_TAG)
                    .put("savedAt", System.currentTimeMillis())
                    .put("profileId", currentProfile.id)
                    .put("cpuModel", cfg.cpuModel)
                    .put("tcgThread", cfg.tcgThread)
                    .put("rawVars", cfg.rawVars)
                    .put("memoryMb", cfg.memoryMb)
                    .put("cpus", cfg.cpus)
                    .put("isoAttached", cfg.iso != null)
                    .put("vmSize", res.vmSize ?: "")
                val dest = vm.savedStateFile(this)
                val tmp = File(dest.parentFile, dest.name + ".tmp")
                tmp.writeText(meta.toString(2))
                if (dest.exists()) dest.delete()
                tmp.renameTo(dest)
                engine.appendNote(vm.serialFile(this), "[nimbus] saved state (${res.vmSize ?: "ok"})")
            } else {
                engine.appendNote(vm.serialFile(this), "[nimbus] save failed: ${res.message}")
            }
            res
        } finally {
            savingState = false
        }
    }

    fun discardSavedState(id: String) {
        val vm = store.get(id)
        if (isThisVmRunning(id)) {
            engine.deleteSnapshotRunning(QemuEngine.SNAPSHOT_TAG)
        } else {
            engine.deleteSnapshotOffline(id, vm?.arch ?: QemuEngine.Arch.ARM64, QemuEngine.SNAPSHOT_TAG)
        }
        val f = vm?.savedStateFile(this) ?: File(engine.workDir(id), "saved-state.json")
        if (f.exists()) f.delete()
    }

    /** Latest QMP screendump summary for the on-screen diagnostics. */
    @Volatile var lastFbDiag: String = "guest fb (QMP) pending"

    fun isThisVmRunning(id: String): Boolean = currentId == id && engine.isRunning

    private fun startMonitor(id: String, serial: File, profile: BootProfile) {
        stopMonitor()
        val gen = monitorGen
        val startedAt = System.currentTimeMillis()
        val auto = BootSettings.mode(this) == BootSettings.MODE_AUTO
        val stderr = engine.qemuLogFile(id)
        val dumpFile = File(engine.workDir(id), "qmp-screendump.ppm")
        val dumpPng = File(engine.workDir(id), "qmp-screendump.png")
        monitorThread = Thread({
            var bannerAt = 0L
            var kernelAt = 0L
            var kernelHinted = false
            var painted = false
            var lastSerialLen = if (serial.exists()) serial.length() else 0L
            var lastGrowthAt = startedAt
            var stderrPos = if (stderr.exists()) stderr.length() else 0L
            var ticks = 0
            var lastDumpAt = 0L
            while (gen == monitorGen && engine.isRunning) {
                try {
                    Thread.sleep(5_000)
                } catch (_: InterruptedException) {
                    break
                }
                if (gen != monitorGen || !engine.isRunning) break
                if (savingState) continue
                ticks++
                val now = System.currentTimeMillis()
                val elapsedSec = (now - startedAt) / 1000
                val snap = try {
                    engine.diagnosticSnapshot()
                } catch (e: Exception) {
                    "qmp-error: ${e.message}"
                }
                var extra = ""
                if (now - lastDumpAt >= 10_000L) {
                    lastDumpAt = now
                    extra = try {
                        dumpFile.delete()
                        val reply = engine.screendump(dumpFile)
                        val counted = countNonBlackPpm(dumpFile, dumpPng)
                        if (counted != null) {
                            val (nonBlack, total, w, h) = counted
                            if (nonBlack > 64) painted = true
                            lastFbDiag = "guest fb (QMP) ${w}x${h} nonblack=$nonBlack/$total @${elapsedSec}s"
                            " ramfb=${w}x${h} nonblack=$nonBlack/$total"
                        } else {
                            val why = reply.take(80).replace('\n', ' ')
                            lastFbDiag = "guest fb (QMP) unreadable @${elapsedSec}s $why"
                            " ramfb=screendump-unreadable $why"
                        }
                    } catch (e: Exception) {
                        lastFbDiag = "guest fb (QMP) error: ${e.message}"
                        " ramfb=screendump-error: ${e.message}"
                    }
                }
                val serLen = if (serial.exists()) serial.length() else 0L
                if (serLen > lastSerialLen) {
                    lastGrowthAt = now
                    lastSerialLen = serLen
                }
                val quietSec = (now - lastGrowthAt) / 1000
                engine.appendNote(
                    serial,
                    "[nimbus qmp ${elapsedSec}s profile=${profile.id}] $snap serial=${serLen}B quiet=${quietSec}s$extra",
                )
                // appendNote grew the file; don't treat our own notes as guest progress.
                lastSerialLen = if (serial.exists()) serial.length() else lastSerialLen
                stderrPos = appendNewStderr(stderr, serial, stderrPos)
                lastSerialLen = if (serial.exists()) serial.length() else lastSerialLen
                val text = tailText(serial, 256 * 1024)
                if (bannerAt == 0L && BootSettings.hasBanner(text)) {
                    bannerAt = now
                    engine.appendNote(serial, "[nimbus] UEFI banner seen at ${elapsedSec}s")
                    lastSerialLen = if (serial.exists()) serial.length() else lastSerialLen
                }
                if (kernelAt == 0L && BootSettings.hasKernel(text)) {
                    kernelAt = now
                    engine.appendNote(
                        serial,
                        "[nimbus] kernel output seen at ${elapsedSec}s. Kernel text continues on the VM display (and on Serial via console=ttyAMA0). Auto-fallback is off from here: a quiet Serial after the kernel starts is not a hang.",
                    )
                    lastSerialLen = if (serial.exists()) serial.length() else lastSerialLen
                    kernelHinted = true
                }
                if (!auto) continue
                if (resumedFromSave) continue
                if (painted) continue
                // Once the kernel runs, never restart on Serial silence: after
                // "bootconsole [pl11] disabled" a console=tty0 kernel is legitimately
                // quiet on Serial (0.2.8 restarted healthy boots after 60s).
                if (kernelAt != 0L) continue
                val stalled = now - lastGrowthAt >= BootSettings.STALL_TIMEOUT_MS
                if (!stalled) continue
                val next = BootSettings.nextAutoProfile(profile)
                if (next == null) {
                    engine.appendNote(
                        serial,
                        "[nimbus] auto-fallback exhausted after ${profile.id} (Serial quiet ${quietSec}s); leaving this session running",
                    )
                    break
                }
                engine.appendNote(
                    serial,
                    "[nimbus] auto-fallback: Serial quiet for ${quietSec}s on ${profile.id}; restarting with ${next.describe()}",
                )
                try {
                    engine.stop()
                    startVm(id, clearSerial = false, profile = next)
                } catch (e: Exception) {
                    engine.appendNote(serial, "[nimbus] auto-fallback restart failed: ${e.message}")
                }
                return@Thread
            }
            Log.i(TAG, "monitor exit gen=$gen ticks=$ticks running=${engine.isRunning}")
        }, "nimbus-boot-monitor").also { it.isDaemon = true; it.start() }
    }

    /** @return non-black count, total pixels, width, height */
    /**
     * QMP screendump in QEMU's default PPM (P6) format: the Android QEMU
     * build has no libpng, so format=png failed and the diag always said
     * "unreadable". Counts non-black pixels and writes a PNG copy for tests.
     */
    private fun countNonBlackPpm(file: File, pngOut: File): Quad? {
        if (!file.exists() || file.length() < 16) return null
        val bytes = file.readBytes()
        var pos = 0
        fun token(): String {
            while (pos < bytes.size) {
                val c = bytes[pos].toInt().toChar()
                if (c == '#') { while (pos < bytes.size && bytes[pos].toInt() != '\n'.code) pos++ }
                else if (c.isWhitespace()) pos++ else break
            }
            val start = pos
            while (pos < bytes.size && !bytes[pos].toInt().toChar().isWhitespace()) pos++
            return String(bytes, start, pos - start, Charsets.US_ASCII)
        }
        if (token() != "P6") return null
        val w = token().toIntOrNull() ?: return null
        val h = token().toIntOrNull() ?: return null
        val max = token().toIntOrNull() ?: return null
        pos++ // single whitespace before raster
        if (w <= 0 || h <= 0 || max != 255 || bytes.size < pos + w * h * 3) return null
        var n = 0
        val argb = IntArray(w * h)
        var i = pos
        for (k in 0 until w * h) {
            val r = bytes[i].toInt() and 0xff
            val g = bytes[i + 1].toInt() and 0xff
            val b = bytes[i + 2].toInt() and 0xff
            if ((r or g or b) != 0) n++
            argb[k] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            i += 3
        }
        try {
            val bmp = android.graphics.Bitmap.createBitmap(argb, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            pngOut.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        } catch (_: Exception) {
        }
        return Quad(n, w * h, w, h)
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)

    private fun stopMonitor() {
        monitorGen++
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun appendNewStderr(stderr: File, serial: File, already: Long): Long {
        if (!stderr.exists()) return already
        val len = stderr.length()
        if (len <= already) return len
        return try {
            val chunk = RandomAccessFile(stderr, "r").use { raf ->
                raf.seek(already)
                val n = (len - already).coerceAtMost(16 * 1024).toInt()
                val buf = ByteArray(n)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
            val trimmed = chunk.trim()
            if (trimmed.isNotEmpty()) {
                engine.appendNote(serial, "[nimbus qemu-stderr]\n$trimmed")
            }
            already + (len - already).coerceAtMost(16 * 1024)
        } catch (_: Exception) {
            len
        }
    }

    private fun tailText(file: File, max: Int): String {
        if (!file.exists()) return ""
        val len = file.length()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val start = (len - max).coerceAtLeast(0)
                raf.seek(start)
                val buf = ByteArray((len - start).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nimbusvm:vm").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Running VMs", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun notification(name: String, state: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, DisplayActivity::class.java)
                .putExtra(DisplayActivity.EXTRA_VM_ID, currentId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(name)
            .setContentText(state)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopMonitor()
        engine.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lex.nimbusvm.START"
        const val ACTION_STOP = "com.lex.nimbusvm.STOP"
        const val EXTRA_VM_ID = "vm_id"
        private const val CHANNEL = "nimbus-vm"
        private const val NOTIF_ID = 42
        private const val TAG = "NimbusVM"

        fun start(context: Context, vmId: String) {
            val i = Intent(context, VmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VM_ID, vmId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
