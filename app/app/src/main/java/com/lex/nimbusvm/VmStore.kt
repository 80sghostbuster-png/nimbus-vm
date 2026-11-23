package com.lex.nimbusvm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VmRecord(
    val id: String,
    var name: String,
    var arch: QemuEngine.Arch,
    var os: IsoInspector.OsKind,
    var memoryMb: Int,
    var cpus: Int,
    var diskGb: Int,
    var isoName: String?,
    var isoAttached: Boolean,
    var tpm: Boolean,
    var secureBoot: Boolean,
    var sharedEnabled: Boolean,
    var createdAt: Long,
    var lastUsedAt: Long,
) {
    fun dir(ctx: Context): File = File(ctx.filesDir, "vms/$id").apply { mkdirs() }
    fun diskFile(ctx: Context): File = File(dir(ctx), "disk.qcow2")
    fun isoFile(ctx: Context): File = File(dir(ctx), "cdrom.iso")
    fun shareDir(ctx: Context): File = File(dir(ctx), "share").apply { mkdirs() }
    fun serialFile(ctx: Context): File = File(dir(ctx), "serial.log")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("arch", arch.name)
        .put("os", os.name)
        .put("memoryMb", memoryMb)
        .put("cpus", cpus)
        .put("diskGb", diskGb)
        .put("isoName", isoName)
        .put("isoAttached", isoAttached)
        .put("tpm", tpm)
        .put("secureBoot", secureBoot)
        .put("sharedEnabled", sharedEnabled)
        .put("createdAt", createdAt)
        .put("lastUsedAt", lastUsedAt)

    companion object {
        fun fromJson(o: JSONObject): VmRecord = VmRecord(
            id = o.getString("id"),
            name = o.getString("name"),
            arch = QemuEngine.Arch.valueOf(o.optString("arch", "ARM64")),
            os = runCatching { IsoInspector.OsKind.valueOf(o.optString("os", "UNKNOWN")) }
                .getOrDefault(IsoInspector.OsKind.UNKNOWN),
            memoryMb = o.optInt("memoryMb", 2048),
            cpus = o.optInt("cpus", 2),
            diskGb = o.optInt("diskGb", 32),
            isoName = o.optString("isoName").ifEmpty { null },
            isoAttached = o.optBoolean("isoAttached", true),
            tpm = o.optBoolean("tpm", false),
            secureBoot = o.optBoolean("secureBoot", false),
            sharedEnabled = o.optBoolean("sharedEnabled", true),
            createdAt = o.optLong("createdAt", 0),
            lastUsedAt = o.optLong("lastUsedAt", 0),
        )
    }
}

class VmStore(private val context: Context) {
    private val file = File(context.filesDir, "vms/library.json")
    private val items = mutableListOf<VmRecord>()

    init { load() }

    fun all(): List<VmRecord> = items.sortedByDescending { it.lastUsedAt }

    fun get(id: String): VmRecord? = items.find { it.id == id }

    fun add(record: VmRecord) {
        items.removeAll { it.id == record.id }
        items.add(record)
        save()
    }

    fun update(record: VmRecord) {
        val i = items.indexOfFirst { it.id == record.id }
        if (i >= 0) items[i] = record else items.add(record)
        save()
    }

    fun delete(id: String) {
        items.removeAll { it.id == id }
        save()
        File(context.filesDir, "vms/$id").deleteRecursively()
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun toConfig(vm: VmRecord): QemuEngine.Config {
        val iso = vm.isoFile(context).takeIf { vm.isoAttached && it.exists() }
        return QemuEngine.Config(
            vmId = vm.id,
            arch = vm.arch,
            memoryMb = vm.memoryMb,
            cpus = vm.cpus,
            disk = vm.diskFile(context),
            iso = iso,
            sharedDir = if (vm.sharedEnabled) vm.shareDir(context) else null,
            tpm = vm.tpm,
            secureBoot = vm.secureBoot,
            graphics = true,
        )
    }

    private fun load() {
        items.clear()
        if (!file.exists()) return
        val root = JSONObject(file.readText())
        val arr = root.optJSONArray("vms") ?: JSONArray()
        for (i in 0 until arr.length()) items.add(VmRecord.fromJson(arr.getJSONObject(i)))
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        file.writeText(JSONObject().put("vms", arr).toString(2))
    }
}
