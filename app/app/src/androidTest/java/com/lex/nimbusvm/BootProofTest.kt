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

    @Test(timeout = 14 * 60 * 1000)
    fun downloadAndBootInstaller() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val pushed = args.getString("isoPushed")
        val url = if (pushed != null) {
            "file://" + copyPushedIso(inst, pushed).absolutePath
        } else {
            args.getString("isoUrl") ?: DEFAULT_ISO
        }
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

        // Short real-device runs: download + extract must finish in ~6 min.
        val testStart = System.currentTimeMillis()
        val downloadDeadline = testStart + (args.getString("setupMin")?.toLongOrNull() ?: 6L) * 60 * 1000L
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

        val bootMin = args.getString("bootMin")?.toLongOrNull() ?: 0L
        val bootDeadline = if (bootMin > 0) System.currentTimeMillis() + bootMin * 60 * 1000L
            else testStart + 13 * 60 * 1000L
        while (System.currentTimeMillis() < bootDeadline) {
            shot++
            takeShot(inst, outDirs, shot, "boot")
            pullLogs(target, outDirs)
            keepScreendump(target, outDirs, shot)
            Thread.sleep(SHOT_EVERY_MS)
        }
        pullLogs(target, outDirs)
        writeSummary(target, outDirs)
    }

    /**
     * Test Lab pushes --other-files as the shell user, so the app can't open
     * them. Stream the file through a shell `cat` into the app's own dir.
     */
    private fun copyPushedIso(inst: android.app.Instrumentation, path: String): File {
        val dir = inst.targetContext.getExternalFilesDir("iso") ?: inst.targetContext.filesDir
        dir.mkdirs()
        val out = File(dir, File(path).name)
        val pfd = inst.uiAutomation.executeShellCommand("cat $path")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { inp ->
            FileOutputStream(out).use { o -> inp.copyTo(o, 1 shl 20) }
        }
        return out
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
        val names = listOf("serial.log", "qemu-stderr.log", "selftest.txt", "installer-extract.txt", "qmp-screendump.png")
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

    /** Keep QEMU's own view of the guest framebuffer next to each app screenshot. */
    private fun keepScreendump(target: android.content.Context, dirs: List<File>, n: Int) {
        workDirs(target).forEach { vm ->
            val src = File(vm, "qmp-screendump.png")
            if (!src.exists()) return@forEach
            for (dir in dirs) {
                try {
                    src.copyTo(File(dir, "qmp-%02d.png".format(n)), overwrite = true)
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
        const val SHOT_EVERY_MS = 20_000L
    }
}
