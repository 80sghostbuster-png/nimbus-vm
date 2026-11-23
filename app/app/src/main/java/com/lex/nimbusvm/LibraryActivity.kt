package com.lex.nimbusvm

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class LibraryActivity : AppCompatActivity() {
    private lateinit var store: VmStore
    private lateinit var engine: QemuEngine
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var progressLabel: TextView
    private var openAdvancedAfterPick = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onIsoPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        store = VmStore(this)
        engine = QemuEngine(this)
        engine.unpackAll()
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.vmList)
        progress = findViewById(R.id.progress)
        progressLabel = findViewById(R.id.progressLabel)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = VmAdapter()

        findViewById<Button>(R.id.addIso).setOnClickListener {
            openAdvancedAfterPick = false
            openPicker()
        }
        findViewById<Button>(R.id.advanced).setOnClickListener {
            openAdvancedAfterPick = true
            openPicker()
        }
        findViewById<TextView>(R.id.debugLink).setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openPicker() {
        picker.launch(arrayOf("application/*", "*/*"))
    }

    private fun onIsoPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        setBusy(true, "Copying ISO…")
        thread {
            try {
                val id = store.newId()
                val dir = File(filesDir, "vms/$id").apply { mkdirs() }
                val iso = File(dir, "cdrom.iso")
                copyUri(uri, iso) { copied, total ->
                    if (total > 0) {
                        val pct = (copied * 100 / total).toInt()
                        runOnUiThread { setBusy(true, "Copying ISO… $pct%") }
                    }
                }
                runOnUiThread { setBusy(true, "Detecting image…") }
                val profile = IsoInspector.inspect(iso)
                val windows = profile.os == IsoInspector.OsKind.WINDOWS
                val displayName = queryDisplayName(uri) ?: iso.name
                val rec = VmRecord(
                    id = id,
                    name = VmDefaults.displayName(profile, displayName),
                    arch = profile.arch,
                    os = profile.os,
                    memoryMb = VmDefaults.ramMb(this, windows),
                    cpus = VmDefaults.cpus(),
                    diskGb = VmDefaults.diskGb(windows),
                    isoName = displayName,
                    isoAttached = true,
                    tpm = VmDefaults.tpm(windows),
                    secureBoot = VmDefaults.secureBoot(windows),
                    sharedEnabled = true,
                    createdAt = System.currentTimeMillis(),
                    lastUsedAt = System.currentTimeMillis(),
                )
                rec.shareDir(this)
                store.add(rec)
                val goAdvanced = openAdvancedAfterPick
                runOnUiThread {
                    setBusy(false, "")
                    Toast.makeText(this, profile.summary, Toast.LENGTH_LONG).show()
                    if (goAdvanced) {
                        startActivity(Intent(this, AdvancedActivity::class.java).putExtra(EXTRA_VM_ID, rec.id))
                    } else {
                        boot(rec)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false, "")
                    AlertDialog.Builder(this)
                        .setTitle("Could not use that ISO")
                        .setMessage(e.message ?: e.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun boot(vm: VmRecord) {
        val i = Intent(this, DisplayActivity::class.java)
            .putExtra(DisplayActivity.EXTRA_VM_ID, vm.id)
            .putExtra(DisplayActivity.EXTRA_START, true)
        startActivity(i)
    }

    private fun refresh() {
        val vms = store.all()
        empty.visibility = if (vms.isEmpty()) View.VISIBLE else View.GONE
        (list.adapter as VmAdapter).items = vms
        list.adapter?.notifyDataSetChanged()
    }

    private fun setBusy(busy: Boolean, label: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progressLabel.visibility = if (busy) View.VISIBLE else View.GONE
        progressLabel.text = label
        findViewById<Button>(R.id.addIso).isEnabled = !busy
        findViewById<Button>(R.id.advanced).isEnabled = !busy
    }

    private fun copyUri(uri: Uri, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val total = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        contentResolver.openInputStream(uri).use { inp ->
            requireNotNull(inp) { "Cannot open ISO" }
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(1024 * 256)
                var copied = 0L
                var last = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (copied - last > 5 * 1024 * 1024) {
                        onProgress(copied, total)
                        last = copied
                    }
                }
                onProgress(copied, total)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment
    }

    inner class VmAdapter : RecyclerView.Adapter<VmHolder>() {
        var items: List<VmRecord> = emptyList()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VmHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_vm, parent, false)
            return VmHolder(v)
        }
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VmHolder, position: Int) {
            val vm = items[position]
            holder.name.text = vm.name
            val iso = if (vm.isoAttached && vm.isoName != null) "ISO attached · ${vm.isoName}" else "Boot from disk"
            holder.meta.text = "${vm.memoryMb} MB RAM · ${vm.cpus} CPUs · ${vm.diskGb} GB disk · $iso"
            holder.run.setOnClickListener { boot(vm) }
            holder.itemView.setOnLongClickListener {
                vmMenu(vm)
                true
            }
            holder.more.setOnClickListener { vmMenu(vm) }
        }
    }

    class VmHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.vmName)
        val meta: TextView = view.findViewById(R.id.vmMeta)
        val run: Button = view.findViewById(R.id.vmRun)
        val more: Button = view.findViewById(R.id.vmMore)
    }

    private fun vmMenu(vm: VmRecord) {
        val ejectLabel = if (vm.isoAttached) "Eject ISO (boot from disk next time)" else "Re-attach ISO"
        val items = arrayOf("Run", "Advanced settings", ejectLabel, "Delete")
        AlertDialog.Builder(this)
            .setTitle(vm.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> boot(vm)
                    1 -> startActivity(Intent(this, AdvancedActivity::class.java).putExtra(EXTRA_VM_ID, vm.id))
                    2 -> {
                        vm.isoAttached = !vm.isoAttached
                        store.update(vm)
                        refresh()
                    }
                    3 -> {
                        AlertDialog.Builder(this)
                            .setMessage("Delete ${vm.name} and its disk?")
                            .setPositiveButton("Delete") { _, _ -> store.delete(vm.id); refresh() }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    companion object { const val EXTRA_VM_ID = "vm_id" }
}
