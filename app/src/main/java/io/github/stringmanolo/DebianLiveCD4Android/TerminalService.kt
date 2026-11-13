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
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Foreground service that prepares the environment (install / extract / copy / VM feeder), owns the
 * terminal session so it survives the Activity, and cleans up (wipes ephemeral data) afterwards.
 */
class TerminalService : Service(), TerminalSessionClient {

    interface Listener {
        fun onProgress(percent: Int, message: String)
        fun onSessionReady(session: TerminalSession)
        fun onFailed(message: String)
        fun onScreenChanged()
        fun onColorsChanged()
        fun onSessionFinished()
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            listener?.onSessionFinished()
            stopCurrent { stopForeground(true); stopSelf() } // stays foreground until cleanup/wipe is done
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    /** (Re)starts the environment described by [cfg]. Any running session is stopped first. */
    fun launch(cfg: AppConfig) {
        if (preparing) return
        stopCurrent()
        preparing = true
        lastError = null
        lastProgress = 0 to "Preparing…"
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
                        session = s
                        cleanup = p.cleanup
                        preparing = false
                        lastProgress = null
                        listener?.onSessionReady(s)
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
        lastError = "${t.javaClass.simpleName}: ${t.message}"
        cleanup = plan?.cleanup
        runCleanup {}
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

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Terminal session", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setContentTitle("DebianLiveCD")
            .setContentText("Session running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exit)
        startForeground(1, b.build())
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) { listener?.onScreenChanged() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (finishedSession !== session) return // stale session we already replaced
        session = null
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
    override fun onColorsChanged(session: TerminalSession) { listener?.onColorsChanged() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
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
        const val ACTION_EXIT = "io.github.stringmanolo.DebianLiveCD4Android.EXIT"
    }
}
