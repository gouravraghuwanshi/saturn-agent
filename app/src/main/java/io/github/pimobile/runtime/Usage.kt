package io.github.pimobile.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * What the AI has cost, read from pi's own session files: every assistant
 * reply there records its provider, model, tokens and price
 * (~/.pi/agent/sessions/<cwd>/<time>_<sessionId>.jsonl). Nothing is tracked
 * separately, so the numbers match pi's /session.
 */
object Usage {
    class Reply(val at: Long, val provider: String, val model: String, val cost: Double, val tokens: Long, val sessionId: String)

    private class Cached(val modified: Long, val size: Long, val replies: List<Reply>)
    private val cache = HashMap<String, Cached>()

    /** All replies from every session, oldest first. Blocking: call off the main thread. */
    @Synchronized
    fun scan(ctx: Context): List<Reply> {
        val root = File(PiRuntime.get(ctx).home, ".pi/agent/sessions")
        val files = root.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".jsonl") }.toList()
        cache.keys.retainAll(files.map { it.path }.toSet())
        return files.flatMap { f ->
            val hit = cache[f.path]
            if (hit != null && hit.modified == f.lastModified() && hit.size == f.length()) hit.replies
            else read(f).also { cache[f.path] = Cached(f.lastModified(), f.length(), it) }
        }.sortedBy { it.at }
    }

    private fun read(f: File): List<Reply> {
        val session = f.name.removeSuffix(".jsonl").substringAfter('_')
        val out = ArrayList<Reply>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // Cheap filter first: most lines are tool output.
                    if (!line.contains("\"usage\"") || !line.contains("\"assistant\"")) continue
                    val e = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val m = e.optJSONObject("message") ?: continue
                    if (m.optString("role") != "assistant") continue
                    val u = m.optJSONObject("usage") ?: continue
                    val at = runCatching { Instant.parse(e.optString("timestamp")).toEpochMilli() }.getOrNull()
                        ?: m.optLong("timestamp", f.lastModified())
                    out += Reply(at, m.optString("provider"), m.optString("model"),
                        u.optJSONObject("cost")?.optDouble("total", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                        u.optLong("totalTokens", u.optLong("input") + u.optLong("output")), session)
                }
            }
        }
        return out
    }
}
