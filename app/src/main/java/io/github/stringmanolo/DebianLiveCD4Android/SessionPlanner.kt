package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/** A prepared environment: how to start the terminal session and how to clean up after it. */
class Plan(
    val createSession: (TerminalSessionClient) -> TerminalSession,
    val cleanup: () -> Unit
)

/**
 * Turns (target, mode) into a runnable [Plan]. Runs on a background thread; may take a while
 * (install / extract / copy), reporting through [progress].
 *
 *            NORMAL                         EPHEMERAL                          VM
 * SYSTEM     proot on installed Debian      proot on a temp copy, wiped        QEMU, installed tree streamed
 *                                           on exit                            into guest RAM
 * LIVECD     proot on a persistent          proot on a fresh extraction of     QEMU, shipped image streamed
 *            extraction of the image        the image, wiped on exit           into guest RAM
 */
object SessionPlanner {

    fun prepare(ctx: Context, cfg: AppConfig, progress: (Int, String) -> Unit): Plan {
        Ephemeral.sweep(ctx) // leftovers from crashed ephemeral sessions
        val installer = RootfsInstaller(ctx)

        fun ensureSystem(): File {
            val sys = Paths.systemRoot(ctx)
            if (!installer.isInstalled(sys)) installer.installFromAsset(sys, progress)
            return sys
        }

        return when (cfg.mode) {
            Mode.NORMAL -> {
                val root = if (cfg.target == Target.SYSTEM) ensureSystem()
                else Paths.livecdPersistRoot(ctx).also { if (!installer.isInstalled(it)) installer.installFromAsset(it, progress) }
                Plan({ c -> ProotLauncher.createSession(ctx, root, c) }, {})
            }

            Mode.EPHEMERAL -> {
                val dir = Ephemeral.newDir(ctx)
                try {
                    if (cfg.target == Target.SYSTEM) {
                        val sys = ensureSystem()
                        progress(0, "Copying system to a temporary directory…")
                        FsUtil.copyTree(sys, dir) { n -> progress(50, "Copying system… $n files") }
                    } else {
                        installer.extractAsset(dir, progress)
                    }
                } catch (t: Throwable) {
                    FsUtil.wipeTree(dir, false)
                    throw t
                }
                Plan({ c -> ProotLauncher.createSession(ctx, dir, c) }, { FsUtil.wipeTree(dir, cfg.secureWipe) })
            }

            Mode.VM -> {
                if (!QemuLauncher.available(ctx)) {
                    throw IllegalStateException("VM mode is not available in this build (QEMU/kernel assets missing)")
                }
                val sysRoot = if (cfg.target == Target.SYSTEM) ensureSystem() else null
                progress(50, "Starting VM…")
                QemuLauncher.prepareFiles(ctx)
                val feeder = VmFeeder(ctx, sysRoot)
                feeder.start()
                Plan({ c -> QemuLauncher.createSession(ctx, cfg, feeder.port, c) }, { feeder.close() })
            }
        }
    }
}
