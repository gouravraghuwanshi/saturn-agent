package io.github.pimobile.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The GitHub CLI isn't shipped inside the APK (it's 42 MB unpacked and most people never sign in to
 * GitHub). The first time it's needed (GitHub sign-in, or the agent running `gh`), Saturn downloads
 * the exact same Termux build from its own GitHub release and checks its SHA-256 before using it.
 *
 * Android won't exec() files in app storage, so it runs through the system loader:
 * `/system/bin/linker64 <file> args…` (gh is a normal dynamically linked Android executable).
 */
object GhTool {
    const val VERSION = "2.105.0"
    private const val URL_GZ =
        "https://github.com/gouravraghuwanshi/saturn-agent/releases/download/tools-gh-$VERSION/gh-$VERSION-android-arm64.gz"
    /** SHA-256 of the unpacked binary. */
    private const val SHA256 = "6958c51c3e657173d9bca9465c0c56542d5c7346ee3bdb3e3381757e02183aad"
    const val LINKER = "/system/bin/linker64"

    fun file(ctx: Context) = File(PiRuntime.get(ctx).usr, "opt/gh-$VERSION/gh")
    private fun marker(ctx: Context) = File(file(ctx).parentFile, ".verified")

    fun installed(ctx: Context) = file(ctx).isFile && marker(ctx).isFile

    private val lock = Any()

    /** Downloads and verifies gh if it isn't here yet. Blocking; throws IOException with a plain message. */
    fun ensure(ctx: Context) {
        if (installed(ctx)) return
        synchronized(lock) {
            if (installed(ctx)) return
            val dest = file(ctx)
            dest.parentFile!!.mkdirs()
            val tmp = File(dest.parentFile, "gh.download")
            try {
                val conn = (URL(URL_GZ).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                }
                if (conn.responseCode != 200) throw IOException("couldn't download the GitHub CLI (HTTP ${conn.responseCode})")
                val sha = MessageDigest.getInstance("SHA-256")
                GZIPInputStream(conn.inputStream.buffered()).use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            sha.update(buf, 0, n); out.write(buf, 0, n)
                        }
                    }
                }
                val got = sha.digest().joinToString("") { "%02x".format(it) }
                if (got != SHA256) throw IOException("the downloaded GitHub CLI didn't match its checksum, so it wasn't used")
                tmp.setExecutable(true, false)
                if (!tmp.renameTo(dest)) throw IOException("couldn't save the GitHub CLI")
                marker(ctx).writeText(SHA256)
                // Older versions of gh downloaded by earlier builds.
                dest.parentFile!!.parentFile!!.listFiles()?.filter { it.name.startsWith("gh-") && it.name != "gh-$VERSION" }?.forEach { it.deleteRecursively() }
            } catch (e: IOException) {
                tmp.delete(); throw e
            } catch (e: Exception) {
                tmp.delete(); throw IOException("couldn't download the GitHub CLI: ${e.message ?: e}", e)
            }
        }
    }

    /**
     * git's credential helper names the gh executable. Builds up to 0.5.5 shipped gh in the APK, so
     * existing sign-ins point at a file that's gone; point them (and new ones) at Saturn's `gh` command.
     */
    fun fixGitConfig(home: File, ghCommand: File) {
        val cfg = File(home, ".gitconfig")
        if (!cfg.isFile) return
        val text = runCatching { cfg.readText() }.getOrNull() ?: return
        val fixed = text.replace(Regex("""(helper\s*=\s*)!\S*?(?:libx_gh\.so|/gh)['"]?\s+auth git-credential""")) {
            "${it.groupValues[1]}!/system/bin/sh ${ghCommand.path} auth git-credential"
        }
        if (fixed != text) runCatching { cfg.writeText(fixed) }
    }
}
