package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

class MainActivity : Activity(), TerminalService.Listener {

    private lateinit var rootView: View
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var installPanel: View
    private lateinit var installStatus: TextView
    private lateinit var installProgress: ProgressBar
    private lateinit var panelAction: Button
    private lateinit var btnSettings: TextView

    private var service: TerminalService? = null
    private var bound = false
    private var promptedFirstRun = false
    private var waitingOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootView = findViewById(R.id.root)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.extra_keys)
        installPanel = findViewById(R.id.install_panel)
        installStatus = findViewById(R.id.install_status)
        installProgress = findViewById(R.id.install_progress)
        panelAction = findViewById(R.id.btn_panel_action)
        btnSettings = findViewById(R.id.btn_settings)
        btnSettings.setOnClickListener { openSettings() }

        terminalView.setTerminalViewClient(TermViewClient({ extraKeys }, { showKeyboard() }, { d -> service?.changeFont(d) }))
        extraKeys.sessionProvider = { service?.session }
        applyAppearance(AppConfig.load(this)) // must run before a session is attached

        val i = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        terminalView.onScreenUpdated()
        if (waitingOverlay && Settings.canDrawOverlays(this)) {
            waitingOverlay = false
            service?.present(true)
        }
    }

    override fun onDestroy() {
        service?.let { if (it.listener === this) it.listener = null }
        if (isFinishing) service?.stopIfIdle()
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            val cfg = AppConfig.load(this@MainActivity)
            val cur = s.session
            when {
                !cfg.configured -> {
                    // nothing is installed or started before the user chooses
                    showPanel(0, "Welcome. Choose how to start: System, LiveCD or VM.", "Choose environment") { openSettings() }
                    if (!promptedFirstRun) { promptedFirstRun = true; openSettings() }
                }
                s.preparing -> {
                    val p = s.lastProgress
                    showPanel(p?.first ?: 0, p?.second ?: "Preparing…")
                }
                cur != null && cur.isRunning -> s.present(true)
                s.lastError != null -> onFailed(s.lastError!!)
                else -> {
                    showPanel(0, "Preparing…")
                    s.launch(cfg)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    private fun attachFullscreen(session: TerminalSession) {
        installPanel.visibility = View.GONE
        terminalView.attachSession(session)
        terminalView.requestFocus()
    }

    private fun showPanel(pct: Int, msg: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        installPanel.visibility = View.VISIBLE
        installProgress.progress = pct
        installProgress.visibility = if (actionLabel == null) View.VISIBLE else View.GONE
        installStatus.text = msg
        if (actionLabel != null && action != null) {
            panelAction.text = actionLabel
            panelAction.visibility = View.VISIBLE
            panelAction.setOnClickListener { action() }
        } else {
            panelAction.visibility = View.GONE
        }
    }

    private fun applyAppearance(cfg: AppConfig) {
        val th = Themes.byId(cfg.themeId)
        rootView.setBackgroundColor(th.bgInt)
        installPanel.setBackgroundColor(th.bgInt)
        installStatus.setTextColor(th.fgInt)
        btnSettings.setTextColor(th.fgInt)
        window.statusBarColor = th.bgInt
        extraKeys.applyTheme(th.bgInt, th.fgInt)
        terminalView.setTextSize((cfg.fontSp * resources.displayMetrics.scaledDensity).toInt())
        terminalView.setTypeface(
            if (cfg.fontBold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        )
        terminalView.onScreenUpdated()
    }

    private fun showKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    // ---- TerminalService.Listener (always called on the main thread) ----
    override fun onProgress(percent: Int, message: String) = showPanel(percent, message)
    override fun onSessionReady(session: TerminalSession) = attachFullscreen(session)
    override fun onFloatingShown() { finish() }
    override fun onNeedOverlayPermission() {
        waitingOverlay = true
        showPanel(
            0,
            "Floating window mode needs the \"Display over other apps\" permission.",
            "Grant permission"
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }
    override fun onFailed(message: String) {
        showPanel(0, "Could not start:\n$message\n\nOpen ⚙ to change the environment.", "Retry") {
            showPanel(0, "Preparing…")
            service?.launch(AppConfig.load(this))
        }
    }
    override fun onScreenChanged() { terminalView.onScreenUpdated() }
    override fun onColorsChanged() { terminalView.invalidate() }
    override fun onSessionFinished() { finish() }
    override fun onAppearanceChanged(cfg: AppConfig) = applyAppearance(cfg)
}
