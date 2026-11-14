package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.content.res.AssetManager
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Extracts tar streams (shipped image, archives, imports, downloads) into a directory. No external tools. */
class RootfsInstaller(private val ctx: Context) {

    /** Extracts the shipped LiveCD image into [root] (wiped first). On failure the partial tree is removed. */
    fun extractAsset(root: File, onProgress: (Int, String) -> Unit) {
        FsUtil.deleteTree(root)
        root.mkdirs()
        try {
            val raw = ctx.assets.open(Paths.ROOTFS_ASSET, AssetManager.ACCESS_STREAMING)
            val total = raw.available().toLong().coerceAtLeast(1L)
            val counting = CountingInputStream(raw)
            val tar = XZCompressorInputStream(BufferedInputStream(counting, 1 shl 16))
            extractTarStream(tar, root, onProgress, { counting.bytesRead }, total, true)
        } catch (t: Throwable) {
            FsUtil.deleteTree(root)
            throw t
        }
    }

    /**
     * Extracts a plain tar [stream] into [root]. [bytesRead]/[total] drive the percentage when known.
     * [setup] writes resolv.conf/hosts/apt config (skip it when the tree comes from an already prepared system).
     */
    fun extractTarStream(
        stream: InputStream, root: File, onProgress: (Int, String) -> Unit,
        bytesRead: (() -> Long)?, total: Long, setup: Boolean
    ) {
        root.mkdirs()
        var last = 0L
        var n = 0
        TarArchiveInputStream(stream).use { tar ->
            var e: TarArchiveEntry? = tar.nextTarEntry
            while (e != null) {
                extract(tar, e, root)
                n++
                val now = System.currentTimeMillis()
                if (now - last > 150) {
                    last = now
                    val pct = if (bytesRead != null && total > 0) (bytesRead() * 100 / total).toInt().coerceIn(0, 99) else 50
                    onProgress(pct, "Extracting… $n entries")
                }
                e = tar.nextTarEntry
            }
        }
        if (setup) postSetup(root)
        onProgress(100, "done")
    }

    private fun extract(tar: TarArchiveInputStream, e: TarArchiveEntry, root: File) {
        val rel = e.name.removePrefix("./").trimEnd('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return
        val out = File(root, rel)
        val mode = e.mode and 0xFFF
        try {
            when {
                e.isDirectory -> {
                    if (!FsUtil.exists(out)) out.mkdirs()
                    Os.chmod(out.path, mode or 448) // |0700 so we can always write inside
                }
                e.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    FsUtil.deleteTree(out)
                    Os.symlink(e.linkName, out.path)
                }
                e.isLink -> {
                    out.parentFile?.mkdirs()
                    FsUtil.deleteTree(out)
                    val target = File(root, e.linkName.removePrefix("./"))
                    try {
                        Os.link(target.path, out.path)
                    } catch (_: ErrnoException) { // hardlinks are often denied by SELinux: copy instead
                        target.copyTo(out, overwrite = true)
                        Os.chmod(out.path, mode or 384)
                    }
                }
                e.isFile -> {
                    out.parentFile?.mkdirs()
                    FsUtil.deleteTree(out)
                    FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
                    Os.chmod(out.path, mode or 384) // |0600
                }
                else -> { /* device nodes / fifos: skipped, proot binds /dev */ }
            }
        } catch (t: Throwable) {
            Log.w("RootfsInstaller", "entry $rel failed: $t")
        }
    }

    private fun postSetup(root: File) {
        File(root, "tmp").mkdirs()
        Os.chmod(File(root, "tmp").path, 1023) // 1777
        File(root, "root").mkdirs()
        writeText(root, "etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        writeText(root, "etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost\n")
        // apt's sandbox user can't be switched to under proot (harmless on other distros)
        if (File(root, "etc/apt").exists()) {
            writeText(root, "etc/apt/apt.conf.d/01-android-nosandbox", "APT::Sandbox::User \"root\";\n")
        }
    }

    private fun writeText(root: File, rel: String, text: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        FsUtil.deleteTree(f)
        f.writeText(text)
        Os.chmod(f.path, 420) // 0644
    }
}
