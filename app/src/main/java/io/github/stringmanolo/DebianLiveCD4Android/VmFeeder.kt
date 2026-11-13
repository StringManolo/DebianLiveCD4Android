package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import android.util.Log
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Serves the rootfs to the guest over loopback; QEMU connects to it and exposes it as a virtio-console port
 * (/dev/vport0p1 inside the guest). The guest unpacks the tar into a tmpfs, so nothing is written to disk:
 *  - LIVECD: the tarball shipped in the APK, decompressed on the fly
 *  - SYSTEM: the installed tree, tarred on the fly
 */
class VmFeeder(private val ctx: Context, private val systemRoot: File?) {

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed = false

    val port: Int get() = server.localPort

    fun start() {
        Thread({
            try {
                server.accept().use { s ->
                    val out = s.getOutputStream().buffered(1 shl 16)
                    if (systemRoot != null) {
                        TarWriter.write(systemRoot, out)
                    } else {
                        ctx.assets.open(Paths.ROOTFS_ASSET).use { raw ->
                            XZCompressorInputStream(BufferedInputStream(raw, 1 shl 16)).use { it.copyTo(out, 1 shl 16) }
                        }
                    }
                    out.flush()
                    s.shutdownOutput()
                    // keep the socket open until the session ends so QEMU can drain it
                    while (!closed && !s.isClosed) Thread.sleep(500)
                }
            } catch (e: Exception) {
                if (!closed) Log.w("VmFeeder", "feeding failed: $e")
            }
        }, "vm-feeder").start()
    }

    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
    }
}
