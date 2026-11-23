package com.lex.nimbusvm

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Firebase Test Lab / real-device boot proof.
 *
 * Pass isoUrl (and optional isoSha256) via instrumentation args.
 * Default is the Debian arm64 gtk mini.iso (~233 MB), not Kali
 * (too large to bundle) and not the historical ~50 MB x86 mini.iso.
 */
@RunWith(AndroidJUnit4::class)
class BootProofTest {

    @Test(timeout = 45 * 60 * 1000)
    fun downloadAndBootInstaller() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("isoUrl") ?: DEFAULT_ISO
        val sha = args.getString("isoSha256") ?: DEFAULT_SHA
        val target = inst.targetContext
        BootSettings.setMode(target, BootSettings.MODE_AUTO)

        val outDirs = outputDirs(target)
        outDirs.forEach { it.mkdirs() }
        writeAll(outDirs, "ISO_URL.txt", "$url\nsha256=$sha\n")

        val intent = Intent(target, LibraryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(LibraryActivity.EXTRA_ISO_URL, url)
            .putExtra(LibraryActivity.EXTRA_ISO_SHA256, sha)
            .putExtra(LibraryActivity.EXTRA_AUTO_START, true)
        target.startActivity(intent)
        inst.uiAutomation.waitForIdle(1_000, 10_000)

        val device = UiDevice.getInstance(inst)
        device.waitForIdle()

        // On-device download of ~233 MB can take several minutes on a phone.
        val downloadDeadline = System.currentTimeMillis() + 20 * 60 * 1000L
        var shot = 0
        while (System.currentTimeMillis() < downloadDeadline) {
            shot++
            takeShot(inst, outDirs, shot, "setup")
            pullLogs(target, outDirs)
            if (qemuRunning(target)) break
            Thread.sleep(SHOT_EVERY_MS)
        }
        pullLogs(target, outDirs)
        assertTrue(
            "QEMU never started — download or VM create likely failed. See screenshots, serial.log, and qemu-stderr.log in the test output dir.",
            qemuRunning(target) || serialHas(target, "[nimbus self-test]"),
        )

        val bootDeadline = System.currentTimeMillis() + BOOT_WAIT_MS
        while (System.currentTimeMillis() < bootDeadline) {
            shot++
            takeShot(inst, outDirs, shot, "boot")
            pullLogs(target, outDirs)
            Thread.sleep(SHOT_EVERY_MS)
        }
        pullLogs(target, outDirs)
        writeSummary(target, outDirs)
    }

    private fun outputDirs(target: android.content.Context): List<File> {
        val dirs = mutableListOf<File>()
        val ftl = File("/sdcard/googletest/test_outputfiles")
        dirs += ftl
        target.getExternalFilesDir("ftl")?.let { dirs += it }
        return dirs
    }

    private fun takeShot(inst: android.app.Instrumentation, dirs: List<File>, n: Int, phase: String) {
        val bmp = try {
            inst.uiAutomation.takeScreenshot()
        } catch (_: Exception) {
            null
        } ?: return
        val name = "shot-%s-%02d.png".format(phase, n)
        for (dir in dirs) {
            try {
                dir.mkdirs()
                FileOutputStream(File(dir, name)).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } catch (_: Exception) {
            }
        }
        if (!bmp.isRecycled) bmp.recycle()
    }

    private fun workDirs(target: android.content.Context): List<File> {
        val root = File(target.filesDir, "vms")
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.filter { it.isDirectory } ?: emptyList()
    }

    private fun qemuRunning(target: android.content.Context): Boolean {
        return workDirs(target).any { File(it, "qmp.sock").exists() || File(it, "vnc.sock").exists() }
    }

    private fun serialHas(target: android.content.Context, needle: String): Boolean {
        return workDirs(target).any { dir ->
            val f = File(dir, "serial.log")
            f.exists() && f.readText().contains(needle)
        }
    }

    private fun pullLogs(target: android.content.Context, dirs: List<File>) {
        val names = listOf("serial.log", "qemu-stderr.log", "selftest.txt", "installer-extract.txt")
        workDirs(target).forEach { vm ->
            for (name in names) {
                val src = File(vm, name)
                if (!src.exists()) continue
                for (dir in dirs) {
                    try {
                        dir.mkdirs()
                        src.copyTo(File(dir, "${vm.name}-$name"), overwrite = true)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        File(target.filesDir, "selftest.txt").takeIf { it.exists() }?.let { src ->
            for (dir in dirs) {
                try {
                    src.copyTo(File(dir, "app-selftest.txt"), overwrite = true)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun writeSummary(target: android.content.Context, dirs: List<File>) {
        val serial = workDirs(target).mapNotNull { File(it, "serial.log").takeIf { f -> f.exists() }?.readText() }
            .joinToString("\n-----\n")
        val summary = buildString {
            appendLine("qemu_running=${qemuRunning(target)}")
            appendLine("banner=${BootSettings.hasBanner(serial)}")
            appendLine("kernel=${BootSettings.hasKernel(serial)}")
            appendLine("serial_bytes=${serial.length}")
            appendLine("recovery_mode=${BootSettings.mode(target)}")
            appendLine()
            appendLine(serial.takeLast(8000))
        }
        writeAll(dirs, "SUMMARY.txt", summary)
    }

    private fun writeAll(dirs: List<File>, name: String, text: String) {
        for (dir in dirs) {
            try {
                dir.mkdirs()
                File(dir, name).writeText(text)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_ISO =
            "https://deb.debian.org/debian/dists/stable/main/installer-arm64/current/images/netboot/gtk/mini.iso"
        const val DEFAULT_SHA =
            "391245851f4dc87f8d3d4323f80a5bf26fbff717de49139278a58f551dd6a600"
        const val BOOT_WAIT_MS = 10 * 60 * 1000L
        const val SHOT_EVERY_MS = 30_000L
    }
}
