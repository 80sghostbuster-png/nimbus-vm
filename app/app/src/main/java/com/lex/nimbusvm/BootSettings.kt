package com.lex.nimbusvm

import android.content.Context

/** One QEMU argv flavour used to work around a post-UEFI-banner stall. */
data class BootProfile(
    val id: String,
    val label: String,
    val cpuModel: String,
    val tcgThread: String,
    val rawVars: Boolean,
) {
    fun describe(): String =
        "$id cpu=$cpuModel thread=$tcgThread vars=${if (rawVars) "raw" else "qcow2"}"
}

/**
 * Hidden installer-recovery setting. Default is auto-fallback: if the
 * Serial log never shows a kernel start after the AAVMF banner, restart
 * QEMU with the next candidate profile.
 */
object BootSettings {
    const val PREFS = "nimbus_prefs"
    const val KEY_MODE = "boot_recovery"
    const val MODE_AUTO = "auto"
    const val MODE_DEFAULT = "default"
    const val MODE_A72_SINGLE = "a72_single"
    const val MODE_A72_SINGLE_RAW = "a72_single_raw"

    val defaultProfile = BootProfile(
        id = "default",
        label = "Default (max + thread=multi, qcow2 VARS)",
        cpuModel = "max,pauth-impdef=on",
        tcgThread = "multi",
        rawVars = false,
    )
    val a72Single = BootProfile(
        id = "a72_single",
        label = "cortex-a72 + thread=single",
        cpuModel = "cortex-a72",
        tcgThread = "single",
        rawVars = false,
    )
    val a72SingleRaw = BootProfile(
        id = "a72_single_raw",
        label = "cortex-a72 + thread=single + raw VARS",
        cpuModel = "cortex-a72",
        tcgThread = "single",
        rawVars = true,
    )

    val profiles: List<BootProfile> = listOf(defaultProfile, a72Single, a72SingleRaw)

    val modeEntries: List<Pair<String, String>> = listOf(
        MODE_AUTO to "Auto-fallback if the kernel never starts",
        MODE_DEFAULT to defaultProfile.label,
        MODE_A72_SINGLE to a72Single.label,
        MODE_A72_SINGLE_RAW to a72SingleRaw.label,
    )

    /** After the UEFI banner, wait this long for “Booting Linux” before a fallback. */
    const val KERNEL_TIMEOUT_MS = 120_000L
    /** After start, wait this long for any UEFI banner before treating it as a stall. */
    const val BANNER_TIMEOUT_MS = 90_000L

    fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = prefs(ctx).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

    fun initialProfile(ctx: Context): BootProfile = when (mode(ctx)) {
        MODE_A72_SINGLE -> a72Single
        MODE_A72_SINGLE_RAW -> a72SingleRaw
        else -> defaultProfile
    }

    fun nextAutoProfile(current: BootProfile): BootProfile? {
        val i = profiles.indexOfFirst { it.id == current.id }
        return profiles.getOrNull(i + 1)
    }

    fun hasBanner(serial: String): Boolean {
        return serial.contains("UEFI firmware", ignoreCase = true) ||
            serial.contains("EDK II", ignoreCase = true) ||
            serial.contains("Tianocore", ignoreCase = true)
    }

    fun hasKernel(serial: String): Boolean {
        // Do not match "earlycon" — that word appears in our own notes and argv.
        return serial.contains("Booting Linux") ||
            serial.contains("Linux version ")
    }
}
