package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var old: AppConfig
    private lateinit var groupTarget: RadioGroup
    private lateinit var groupMode: RadioGroup
    private lateinit var groupWindow: RadioGroup
    private lateinit var help: TextView
    private lateinit var windowHelp: TextView
    private lateinit var storage: TextView
    private lateinit var spinRam: Spinner
    private lateinit var spinCpus: Spinner
    private lateinit var spinTheme: Spinner
    private lateinit var spinFont: Spinner
    private lateinit var cbWipe: CheckBox
    private lateinit var cbMount: CheckBox
    private lateinit var cbBold: CheckBox
    private var missingVm: List<String> = emptyList()

    private val systemInstalled get() = Paths.okMarker(Paths.systemRoot(this)).exists()
    private val livecdPersistPresent get() = Paths.okMarker(Paths.livecdPersistRoot(this)).exists()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        groupTarget = findViewById(R.id.group_target)
        groupMode = findViewById(R.id.group_mode)
        groupWindow = findViewById(R.id.group_window)
        help = findViewById(R.id.mode_help)
        windowHelp = findViewById(R.id.window_help)
        storage = findViewById(R.id.storage_status)
        spinRam = findViewById(R.id.spin_ram)
        spinCpus = findViewById(R.id.spin_cpus)
        spinTheme = findViewById(R.id.spin_theme)
        spinFont = findViewById(R.id.spin_font)
        cbWipe = findViewById(R.id.cb_wipe)
        cbMount = findViewById(R.id.cb_mount_system)
        cbBold = findViewById(R.id.cb_bold)

        old = AppConfig.load(this)
        findViewById<TextView>(R.id.settings_header).text =
            if (old.configured) "Settings" else "Choose how to start"

        missingVm = QemuLauncher.missingParts(this)
        val rbVm = findViewById<RadioButton>(R.id.rb_vm)
        if (missingVm.isNotEmpty()) {
            rbVm.isEnabled = false
            rbVm.text = "VM (unavailable: ${missingVm.joinToString()})"
        }
        findViewById<TextView>(R.id.diag).text = QemuLauncher.diagnose(this)

        fun adapter(items: List<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinRam.adapter = adapter(AppConfig.RAM_CHOICES.map { it.toString() })
        spinCpus.adapter = adapter(AppConfig.CPU_CHOICES.map { it.toString() })
        spinTheme.adapter = adapter(Themes.ALL.map { it.name })
        spinFont.adapter = adapter(AppConfig.FONT_CHOICES.map { it.toString() })

        groupTarget.check(if (old.target == Target.SYSTEM) R.id.rb_system else R.id.rb_livecd)
        groupMode.check(
            when {
                old.mode == Mode.VM && missingVm.isEmpty() -> R.id.rb_vm
                old.mode == Mode.NORMAL -> R.id.rb_normal
                else -> R.id.rb_ephemeral
            }
        )
        groupWindow.check(if (old.windowMode == WindowMode.FLOATING) R.id.rb_floating else R.id.rb_fullscreen)
        spinRam.setSelection(AppConfig.RAM_CHOICES.indexOf(old.vmRamMb).coerceAtLeast(0))
        spinCpus.setSelection(AppConfig.CPU_CHOICES.indexOf(old.vmCpus).coerceAtLeast(0))
        spinTheme.setSelection(Themes.ALL.indexOfFirst { it.id == old.themeId }.coerceAtLeast(0))
        spinFont.setSelection(AppConfig.FONT_CHOICES.indexOf(old.fontSp).let { if (it < 0) 2 else it })
        cbWipe.isChecked = old.secureWipe
        cbMount.isChecked = old.mountSystem
        cbBold.isChecked = old.fontBold

        val refresh = RadioGroup.OnCheckedChangeListener { _, _ -> refresh() }
        groupTarget.setOnCheckedChangeListener(refresh)
        groupMode.setOnCheckedChangeListener(refresh)
        groupWindow.setOnCheckedChangeListener(refresh)
        refresh()

        findViewById<View>(R.id.btn_apply).setOnClickListener { apply() }
    }

    private fun currentTarget() = if (groupTarget.checkedRadioButtonId == R.id.rb_livecd) Target.LIVECD else Target.SYSTEM

    private fun currentMode() = when (groupMode.checkedRadioButtonId) {
        R.id.rb_ephemeral -> Mode.EPHEMERAL
        R.id.rb_vm -> Mode.VM
        else -> Mode.NORMAL
    }

    private fun currentWindow() =
        if (groupWindow.checkedRadioButtonId == R.id.rb_floating) WindowMode.FLOATING else WindowMode.FULLSCREEN

    private fun current() = AppConfig(
        configured = true,
        target = currentTarget(),
        mode = currentMode(),
        vmRamMb = AppConfig.RAM_CHOICES[spinRam.selectedItemPosition],
        vmCpus = AppConfig.CPU_CHOICES[spinCpus.selectedItemPosition],
        secureWipe = cbWipe.isChecked,
        mountSystem = cbMount.isChecked,
        windowMode = currentWindow(),
        themeId = Themes.ALL[spinTheme.selectedItemPosition].id,
        fontSp = AppConfig.FONT_CHOICES[spinFont.selectedItemPosition],
        fontBold = cbBold.isChecked
    )

    private fun apply() {
        val next = current()
        if (next.target == Target.SYSTEM && next.mode != Mode.NORMAL && !systemInstalled) {
            Toast.makeText(this, "The System is not installed yet. Choose System + Normal once to install it.", Toast.LENGTH_LONG).show()
            return
        }
        if (next.mode == Mode.VM && missingVm.isNotEmpty()) {
            Toast.makeText(this, "VM mode is not available in this build.", Toast.LENGTH_LONG).show()
            return
        }
        val restart = !old.configured || old.needsRestart(next)
        next.save(this)
        startService(
            Intent(this, TerminalService::class.java)
                .setAction(TerminalService.ACTION_APPLY)
                .putExtra(TerminalService.EXTRA_RESTART, restart)
        )
        // Show the Activity when the session (re)starts so progress is visible, in fullscreen mode, or to ask for the permission.
        if (restart || next.windowMode == WindowMode.FULLSCREEN || !Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun refresh() {
        val t = currentTarget()
        val m = currentMode()
        spinRam.isEnabled = m == Mode.VM
        spinCpus.isEnabled = m == Mode.VM
        cbWipe.isEnabled = m == Mode.EPHEMERAL
        cbMount.isEnabled = t == Target.LIVECD && m != Mode.VM && systemInstalled
        cbMount.text = when {
            t != Target.LIVECD -> "LiveCD: mount the installed System at /mnt/system (LiveCD only)"
            m == Mode.VM -> "LiveCD: mount the installed System at /mnt/system (not available in VM mode)"
            !systemInstalled -> "LiveCD: mount the installed System at /mnt/system (System not installed)"
            else -> "LiveCD: mount the installed System at /mnt/system (read-write)"
        }
        help.text = describe(t, m)
        windowHelp.text = if (currentWindow() == WindowMode.FLOATING) {
            "Needs the \"Display over other apps\" permission (you will be asked). Minimize from the title bar; restore from the notification."
        } else ""
        storage.text = "System:           ${if (systemInstalled) "installed" else "not installed"}\n" +
            "LiveCD persistence: ${if (livecdPersistPresent) "present" else "empty"}\n" +
            "Each mode uses its own storage; Ephemeral and VM never modify either one."
    }

    private fun describe(t: Target, m: Mode): String = when {
        t == Target.SYSTEM && m == Mode.NORMAL ->
            if (systemInstalled) "Runs your installed Debian; everything you change is kept."
            else "Debian will be installed when you press Apply (this is the only way it gets installed)."
        t == Target.SYSTEM && m == Mode.EPHEMERAL ->
            "Runs on a temporary copy of your installed Debian. Changes are discarded on exit and the copy is wiped. " +
                "Needs free space for a full copy. Flash storage may keep remnants: for no disk traces use VM."
        t == Target.SYSTEM && m == Mode.VM ->
            "Your installed Debian is streamed into a QEMU virtual machine and lives only in RAM. Changes are lost " +
                "on exit. Much slower than proot (no KVM on Android)."
        t == Target.LIVECD && m == Mode.NORMAL ->
            "The shipped image is extracted once and kept (LiveCD with persistence), separate from your System."
        t == Target.LIVECD && m == Mode.EPHEMERAL ->
            "A fresh copy of the shipped image is extracted on every start and wiped on exit. Flash storage may keep " +
                "remnants: for no disk traces use VM."
        else ->
            "The shipped image is streamed into a QEMU virtual machine and lives only in RAM. Nothing is written to " +
                "disk. Much slower than proot (no KVM on Android)."
    }
}
