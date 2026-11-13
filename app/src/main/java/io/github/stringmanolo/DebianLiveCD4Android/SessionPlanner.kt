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
 * Storage areas never interfere with each other:
 *  - System   files/rootfs/system          written only by SYSTEM + NORMAL (and its one-time install)
 *  - LiveCD   files/rootfs/livecd-persist  written only by LIVECD + NORMAL
 *  - temp     files/eph/*                  EPHEMERAL runs only; wiped on exit / next launch
 *  - VM       nothing on disk
 * Nothing is ever installed implicitly: System + EPHEMERAL/VM require an already installed System.
 * The only cross-access is explicit: a LiveCD session (proot modes) may bind the installed System at
 * /mnt/system when the user enables it, like a real live CD mounting the hard disk.
 */
object SessionPlanner {

    fun prepare(ctx: Context, cfg: AppConfig, progress: (Int, String) -> Unit): Plan {
        Ephemeral.sweep(ctx) // leftovers from crashed ephemeral sessions only
        val installer = RootfsInstaller(ctx)
        val sys = Paths.systemRoot(ctx)

        fun installedSystemOrThrow(): File {
            if (!installer.isInstalled(sys)) {
                throw IllegalStateException(
                    "The System is not installed. Choose System + Normal once to install it " +
                        "(nothing is installed automatically)."
                )
            }
            return sys
        }

        /** LiveCD only: optional bind of the installed System. */
        fun liveCdBinds(root: File): List<Pair<String, String>> {
            if (!cfg.mountSystem || !installer.isInstalled(sys)) return emptyList()
            File(root, "mnt/system").mkdirs()
            return listOf(sys.absolutePath to "/mnt/system")
        }

        return when (cfg.mode) {
            Mode.NORMAL -> {
                if (cfg.target == Target.SYSTEM) {
                    if (!installer.isInstalled(sys)) installer.installFromAsset(sys, progress) // explicit choice
                    Plan({ c -> ProotLauncher.createSession(ctx, sys, c) }, {})
                } else {
                    val root = Paths.livecdPersistRoot(ctx)
                    if (!installer.isInstalled(root)) installer.installFromAsset(root, progress)
                    val binds = liveCdBinds(root)
                    Plan({ c -> ProotLauncher.createSession(ctx, root, c, binds) }, {})
                }
            }

            Mode.EPHEMERAL -> {
                val dir = Ephemeral.newDir(ctx)
                var binds: List<Pair<String, String>> = emptyList()
                try {
                    if (cfg.target == Target.SYSTEM) {
                        val src = installedSystemOrThrow() // read-only use: copied, never modified
                        progress(0, "Copying system to a temporary directory…")
                        FsUtil.copyTree(src, dir) { n -> progress(50, "Copying system… $n files") }
                    } else {
                        installer.extractAsset(dir, progress)
                        binds = liveCdBinds(dir)
                    }
                } catch (t: Throwable) {
                    FsUtil.wipeTree(dir, false)
                    throw t
                }
                val b = binds
                Plan({ c -> ProotLauncher.createSession(ctx, dir, c, b) }, { FsUtil.wipeTree(dir, cfg.secureWipe) })
            }

            Mode.VM -> {
                val missing = QemuLauncher.missingParts(ctx)
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("VM mode is not available in this build (missing: ${missing.joinToString()})")
                }
                val sysRoot = if (cfg.target == Target.SYSTEM) installedSystemOrThrow() else null
                progress(50, "Starting VM…")
                QemuLauncher.prepareFiles(ctx)
                val feeder = VmFeeder(ctx, sysRoot)
                feeder.start()
                Plan({ c -> QemuLauncher.createSession(ctx, cfg, feeder.port, c) }, { feeder.close() })
            }
        }
    }
}
