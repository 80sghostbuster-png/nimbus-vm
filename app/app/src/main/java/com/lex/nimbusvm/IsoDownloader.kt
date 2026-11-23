package com.lex.nimbusvm

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Download an official ISO: fetch SHA256SUMS, resume via HTTP Range, verify the digest. */
class IsoDownloader {

    data class Plan(
        val preset: DistroPreset,
        val fileName: String,
        val sha256: String,
        val url: String,
    )

    fun resolve(preset: DistroPreset): Plan {
        val body = URL(preset.sumsUrl).openStream().bufferedReader().readText()
        val match = body.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = parts[1].removePrefix("*").trim()
            if (preset.isoPattern.matches(name)) Plan(preset, name, parts[0].lowercase(), preset.dirUrl + name) else null
        }.firstOrNull() ?: error("No matching ISO in ${preset.sumsUrl}")
        return match
    }

    /**
     * Resume-capable download into [dest]. [onProgress] is called with (copied, total or -1).
     * Existing bytes are kept and a Range request is issued when the server allows it.
     */
    fun download(plan: Plan, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".partial")
        if (dest.exists() && dest.length() > 0 && sha256(dest) == plan.sha256) {
            onProgress(dest.length(), dest.length())
            return
        }
        var existing = if (part.exists()) part.length() else 0L
        val conn = (URL(plan.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 200) {
            // Server ignored Range; start over.
            existing = 0
            if (part.exists()) part.delete()
        } else if (code != 206 && !(code == 200 && existing == 0L)) {
            if (existing > 0 && code != 206) {
                // Fall back to a full GET
                conn.disconnect()
                existing = 0
                part.delete()
                return downloadFresh(plan, part, dest, onProgress)
            }
            error("Download failed HTTP $code for ${plan.url}")
        }
        val total = when {
            code == 206 -> existing + conn.getHeaderFieldLong("Content-Length", -1)
            else -> conn.getHeaderFieldLong("Content-Length", -1)
        }
        conn.inputStream.use { inp ->
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(existing)
                val buf = ByteArray(256 * 1024)
                var copied = existing
                var last = copied
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    copied += n
                    if (copied - last > 1024 * 1024) {
                        onProgress(copied, total)
                        last = copied
                    }
                }
                onProgress(copied, total)
            }
        }
        conn.disconnect()
        val digest = sha256(part)
        check(digest == plan.sha256) {
            "Checksum mismatch for ${plan.fileName}: got $digest expected ${plan.sha256}"
        }
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Could not move downloaded ISO into place" }
    }

    private fun downloadFresh(plan: Plan, part: File, dest: File, onProgress: (Long, Long) -> Unit) {
        val conn = (URL(plan.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        conn.connect()
        check(conn.responseCode in 200..299) { "Download failed HTTP ${conn.responseCode}" }
        val total = conn.getHeaderFieldLong("Content-Length", -1)
        conn.inputStream.use { inp ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var copied = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (copied - last > 1024 * 1024) {
                        onProgress(copied, total)
                        last = copied
                    }
                }
                onProgress(copied, total)
            }
        }
        conn.disconnect()
        val digest = sha256(part)
        check(digest == plan.sha256) {
            "Checksum mismatch for ${plan.fileName}: got $digest expected ${plan.sha256}"
        }
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Could not move downloaded ISO into place" }
    }

    /**
     * Download [url] into [dest]. [expectedSha256] is optional; when set the
     * file is verified. Used by the Test Lab / intent-extra boot path, which
     * may pass a Kali ISO URL or a smaller Debian mini.iso.
     */
    fun downloadUrl(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val want = expectedSha256?.lowercase()?.trim().orEmpty()
        if (want.isNotEmpty() && dest.exists() && dest.length() > 0 && sha256(dest) == want) {
            onProgress(dest.length(), dest.length())
            return
        }
        if (url.startsWith("file://")) {
            // Test/adb path: copy an ISO already on the device (e.g. pushed by Test Lab).
            val src = File(url.removePrefix("file://"))
            val total = src.length()
            val part = File(dest.parentFile, dest.name + ".partial")
            src.inputStream().use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= (16L shl 20)) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                }
            }
            if (want.isNotEmpty() && sha256(part) != want) {
                part.delete()
                error("SHA-256 mismatch for $url")
            }
            if (dest.exists()) dest.delete()
            check(part.renameTo(dest)) { "rename failed" }
            onProgress(total, total)
            return
        }
        val plan = Plan(
            preset = DistroPreset(
                id = "url",
                name = dest.name,
                blurb = "",
                sumsUrl = "",
                dirUrl = url.substringBeforeLast('/') + "/",
                isoPattern = Regex(".*"),
            ),
            fileName = dest.name,
            sha256 = want,
            url = url,
        )
        if (want.isEmpty()) {
            downloadUrlUnchecked(url, dest, onProgress)
        } else {
            download(plan, dest, onProgress)
        }
    }

    private fun downloadUrlUnchecked(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val part = File(dest.parentFile, dest.name + ".partial")
        var existing = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 200 && existing > 0) {
            existing = 0
            if (part.exists()) part.delete()
        } else if (code != 200 && code != 206) {
            conn.disconnect()
            error("Download failed HTTP $code for $url")
        }
        val total = when {
            code == 206 -> existing + conn.getHeaderFieldLong("Content-Length", -1)
            else -> conn.getHeaderFieldLong("Content-Length", -1)
        }
        conn.inputStream.use { inp ->
            RandomAccessFile(part, "rw").use { raf ->
                if (existing == 0L) raf.setLength(0)
                raf.seek(existing)
                val buf = ByteArray(256 * 1024)
                var copied = existing
                var last = copied
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                    copied += n
                    if (copied - last > 1024 * 1024) {
                        onProgress(copied, total)
                        last = copied
                    }
                }
                onProgress(copied, total)
            }
        }
        conn.disconnect()
        if (dest.exists()) dest.delete()
        check(part.renameTo(dest)) { "Could not move downloaded ISO into place" }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private const val TAG = "NimbusDL" }
}
