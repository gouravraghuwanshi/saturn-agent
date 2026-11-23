package io.github.pimobile.runtime

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of tasks. A task's history is pi's own session file
 * (~/.pi/agent/sessions/<cwd>/<time>_<id>.jsonl), so an export is a zip of
 * those files plus a manifest with the task titles. A bare pi .jsonl session
 * can be imported too.
 */
object Transfer {
    private const val FORMAT = "saturn-tasks"

    private fun sessionsRoot(ctx: Context) = File(PiRuntime.get(ctx).home, ".pi/agent/sessions")

    /** pi's directory name for a working directory: "/a/b" → "--a-b--". */
    private fun dirFor(cwd: String) = "--" + cwd.trim('/').replace('/', '-') + "--"

    /** The session file for [sessionId], or null if pi hasn't written it yet. */
    fun sessionFile(ctx: Context, sessionId: String): File? =
        sessionsRoot(ctx).walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.endsWith("_$sessionId.jsonl") }

    /** Writes the tasks' sessions to [out]. Returns how many tasks had a session to export. */
    fun export(ctx: Context, tasks: List<TaskManager.Task>, out: Uri): Int {
        val manifest = JSONArray()
        var n = 0
        ctx.contentResolver.openOutputStream(out)?.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                for (t in tasks) {
                    val f = sessionFile(ctx, t.sessionId) ?: continue
                    zip.putNextEntry(ZipEntry("sessions/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    manifest.put(JSONObject().put("title", t.title).put("sessionId", t.sessionId).put("file", f.name)
                        .put("cwd", t.cwd.path).put("archived", t.archived))
                    n++
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JSONObject().put("format", FORMAT).put("version", 1).put("tasks", manifest).toString(2).toByteArray())
                zip.closeEntry()
            }
        } ?: error("Couldn't open the file for writing")
        return n
    }

    data class Imported(val added: List<TaskManager.Task>, val skipped: Int)

    /** Imports a Saturn export (.zip) or a single pi session (.jsonl) as task cards. */
    fun import(ctx: Context, uri: Uri): Imported {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the file")
        val sessions = mutableMapOf<String, ByteArray>() // file name → content
        var manifest: JSONArray? = null
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    when {
                        e.isDirectory -> {}
                        e.name == "manifest.json" -> manifest = JSONObject(zip.readBytes().decodeToString()).optJSONArray("tasks")
                        name.endsWith(".jsonl") -> sessions[name] = zip.readBytes()
                    }
                }
            }
        } else sessions["imported.jsonl"] = bytes
        if (sessions.isEmpty()) error("No sessions found in this file")

        val meta = (0 until (manifest?.length() ?: 0)).map { manifest!!.getJSONObject(it) }.associateBy { it.optString("file") }
        val rt = PiRuntime.get(ctx)
        val added = mutableListOf<TaskManager.Task>()
        var skipped = 0
        for ((name, content) in sessions) {
            val lines = content.decodeToString().lines().filter { it.isNotBlank() }
            val header = lines.firstOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (header?.optString("type") != "session") { skipped++; continue }
            val id = header.optString("id")
            if (id.isBlank() || TaskManager.tasks.any { it.sessionId == id }) { skipped++; continue } // already here
            // Sessions from another device point at folders that don't exist here.
            val m = meta[name]
            val cwd = File(header.optString("cwd")).takeIf { it.isDirectory }
                ?: File(rt.projects, File(header.optString("cwd")).name).takeIf { it.isDirectory }
                ?: rt.projects
            header.put("cwd", cwd.path)
            val fileName = if (name.endsWith("_$id.jsonl")) name
                else java.text.SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()) + "_$id.jsonl"
            val dest = File(sessionsRoot(ctx), dirFor(cwd.path) + "/" + fileName)
            dest.parentFile?.mkdirs()
            dest.writeText((listOf(header.toString()) + lines.drop(1)).joinToString("\n", postfix = "\n"))
            val title = m?.optString("title")?.takeIf { it.isNotBlank() } ?: titleOf(lines) ?: "Imported session"
            added += TaskManager.adopt(id, title, cwd).also { if (m?.optBoolean("archived") == true) TaskManager.setArchived(it, true) }
        }
        return Imported(added, skipped)
    }

    private fun titleOf(lines: List<String>): String? {
        for (l in lines) {
            val e = runCatching { JSONObject(l) }.getOrNull() ?: continue
            if (e.optString("type") == "session_info") e.optString("name").takeIf { it.isNotBlank() }?.let { return it }
            val msg = e.optJSONObject("message") ?: continue
            if (msg.optString("role") != "user") continue
            val c = msg.opt("content")
            val text = when (c) {
                is String -> c
                is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text") }.joinToString(" ")
                else -> ""
            }
            if (text.isNotBlank()) return text.lines().first().take(80)
        }
        return null
    }

    /**
     * Saturn's last text reply in a session, read from the end of its file (sessions can be
     * large; the last reply is near the end). Null if there's none or the file can't be read.
     */
    /** What a home card shows for a task that hasn't been opened yet. */
    class Summary(val lastReply: String?, val steps: Int, val at: Long)

    /** [lastReply], plus how many steps (tool calls) the session ran and when it last changed. */
    fun summary(ctx: Context, sessionId: String): Summary? = runCatching {
        val f = sessionFile(ctx, sessionId) ?: return null
        var steps = 0
        f.bufferedReader().useLines { ls -> ls.forEach { if (it.contains("\"role\":\"toolResult\"")) steps++ } }
        Summary(lastReply(ctx, sessionId), steps, f.lastModified())
    }.getOrNull()

    fun lastReply(ctx: Context, sessionId: String): String? = runCatching {
        val f = sessionFile(ctx, sessionId) ?: return null
        val tail = java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), 256L * 1024)
            r.seek(r.length() - n); ByteArray(n.toInt()).also { r.readFully(it) }
        }.toString(Charsets.UTF_8)
        tail.lines().asReversed().asSequence()
            .filter { it.contains("\"assistant\"") }
            .mapNotNull { runCatching { org.json.JSONObject(it) }.getOrNull()?.optJSONObject("message") }
            .filter { it.optString("role") == "assistant" }
            .map { m -> m.optJSONArray("content")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") } }.orEmpty() }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()
}
