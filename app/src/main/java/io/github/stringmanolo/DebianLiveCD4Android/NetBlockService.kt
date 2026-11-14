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
        reader?.interrupt()
        try { fd?.close() } catch (_: Exception) {}
        fd = null
    }

    override fun onDestroy() { stopBlocking(); super.onDestroy() }
    override fun onRevoke() { stopBlocking(); super.onRevoke() }

    companion object {
        const val ACTION_STOP = "io.github.stringmanolo.DebianLiveCD4Android.NETBLOCK_STOP"

        /** Null if consent was already given; otherwise an Intent to launch for the system dialog. */
        fun consentIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

        fun apply(ctx: Context) {
            val i = Intent(ctx, NetBlockService::class.java)
            if (Cfg.allowNetwork) i.action = ACTION_STOP
            ctx.startService(i)
        }
    }
}
