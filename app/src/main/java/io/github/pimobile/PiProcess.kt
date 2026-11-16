package io.github.pimobile

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Runs the unmodified Pi Coding Agent as `pi --mode rpc` on the bundled
 * Bionic Node runtime and speaks Pi's JSONL protocol over stdin/stdout.
 */
class PiProcess(
    private val ctx: Context,
    private val onRecord: (JSONObject) -> Unit,
    private val onLog: (String) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    private var proc: Process? = null
    private var stdin: OutputStream? = null

    private val files get() = ctx.filesDir
    private val nativeDir get() = File(ctx.applicationInfo.nativeLibraryDir)
    private val piRoot get() = File(files, "pi")
    private val home get() = File(files, "home")
    val workspace get() = File(files, "workspace")

    val isRunning get() = proc?.isAlive == true

    /** Extracts assets/pi-bundle.zip into filesDir/pi when the bundled version changes. */
    fun install() {
        val want = ctx.assets.open("pi-bundle.version").bufferedReader().readText().trim()
        val stamp = File(piRoot, ".version")
        if (stamp.exists() && stamp.readText().trim() == want) return

        onLog("Installing Pi $want…")
        piRoot.deleteRecursively()
        piRoot.mkdirs()
        val rootPath = piRoot.canonicalPath + File.separator
        var count = 0
        ZipInputStream(ctx.assets.open("pi-bundle.zip").buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(piRoot, e.name)
                require(out.canonicalPath.startsWith(rootPath)) { "bad zip entry ${e.name}" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        }
        stamp.writeText(want)
        onLog("Installed Pi $want ($count files)")
    }

    fun start(extraEnv: Map<String, String>, extraArgs: List<String>) {
        stop()
        listOf(home, workspace, ctx.cacheDir).forEach { it.mkdirs() }
        // Termux's OpenSSL looks for a config under /data/data/com.termux; give it an empty one.
        val opensslConf = File(files, "openssl.cnf").apply { if (!exists()) writeText("") }

        val cli = File(piRoot, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")
        val cmd = listOf(
            File(nativeDir, "libnode.so").path,
            "--use-bundled-ca",
            cli.path,
            "--mode", "rpc",
            "--no-session",
        ) + extraArgs

        val pb = ProcessBuilder(cmd).directory(workspace)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", nativeDir.path)
            put("HOME", home.path)
            put("TMPDIR", ctx.cacheDir.path)
            put("OPENSSL_CONF", opensslConf.path)
            put("PATH", "${nativeDir.path}:/system/bin")
            put("SHELL", "/system/bin/sh")
            put("TERM", "xterm-256color")
            put("PI_SKIP_VERSION_CHECK", "1")
            putAll(extraEnv)
        }
        onLog("$ pi --mode rpc ${extraArgs.joinToString(" ")}")
        val p = pb.start()
        proc = p
        stdin = p.outputStream

        thread("pi-stdout") { readJsonl(p.inputStream) }
        thread("pi-stderr") {
            p.errorStream.bufferedReader().forEachLine { onLog("stderr: $it") }
        }
        thread("pi-wait") { onExit(p.waitFor()) }
    }

    fun send(cmd: JSONObject) {
        val out = stdin ?: return onLog("pi is not running")
        thread("pi-stdin") {
            runCatching {
                synchronized(out) {
                    out.write((cmd.toString() + "\n").toByteArray())
                    out.flush()
                }
            }.onFailure { onLog("send failed: ${it.message}") }
        }
    }

    fun stop() {
        val p = proc ?: return
        proc = null
        runCatching { stdin?.close() }
        stdin = null
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroy()
    }

    /** Pi's framing is strict JSONL: split only on LF (not U+2028/2029). */
    private fun readJsonl(input: InputStream) {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            var start = 0
            for (i in 0 until n) {
                if (chunk[i] == '\n'.code.toByte()) {
                    buf.write(chunk, start, i - start)
                    emit(buf.toString(Charsets.UTF_8.name()).removeSuffix("\r"))
                    buf.reset()
                    start = i + 1
                }
            }
            buf.write(chunk, start, n - start)
        }
    }

    private fun emit(line: String) {
        if (line.isBlank()) return
        runCatching { JSONObject(line) }
            .onSuccess(onRecord)
            .onFailure { onLog("non-JSON stdout: $line") }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true; start() }
}
