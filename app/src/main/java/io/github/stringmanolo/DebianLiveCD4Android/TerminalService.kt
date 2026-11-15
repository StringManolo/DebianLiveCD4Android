package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Foreground service: prepares the environment (unlock / extract / copy / VM feeder), owns the terminal session so
 * it survives the Activity, hosts the floating window, enforces the privacy options, and cleans up afterwards
 * (re-packs systems, wipes ephemeral data, forgets keys).
 */
class TerminalService : Service(), TerminalSessionClient, FloatingWindow.Host {

    interface Listener {
        fun onProgress(percent: Int, message: String)
        /** Fullscreen presentation: attach the session to the Activity's view. */
        fun onSessionReady(session: TerminalSession)
        /** The floating window took over; the Activity can close. */
        fun onFloatingShown()
        fun onNeedOverlayPermission()
        fun onNeedPassphrase()
        fun onNeedUnlock()
        fun onFailed(message: String)
        fun onScreenChanged()
        fun onColorsChanged()
        /** The session ended by itself (exit typed, crash, VM powered off...). The app stays open. */
        fun onSessionEnded(message: String)
        /** The user asked to leave (notification / window Exit): the app may close. */
        fun onExitRequested()
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
    private var cleanupThread: Thread? = null
    private var floating: FloatingWindow? = null
    private var minimized = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wipeStatus: String? = null
    private var sessionStart = 0L
    private var pendingEndReason: String? = null

    private val netWatch = object : Runnable {
        override fun run() {
            if (session != null && !Cfg.allowNetwork && !NetBlockService.active) netLost()
            if (session != null) main.postDelayed(this, 2000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Cfg.closeOnScreenOff && session != null) exitAll()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        NetBlockService.onLost = { main.post { netLost() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT -> exitAll()
            ACTION_MINIMIZE -> hostMinimize()
            ACTION_RESTORE -> { minimized = false; present(true) }
            ACTION_APPLY -> applyConfig(intent.getBooleanExtra(EXTRA_RESTART, false))
            ACTION_WIPE -> startWipe(intent.getBooleanExtra("force", false))
            ACTION_RESET_WINDOW -> {
                FloatState.defaults(this).save(this)
                floating?.resetToDefaults()
                if (minimized) { minimized = false; present(true) }
                // started only for this: don't leave an idle foreground service behind
                if (session == null && !preparing && listener == null) { stopForeground(true); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        NetBlockService.onLost = null
        floating?.hide()
        releaseWake()
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
        Cfg.lastOpened = System.currentTimeMillis()
        Cfg.autoLaunchBlocked = false
        try { NetBlockService.apply(this) } catch (_: Exception) {}
        updateNotification()
        val waitFor = cleanupThread
        Thread {
            var plan: Plan? = null
            try {
                waitFor?.join() // never prepare while the previous session is still being re-packed
                NetBlockService.requireActive(this) // network off => the block must exist before anything runs
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
                        Cfg.sessionRunning = true
                        sessionStart = SystemClock.elapsedRealtime()
                        acquireWake()
                        if (!Cfg.allowNetwork) main.postDelayed(netWatch, 2000)
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
        Cfg.autoLaunchBlocked = true
        Cfg.lastEnded = "Could not start: $lastError"
        cleanup = plan?.cleanup
        runCleanup {}
        updateNotification()
        if (t is KeyLockedException) listener?.onNeedPassphrase() else listener?.onFailed(lastError!!)
    }

    private fun stopCurrent(then: () -> Unit = {}) {
        val s = session
        session = null // late callbacks of the old session are ignored (identity check)
        s?.finishIfRunning()
        markSessionOver()
        runCleanup(then)
    }

    private fun markSessionOver() {
        Cfg.sessionRunning = false
        releaseWake()
        if (Cfg.clearClipboardOnExit) clearClipboard()
    }

    /** Runs the pending cleanup on a worker thread (re-packing / wiping can take a while), then [then] on the main thread. */
    private fun runCleanup(then: () -> Unit) {
        val c = cleanup
        cleanup = null
        val prev = cleanupThread
        if (c == null && (prev == null || !prev.isAlive)) { then(); return }
        val t = Thread {
            try { prev?.join() } catch (_: InterruptedException) {}
            try { c?.invoke() } catch (t: Throwable) { Log.w("TerminalService", "cleanup failed: $t") }
            main.post {
                if (session == null && !preparing) KeyManager.lock()
                then()
            }
        }
        cleanupThread = t
        t.start()
    }

    private fun exitAll() {
        floating?.hide()
        floating = null
        minimized = false
        listener?.onExitRequested()
        stopCurrent { stopForeground(true); stopSelf() }
    }

    /** Called by the Activity when it goes away: don't leave an idle foreground service behind. */
    fun stopIfIdle() {
        val packing = cleanupThread?.isAlive == true
        if (session == null && !preparing && wipeStatus == null && !packing) { stopForeground(true); stopSelf() }
    }

    private fun startWipe(force: Boolean) {
        if (WipeManager.running || wipeStatus != null) return
        if (!force && !Cfg.wipeEnabled) return
        floating?.hide()
        floating = null
        minimized = false
        wipeStatus = "Wiping…"
        listener?.onExitRequested()
        stopCurrent {
            Thread {
                try {
                    WipeManager.execute(this) { pct, msg ->
                        wipeStatus = "$msg $pct%"
                        main.post { updateNotification() }
                    }
                } catch (t: Throwable) {
                    Log.w("TerminalService", "wipe failed: $t")
                }
                main.post { wipeStatus = null; Cfg.failedPins = 0; stopForeground(true); stopSelf() }
            }.start()
        }
    }

    // ------------------------------------------------------------------ network block watchdog

    /** The VPN block vanished while the network must be off: try to restore it, otherwise close the session (fail closed). */
    private fun netLost() {
        val s = session ?: return
        if (Cfg.allowNetwork) return
        try { NetBlockService.apply(this) } catch (_: Exception) {}
        main.postDelayed({
            if (!Cfg.allowNetwork && !NetBlockService.active && session === s) {
                pendingEndReason = "The network block was lost (killed, or replaced by another VPN), so the session was closed " +
                    "to avoid leaking traffic."
                s.finishIfRunning()
            }
        }, 3000)
    }

    private fun postEndedNotification(msg: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("ended", "Session ended", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            this, 5, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "ended") else Notification.Builder(this)
        b.setContentTitle("DebianLiveCD")
            .setContentText(if (Cfg.hideNotifContent) "Session ended" else msg)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(open)
            .setAutoCancel(true)
        nm.notify(5, b.build())
    }

    // ------------------------------------------------------------------ wake lock / clipboard

    private fun acquireWake() {
        if (!Cfg.wakeLock || wakeLock != null) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DebianLiveCD:session").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWake() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun clearClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ presentation

    /**
     * Shows the running session according to the window mode: attaches it to the Activity (fullscreen)
     * or opens the floating window. [restore] also brings back a minimized floating window.
     */
    fun present(restore: Boolean) {
        val s = session ?: return
        if (AppLock.isLocked()) {
            if (listener != null) listener?.onNeedUnlock()
            else startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Cfg.lastOpened = System.currentTimeMillis()
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
        updateNotification()
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
            wipeStatus != null -> wipeStatus!!
            preparing -> "Preparing…"
            session == null -> "Idle"
            minimized -> "Window minimized"
            else -> "Session running"
        }
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setContentTitle("DebianLiveCD")
            .setContentText(if (Cfg.hideNotifContent) "Running" else text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(if (minimized) svc(2, ACTION_RESTORE) else open)
            .setOngoing(true)
        if (Cfg.hideNotifContent) b.setVisibility(Notification.VISIBILITY_SECRET)
        if (wipeStatus == null) {
            if (floatingMode) {
                if (minimized) b.addAction(android.R.drawable.ic_menu_view, "Restore", svc(2, ACTION_RESTORE))
                else b.addAction(android.R.drawable.ic_menu_revert, "Minimize", svc(3, ACTION_MINIMIZE))
            }
            b.addAction(android.R.drawable.ic_menu_preferences, "Settings", settings)
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", svc(4, ACTION_EXIT))
        }
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
        val secs = (SystemClock.elapsedRealtime() - sessionStart) / 1000
        val reason = pendingEndReason
        pendingEndReason = null
        val msg = reason ?: "Session ended after ${if (secs >= 60) "${secs / 60} min" else "$secs s"} (exit status ${finishedSession.exitStatus})."
        session = null
        floating?.hide()
        floating = null
        minimized = false
        markSessionOver()
        // a session that dies within seconds (bad VM, missing system...) must not be relaunched in a loop
        if (reason != null || secs < 15) Cfg.autoLaunchBlocked = true
        Cfg.lastEnded = msg
        listener?.onProgress(50, "Saving and protecting the system…")
        runCleanup {
            // the app stays open so the user can read what happened, restart, or change the environment
            if (listener != null) {
                updateNotification()
                listener?.onSessionEnded(msg)
            } else {
                postEndedNotification(msg)
                stopForeground(true)
                stopSelf()
            }
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (Cfg.blockClipboard) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (Cfg.blockClipboard) return
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
        const val ACTION_WIPE = "io.github.stringmanolo.DebianLiveCD4Android.WIPE"
        const val ACTION_RESET_WINDOW = "io.github.stringmanolo.DebianLiveCD4Android.RESET_WINDOW"
        const val EXTRA_RESTART = "restart"
    }
}
