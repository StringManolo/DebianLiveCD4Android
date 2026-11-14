package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Update check against the GitHub releases of the project. Modes (Settings > Updates):
 *  none   nothing, ever
 *  manual the releases page is opened in the browser (only when the network is allowed); nothing else
 *  auto   every N hours (default 8) the latest release is fetched; if newer, the APK is downloaded and an install is offered
 * Android always asks for confirmation to install, and only accepts an APK signed with the same key as the installed app.
 */
object Updater {
    const val REPO = "StringManolo/DebianLiveCD4Android"
    class Release(val tag: String, val page: String, val apkUrl: String?, val apkName: String?)

    fun releasesPage() = "https://github.com/$REPO/releases"

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun latest(): Release {
        val j = JSONObject(Downloader.text("https://api.github.com/repos/$REPO/releases/latest"))
        val assets = j.optJSONArray("assets")
        var url: String? = null
        var name: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val n = a.optString("name")
                if (n.endsWith(".apk") && (url == null || n.contains("release", true))) {
                    url = a.optString("browser_download_url"); name = n
                }
            }
        }
        return Release(j.optString("tag_name"), j.optString("html_url", releasesPage()), url, name)
    }

    fun isNewer(tag: String, current: String): Boolean {
        fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()
        val a = nums(tag)
        val b = nums(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun download(ctx: Context, r: Release, progress: (Int) -> Unit): File {
        val url = r.apkUrl ?: throw IOException("the release has no APK attached")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val c = Downloader.open(url)
        val total = c.contentLengthLong
        var done = 0L
        c.inputStream.use { i ->
            FileOutputStream(out).use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        return out
    }

    fun installIntent(ctx: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Called from the background job. */
    fun autoCheckIfDue(ctx: Context) {
        if (Cfg.updateMode != "auto" || !Cfg.allowNetwork) return
        val now = System.currentTimeMillis()
        if (now - Cfg.lastUpdateCheck < Cfg.updateHours.coerceAtLeast(1) * 3_600_000L) return
        Cfg.lastUpdateCheck = now
        try {
            val r = latest()
            if (isNewer(r.tag, currentVersion(ctx))) {
                Cfg.lastUpdateInfo = "Update ${r.tag} available"
                if (r.apkUrl != null) {
                    val apk = download(ctx, r) {}
                    notifyInstall(ctx, apk, r.tag)
                }
            } else {
                Cfg.lastUpdateInfo = "Up to date (latest ${r.tag})"
            }
        } catch (e: Exception) {
            Cfg.lastUpdateInfo = "Check failed: ${e.message}"
        }
    }

    private fun notifyInstall(ctx: Context, apk: File, tag: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(ctx, 7, installIntent(ctx, apk), PendingIntent.FLAG_UPDATE_CURRENT)
        val b: Notification.Builder =
            if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "updates") else Notification.Builder(ctx)
        b.setContentTitle("DebianLiveCD update $tag downloaded")
            .setContentText("Tap to install")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
        nm.notify(7, b.build())
    }
}
