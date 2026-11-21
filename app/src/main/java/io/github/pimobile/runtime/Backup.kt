package io.github.pimobile.runtime

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything Saturn knows in one .zip: chats (pi's session files), task cards, routines, settings,
 * your computers list, and optionally project files and sign-ins. Restoring merges into what's
 * already here (nothing is wiped), then the app restarts to load it.
 */
object Backup {
    private const val FORMAT = "saturn-backup"
    /** Folders that are rebuilt or re-downloaded anyway; leaving them out keeps backups small. */
    private val SKIP_DIRS = setOf("node_modules", ".gradle", "build", ".cache", "__pycache__", ".venv", "venv", ".next", "target", ".npm", "tmp")
    private const val MAX_FILE = 40L * 1024 * 1024
    private val PREFS = listOf("ui", "device")
    /** Files that let someone act as you: only with "sign-ins and keys". */
    private fun secret(rel: String) = rel == ".pi/agent/auth.json" || rel.startsWith(".ssh/") || rel.startsWith(".config/gh/")

    class Options(val projects: Boolean, val signIns: Boolean)
    class Made(val files: Int, val bytes: Long, val chats: Int)
    class Restored(val chats: Int, val routines: Int, val files: Int)

    fun create(ctx: Context, out: Uri, opt: Options, progress: (Int) -> Unit): Made {
        val rt = PiRuntime.get(ctx)
        var files = 0; var bytes = 0L; var chats = 0
        val os = ctx.contentResolver.openOutputStream(out) ?: error("Couldn't open the file for writing")
        ZipOutputStream(os.buffered()).use { zip ->
            fun text(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray()); zip.closeEntry() }
            fun file(name: String, f: File) {
                if (!f.isFile || f.length() > MAX_FILE) return
                zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                files++; bytes += f.length()
                if (files % 25 == 0) progress(files)
            }
            fun tree(prefix: String, root: File, base: File, filter: (String) -> Boolean = { true }) {
                if (!root.exists()) return
                root.walkTopDown().onEnter { it == root || (it.name !in SKIP_DIRS && !java.nio.file.Files.isSymbolicLink(it.toPath())) }
                    .filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(base).invariantSeparatorsPath
                        if (filter(rel) && (opt.signIns || !secret(rel))) file(prefix + rel, f)
                    }
            }
            text("manifest.json", JSONObject().put("format", FORMAT).put("version", 1).put("created", System.currentTimeMillis())
                .put("app", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull())
                .put("projects", opt.projects).put("signIns", opt.signIns).toString(2))
            for (n in listOf("tasks.json", "jobs.json", "computers.json")) file("app/$n", File(ctx.filesDir, n))
            tree("app/", File(ctx.filesDir, "watch"), ctx.filesDir)
            text("prefs.json", JSONObject().apply { PREFS.forEach { put(it, dump(ctx.getSharedPreferences(it, Context.MODE_PRIVATE))) } }.toString())
            val home = rt.home
            chats = File(home, ".pi/agent/sessions").walkTopDown().count { it.isFile && it.name.endsWith(".jsonl") }
            tree("home/", File(home, ".pi"), home)
            tree("home/", File(home, ".saturn"), home)
            file("home/.gitconfig", File(home, ".gitconfig"))
            if (opt.signIns) { tree("home/", File(home, ".ssh"), home); tree("home/", File(home, ".config/gh"), home) }
            if (opt.projects) tree("home/", rt.projects, home)
        }
        progress(files)
        return Made(files, bytes, chats)
    }

    fun restore(ctx: Context, uri: Uri, progress: (Int) -> Unit): Restored {
        val rt = PiRuntime.get(ctx)
        var files = 0
        var tasks: JSONArray? = null; var jobs: JSONArray? = null; var prefs: JSONObject? = null
        var checked = false
        val ins = ctx.contentResolver.openInputStream(uri) ?: error("Couldn't read the file")
        ZipInputStream(ins.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                if (!checked) {
                    if (name != "manifest.json" || runCatching { JSONObject(zip.readBytes().decodeToString()).optString("format") }.getOrNull() != FORMAT)
                        error("This isn't a Saturn backup")
                    checked = true; continue
                }
                if (e.isDirectory || name.contains("..") || name.startsWith("/")) continue
                when {
                    name == "app/tasks.json" -> tasks = runCatching { JSONArray(zip.readBytes().decodeToString()) }.getOrNull()
                    name == "app/jobs.json" -> jobs = runCatching { JSONArray(zip.readBytes().decodeToString()) }.getOrNull()
                    name == "prefs.json" -> prefs = runCatching { JSONObject(zip.readBytes().decodeToString()) }.getOrNull()
                    name.startsWith("app/") -> write(File(ctx.filesDir, name.removePrefix("app/")), zip, e, keepExisting = name == "app/computers.json")
                    name.startsWith("home/") -> write(File(rt.home, name.removePrefix("home/")), zip, e)
                    else -> continue
                }
                files++
                if (files % 25 == 0) progress(files)
            }
        }
        if (!checked) error("This isn't a Saturn backup")
        val chats = tasks?.let { merge(File(ctx.filesDir, "tasks.json"), it, renumber = true) } ?: 0
        val routines = jobs?.let { merge(File(ctx.filesDir, "jobs.json"), it, renumber = false) } ?: 0
        prefs?.let { p -> PREFS.forEach { n -> p.optJSONObject(n)?.let { load(ctx.getSharedPreferences(n, Context.MODE_PRIVATE), it) } } }
        progress(files)
        return Restored(chats, routines, files)
    }

    /** Newer wins: a file already here is replaced only by a newer copy. */
    private fun write(target: File, zip: ZipInputStream, e: ZipEntry, keepExisting: Boolean = false) {
        if (target.exists() && (keepExisting || (e.time > 0 && target.lastModified() >= e.time))) return
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".restoring")
        tmp.outputStream().use { zip.copyTo(it) }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        if (e.time > 0) target.setLastModified(e.time)
    }

    /** Adds the backup's entries that aren't here yet (by id). Task numbers stay unique. Returns how many were added. */
    private fun merge(file: File, from: JSONArray, renumber: Boolean): Int {
        val have = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        val ids = (0 until have.length()).map { have.getJSONObject(it).optString("id") }.toMutableSet()
        val numbers = (0 until have.length()).map { have.getJSONObject(it).optInt("number") }.toMutableSet()
        var next = (numbers.maxOrNull() ?: 0) + 1
        var added = 0
        for (i in 0 until from.length()) {
            val o = from.optJSONObject(i) ?: continue
            if (!ids.add(o.optString("id"))) continue
            if (renumber && !numbers.add(o.optInt("number"))) { o.put("number", next); numbers.add(next); next++ }
            have.put(o); added++
        }
        file.writeText(have.toString())
        return added
    }

    private fun dump(sp: SharedPreferences) = JSONObject().apply {
        sp.all.forEach { (k, v) ->
            val (t, value) = when (v) {
                is Boolean -> "b" to v; is Int -> "i" to v; is Long -> "l" to v; is Float -> "f" to v.toDouble()
                is String -> "s" to v; is Set<*> -> "S" to JSONArray(v.map { it.toString() }); else -> return@forEach
            }
            put(k, JSONObject().put("t", t).put("v", value))
        }
    }

    private fun load(sp: SharedPreferences, o: JSONObject) {
        val ed = sp.edit()
        o.keys().forEach { k ->
            val e = o.optJSONObject(k) ?: return@forEach
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v")); "i" -> ed.putInt(k, e.optInt("v")); "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat()); "s" -> ed.putString(k, e.optString("v"))
                "S" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
            }
        }
        ed.commit()
    }

    /** Start fresh so every part of the app loads the restored data. */
    fun restart(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }
}
