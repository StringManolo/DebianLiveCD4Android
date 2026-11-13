package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.FileOutputStream

/** VM mode: qemu-system-aarch64 (TCG, no KVM) booting a tiny initramfs that receives the rootfs into RAM. */
object QemuLauncher {

    private fun hasAsset(ctx: Context, name: String) = try { ctx.assets.open(name).close(); true } catch (e: Exception) { false }

    /** True only if this build shipped QEMU, the kernel and the initramfs (see .github/actions/prepare-assets). */
    fun available(ctx: Context): Boolean =
        File(Paths.nativeLibDir(ctx), "libqemu.so").exists() &&
            hasAsset(ctx, "vm/Image") && hasAsset(ctx, "vm/initramfs.cpio.gz")

    /** Kernel and initramfs must be real files for QEMU; they are not secret, so they may live on disk. */
    fun prepareFiles(ctx: Context): File {
        val dir = Paths.vmDir(ctx).apply { mkdirs() }
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(dir, "version")
        if (marker.exists() && marker.readText() == stamp && File(dir, "Image").exists()) return dir
        for (n in listOf("Image", "initramfs.cpio.gz")) {
            ctx.assets.open("vm/$n").use { i -> FileOutputStream(File(dir, n)).use { o -> i.copyTo(o, 1 shl 16) } }
        }
        marker.writeText(stamp)
        return dir
    }

    fun createSession(ctx: Context, cfg: AppConfig, feederPort: Int, client: TerminalSessionClient): TerminalSession {
        val lib = Paths.nativeLibDir(ctx)
        val vm = Paths.vmDir(ctx)
        val tmp = Paths.tmpDir(ctx).apply { mkdirs() }

        val args = arrayListOf(
            "qemu-system-aarch64",
            "-machine", "virt", "-cpu", "max",
            "-smp", cfg.vmCpus.toString(), "-m", cfg.vmRamMb.toString(),
            "-accel", "tcg,thread=multi",
            "-kernel", File(vm, "Image").path,
            "-initrd", File(vm, "initramfs.cpio.gz").path,
            "-append", "console=ttyAMA0 loglevel=3 panic=-1 rdinit=/init",
            "-display", "none", "-monitor", "none", "-no-reboot",
            // console on the pty; signal=off so Ctrl-C reaches the guest instead of killing QEMU
            "-chardev", "stdio,id=con,signal=off", "-serial", "chardev:con",
            "-netdev", "user,id=n0", "-device", "virtio-net-device,netdev=n0",
            // rootfs channel: QEMU connects to VmFeeder; guest sees it as /dev/vport0p1
            "-device", "virtio-serial-device",
            "-chardev", "socket,id=rootfs,host=127.0.0.1,port=$feederPort",
            "-device", "virtserialport,chardev=rootfs,name=rootfs"
        )
        val env = arrayOf(
            "LD_LIBRARY_PATH=$lib",
            "PATH=/system/bin",
            "HOME=${ctx.filesDir.absolutePath}",
            "TMPDIR=${tmp.absolutePath}"
        )
        return TerminalSession(
            "$lib/libqemu.so", ctx.filesDir.absolutePath,
            args.toTypedArray(), env, 5000, client
        )
    }
}
