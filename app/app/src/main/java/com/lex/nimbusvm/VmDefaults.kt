package com.lex.nimbusvm

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Sensible first-run defaults: half of device RAM, big cores, sparse disk, TPM+SB for Windows. */
object VmDefaults {

    fun ramMb(context: Context, windows: Boolean): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = (mi.totalMem / (1024 * 1024)).toInt()
        val half = total / 2
        val headroom = (total - 1536).coerceAtLeast(512)
        val cap = if (windows) 8192 else 6144
        val floor = if (windows) minOf(4096, headroom) else 1024
        return half.coerceAtMost(headroom).coerceAtMost(cap).coerceAtLeast(floor)
    }

    fun cpus(): Int = bigCoreCount()

    fun diskGb(windows: Boolean): Int = if (windows) 64 else 32

    fun tpm(windows: Boolean): Boolean = windows
    fun secureBoot(windows: Boolean): Boolean = windows

    fun bigCoreCount(): Int {
        val base = File("/sys/devices/system/cpu")
        val caps = mutableListOf<Int>()
        var i = 0
        while (File(base, "cpu$i").exists()) {
            val capFile = File(base, "cpu$i/cpu_capacity")
            val freqFile = File(base, "cpu$i/cpufreq/cpuinfo_max_freq")
            val v = when {
                capFile.exists() -> capFile.readText().trim().toIntOrNull()
                freqFile.exists() -> freqFile.readText().trim().toIntOrNull()
                else -> null
            }
            if (v != null) caps.add(v)
            i++
        }
        if (caps.isEmpty()) {
            return Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        }
        val max = caps.maxOrNull() ?: return 2
        val big = caps.count { it * 10 >= max * 6 }
        return big.coerceIn(2, 8)
    }

    fun displayName(profile: IsoInspector.Profile, fileName: String): String {
        val base = when (profile.os) {
            IsoInspector.OsKind.WINDOWS ->
                if (profile.arch == QemuEngine.Arch.ARM64) "Windows 11 ARM" else "Windows x86-64"
            IsoInspector.OsKind.UBUNTU -> "Ubuntu"
            IsoInspector.OsKind.DEBIAN -> "Debian"
            IsoInspector.OsKind.KALI -> "Kali"
            IsoInspector.OsKind.LINUX -> profile.label.ifBlank { "Linux" }
            IsoInspector.OsKind.UNKNOWN -> profile.label.ifBlank { fileName.substringBeforeLast('.') }
        }
        val arch = if (profile.arch == QemuEngine.Arch.ARM64) "ARM64" else "x86-64"
        return "$base ($arch)"
    }
}
