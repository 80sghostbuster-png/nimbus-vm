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
import androidx.core.app.NotificationCompat
import java.io.File

/** Owns the running QEMU process so it survives DisplayActivity recreation. */
class VmService : Service() {
    inner class LocalBinder : Binder() { fun service(): VmService = this@VmService }

    lateinit var engine: QemuEngine
        private set
    lateinit var store: VmStore
        private set
    var currentId: String? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null

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

    fun startVm(id: String): Result<Unit> {
        val vm = store.get(id) ?: return Result.failure(IllegalArgumentException("Unknown VM $id"))
        return try {
            val cfg = store.toConfig(vm)
            if (cfg.disk != null && !cfg.disk.exists()) engine.createDisk(cfg.disk, vm.diskGb, vm.id)
            val serial = vm.serialFile(this)
            serial.writeText("")
            startForeground(NOTIF_ID, notification(vm.name, "Starting…"))
            acquireWakeLock()
            engine.start(cfg, serial)
            currentId = id
            vm.lastUsedAt = System.currentTimeMillis()
            store.update(vm)
            startForeground(NOTIF_ID, notification(vm.name, "Running"))
            Result.success(Unit)
        } catch (e: Exception) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            Result.failure(e)
        }
    }

    fun stopVm() {
        engine.stop()
        currentId = null
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isThisVmRunning(id: String): Boolean = currentId == id && engine.isRunning

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
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

        fun start(context: Context, vmId: String) {
            val i = Intent(context, VmService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VM_ID, vmId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
