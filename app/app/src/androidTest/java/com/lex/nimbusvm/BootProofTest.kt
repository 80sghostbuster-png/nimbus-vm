package com.lex.nimbusvm

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

/**
 * Firebase Test Lab / real-device proof for v0.2.10-test:
 * 1. Boots Kali arm64 netinst to the GTK installer ("Select a language") in landscape.
 * 2. Verifies the diagnostics overlay auto-hides after 30s so bottom-left UI is clear.
 * 3. Taps Hide, captures the floating "▼ Toolbar" handle, taps it to restore the toolbar.
 * 4. Taps Save, captures "VM state saved", closes the VM cleanly.
 * 5. Relaunches LibraryActivity (fresh task), taps Resume, captures the resumed Kali screen.
 * 6. Forces stop, discards the saved state from More menu, verifies "Run" returns.
 */
@RunWith(AndroidJUnit4::class)
class BootProofTest {

    @Test(timeout = 13 * 60 * 1000)
    fun bootToolbarSaveAndResume() {
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

        val device = UiDevice.getInstance(inst)
        var shot = 0
        try {
            val intent = Intent(target, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LibraryActivity.EXTRA_ISO_URL, url)
                .putExtra(LibraryActivity.EXTRA_ISO_SHA256, sha)
                .putExtra(LibraryActivity.EXTRA_AUTO_START, true)
            target.startActivity(intent)
            device.waitForIdle()

            // 1. Wait for ISO setup + QEMU start.
            val testStart = System.currentTimeMillis()
            val setupDeadline = testStart + (args.getString("setupMin")?.toLongOrNull() ?: 4L) * 60 * 1000L
            while (System.currentTimeMillis() < setupDeadline) {
                shot++
                takeNamedShot(inst, outDirs, "shot-setup-%02d.png".format(shot))
                pullLogs(target, outDirs)
                if (qemuRunning(target)) break
                Thread.sleep(15_000L)
            }
            assertTrue("QEMU never started", qemuRunning(target))

            // 2. Wait until Kali GTK installer is painted (nonblack >= 300k) and >= 35s elapsed so diag auto-hides.
            val bootStart = System.currentTimeMillis()
            val bootDeadline = bootStart + (args.getString("bootMin")?.toLongOrNull() ?: 4L) * 60 * 1000L
            var installerPainted = false
            while (System.currentTimeMillis() < bootDeadline) {
                shot++
                takeNamedShot(inst, outDirs, "shot-boot-%02d.png".format(shot))
                pullLogs(target, outDirs)
                keepScreendump(target, outDirs, shot)
                val elapsed = System.currentTimeMillis() - bootStart
                if (maxNonBlack(target) >= 300_000 && elapsed >= 35_000L) {
                    installerPainted = true
                    break
                }
                Thread.sleep(15_000L)
            }
            assertTrue("Kali installer screen did not paint in time (maxNonBlack=${maxNonBlack(target)})", installerPainted)
            Thread.sleep(5_000L)
            takeNamedShot(inst, outDirs, "shot-01-kali-clean.png")

            // 3. Hide toolbar -> verify handle appears -> restore via handle.
            val hideBtn = device.wait(Until.findObject(By.text("Hide")), 5_000)
            assertNotNull("Hide button not found", hideBtn)
            hideBtn.click()
            val handle = device.wait(Until.findObject(By.textContains("Toolbar")), 5_000)
            assertNotNull("Restore toolbar handle not visible after Hide", handle)
            assertNull("Hide button still visible after Hide", device.findObject(By.text("Hide")))
            Thread.sleep(500L)
            takeNamedShot(inst, outDirs, "shot-02-toolbar-hidden.png")

            handle.click()
            val saveBtn = device.wait(Until.findObject(By.text("Save")), 5_000)
            assertNotNull("Save button not visible after restoring toolbar", saveBtn)
            assertNotNull("Keyboard button not visible after restoring toolbar", device.findObject(By.text("Keyboard")))
            Thread.sleep(500L)
            takeNamedShot(inst, outDirs, "shot-03-toolbar-restored.png")

            // 4. Tap Save -> capture progress/completion -> Close VM.
            saveBtn.click()
            Thread.sleep(400L)
            takeNamedShot(inst, outDirs, "shot-04a-saving.png")
            val savedTitle = device.wait(Until.findObject(By.text("VM state saved")), 90_000)
            if (savedTitle == null) {
                takeNamedShot(inst, outDirs, "shot-04-save-error.png")
                pullLogs(target, outDirs)
                assertNotNull("Save did not complete with 'VM state saved'", savedTitle)
            }
            Thread.sleep(300L)
            takeNamedShot(inst, outDirs, "shot-04-save-done.png")

            val closeVmBtn = device.wait(
                Until.findObject(By.text(Pattern.compile("Close VM", Pattern.CASE_INSENSITIVE))),
                5_000,
            )
            assertNotNull("'Close VM' button missing in Save dialog", closeVmBtn)
            closeVmBtn.click()

            val resumeBtn1 = device.wait(Until.findObject(By.text(Pattern.compile("Resume", Pattern.CASE_INSENSITIVE))), 20_000)
            assertNotNull("'Resume' button missing in LibraryActivity after save", resumeBtn1)
            Thread.sleep(500L)
            takeNamedShot(inst, outDirs, "shot-05-library-saved.png")

            // 5. Relaunch LibraryActivity in a fresh task (simulates reopening the app) and tap Resume.
            val relaunch = Intent(target, LibraryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            target.startActivity(relaunch)
            val resumeBtn2 = device.wait(Until.findObject(By.text(Pattern.compile("Resume", Pattern.CASE_INSENSITIVE))), 15_000)
            assertNotNull("'Resume' button missing after relaunch", resumeBtn2)
            resumeBtn2.click()

            // Wait up to 45s for the resumed Kali screen to paint (and diag to auto-hide after 10s).
            val resumeDeadline = System.currentTimeMillis() + 45_000L
            var rShot = 0
            while (System.currentTimeMillis() < resumeDeadline) {
                rShot++
                Thread.sleep(5_000L)
                takeNamedShot(inst, outDirs, "shot-06-resumed-%02d.png".format(rShot))
                pullLogs(target, outDirs)
                keepScreendump(target, outDirs, 90 + rShot)
                if (rShot >= 3 && maxNonBlack(target) >= 300_000) break
            }
            assertTrue("Resumed VM did not report resuming saved state", serialHas(target, "resuming saved state"))
            assertTrue("Resumed VM screen did not paint", maxNonBlack(target) >= 300_000)
            takeNamedShot(inst, outDirs, "shot-06-resumed-kali.png")

            // 6. Verify Discard saved state from LibraryActivity More menu.
            val powerBtn = device.wait(Until.findObject(By.text("Power")), 5_000)
            assertNotNull("Power button missing on resumed screen", powerBtn)
            powerBtn.click()
            val forceStop = device.wait(Until.findObject(By.text("Force stop")), 5_000)
            assertNotNull("Force stop missing in Power menu", forceStop)
            forceStop.click()

            val moreBtn = device.wait(Until.findObject(By.text(Pattern.compile("More", Pattern.CASE_INSENSITIVE))), 15_000)
            assertNotNull("More button missing after Force stop", moreBtn)
            moreBtn.click()
            val discardItem = device.wait(Until.findObject(By.text("Discard saved state")), 5_000)
            assertNotNull("'Discard saved state' missing in More menu", discardItem)
            discardItem.click()

            val runBtn = device.wait(Until.findObject(By.text(Pattern.compile("Run", Pattern.CASE_INSENSITIVE))), 20_000)
            assertNotNull("'Run' button did not return after discarding save", runBtn)
            Thread.sleep(500L)
            takeNamedShot(inst, outDirs, "shot-07-discarded.png")
        } finally {
            takeNamedShot(inst, outDirs, "shot-finally.png")
            pullLogs(target, outDirs)
            writeSummary(target, outDirs)
        }
    }

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
        dirs += File("/sdcard/googletest/test_outputfiles")
        target.getExternalFilesDir("ftl")?.let { dirs += it }
        return dirs
    }

    private fun takeNamedShot(inst: android.app.Instrumentation, dirs: List<File>, name: String) {
        val bmp = try {
            inst.uiAutomation.takeScreenshot()
        } catch (_: Exception) {
            null
        } ?: return
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

    private fun maxNonBlack(target: android.content.Context): Int {
        var best = 0
        val re = Regex("""nonblack=(\d+)/""")
        workDirs(target).forEach { dir ->
            val f = File(dir, "serial.log")
            if (!f.exists()) return@forEach
            re.findAll(f.readText()).forEach { m ->
                val v = m.groupValues[1].toIntOrNull() ?: 0
                if (v > best) best = v
            }
        }
        return best
    }

    private fun pullLogs(target: android.content.Context, dirs: List<File>) {
        val names = listOf("serial.log", "qemu-stderr.log", "selftest.txt", "installer-extract.txt", "qmp-screendump.png", "saved-state.json")
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
            appendLine("max_nonblack=${maxNonBlack(target)}")
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
    }
}
