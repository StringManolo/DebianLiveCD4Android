package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

object Paths {
    const val ROOTFS_ASSET = "rootfs/debian-arm64.tar.xz"

    fun systemRoot(ctx: Context) = File(ctx.filesDir, "rootfs/system")
    fun livecdPersistRoot(ctx: Context) = File(ctx.filesDir, "rootfs/livecd-persist")
    fun ephemeralBase(ctx: Context) = File(ctx.filesDir, "eph")
    fun vmDir(ctx: Context) = File(ctx.filesDir, "vm")
    fun tmpDir(ctx: Context) = File(ctx.filesDir, "tmp")
    fun fakeProcDir(ctx: Context) = File(ctx.filesDir, "fakeproc")
    fun nativeLibDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    /** Marker lives next to the rootfs dir, never inside it. */
    fun okMarker(root: File) = File(root.parentFile, root.name + ".ok")
}

object FsUtil {
    /** lstat-based existence check: true for dangling symlinks too. */
    fun exists(f: File): Boolean = try { Os.lstat(f.path); true } catch (e: Exception) { false }

    /** Recursive delete that never follows symlinks. */
    fun deleteTree(f: File) = wipeTree(f, false)

    /**
     * Recursive delete that never follows symlinks. With [overwrite], regular files are zero-filled and
     * fsync'ed before unlinking. NOTE: on flash storage (F2FS, wear levelling) this does NOT guarantee that
     * old blocks are gone; real RAM-only operation is what VM mode is for.
     */
    fun wipeTree(f: File, overwrite: Boolean) {
        val st = try { Os.lstat(f.path) } catch (e: Exception) { return }
        if (OsConstants.S_ISDIR(st.st_mode)) {
            try { Os.chmod(f.path, 448) } catch (_: Exception) {} // 0700
            f.listFiles()?.forEach { wipeTree(it, overwrite) }
        } else if (overwrite && OsConstants.S_ISREG(st.st_mode) && st.st_size > 0) {
            try {
                Os.chmod(f.path, 384) // 0600
                RandomAccessFile(f, "rw").use { raf ->
                    val buf = ByteArray(1 shl 16)
                    var left = st.st_size
                    while (left > 0) {
                        val n = minOf(left, buf.size.toLong()).toInt()
                        raf.write(buf, 0, n)
                        left -= n
                    }
                    raf.fd.sync()
                }
            } catch (_: Exception) {}
        }
        f.delete()
    }

    /** Copies a tree preserving symlinks and mode bits (never follows symlinks). */
    fun copyTree(src: File, dst: File, onFile: (Int) -> Unit = {}) {
        var count = 0
        fun rec(s: File, d: File) {
            val st = Os.lstat(s.path)
            when {
                OsConstants.S_ISLNK(st.st_mode) -> Os.symlink(Os.readlink(s.path), d.path)
                OsConstants.S_ISDIR(st.st_mode) -> {
                    d.mkdirs()
                    Os.chmod(d.path, (st.st_mode and 0xFFF) or 448)
                    s.listFiles()?.forEach { rec(it, File(d, it.name)) }
                }
                OsConstants.S_ISREG(st.st_mode) -> {
                    FileInputStream(s).use { i -> FileOutputStream(d).use { o -> i.copyTo(o, 1 shl 16) } }
                    Os.chmod(d.path, (st.st_mode and 0xFFF) or 384)
                    if (++count % 200 == 0) onFile(count)
                }
            }
        }
        rec(src, dst)
    }
}

/** Temporary roots for ephemeral mode. Anything left here (crash, kill) is wiped on the next launch. */
object Ephemeral {
    fun newDir(ctx: Context): File =
        File(Paths.ephemeralBase(ctx), "s${System.currentTimeMillis()}").also { it.mkdirs() }

    fun sweep(ctx: Context) {
        Paths.ephemeralBase(ctx).listFiles()?.forEach { FsUtil.wipeTree(it, true) }
    }
}
