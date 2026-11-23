package com.lex.nimbusvm

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AdvancedActivity : AppCompatActivity() {
    private lateinit var store: VmStore
    private lateinit var vm: VmRecord

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced)
        store = VmStore(this)
        val id = intent.getStringExtra(LibraryActivity.EXTRA_VM_ID)
        vm = store.get(id ?: "") ?: run { finish(); return }

        val name = findViewById<EditText>(R.id.name)
        val ramBar = findViewById<SeekBar>(R.id.ram)
        val ramVal = findViewById<TextView>(R.id.ramVal)
        val cpuBar = findViewById<SeekBar>(R.id.cpu)
        val cpuVal = findViewById<TextView>(R.id.cpuVal)
        val diskBar = findViewById<SeekBar>(R.id.disk)
        val diskVal = findViewById<TextView>(R.id.diskVal)
        val x86 = findViewById<Switch>(R.id.x86)
        val tpm = findViewById<Switch>(R.id.tpm)
        val sb = findViewById<Switch>(R.id.secureBoot)
        val share = findViewById<Switch>(R.id.share)
        val iso = findViewById<Switch>(R.id.isoAttached)
        val warn = findViewById<TextView>(R.id.x86Warning)

        name.setText(vm.name)
        // RAM 512..8192 in 256 MB steps
        ramBar.max = (8192 - 512) / 256
        ramBar.progress = ((vm.memoryMb - 512) / 256).coerceIn(0, ramBar.max)
        fun ramNow() = 512 + ramBar.progress * 256
        ramVal.text = "${ramNow()} MB"
        ramBar.setOnSeekBarChangeListener(simple { ramVal.text = "${ramNow()} MB" })

        val maxCpu = maxOf(8, Runtime.getRuntime().availableProcessors())
        cpuBar.max = maxCpu - 1
        cpuBar.progress = (vm.cpus - 1).coerceIn(0, cpuBar.max)
        fun cpuNow() = cpuBar.progress + 1
        cpuVal.text = "${cpuNow()} CPUs"
        cpuBar.setOnSeekBarChangeListener(simple { cpuVal.text = "${cpuNow()} CPUs" })

        // Disk 8..64 GB; only applied if the qcow2 does not yet exist at a different size
        diskBar.max = 64 - 8
        diskBar.progress = (vm.diskGb - 8).coerceIn(0, diskBar.max)
        fun diskNow() = diskBar.progress + 8
        diskVal.text = "${diskNow()} GB (sparse)"
        diskBar.setOnSeekBarChangeListener(simple { diskVal.text = "${diskNow()} GB (sparse)" })
        if (vm.diskFile(this).exists()) {
            diskBar.isEnabled = false
            diskVal.text = "${vm.diskGb} GB (already created)"
        }

        x86.isChecked = vm.arch == QemuEngine.Arch.X86_64
        warn.visibility = if (x86.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        x86.setOnCheckedChangeListener { _, on ->
            warn.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        }
        tpm.isChecked = vm.tpm
        sb.isChecked = vm.secureBoot
        share.isChecked = vm.sharedEnabled
        iso.isChecked = vm.isoAttached
        iso.isEnabled = vm.isoFile(this).exists()

        findViewById<Button>(R.id.start).setOnClickListener {
            vm.name = name.text.toString().ifBlank { vm.name }
            vm.memoryMb = ramNow()
            vm.cpus = cpuNow()
            if (!vm.diskFile(this).exists()) vm.diskGb = diskNow()
            vm.arch = if (x86.isChecked) QemuEngine.Arch.X86_64 else QemuEngine.Arch.ARM64
            vm.tpm = tpm.isChecked
            vm.secureBoot = sb.isChecked
            vm.sharedEnabled = share.isChecked
            vm.isoAttached = iso.isChecked
            store.update(vm)
            startActivity(
                Intent(this, DisplayActivity::class.java)
                    .putExtra(DisplayActivity.EXTRA_VM_ID, vm.id)
                    .putExtra(DisplayActivity.EXTRA_START, true)
            )
            finish()
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            vm.name = name.text.toString().ifBlank { vm.name }
            vm.memoryMb = ramNow()
            vm.cpus = cpuNow()
            if (!vm.diskFile(this).exists()) vm.diskGb = diskNow()
            vm.arch = if (x86.isChecked) QemuEngine.Arch.X86_64 else QemuEngine.Arch.ARM64
            vm.tpm = tpm.isChecked
            vm.secureBoot = sb.isChecked
            vm.sharedEnabled = share.isChecked
            vm.isoAttached = iso.isChecked
            store.update(vm)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun simple(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange()
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
