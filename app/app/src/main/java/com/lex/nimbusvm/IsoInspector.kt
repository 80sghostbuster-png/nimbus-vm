package com.lex.nimbusvm

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

/**
 * Detect guest architecture from the ISO's EFI boot files
 * (BOOTAA64.EFI vs BOOTX64.EFI) and guess the OS from well-known markers.
 */
object IsoInspector {

    enum class OsKind { WINDOWS, UBUNTU, DEBIAN, KALI, LINUX, UNKNOWN }

    data class Profile(
        val arch: QemuEngine.Arch,
        val os: OsKind,
        val label: String,
        val hasBootAa64: Boolean,
        val hasBootX64: Boolean,
        val volumeId: String,
        val summary: String,
    )

    data class IsoEntry(val path: String, val lba: Int, val size: Int)

    data class Arm64Installer(
        val kernel: File,
        val initrd: File,
        val append: String,
        val graphical: Boolean,
    )

    fun inspect(iso: File): Profile {
        require(iso.exists() && iso.length() > 32_768) { "Not a usable ISO: ${iso.absolutePath}" }
        RandomAccessFile(iso, "r").use { raf ->
            val names = linkedSetOf<String>()
            val volume = readVolumeId(raf)
            walkIso9660(raf, names)
            var blobHits = emptySet<String>()
            val hasFromTreeAa64 = names.any { it.endsWith("BOOTAA64.EFI") }
            val hasFromTreeX64 = names.any { it.endsWith("BOOTX64.EFI") }
            val treeText = (names + volume).joinToString("\n").uppercase(Locale.US)
            val osHinted = listOf("DEBIAN", "UBUNTU", "KALI", "WINDOWS", "INSTALL.WIM", "INSTALL.ESD").any { it in treeText }
            if ((!hasFromTreeAa64 && !hasFromTreeX64) || !osHinted) {
                blobHits = scanMarkers(raf)
            }
            val hasAa64 = hasFromTreeAa64 || blobHits.contains("BOOTAA64.EFI")
            val hasX64 = hasFromTreeX64 || blobHits.contains("BOOTX64.EFI")
            val joined = (names + blobHits + listOf(volume)).joinToString("\n").uppercase(Locale.US)
            val os = when {
                "SOURCES/INSTALL.WIM" in joined || "SOURCES/INSTALL.ESD" in joined ||
                    "SOURCES/BOOT.WIM" in joined || "MICROSOFT WINDOWS" in joined -> OsKind.WINDOWS
                "KALI" in joined -> OsKind.KALI
                "UBUNTU" in joined -> OsKind.UBUNTU
                "DEBIAN" in joined -> OsKind.DEBIAN
                ".DISK/INFO" in names.joinToString("\n").uppercase(Locale.US) ||
                    "LINUX" in joined -> OsKind.LINUX
                else -> OsKind.UNKNOWN
            }
            val arch = when {
                hasAa64 && !hasX64 -> QemuEngine.Arch.ARM64
                hasX64 && !hasAa64 -> QemuEngine.Arch.X86_64
                hasAa64 && hasX64 -> QemuEngine.Arch.ARM64 // prefer native on this app
                else -> QemuEngine.Arch.ARM64
            }
            val prettyOs = when (os) {
                OsKind.WINDOWS -> if (arch == QemuEngine.Arch.ARM64) "Windows 11 ARM" else "Windows (x86-64, slow)"
                OsKind.UBUNTU -> "Ubuntu"
                OsKind.DEBIAN -> "Debian"
                OsKind.KALI -> "Kali Linux"
                OsKind.LINUX -> "Linux"
                OsKind.UNKNOWN -> iso.nameWithoutExtension
            }
            val archLabel = if (arch == QemuEngine.Arch.ARM64) "ARM64" else "x86-64"
            val how = buildString {
                if (hasAa64) append("BOOTAA64.EFI")
                if (hasAa64 && hasX64) append(" + ")
                if (hasX64) append("BOOTX64.EFI")
                if (!hasAa64 && !hasX64) append("no EFI boot file found; defaulting to ARM64")
            }
            val label = volume.ifBlank { prettyOs }
            val summary = "$prettyOs · $archLabel · $how"
            return Profile(arch, os, label, hasAa64, hasX64, volume, summary)
        }
    }


    /**
     * Extract a debian-installer kernel from an ARM64 ISO and return QEMU
     * -kernel/-initrd/-append inputs. Prefer the Graphical (gtk) initrd when
     * present. Returns null when the ISO has no install.a64 tree (live or
     * unknown layout) so the caller can fall back to firmware/GRUB.
     */
    data class InstallerExtract(
        val installer: Arm64Installer?,
        val log: String,
    )

    /** Never throws. Used on every ISO boot so a 0.2.0/0.2.1 VM is re-detected. */
    fun tryExtractArm64Installer(iso: File, destDir: File): InstallerExtract {
        return try {
            val inst = extractArm64Installer(iso, destDir)
            if (inst == null) {
                InstallerExtract(null, "No /install.a64 kernel on this ISO; booting firmware/GRUB.")
            } else {
                val kind = if (inst.graphical) "graphical (gtk initrd)" else "text (initrd)"
                InstallerExtract(
                    inst,
                    "Extracted ARM64 $kind from ${iso.name}: vmlinuz ${inst.kernel.length()} bytes, " +
                        "initrd ${inst.initrd.length()} bytes. append=${inst.append}",
                )
            }
        } catch (e: Exception) {
            InstallerExtract(null, "Installer extract failed (${e.message}); booting firmware/GRUB.")
        }
    }

        fun extractArm64Installer(iso: File, destDir: File): Arm64Installer? {
        if (!iso.exists() || iso.length() < 32_768) return null
        return RandomAccessFile(iso, "r").use { raf ->
            val files = linkedMapOf<String, IsoEntry>()
            indexIso(raf, files)
            if (files.isEmpty()) return@use null
            val kernelEnt = findEntry(files, "INSTALL.A64/VMLINUZ") ?: return@use null
            val gtkEnt = findEntry(files, "INSTALL.A64/GTK/INITRD.GZ")
            val textEnt = findEntry(files, "INSTALL.A64/INITRD.GZ")
            val initrdEnt = gtkEnt ?: textEnt ?: return@use null
            val graphical = gtkEnt != null
            destDir.mkdirs()
            val kernelOut = File(destDir, "vmlinuz")
            val initrdOut = File(destDir, "initrd.gz")
            val stamp = File(destDir, "installer.stamp")
            val token = listOf(
                iso.canonicalPath, iso.length().toString(), iso.lastModified().toString(),
                if (graphical) "gtk" else "text",
                kernelEnt.size.toString(), initrdEnt.size.toString(),
            ).joinToString("|")
            val reuse = stamp.isFile && stamp.readText() == token &&
                kernelOut.isFile && kernelOut.length() == kernelEnt.size.toLong() &&
                initrdOut.isFile && initrdOut.length() == initrdEnt.size.toLong()
            if (!reuse) {
                extractEntry(raf, kernelEnt, kernelOut)
                extractEntry(raf, initrdEnt, initrdOut)
                stamp.writeText(token)
            }
            val grub = findEntry(files, "BOOT/GRUB/GRUB.CFG") ?: findEntry(files, "BOOT/GRUB/GRUB.CFG")
            val grubText = if (grub != null) extractToString(raf, grub) else ""
            val rawAppend = parseLinuxAppend(grubText, if (graphical) "/install.a64/gtk/initrd.gz" else "/install.a64/initrd.gz")
                ?: "console=tty0"
            Arm64Installer(kernelOut, initrdOut, sanitizeInstallerAppend(rawAppend), graphical)
        }
    }

    private fun findEntry(files: Map<String, IsoEntry>, path: String): IsoEntry? {
        val want = path.trim('/').uppercase(Locale.US).trimEnd('.')
        return files[want]
            ?: files["$want."]
            ?: files.values.firstOrNull { it.path.uppercase(Locale.US).trimEnd('.') == want }
    }

    private fun indexIso(raf: RandomAccessFile, files: MutableMap<String, IsoEntry>) {
        val pvd = ByteArray(2048)
        raf.seek(16L * 2048)
        if (raf.read(pvd) < 2048) return
        if (pvd[0] != 1.toByte()) return
        val root = pvd.copyOfRange(156, 190)
        walkDir(raf, extent(root), size(root), "", linkedSetOf(), 0, visited = mutableSetOf(), files = files)
        try {
            for (s in 17..31) {
                raf.seek(s * 2048L)
                if (raf.read(pvd) < 2048) break
                if (pvd[0] == 255.toByte()) break
                if (pvd[0] == 2.toByte() && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") {
                    val jroot = pvd.copyOfRange(156, 190)
                    walkDir(raf, extent(jroot), size(jroot), "", linkedSetOf(), 0, joliet = true, visited = mutableSetOf(), files = files)
                    break
                }
            }
        } catch (_: Exception) {
            // Keep the ISO 9660 names already collected (including VMLINUZ.).
        }
    }

    private fun extractEntry(raf: RandomAccessFile, ent: IsoEntry, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        FileOutputStream(tmp).use { out ->
            var left = ent.size.toLong()
            var pos = ent.lba.toLong() * 2048L
            val buf = ByteArray(1024 * 1024)
            while (left > 0) {
                raf.seek(pos)
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) throw IllegalStateException("ISO read failed at $pos for ${ent.path}")
                out.write(buf, 0, n)
                left -= n
                pos += n
            }
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        if (dest.length() != ent.size.toLong()) {
            dest.delete()
            throw IllegalStateException("ISO extract size mismatch for ${ent.path}")
        }
    }

    private fun extractToString(raf: RandomAccessFile, ent: IsoEntry, limit: Int = 256 * 1024): String {
        if (ent.size <= 0) return ""
        val n = minOf(ent.size, limit)
        val buf = ByteArray(n)
        raf.seek(ent.lba.toLong() * 2048L)
        val got = raf.read(buf)
        if (got <= 0) return ""
        return String(buf, 0, got, Charsets.UTF_8).trimEnd('\u0000')
    }

    internal fun parseLinuxAppend(grubCfg: String, initrdPath: String): String? {
        if (grubCfg.isBlank()) return null
        val want = initrdPath.trim().lowercase(Locale.US)
        var pending: String? = null
        var matched: String? = null
        var matchedGraphical = false
        var inGraphical = false
        for (raw in grubCfg.lines()) {
            val line = raw.trim()
            val low = line.lowercase(Locale.US)
            if (low.startsWith("menuentry")) {
                inGraphical = "graphical" in low
            }
            if (low.startsWith("linux ") || low.startsWith("linux\t")) {
                val rest = line.substring(5).trim()
                val sp = rest.indexOf(' ')
                pending = if (sp < 0) "" else rest.substring(sp + 1).trim()
            }
            if (low.startsWith("initrd ") || low.startsWith("initrd\t")) {
                val path = line.substring(6).trim().substringBefore(' ').lowercase(Locale.US)
                if (path == want || path.endsWith(want) || want.endsWith(path.trimStart('/'))) {
                    if (matched == null || (inGraphical && !matchedGraphical)) {
                        matched = pending ?: ""
                        matchedGraphical = inGraphical
                    }
                }
            }
        }
        return matched
    }

    internal fun sanitizeInstallerAppend(raw: String): String {
        val parts = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = mutableListOf<String>()
        for (p in parts) {
            val low = p.lowercase(Locale.US)
            when {
                low == "quiet" || low.startsWith("quiet=") -> {}
                low.startsWith("console=") || low.startsWith("earlycon=") -> {}
                low == "acpi=off" || low.startsWith("acpi=") -> {}
                else -> kept.add(p)
            }
        }
        // earlycon writes kernel progress to Serial, then disables itself
        // when tty0/efifb comes up. Do not add console=ttyAMA0: that makes
        // debian-installer disable the graphical frontend.
        val insertAt = kept.indexOf("---").let { if (it < 0) kept.size else it }
        kept.add(insertAt, "acpi=off")
        kept.add(insertAt + 1, "earlycon=pl011,0x9000000")
        kept.add(insertAt + 2, "console=tty0")
        return kept.joinToString(" ")
    }


    private fun readVolumeId(raf: RandomAccessFile): String {
        val buf = ByteArray(2048)
        raf.seek(16L * 2048)
        if (raf.read(buf) < 2048) return ""
        if (buf[0] != 1.toByte() || String(buf, 1, 5, Charsets.US_ASCII) != "CD001") return ""
        return String(buf, 40, 32, Charsets.US_ASCII).trim()
    }

    private fun walkIso9660(raf: RandomAccessFile, out: MutableSet<String>) {
        val pvd = ByteArray(2048)
        raf.seek(16L * 2048)
        if (raf.read(pvd) < 2048) return
        if (pvd[0] != 1.toByte()) return
        val root = pvd.copyOfRange(156, 190)
        walkDir(raf, extent(root), size(root), "", out, 0, visited = mutableSetOf())
        // Joliet supplementary volume descriptor, if any
        for (s in 17..31) {
            raf.seek(s * 2048L)
            if (raf.read(pvd) < 2048) break
            if (pvd[0] == 255.toByte()) break
            if (pvd[0] == 2.toByte() && String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") {
                val jroot = pvd.copyOfRange(156, 190)
                walkDir(raf, extent(jroot), size(jroot), "", out, 0, joliet = true, visited = mutableSetOf())
                break
            }
        }
    }

    private fun walkDir(
        raf: RandomAccessFile,
        lba: Int,
        size: Int,
        prefix: String,
        out: MutableSet<String>,
        depth: Int,
        joliet: Boolean = false,
        visited: MutableSet<Int>,
        files: MutableMap<String, IsoEntry>? = null,
    ) {
        if (depth > 8 || lba <= 0 || size <= 0 || size > 8 * 1024 * 1024) return
        if (!visited.add(lba)) return
        val data = ByteArray(size)
        raf.seek(lba * 2048L)
        val n = raf.read(data)
        if (n <= 0) return
        var off = 0
        while (off + 33 < n) {
            val recLen = data[off].toInt() and 0xff
            if (recLen == 0) {
                off = ((off / 2048) + 1) * 2048
                continue
            }
            if (off + recLen > n) break
            val rec = data.copyOfRange(off, off + recLen)
            off += recLen
            val nameLen = rec[32].toInt() and 0xff
            if (nameLen <= 0 || 33 + nameLen > rec.size) continue
            val flags = rec[25].toInt() and 0xff
            val rawName = try {
                if (joliet) {
                    String(rec, 33, nameLen, Charset.forName("UTF-16BE")).trim()
                } else {
                    String(rec, 33, nameLen, Charsets.US_ASCII).trim()
                }
            } catch (_: Exception) {
                continue
            }
            if (nameLen == 1 && (rec[33] == 0.toByte() || rec[33] == 1.toByte())) continue
            val name = rawName.substringBefore(';').trim().uppercase(Locale.US).trimEnd('.')
            if (name.isEmpty() || name == "." || name == "..") continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            out.add(path)
            if (flags and 2 == 0) {
                files?.put(path, IsoEntry(path, extent(rec), size(rec)))
            }
            if (flags and 2 != 0 && name != "SYSTEM RESOURCE") {
                walkDir(raf, extent(rec), size(rec), path, out, depth + 1, joliet, visited, files)
            }
        }
    }

    private fun extent(rec: ByteArray): Int =
        ByteBuffer.wrap(rec, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun size(rec: ByteArray): Int =
        ByteBuffer.wrap(rec, 10, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun scanMarkers(raf: RandomAccessFile): Set<String> {
        val needles = listOf(
            "BOOTAA64.EFI", "BOOTX64.EFI", "bootaa64.efi", "bootx64.efi",
            "sources/install.wim", "SOURCES/INSTALL.WIM",
            "sources/install.esd", "SOURCES/INSTALL.ESD",
            "sources/boot.wim", "Kali Linux", "Ubuntu", "Debian GNU/Linux",
        )
        val found = mutableSetOf<String>()
        val window = 1024 * 1024
        val buf = ByteArray(window)
        var pos = 0L
        val length = raf.length()
        raf.seek(0)
        while (pos < length) {
            val n = raf.read(buf)
            if (n <= 0) break
            val chunk = buf.copyOf(n)
            val ascii = String(chunk, Charsets.ISO_8859_1)
            for (n1 in needles) {
                if (ascii.contains(n1, ignoreCase = true)) {
                    found.add(n1.uppercase(Locale.US))
                }
            }
            // UTF-16LE versions of the EFI names
            val utf16 = String(chunk, Charsets.UTF_16LE)
            if (utf16.contains("BOOTAA64.EFI", ignoreCase = true)) found.add("BOOTAA64.EFI")
            if (utf16.contains("BOOTX64.EFI", ignoreCase = true)) found.add("BOOTX64.EFI")
            pos += n
            // overlap so markers on a boundary are not missed
            if (pos < length) {
                pos -= 64
                raf.seek(pos)
            }
        }
        return found
    }
}
