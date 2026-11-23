package io.github.pimobile.runtime

import io.github.pimobile.ui.Shortcuts
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Home-screen tasks: each task is its own pi session (own process while
 * active). Task metadata is persisted so cards survive an app restart and a
 * task can be resumed later with the same session id.
 */
object TaskManager {

    enum class Status { STARTING, RUNNING, NEEDS_INPUT, IDLE, STOPPED, FAILED }

    class Task(
        val id: String,
        val number: Int,
        var title: String,
        val cwd: File,
        /** Loaded from disk at startup: its history lives in pi's session file. */
        val restored: Boolean = false,
    ) {
        /** pi session this task is on; changes after /fork, /clone, /new, /resume. */
        @Volatile var sessionId: String = id
        @Volatile var status = Status.STOPPED
        /** Swiped away into the home screen's Archived section. */
        @Volatile var archived = false
        @Volatile var lastActivity: String = ""
        internal var session: PiSession? = null
    }

    fun interface Listener {
        /** Called on the main thread. [record] is null for status-only changes. */
        fun onTaskEvent(task: Task, record: JSONObject?, log: String?)
    }

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val _tasks = mutableListOf<Task>()
    val tasks: List<Task> get() = synchronized(_tasks) { _tasks.toList() }

    /** Provider credentials etc. passed to every session (e.g. OPENROUTER_API_KEY). */
    @Volatile var sessionEnv: Map<String, String> = emptyMap()

    private val store get() = File(app.filesDir, "tasks.json")
    private val runtime get() = PiRuntime.get(app)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
    }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Other work that must survive the app going to the background (e.g. a /login waiting for its browser callback). */
    /** Background work that must keep the foreground service up (a /login or GitHub sign-in waiting on the browser). */
    private val holds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val extraActive get() = holds.size
    /** Main thread. Keeps Saturn running while the user is away in the browser. */
    fun hold(key: String, on: Boolean) {
        if (on) holds.add(key) else holds.remove(key)
        PiService.sync(app, activeCount)
    }

    val activeCount get() = tasks.count { it.status in setOf(Status.STARTING, Status.RUNNING, Status.NEEDS_INPUT) } + extraActive

    /** Home-screen input: every new instruction becomes a new task. */
    fun createTask(prompt: String, cwd: File = runtime.projects, images: JSONArray? = null): Task {
        val task = synchronized(_tasks) {
            Task(
                id = "task-" + UUID.randomUUID().toString().take(8),
                number = (_tasks.maxOfOrNull { it.number } ?: 0) + 1,
                title = (prompt.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("[Attached image:") && !it.startsWith("[Attached file:") } ?: "Attachment").take(80),
                cwd = cwd,
            ).also { it.sessionId = UUID.randomUUID().toString(); _tasks.add(0, it) }
        }
        save()
        send(task, prompt, images)
        return task
    }

    /** Continue a task's conversation; starts/resumes its process if needed. */
    fun send(task: Task, text: String, images: JSONArray? = null) {
        if (task.archived) { task.archived = false; save() } // continuing it brings it back
        io.execute {
            ensureSession(task)
            val cmd = JSONObject().put("type", "prompt").put("message", text)
            if (images != null && images.length() > 0) cmd.put("images", images)
            // While a run is active pi requires a queueing mode; steer = after current step.
            if (task.status == Status.RUNNING) cmd.put("streamingBehavior", "steer")
            task.session?.send(cmd)
        }
    }

    fun command(task: Task, cmd: JSONObject) = io.execute {
        ensureSession(task)
        task.session?.send(cmd)
    }

    /**
     * Stop button. Queued steer/follow-up messages are dropped first (pi's
     * abort would otherwise carry on with them). If the run hasn't ended a few
     * seconds later — e.g. a shell command that ignores the signal — pi and
     * everything it started are killed; the conversation is in the session
     * file and the next message picks it up.
     */
    fun abort(task: Task) {
        val s = task.session ?: return
        s.send(JSONObject().put("type", "clear_queue").put("id", "stop-q"))
        s.send(JSONObject().put("type", "abort").put("id", "stop-a"))
        main.postDelayed({
            if (task.session === s && task.status in setOf(Status.RUNNING, Status.NEEDS_INPUT, Status.STARTING)) io.execute {
                s.kill()
                if (task.session === s) task.session = null
                setStatus(task, Status.IDLE)
                emit(task, JSONObject().put("type", FORCE_STOPPED), null)
            }
        }, 5000)
    }

    /** Synthetic record: the Stop button had to kill the run. */
    const val FORCE_STOPPED = "app_force_stopped"

    private val waiters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<JSONObject>>()
    private var requestIds = 0

    /** Sends a command and waits for its response (off the main thread). Throws on failure. */
    private fun request(task: Task, cmd: JSONObject, timeoutSec: Long = 30): JSONObject {
        val id = "br-${requestIds++}"
        val f = java.util.concurrent.CompletableFuture<JSONObject>()
        waiters[id] = f
        try {
            task.session?.send(cmd.put("id", id)) ?: error("Saturn isn't running for this task")
            val r = f.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!r.optBoolean("success")) error(r.optString("error").ifBlank { "${cmd.optString("type")} failed" })
            return r.optJSONObject("data") ?: JSONObject()
        } finally { waiters.remove(id) }
    }

    /**
     * Branches a task into a new task card, leaving the original as it was.
     * [userIndex] = branch before that user message (pi's fork; its text comes
     * back to edit); null = copy the whole conversation (pi's clone).
     * Done in the task's own pi process: fork/clone moves it to the new
     * session, then it switches straight back to the original.
     */
    fun branch(task: Task, userIndex: Int?, userText: String?, done: (Task?, String?, String?) -> Unit) = io.execute {
        try {
            if (task.status == Status.RUNNING || task.status == Status.NEEDS_INPUT) error("Wait until Saturn finishes (or stop it), then branch.")
            ensureSession(task)
            val original = request(task, JSONObject().put("type", "get_state")).optString("sessionFile")
            if (original.isBlank()) error("This task has no saved conversation yet")
            var text: String? = null
            if (userIndex != null) {
                val msgs = request(task, JSONObject().put("type", "get_fork_messages")).optJSONArray("messages") ?: JSONArray()
                val list = (0 until msgs.length()).map { msgs.getJSONObject(it) }
                val want = userText?.trim()?.take(60).orEmpty()
                val pick = list.getOrNull(userIndex)?.takeIf { want.isEmpty() || it.optString("text").trim().startsWith(want) }
                    ?: list.lastOrNull { it.optString("text").trim().startsWith(want) }
                    ?: error("Couldn't find that message in the conversation")
                val r = request(task, JSONObject().put("type", "fork").put("entryId", pick.optString("entryId")))
                if (r.optBoolean("cancelled")) error("An extension cancelled the branch")
                text = r.optString("text").ifBlank { null }
            } else {
                val r = request(task, JSONObject().put("type", "clone"))
                if (r.optBoolean("cancelled")) error("An extension cancelled the branch")
            }
            val newId = request(task, JSONObject().put("type", "get_state")).optString("sessionId")
            request(task, JSONObject().put("type", "switch_session").put("sessionPath", original))
            if (newId.isBlank() || newId == task.sessionId) error("pi didn't create a new session")
            val t = adopt(newId, "⑂ " + task.title.removePrefix("⑂ "), task.cwd)
            main.post { done(t, text, null) }
        } catch (e: Exception) {
            main.post { done(null, null, e.message ?: e.toString()) }
        }
    }

    /** Answers a dialog `extension_ui_request` (select/confirm/input/editor). */
    fun answer(task: Task, requestId: String, response: JSONObject) {
        task.session?.send(response.put("type", "extension_ui_response").put("id", requestId))
        if (task.status == Status.NEEDS_INPUT) setStatus(task, Status.RUNNING)
    }

    private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

    /** Environment for a task's pi process (RPC or TUI): credentials + app integration. */
    fun envFor(task: Task): Map<String, String> = sessionEnv + mapOf(
        "PIMOBILE_TASK_ID" to task.id,
        "SATURN_PROJECT" to runtime.projectName(task.cwd),
        "SATURN_ARTIFACTS" to runtime.artifactsFor(task.cwd).apply { mkdirs() }.path,
    )

    /** Synthetic record emitted after a task's pi process is up. */
    const val SESSION_STARTED = "app_session_started"

    /** pi moved the task to another session (fork/clone/new/resume): restart on that one. */
    fun setSessionId(task: Task, id: String) {
        if (id.isBlank() || id == task.sessionId) return
        task.sessionId = id
        save()
    }

    /** Opens an existing pi session (from /resume) as a new task card. */
    fun adopt(sessionId: String, title: String, cwd: File): Task {
        val task = synchronized(_tasks) {
            Task("task-" + UUID.randomUUID().toString().take(8), (_tasks.maxOfOrNull { it.number } ?: 0) + 1,
                title.take(80), cwd, restored = true).also { it.sessionId = sessionId; _tasks.add(0, it) }
        }
        save()
        emit(task, null, null)
        return task
    }

    fun setArchived(task: Task, archived: Boolean) {
        task.archived = archived
        save()
        emit(task, null, null)
    }

    fun rename(task: Task, title: String) {
        task.title = title
        save()
        emit(task, null, null)
    }

    /**
     * Hands the session to the real pi TUI: ends the RPC process (pi saves the
     * session as it goes), so only one pi process ever touches a session file.
     * Blocking; call off the main thread. The next send() restarts RPC mode.
     */
    fun releaseForTerminal(task: Task) {
        task.session?.stop()
        task.session = null
        setStatus(task, Status.STOPPED)
    }

    /**
     * After installing/removing packages: end idle task processes so they
     * reload resources on their next message. Running tasks are left alone.
     */
    fun restartIdle() = tasks.filter { it.status == Status.IDLE && it.session != null }.forEach { t ->
        io.execute { t.session?.stop(); t.session = null; setStatus(t, Status.STOPPED) }
    }

    /** Removes the card and ends the process. pi's session file is kept on disk. */
    fun delete(task: Task) = io.execute {
        task.session?.stop()
        task.session = null
        synchronized(_tasks) { _tasks.remove(task) }
        save()
        main.post { PiService.sync(app, activeCount) }
        emit(task, null, null)
    }

    /** Ends the process; the session stays on disk and can be resumed. */
    fun stop(task: Task) = io.execute {
        task.session?.stop()
        task.session = null
        setStatus(task, Status.STOPPED)
    }

    /**
     * Start pi for a chat you just opened, quietly: the status doesn't change, so the screen doesn't act as if a
     * run began (no "starting", no live Work card). A message sent meanwhile just waits its turn behind it.
     */
    fun warm(task: Task) = io.execute {
        ensureSession(task, quiet = true)
        task.session?.send(JSONObject().put("type", "get_state"))
    }

    private fun ensureSession(task: Task, quiet: Boolean = false) {
        if (task.session?.isAlive == true) return
        if (!quiet) setStatus(task, Status.STARTING)
        val session = PiSession(runtime, task.sessionId, task.cwd, object : PiSession.Listener {
            override fun onRecord(record: JSONObject) = handle(task, record)
            override fun onLog(line: String) = emit(task, null, line)
            override fun onExit(code: Int) {
                if (task.session?.isAlive != true) {
                    setStatus(task, if (code == 0 || task.status == Status.STOPPED) Status.STOPPED else Status.FAILED)
                }
                emit(task, null, "pi exited ($code)")
            }
        })
        task.session = session
        try {
            session.start(envFor(task))
            if (!quiet || task.status == Status.STARTING || task.status == Status.FAILED) setStatus(task, Status.IDLE)
            migrateSessionId(task)
            emit(task, JSONObject().put("type", SESSION_STARTED), null)
        } catch (e: Exception) {
            setStatus(task, Status.FAILED)
            emit(task, null, "start failed: $e")
        }
    }

    private val UUID_RE = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * Tasks from before 0.2.1 used "task-xxxxxxxx" as pi's session id. Some
     * providers send it on (ChatGPT's backend puts it in a session-id header
     * and rejects anything that isn't a UUID), so move such a task onto a
     * copy of its conversation under a proper UUID (pi's own clone).
     */
    private fun migrateSessionId(task: Task) {
        if (UUID_RE.matches(task.sessionId)) return
        runCatching {
            val st = request(task, JSONObject().put("type", "get_state"))
            val current = st.optString("sessionId")
            if (UUID_RE.matches(current)) { setSessionId(task, current); return }
            // Empty session: just start a fresh one; otherwise copy the conversation.
            request(task, JSONObject().put("type", if (st.optInt("messageCount", 1) == 0) "new_session" else "clone"))
            val fresh = request(task, JSONObject().put("type", "get_state")).optString("sessionId")
            if (UUID_RE.matches(fresh)) setSessionId(task, fresh)
        }.onFailure { emit(task, null, "session id migration skipped: ${it.message}") }
    }

    private fun handle(task: Task, r: JSONObject) {
        if (r.optString("type") == "response") waiters[r.optString("id")]?.let { it.complete(r); return }
        when (r.optString("type")) {
            "agent_start" -> setStatus(task, Status.RUNNING)
            "agent_settled" -> setStatus(task, Status.IDLE)
            // Only dialog methods block waiting for the user; the rest are fire-and-forget.
            "extension_ui_request" ->
                if (r.optString("method") in DIALOG_METHODS) setStatus(task, Status.NEEDS_INPUT)
            "tool_execution_start" -> task.lastActivity = "${r.optString("toolName")} ${summarize(r.optJSONObject("args"))}"
        }
        emit(task, r, null)
    }

    private fun summarize(args: JSONObject?): String {
        args ?: return ""
        val v = listOf("path", "command", "url", "pattern").firstNotNullOfOrNull { args.optString(it).ifEmpty { null } }
        return (v ?: args.toString()).take(80)
    }

    private fun setStatus(task: Task, status: Status) {
        if (task.status == status) return
        task.status = status
        save()
        main.post { PiService.sync(app, activeCount); Shortcuts.refresh(app) }
        emit(task, null, null)
    }

    private fun emit(task: Task, record: JSONObject?, log: String?) {
        main.post { listeners.forEach { it.onTaskEvent(task, record, log) } }
    }

    private fun save() {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().put("id", it.id).put("number", it.number).put("title", it.title)
                .put("cwd", it.cwd.path).put("status", it.status.name).put("sessionId", it.sessionId).put("archived", it.archived))
        }
        runCatching { store.writeText(arr.toString()) }
    }

    private fun load() {
        val arr = runCatching { JSONArray(store.readText()) }.getOrNull() ?: return
        synchronized(_tasks) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Processes don't survive app death: a task that was active is now resumable.
                _tasks.add(Task(o.getString("id"), o.getInt("number"), o.getString("title"), File(o.getString("cwd")), restored = true)
                    .apply { sessionId = o.optString("sessionId").ifBlank { id }; archived = o.optBoolean("archived") })
            }
        }
    }
}
