package io.github.stringmanolo.DebianLiveCD4Android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class KeyLockedException : Exception("The encryption key is locked: enter your passphrase.")

enum class Compression(val id: String, val code: Int) {
    NONE("none", 0), FAST("fast", 1), BEST("best", 2);
    companion object {
        fun of(id: String) = values().firstOrNull { it.id == id } ?: NONE
        fun ofCode(c: Int) = values().firstOrNull { it.code == c } ?: NONE
    }
}

/** What the user wants at rest. Plain = no archive at all (systems stay as a directory tree). */
data class Policy(val encrypt: Boolean, val comp: Compression) {
    val needsArchive get() = encrypt || comp != Compression.NONE
    companion object {
        fun current() = Policy(Cfg.encryptAtRest, Compression.of(Cfg.compression))
    }
}

private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

/**
 * One random 256-bit data key (DEK) protects every archive. The DEK itself is stored wrapped by either
 *  - "keystore": a non-exportable AES key in the Android Keystore (app-generated, nothing to remember), or
 *  - "passphrase": a key derived from the user's passphrase with PBKDF2 (the DEK exists in RAM only while unlocked).
 * Changing the wrapping is instant (re-wrap); rotating the DEK re-encrypts every archive.
 */
object KeyManager {
    private const val ALIAS = "dlc_kek_v1"
    private val file get() = File(App.instance.filesDir, "keys/dek.json")
    @Volatile private var cached: ByteArray? = null

    fun hasKey() = file.exists()
    fun mode(): String? = if (hasKey()) runCatching { JSONObject(file.readText()).getString("mode") }.getOrNull() else null
    fun isUnlocked() = cached != null || mode() == "keystore"

    fun dek(): ByteArray {
        cached?.let { return it }
        val j = JSONObject(file.readText())
        if (j.getString("mode") == "keystore") {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, unb64(j.getString("iv"))))
            return c.doFinal(unb64(j.getString("ct"))).also { cached = it }
        }
        throw KeyLockedException()
    }

    fun unlock(pass: CharArray): Boolean {
        val j = JSONObject(file.readText())
        if (j.getString("mode") != "passphrase") return true
        return try {
            val kek = derive(pass, unb64(j.getString("salt")), j.getInt("iter"), j.optString("kdf", "SHA256"))
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, unb64(j.getString("iv"))))
            cached = c.doFinal(unb64(j.getString("ct")))
            true
        } catch (e: Exception) { false }
    }

    /** Forget the DEK from RAM (passphrase mode: next use needs the passphrase again). */
    fun lock() { cached = null } // only drops the reference: a concurrent job may still be using the array

    fun enable(mode: String, pass: CharArray?) {
        val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
        store(dek, mode, pass)
        cached = dek
    }

    /** Switch wrapping (keystore <-> passphrase) or change the passphrase. Requires the key to be unlocked. */
    fun rewrap(mode: String, pass: CharArray?) { val d = dek(); store(d, mode, pass); cached = d }

    fun newDek(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun replaceDek(newDek: ByteArray, mode: String? = null, pass: CharArray? = null) {
        store(newDek, mode ?: this.mode() ?: "keystore", pass)
        cached = newDek
    }

    /** Delete the key: everything encrypted with it becomes unreadable (crypto-shredding). */
    fun destroy() {
        lock()
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    fun keyId(dek: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(dek).copyOf(4)

    private fun store(dek: ByteArray, mode: String, pass: CharArray?) {
        val j = JSONObject().put("mode", mode)
        if (mode == "keystore") {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, keystoreKey())
            j.put("iv", b64(c.iv)).put("ct", b64(c.doFinal(dek)))
        } else {
            require(pass != null && pass.isNotEmpty()) { "empty passphrase" }
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val kdf = if (Build.VERSION.SDK_INT >= 26) "SHA256" else "SHA1"
            val iter = if (kdf == "SHA256") 600_000 else 1_000_000
            val kek = derive(pass, salt, iter, kdf)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
            j.put("salt", b64(salt)).put("iter", iter).put("kdf", kdf).put("iv", b64(iv)).put("ct", b64(c.doFinal(dek)))
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file)
    }

    private fun derive(pass: CharArray, salt: ByteArray, iter: Int, kdf: String): ByteArray {
        val f = SecretKeyFactory.getInstance(if (kdf == "SHA256") "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1")
        return f.generateSecret(PBEKeySpec(pass, salt, iter, 256)).encoded
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build()
                )
            }.generateKey()
        }
        return ks.getKey(ALIAS, null) as SecretKey
    }
}

private const val CHUNK = 1 shl 20

/** AES-256-GCM in 1 MiB chunks: IV = random 8-byte prefix || chunk counter; AAD marks the final chunk (truncation is detected). */
class GcmChunkOut(private val out: OutputStream, dek: ByteArray, private val prefix: ByteArray) : OutputStream() {
    private val key = SecretKeySpec(dek, "AES")
    private val buf = ByteArray(CHUNK)
    private var n = 0
    private var counter = 0
    private var closed = false

    override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (n == CHUNK) flushChunk(false)   // a full buffer is held back until we know more data follows
            val c = minOf(l, CHUNK - n)
            System.arraycopy(b, o, buf, n, c)
            n += c; o += c; l -= c
        }
    }

    private fun flushChunk(last: Boolean) {
        val iv = ByteArray(12)
        System.arraycopy(prefix, 0, iv, 0, 8)
        ByteBuffer.wrap(iv, 8, 4).putInt(counter)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(byteArrayOf(if (last) 1 else 0))
        val ct = c.doFinal(buf, 0, n)
        out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
        out.write(ct)
        counter++
        n = 0
    }

    override fun flush() { out.flush() }

    override fun close() {
        if (closed) return
        closed = true
        flushChunk(true)
        out.flush()
        out.close()
    }
}

class GcmChunkIn(private val inp: InputStream, dek: ByteArray, private val prefix: ByteArray) : InputStream() {
    private val key = SecretKeySpec(dek, "AES")
    private var buf = ByteArray(0)
    private var pos = 0
    private var counter = 0
    private var nextLen = readLen()
    private var finished = false

    private fun readLen(): Int {
        val b = ByteArray(4)
        var r = 0
        while (r < 4) {
            val k = inp.read(b, r, 4 - r)
            if (k < 0) { if (r == 0) return -1; throw IOException("truncated archive") }
            r += k
        }
        return ByteBuffer.wrap(b).int
    }

    private fun fill() {
        val len = nextLen
        if (len < 0) throw IOException("archive is truncated (missing final chunk)")
        val ct = ByteArray(len)
        var r = 0
        while (r < len) {
            val k = inp.read(ct, r, len - r)
            if (k < 0) throw IOException("truncated archive")
            r += k
        }
        nextLen = readLen()
        val last = nextLen < 0
        val iv = ByteArray(12)
        System.arraycopy(prefix, 0, iv, 0, 8)
        ByteBuffer.wrap(iv, 8, 4).putInt(counter)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(byteArrayOf(if (last) 1 else 0))
        buf = try { c.doFinal(ct) } catch (e: Exception) { throw IOException("wrong key or corrupted archive", e) }
        pos = 0
        counter++
        if (last) finished = true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        while (pos >= buf.size) {
            if (finished) return -1
            fill()
        }
        val c = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, c)
        pos += c
        return c
    }

    override fun close() { inp.close() }
}

/**
 * Container format "DLC1": [magic 4][version 1][flags 1][if encrypted: nonce prefix 8, key id 4][body]
 * flags: bit0 = encrypted, bits1-2 = compression (0 none, 1 gzip, 2 xz). Body = tar -> compress -> encrypt.
 * Also reads plain tar / tar.gz / tar.xz (sniffed by magic), used for imports.
 */
object Archive {
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    fun isDlc(f: File): Boolean = try {
        FileInputStream(f).use { val h = ByteArray(4); it.read(h) == 4 && h.contentEquals(MAGIC) }
    } catch (e: Exception) { false }

    /** Writes the container to [os]. [body] must write the tar stream. Closes [os]. */
    fun writeTo(os: OutputStream, comp: Compression, dek: ByteArray?, body: (OutputStream) -> Unit) {
        val flags = (if (dek != null) 1 else 0) or (comp.code shl 1)
        os.write(MAGIC)
        os.write(1)
        os.write(flags)
        var s: OutputStream = os
        if (dek != null) {
            val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
            os.write(prefix)
            os.write(KeyManager.keyId(dek))
            s = GcmChunkOut(s, dek, prefix)
        }
        s = when (comp) {
            Compression.NONE -> s
            Compression.FAST -> object : GZIPOutputStream(s, 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }
            Compression.BEST -> XZOutputStream(s, LZMA2Options(4))
        }
        body(s)
        s.close()
    }

    /** Writes [dest] atomically (tmp + rename). */
    fun write(dest: File, comp: Compression, dek: ByteArray?, body: (OutputStream) -> Unit) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        try {
            writeTo(FileOutputStream(tmp), comp, dek, body)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("cannot rename ${tmp.name}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** (encrypted, compression) of a DLC1 file, or null if it is not one. */
    fun info(f: File): Pair<Boolean, Compression>? = try {
        FileInputStream(f).use {
            val h = ByteArray(6)
            if (it.read(h) == 6 && h.copyOf(4).contentEquals(MAGIC)) {
                val fl = h[5].toInt()
                Pair(fl and 1 != 0, Compression.ofCode((fl shr 1) and 3))
            } else null
        }
    } catch (e: Exception) { null }

    /** Opens an archive file and returns the plain tar stream. [dek] is only called if the archive is encrypted. */
    fun openTar(f: File, dek: () -> ByteArray): InputStream {
        val raw = BufferedInputStream(FileInputStream(f), 1 shl 16)
        raw.mark(16)
        val h = ByteArray(6)
        var r = 0
        while (r < 6) { val k = raw.read(h, r, 6 - r); if (k < 0) break; r += k }
        if (r == 6 && h.copyOf(4).contentEquals(MAGIC)) {
            val flags = h[5].toInt()
            var s: InputStream = raw
            if (flags and 1 != 0) {
                val extra = ByteArray(12)
                var e = 0
                while (e < 12) { val k = raw.read(extra, e, 12 - e); if (k < 0) throw IOException("truncated header"); e += k }
                val key = dek()
                if (!KeyManager.keyId(key).contentEquals(extra.copyOfRange(8, 12))) {
                    throw IOException("this archive was encrypted with a different key")
                }
                s = GcmChunkIn(raw, key, extra.copyOf(8))
            }
            return when (Compression.ofCode((flags shr 1) and 3)) {
                Compression.NONE -> s
                Compression.FAST -> GZIPInputStream(s, 1 shl 16)
                Compression.BEST -> XZCompressorInputStream(s)
            }
        }
        raw.reset()
        return sniff(raw)
    }

    /** Plain tar, tar.gz or tar.xz -> tar stream. */
    fun sniff(raw: BufferedInputStream): InputStream {
        raw.mark(16)
        val h = ByteArray(6)
        var r = 0
        while (r < 6) { val k = raw.read(h, r, 6 - r); if (k < 0) break; r += k }
        raw.reset()
        return when {
            r >= 2 && h[0] == 0x1f.toByte() && h[1] == 0x8b.toByte() -> GZIPInputStream(raw, 1 shl 16)
            r >= 6 && h[0] == 0xFD.toByte() && h[1] == 0x37.toByte() && h[2] == 0x7A.toByte() -> XZCompressorInputStream(raw)
            else -> raw
        }
    }
}

/** Pseudo-random byte stream from a throw-away AES-CTR key (fast CSPRNG; the key is discarded = "encrypt with a random key"). */
class RandomStream {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), javax.crypto.spec.IvParameterSpec(iv))
    }
    private val zeros = ByteArray(1 shl 16)

    /** Fills [b] (size <= 64 KiB) with random bytes. */
    fun fill(b: ByteArray, len: Int = b.size) {
        val out = cipher.update(zeros, 0, len)
        System.arraycopy(out, 0, b, 0, len)
    }
}
