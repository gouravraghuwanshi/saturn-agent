package io.github.pimobile.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.pimobile.runtime.PiRuntime
import io.github.pimobile.runtime.TaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The extension store. pi.dev's package gallery is the set of npm packages
 * tagged with the `pi-package` keyword, so we browse the public npm registry
 * and install with pi's own `pi install npm:<name>`.
 */
object Store {
    data class Pkg(
        val name: String,
        val version: String,
        val description: String,
        val weeklyDownloads: Long,
        val keywords: List<String>,
        val updated: String,
    ) {
        val kind: String get() = when {
            keywords.any { it.contains("theme") } -> "theme"
            keywords.any { it.contains("skill") } -> "skill"
            keywords.any { it.contains("prompt") } -> "prompt"
            else -> "extension"
        }
        /**
         * Bridges to other agent programs over the Agent Client Protocol (Antigravity's
         * agy server, Cursor, Claude Code, Codex, Copilot CLI…). Those programs are
         * desktop-only, so these packages can't work on a phone.
         */
        val desktopOnly: Boolean get() = isAcpBridge(name, description, keywords)
        val installSpec get() = "npm:$name"
        val pageUrl get() = "https://pi.dev/packages/$name"
    }

    data class Details(
        val readme: String,
        val contents: List<String>,      // e.g. "2 extensions", "1 skill"
        val dependencies: List<String>,
        val imageUrl: String?,
        val repository: String?,
        /** Dependencies that usually need desktop Chrome or native modules. */
        val mobileWarnings: List<String>,
    )

    fun isAcpBridge(name: String, description: String, keywords: List<String>): Boolean {
        val text = (name + " " + description + " " + keywords.joinToString(" ")).lowercase()
        if ("active context pruning" in text) return false // "ACP" that means something else
        return "agent client protocol" in text || "agent-client-protocol" in text ||
            keywords.any { it.lowercase() == "acp" || it.lowercase() == "acp-bridge" } ||
            Regex("\\bacp\\b|\\bagy\\b").containsMatchIn(text)
    }

    enum class Filter(val label: String, val query: String) {
        ALL("all", ""), WORKS("✓ works on Saturn", ""), EXTENSIONS("extensions", "extension"), SKILLS("skills", "skill"),
        THEMES("themes", "theme"), INSTALLED("installed", ""),
    }

    private const val REGISTRY = "https://registry.npmjs.org"

    /** A package Saturn has tested on a phone: installed with npm and loaded with pi's own loader. */
    data class Compat(val status: String, val version: String, val description: String, val weekly: Long, val note: String?) {
        val works get() = status == "works"
    }

    /** The tested list, published in the repo so it grows without an app update. */
    private const val COMPAT_URL = "https://raw.githubusercontent.com/gouravraghuwanshi/saturn-agent/main/compat/extensions.json"
    @Volatile private var compatCache: Map<String, Compat>? = null
    var compatTested = ""; private set

    /** Tested packages: the newest copy we have (downloaded, else the one in the app). Call off the main thread. */
    fun compat(ctx: android.content.Context): Map<String, Compat> {
        compatCache?.let { return it }
        val cached = File(ctx.filesDir, "compat.json")
        val bundled = ctx.assets.open("compat.json").bufferedReader().use { it.readText() }
        // The downloaded copy, unless it's broken or older than the one in this version of the app.
        val text = runCatching { cached.readText().takeIf { tested(it) >= tested(bundled) } }.getOrNull() ?: bundled
        val map = runCatching { parseCompat(text) }.getOrElse { parseCompat(bundled) }
        compatCache = map
        // Refresh at most once a day, in the background; used from the next time the store opens.
        if (System.currentTimeMillis() - cached.lastModified() > 24 * 3600_000L) Thread {
            runCatching { get(COMPAT_URL).also { parseCompat(it); cached.writeText(it) } }
        }.start()
        return map
    }

    private fun tested(text: String) = runCatching { JSONObject(text).optString("tested") }.getOrDefault("")

    private fun parseCompat(text: String): Map<String, Compat> {
        val o = JSONObject(text)
        val p = o.getJSONObject("packages")
        compatTested = o.optString("tested")
        return p.keys().asSequence().associateWith { k ->
            val e = p.getJSONObject(k)
            Compat(e.optString("status"), e.optString("version"), e.optString("description"), e.optLong("weekly"),
                e.optString("note").ifBlank { null })
        }
    }

    /** The "works on Saturn" tab: tested packages that work, most used first. */
    fun works(ctx: android.content.Context, text: String): List<Pkg> = compat(ctx).filter { (n, c) ->
        c.works && (text.isBlank() || n.contains(text, true) || c.description.contains(text, true))
    }.entries.sortedByDescending { it.value.weekly }.map { (n, c) -> Pkg(n, c.version, c.description, c.weekly, emptyList(), "") }

    /** Desktop-only or native dependencies that won't work in the Android runtime. */
    private val RISKY = mapOf(
        "puppeteer" to "drives desktop Chrome", "puppeteer-core" to "drives desktop Chrome",
        "playwright" to "drives desktop browsers", "playwright-core" to "drives desktop browsers",
        "chrome-launcher" to "launches desktop Chrome", "chrome-devtools-mcp" to "launches desktop Chrome",
        "node-pty" to "native module", "better-sqlite3" to "native module", "sharp" to "native module",
        "canvas" to "native module", "robotjs" to "desktop automation", "electron" to "desktop app runtime",
    )

    fun search(text: String, filter: Filter, from: Int = 0, size: Int = 30): List<Pkg> {
        val q = listOf("keywords:pi-package", filter.query, text.trim()).filter { it.isNotBlank() }.joinToString(" ")
        val url = "$REGISTRY/-/v1/search?text=${URLEncoder.encode(q, "UTF-8")}&size=$size&from=$from&popularity=1.0"
        val objects = JSONObject(get(url)).getJSONArray("objects")
        return (0 until objects.length()).map { i ->
            val o = objects.getJSONObject(i)
            val p = o.getJSONObject("package")
            Pkg(
                name = p.getString("name"),
                version = p.optString("version"),
                description = p.optString("description"),
                weeklyDownloads = o.optJSONObject("downloads")?.optLong("weekly") ?: 0,
                keywords = p.optJSONArray("keywords")?.strings().orEmpty(),
                updated = p.optString("date").take(10),
            )
        }
    }

    fun details(name: String): Details {
        val doc = JSONObject(get("$REGISTRY/${name.replace("/", "%2F")}"))
        val latest = doc.optJSONObject("dist-tags")?.optString("latest")
        val v = latest?.let { doc.optJSONObject("versions")?.optJSONObject(it) } ?: JSONObject()
        val pi = v.optJSONObject("pi")
        val contents = buildList {
            for (k in listOf("extensions", "skills", "prompts", "themes")) {
                val n = pi?.optJSONArray(k)?.length() ?: 0
                if (n > 0) add("$n ${if (n == 1) k.removeSuffix("s") else k}")
            }
        }
        val deps = v.optJSONObject("dependencies")?.keys()?.asSequence()?.toList().orEmpty()
        val keywords = v.optJSONArray("keywords")?.strings().orEmpty().map { it.lowercase() }
        val forOmp = keywords.any { it == "omp" || it == "oh-my-pi" || it == "ohmypi" }
        return Details(
            readme = doc.optString("readme").ifBlank { v.optString("description") }.take(20_000),
            contents = contents,
            dependencies = deps,
            imageUrl = pi?.optString("image")?.takeIf { it.startsWith("https://") },
            repository = (v.opt("repository") as? JSONObject)?.optString("url")
                ?.removePrefix("git+")?.removeSuffix(".git")?.takeIf { it.startsWith("https://") },
            mobileWarnings = listOfNotNull(
                "Needs a desktop program (an ACP agent such as Antigravity, Cursor, Claude Code or Codex). Android can't run those, so this won't work on a phone"
                    .takeIf { isAcpBridge(v.optString("name"), v.optString("description"), keywords) },
                "Made for Oh My Pi (a different fork of pi) — it may not load in pi".takeIf { forOmp },
            ) + deps.mapNotNull { d -> RISKY[d]?.let { "$d — $it" } },
        )
    }

    fun image(url: String): Bitmap? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000; readTimeout = 15_000
            inputStream.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    /** Package sources pi has in ~/.pi/agent/settings.json, e.g. "npm:pi-web-access". */
    fun installed(rt: PiRuntime): Set<String> {
        val f = File(rt.home, ".pi/agent/settings.json")
        val arr = runCatching { JSONObject(f.readText()).optJSONArray("packages") }.getOrNull() ?: return emptySet()
        return (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.get(i)) { is String -> e; is JSONObject -> e.optString("source"); else -> null }
        }.map { it.substringBeforeLast('@').let { s -> if (s == "npm:" || s.isEmpty()) it else s } }.toSet()
    }

    /**
     * Runs `pi install|remove npm:<name>` and streams its output. Returns the
     * exit code. Afterwards idle task processes are restarted so they load
     * the change (busy ones pick it up on their next start).
     */
    /** [run] result when an install was rolled back because pi could no longer start. */
    const val REJECTED = 90

    fun run(rt: PiRuntime, action: String, spec: String, onLine: (String) -> Unit): Int {
        val code = exec(rt, action, spec, onLine)
        if (code == 0 && action == "install") {
            onLine("Checking that pi still starts with it…")
            val err = startupError(rt)
            if (err != null) {
                onLine("✗ pi can't start with this package:")
                onLine(err)
                onLine("Removing it again…")
                exec(rt, "remove", spec, onLine)
                TaskManager.restartIdle(); io.github.pimobile.ui.state.Control.restart()
                return REJECTED
            }
        }
        if (code == 0) { TaskManager.restartIdle(); io.github.pimobile.ui.state.Control.restart() }
        return code
    }

    /**
     * Starts pi the way tasks do and asks for its state. Returns pi's error
     * output if it exits instead (a broken extension stops pi from starting,
     * which would take down every task), or null when it answers.
     */
    fun startupError(rt: PiRuntime, timeoutMs: Long = 120_000): String? {
        val pb = ProcessBuilder(rt.node.path, rt.piCli.path, "--mode", "rpc", "--no-session")
            .directory(rt.projects).redirectErrorStream(true)
        pb.environment().putAll(rt.env() + TaskManager.sessionEnv)
        val p = pb.start()
        val out = StringBuilder()
        var ok = false
        val reader = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { l ->
                    if (l.contains("\"type\":\"response\"") && l.contains("\"id\":\"saturn-check\"")) { ok = true; p.destroy() }
                    else if (!l.startsWith("{")) out.appendLine(l)
                }
            }
        }.apply { start() }
        runCatching {
            p.outputStream.write("{\"type\":\"get_state\",\"id\":\"saturn-check\"}\n".toByteArray()); p.outputStream.flush()
        }
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) { p.destroy(); return null } // slow, not broken
        reader.join(2000)
        return if (ok) null else out.toString().trim().ifBlank { "pi exited (${p.exitValue()})" }.take(2000)
    }

    /** The npm package behind a pi "Extension \"…/node_modules/<pkg>/…\" error". */
    fun packageInError(text: String): String? {
        val path = Regex("Extension \"([^\"]+)\" error").find(text)?.groupValues?.get(1) ?: return null
        val m = Regex("/node_modules/((?:@[^/]+/)?[^/]+)/").find(path) ?: return null
        return "npm:" + m.groupValues[1]
    }

    private fun exec(rt: PiRuntime, action: String, spec: String, onLine: (String) -> Unit): Int {
        rt.prepare()
        val pb = ProcessBuilder(rt.node.path, rt.piCli.path, action, spec)
            .directory(rt.home).redirectErrorStream(true)
        pb.environment().putAll(rt.env() + TaskManager.sessionEnv)
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { onLine(it.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")) }
        return p.waitFor()
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        if (responseCode !in 200..299) error("HTTP $responseCode")
        inputStream.bufferedReader().readText()
    }

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }
}
