package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context

/** Which environment: the installed Debian ("system") or the read-only image shipped in the APK ("livecd"). */
enum class Target { SYSTEM, LIVECD }

/** How it runs: persistent on disk, temporary copy wiped on exit, or inside a QEMU VM (RAM only). */
enum class Mode { NORMAL, EPHEMERAL, VM }

enum class WindowMode { FULLSCREEN, FLOATING }

data class AppConfig(
    /** false until the user has explicitly chosen an environment (nothing is installed or started before that). */
    val configured: Boolean = false,
    val target: Target = Target.LIVECD,
    val mode: Mode = Mode.EPHEMERAL,
    val vmRamMb: Int = 1024,
    val vmCpus: Int = 2,
    val secureWipe: Boolean = true,
    /** LiveCD (proot modes) only: bind the installed System at /mnt/system. */
    val mountSystem: Boolean = false,
    val windowMode: WindowMode = WindowMode.FULLSCREEN,
    val themeId: String = "dark",
    val fontSp: Int = 12,
    val fontBold: Boolean = false
) {
    /** Environment-affecting changes restart the session; appearance and window changes never do. */
    fun needsRestart(o: AppConfig) =
        target != o.target || mode != o.mode || mountSystem != o.mountSystem ||
            (o.mode == Mode.VM && (vmRamMb != o.vmRamMb || vmCpus != o.vmCpus))

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("configured", configured)
            .putString("target", target.name)
            .putString("mode", mode.name)
            .putInt("vmRamMb", vmRamMb)
            .putInt("vmCpus", vmCpus)
            .putBoolean("secureWipe", secureWipe)
            .putBoolean("mountSystem", mountSystem)
            .putString("windowMode", windowMode.name)
            .putString("themeId", themeId)
            .putInt("fontSp", fontSp)
            .putBoolean("fontBold", fontBold)
            .apply()
    }

    companion object {
        private const val PREFS = "config"
        val RAM_CHOICES = listOf(512, 1024, 1536, 2048, 3072)
        val CPU_CHOICES = listOf(1, 2, 4)
        val FONT_CHOICES = listOf(8, 10, 12, 14, 16, 18, 20, 24)

        fun load(ctx: Context): AppConfig {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = AppConfig()
            return AppConfig(
                sp.getBoolean("configured", d.configured),
                runCatching { Target.valueOf(sp.getString("target", null) ?: "") }.getOrDefault(d.target),
                runCatching { Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(d.mode),
                sp.getInt("vmRamMb", d.vmRamMb),
                sp.getInt("vmCpus", d.vmCpus),
                sp.getBoolean("secureWipe", d.secureWipe),
                sp.getBoolean("mountSystem", d.mountSystem),
                runCatching { WindowMode.valueOf(sp.getString("windowMode", null) ?: "") }.getOrDefault(d.windowMode),
                sp.getString("themeId", d.themeId) ?: d.themeId,
                sp.getInt("fontSp", d.fontSp),
                sp.getBoolean("fontBold", d.fontBold)
            )
        }
    }
}
