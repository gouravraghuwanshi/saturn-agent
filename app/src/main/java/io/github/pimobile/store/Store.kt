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

    enum class Filter(val label: String, val query: String) {
        ALL("all", ""), EXTENSIONS("extensions", "extension"), SKILLS("skills", "skill"),
        THEMES("themes", "theme"), INSTALLED("installed", ""),
    }

    private const val REGISTRY = "https://registry.npmjs.org"

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
        return Details(
            readme = doc.optString("readme").ifBlank { v.optString("description") }.take(20_000),
            contents = contents,
            dependencies = deps,
            imageUrl = pi?.optString("image")?.takeIf { it.startsWith("https://") },
            repository = (v.opt("repository") as? JSONObject)?.optString("url")
                ?.removePrefix("git+")?.removeSuffix(".git")?.takeIf { it.startsWith("https://") },
            mobileWarnings = deps.mapNotNull { d -> RISKY[d]?.let { "$d — $it" } },
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
    fun run(rt: PiRuntime, action: String, spec: String, onLine: (String) -> Unit): Int {
        rt.prepare()
        val pb = ProcessBuilder(rt.node.path, rt.piCli.path, action, spec)
            .directory(rt.home).redirectErrorStream(true)
        pb.environment().putAll(rt.env() + TaskManager.sessionEnv)
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { onLine(it.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")) }
        val code = p.waitFor()
        if (code == 0) TaskManager.restartIdle()
        return code
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000; readTimeout = 20_000
        setRequestProperty("Accept", "application/json")
        if (responseCode !in 200..299) error("HTTP $responseCode")
        inputStream.bufferedReader().readText()
    }

    private fun JSONArray.strings() = (0 until length()).map { optString(it) }
}
