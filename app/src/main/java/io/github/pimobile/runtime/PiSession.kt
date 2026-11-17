package io.github.pimobile.runtime

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * One unmodified `pi --mode rpc` process bound to a persistent session id.
 * Restarting with the same [sessionId] and [cwd] resumes the conversation.
 */
class PiSession(
    private val runtime: PiRuntime,
    /** null = in-memory session (`--no-session`), used by the app's control process. */
    val sessionId: String?,
    val cwd: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onRecord(record: JSONObject)
        fun onLog(line: String)
        fun onExit(code: Int)
    }

    private var proc: Process? = null
    private var stdin: OutputStream? = null
    private var nextId = 0

    val isAlive get() = proc?.isAlive == true

    /** Blocking; call off the main thread. */
    fun start(extraEnv: Map<String, String> = emptyMap(), extraArgs: List<String> = emptyList()) {
        stop()
        runtime.prepare(listener::onLog)
        cwd.mkdirs()
        val cmd = listOf(
            runtime.node.path, runtime.piCli.path,
            "--mode", "rpc",
        ) + (if (sessionId != null) listOf("--session-id", sessionId) else listOf("--no-session")) + extraArgs
        val pb = ProcessBuilder(cmd).directory(cwd)
        pb.environment().putAll(runtime.env() + extraEnv)
        val p = pb.start()
        proc = p
        stdin = p.outputStream
        thread("pi-$sessionId-out") { readJsonl(p.inputStream) }
        thread("pi-$sessionId-err") { p.errorStream.bufferedReader().forEachLine(listener::onLog) }
        thread("pi-$sessionId-wait") { listener.onExit(p.waitFor()) }
    }

    /** Sends one RPC command; adds an `id` if missing. Thread-safe, non-blocking. */
    fun send(cmd: JSONObject) {
        val out = stdin ?: return listener.onLog("pi is not running")
        if (!cmd.has("id")) cmd.put("id", "c${nextId++}")
        thread("pi-$sessionId-in") {
            runCatching {
                synchronized(out) {
                    out.write((cmd.toString() + "\n").toByteArray())
                    out.flush()
                }
            }.onFailure { listener.onLog("send failed: ${it.message}") }
        }
    }

    /** Orderly shutdown (stdin EOF), then SIGTERM, then SIGKILL. */
    fun stop() {
        val p = proc ?: return
        proc = null
        runCatching { stdin?.close() }
        stdin = null
        if (!p.waitFor(3, TimeUnit.SECONDS)) {
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Strict JSONL: split only on LF (JSON strings may contain U+2028/2029). */
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
            .onSuccess(listener::onRecord)
            .onFailure { listener.onLog("non-JSON stdout: $line") }
    }

    private fun thread(name: String, body: () -> Unit) =
        Thread(body, name).apply { isDaemon = true; start() }
}
