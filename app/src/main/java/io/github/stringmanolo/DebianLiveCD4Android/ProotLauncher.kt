package io.github.stringmanolo.DebianLiveCD4Android

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.FileInputStream

object ProotLauncher {

    fun createSession(ctx: Context, rootDir: File, client: TerminalSessionClient): TerminalSession {
        val lib = Paths.nativeLibDir(ctx)
        val root = rootDir.absolutePath
        val tmp = Paths.tmpDir(ctx).apply { mkdirs() }
        File(root, "tmp").mkdirs()

        val args = ArrayList<String>()
        args += listOf(
            "proot", "--kill-on-exit", "--link2symlink", "-0", "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "$root/tmp:/dev/shm"
        )
        for ((name, f) in fakeProc(ctx)) args += listOf("-b", "${f.absolutePath}:/proc/$name")
        args += listOf(
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "TERM=xterm-256color", "COLORTERM=truecolor", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "--login"
        )

        val env = ArrayList<String>()
        env += "PROOT_TMP_DIR=${tmp.absolutePath}"
        env += "PROOT_LOADER=$lib/libproot-loader.so"
        if (File(lib, "libproot-loader32.so").exists()) env += "PROOT_LOADER_32=$lib/libproot-loader32.so"
        env += "LD_LIBRARY_PATH=$lib"
        env += "PATH=/system/bin"
        env += "HOME=${ctx.filesDir.absolutePath}"
        env += "TMPDIR=${tmp.absolutePath}"

        return TerminalSession(
            "$lib/libproot.so", ctx.filesDir.absolutePath,
            args.toTypedArray(), env.toTypedArray(), 5000, client
        )
    }

    private fun readable(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }; true
    } catch (e: Exception) { false }

    /** Android 8+ hides several /proc files from apps; bind plausible replacements for the ones we can't read. */
    private fun fakeProc(ctx: Context): Map<String, File> {
        val dir = Paths.fakeProcDir(ctx).apply { mkdirs() }
        val out = LinkedHashMap<String, File>()
        fun add(name: String, content: String) {
            if (readable("/proc/$name")) return
            val f = File(dir, name)
            f.writeText(content)
            out[name] = f
        }
        add("stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 1957 0 2877 93280 262 342 254 87 0 0\n" +
                "intr 63361 0 0 0\nctxt 38014093\nbtime 1767225600\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n")
        add("loadavg", "0.54 0.41 0.30 1/1043 12345\n")
        add("uptime", "284684.56 513853.46\n")
        add("version", "Linux version 6.1.0 (proot@android) (gcc 12.2.0) #1 SMP PREEMPT Thu Jan 1 00:00:00 UTC 2026\n")
        return out
    }
}
