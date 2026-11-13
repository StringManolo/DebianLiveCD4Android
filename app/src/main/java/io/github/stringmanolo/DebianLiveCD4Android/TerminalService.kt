package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Foreground service: prepares the environment (install / extract / copy / VM feeder), owns the terminal
 * session so it survives the Activity, hosts the floating window, and cleans up (wipes ephemeral data).
 */
class TerminalService : Service(), TerminalSessionClient, FloatingWindow.Host {

    interface Listener {
        fun onProgress(percent: Int, message: String)
        /** Fullscreen presentation: attach the session to the Activity's view. */
        fun onSessionReady(session: TerminalSession)
        /** The floating window took over; the Activity can close. */
        fun onFloatingShown()
        fun onNeedOverlayPermission()
        fun onFailed(message: String)
        fun onScreenChanged()
        fun onColorsChanged()
        fun onSessionFinished()
        fun onAppearanceChanged(cfg: AppConfig)
    }

    inner class LocalBinder : Binder() { val service: TerminalService get() = this@TerminalService }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())

    var session: TerminalSession? = null
        private set
    var listener: Listener? = null
    @Volatile var preparing = false
        private set
    @Volatile var lastProgress: Pair<Int, String>? = null
        private set
    @Volatile var lastError: String? = null
        private set

    private var cleanup: (() -> Unit)? = null
    private var floating: FloatingWindow? = null
    private var minimized = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> exitAll()
            ACTION_MINIMIZE -> hostMinimize()
            ACTION_RESTORE -> { minimized = false; present(true) }
            ACTION_APPLY -> applyConfig(intent.getBooleanExtra(EXTRA_RESTART, false))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        floating?.hide()
        session?.finishIfRunning()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ lifecycle

    /** (Re)starts the environment described by [cfg]. Any running session is stopped first. */
    fun launch(cfg: AppConfig) {
        if (preparing) return
        stopCurrent()
        preparing = true
        lastError = null
        lastProgress = 0 to "Preparing…"
        updateNotification()
        Thread {
            var plan: Plan? = null
            try {
                val p = SessionPlanner.prepare(this, cfg) { pct, msg ->
                    lastProgress = pct to msg
                    main.post { listener?.onProgress(pct, msg) }
                }
                plan = p
                main.post {
                    try {
                        val s = p.createSession(this)
                        Themes.apply(Themes.byId(cfg.themeId), s)
                        session = s
                        cleanup = p.cleanup
                        preparing = false
                        lastProgress = null
                        minimized = false
                        present(false)
                    } catch (t: Throwable) {
                        fail(p, t)
                    }
                }
            } catch (t: Throwable) {
                val pl = plan
                main.post { fail(pl, t) }
            }
        }.start()
    }

    private fun fail(plan: Plan?, t: Throwable) {
        Log.e("TerminalService", "launch failed", t)
        preparing = false
        lastProgress = null
        lastError = t.message ?: t.javaClass.simpleName
        cleanup = plan?.cleanup
        runCleanup {}
        updateNotification()
        listener?.onFailed(lastError!!)
    }

    private fun stopCurrent(then: () -> Unit = {}) {
        val s = session
        session = null // late callbacks of the old session are ignored (identity check)
        s?.finishIfRunning()
        runCleanup(then)
    }

    /** Runs the pending cleanup on a worker thread (wiping can take a while), then [then] on the main thread. */
    private fun runCleanup(then: () -> Unit) {
        val c = cleanup
        cleanup = null
        if (c == null) { then(); return }
        Thread {
            try { c() } catch (t: Throwable) { Log.w("TerminalService", "cleanup failed: $t") }
            main.post { then() }
        }.start()
    }

    private fun exitAll() {
        floating?.hide()
        floating = null
        minimized = false
        listener?.onSessionFinished()
        stopCurrent { stopForeground(true); stopSelf() }
    }

    /** Called by the Activity when it goes away: don't leave an idle foreground service behind. */
    fun stopIfIdle() {
        if (session == null && !preparing) { stopForeground(true); stopSelf() }
    }

    // ------------------------------------------------------------------ presentation

    /**
     * Shows the running session according to the window mode: attaches it to the Activity (fullscreen)
     * or opens the floating window. [restore] also brings back a minimized floating window.
     */
    fun present(restore: Boolean) {
        val s = session ?: return
        val cfg = AppConfig.load(this)
        if (cfg.windowMode == WindowMode.FLOATING) {
            if (!Settings.canDrawOverlays(this)) { listener?.onNeedOverlayPermission(); return }
            if (restore) minimized = false
            val fw = floating ?: FloatingWindow(this, this).also { floating = it }
            fw.applyAppearance(cfg)
            if (!minimized) fw.show(s)
            updateNotification()
            listener?.onFloatingShown()
        } else {
            floating?.hide()
            minimized = false
            updateNotification()
            listener?.onSessionReady(s)
        }
    }

    /** Settings screen applied a new configuration (already saved to prefs). */
    private fun applyConfig(restart: Boolean) {
        val cfg = AppConfig.load(this)
        if (restart) {
            launch(cfg)
            return
        }
        Themes.apply(Themes.byId(cfg.themeId), session)
        floating?.applyAppearance(cfg)
        listener?.onAppearanceChanged(cfg)
        if (session != null) present(false)
    }

    fun changeFont(delta: Int) {
        val c = AppConfig.load(this)
        val n = c.copy(fontSp = (c.fontSp + delta).coerceIn(6, 40))
        if (n.fontSp == c.fontSp) return
        n.save(this)
        floating?.applyAppearance(n)
        listener?.onAppearanceChanged(n)
    }

    // ------------------------------------------------------------------ FloatingWindow.Host

    override fun hostMinimize() {
        floating?.hide()
        minimized = true
        updateNotification()
    }

    override fun hostExit() = exitAll()

    override fun hostSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun hostFont(delta: Int) = changeFont(delta)

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Terminal session", NotificationManager.IMPORTANCE_LOW))
        }
        fun svc(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, TerminalService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val settings = PendingIntent.getActivity(
            this, 1, Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val floatingMode = AppConfig.load(this).windowMode == WindowMode.FLOATING && session != null
        val text = when {
            preparing -> "Preparing…"
            session == null -> "Idle"
            minimized -> "Window minimized"
            else -> "Session running"
        }
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setContentTitle("DebianLiveCD")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(if (minimized) svc(2, ACTION_RESTORE) else open)
            .setOngoing(true)
        if (floatingMode) {
            if (minimized) b.addAction(android.R.drawable.ic_menu_view, "Restore", svc(2, ACTION_RESTORE))
            else b.addAction(android.R.drawable.ic_menu_revert, "Minimize", svc(3, ACTION_MINIMIZE))
        }
        b.addAction(android.R.drawable.ic_menu_preferences, "Settings", settings)
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", svc(4, ACTION_EXIT))
        return b.build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    // ------------------------------------------------------------------ TerminalSessionClient

    override fun onTextChanged(changedSession: TerminalSession) {
        listener?.onScreenChanged()
        floating?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== session) return // stale session we already replaced
        session = null
        floating?.hide()
        floating = null
        minimized = false
        listener?.onSessionFinished()
        runCleanup { stopForeground(true); stopSelf() }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).coerceToText(this).toString()
            if (t.isNotEmpty()) (session ?: this.session)?.emulator?.paste(t)
        }
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {
        listener?.onColorsChanged()
        floating?.invalidateColors()
    }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "error", e) }

    companion object {
        const val CHANNEL = "terminal"
        const val NOTIF_ID = 1
        const val ACTION_EXIT = "io.github.stringmanolo.DebianLiveCD4Android.EXIT"
        const val ACTION_MINIMIZE = "io.github.stringmanolo.DebianLiveCD4Android.MINIMIZE"
        const val ACTION_RESTORE = "io.github.stringmanolo.DebianLiveCD4Android.RESTORE"
        const val ACTION_APPLY = "io.github.stringmanolo.DebianLiveCD4Android.APPLY"
        const val EXTRA_RESTART = "restart"
    }
}
