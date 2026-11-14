package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Scheduled / on-demand wipe. Order matters:
 *  1. if everything that is encrypted is being wiped, destroy the key first (crypto-shredding: every archive becomes
 *     unreadable at once, even if the overwrite below is cut short);
 *  2. overwrite every file in place with CSPRNG bytes from a throw-away AES key ("encrypt with a random key") and delete it;
 *  3. optionally fill the free space with random files so the filesystem reuses the freed blocks (anti-forensics).
 * On flash storage (F2FS, wear levelling, TRIM) in-place overwrite is best effort; crypto-shredding is the strong part.
 */
object WipeManager {
    @Volatile var running = false
        private set

    /** Returns a human-readable reason if a wipe condition is met, else null. */
    fun checkConditions(ctx: Context): String? {
        if (!Cfg.wipeEnabled) return null
        val now = System.currentTimeMillis()
        if (Cfg.lastOpened == 0L) Cfg.lastOpened = now
        if (Cfg.wipeDeadline > 0 && now >= Cfg.wipeDeadline) return "the configured date was reached"
        val h = Cfg.wipeInactivityHours
        if (h > 0 && !Cfg.sessionRunning && now - Cfg.lastOpened > h * 3_600_000L) return "the app was not opened for $h hours"
        if (Cfg.wipeOnAdb && try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (e: Exception) { false }) {
            return "USB debugging is enabled"
        }
        return null
    }

    fun execute(ctx: Context, progress: (Int, String) -> Unit) {
        if (running) return
        running = true
        try {
            progress(0, "Wiping…")
            val all = Cfg.wipeSystems && Cfg.wipeLivecd && Cfg.wipeSnapshots
            if (all && KeyManager.hasKey()) KeyManager.destroy()

            val systems = SystemStore.list()
            systems.forEachIndexed { i, s ->
                progress(5 + i * 60 / systems.size.coerceAtLeast(1), "Wiping ${s.name}…")
                val inCategory = if (s.kind == "livecd") Cfg.wipeLivecd else Cfg.wipeSystems
                val dir = SystemStore.dir(s.id)
                if (inCategory) {
                    dir.listFiles()?.forEach { c ->
                        val keep = !Cfg.wipeSnapshots && (c.name == "snapshots" || c.name == "meta.json")
                        if (!keep) FsUtil.wipeTree(c, WipeMode.RANDOM)
                    }
                    if (Cfg.wipeSnapshots) dir.delete()
                    if (s.id == Cfg.activeSystem) Cfg.activeSystem = ""
                } else if (Cfg.wipeSnapshots) {
                    FsUtil.wipeTree(SystemStore.snapDir(s.id), WipeMode.RANDOM)
                }
            }
            // temporary material
            Paths.ephemeralBase(ctx).listFiles()?.forEach { FsUtil.wipeTree(it, WipeMode.RANDOM) }
            Paths.tmpDir(ctx).listFiles()?.forEach { FsUtil.wipeTree(it, WipeMode.RANDOM) }
            File(ctx.filesDir, "rootfs").let { if (it.exists()) FsUtil.wipeTree(it, WipeMode.RANDOM) }

            if (Cfg.wipeFillFree) FreeSpaceFill.run(ctx.filesDir) { pct, m -> progress(65 + pct * 35 / 100, m) }

            Cfg.wipeDeadline = 0L
            Cfg.lastOpened = System.currentTimeMillis()
            progress(100, "Wipe complete")
        } finally {
            running = false
        }
    }
}

/** Fills the free space of the data partition with random files, then deletes them (forces reuse of freed blocks). */
object FreeSpaceFill {
    fun run(dir: File, progress: (Int, String) -> Unit) {
        val reserve = 200L * 1024 * 1024  // keep some room so the phone keeps working
        val start = (dir.usableSpace - reserve).coerceAtLeast(1L)
        val files = ArrayList<File>()
        val rnd = RandomStream()
        val buf = ByteArray(1 shl 16)
        try {
            var idx = 0
            while (dir.usableSpace > reserve) {
                val f = File(dir, ".fill_${idx++}")
                files += f
                FileOutputStream(f).use { out ->
                    var written = 0L
                    while (written < 256L * 1024 * 1024 && dir.usableSpace > reserve) {
                        rnd.fill(buf)
                        out.write(buf)
                        written += buf.size
                        if (written % (16L * 1024 * 1024) == 0L) {
                            val done = (start - (dir.usableSpace - reserve)).coerceIn(0L, start)
                            progress((done * 100 / start).toInt(), "Filling free space…")
                        }
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            // disk full: exactly what we wanted
        } finally {
            files.forEach { it.delete() }
        }
    }
}
