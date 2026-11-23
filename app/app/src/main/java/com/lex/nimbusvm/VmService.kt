package com.lex.nimbusvm

import android.graphics.BitmapFactory
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
    ): Result<Unit> {
        val vm = store.get(id) ?: return Result.failure(IllegalArgumentException("Unknown VM $id"))
        return try {
            var cfg = store.toConfig(vm)
            if (vm.arch == QemuEngine.Arch.ARM64) {
                cfg = cfg.copy(
                    cpuModel = profile.cpuModel,
                    tcgThread = profile.tcgThread,
                    rawVars = profile.rawVars,
                )
            }
            if (cfg.disk != null && !cfg.disk.exists()) engine.createDisk(cfg.disk, vm.diskGb, vm.id)
            val serial = vm.serialFile(this)
            if (clearSerial) serial.writeText("")
            engine.appendNote(serial, "[nimbus] starting profile ${profile.describe()}")
            startForeground(NOTIF_ID, notification(vm.name, "Starting…"))
            acquireWakeLock()
            engine.start(cfg, serial)
            currentId = id
            currentProfile = profile
            vm.lastUsedAt = System.currentTimeMillis()
            store.update(vm)
            startForeground(NOTIF_ID, notification(vm.name, "Running (${profile.id})"))
            startMonitor(id, serial, profile)
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

    fun isThisVmRunning(id: String): Boolean = currentId == id && engine.isRunning

    private fun startMonitor(id: String, serial: File, profile: BootProfile) {
        stopMonitor()
        val gen = monitorGen
        val startedAt = System.currentTimeMillis()
        val auto = BootSettings.mode(this) == BootSettings.MODE_AUTO
        val stderr = engine.qemuLogFile(id)
        val dumpFile = File(engine.workDir(id), "qmp-screendump.png")
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
                ticks++
                val now = System.currentTimeMillis()
                val elapsedSec = (now - startedAt) / 1000
                val snap = try {
                    engine.diagnosticSnapshot()
                } catch (e: Exception) {
                    "qmp-error: ${e.message}"
                }
                var extra = ""
                if (now - lastDumpAt >= 30_000L) {
                    lastDumpAt = now
                    extra = try {
                        engine.screendump(dumpFile)
                        val counted = countNonBlackPixels(dumpFile)
                        if (counted != null) {
                            val (nonBlack, total, w, h) = counted
                            if (nonBlack > 64) painted = true
                            " ramfb=${w}x${h} nonblack=$nonBlack/$total"
                        } else {
                            " ramfb=screendump-unreadable"
                        }
                    } catch (e: Exception) {
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
                        "[nimbus] kernel output seen at ${elapsedSec}s. The installer initrd is large (~68 MB); unpacking it on a phone can take many minutes. Serial should keep growing — if it stays quiet for 60s, auto-fallback will try the next profile.",
                    )
                    lastSerialLen = if (serial.exists()) serial.length() else lastSerialLen
                    kernelHinted = true
                }
                if (!auto) continue
                if (painted) continue
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
    private fun countNonBlackPixels(file: File): Quad? {
        if (!file.exists() || file.length() < 32) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        try {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return null
            val row = IntArray(w)
            var n = 0
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (c in row) {
                    if ((c and 0x00ffffff) != 0) n++
                }
            }
            return Quad(n, w * h, w, h)
        } finally {
            bmp.recycle()
        }
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
