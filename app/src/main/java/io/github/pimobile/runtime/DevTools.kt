package io.github.pimobile.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs bundled CLIs (gh, git, ssh) from the app, with the same environment tasks get. */
object Tools {
    lateinit var app: Context
    fun init(ctx: Context) { app = ctx.applicationContext }
    private val rt get() = PiRuntime.get(app)

    fun process(args: List<String>, cwd: File = rt.home, extraEnv: Map<String, String> = emptyMap()): Process {
        rt.prepare()
        // The app's own PATH doesn't include Saturn's usr/bin: resolve bundled tools to their full path.
        val exe = args.first().let { n -> if ('/' in n) n else File(rt.bin, n).takeIf { it.exists() }?.path ?: n }
        return ProcessBuilder(listOf(exe) + args.drop(1)).directory(cwd).redirectErrorStream(true)
            .apply { environment().clear(); environment().putAll(rt.env() + TaskManager.sessionEnv + ("GH_PROMPT_DISABLED" to "1") + extraEnv) }
            .start().also { it.outputStream.close() }
    }

    /** Blocking. Returns exit code and combined output (trimmed). */
    fun run(vararg args: String, cwd: File = rt.home, timeoutSec: Long = 60): Pair<Int, String> {
        val p = runCatching { process(args.toList(), cwd) }.getOrElse { return -1 to (it.message ?: it.toString()) }
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 32_000) out.appendLine(it) } } }.apply { start() }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); return -1 to (out.toString().trim() + "\n(timed out)") }
        reader.join(1000)
        return p.exitValue() to out.toString().trim()
    }
}

/**
 * GitHub sign-in through the bundled `gh` (its own device-code flow), then
 * git is set up to push with it.
 */
object GitHub {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    var user by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var code by mutableStateOf<String?>(null)
        private set
    var url by mutableStateOf<String?>(null)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    private var login: Process? = null

    fun refresh() = io.execute {
        main.post { checking = true }
        val (c, out) = Tools.run("gh", "api", "user", "--jq", ".login", timeoutSec = 20)
        main.post { user = out.lines().lastOrNull()?.trim()?.takeIf { c == 0 && it.isNotBlank() }; checking = false }
    }

    fun signIn() {
        if (signingIn) return
        error = null; code = null; url = null; signingIn = true
        io.execute {
            main.post { TaskManager.hold("github", true) }
            // gh would open the browser itself right away; Saturn shows the code first and opens it on tap.
            val p = runCatching { Tools.process(listOf("gh", "auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--web", "--skip-ssh-key"),
                extraEnv = mapOf("BROWSER" to "true", "GH_BROWSER" to "true")) }
                .getOrElse { e -> main.post { error = e.message; signingIn = false; TaskManager.hold("github", false) }; return@execute }
            login = p
            val log = StringBuilder()
            p.inputStream.bufferedReader().forEachLine { line ->
                log.appendLine(line)
                Regex("\\b([A-Z0-9]{4}-[A-Z0-9]{4})\\b").find(line)?.let { m -> main.post { code = m.groupValues[1] } }
                Regex("https://\\S+/login/device\\S*").find(line)?.let { m -> main.post { url = m.value } }
            }
            val ok = p.waitFor() == 0
            login = null
            if (ok) {
                Tools.run("gh", "auth", "setup-git")
                // Commits need a name and email: use the GitHub account (private noreply address).
                val (_, info) = Tools.run("gh", "api", "user", "--jq", "[.login, (.name // .login), (.id|tostring)] | join(\"\\t\")")
                info.lines().lastOrNull()?.split('\t')?.takeIf { it.size == 3 }?.let { (login, name, id) ->
                    if (Tools.run("git", "config", "--global", "user.email").second.isBlank()) {
                        Tools.run("git", "config", "--global", "user.name", name)
                        Tools.run("git", "config", "--global", "user.email", "$id+$login@users.noreply.github.com")
                    }
                }
            }
            main.post {
                TaskManager.hold("github", false)
                signingIn = false; code = null; url = null
                if (!ok) error = log.lines().lastOrNull { it.isNotBlank() && !it.startsWith("!") }?.takeIf { it.isNotBlank() } ?: "Sign-in didn't complete"
            }
            refresh()
        }
    }

    fun cancel() { login?.destroy(); login = null; signingIn = false; code = null; url = null; TaskManager.hold("github", false) }

    fun signOut() = io.execute {
        Tools.run("gh", "auth", "logout", "--hostname", "github.com")
        refresh()
    }

    /** Clones owner/repo (or a URL) into ~/projects/<name>. Returns the folder, or throws with gh's message. */
    fun clone(repo: String, into: File): File {
        val spec = repo.trim().removeSuffix("/").removeSuffix(".git")
        val name = spec.substringAfterLast('/').ifBlank { error("Give owner/repo or a GitHub link") }
        val dest = File(into, name)
        if (dest.exists()) error("A project named $name already exists")
        // Plain git: public repos work signed out; once signed in, gh is git's credential helper for private ones.
        val url = if (spec.contains("://")) spec else "https://github.com/$spec.git"
        val (c, out) = Tools.run("git", "clone", url, dest.path, cwd = into, timeoutSec = 600)
        if (c != 0) { dest.deleteRecursively(); error(out.lines().lastOrNull { it.isNotBlank() } ?: "Clone failed") }
        return dest
    }
}

/**
 * "Your computers": SSH hosts the agent can work on. Saturn keeps one key
 * (~/.ssh/id_ed25519) and writes ~/.ssh/config from this list; ssh always
 * uses that config (Android has no home directory for app users).
 */
object Computers {
    data class Host(val alias: String, val user: String, val host: String, val port: Int)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val rt get() = PiRuntime.get(Tools.app)
    private val sshDir get() = File(rt.home, ".ssh")
    private val keyFile get() = File(sshDir, "id_ed25519")
    private val store get() = File(Tools.app.filesDir, "computers.json")

    var hosts by mutableStateOf<List<Host>>(emptyList())
        private set
    var publicKey by mutableStateOf<String?>(null)
        private set
    var testing by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun load() {
        hosts = runCatching { JSONArray(store.readText()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { Host(it.getString("alias"), it.getString("user"), it.getString("host"), it.optInt("port", 22)) } } }.getOrDefault(emptyList())
        publicKey = File(sshDir, "id_ed25519.pub").takeIf { it.exists() }?.readText()?.trim()
    }

    /** Creates Saturn's SSH key if there isn't one yet. */
    fun ensureKey() = io.execute {
        if (!keyFile.exists()) {
            sshDir.mkdirs(); sshDir.setReadable(false, false); sshDir.setReadable(true, true); sshDir.setExecutable(true, true)
            Tools.run("ssh-keygen", "-t", "ed25519", "-N", "", "-C", "saturn@${android.os.Build.MODEL.replace(' ', '-')}", "-f", keyFile.path, "-q")
        }
        val pub = File(sshDir, "id_ed25519.pub").takeIf { it.exists() }?.readText()?.trim()
        main.post { publicKey = pub }
    }

    fun add(alias: String, target: String, port: Int): String? {
        val a = alias.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-')
        if (a.isEmpty()) return "Give it a short name, like laptop"
        val (u, h) = target.trim().let { if ('@' in it) it.substringBefore('@') to it.substringAfter('@') else "" to it }
        if (u.isBlank() || h.isBlank()) return "Use the form user@address, like gourav@192.168.0.10"
        if (hosts.any { it.alias == a }) return "There's already a computer called $a"
        save(hosts + Host(a, u, h, port))
        return null
    }

    fun remove(alias: String) = save(hosts.filterNot { it.alias == alias })

    private fun save(list: List<Host>) {
        hosts = list
        store.writeText(JSONArray(list.map { JSONObject().put("alias", it.alias).put("user", it.user).put("host", it.host).put("port", it.port) }).toString())
        writeConfig()
    }

    /** ~/.ssh/config: Saturn's hosts, absolute paths, never an interactive prompt. */
    fun writeConfig() {
        sshDir.mkdirs()
        val home = rt.home.path
        val text = buildString {
            appendLine("# Written by Saturn from Settings → Your computers. Edits here are replaced.")
            hosts.forEach { h ->
                appendLine("Host ${h.alias}")
                appendLine("  HostName ${h.host}"); appendLine("  User ${h.user}"); appendLine("  Port ${h.port}")
            }
            appendLine("Host *")
            appendLine("  IdentityFile $home/.ssh/id_ed25519")
            appendLine("  UserKnownHostsFile $home/.ssh/known_hosts")
            appendLine("  StrictHostKeyChecking accept-new")
            appendLine("  BatchMode yes")
            appendLine("  ConnectTimeout 10")
            appendLine("  ServerAliveInterval 30")
        }
        File(sshDir, "config").writeText(text)
    }

    fun test(alias: String) = io.execute {
        main.post { testing = alias }
        val (c, out) = Tools.run("ssh", "-F", File(sshDir, "config").path, alias, "echo connected to \$(hostname) as \$(whoami)", timeoutSec = 25)
        val msg = if (c == 0) "✓ " + out.lines().last { it.isNotBlank() }
            else "✗ " + (out.lines().lastOrNull { it.isNotBlank() } ?: "couldn't connect (exit $c)")
        main.post { results = results + (alias to msg); testing = null }
    }
}
