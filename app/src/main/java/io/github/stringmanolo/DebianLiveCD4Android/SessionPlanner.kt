package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream

/** A prepared environment: how to start the terminal session and how to clean up after it. */
class Plan(
    val createSession: (TerminalSessionClient) -> TerminalSession,
    val cleanup: () -> Unit
)

/**
 * Turns (target, mode) into a runnable [Plan]. Runs on a background thread; may take a while
 * (unlock / extract / copy), reporting through [progress].
 *
 * Storage areas never interfere with each other (see [SystemStore]):
 *  - a System is written only by SYSTEM + NORMAL, and only the *selected* one
 *  - the LiveCD persistence is written only by LIVECD + NORMAL
 *  - EPHEMERAL runs live in files/eph/<id> and are wiped on exit / next launch
 *  - VM writes nothing to disk (encrypted systems are decrypted on the fly straight into the guest)
 * Nothing is ever installed implicitly. The only cross-access is explicit: a LiveCD session may bind the selected
 * System at /mnt/system when the user enables it.
 */
object SessionPlanner {

    private fun assetTar(ctx: Context): (OutputStream) -> Unit = { out ->
        ctx.assets.open(Paths.ROOTFS_ASSET).use { raw ->
            XZCompressorInputStream(BufferedInputStream(raw, 1 shl 16)).use { it.copyTo(out, 1 shl 16) }
        }
    }

    fun prepare(ctx: Context, cfg: AppConfig, progress: Progress): Plan {
        Ephemeral.sweep(ctx) // leftovers from crashed ephemeral sessions only
        SystemStore.migrateLegacy()
        SystemStore.recover(progress)
        val installer = RootfsInstaller(ctx)
        val quiet: Progress = { _, _ -> }

        fun activeSystem(): String {
            val id = Cfg.activeSystem
            if (id.isEmpty() || SystemStore.info(id)?.installed != true) {
                throw IllegalStateException("No system is selected. Create or select one in Settings > Systems (nothing is installed automatically).")
            }
            return id
        }

        /** LiveCD only: optionally open the selected System and bind it at /mnt/system. */
        fun mountSelected(root: File): Pair<List<Pair<String, String>>, () -> Unit> {
            val id = Cfg.activeSystem
            if (!cfg.mountSystem || id.isEmpty() || SystemStore.info(id)?.installed != true) {
                return Pair(emptyList<Pair<String, String>>(), {})
            }
            val sysRoot = SystemStore.openWorking(id, progress)
            File(root, "mnt/system").mkdirs()
            val close: () -> Unit = { SystemStore.closeWorking(id, quiet) }
            return Pair(listOf(sysRoot.absolutePath to "/mnt/system"), close)
        }

        return when (cfg.mode) {
            Mode.NORMAL -> {
                if (cfg.target == Target.SYSTEM) {
                    val id = activeSystem()
                    val root = SystemStore.openWorking(id, progress)
                    Plan({ c -> ProotLauncher.createSession(ctx, root, c) }, { SystemStore.closeWorking(id, quiet) })
                } else {
                    SystemStore.ensureLivecd(progress)
                    val root = SystemStore.openWorking(SystemStore.LIVECD_ID, progress)
                    val (binds, closeMount) = try { mountSelected(root) } catch (t: Throwable) {
                        SystemStore.closeWorking(SystemStore.LIVECD_ID, quiet); throw t
                    }
                    Plan({ c -> ProotLauncher.createSession(ctx, root, c, binds) }, {
                        SystemStore.closeWorking(SystemStore.LIVECD_ID, quiet)
                        closeMount()
                    })
                }
            }

            Mode.EPHEMERAL -> {
                val dir = Ephemeral.newDir(ctx)
                var binds: List<Pair<String, String>> = emptyList()
                var closeMount: () -> Unit = {}
                try {
                    if (cfg.target == Target.SYSTEM) {
                        progress(0, "Copying system to a temporary directory…")
                        SystemStore.extractInto(activeSystem(), dir, progress) // read-only use of the source
                    } else {
                        installer.extractAsset(dir, progress)
                        val m = mountSelected(dir)
                        binds = m.first
                        closeMount = m.second
                    }
                } catch (t: Throwable) {
                    FsUtil.wipeTree(dir, WipeMode.NONE)
                    throw t
                }
                val b = binds
                val cm = closeMount
                Plan({ c -> ProotLauncher.createSession(ctx, dir, c, b) }, {
                    FsUtil.wipeTree(dir, if (cfg.secureWipe) WipeMode.RANDOM else WipeMode.NONE)
                    cm()
                })
            }

            Mode.VM -> {
                val missing = QemuLauncher.missingParts(ctx)
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("VM mode is not available in this build (missing: ${missing.joinToString()})")
                }
                val writer: (OutputStream) -> Unit
                if (cfg.target == Target.SYSTEM) {
                    val id = activeSystem()
                    writer = { out -> SystemStore.streamTar(id, out) }
                } else {
                    writer = assetTar(ctx)
                }
                progress(50, "Starting VM…")
                QemuLauncher.prepareFiles(ctx)
                val feeder = VmFeeder(writer)
                feeder.start()
                Plan({ c -> QemuLauncher.createSession(ctx, cfg, feeder.port, c) }, { feeder.close() })
            }
        }
    }
}
