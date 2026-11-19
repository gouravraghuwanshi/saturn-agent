package io.github.pimobile.phonecontrol

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A minimal MCP server (Streamable HTTP transport, JSON responses, no SSE)
 * on 127.0.0.1. Any app on the phone can reach localhost, so every request
 * needs the Bearer token shown in the app, and browser-originated requests
 * (an Origin header) are refused.
 */
object McpServer {
    const val DEFAULT_PORT = 47811
    private const val NAME = "phone-control"

    private lateinit var prefs: android.content.SharedPreferences
    private var server: ServerSocket? = null

    var running by mutableStateOf(false)
        private set
    var port by mutableStateOf(DEFAULT_PORT)
        private set
    var token by mutableStateOf("")
        private set

    val url get() = "http://127.0.0.1:$port/mcp"

    fun load(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("mcp", Context.MODE_PRIVATE)
        token = prefs.getString("token", null) ?: newToken().also { prefs.edit().putString("token", it).apply() }
        port = prefs.getInt("port", DEFAULT_PORT)
    }

    private fun newToken() = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Invalidates every client's config; they need the new token. */
    fun regenerateToken() { token = newToken(); prefs.edit().putString("token", token).apply() }

    /** Config for MCP clients (pi's ~/.pi/agent/mcp.json and most others use this shape). */
    fun clientConfig(): String = JSONObject().put("mcpServers", JSONObject().put("phone", JSONObject()
        .put("url", url).put("headers", JSONObject().put("Authorization", "Bearer $token"))
        .put("description", "Read the phone's screen and tap, type and swipe in its apps"))).toString(2)

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        load(context)
        val s = runCatching { ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")) }.getOrElse {
            // Port taken: use a free one and remember it (clients need the new URL).
            ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).also { port = it.localPort; prefs.edit().putInt("port", port).apply() }
        }
        server = s
        running = true
        thread(name = "mcp-accept", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "mcp-conn", isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
    }

    @Synchronized
    fun stop() { runCatching { server?.close() }; server = null; running = false }

    // ------------------------------------------------------------------ HTTP

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private fun handle(c: Socket) {
        c.soTimeout = 120_000
        val input = c.getInputStream(); val out = c.getOutputStream()
        val req = read(input) ?: return
        val path = req.path.substringBefore('?')
        if (path != "/mcp") return reply(out, 404, null)
        // Web pages can reach 127.0.0.1 too; real MCP clients don't send Origin.
        req.headers["origin"]?.let { return reply(out, 403, error(null, -32600, "Origin not allowed")) }
        val auth = req.headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) return reply(out, 401, error(null, -32001, "Missing or wrong token"))
        when (req.method) {
            "POST" -> {
                val text = req.body.toString(Charsets.UTF_8).trim()
                val replies = if (text.startsWith("[")) JSONArray(text).let { a -> (0 until a.length()).mapNotNull { rpc(a.getJSONObject(it)) } }
                    else listOfNotNull(rpc(JSONObject(text)))
                when {
                    replies.isEmpty() -> reply(out, 202, null) // only notifications / responses
                    text.startsWith("[") -> reply(out, 200, JSONArray(replies).toString())
                    else -> reply(out, 200, replies[0].toString())
                }
            }
            "DELETE" -> reply(out, 200, null) // end of session: nothing to clean up
            else -> reply(out, 405, null)    // GET (server-to-client stream) isn't offered
        }
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16 * 1024) return null
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val (method, path) = lines.first().split(" ").let { it[0] to it.getOrElse(1) { "/" } }
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val n = (headers["content-length"]?.toIntOrNull() ?: 0).coerceIn(0, 1 shl 20)
        val body = ByteArray(n); var got = 0
        while (got < n) { val r = input.read(body, got, n - got); if (r < 0) break; got += r }
        return Request(method, path, headers, body)
    }

    private fun reply(out: OutputStream, code: Int, body: String?) {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val status = mapOf(200 to "OK", 202 to "Accepted", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed")[code] ?: "OK"
        val h = StringBuilder("HTTP/1.1 $code $status\r\n")
        if (body != null) h.append("Content-Type: application/json\r\n")
        if (code == 405) h.append("Allow: POST, DELETE\r\n")
        h.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.write(h.toString().toByteArray()); out.write(bytes); out.flush()
    }

    // --------------------------------------------------------------- JSON-RPC

    private fun error(id: Any?, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message)).toString()

    private const val INSTRUCTIONS = "Controls this Android phone's apps for the user. Work one step at a time: " +
        "phone_open_app or phone_screen, then act on the numbered elements and check the screen each action returns " +
        "(numbers change after every action). Do only what the user asked. Taps on Send, Pay, Delete, Install and similar " +
        "show the user an Allow prompt; if they don't allow it, stop and ask them. Never try to get around a refusal " +
        "(password fields, lock screen, permission prompts, the user's Stop)."

    /** One JSON-RPC message; null when no reply is due (notifications, responses). */
    private fun rpc(m: JSONObject): JSONObject? {
        if (!m.has("method")) return null
        val id = m.opt("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        fun ok(result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        if (id == null || id == JSONObject.NULL) return null // notification
        return when (m.getString("method")) {
            "initialize" -> ok(JSONObject()
                // We use only basics every version shares, so agree to the client's version.
                .put("protocolVersion", params.optString("protocolVersion").ifBlank { "2025-06-18" })
                .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                .put("serverInfo", JSONObject().put("name", NAME).put("version", BuildConfigVersion.name))
                .put("instructions", INSTRUCTIONS))
            "ping" -> ok(JSONObject())
            "tools/list" -> ok(JSONObject().put("tools", PhoneControl.tools))
            "tools/call" -> {
                val content = JSONArray()
                val isError = try {
                    val r = PhoneControl.call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
                    content.put(JSONObject().put("type", "text").put("text", r.text))
                    r.jpeg?.let { content.put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", Base64.encodeToString(it, Base64.NO_WRAP))) }
                    false
                } catch (e: PhoneControl.Fail) {
                    content.put(JSONObject().put("type", "text").put("text", e.message)); true
                } catch (e: Exception) {
                    content.put(JSONObject().put("type", "text").put("text", "Phone Control error: ${e.message ?: e}")); true
                }
                ok(JSONObject().put("content", content).put("isError", isError))
            }
            else -> JSONObject(error(id, -32601, "Method not found: ${m.optString("method")}"))
        }
    }
}

/** Version reported to MCP clients. */
private object BuildConfigVersion { const val name = "0.1.0" }
