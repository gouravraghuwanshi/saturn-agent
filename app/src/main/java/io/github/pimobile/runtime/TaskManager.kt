package io.github.pimobile.runtime

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
        @Volatile var status = Status.STOPPED
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

    val activeCount get() = tasks.count { it.status in setOf(Status.STARTING, Status.RUNNING, Status.NEEDS_INPUT) }

    /** Home-screen input: every new instruction becomes a new task. */
    fun createTask(prompt: String, cwd: File = runtime.projects): Task {
        val task = synchronized(_tasks) {
            Task(
                id = "task-" + UUID.randomUUID().toString().take(8),
                number = (_tasks.maxOfOrNull { it.number } ?: 0) + 1,
                title = prompt.lineSequence().first().take(80),
                cwd = cwd,
            ).also { _tasks.add(0, it) }
        }
        save()
        send(task, prompt)
        return task
    }

    /** Continue a task's conversation; starts/resumes its process if needed. */
    fun send(task: Task, text: String) {
        io.execute {
            ensureSession(task)
            val cmd = JSONObject().put("type", "prompt").put("message", text)
            // While a run is active pi requires a queueing mode; steer = after current step.
            if (task.status == Status.RUNNING) cmd.put("streamingBehavior", "steer")
            task.session?.send(cmd)
        }
    }

    fun command(task: Task, cmd: JSONObject) = io.execute {
        ensureSession(task)
        task.session?.send(cmd)
    }

    fun abort(task: Task) = task.session?.send(JSONObject().put("type", "abort"))

    /** Answers a dialog `extension_ui_request` (select/confirm/input/editor). */
    fun answer(task: Task, requestId: String, response: JSONObject) {
        task.session?.send(response.put("type", "extension_ui_response").put("id", requestId))
        if (task.status == Status.NEEDS_INPUT) setStatus(task, Status.RUNNING)
    }

    private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")

    /** Synthetic record emitted after a task's pi process is up. */
    const val SESSION_STARTED = "app_session_started"

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

    private fun ensureSession(task: Task) {
        if (task.session?.isAlive == true) return
        setStatus(task, Status.STARTING)
        val session = PiSession(runtime, task.id, task.cwd, object : PiSession.Listener {
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
            session.start(sessionEnv)
            setStatus(task, Status.IDLE)
            emit(task, JSONObject().put("type", SESSION_STARTED), null)
        } catch (e: Exception) {
            setStatus(task, Status.FAILED)
            emit(task, null, "start failed: $e")
        }
    }

    private fun handle(task: Task, r: JSONObject) {
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
        main.post { PiService.sync(app, activeCount) }
        emit(task, null, null)
    }

    private fun emit(task: Task, record: JSONObject?, log: String?) {
        main.post { listeners.forEach { it.onTaskEvent(task, record, log) } }
    }

    private fun save() {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().put("id", it.id).put("number", it.number).put("title", it.title)
                .put("cwd", it.cwd.path).put("status", it.status.name))
        }
        runCatching { store.writeText(arr.toString()) }
    }

    private fun load() {
        val arr = runCatching { JSONArray(store.readText()) }.getOrNull() ?: return
        synchronized(_tasks) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Processes don't survive app death: a task that was active is now resumable.
                _tasks.add(Task(o.getString("id"), o.getInt("number"), o.getString("title"), File(o.getString("cwd")), restored = true))
            }
        }
    }
}
