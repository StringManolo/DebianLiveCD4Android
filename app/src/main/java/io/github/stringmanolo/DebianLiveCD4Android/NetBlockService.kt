package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * "Network off": a local VPN that captures every packet of THIS app (and therefore of every Debian process running in
 * it, which shares the app's uid) and silently drops it. No permission is revoked (Android cannot do that at runtime);
 * the traffic simply has nowhere to go. Needs the one-time system VPN consent. For a guarantee that survives this
 * service dying, enable Android's "Always-on VPN" + "Block connections without VPN" for this app.
 */
class NetBlockService : VpnService() {
    private var fd: ParcelFileDescriptor? = null
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || Cfg.allowNetwork) {
            stopBlocking()
            stopSelf()
            return START_NOT_STICKY
        }
        startBlocking()
        return START_STICKY
    }

    private fun startBlocking() {
        if (fd != null) return
        try {
            val p = Builder()
                .setSession("DebianLiveCD: network blocked")
                .addAddress("10.255.0.1", 32)
                .addAddress("fd00:255::1", 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addAllowedApplication(packageName)
                .setBlocking(true)
                .establish()
            fd = p
            active = p != null
            if (p != null) {
                reader = Thread({
                    val buf = ByteArray(32767)
                    try { FileInputStream(p.fileDescriptor).use { while (it.read(buf) >= 0) { /* drop */ } } } catch (_: Exception) {}
                }, "netblock").also { it.isDaemon = true; it.start() }
            } else {
                Log.w("NetBlock", "VPN consent missing")
            }
        } catch (e: Exception) {
            Log.w("NetBlock", "cannot start: $e")
        }
    }

    private fun stopBlocking() {
        val was = active
        active = false
        reader?.interrupt()
        try { fd?.close() } catch (_: Exception) {}
        fd = null
        if (was && !Cfg.allowNetwork) onLost?.invoke()   // someone (or the system) killed the block while it must be on
    }

    override fun onDestroy() { stopBlocking(); super.onDestroy() }
    override fun onRevoke() { stopBlocking(); super.onRevoke() }

    companion object {
        /** True only while the VPN interface really exists. */
        @Volatile var active = false
            private set

        /** Called when the block disappears although the network must stay off (killed, revoked by another VPN...). */
        @Volatile var onLost: (() -> Unit)? = null

        const val ACTION_STOP = "io.github.stringmanolo.DebianLiveCD4Android.NETBLOCK_STOP"

        /** Null if consent was already given; otherwise an Intent to launch for the system dialog. */
        fun consentIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

        fun apply(ctx: Context) {
            val i = Intent(ctx, NetBlockService::class.java)
            if (Cfg.allowNetwork) i.action = ACTION_STOP
            ctx.startService(i)
        }

        /**
         * Fail closed: with the network disabled, a session must not start unless the block really exists.
         * Blocks up to [timeoutMs] (call from a worker thread). Throws with the reason otherwise.
         */
        fun requireActive(ctx: Context, timeoutMs: Long = 5000) {
            if (Cfg.allowNetwork || active) return
            if (consentIntent(ctx) != null) {
                throw IllegalStateException(
                    "The network is disabled in Settings but the VPN permission is missing, so it cannot be enforced. " +
                        "Open Settings > Network, enable and disable the network option again, and accept the VPN dialog."
                )
            }
            apply(ctx)
            val end = System.currentTimeMillis() + timeoutMs
            while (!active && System.currentTimeMillis() < end) Thread.sleep(100)
            if (!active) {
                throw IllegalStateException(
                    "The network is disabled in Settings but the network block could not be started " +
                        "(another VPN may be active). Refusing to start the session without it."
                )
            }
        }
    }
}
