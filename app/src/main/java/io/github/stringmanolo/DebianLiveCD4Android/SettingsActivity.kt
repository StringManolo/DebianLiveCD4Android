package io.github.stringmanolo.DebianLiveCD4Android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar

/** Tabbed settings. Environment/Window changes use the Apply button; everything else takes effect immediately. */
class SettingsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var applyBar: Button
    private lateinit var old: AppConfig
    private lateinit var work: AppConfig
    private var currentTab = "env"
    private var missingVm: List<String> = emptyList()
    private var expectReturn = false
    private var pinShowing = false
    private var pendingExport: Pair<SysInfo, String>? = null
    private var systemsList: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Cfg.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        ui = Ui(this)
        old = AppConfig.load(this)
        work = old
        missingVm = QemuLauncher.missingParts(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0E11")) }
        val title = TextView(this).apply {
            text = if (old.configured) "Settings" else "Choose how to start"
            textSize = 20f; setTextColor(Color.WHITE); setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(6))
        }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }
        content = FrameLayout(this)
        applyBar = Button(this).apply { text = "Apply environment & window"; isAllCaps = false; setOnClickListener { apply() } }
        root.addView(title)
        root.addView(tabScroll)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(applyBar)
        setContentView(root)

        for ((id, label) in TABS) {
            val t = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); tag = id
                setOnClickListener { show(id) }
            }
            tabBar.addView(t)
        }
        show("env")
    }

    override fun onResume() {
        super.onResume()
        expectReturn = false
        if (AppLock.isLocked() && !pinShowing) {
            pinShowing = true
            content.visibility = View.INVISIBLE
            startActivityForResult(Intent(this, PinActivity::class.java), REQ_PIN)
        } else {
            AppLock.touch()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!expectReturn && !pinShowing) AppLock.onLeftForeground()
    }

    private fun picker(i: Intent, code: Int) { expectReturn = true; startActivityForResult(i, code) }

    // ------------------------------------------------------------------ tabs

    private fun show(tab: String) {
        currentTab = tab
        for (i in 0 until tabBar.childCount) {
            val v = tabBar.getChildAt(i) as TextView
            val sel = v.tag == tab
            v.setTextColor(if (sel) Color.parseColor("#58A6FF") else Color.parseColor("#8B949E"))
            v.setBackgroundColor(if (sel) Color.parseColor("#161B22") else Color.TRANSPARENT)
        }
        val page: View = when (tab) {
            "systems" -> systemsTab()
            "look" -> lookTab()
            "sec" -> securityTab()
            "net" -> networkTab()
            "upd" -> updatesTab()
            else -> envTab()
        }
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(page) })
        applyBar.visibility = if (tab == "env") View.VISIBLE else View.GONE
    }

    // ---- Environment
    private fun envTab(): View {
        val p = ui.page()
        ui.header(p, "Environment", 0)
        ui.radios(p, listOf("System (one of your installed systems)", "LiveCD (image shipped in the APK)"),
            if (work.target == Target.SYSTEM) 0 else 1) {
            work = work.copy(target = if (it == 0) Target.SYSTEM else Target.LIVECD); show("env")
        }
        if (work.target == Target.SYSTEM) {
            val sys = SystemStore.systems().filter { it.installed }
            if (sys.isEmpty()) {
                ui.note(p, "No system installed yet. Create one in the Systems tab (nothing is installed automatically).")
            } else {
                ui.label(p, "Active system")
                ui.spinner(p, sys.map { it.name }, sys.indexOfFirst { it.id == Cfg.activeSystem }.coerceAtLeast(0)) {
                    Cfg.activeSystem = sys[it].id
                }
                if (Cfg.activeSystem.isEmpty() || sys.none { it.id == Cfg.activeSystem }) Cfg.activeSystem = sys[0].id
            }
        }

        ui.header(p, "Mode")
        val modes = listOf(
            "Normal (persistent)", "Ephemeral (temporary copy, wiped on exit)",
            if (missingVm.isEmpty()) "VM (QEMU, RAM only) — Experimental" else "VM (Experimental) — unavailable: ${missingVm.joinToString()}"
        )
        ui.radios(p, modes, work.mode.ordinal) {
            if (it == 2 && missingVm.isNotEmpty()) { ui.toast("VM is not available in this build"); show("env") }
            else { work = work.copy(mode = Mode.values()[it]); show("env") }
        }
        ui.note(p, describe(work.target, work.mode))

        if (work.target == Target.LIVECD && work.mode != Mode.VM) {
            val canMount = Cfg.activeSystem.isNotEmpty() && SystemStore.info(Cfg.activeSystem)?.installed == true
            val c = ui.check(p, "Mount the active system at /mnt/system (read-write)", work.mountSystem && canMount) {
                work = work.copy(mountSystem = it)
            }
            c.isEnabled = canMount
            ui.note(p, "Like a live CD mounting the hard disk to repair or back it up. Off by default.")
        }
        if (work.mode == Mode.EPHEMERAL) {
            ui.check(p, "Overwrite files with random data before deleting", work.secureWipe) { work = work.copy(secureWipe = it) }
        }
        if (work.mode == Mode.VM) {
            ui.label(p, "VM RAM (MB)")
            ui.spinner(p, AppConfig.RAM_CHOICES.map { it.toString() }, AppConfig.RAM_CHOICES.indexOf(work.vmRamMb).coerceAtLeast(0)) {
                work = work.copy(vmRamMb = AppConfig.RAM_CHOICES[it])
            }
            ui.label(p, "VM CPU cores")
            ui.spinner(p, AppConfig.CPU_CHOICES.map { it.toString() }, AppConfig.CPU_CHOICES.indexOf(work.vmCpus).coerceAtLeast(0)) {
                work = work.copy(vmCpus = AppConfig.CPU_CHOICES[it])
            }
            ui.check(p, "Give the VM a network device (only if the network is allowed)", Cfg.vmNic) { Cfg.vmNic = it }
        }

        ui.header(p, "Window")
        ui.radios(p, listOf("Fullscreen", "Floating window (drag, resize, rotate, pin, opacity, minimize)"),
            work.windowMode.ordinal) { work = work.copy(windowMode = WindowMode.values()[it]); show("env") }
        if (work.windowMode == WindowMode.FLOATING) {
            ui.note(p, "Needs \"Display over other apps\" (you will be asked). Minimize with —; restore from the notification.")
        }
        ui.button(p, "Reset floating window (size, position, rotation, opacity, pin)", 6) {
            startService(Intent(this, TerminalService::class.java).setAction(TerminalService.ACTION_RESET_WINDOW))
            ui.toast("Floating window reset to its defaults.")
        }

        ui.header(p, "While running")
        ui.check(p, "Keep the CPU awake (wake lock)", Cfg.wakeLock) { Cfg.wakeLock = it }
        ui.note(p, "Only needed for long jobs while the screen is off (builds, servers, VM). Costs battery.")
        ui.check(p, "Allow access to shared storage (/sdcard and ~/storage/shared)",
            Cfg.storageAccess && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) { on ->
            if (on) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            else {
                Cfg.storageAccess = false
                ui.toast("Storage is no longer mounted in new sessions. To also revoke the Android permission, use the button below.")
            }
        }
        ui.button(p, "Open Android app permissions", 4) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        ui.note(p, "Like termux-setup-storage, but on demand. VM mode never has shared storage.")

        ui.header(p, "Diagnostics")
        ui.note(p, QemuLauncher.diagnose(this)).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) }
        return p
    }

    private fun describe(t: Target, m: Mode): String = when {
        t == Target.SYSTEM && m == Mode.NORMAL -> "Runs the active system; everything you change is kept."
        t == Target.SYSTEM && m == Mode.EPHEMERAL ->
            "Runs on a temporary copy of the active system; changes are discarded and the copy is wiped. The system itself is never modified. Flash storage may keep remnants: for no disk traces use VM."
        t == Target.SYSTEM -> "EXPERIMENTAL. The active system is streamed into a QEMU VM and lives only in RAM (decrypted on the fly if it is encrypted). Changes are lost on exit. Much slower than proot."
        m == Mode.NORMAL -> "The shipped image is extracted once and kept (LiveCD with persistence), separate from your systems."
        m == Mode.EPHEMERAL -> "A fresh copy of the shipped image on every start, wiped on exit."
        else -> "EXPERIMENTAL. The shipped image is streamed into a QEMU VM and lives only in RAM. Nothing is written to disk."
    }

    private fun apply() {
        val next = work.copy(configured = true)
        if (next.target == Target.SYSTEM) {
            val id = Cfg.activeSystem
            if (id.isEmpty() || SystemStore.info(id)?.installed != true) {
                ui.toast("Select or create a system first (Systems tab), or use the LiveCD.")
                return
            }
        }
        if (next.mode == Mode.VM && missingVm.isNotEmpty()) { ui.toast("VM mode is not available in this build."); return }
        val restart = !old.configured || old.needsRestart(next)
        next.save(this)
        old = next
        Cfg.lastOpened = System.currentTimeMillis()
        sendApply(restart)
        if (restart || next.windowMode == WindowMode.FULLSCREEN || !Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun sendApply(restart: Boolean) {
        startService(Intent(this, TerminalService::class.java)
            .setAction(TerminalService.ACTION_APPLY).putExtra(TerminalService.EXTRA_RESTART, restart))
    }

    // ---- Systems
    private fun busy(): Boolean {
        if (Cfg.sessionRunning) { ui.toast("Stop the running session first (notification > Exit)."); return true }
        return false
    }

    private fun systemsTab(): View {
        val p = ui.page()
        ui.header(p, "Systems", 0)
        ui.note(p, "Create, rename, duplicate, delete, import, export, download, snapshot and restore. Each system is stored on its own and never touches the others.")
        val space = ui.note(p, "Free space: ${fmtBytes(filesDir.usableSpace)} · Systems use: calculating…", 6)
        ui.button(p, "New system from the shipped Debian") {
            if (busy()) return@button
            ui.prompt("New system", "Name", "Debian") { name ->
                ui.ensureKeyUnlocked {
                    ui.job("Installing $name", { prog ->
                        val s = SystemStore.createFromAsset(name.ifBlank { "Debian" }, "system", progress = prog)
                        if (Cfg.activeSystem.isEmpty()) Cfg.activeSystem = s.id
                    }) { show("systems") }
                }
            }
        }
        ui.button(p, "Import a tar / tar.gz / tar.xz / .dlc file…", 4) {
            if (busy()) return@button
            picker(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
        }
        ui.button(p, "Download a rootfs…", 4) { if (!busy()) downloadDialog() }
        ui.header(p, "Installed")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.addView(list)
        systemsList = list
        val all = SystemStore.list()
        if (all.isEmpty()) ui.note(list, "Nothing installed.")
        val rows = ArrayList<Pair<SysInfo, TextView>>()
        for (s in all) {
            val state = when {
                !s.installed -> "not installed"
                s.inUse -> "in use"
                s.archived -> "at rest" + (if (s.encrypted) ", encrypted" else "") + " (archive)"
                else -> "plain"
            }
            val star = if (s.id == Cfg.activeSystem) "★ " else ""
            val t = TextView(this).apply {
                text = "$star${s.name}\n${s.kind} · $state · ${s.snapshots} snapshot(s)"
                setTextColor(ui.text); textSize = 14f
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                setBackgroundColor(Color.parseColor("#161B22"))
                setOnClickListener { systemActions(s) }
            }
            list.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            rows.add(Pair(s, t))
        }
        Thread {
            var total = 0L
            for ((si, tv) in rows) {
                val sz = SystemStore.sizeOf(si.id)
                total += sz
                runOnUiThread { if (currentTab == "systems") tv.text = tv.text.toString().replaceFirst("\n", "\n${fmtBytes(sz)} · ") }
            }
            runOnUiThread { if (currentTab == "systems") space.text = "Free space: ${fmtBytes(filesDir.usableSpace)} · Systems use: ${fmtBytes(total)}" }
        }.start()
        return p
    }

    private fun systemActions(s: SysInfo) {
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        fun add(t: String, a: () -> Unit) { items += t; acts += a }
        if (s.kind == "system" && s.installed) add("Use as the active system") { Cfg.activeSystem = s.id; show("systems") }
        if (s.kind == "system") add("Rename…") {
            ui.prompt("Rename", "Name", s.name) { SystemStore.rename(s.id, it.ifBlank { s.name }); show("systems") }
        }
        if (s.kind == "system" && s.installed) add("Duplicate…") {
            if (busy()) return@add
            ui.prompt("Duplicate", "Name for the copy", s.name + " copy") { n ->
                ui.job("Duplicating", { prog -> SystemStore.duplicate(s.id, n, prog) }) { show("systems") }
            }
        }
        if (s.installed) {
            add("Export…") { if (!busy()) exportDialog(s) }
            add("Take snapshot…") {
                if (busy()) return@add
                ui.prompt("Snapshot", "Label", "manual") { label ->
                    ui.ensureKeyUnlocked { ui.job("Snapshot", { prog -> SystemStore.takeSnapshot(s.id, label, prog) }) { show("systems") } }
                }
            }
        }
        add("Snapshots (${s.snapshots})…") { snapshotsDialog(s) }
        add(if (s.kind == "livecd") "Reset LiveCD persistence…" else "Delete…") {
            if (busy()) return@add
            ui.choose("Delete ${s.name}", listOf("Delete", "Secure wipe (overwrite with random data, slower)")) { m ->
                ui.job("Deleting", { SystemStore.delete(s.id, if (m == 1) WipeMode.RANDOM else WipeMode.NONE) }) { show("systems") }
            }
        }
        ui.choose(s.name, items) { acts[it]() }
    }

    private fun exportDialog(s: SysInfo) {
        ui.choose("Export ${s.name}", listOf("Portable .tar.gz", "Portable .tar.xz (smaller, slower)", "This app's .dlc (uses the current compression/encryption)")) { f ->
            val fmt = listOf("tgz", "txz", "dlc")[f]
            val ext = listOf(".tar.gz", ".tar.xz", ".dlc")[f]
            ui.ensureKeyUnlocked {
                pendingExport = Pair(s, fmt)
                picker(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, s.name.replace(' ', '_') + ext), REQ_EXPORT)
            }
        }
    }

    private fun snapshotsDialog(s: SysInfo) {
        val snaps = SystemStore.snapshots(s.id)
        if (snaps.isEmpty()) { ui.toast("No snapshots yet."); return }
        ui.choose("Snapshots of ${s.name}", snaps.map { SystemStore.snapshotTitle(it) }) { i ->
            val f = snaps[i]
            ui.choose(SystemStore.snapshotTitle(f), listOf("Restore (replaces the current content)", "Delete")) { a ->
                if (busy()) return@choose
                if (a == 0) ui.confirm("Restore snapshot", "The current content of ${s.name} will be replaced.", "Restore") {
                    ui.ensureKeyUnlocked { ui.job("Restoring", { prog -> SystemStore.restoreSnapshot(s.id, f, prog) }) { show("systems") } }
                } else {
                    ui.job("Deleting", { FsUtil.wipeTree(f, WipeMode.NONE) }) { show("systems") }
                }
            }
        }
    }

    private fun downloadDialog() {
        if (!Cfg.allowNetwork) { ui.toast("The network is disabled (Network tab)."); return }
        val labels = Downloader.CATALOG.map { it.label } + "Custom URL…"
        ui.choose("Download a rootfs  (free space: ${fmtBytes(filesDir.usableSpace)})", labels) { i ->
            if (i == Downloader.CATALOG.size) {
                val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
                val u = EditText(this).apply { hint = "https://…/rootfs.tar.gz" }
                val h = EditText(this).apply { hint = "SHA-256 (optional but recommended)" }
                box.addView(u); box.addView(h)
                AlertDialog.Builder(this).setTitle("Custom rootfs URL").setView(box)
                    .setPositiveButton("Next") { _, _ ->
                        val url = u.text.toString().trim()
                        if (url.startsWith("http")) confirmDownload("Custom system", url, h.text.toString().trim().ifEmpty { null })
                        else ui.toast("Enter an http(s) URL")
                    }.setNegativeButton("Cancel", null).show()
            } else {
                resolved = null
                ui.job("Looking up…", { _ -> resolved = Downloader.CATALOG[i].resolve() }) { e ->
                    val r = resolved
                    if (e == null && r != null) confirmDownload(r.name, r.url, r.sha256)
                }
            }
        }
    }

    /** Shows the real download size, an estimate of the installed size and the free space, then asks. */
    private fun confirmDownload(name: String, url: String, sha: String?) {
        var size = -1L
        ui.job("Checking size…", { _ -> size = Downloader.size(url) }) { e ->
            if (e != null) return@job
            val free = filesDir.usableSpace
            val est = if (size > 0) size * 3 else -1L   // gz/xz rootfs tarballs typically unpack to ~3x
            val low = est > 0 && free < est + 200L * 1024 * 1024
            ui.confirm(
                "Download $name",
                "$url\n\nDownload: ${fmtBytes(size)}\nInstalled (estimate): ${if (est > 0) "≈ " + fmtBytes(est) else "unknown"}\n" +
                    "Free space: ${fmtBytes(free)}\n" +
                    (if (sha != null) "SHA-256 will be verified." else "No checksum: the download is not verified.") +
                    (if (low) "\n\n⚠ There may not be enough free space." else ""),
                if (low) "Download anyway" else "Download"
            ) { runDownload(url, sha, name) }
        }
    }

    private var resolved: Downloader.Resolved? = null

    private fun runDownload(url: String, sha: String?, name: String) {
        ui.ensureKeyUnlocked {
            ui.job("Downloading $name", { prog ->
                val c = Downloader.open(url)
                val s = SystemStore.importStream(name, c.inputStream, c.contentLengthLong, sha, prog)
                if (Cfg.activeSystem.isEmpty()) Cfg.activeSystem = s.id
            }) { show("systems") }
        }
    }

    // ---- Appearance
    private fun lookTab(): View {
        val p = ui.page()
        ui.header(p, "Appearance", 0)
        ui.note(p, "Changes apply instantly; the session is not restarted.")
        ui.label(p, "Colour scheme")
        val names = Themes.ALL.map { it.name } + "Custom…"
        val sel = if (work.themeId == "custom") Themes.ALL.size else Themes.ALL.indexOfFirst { it.id == work.themeId }.coerceAtLeast(0)
        ui.spinner(p, names, sel) {
            if (it == Themes.ALL.size) customThemeDialog() else { work = work.copy(themeId = Themes.ALL[it].id); pushLook() }
        }
        ui.button(p, "Edit custom colours…", 6) { customThemeDialog() }
        ui.label(p, "Font size (sp)")
        ui.spinner(p, AppConfig.FONT_CHOICES.map { it.toString() }, AppConfig.FONT_CHOICES.indexOf(work.fontSp).let { if (it < 0) 2 else it }) {
            work = work.copy(fontSp = AppConfig.FONT_CHOICES[it]); pushLook()
        }
        ui.check(p, "Bold font", work.fontBold) { work = work.copy(fontBold = it); pushLook() }
        return p
    }

    private fun pushLook() {
        val c = AppConfig.load(this)
        c.copy(themeId = work.themeId, fontSp = work.fontSp, fontBold = work.fontBold).save(this)
        if (old.configured) sendApply(false)
    }

    private fun customThemeDialog() {
        val keys = listOf("background", "foreground", "cursor") + (0..15).map { "color$it" }
        val start = Themes.byId(if (work.themeId == "custom") "custom" else work.themeId)
        val values = mutableListOf(start.bg, start.fg, start.cursor).apply { addAll(start.ansi) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        val fields = ArrayList<EditText>()
        val base = Ui(this)
        base.label(box, "Start from", 0)
        base.spinner(box, Themes.ALL.map { it.name }, Themes.ALL.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { i ->
            val t = Themes.ALL[i]
            val vs = listOf(t.bg, t.fg, t.cursor) + t.ansi
            vs.forEachIndexed { k, v -> fields[k].setText(v) }
        }
        keys.forEachIndexed { i, k ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply { text = k; textSize = 12f }
            val e = EditText(this).apply { setText(values[i]); textSize = 13f; setSingleLine() }
            fields.add(e)
            row.addView(label, LinearLayout.LayoutParams(ui.dp(96), -2))
            row.addView(e, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row)
        }
        AlertDialog.Builder(this).setTitle("Custom colours (#RRGGBB)").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val v = fields.map { it.text.toString().trim() }
                if (v.all { runCatching { Color.parseColor(it) }.isSuccess }) {
                    Cfg.customTheme = v.joinToString(",")
                    work = work.copy(themeId = "custom")
                    pushLook(); show("look")
                } else ui.toast("Invalid colour: use #RRGGBB")
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- Security
    private fun securityTab(): View {
        val p = ui.page()
        ui.header(p, "PIN lock", 0)
        ui.check(p, "Require a PIN to use the app", Cfg.pinEnabled) { on ->
            if (on) picker(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET), REQ_SETPIN)
            else ui.confirm("Disable PIN", "Anyone with the phone will be able to open the app.", "Disable") { AppLock.clearPin(); show("sec") }
        }
        if (Cfg.pinEnabled) {
            ui.button(p, "Change PIN", 4) { picker(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET), REQ_SETPIN) }
            ui.button(p, if (Cfg.duressHash.isEmpty()) "Set a duress PIN…" else "Change the duress PIN…", 4) {
                picker(Intent(this, PinActivity::class.java).putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_DURESS), REQ_DURESS)
            }
            ui.label(p, "Lock again")
            val opts = listOf(0, 1, 5, 15, 60)
            ui.spinner(p, listOf("Whenever the app is left", "After 1 min", "After 5 min", "After 15 min", "After 1 hour"), opts.indexOf(Cfg.pinTimeoutMin).coerceAtLeast(0)) {
                Cfg.pinTimeoutMin = opts[it]
            }
        }

        ui.header(p, "Encryption & compression at rest")
        ui.note(p, "While a system is not in use it is stored as an archive: compressed (saves space with many systems) and/or encrypted with AES-256-GCM. It is unpacked when you start it and packed again when the session ends. Protects stopped systems against malware or backdoors in a running one, and against someone copying your files.")
        ui.label(p, "Compression")
        val comps = listOf("none", "fast", "best")
        ui.spinner(p, listOf("None", "Fast (gzip)", "Best (xz, slow)"), comps.indexOf(Cfg.compression).coerceAtLeast(0)) { i ->
            if (busy()) { show("sec"); return@spinner }
            ui.ensureKeyUnlocked {
                Cfg.compression = comps[i]
                val pol = Policy.current()
                ui.job("Re-packing systems", { prog ->
                    SystemStore.reconcile(pol, { KeyManager.dek() }, if (pol.encrypt) KeyManager.dek() else null, false, prog)
                }) { show("sec") }
            }
        }
        ui.check(p, "Encrypt systems at rest", Cfg.encryptAtRest) { on ->
            if (busy()) { show("sec"); return@check }
            if (on) enableEncryption() else disableEncryption()
        }
        if (Cfg.encryptAtRest) {
            ui.note(p, "Key: " + (if (KeyManager.mode() == "passphrase") "your passphrase (asked once per session; the key is forgotten from memory afterwards)" else "generated by the app, stored in the Android Keystore"))
            ui.button(p, "Change key type / passphrase…", 4) { changeKey() }
            ui.button(p, "Rotate the data key (re-encrypt everything)", 4) { rotateKey() }
        }
        ui.note(p, "proot is not a security boundary: malware running inside a session can read that session's files. Use VM mode for real isolation.")

        ui.header(p, "Privacy")
        ui.check(p, "Block screenshots and hide the app in Recents", Cfg.secureScreen) {
            Cfg.secureScreen = it
            if (it) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            sendApply(false)
        }
        ui.check(p, "Hide notification content on the lock screen", Cfg.hideNotifContent) { Cfg.hideNotifContent = it }
        ui.check(p, "Disable clipboard copy/paste with Android", Cfg.blockClipboard) { Cfg.blockClipboard = it }
        ui.check(p, "Clear the clipboard when the session ends", Cfg.clearClipboardOnExit) { Cfg.clearClipboardOnExit = it }
        ui.check(p, "End the session when the screen turns off", Cfg.closeOnScreenOff) { Cfg.closeOnScreenOff = it }
        ui.note(p, "Ending the session re-packs (and re-encrypts) the system, and forgets a passphrase key from memory.")

        ui.header(p, "Scheduled wipe")
        ui.note(p, "Irreversibly destroys the selected data when a condition is met. The key is destroyed first (crypto-shredding), then every file is overwritten with random data from a throw-away key, then deleted.")
        ui.check(p, "Enable scheduled wipe", Cfg.wipeEnabled) { on ->
            if (on) ui.confirm("Enable wipe", "Your data will be destroyed IRREVERSIBLY when a condition below is met. Make sure you have backups (Systems > Export).", "Enable") { Cfg.wipeEnabled = true; show("sec") }
            else { Cfg.wipeEnabled = false; show("sec") }
        }
        if (Cfg.wipeEnabled) {
            ui.label(p, "Wipe if the app is not opened for…")
            val hrs = listOf(0, 6, 12, 24, 72, 168, 336, 720)
            ui.spinner(p, listOf("Never", "6 hours", "12 hours", "1 day", "3 days", "7 days", "14 days", "30 days"), hrs.indexOf(Cfg.wipeInactivityHours).coerceAtLeast(0)) { Cfg.wipeInactivityHours = hrs[it] }
            ui.label(p, "Wipe after this many wrong PIN attempts")
            val tries = listOf(0, 3, 5, 10)
            ui.spinner(p, listOf("Never", "3", "5", "10"), tries.indexOf(Cfg.wipeFailedPins).coerceAtLeast(0)) { Cfg.wipeFailedPins = tries[it] }
            ui.check(p, "Wipe when the duress PIN is entered", Cfg.wipeOnDuress) { Cfg.wipeOnDuress = it }
            ui.check(p, "Wipe if USB debugging (ADB) is enabled", Cfg.wipeOnAdb) { Cfg.wipeOnAdb = it }
            val dl = if (Cfg.wipeDeadline > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(Cfg.wipeDeadline)) else "off"
            ui.button(p, "Wipe on a date: $dl", 6) { pickDeadline() }
            ui.header(p, "What to wipe", 10)
            ui.check(p, "Systems", Cfg.wipeSystems) { Cfg.wipeSystems = it }
            ui.check(p, "LiveCD persistence", Cfg.wipeLivecd) { Cfg.wipeLivecd = it }
            ui.check(p, "Snapshots", Cfg.wipeSnapshots) { Cfg.wipeSnapshots = it }
            ui.check(p, "Then fill the free space with random files and delete them (anti-forensics)", Cfg.wipeFillFree) { Cfg.wipeFillFree = it }
            ui.note(p, "The fill step can take a long time and temporarily leaves ~200 MB free. Conditions are checked about every 30 minutes by Android's job scheduler and when the app starts, so timing is approximate.")
        }
        ui.button(p, "Wipe now…", 10) {
            ui.confirm("Wipe now", "This destroys the selected data (see above; if scheduled wipe is off, systems, LiveCD persistence and snapshots) IRREVERSIBLY.", "Continue") {
                ui.prompt("Type WIPE to confirm", "WIPE") { t ->
                    if (t.trim() == "WIPE") {
                        startService(Intent(this, TerminalService::class.java).setAction(TerminalService.ACTION_WIPE).putExtra("reason", "manual").putExtra("force", true))
                        ui.toast("Wipe started (see the notification).")
                    } else ui.toast("Cancelled")
                }
            }
        }
        return p
    }

    private fun pickDeadline() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val cal = Calendar.getInstance(); cal.set(y, m, d, h, min, 0)
                Cfg.wipeDeadline = cal.timeInMillis; show("sec")
            }, 12, 0, true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            setButton(AlertDialog.BUTTON_NEUTRAL, "Clear") { _, _ -> Cfg.wipeDeadline = 0L; show("sec") }
        }.show()
    }

    private fun enableEncryption() {
        ui.choose("Key type", listOf("App-generated key (Android Keystore, nothing to remember)", "My passphrase (asked once per session)")) { m ->
            fun go(mode: String, pass: CharArray?) {
                ui.job("Encrypting systems", { prog ->
                    KeyManager.enable(mode, pass)
                    Cfg.encryptAtRest = true
                    val pol = Policy(true, Compression.of(Cfg.compression))
                    SystemStore.reconcile(pol, { KeyManager.dek() }, KeyManager.dek(), false, prog)
                }) { show("sec") }
            }
            if (m == 0) go("keystore", null) else ui.newPassphrase("Choose a passphrase") { go("passphrase", it) }
        }
    }

    private fun disableEncryption() {
        ui.confirm("Disable encryption", "All systems and snapshots will be decrypted and the key deleted.", "Disable") {
            ui.ensureKeyUnlocked {
                ui.job("Decrypting", { prog ->
                    val old = KeyManager.dek()
                    SystemStore.reconcile(Policy(false, Compression.of(Cfg.compression)), { old }, null, false, prog)
                    Cfg.encryptAtRest = false
                    KeyManager.destroy()
                }) { show("sec") }
            }
        }
    }

    private fun changeKey() {
        ui.ensureKeyUnlocked {
            ui.choose("Key type", listOf("App-generated key (Android Keystore)", "New passphrase")) { m ->
                if (m == 0) { KeyManager.rewrap("keystore", null); ui.toast("Key changed"); show("sec") }
                else ui.newPassphrase("New passphrase") { KeyManager.rewrap("passphrase", it); ui.toast("Passphrase changed"); show("sec") }
            }
        }
    }

    private fun rotateKey() {
        if (busy()) return
        fun go(pass: CharArray?) {
            ui.job("Rotating key", { prog ->
                val old = KeyManager.dek()
                val nw = KeyManager.newDek()
                SystemStore.reconcile(Policy(true, Compression.of(Cfg.compression)), { old }, nw, true, prog)
                if (pass != null) KeyManager.replaceDek(nw, "passphrase", pass) else KeyManager.replaceDek(nw)
            }) { show("sec") }
        }
        ui.confirm("Rotate key", "Every archive is re-encrypted with a new key. Do not interrupt this.", "Rotate") {
            if (KeyManager.mode() == "passphrase") {
                ui.prompt("Passphrase", "Current passphrase", password = true) { s ->
                    if (KeyManager.unlock(s.toCharArray())) go(s.toCharArray()) else ui.toast("Wrong passphrase")
                }
            } else go(null)
        }
    }

    // ---- Network
    private fun networkTab(): View {
        val p = ui.page()
        ui.header(p, "Network", 0)
        ui.check(p, "Allow network access", Cfg.allowNetwork) { on ->
            if (on) { Cfg.allowNetwork = true; NetBlockService.apply(this); show("net") }
            else {
                val c = NetBlockService.consentIntent(this)
                if (c != null) picker(c, REQ_VPN) else { Cfg.allowNetwork = false; NetBlockService.apply(this); show("net") }
            }
        }
        ui.note(p, "Block status: " + when {
            Cfg.allowNetwork -> "not needed (the network is allowed)"
            NetBlockService.active -> "ACTIVE"
            else -> "NOT ACTIVE: sessions refuse to start until it is (fail closed)"
        }, 6)
        ui.note(p, "When off: every process of this app, including your Debian systems, cannot reach the network (a local VPN with no exit silently drops their traffic; Android asks for VPN consent once). The VM gets no network device. The app itself will not download rootfs, check for updates, or open the browser.")
        ui.note(p, "For a guarantee that survives this app being killed, enable Android's \"Always-on VPN\" and \"Block connections without VPN\" for DebianLiveCD in the system VPN settings.")
        ui.note(p, "Android cannot remove the INTERNET permission at runtime. A build without it can be produced on request.")
        ui.check(p, "Give the VM a network device (when the network is allowed)", Cfg.vmNic) { Cfg.vmNic = it }
        return p
    }

    // ---- Updates
    private fun updatesTab(): View {
        val p = ui.page()
        ui.header(p, "Updates", 0)
        ui.note(p, "Current version: ${Updater.currentVersion(this)}. Source: github.com/${Updater.REPO} releases.")
        val modes = listOf("none", "manual", "auto")
        ui.radios(p, listOf("No updates (never contacts GitHub)", "Manual (opens the releases page in your browser, nothing else)", "Automatic (check, download and offer to install)"),
            modes.indexOf(Cfg.updateMode).coerceAtLeast(0)) { Cfg.updateMode = modes[it]; show("upd") }
        if (Cfg.updateMode == "auto") {
            ui.label(p, "Check every")
            val hrs = listOf(1, 2, 4, 8, 12, 24, 48, 168)
            ui.spinner(p, hrs.map { if (it < 24) "$it hours" else "${it / 24} day(s)" }, hrs.indexOf(Cfg.updateHours).let { if (it < 0) 3 else it }) { Cfg.updateHours = hrs[it] }
            ui.button(p, "Check now", 8) { checkNow() }
        }
        if (Cfg.updateMode == "manual") {
            ui.button(p, "Open the releases page", 8) {
                if (!Cfg.allowNetwork) ui.toast("The network is disabled (Network tab).")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.releasesPage())))
            }
        }
        if (Cfg.lastUpdateInfo.isNotEmpty()) ui.note(p, "Last check: ${Cfg.lastUpdateInfo}", 10)
        ui.note(p, "Android always asks you to confirm an install and only accepts an APK signed with the same key as the installed app. Debug builds (package …debug) are not updated by release APKs.")
        return p
    }

    private fun checkNow() {
        var rel: Updater.Release? = null
        var apk: java.io.File? = null
        ui.job("Checking for updates", { prog ->
            val r = Updater.latest()
            rel = r
            Cfg.lastUpdateCheck = System.currentTimeMillis()
            if (Updater.isNewer(r.tag, Updater.currentVersion(this)) && r.apkUrl != null) {
                apk = Updater.download(this, r) { prog(it, "Downloading ${r.tag}…") }
                Cfg.lastUpdateInfo = "Update ${r.tag} downloaded"
            } else Cfg.lastUpdateInfo = "Up to date (latest ${r.tag})"
        }) { e ->
            if (e == null) {
                val f = apk
                if (f != null) {
                    if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                        picker(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), REQ_UNKNOWN)
                    } else picker(Updater.installIntent(this, f), REQ_INSTALL)
                } else ui.toast(Cfg.lastUpdateInfo)
            }
            show("upd")
        }
    }

    // ------------------------------------------------------------------ results

    private fun queryName(uri: Uri): Pair<String, Long> {
        var n = "imported"
        var s = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i)
                val z = c.getColumnIndex(OpenableColumns.SIZE)
                if (z >= 0) s = c.getLong(z)
            }
        }
        return Pair(n, s)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PIN -> {
                pinShowing = false
                if (resultCode == RESULT_OK) { content.visibility = View.VISIBLE; AppLock.touch() } else finish()
            }
            REQ_SETPIN, REQ_DURESS -> show("sec")
            REQ_VPN -> {
                if (resultCode == RESULT_OK) { Cfg.allowNetwork = false; NetBlockService.apply(this) }
                show("net")
            }
            REQ_IMPORT -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    val (fn, size) = queryName(uri)
                    ui.prompt("Import system (file ${fmtBytes(size)}, free ${fmtBytes(filesDir.usableSpace)})", "Name", fn.substringBefore('.')) { name ->
                        ui.ensureKeyUnlocked {
                            ui.job("Importing", { prog ->
                                val ins = contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open the file")
                                val s = SystemStore.importFile(name.ifBlank { "Imported" }, ins, size, prog)
                                if (Cfg.activeSystem.isEmpty()) Cfg.activeSystem = s.id
                            }) { show("systems") }
                        }
                    }
                }
            }
            REQ_EXPORT -> {
                val uri = data?.data
                val pe = pendingExport
                if (resultCode == RESULT_OK && uri != null && pe != null) {
                    ui.job("Exporting ${pe.first.name}", { prog ->
                        prog(50, "Writing…")
                        val os = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("cannot write the file")
                        SystemStore.export(pe.first.id, os, pe.second)
                    }) { if (it == null) ui.toast("Exported") }
                }
                pendingExport = null
            }
            REQ_UNKNOWN, REQ_INSTALL -> show("upd")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            Cfg.storageAccess = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!Cfg.storageAccess) ui.toast("Permission denied.")
            show("env")
        }
    }

    companion object {
        private val TABS = listOf("env" to "Environment", "systems" to "Systems", "look" to "Appearance",
            "sec" to "Security", "net" to "Network", "upd" to "Updates")
        private const val REQ_PIN = 1
        private const val REQ_SETPIN = 2
        private const val REQ_DURESS = 3
        private const val REQ_IMPORT = 4
        private const val REQ_EXPORT = 5
        private const val REQ_VPN = 6
        private const val REQ_STORAGE = 7
        private const val REQ_UNKNOWN = 8
        private const val REQ_INSTALL = 9
    }
}
