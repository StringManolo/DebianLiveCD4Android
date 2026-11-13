package io.github.stringmanolo.DebianLiveCD4Android

import android.system.Os
import android.system.OsConstants
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Date

/** Streams a directory tree as an (uncompressed) tar. Used to feed an installed system into the VM without touching disk. */
object TarWriter {

    fun write(root: File, out: OutputStream) {
        val tar = TarArchiveOutputStream(out)
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
        walk(root, "", tar)
        tar.finish()
        out.flush()
    }

    private fun walk(dir: File, prefix: String, tar: TarArchiveOutputStream) {
        val children = dir.listFiles() ?: return
        children.sortBy { it.name }
        for (f in children) {
            val st = try { Os.lstat(f.path) } catch (e: Exception) { continue }
            val name = prefix + f.name
            val mode = st.st_mode and 0xFFF
            when {
                OsConstants.S_ISLNK(st.st_mode) -> {
                    val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                    e.linkName = Os.readlink(f.path)
                    e.mode = 511 // 0777
                    e.modTime = Date(st.st_mtime * 1000)
                    tar.putArchiveEntry(e); tar.closeArchiveEntry()
                }
                OsConstants.S_ISDIR(st.st_mode) -> {
                    val e = TarArchiveEntry("$name/")
                    e.mode = mode
                    e.modTime = Date(st.st_mtime * 1000)
                    tar.putArchiveEntry(e); tar.closeArchiveEntry()
                    walk(f, "$name/", tar)
                }
                OsConstants.S_ISREG(st.st_mode) -> {
                    val e = TarArchiveEntry(name)
                    e.size = st.st_size
                    e.mode = mode
                    e.modTime = Date(st.st_mtime * 1000)
                    tar.putArchiveEntry(e)
                    FileInputStream(f).use { it.copyTo(tar, 1 shl 16) }
                    tar.closeArchiveEntry()
                }
            }
        }
    }
}
