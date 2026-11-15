package io.github.stringmanolo.DebianLiveCD4Android

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP helpers and the rootfs catalog. Everything here refuses to run when the network is disabled in Settings. */
object Downloader {
    class Resolved(val url: String, val sha256: String?, val name: String)
    class Entry(val label: String, val resolve: () -> Resolved)

    fun requireNetwork() {
        if (!Cfg.allowNetwork) throw IOException("The network is disabled (Settings > Network).")
    }

    /** GET with manual redirect handling (http <-> https included). */
    fun open(url: String): HttpURLConnection {
        requireNetwork()
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "DebianLiveCD4Android")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without Location")
                u = URL(URL(u), loc).toString()
                c.disconnect()
            } else if (code == 200) {
                return c
            } else {
                throw IOException("HTTP $code for $u")
            }
        }
        throw IOException("too many redirects")
    }

    /** Download size in bytes from the response headers (-1 if the server does not say). The body is not read. */
    fun size(url: String): Long {
        val c = open(url)
        val n = c.contentLengthLong
        c.disconnect()
        return n
    }

    fun text(url: String): String = open(url).inputStream.bufferedReader().use { it.readText() }

    private fun firstHash(s: String): String? = Regex("[0-9a-fA-F]{64}").find(s)?.value

    private fun alpine(): Resolved {
        val base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/"
        val yaml = text(base + "latest-releases.yaml")
        val file = Regex("alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz").find(yaml)?.value
            ?: throw IOException("could not find the Alpine minirootfs in latest-releases.yaml")
        val sha = runCatching { firstHash(text("$base$file.sha256")) }.getOrNull()
        return Resolved(base + file, sha, "Alpine " + file.removePrefix("alpine-minirootfs-").substringBefore("-aarch64"))
    }

    private fun ubuntu(): Resolved {
        val base = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
        val html = text(base)
        val files = Regex("ubuntu-base-24\\.04(?:\\.\\d+)?-base-arm64\\.tar\\.gz").findAll(html).map { it.value }.toSet()
        val file = files.maxByOrNull { Regex("\\d+").findAll(it).map { m -> m.value.toInt() }.toList().joinToString(".") { n -> "%04d".format(n) } }
            ?: throw IOException("could not find Ubuntu Base in the directory listing")
        val sums = runCatching { text(base + "SHA256SUMS") }.getOrDefault("")
        val sha = sums.lineSequence().firstOrNull { it.contains(file) }?.let { firstHash(it) }
        return Resolved(base + file, sha, "Ubuntu " + file.removePrefix("ubuntu-base-").substringBefore("-base"))
    }

    val CATALOG = listOf(
        Entry("Alpine Linux, minirootfs (≈3 MB download, ≈10 MB installed; checksum verified)") { alpine() },
        Entry("Ubuntu Base 24.04 LTS (≈30 MB download, ≈100 MB installed; checksum verified)") { ubuntu() },
        Entry("Arch Linux ARM (≈500 MB download, ≈1.5 GB installed; no checksum available)") {
            Resolved("https://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz", null, "Arch Linux ARM")
        }
    )
}
