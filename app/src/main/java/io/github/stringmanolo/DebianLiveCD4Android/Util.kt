package io.github.stringmanolo.DebianLiveCD4Android

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

fun fmtBytes(b: Long): String = when {
    b < 0 -> "?"
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
    else -> "$b B"
}

/** Passes reads through but ignores close(): lets us keep draining the source after a decompressor was closed. */
class NoCloseInputStream(i: InputStream) : FilterInputStream(i) {
    override fun close() {}
}

/** Total size of a tree in bytes (lstat; never follows symlinks). */
fun treeSize(f: File): Long {
    val st = try { Os.lstat(f.path) } catch (e: Exception) { return 0L }
    if (OsConstants.S_ISDIR(st.st_mode)) return (f.listFiles() ?: emptyArray()).sumOf { treeSize(it) }
    return if (OsConstants.S_ISREG(st.st_mode)) st.st_size else 0L
}
