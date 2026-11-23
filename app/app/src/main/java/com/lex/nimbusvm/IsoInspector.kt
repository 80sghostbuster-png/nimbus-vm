package com.lex.nimbusvm

import java.io.File
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
            val rawName = if (joliet) {
                String(rec, 33, nameLen, Charset.forName("UTF-16BE")).trim()
            } else {
                String(rec, 33, nameLen, Charsets.US_ASCII).trim()
            }
            if (nameLen == 1 && (rec[33] == 0.toByte() || rec[33] == 1.toByte())) continue
            val name = rawName.substringBefore(';').trim().uppercase(Locale.US)
            if (name.isEmpty() || name == "." || name == "..") continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            out.add(path)
            if (flags and 2 != 0 && name != "SYSTEM RESOURCE") {
                walkDir(raf, extent(rec), size(rec), path, out, depth + 1, joliet, visited)
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
