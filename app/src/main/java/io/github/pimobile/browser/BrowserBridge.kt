package io.github.pimobile.browser

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Exposes the app's WebViews to pi over the Chrome DevTools Protocol.
 *
 * Android serves WebView DevTools on an abstract unix socket that only this
 * app (same uid) can open. pi runs in a child process, so we relay it on
 * 127.0.0.1. Any app on the device can reach localhost, so the bridge uses a
 * random port and every path must start with a random secret token that only
 * our pi processes receive (in their environment).
 *
 *   GET  /<token>/open?task=<id>        -> {"ws": "/<token>/devtools/page/<target>"}
 *   ANY  /<token>/devtools/...           -> proxied to WebView DevTools (HTTP + WebSocket)
 */
object BrowserBridge {
    @Volatile var port = 0
        private set
    val token: String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private val devtoolsSocket get() = "webview_devtools_remote_${Process.myPid()}"

    /** Base URL handed to pi (its extension appends /open, /devtools/...). */
    val baseUrl get() = "http://127.0.0.1:$port/$token"

    @Synchronized
    fun start() {
        if (port != 0) return
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(name = "cdp-bridge", isDaemon = true) {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: continue
                thread(name = "cdp-conn", isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
    }

    private class Request(val method: String, val path: String, val headers: List<Pair<String, String>>)

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val out = client.getOutputStream()
        val req = readRequest(input) ?: return
        val prefix = "/$token/"
        if (!req.path.startsWith(prefix)) return respond(out, 404, "{}")
        val rest = req.path.removePrefix(prefix.dropLast(1))
        when {
            rest.startsWith("/open") -> {
                val task = Regex("task=([A-Za-z0-9_.-]+)").find(rest)?.groupValues?.get(1)
                    ?: return respond(out, 400, """{"error":"task required"}""")
                val target = runCatching { BrowserManager.targetFor(task) }.getOrElse {
                    return respond(out, 500, JSONObject().put("error", it.message ?: it.toString()).toString())
                }
                respond(out, 200, JSONObject().put("ws", "/$token/devtools/page/$target").toString())
            }
            // xdg-open / termux-open-url shims: open a link in the user's browser.
            rest.startsWith("/app/open") -> {
                val url = body(req, input).trim()
                if (!Regex("^(https?|mailto|tel|market|intent):.+", RegexOption.IGNORE_CASE).matches(url))
                    return respond(out, 400, """{"error":"only http(s)/mailto/tel links"}""")
                val ok = runCatching { AppLinks.open(url) }.isSuccess
                respond(out, if (ok) 200 else 500, "{}")
            }
            // termux-clipboard-set / -get shims.
            rest.startsWith("/app/clipboard") -> if (req.method == "POST") {
                AppLinks.setClipboard(body(req, input)); respond(out, 200, "{}")
            } else respondText(out, AppLinks.getClipboard())
            // termux-* phone feature commands: body = cwd \0 artifacts \0 args…
            rest.startsWith("/app/device/") -> {
                val cmd = rest.removePrefix("/app/device/").substringBefore('?')
                val argv = body(req, input).split('\u0000').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
                runCatching { io.github.pimobile.device.Device.run(cmd, argv) }
                    .onSuccess { respondText(out, it) }
                    .onFailure { e -> respondText(out, (e.message ?: e.toString()), 400) }
            }
            // saturn-schedule: body = cwd \0 artifacts \0 args…
            rest.startsWith("/app/schedule") -> {
                val argv = body(req, input).split('\u0000').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
                runCatching { io.github.pimobile.runtime.Scheduler.cli(argv) }
                    .onSuccess { respondText(out, it) }
                    .onFailure { e -> respondText(out, (e.message ?: e.toString()), 400) }
            }
            // DevTools serves its WebSocket targets under /devtools/page/<id>.
            rest.startsWith("/devtools/") -> proxy(req, rest, input, out)
            else -> respond(out, 404, "{}")
        }
    }

    /** Byte-level relay; works for plain HTTP and WebSocket upgrades alike. */
    private fun proxy(req: Request, path: String, clientIn: InputStream, clientOut: OutputStream) {
        val dev = LocalSocket().apply { connect(LocalSocketAddress(devtoolsSocket, LocalSocketAddress.Namespace.ABSTRACT)) }
        val head = StringBuilder("${req.method} $path HTTP/1.1\r\n")
        for ((k, v) in req.headers) {
            // DevTools rejects non-localhost Host headers (DNS-rebinding protection).
            head.append(if (k.equals("host", true)) "Host: localhost" else "$k: $v").append("\r\n")
        }
        head.append("\r\n")
        val devOut = dev.outputStream
        devOut.write(head.toString().toByteArray()); devOut.flush()
        val up = thread(isDaemon = true) { runCatching { clientIn.copyTo(devOut) }; runCatching { dev.shutdownOutput() } }
        runCatching { dev.inputStream.copyTo(clientOut) }
        runCatching { dev.close() }
        up.interrupt()
    }

    /** GET /json/list from WebView DevTools (used to find a WebView's target id). */
    fun listTargets(): JSONArray {
        LocalSocket().use { s ->
            s.connect(LocalSocketAddress(devtoolsSocket, LocalSocketAddress.Namespace.ABSTRACT))
            s.soTimeout = 5000
            s.outputStream.write("GET /json/list HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            // DevTools keeps the connection open, so read exactly Content-Length bytes.
            val input = s.inputStream
            val head = readHead(input) ?: error("no DevTools response")
            val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt()
                ?: error("DevTools response without Content-Length")
            val body = ByteArray(length)
            var read = 0
            while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
            return JSONArray(String(body, 0, read, Charsets.UTF_8))
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString(Charsets.ISO_8859_1.name())
            if (buf.size() > 16 * 1024) return null
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val lines = (readHead(input) ?: return null).split("\r\n").filter { it.isNotEmpty() }
        val (method, path) = lines.first().split(" ").let { it[0] to it[1] }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }
        return Request(method, path, headers)
    }

    private fun body(req: Request, input: InputStream): String {
        val n = req.headers.firstOrNull { it.first.equals("content-length", true) }?.second?.toIntOrNull() ?: 0
        val buf = ByteArray(n.coerceAtMost(1 shl 20))
        var read = 0
        while (read < buf.size) { val r = input.read(buf, read, buf.size - read); if (r < 0) break; read += r }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun respondText(out: OutputStream, body: String, code: Int = 200) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $code OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }
}
