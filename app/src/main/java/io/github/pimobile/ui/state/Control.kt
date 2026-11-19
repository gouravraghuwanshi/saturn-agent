package io.github.pimobile.ui.state

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pimobile.runtime.PiRuntime
import io.github.pimobile.runtime.PiSession
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.runtime.PiService
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * A session-less pi process for app-level commands (/login, /logout, provider
 * list). It runs pi's own code through the bundled `pimobile-app` extension;
 * the extension's dialogs and events are rendered by the native login screen.
 */
object Control {
    data class Provider(val id: String, val name: String, val oauth: Boolean, val subscription: Boolean,
                        val oauthLabel: String?, val apiKey: Boolean, val configured: Boolean, val source: String?)

    /** A pending pi dialog (select / text / secret / manual code). */
    data class Dialog(val requestId: String, val method: String, val title: String,
                      val options: List<String>, val placeholder: String, val kind: String)

    class Login {
        var provider by mutableStateOf<Provider?>(null)
        var authType by mutableStateOf("")
        private var _running by mutableStateOf(false)
        /**
         * While a login runs the user is in their browser: keep the foreground
         * service up so Android doesn't freeze pi's localhost callback server.
         */
        var running: Boolean
            get() = _running
            set(v) {
                if (_running == v) return
                _running = v
                TaskManager.extraActive = if (v) 1 else 0
                PiService.sync(app, TaskManager.activeCount)
            }
        var done by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        var authUrl by mutableStateOf<String?>(null)
        var authInstructions by mutableStateOf<String?>(null)
        var deviceCode by mutableStateOf<Pair<String, String>?>(null) // code to verification URL
        var dialog by mutableStateOf<Dialog?>(null)
        val lines = mutableStateListOf<String>()
        internal var pendingKind = "text"
        fun reset() {
            running = false; done = false; error = null; authUrl = null; authInstructions = null
            deviceCode = null; dialog = null; lines.clear(); pendingKind = "text"
        }
    }

    val login = Login()
    var providers by mutableStateOf<List<Provider>?>(null)
    var providersError by mutableStateOf<String?>(null)
    /** Provider ids with credentials in pi's auth.json. */
    var signedIn by mutableStateOf<Set<String>>(emptySet())

    internal lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var session: PiSession? = null
    private var nextId = 0

    fun init(context: Context) { app = context.applicationContext; refreshSignedIn() }

    private val runtime get() = PiRuntime.get(app)

    fun loadProviders() { providersError = null; command("providers", "") }

    /** Every model the signed-in providers offer (no task needed). Fills UiStore.models. */
    fun loadModels() = io.execute {
        ensure()
        session?.send(JSONObject().put("id", "ctl-models").put("type", "get_available_models"))
    }

    fun startLogin(p: Provider, type: String) {
        login.reset()
        login.provider = p; login.authType = type; login.running = true
        command("login", JSONObject().put("provider", p.id).put("type", type).toString())
    }

    fun cancelLogin() {
        login.dialog?.let { answer(null) }
        command("login-cancel", "")
        login.running = false
    }

    fun logout(p: Provider) = command("logout", JSONObject().put("provider", p.id).toString())

    /** Answers the pending dialog (null = cancel). */
    fun answer(value: String?) {
        val d = login.dialog ?: return
        login.dialog = null
        val r = JSONObject().put("type", "extension_ui_response").put("id", d.requestId)
        if (value == null) r.put("cancelled", true) else r.put("value", value)
        io.execute { session?.send(r) }
    }

    fun refreshSignedIn() {
        val f = File(PiRuntime.get(app).home, ".pi/agent/auth.json")
        signedIn = runCatching { JSONObject(f.readText()).keys().asSequence().toSet() }.getOrDefault(emptySet())
    }

    private fun command(name: String, args: String) = io.execute {
        ensure()
        session?.send(JSONObject().put("id", "ctl${nextId++}").put("type", "prompt")
            .put("message", "/pimobile-$name $args".trim()))
    }

    private fun ensure() {
        if (session?.isAlive == true) return
        val s = PiSession(runtime, null, runtime.home, object : PiSession.Listener {
            override fun onRecord(record: JSONObject) { main.post { handle(record) } }
            override fun onLog(line: String) {}
            override fun onExit(code: Int) { main.post { if (login.running) { login.running = false; login.error = "pi exited ($code)" } } }
        })
        session = s
        runCatching { s.start(TaskManager.sessionEnv + ("PIMOBILE_CONTROL" to "1")) }
            .onFailure { e -> main.post { providersError = e.message; login.error = e.message } }
    }

    private fun handle(r: JSONObject) {
        when (r.optString("type")) {
            "extension_ui_request" -> when (val m = r.optString("method")) {
                "notify" -> r.optString("message").takeIf { it.startsWith("pimobile:") }
                    ?.let { runCatching { event(JSONObject(it.removePrefix("pimobile:"))) } }
                "select", "input", "editor", "confirm" -> login.dialog = Dialog(
                    r.optString("id"), m, r.optString("title"),
                    r.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    r.optString("placeholder"), if (m == "select") "select" else login.pendingKind,
                )
            }
            "response" -> if (r.optString("command") == "get_available_models" && r.optBoolean("success")) {
                r.optJSONObject("data")?.optJSONArray("models")?.let { a ->
                    UiStore.models = (0 until a.length()).map { a.getJSONObject(it) }
                        .map { it.optString("provider") to it.optString("id") }
                }
            } else if (!r.optBoolean("success")) {
                val msg = r.optString("error")
                if (login.running) { login.error = msg; login.running = false } else providersError = msg
            }
        }
    }

    private fun event(e: JSONObject) {
        when (e.optString("type")) {
            "providers" -> {
                val a = e.getJSONArray("providers")
                providers = (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
                    val o = p.optJSONObject("oauth")
                    Provider(p.getString("id"), p.optString("name"), o != null, o?.optBoolean("subscription") == true,
                        o?.optString("label")?.takeIf { it.isNotBlank() && it != "null" }, p.optBoolean("apiKey"),
                        p.optBoolean("configured"), p.optString("source").takeIf { it.isNotBlank() && it != "null" })
                }.sortedWith(compareByDescending<Provider> { it.subscription }.thenBy { it.name.lowercase() })
                refreshSignedIn()
                signedIn = signedIn + providers!!.filter { it.configured }.map { it.id }
            }
            "login_auth_url" -> { login.authUrl = e.optString("url"); login.authInstructions = e.optString("instructions").ifBlank { null } }
            "login_device_code" -> login.deviceCode = e.optString("userCode") to e.optString("verificationUri")
            "login_progress", "login_info" -> login.lines.add(e.optString("message"))
            "prompt_kind" -> login.pendingKind = e.optString("kind")
            "prompt_cancelled" -> login.dialog = null
            "login_done" -> {
                login.running = false; login.done = true; login.dialog = null
                refreshSignedIn(); TaskManager.restartIdle(); loadProviders(); loadModels()
            }
            "login_failed" -> {
                login.running = false; login.dialog = null
                login.error = e.optString("message").takeUnless { it == "cancelled" }
            }
            "logout_done" -> { refreshSignedIn(); TaskManager.restartIdle(); loadProviders(); loadModels() }
            "error" -> if (e.optString("scope") == "providers") providersError = e.optString("message") else login.error = e.optString("message")
        }
    }
}
