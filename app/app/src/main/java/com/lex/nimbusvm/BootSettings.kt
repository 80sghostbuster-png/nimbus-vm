package com.lex.nimbusvm

import android.content.Context

/** One QEMU argv flavour used to work around a post-UEFI / mid-kernel stall. */
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
 * Hidden installer-recovery setting.
 *
 * The v0.2.5-test default (`-cpu max,pauth-impdef=on` + `thread=multi`)
 * started the kernel on Lex's Pixel 6 Pro but froze mid-line at
 * `OF: reserved mem: Res`. Auto-fallback now keys on a *progress stall*
 * (no new Serial bytes for [STALL_TIMEOUT_MS]), not on first kernel output.
 * The safer default for installer boots is cortex-a72 + thread=single.
 */
object BootSettings {
    const val PREFS = "nimbus_prefs"
    const val KEY_MODE = "boot_recovery"
    const val MODE_AUTO = "auto"
    const val MODE_DEFAULT = "default"
    const val MODE_MAX_MULTI = "max_multi"
    const val MODE_MAX_SINGLE = "max_single"
    const val MODE_A72_SINGLE_RAW = "a72_single_raw"

    val defaultProfile = BootProfile(
        id = "default",
        label = "Default (cortex-a72 + thread=single, qcow2 VARS)",
        cpuModel = "cortex-a72",
        tcgThread = "single",
        rawVars = false,
    )
    val maxSingle = BootProfile(
        id = "max_single",
        label = "max + pauth-impdef + thread=single",
        cpuModel = "max,pauth-impdef=on",
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
    val maxMulti = BootProfile(
        id = "max_multi",
        label = "Legacy 0.2.5 (max + thread=multi) — hangs on Pixel 6 Pro",
        cpuModel = "max,pauth-impdef=on",
        tcgThread = "multi",
        rawVars = false,
    )

    /** Auto-fallback order. Legacy max+multi is opt-in only, not in this list. */
    val profiles: List<BootProfile> = listOf(defaultProfile, maxSingle, a72SingleRaw)

    val modeEntries: List<Pair<String, String>> = listOf(
        MODE_AUTO to "Auto-fallback if Serial stops growing",
        MODE_DEFAULT to defaultProfile.label,
        MODE_MAX_SINGLE to maxSingle.label,
        MODE_A72_SINGLE_RAW to a72SingleRaw.label,
        MODE_MAX_MULTI to maxMulti.label,
    )

    /** No new Serial bytes for this long → treat as a stall and (if Auto) fall back. */
    const val STALL_TIMEOUT_MS = 60_000L

    fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): String = prefs(ctx).getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO

    fun setMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply()
    }

    fun initialProfile(ctx: Context): BootProfile = when (mode(ctx)) {
        MODE_MAX_SINGLE -> maxSingle
        MODE_A72_SINGLE_RAW -> a72SingleRaw
        MODE_MAX_MULTI -> maxMulti
        else -> defaultProfile // auto and default start here
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
        return serial.contains("Booting Linux") ||
            serial.contains("Linux version ") ||
            serial.contains("earlycon: pl11") ||
            serial.contains("Machine model: linux,dummy-virt")
    }
}
