package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import org.apache.commons.compress.utils.CountingInputStream
import org.json.JSONObject
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

typealias Progress = (Int, String) -> Unit

class SysInfo(
    val id: String, val name: String, val kind: String, val created: Long, val lastUsed: Long,
    val installed: Boolean, val inUse: Boolean, val archived: Boolean, val encrypted: Boolean,
    val snapshots: Int
)

/**
 * Registry and lifecycle of systems. Layout: files/systems/<id>/
 *   meta.json            name, kind (system | livecd), timestamps
 *   rootfs/ + rootfs.ok  plain tree: exists while the system is in use (or always, if no archive policy)
 *   rootfs.dlc           archive at rest (optionally compressed and/or encrypted)
 *   in_use               marker while a session works on rootfs/
 *   snapshots/<n>.dlc      point-in-time copies
 * Every system is independent; nothing here ever touches another system's files.
 */
object SystemStore {
    const val LIVECD_ID = "livecd"
    private val ctx: Context get() = App.instance

    fun base() = File(ctx.filesDir, "systems")
    fun dir(id: String) = File(base(), id)
    fun rootfs(id: String) = File(dir(id), "rootfs")
    fun archive(id: String) = File(dir(id), "rootfs.dlc")
    fun inUseFile(id: String) = File(dir(id), "in_use")
    fun snapDir(id: String) = File(dir(id), "snapshots")
    private fun metaFile(id: String) = File(dir(id), "meta.json")

    fun plainReady(id: String) = Paths.okMarker(rootfs(id)).exists()
    fun hasArchive(id: String) = archive(id).exists()
    fun isInstalled(id: String) = plainReady(id) || hasArchive(id)

    // ------------------------------------------------------------------ registry

    private fun readMeta(id: String): JSONObject? =
        try { JSONObject(metaFile(id).readText()) } catch (e: Exception) { null }

    private fun writeMeta(id: String, j: JSONObject) {
        dir(id).mkdirs()
        val tmp = File(metaFile(id).path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(metaFile(id))
    }

    fun info(id: String): SysInfo? {
        val j = readMeta(id) ?: return null
        val arch = archive(id)
        val ai = if (arch.exists()) Archive.info(arch) else null
        return SysInfo(
            id, j.optString("name", id), j.optString("kind", "system"), j.optLong("created"), j.optLong("lastUsed"),
            isInstalled(id), inUseFile(id).exists(), arch.exists(), ai?.first == true,
            snapshots(id).size
        )
    }

    fun list(): List<SysInfo> =
        (base().listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { info(it.name) }.sortedBy { it.created }

    fun systems(): List<SysInfo> = list().filter { it.kind == "system" }

    private fun newId(): String = "s" + System.currentTimeMillis().toString(36) + (Math.random() * 1296).toInt().toString(36)

    fun create(name: String, kind: String, id: String = newId()): SysInfo {
        val now = System.currentTimeMillis()
        writeMeta(id, JSONObject().put("name", name).put("kind", kind).put("created", now).put("lastUsed", 0L))
        return info(id)!!
    }

    fun rename(id: String, name: String) {
        val j = readMeta(id) ?: return
        writeMeta(id, j.put("name", name))
    }

    private fun touch(id: String) {
        val j = readMeta(id) ?: return
        writeMeta(id, j.put("lastUsed", System.currentTimeMillis()))
    }

    fun delete(id: String, mode: WipeMode) {
        FsUtil.wipeTree(dir(id), mode)
        if (Cfg.activeSystem == id) Cfg.activeSystem = ""
    }

    // ------------------------------------------------------------------ migration from the single-system layout

    fun migrateLegacy() {
        if (prefs.getBoolean("migrated_systems", false)) return
        prefs.edit().putBoolean("migrated_systems", true).apply()
        val sys = Paths.legacyRoot(ctx, "system")
        if (Paths.okMarker(sys).exists()) {
            val s = create("Debian (migrated)", "system")
            if (sys.renameTo(rootfs(s.id))) {
                Paths.okMarker(rootfs(s.id)).writeText("1")
                Cfg.activeSystem = s.id
            }
            Paths.okMarker(sys).delete()
        }
        val lc = Paths.legacyRoot(ctx, "livecd-persist")
        if (Paths.okMarker(lc).exists()) {
            create("LiveCD persistence", "livecd", LIVECD_ID)
            if (lc.renameTo(rootfs(LIVECD_ID))) Paths.okMarker(rootfs(LIVECD_ID)).writeText("1")
            Paths.okMarker(lc).delete()
        }
    }

    // ------------------------------------------------------------------ install sources

    /** New system from the Debian image shipped in the APK. */
    fun createFromAsset(name: String, kind: String, id: String = newId(), progress: Progress): SysInfo {
        val info = create(name, kind, id)
        try {
            RootfsInstaller(ctx).extractAsset(rootfs(id), progress)
            Paths.okMarker(rootfs(id)).writeText("1")
            finalizeAtRest(id, progress)
            return info
        } catch (t: Throwable) {
            FsUtil.wipeTree(dir(id), WipeMode.NONE)
            throw t
        }
    }

    /** New system from any tar / tar.gz / tar.xz stream (import, download). Verifies [expectedSha256] if given. */
    fun importStream(
        name: String, raw: InputStream, total: Long, expectedSha256: String?, progress: Progress
    ): SysInfo {
        val info = create(name, "system")
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val dis = DigestInputStream(raw, md)
            val counting = CountingInputStream(dis)
            val tar = Archive.sniff(BufferedInputStream(counting, 1 shl 16))
            RootfsInstaller(ctx).extractTarStream(tar, rootfs(info.id), progress, { counting.bytesRead }, total, true)
            val sink = ByteArray(1 shl 16)
            while (dis.read(sink) >= 0) { /* drain so the digest covers the whole file */ }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !got.equals(expectedSha256.trim(), ignoreCase = true)) {
                throw IllegalStateException("SHA-256 mismatch (expected $expectedSha256, got $got)")
            }
            Paths.okMarker(rootfs(info.id)).writeText(got)
            finalizeAtRest(info.id, progress)
            return info
        } catch (t: Throwable) {
            FsUtil.wipeTree(dir(info.id), WipeMode.NONE)
            throw t
        }
    }

    /** Import of our own .dlc archive (needs the same key if encrypted) or any plain tar archive. */
    fun importFile(name: String, input: InputStream, total: Long, progress: Progress): SysInfo =
        importStream(name, input, total, null, progress)

    fun duplicate(id: String, newName: String, progress: Progress): SysInfo {
        if (inUseFile(id).exists()) throw IllegalStateException("The system is in use")
        val info = create(newName, "system")
        try {
            if (hasArchive(id)) archive(id).copyTo(archive(info.id), overwrite = true)
            if (plainReady(id)) {
                progress(10, "Copying…")
                FsUtil.copyTree(rootfs(id), rootfs(info.id)) { n -> progress(50, "Copying… $n files") }
                Paths.okMarker(rootfs(info.id)).writeText("1")
                finalizeAtRest(info.id, progress)
            }
            return info
        } catch (t: Throwable) {
            FsUtil.wipeTree(dir(info.id), WipeMode.NONE)
            throw t
        }
    }

    // ------------------------------------------------------------------ using a system

    /** Make sure a plain working tree exists (decrypting/unpacking the archive if needed) and mark it in use. */
    fun openWorking(id: String, progress: Progress): File {
        if (!isInstalled(id)) throw IllegalStateException("That system is not installed")
        if (!plainReady(id)) {
            val root = rootfs(id)
            FsUtil.deleteTree(root)
            try {
                progress(0, "Unlocking system…")
                Archive.openTar(archive(id)) { KeyManager.dek() }.use {
                    RootfsInstaller(ctx).extractTarStream(it, root, progress, null, 0, false)
                }
                Paths.okMarker(root).writeText("1")
            } catch (t: Throwable) {
                FsUtil.deleteTree(root)
                throw t
            }
        }
        inUseFile(id).writeText("1")
        touch(id)
        return rootfs(id)
    }

    /** End of a session: pack the working tree back to its at-rest form (per the current policy) and clear the marker. */
    fun closeWorking(id: String, progress: Progress) {
        try {
            if (Policy.current().needsArchive) pack(id, progress)
            else if (hasArchive(id) && plainReady(id)) archive(id).delete()
        } finally {
            inUseFile(id).delete()
        }
    }

    private fun pack(id: String, progress: Progress) {
        val pol = Policy.current()
        val dek = if (pol.encrypt) KeyManager.dek() else null
        progress(0, "Protecting system (${if (pol.encrypt) "encrypting" else "compressing"})…")
        Archive.write(archive(id), pol.comp, dek) { TarWriter.write(rootfs(id), it) }
        FsUtil.deleteTree(rootfs(id))
        Paths.okMarker(rootfs(id)).delete()
    }

    private fun finalizeAtRest(id: String, progress: Progress) {
        if (Policy.current().needsArchive && plainReady(id)) pack(id, progress)
    }

    /** Copy a system's content into [dir] (ephemeral sessions). Reads only; never modifies the source. */
    fun extractInto(id: String, dir: File, progress: Progress) {
        if (!isInstalled(id)) throw IllegalStateException("That system is not installed")
        if (plainReady(id)) {
            FsUtil.copyTree(rootfs(id), dir) { n -> progress(50, "Copying system… $n files") }
        } else {
            Archive.openTar(archive(id)) { KeyManager.dek() }.use {
                RootfsInstaller(ctx).extractTarStream(it, dir, progress, null, 0, false)
            }
        }
    }

    /** Stream the system as a plain tar (VM mode, export). */
    fun streamTar(id: String, out: OutputStream) {
        if (plainReady(id)) TarWriter.write(rootfs(id), out)
        else Archive.openTar(archive(id)) { KeyManager.dek() }.use { it.copyTo(out, 1 shl 16) }
    }

    /** Sessions that died without cleanup left a plain tree behind: pack it again per policy. */
    fun recover(progress: Progress) {
        for (s in list()) {
            if (!inUseFile(s.id).exists()) continue
            try {
                if (plainReady(s.id) && Policy.current().needsArchive) pack(s.id, progress)
                inUseFile(s.id).delete()
            } catch (e: KeyLockedException) {
                throw e
            } catch (e: Exception) {
                inUseFile(s.id).delete()
            }
        }
    }

    // ------------------------------------------------------------------ snapshots

    fun snapshots(id: String): List<File> =
        (snapDir(id).listFiles { f -> f.name.endsWith(".dlc") } ?: emptyArray()).sortedByDescending { it.name }

    fun snapshotTitle(f: File): String {
        val n = f.name.removeSuffix(".dlc")
        val ts = n.substringBefore('_').toLongOrNull() ?: 0L
        val label = n.substringAfter('_', "")
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts))
        return "$date  ${label.replace('-', ' ')}  (${f.length() / (1024 * 1024)} MB)"
    }

    fun takeSnapshot(id: String, label: String, progress: Progress) {
        if (inUseFile(id).exists()) throw IllegalStateException("Stop the session first")
        if (!isInstalled(id)) throw IllegalStateException("That system is not installed")
        val pol = Policy.current()
        val safe = label.trim().ifEmpty { "snapshot" }.replace(Regex("[^A-Za-z0-9]+"), "-").take(40)
        val dest = File(snapDir(id), "${System.currentTimeMillis()}_$safe.dlc")
        progress(0, "Taking snapshot…")
        if (hasArchive(id) && !plainReady(id)) {
            snapDir(id).mkdirs()
            archive(id).copyTo(dest, overwrite = true) // already in at-rest format
        } else {
            val dek = if (pol.encrypt) KeyManager.dek() else null
            Archive.write(dest, if (pol.comp == Compression.NONE) Compression.FAST else pol.comp, dek) {
                TarWriter.write(rootfs(id), it)
            }
        }
    }

    fun restoreSnapshot(id: String, snap: File, progress: Progress) {
        if (inUseFile(id).exists()) throw IllegalStateException("Stop the session first")
        val tmp = File(dir(id), "rootfs.restore")
        FsUtil.deleteTree(tmp)
        try {
            progress(0, "Restoring…")
            Archive.openTar(snap) { KeyManager.dek() }.use {
                RootfsInstaller(ctx).extractTarStream(it, tmp, progress, null, 0, false)
            }
        } catch (t: Throwable) {
            FsUtil.deleteTree(tmp)
            throw t
        }
        FsUtil.deleteTree(rootfs(id))
        Paths.okMarker(rootfs(id)).delete()
        archive(id).delete()
        tmp.renameTo(rootfs(id))
        Paths.okMarker(rootfs(id)).writeText("1")
        finalizeAtRest(id, progress)
    }

    // ------------------------------------------------------------------ export

    /** [fmt]: "tgz" | "txz" (portable) or "dlc" (this app's container: uses the current compression/encryption policy). */
    fun export(id: String, os: OutputStream, fmt: String) {
        when (fmt) {
            "tgz" -> GZIPOutputStream(os, 1 shl 16).use { streamTar(id, it) }
            "txz" -> XZOutputStream(os, LZMA2Options(4)).use { streamTar(id, it) }
            else -> {
                val pol = Policy.current()
                Archive.writeTo(os, pol.comp, if (pol.encrypt) KeyManager.dek() else null) { streamTar(id, it) }
            }
        }
    }

    // ------------------------------------------------------------------ policy changes

    /**
     * Brings every system and snapshot in line with [pol]. [readDek] decrypts what exists now; [writeDek] encrypts
     * what is written (null = no encryption). With [force] every archive is rewritten (key rotation).
     */
    fun reconcile(pol: Policy, readDek: () -> ByteArray, writeDek: ByteArray?, force: Boolean, progress: Progress) {
        val all = list()
        all.forEachIndexed { i, s ->
            if (s.inUse) return@forEachIndexed
            val label = "${s.name} (${i + 1}/${all.size})"
            progress(i * 100 / all.size, label)
            val id = s.id
            if (pol.needsArchive) {
                if (plainReady(id) && !hasArchive(id)) {
                    Archive.write(archive(id), pol.comp, writeDek) { TarWriter.write(rootfs(id), it) }
                    FsUtil.deleteTree(rootfs(id)); Paths.okMarker(rootfs(id)).delete()
                } else if (hasArchive(id)) {
                    rewriteIfNeeded(archive(id), pol, readDek, writeDek, force)
                }
            } else if (hasArchive(id) && !plainReady(id)) {
                val root = rootfs(id)
                Archive.openTar(archive(id), readDek).use {
                    RootfsInstaller(ctx).extractTarStream(it, root, { _, _ -> }, null, 0, false)
                }
                Paths.okMarker(root).writeText("1")
                archive(id).delete()
            }
            for (snap in snapshots(id)) {
                val p = if (pol.comp == Compression.NONE) Policy(pol.encrypt, Compression.FAST) else pol
                rewriteIfNeeded(snap, p, readDek, writeDek, force)
            }
        }
        progress(100, "done")
    }

    private fun rewriteIfNeeded(f: File, pol: Policy, readDek: () -> ByteArray, writeDek: ByteArray?, force: Boolean) {
        val cur = Archive.info(f)
        val same = cur != null && cur.first == (writeDek != null) && cur.second == pol.comp
        if (same && !force) return
        val tmp = File(f.path + ".rewrite")
        try {
            Archive.write(tmp, pol.comp, writeDek) { out ->
                Archive.openTar(f, readDek).use { it.copyTo(out, 1 shl 16) }
            }
            f.delete()
            tmp.renameTo(f)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    // ------------------------------------------------------------------ LiveCD persistence

    fun ensureLivecd(progress: Progress) {
        if (isInstalled(LIVECD_ID)) return
        if (readMeta(LIVECD_ID) != null) FsUtil.wipeTree(dir(LIVECD_ID), WipeMode.NONE)
        createFromAsset("LiveCD persistence", "livecd", LIVECD_ID, progress)
    }
}
