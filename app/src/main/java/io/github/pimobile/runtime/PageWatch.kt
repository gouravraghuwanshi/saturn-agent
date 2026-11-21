package io.github.pimobile.runtime

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * "When a page changes" routines: fetch the page, reduce it to its readable text and compare that with
 * the last check. Costs nothing (no model) until something actually changed; then the routine's task
 * is started with what was added and removed.
 */
object PageWatch {
    class Change(val added: List<String>, val removed: List<String>)

    /** null: first look (baseline saved) or nothing changed. Throws when the page can't be read. */
    fun check(ctx: Context, jobId: String, url: String): Change? {
        val text = readable(fetch(url))
        if (text.isBlank()) throw IllegalStateException("the page had no readable text (it may need a browser to load)")
        val dir = File(ctx.filesDir, "watch").apply { mkdirs() }
        val snap = File(dir, "$jobId.txt")
        val old = snap.takeIf { it.exists() }?.readText()
        snap.writeText(text)
        if (old == null || hash(old) == hash(text)) return null
        val before = old.lines().toSet()
        val now = text.lines()
        val added = now.filter { it !in before }
        val nowSet = now.toSet()
        val removed = old.lines().filter { it !in nowSet }
        if (added.isEmpty() && removed.isEmpty()) return null   // only reordered
        return Change(added, removed)
    }

    fun forget(ctx: Context, jobId: String) { File(ctx.filesDir, "watch/$jobId.txt").delete() }

    /** What the routine's task is told. */
    fun describe(url: String, c: Change): String = buildString {
        append("\n\nThe page $url changed since the last check.")
        if (c.added.isNotEmpty()) {
            append("\n\nNew on the page:\n")
            c.added.take(40).forEach { append("+ ").append(it.take(300)).append('\n') }
            if (c.added.size > 40) append("(+${c.added.size - 40} more lines)\n")
        }
        if (c.removed.isNotEmpty()) {
            append("\nGone from the page:\n")
            c.removed.take(20).forEach { append("- ").append(it.take(300)).append('\n') }
            if (c.removed.size > 20) append("(${c.removed.size - 20} more lines)\n")
        }
        append("\n(You can fetch the page yourself if you need more.)")
    }

    private fun fetch(url: String): String {
        var u = URL(if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url)
        repeat(5) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location") ?: throw IllegalStateException("redirect without a target")); return@repeat }
                if (code !in 200..299) throw IllegalStateException("the page answered $code")
                return c.inputStream.use { s -> s.readNBytes(3_000_000) }.toString(Charsets.UTF_8)
            } finally { c.disconnect() }
        }
        throw IllegalStateException("too many redirects")
    }

    /** The text a person reads: no scripts, styles or tags; one block of text per line. */
    fun readable(html: String): String {
        var s = html
        s = Regex("(?is)<(script|style|noscript|svg|template|head)\\b.*?</\\1>").replace(s, " ")
        s = Regex("(?s)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer|/td|/th|/dt|/dd|/blockquote|/pre)\\b[^>]*>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&rsquo;", "’").replace("&ldquo;", "“").replace("&rdquo;", "”")
        s = Regex("&#(\\d+);").replace(s) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " " }
        return s.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 1 }.joinToString("\n")
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
