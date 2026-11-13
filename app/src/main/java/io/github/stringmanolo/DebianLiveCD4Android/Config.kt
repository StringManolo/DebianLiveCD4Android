package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context

/** Which environment: the installed Debian ("system") or the read-only image shipped in the APK ("livecd"). */
enum class Target { SYSTEM, LIVECD }

/** How it runs: persistent on disk, temporary copy wiped on exit, or inside a QEMU VM (RAM only). */
enum class Mode { NORMAL, EPHEMERAL, VM }

data class AppConfig(
    val target: Target = Target.SYSTEM,
    val mode: Mode = Mode.NORMAL,
    val vmRamMb: Int = 1024,
    val vmCpus: Int = 2,
    val secureWipe: Boolean = true
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("target", target.name)
            .putString("mode", mode.name)
            .putInt("vmRamMb", vmRamMb)
            .putInt("vmCpus", vmCpus)
            .putBoolean("secureWipe", secureWipe)
            .apply()
    }

    companion object {
        private const val PREFS = "config"
        val RAM_CHOICES = listOf(512, 1024, 1536, 2048, 3072)
        val CPU_CHOICES = listOf(1, 2, 4)

        fun load(ctx: Context): AppConfig {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = AppConfig()
            return AppConfig(
                runCatching { Target.valueOf(sp.getString("target", null) ?: "") }.getOrDefault(d.target),
                runCatching { Mode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(d.mode),
                sp.getInt("vmRamMb", d.vmRamMb),
                sp.getInt("vmCpus", d.vmCpus),
                sp.getBoolean("secureWipe", d.secureWipe)
            )
        }
    }
}
