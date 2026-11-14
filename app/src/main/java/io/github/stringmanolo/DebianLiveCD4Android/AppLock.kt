package io.github.stringmanolo.DebianLiveCD4Android

import android.os.Build
import android.os.SystemClock
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Optional PIN lock for the app (Settings always, the Activity, and restoring a minimized window). */
object AppLock {
    enum class Result { OK, WRONG, DURESS, WIPE }

    @Volatile private var unlockedAt = 0L      // elapsedRealtime of the last unlock/touch, 0 = locked
    @Volatile private var lockedUntil = 0L     // brute-force delay

    fun enabled() = Cfg.pinEnabled

    fun isLocked(): Boolean {
        if (!enabled()) return false
        if (unlockedAt == 0L) return true
        val t = Cfg.pinTimeoutMin
        return t > 0 && SystemClock.elapsedRealtime() - unlockedAt > t * 60_000L
    }

    fun touch() { if (unlockedAt != 0L) unlockedAt = SystemClock.elapsedRealtime() }

    /** Called when the app UI goes to the background. */
    fun onLeftForeground() { if (Cfg.pinTimeoutMin == 0) unlockedAt = 0L }

    fun lockNow() { unlockedAt = 0L }

    fun waitSeconds(): Int = ((lockedUntil - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

    fun attempt(pin: String): Result {
        if (waitSeconds() > 0) return Result.WRONG
        if (Cfg.duressHash.isNotEmpty() && matches(pin, Cfg.duressSalt, Cfg.duressHash)) {
            if (Cfg.wipeEnabled && Cfg.wipeOnDuress) return Result.DURESS
            // duress PIN without a wipe policy behaves like a wrong PIN
            return fail()
        }
        if (matches(pin, Cfg.pinSalt, Cfg.pinHash)) {
            Cfg.failedPins = 0
            unlockedAt = SystemClock.elapsedRealtime()
            return Result.OK
        }
        return fail()
    }

    private fun fail(): Result {
        Cfg.failedPins = Cfg.failedPins + 1
        val n = Cfg.failedPins
        if (Cfg.wipeEnabled && Cfg.wipeFailedPins > 0 && n >= Cfg.wipeFailedPins) return Result.WIPE
        if (n >= 5) lockedUntil = SystemClock.elapsedRealtime() + minOf(30L * (n - 4), 600L) * 1000L
        return Result.WRONG
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Cfg.pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        Cfg.pinHash = hash(pin, salt)
        Cfg.pinEnabled = true
        Cfg.failedPins = 0
        unlockedAt = SystemClock.elapsedRealtime()
    }

    fun clearPin() {
        Cfg.pinEnabled = false
        Cfg.pinHash = ""; Cfg.pinSalt = ""
        Cfg.duressHash = ""; Cfg.duressSalt = ""
        Cfg.failedPins = 0
        unlockedAt = 0L
    }

    fun setDuress(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Cfg.duressSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        Cfg.duressHash = hash(pin, salt)
    }

    fun verify(pin: String) = matches(pin, Cfg.pinSalt, Cfg.pinHash)

    private fun matches(pin: String, saltB64: String, hashB64: String): Boolean {
        if (saltB64.isEmpty() || hashB64.isEmpty()) return false
        val h = hash(pin, Base64.decode(saltB64, Base64.NO_WRAP))
        return java.security.MessageDigest.isEqual(h.toByteArray(), hashB64.toByteArray())
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val alg = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val f = SecretKeyFactory.getInstance(alg)
        val k = f.generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
        return Base64.encodeToString(k, Base64.NO_WRAP)
    }
}
