package io.github.stringmanolo.DebianLiveCD4Android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlin.reflect.KProperty

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Cfg.sessionRunning = false  // a previous process may have died mid-session
        Maintenance.schedule(this)
        if (!Cfg.allowNetwork) try { NetBlockService.apply(this) } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

internal val prefs: SharedPreferences
    get() = App.instance.getSharedPreferences("settings2", Context.MODE_PRIVATE)

private class BoolP(val k: String, val d: Boolean) {
    operator fun getValue(t: Any?, p: KProperty<*>) = prefs.getBoolean(k, d)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Boolean) { prefs.edit().putBoolean(k, v).apply() }
}
private class IntP(val k: String, val d: Int) {
    operator fun getValue(t: Any?, p: KProperty<*>) = prefs.getInt(k, d)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Int) { prefs.edit().putInt(k, v).apply() }
}
private class LongP(val k: String, val d: Long) {
    operator fun getValue(t: Any?, p: KProperty<*>) = prefs.getLong(k, d)
    operator fun setValue(t: Any?, p: KProperty<*>, v: Long) { prefs.edit().putLong(k, v).apply() }
}
private class StrP(val k: String, val d: String) {
    operator fun getValue(t: Any?, p: KProperty<*>) = prefs.getString(k, d) ?: d
    operator fun setValue(t: Any?, p: KProperty<*>, v: String) { prefs.edit().putString(k, v).apply() }
}

/** Everything that is not part of [AppConfig]. Each property is read from / written to SharedPreferences directly. */
object Cfg {
    // ---- systems / run
    var activeSystem by StrP("active_system", "")
    var wakeLock by BoolP("wake_lock", false)
    var closeOnScreenOff by BoolP("close_on_screen_off", false)
    var storageAccess by BoolP("storage_access", false)
    var customTheme by StrP("custom_theme", "")

    // ---- security: at-rest protection
    var encryptAtRest by BoolP("encrypt_at_rest", false)
    var compression by StrP("compression", "none")        // none | fast | best

    // ---- security: PIN
    var pinEnabled by BoolP("pin_enabled", false)
    var pinHash by StrP("pin_hash", "")
    var pinSalt by StrP("pin_salt", "")
    var duressHash by StrP("duress_hash", "")
    var duressSalt by StrP("duress_salt", "")
    var pinTimeoutMin by IntP("pin_timeout_min", 0)        // 0 = lock whenever the app leaves the foreground
    var failedPins by IntP("failed_pins", 0)

    // ---- privacy
    var secureScreen by BoolP("secure_screen", false)
    var hideNotifContent by BoolP("hide_notif", false)
    var blockClipboard by BoolP("block_clipboard", false)
    var clearClipboardOnExit by BoolP("clear_clipboard", false)

    // ---- network
    var allowNetwork by BoolP("allow_network", true)
    var vmNic by BoolP("vm_nic", true)

    // ---- updates
    var updateMode by StrP("update_mode", "none")          // none | manual | auto
    var updateHours by IntP("update_hours", 8)
    var lastUpdateCheck by LongP("last_update_check", 0L)
    var lastUpdateInfo by StrP("last_update_info", "")

    // ---- scheduled wipe
    var wipeEnabled by BoolP("wipe_enabled", false)
    var wipeInactivityHours by IntP("wipe_inactivity_h", 0)  // 0 = off
    var wipeDeadline by LongP("wipe_deadline", 0L)           // epoch ms, 0 = off
    var wipeFailedPins by IntP("wipe_failed_pins", 0)        // 0 = off
    var wipeOnDuress by BoolP("wipe_on_duress", false)
    var wipeOnAdb by BoolP("wipe_on_adb", false)
    var wipeSystems by BoolP("wipe_systems", true)
    var wipeLivecd by BoolP("wipe_livecd", true)
    var wipeSnapshots by BoolP("wipe_snapshots", true)
    var wipeFillFree by BoolP("wipe_fill_free", false)
    var lastOpened by LongP("last_opened", 0L)
    var sessionRunning by BoolP("session_running", false)
}
