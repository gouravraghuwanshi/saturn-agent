package io.github.pimobile.runtime

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Scheduled jobs: run a shell command, or start a new Saturn task with a
 * prompt, at a time or on a cron schedule. Backed by AlarmManager, so jobs
 * fire while the app is closed and survive reboots (BootReceiver).
 * The agent uses it through the `saturn-schedule` command.
 */
object Scheduler {
    data class Job(
        val id: String, val name: String,
        val command: String?, val prompt: String?,
        val cwd: String, val artifacts: String?,
        val cron: String?, var next: Long,
        var lastRun: Long = 0, var lastResult: String? = null,
    ) {
        fun json() = JSONObject().put("id", id).put("name", name).put("command", command).put("prompt", prompt)
            .put("cwd", cwd).put("artifacts", artifacts).put("cron", cron).put("next", next)
            .put("lastRun", lastRun).put("lastResult", lastResult)
        companion object {
            fun from(o: JSONObject) = Job(o.getString("id"), o.optString("name"),
                o.optString("command").takeIf { o.has("command") && it.isNotEmpty() },
                o.optString("prompt").takeIf { o.has("prompt") && it.isNotEmpty() },
                o.optString("cwd"), o.optString("artifacts").takeIf { it.isNotEmpty() },
                o.optString("cron").takeIf { o.has("cron") && it.isNotEmpty() }, o.optLong("next"),
                o.optLong("lastRun"), o.optString("lastResult").takeIf { o.has("lastResult") && it.isNotEmpty() })
        }
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val file get() = File(app.filesDir, "jobs.json")
    private val logs get() = File(PiRuntime.get(app).home, ".saturn/jobs")

    /** Jobs, soonest first (Compose state for the Scheduled screen). */
    var jobs by mutableStateOf<List<Job>>(emptyList())
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = runCatching { JSONArray(file.readText()).let { a -> (0 until a.length()).map { Job.from(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList()).sortedBy { it.next }
        jobs = store
        store.forEach(::arm)
    }

    val exact: Boolean get() = Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Source of truth; [jobs] mirrors it on the main thread. */
    private var store: List<Job> = emptyList()

    @Synchronized private fun save(list: List<Job>) {
        file.writeText(JSONArray(list.map { it.json() }).toString())
        val sorted = list.sortedBy { it.next }
        store = sorted
        main.post { jobs = sorted }
    }

    @Synchronized private fun current() = store.toMutableList()

    private fun pending(id: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(app, id.hashCode(),
        Intent(app, JobReceiver::class.java).setAction("saturn.job").putExtra("id", id), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun arm(job: Job) {
        val am = app.getSystemService(AlarmManager::class.java)
        val at = job.next.coerceAtLeast(System.currentTimeMillis() + 1000)
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(job.id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(job.id))
    }

    fun add(job: Job): Job { val l = current().filter { it.id != job.id } + job; save(l); arm(job); return job }

    fun remove(id: String): Boolean {
        val l = current()
        val job = l.firstOrNull { it.id == id || it.name == id } ?: return false
        app.getSystemService(AlarmManager::class.java).cancel(pending(job.id))
        save(l - job)
        return true
    }

    /** Runs a job now (from its alarm or "Run now"); reschedules cron jobs, drops one-off ones. */
    fun fire(id: String, finished: () -> Unit = {}) {
        val job = current().firstOrNull { it.id == id } ?: return finished()
        val cron = job.cron?.let { runCatching { Cron(it) }.getOrNull() }
        val l = current().filter { it.id != id }
        if (cron != null) {
            job.next = cron.next(System.currentTimeMillis())
            job.lastRun = System.currentTimeMillis()
            save(l + job); arm(job)
        } else save(l)
        if (job.prompt != null) {
            main.post {
                runCatching {
                    val ui = io.github.pimobile.ui.state.UiStore
                    ui.init(app)
                    ui.newTask(job.prompt, File(job.cwd).takeIf { it.isDirectory } ?: PiRuntime.get(app).projects)
                    notify(job, "Started a scheduled task", job.prompt)
                }.onFailure { notify(job, "Scheduled task couldn't start", it.message ?: it.toString()) }
                finished()
            }
        } else Thread({
            val result = runCatching { runCommand(job) }.getOrElse { "error: ${it.message}" }
            log(job, result)
            // A job that failed shouldn't fail silently: tell the user (the log has the details).
            if (result.contains("\n[exit ") || result.startsWith("error:") || result.contains("[stopped after"))
                notify(job, "Scheduled job didn't work: ${job.name}", result.lines().lastOrNull { it.isNotBlank() && !it.startsWith("[") }?.take(200) ?: "See saturn-schedule log ${job.id}")
            if (cron != null) { job.lastResult = result.takeLast(200); save(current().map { if (it.id == job.id) job else it }) }
            finished()
        }, "job-${job.id}").start()
    }

    private fun runCommand(job: Job): String {
        val rt = PiRuntime.get(app)
        rt.prepare()
        val env = rt.env() + TaskManager.sessionEnv + listOfNotNull(job.artifacts?.let { "SATURN_ARTIFACTS" to it }, "SATURN_JOB_ID" to job.id)
        val cwd = File(job.cwd).takeIf { it.isDirectory } ?: rt.home
        val p = ProcessBuilder(File(rt.bin, "bash").path, "-c", job.command!!).directory(cwd).redirectErrorStream(true)
            .apply { environment().clear(); environment().putAll(env) }.start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 16_000) out.appendLine(it) } } }.apply { start() }
        val done = p.waitFor(10, TimeUnit.MINUTES)
        if (!done) p.destroyForcibly()
        reader.join(1000)
        return out.toString() + if (!done) "\n[stopped after 10 minutes]" else if (p.exitValue() != 0) "\n[exit ${p.exitValue()}]" else ""
    }

    private fun log(job: Job, text: String) = runCatching {
        logs.mkdirs()
        val f = File(logs, "${job.id}.log")
        if (f.length() > 64_000) f.writeText(f.readText().takeLast(32_000))
        f.appendText("--- ${java.util.Date()} ---\n$text\n")
    }

    private fun notify(job: Job, title: String, text: String) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("saturn-jobs", "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 1, app.packageManager.getLaunchIntentForPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(20_000 + (job.id.hashCode() and 0xFFFF), android.app.Notification.Builder(app, "saturn-jobs")
            .setSmallIcon(io.github.pimobile.R.drawable.ic_saturn_glyph).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    // ------------------------------------------------------------------ CLI

    private const val USAGE = """usage:
  saturn-schedule in <30s|10m|2h|1d|1h30m> [--name n] -- <command…>
  saturn-schedule at <HH:MM|YYYY-MM-DD HH:MM> [--name n] -- <command…>
  saturn-schedule cron "<min hour day month weekday>" [--name n] -- <command…>
  …or --prompt "<text>" instead of "-- <command>" to start a new Saturn task then
  saturn-schedule list
  saturn-schedule rm <id|name|all>
  saturn-schedule log <id|name>
  saturn-schedule run <id|name>"""

    /** `saturn-schedule` (argv = cwd, artifacts dir, args…). */
    fun cli(argv: List<String>): String {
        val cwd = argv.getOrElse(0) { "" }
        val artifacts = argv.getOrNull(1)?.takeIf { it.isNotBlank() }
        val args = argv.drop(2)
        val verb = args.firstOrNull() ?: return USAGE
        fun find(key: String) = current().firstOrNull { it.id == key || it.name == key } ?: throw IllegalArgumentException("no job \"$key\"")
        when (verb) {
            "list", "ls" -> return current().sortedBy { it.next }.joinToString("\n") { describe(it) }.ifEmpty { "No scheduled jobs." }
            "rm", "remove", "cancel" -> {
                val key = args.getOrNull(1) ?: return USAGE
                if (key == "all") { current().forEach { remove(it.id) }; return "Removed all jobs." }
                return if (remove(key)) "Removed $key." else throw IllegalArgumentException("no job \"$key\"")
            }
            "log" -> { val j = find(args.getOrNull(1) ?: return USAGE); return File(logs, "${j.id}.log").takeIf { it.exists() }?.readText()?.takeLast(8000) ?: "No runs yet." }
            "run" -> { val j = find(args.getOrNull(1) ?: return USAGE); fire(j.id); return "Started ${j.name}." }
            "in", "at", "cron" -> {}
            "-h", "--help", "help" -> return USAGE
            else -> throw IllegalArgumentException("unknown action \"$verb\"\n$USAGE")
        }
        val spec = args.getOrNull(1) ?: throw IllegalArgumentException(USAGE)
        var i = 2
        var name: String? = null
        var prompt: String? = null
        var command: String? = null
        // "at 2026-11-20 09:00": the time may be a second word.
        var at = spec
        if (verb == "at" && args.getOrNull(2)?.matches(Regex("\\d{1,2}:\\d{2}")) == true) { at = "$spec ${args[2]}"; i = 3 }
        while (i < args.size) {
            when (args[i]) {
                "--name" -> { name = args.getOrNull(i + 1); i++ }
                "--prompt" -> { prompt = args.getOrNull(i + 1); i++ }
                "--" -> { command = args.drop(i + 1).joinToString(" "); break }
                else -> { command = args.drop(i).joinToString(" "); break }
            }
            i++
        }
        if (prompt.isNullOrBlank() && command.isNullOrBlank()) throw IllegalArgumentException("nothing to run: add -- <command> or --prompt \"…\"\n$USAGE")
        val now = System.currentTimeMillis()
        val (next, cron) = when (verb) {
            "in" -> now + duration(spec) to null
            "at" -> clock(at, now) to null
            else -> Cron(spec).next(now) to spec
        }
        val id = "j" + java.lang.Long.toString(now % 1_000_000_000L, 36)
        val job = add(Job(id, name ?: (prompt ?: command!!).take(40), command?.takeIf { prompt == null }, prompt, cwd, artifacts, cron, next))
        return "Scheduled ${describe(job)}" +
            if (!exact) "\nNote: exact alarms are off, so it may run a few minutes late. The user can allow them: tap the Saturn logo → Scheduled → Allow exact timing." else ""
    }

    fun describe(j: Job): String {
        val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", java.util.Locale.getDefault())
        val what = j.prompt?.let { "task: \"$it\"" } ?: "run: ${j.command}"
        return "${j.id}  ${fmt.format(java.util.Date(j.next))}${j.cron?.let { "  (cron $it)" } ?: ""}  $what"
    }

    private fun duration(s: String): Long {
        val parts = Regex("(\\d+)\\s*(s|sec|m|min|h|hr|d|day)s?", RegexOption.IGNORE_CASE).findAll(s).toList()
        if (parts.isEmpty() || parts.sumOf { it.value.length } != s.replace(" ", "").length)
            throw IllegalArgumentException("bad duration \"$s\" (use e.g. 30s, 10m, 2h, 1h30m)")
        return parts.sumOf { m ->
            val n = m.groupValues[1].toLong()
            when (m.groupValues[2].lowercase().first()) { 's' -> n * 1000; 'm' -> n * 60_000; 'h' -> n * 3_600_000; else -> n * 86_400_000 }
        }.also { if (it < 1000) throw IllegalArgumentException("duration too short") }
    }

    private fun clock(s: String, now: Long): Long {
        Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})").matchEntire(s.trim())?.let { m ->
            val (y, mo, d, h, mi) = m.destructured
            return Calendar.getInstance().apply { set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), 0); set(Calendar.MILLISECOND, 0) }
                .timeInMillis.also { if (it <= now) throw IllegalArgumentException("that time has already passed") }
        }
        Regex("(\\d{1,2}):(\\d{2})").matchEntire(s.trim())?.let { m ->
            val c = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); set(Calendar.MINUTE, m.groupValues[2].toInt())
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis
        }
        throw IllegalArgumentException("bad time \"$s\" (use HH:MM or YYYY-MM-DD HH:MM, phone's local time)")
    }

    /** Standard 5-field cron (minute hour day-of-month month day-of-week), plus @hourly/@daily/@weekly/@monthly. */
    class Cron(expr: String) {
        private val f: List<Set<Int>>
        private val domStar: Boolean
        private val dowStar: Boolean
        init {
            val e = when (expr.trim()) { "@hourly" -> "0 * * * *"; "@daily", "@midnight" -> "0 0 * * *"; "@weekly" -> "0 0 * * 0"
                "@monthly" -> "0 0 1 * *"; "@yearly", "@annually" -> "0 0 1 1 *"; else -> expr.trim() }
            val p = e.split(Regex("\\s+"))
            require(p.size == 5) { "cron needs 5 fields: minute hour day month weekday (e.g. \"0 9 * * 1-5\")" }
            val ranges = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
            f = p.mapIndexed { i, s -> field(s, ranges[i]) }.let { l -> l.toMutableList().also { it[4] = l[4].map { d -> d % 7 }.toSet() } }
            domStar = p[2] == "*"; dowStar = p[4] == "*"
        }
        private fun field(s: String, r: IntRange): Set<Int> = s.split(',').flatMap { part ->
            val (base, step) = part.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 1) }
            val range = when {
                base == "*" -> r
                '-' in base -> base.split('-').let { it[0].toInt()..it[1].toInt() }
                else -> base.toInt().let { if (step > 1) it..r.last else it..it }
            }
            require(range.first in r && range.last in r && step > 0) { "cron value \"$part\" out of range ${r.first}-${r.last}" }
            range.step(step).toList()
        }.toSet()

        fun next(after: Long): Long {
            val c = Calendar.getInstance().apply { timeInMillis = after; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MINUTE, 1) }
            repeat(366 * 24 * 60) {
                val month = c.get(Calendar.MONTH) + 1
                val dom = c.get(Calendar.DAY_OF_MONTH)
                val dow = c.get(Calendar.DAY_OF_WEEK) - 1
                val dayOk = when {
                    domStar && dowStar -> true
                    domStar -> dow in f[4]
                    dowStar -> dom in f[2]
                    else -> dom in f[2] || dow in f[4]
                }
                if (month !in f[3] || !dayOk) { c.add(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); return@repeat }
                if (c.get(Calendar.HOUR_OF_DAY) !in f[1]) { c.add(Calendar.HOUR_OF_DAY, 1); c.set(Calendar.MINUTE, 0); return@repeat }
                if (c.get(Calendar.MINUTE) in f[0]) return c.timeInMillis
                c.add(Calendar.MINUTE, 1)
            }
            throw IllegalArgumentException("cron never fires")
        }
    }
}

/** An alarm went off: run the job. */
class JobReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        io.github.pimobile.ui.state.UiStore.init(context)
        val result = goAsync()
        // A receiver may only hold on for about a minute; longer commands keep running without it.
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = { if (once.compareAndSet(false, true)) result.finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 50_000)
        Scheduler.fire(id) { finish() }
    }
}

/** Alarms are cleared on reboot; re-arm them (Scheduler.init does that). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            io.github.pimobile.ui.state.UiStore.init(context)
    }
}
