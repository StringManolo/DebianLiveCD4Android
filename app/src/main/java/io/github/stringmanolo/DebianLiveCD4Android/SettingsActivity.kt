package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var groupTarget: RadioGroup
    private lateinit var groupMode: RadioGroup
    private lateinit var help: TextView
    private lateinit var spinRam: Spinner
    private lateinit var spinCpus: Spinner
    private lateinit var cbWipe: CheckBox
    private var vmAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        groupTarget = findViewById(R.id.group_target)
        groupMode = findViewById(R.id.group_mode)
        help = findViewById(R.id.mode_help)
        spinRam = findViewById(R.id.spin_ram)
        spinCpus = findViewById(R.id.spin_cpus)
        cbWipe = findViewById(R.id.cb_wipe)

        vmAvailable = QemuLauncher.available(this)
        val rbVm = findViewById<RadioButton>(R.id.rb_vm)
        if (!vmAvailable) {
            rbVm.isEnabled = false
            rbVm.text = "VM (not included in this build)"
        }

        spinRam.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AppConfig.RAM_CHOICES.map { it.toString() })
        spinCpus.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, AppConfig.CPU_CHOICES.map { it.toString() })

        val cfg = AppConfig.load(this)
        groupTarget.check(if (cfg.target == Target.SYSTEM) R.id.rb_system else R.id.rb_livecd)
        groupMode.check(
            when {
                cfg.mode == Mode.VM && vmAvailable -> R.id.rb_vm
                cfg.mode == Mode.EPHEMERAL -> R.id.rb_ephemeral
                else -> R.id.rb_normal
            }
        )
        spinRam.setSelection(AppConfig.RAM_CHOICES.indexOf(cfg.vmRamMb).coerceAtLeast(0))
        spinCpus.setSelection(AppConfig.CPU_CHOICES.indexOf(cfg.vmCpus).coerceAtLeast(0))
        cbWipe.isChecked = cfg.secureWipe

        val refresh = RadioGroup.OnCheckedChangeListener { _, _ -> refresh() }
        groupTarget.setOnCheckedChangeListener(refresh)
        groupMode.setOnCheckedChangeListener(refresh)
        refresh()

        findViewById<View>(R.id.btn_apply).setOnClickListener {
            val next = AppConfig(
                target = currentTarget(),
                mode = currentMode(),
                vmRamMb = AppConfig.RAM_CHOICES[spinRam.selectedItemPosition],
                vmCpus = AppConfig.CPU_CHOICES[spinCpus.selectedItemPosition],
                secureWipe = cbWipe.isChecked
            )
            next.save(this)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun currentTarget() = if (groupTarget.checkedRadioButtonId == R.id.rb_livecd) Target.LIVECD else Target.SYSTEM

    private fun currentMode() = when (groupMode.checkedRadioButtonId) {
        R.id.rb_ephemeral -> Mode.EPHEMERAL
        R.id.rb_vm -> Mode.VM
        else -> Mode.NORMAL
    }

    private fun refresh() {
        val t = currentTarget()
        val m = currentMode()
        spinRam.isEnabled = m == Mode.VM
        spinCpus.isEnabled = m == Mode.VM
        cbWipe.isEnabled = m == Mode.EPHEMERAL
        help.text = describe(t, m)
    }

    private fun describe(t: Target, m: Mode): String = when {
        t == Target.SYSTEM && m == Mode.NORMAL ->
            "Debian is installed on first use and everything you change is kept."
        t == Target.SYSTEM && m == Mode.EPHEMERAL ->
            "Runs on a temporary copy of your installed Debian. Changes are discarded on exit and the copy is " +
                "wiped. Needs free space for a full copy. Flash storage may keep remnants: for no disk traces use VM."
        t == Target.SYSTEM && m == Mode.VM ->
            "Your installed Debian is streamed into a QEMU virtual machine and lives only in RAM. Changes are " +
                "lost on exit. Much slower than proot (no KVM on Android)."
        t == Target.LIVECD && m == Mode.NORMAL ->
            "The shipped image is extracted once and kept (LiveCD with persistence), separate from your System."
        t == Target.LIVECD && m == Mode.EPHEMERAL ->
            "A fresh copy of the shipped image is extracted on every start and wiped on exit. Flash storage may " +
                "keep remnants: for no disk traces use VM."
        else ->
            "The shipped image is streamed into a QEMU virtual machine and lives only in RAM. Nothing is written " +
                "to disk. Much slower than proot (no KVM on Android)."
    }
}
