package io.github.pimobile.runtime

import android.content.Context
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The app's private Linux userland.
 *
 * Binaries live in the APK's native library dir (the only place a
 * targetSdk 29+ app may exec from) as lib*.so files. We expose them under
 * familiar names through symlinks in filesDir/usr/bin, so `bash`, `git`,
 * `node`... resolve on PATH exactly like on a normal Linux box.
 * The symlinks are rebuilt on every launch because the native lib dir
 * moves whenever the app is updated.
 */
class PiRuntime private constructor(private val ctx: Context) {

    companion object {
        @Volatile private var instance: PiRuntime? = null
        fun get(ctx: Context): PiRuntime = instance ?: synchronized(this) {
            instance ?: PiRuntime(ctx.applicationContext).also { instance = it }
        }

        /** command name -> packaged executable in the native lib dir */
        private val BIN = mapOf(
            "node" to "libx_node.so",
            "bash" to "libx_bash.so",
            "git" to "libx_git.so",
            "rg" to "libx_rg.so",
            "fd" to "libx_fd.so",
            "curl" to "libx_curl.so",
        )
        /** git helpers it execs from GIT_EXEC_PATH */
        private val GIT_CORE = mapOf(
            "git" to "libx_git.so",
            "git-upload-pack" to "libx_git.so",
            "git-receive-pack" to "libx_git.so",
            "git-upload-archive" to "libx_git.so",
            "git-remote-http" to "libx_git-remote-http.so",
            "git-remote-https" to "libx_git-remote-http.so",
        )
    }

    val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    val usr = File(ctx.filesDir, "usr")
    val bin = File(usr, "bin")
    val gitCore = File(usr, "libexec/git-core")
    val home = File(ctx.filesDir, "home")
    val projects = File(home, "projects")
    /** Files Saturn makes for the user (documents, pages, images…); shown in the Artifacts screen. */
    val artifacts = File(home, "artifacts")
    /** Interactive shell profile for the app's Terminal. */
    val bashrc = File(usr, "etc/saturn-bashrc")
    val tmp = File(ctx.cacheDir, "tmp")
    val piRoot = File(ctx.filesDir, "pi")
    val piCli get() = File(piRoot, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")
    private val npmCli get() = File(piRoot, "node_modules/npm/bin/npm-cli.js")
    private val npxCli get() = File(piRoot, "node_modules/npm/bin/npx-cli.js")
    private val bashEnv = File(usr, "etc/pi-bash-env.sh")
    private val opensslConf = File(usr, "etc/openssl.cnf")
    private val certs = File(usr, "etc/tls/cert.pem")

    val node get() = File(nativeDir, "libx_node.so")

    private val prefs get() = ctx.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    /**
     * "Run scripts directly": preload libpiexec.so so `./script.sh` and
     * `#!/usr/bin/env node` tools (npx, MCP servers) work although Android
     * forbids exec() of files in app storage. On by default; see piexec.c.
     */
    var runScripts: Boolean
        get() = prefs.getBoolean("run_scripts", true)
        set(v) { prefs.edit().putBoolean("run_scripts", v).apply() }

    @Volatile private var prepared = false

    /** Idempotent; safe to call from any thread before starting a session. */
    @Synchronized
    fun prepare(log: (String) -> Unit = {}) {
        if (prepared) return
        extractAsset("pi-bundle", piRoot, wipe = true, log)
        extractAsset("usr-data", usr, wipe = false, log)
        listOf(home, projects, artifacts, tmp, bin, gitCore, bashEnv.parentFile!!).forEach { it.mkdirs() }
        link(bin, BIN)
        link(gitCore, GIT_CORE)
        // Real npm/npx commands (npm's own #!/usr/bin/env node scripts), so
        // extensions can spawn e.g. `npx some-mcp-server`. Needs the exec shim.
        for ((name, script) in listOf("npm" to npmCli, "npx" to npxCli)) {
            File(bin, name).delete()
            if (runScripts) Os.symlink(script.path, File(bin, name).path)
        }
        writeConfig()
        writeShims()
        prepared = true
    }

    /** Environment for pi and every process it spawns. */
    fun env(): Map<String, String> = baseEnv() +
        (if (runScripts) mapOf("LD_PRELOAD" to File(nativeDir, "libpiexec.so").path) else emptyMap())

    private fun baseEnv(): Map<String, String> = mapOf(
        "PATH" to "${bin.path}:/system/bin",
        "LD_LIBRARY_PATH" to nativeDir.path,
        "HOME" to home.path,
        "TMPDIR" to tmp.path,
        "PREFIX" to usr.path,
        "SHELL" to File(bin, "bash").path,
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
        "TERMINFO" to File(usr, "share/terminfo").path,
        "COLORTERM" to "truecolor",
        // TLS: Termux builds look under /data/data/com.termux; point them at our copies.
        "OPENSSL_CONF" to opensslConf.path,
        "SSL_CERT_FILE" to certs.path,
        "CURL_CA_BUNDLE" to certs.path,
        "GIT_SSL_CAINFO" to certs.path,
        "NODE_OPTIONS" to "--use-bundled-ca",
        // git
        "GIT_EXEC_PATH" to gitCore.path,
        "GIT_TEMPLATE_DIR" to File(usr, "share/git-core/templates").path,
        "GIT_CONFIG_NOSYSTEM" to "1",
        // Non-interactive bash (pi's shell tool) sources this: npm/npx helpers.
        "BASH_ENV" to bashEnv.path,
        "PI_SKIP_VERSION_CHECK" to "1",
        // npm is bundled with the app; its "new version available" notice is noise here.
        "NPM_CONFIG_UPDATE_NOTIFIER" to "false",
        "NPM_CONFIG_FUND" to "false",
        "SATURN_ARTIFACTS" to artifacts.path,
        // pi (and other CLIs) then use termux-clipboard-set/get and Termux-friendly TUI redraws;
        // those commands are the app's own shims below, not Termux:API.
        "TERMUX_VERSION" to "saturn",
        "BROWSER" to "xdg-open",
    )

    private fun link(dir: File, entries: Map<String, String>) {
        for ((name, lib) in entries) {
            val path = File(dir, name)
            path.delete() // removes a stale symlink from a previous install
            Os.symlink(File(nativeDir, lib).path, path.path)
        }
    }

    /**
     * Links and clipboard for CLIs (pi's /login, /copy, `xdg-open url`): tiny
     * scripts that hand the request to the app over its token-protected local
     * bridge ($PIMOBILE_BROWSER_URL). Running them needs the exec shim.
     */
    private fun writeShims() {
        val post = { route: String -> "curl -fsS -X POST --data-binary @- \"${'$'}PIMOBILE_BROWSER_URL/app/$route\" >/dev/null" }
        val shims = mapOf(
            "xdg-open" to "printf '%s' \"${'$'}1\" | ${post("open")}",
            "termux-open-url" to "printf '%s' \"${'$'}1\" | ${post("open")}",
            "termux-open" to "printf '%s' \"${'$'}1\" | ${post("open")}",
            "termux-clipboard-set" to "if [ ${'$'}# -gt 0 ]; then printf '%s' \"${'$'}*\"; else cat; fi | ${post("clipboard")}",
            "termux-clipboard-get" to "curl -fsS \"${'$'}PIMOBILE_BROWSER_URL/app/clipboard\"",
        )
        for ((name, body) in shims) {
            val f = File(bin, name)
            f.delete()
            f.writeText("#!/system/bin/sh\n[ -n \"${'$'}PIMOBILE_BROWSER_URL\" ] || { echo \"$name: not running inside Saturn\" >&2; exit 1; }\n$body\n")
            f.setExecutable(true, false)
        }
    }

    private fun writeConfig() {
        if (!opensslConf.exists()) opensslConf.writeText("")
        bashEnv.writeText(
            """
            # Generated by the app. Sourced by non-interactive bash via BASH_ENV.
            # Android forbids exec() of files in app storage, so JS CLIs are run through node.
            npm() { node "${npmCli.path}" "${'$'}@"; }
            npx() { node "${npxCli.path}" "${'$'}@"; }
            """.trimIndent() + "\n"
        )
        // Tell pi how to run npm for `pi install npm:...`.
        val settingsFile = File(home, ".pi/agent/settings.json")
        settingsFile.parentFile?.mkdirs()
        val settings = runCatching { JSONObject(settingsFile.readText()) }.getOrElse { JSONObject() }
        settings.put("npmCommand", JSONArray(listOf(File(bin, "node").path, npmCli.path)))
        settingsFile.writeText(settings.toString(2))
        // Built-in extensions shipped with the app (refreshed on every start).
        val ext = File(home, ".pi/agent/extensions").apply { mkdirs() }
        for (name in listOf("pimobile-browser.js", "pimobile-app.js")) {
            ctx.assets.open(name).use { i -> File(ext, name).outputStream().use { i.copyTo(it) } }
        }
        // The app is called Saturn; pi stays unmodified and just learns its name here.
        File(home, ".pi/agent/APPEND_SYSTEM.md").writeText(
            """
            |You are running inside Saturn Agent, an Android app. In this app you are called Saturn;
            |introduce yourself as Saturn if asked. The user works on a phone: keep answers concise.
            |
            |## Artifacts
            |When the user asks you to make something for them to keep, view or share — a document,
            |report, note, web page, chart, image, spreadsheet, slide deck, script, data file — save it in
            |the artifacts folder: ${'$'}SATURN_ARTIFACTS (${artifacts.path}). Files there appear in the app's
            |Artifacts screen, where the user can open, preview and share them.
            |- Use clear, descriptive file names with the right extension (e.g. trip-plan.md, budget.csv).
            |- For anything visual or interactive, prefer one self-contained .html file (inline CSS/JS;
            |  it is previewed in an Android WebView, so make it look good on a phone screen).
            |- Use a subfolder for a multi-file artifact. Do not put project source code here — code
            |  belongs in the task's working directory.
            |- After saving, tell the user the file name and that it is in Artifacts.
            |
            |## Environment
            |A Linux-like userland on Android: bash, node, npm/npx, git, curl, ripgrep. There is no apt/pkg,
            |no sudo and no python unless installed via npm packages. `npm i -g <pkg>` installs CLIs on PATH.
            |""".trimMargin()
        )
        bashrc.parentFile?.mkdirs()
        bashrc.writeText(
            """
            |[ -f "${'$'}BASH_ENV" ] && . "${'$'}BASH_ENV"
            |cd ~
            |PS1='\[\e[38;2;242;179;91m\]saturn\[\e[0m\] \[\e[38;2;111;183;204m\]\w\[\e[0m\] \$ '
            |alias ls='ls --color=auto' ll='ls -la'
            |echo -e "\e[2mSaturn terminal · bash, node, npm, git, curl, rg · npm i -g <pkg> to install tools\e[0m"
            |""".trimMargin() + "\n"
        )
    }

    /** Extracts assets/<name>.zip into [dest] when assets/<name>.version changes. */
    private fun extractAsset(name: String, dest: File, wipe: Boolean, log: (String) -> Unit) {
        val want = ctx.assets.open("$name.version").bufferedReader().readText().trim()
        val stamp = File(dest, ".$name.version")
        if (stamp.exists() && stamp.readText().trim() == want) return
        log("Installing $name $want…")
        if (wipe) dest.deleteRecursively()
        dest.mkdirs()
        val root = dest.canonicalPath + File.separator
        var count = 0
        ZipInputStream(ctx.assets.open("$name.zip").buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val out = File(dest, e.name)
                require(out.canonicalPath.startsWith(root)) { "bad zip entry ${e.name}" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    count++
                }
            }
        }
        stamp.writeText(want)
        log("Installed $name $want ($count files)")
    }
}
