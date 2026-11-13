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

/** Extracts the rootfs tarball shipped in assets (the "livecd" image) into a directory. No external tools. */
class RootfsInstaller(private val ctx: Context) {

    fun isInstalled(root: File): Boolean = Paths.okMarker(root).exists()

    /** Persistent install: wipes [root], extracts, then drops the "installed" marker. */
    fun installFromAsset(root: File, onProgress: (Int, String) -> Unit) {
        FsUtil.deleteTree(Paths.okMarker(root))
        extractAsset(root, onProgress)
        Paths.okMarker(root).apply { parentFile?.mkdirs(); writeText(System.currentTimeMillis().toString()) }
    }

    /** Extracts into [root] (created if needed). On failure the partial tree is removed. */
    fun extractAsset(root: File, onProgress: (Int, String) -> Unit) {
        FsUtil.deleteTree(root)
        root.mkdirs()
        try {
            val raw = ctx.assets.open(Paths.ROOTFS_ASSET, AssetManager.ACCESS_STREAMING)
            val total = raw.available().toLong().coerceAtLeast(1L)
            val counting = CountingInputStream(raw)
            var last = 0L
            TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(counting, 1 shl 16))).use { tar ->
                var e: TarArchiveEntry? = tar.nextTarEntry
                while (e != null) {
                    extract(tar, e, root)
                    val now = System.currentTimeMillis()
                    if (now - last > 150) {
                        last = now
                        onProgress((counting.bytesRead * 100 / total).toInt().coerceIn(0, 99), "Extracting ${e.name}")
                    }
                    e = tar.nextTarEntry
                }
            }
            postSetup(root)
            onProgress(100, "done")
        } catch (t: Throwable) {
            FsUtil.deleteTree(root)
            throw t
        }
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
        // apt's sandbox user can't be switched to under proot
        writeText(root, "etc/apt/apt.conf.d/01-android-nosandbox", "APT::Sandbox::User \"root\";\n")
    }

    private fun writeText(root: File, rel: String, text: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        FsUtil.deleteTree(f)
        f.writeText(text)
        Os.chmod(f.path, 420) // 0644
    }
}
