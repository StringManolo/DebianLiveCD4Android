package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : Activity(), TerminalService.Listener {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var installPanel: View
    private lateinit var installStatus: TextView
    private lateinit var installProgress: ProgressBar

    private var service: TerminalService? = null
    private var bound = false
    private var fontSp = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.extra_keys)
        installPanel = findViewById(R.id.install_panel)
        installStatus = findViewById(R.id.install_status)
        installProgress = findViewById(R.id.install_progress)
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }

        terminalView.setTextSize(sp(fontSp)) // must be set before a session is attached
        terminalView.setTerminalViewClient(viewClient)
        extraKeys.sessionProvider = { service?.session }

        val i = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bound = bindService(i, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        terminalView.onScreenUpdated()
    }

    override fun onDestroy() {
        service?.listener = null
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS && resultCode == RESULT_OK) {
            showPanel(0, "Restarting session…")
            service?.launch(AppConfig.load(this))
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val s = (binder as TerminalService.LocalBinder).service
            service = s
            s.listener = this@MainActivity
            val cur = s.session
            when {
                cur != null && cur.isRunning -> attach(cur)
                s.preparing -> s.lastProgress?.let { showPanel(it.first, it.second) }
                else -> {
                    showPanel(0, "Preparing…")
                    s.launch(AppConfig.load(this@MainActivity))
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private fun attach(session: TerminalSession) {
        installPanel.visibility = View.GONE
        terminalView.attachSession(session)
        terminalView.requestFocus()
    }

    private fun showPanel(pct: Int, msg: String) {
        installPanel.visibility = View.VISIBLE
        installProgress.progress = pct
        installStatus.text = msg
    }

    // ---- TerminalService.Listener (always called on the main thread) ----
    override fun onProgress(percent: Int, message: String) = showPanel(percent, message)
    override fun onSessionReady(session: TerminalSession) = attach(session)
    override fun onFailed(message: String) {
        showPanel(0, "Failed:\n$message\n\nOpen ⚙ to change the environment.")
    }
    override fun onScreenChanged() { terminalView.onScreenUpdated() }
    override fun onColorsChanged() { terminalView.invalidate() }
    override fun onSessionFinished() { finish() }

    // ---- helpers ----
    private fun sp(v: Int) = (v * resources.displayMetrics.scaledDensity).toInt()

    private fun showKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                fontSp = (fontSp + if (scale > 1f) 1 else -1).coerceIn(6, 40)
                terminalView.setTextSize(sp(fontSp))
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = extraKeys.consumeCtrl()
        override fun readAltKey() = extraKeys.consumeAlt()
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "error", e) }
    }

    companion object { private const val REQ_SETTINGS = 1 }
}
