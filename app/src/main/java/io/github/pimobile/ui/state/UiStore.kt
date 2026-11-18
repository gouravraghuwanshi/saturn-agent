package io.github.pimobile.ui.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pimobile.browser.BrowserBridge
import io.github.pimobile.browser.BrowserManager
import io.github.pimobile.runtime.PiRuntime
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.security.SecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One row of a task's transcript. */
sealed class Item(val key: String) {
    class User(key: String, val text: String) : Item(key)

    class Assistant(key: String) : Item(key) {
        var text by mutableStateOf("")
        var thinking by mutableStateOf("")
        var streaming by mutableStateOf(true)
    }

    /** [callId] comes from the provider and may repeat across turns; [key] is unique. */
    class Tool(key: String, val callId: String, val name: String, val args: JSONObject) : Item(key) {
        var state by mutableStateOf(ToolState.RUNNING)
        var output by mutableStateOf("")
    }

    class Notice(key: String, val text: String, val error: Boolean) : Item(key)

    class Dialog(key: String, val requestId: String, val method: String, val title: String,
                 val message: String, val options: List<String>, val placeholder: String) : Item(key) {
        var answer by mutableStateOf<String?>(null)
    }
}

enum class ToolState { RUNNING, DONE, ERROR }

/** Observable UI state for one task. */
class TaskUi(val task: TaskManager.Task) {
    val items = mutableStateListOf<Item>()
    var status by mutableStateOf(task.status)
    var title by mutableStateOf(task.title)
    var archived by mutableStateOf(task.archived)
    /** Swiped to delete; gone from the list while its undo is offered. */
    var hidden by mutableStateOf(false)
    var cost by mutableStateOf(0.0)
    var contextTokens by mutableStateOf(0)
    var runStartedAt by mutableStateOf<Long?>(null)
    var lastError by mutableStateOf<String?>(null)
    var pendingDialog by mutableStateOf<Item.Dialog?>(null)
    /** True while the current run is using the in-app browser (shows the browser tab). */
    var browsing by mutableStateOf(false)
    /** Latest successful RPC response data per command (feeds the command sheets). */
    val responses = androidx.compose.runtime.mutableStateMapOf<String, JSONObject>()
    /** Text to put into the composer (e.g. the message chosen in /fork or /tree). */
    var prefill by mutableStateOf<String?>(null)
    var thinkingLevel by mutableStateOf<String?>(null)
    /** Bumped whenever the transcript is replaced from pi's history. */
    var rebuilds by mutableStateOf(0)
    internal var current: Item.Assistant? = null
    internal val echoes = ArrayDeque<String>()
    internal var historyLoaded = false
    /** Set after the TUI ran: the transcript must be re-read from pi. */
    internal var historyStale = false
    private var seq = 0
    internal fun key(p: String) = "$p-${seq++}"

    val tools get() = items.filterIsInstance<Item.Tool>()
}

/**
 * Turns pi's RPC event stream (via [TaskManager]) into Compose state.
 * Main-thread only.
 */
object UiStore : TaskManager.Listener {

    val tasks = mutableStateListOf<TaskUi>()
    private val byId = mutableStateMapOf<String, TaskUi>()

    var model by mutableStateOf<String?>(null)
    var commands by mutableStateOf<List<Command>>(emptyList())
    var models by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var hasCredentials by mutableStateOf(false)

    data class Command(val name: String, val description: String, val source: String)

    private lateinit var app: Context
    lateinit var secrets: SecretStore
        private set
    private val prefs by lazy { app.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        secrets = SecretStore(app)
        // One-time migration of the spike's plain-text key into the Keystore-backed store.
        app.getSharedPreferences("spike", Context.MODE_PRIVATE).let { old ->
            old.getString("key", null)?.takeIf { it.isNotBlank() }?.let { secrets.put("OPENROUTER_API_KEY", it) }
            old.edit().clear().apply()
        }
        TaskManager.init(app)
        Control.init(app)
        // App updates move the native lib dir: refresh usr/bin links (and unpack
        // bundles if new) right away, not only when the first task starts.
        Thread({ runCatching { PiRuntime.get(app).prepare() } }, "runtime-prepare").start()
        BrowserManager.init(app)
        io.github.pimobile.browser.AppLinks.init(app)
        applySecrets()
        model = prefs.getString("model", null)
        project = prefs.getString("project", null)?.let { File(it) }
        TaskManager.tasks.forEach { ui(it) }
        TaskManager.addListener(this)
    }

    fun applySecrets() {
        val s = secrets.all()
        TaskManager.sessionEnv = s + ("PIMOBILE_BROWSER_URL" to BrowserBridge.baseUrl)
        hasCredentials = s.isNotEmpty()
    }

    val runtime get() = PiRuntime.get(app)

    fun ui(task: TaskManager.Task): TaskUi = byId.getOrPut(task.id) {
        TaskUi(task).also { tasks.add(it); sortTasks() }
    }

    fun find(id: String) = byId[id]

    private fun sortTasks() {
        val sorted = tasks.sortedByDescending { it.task.number }
        tasks.clear(); tasks.addAll(sorted)
    }

    // ---------------------------------------------------------------- actions

    fun newTask(text: String, cwd: File): TaskUi {
        val t = TaskManager.createTask(text, cwd)
        val ui = ui(t)
        addUser(ui, text)
        return ui
    }

    fun send(ui: TaskUi, text: String, followUp: Boolean = false) {
        addUser(ui, text)
        if (ui.status == Status.RUNNING) {
            TaskManager.command(ui.task, JSONObject().put("type", "prompt").put("message", text)
                .put("streamingBehavior", if (followUp) "followUp" else "steer"))
        } else TaskManager.send(ui.task, text)
    }

    fun stop(ui: TaskUi) = TaskManager.abort(ui.task)

    fun answer(ui: TaskUi, d: Item.Dialog, value: String?) {
        d.answer = value ?: "dismissed"
        ui.pendingDialog = null
        val r = JSONObject()
        when {
            value == null -> r.put("cancelled", true)
            d.method == "confirm" -> r.put("confirmed", value == "Yes")
            else -> r.put("value", value)
        }
        TaskManager.answer(ui.task, d.requestId, r)
    }

    fun setModel(ui: TaskUi?, provider: String, id: String) {
        model = "$provider/$id"
        prefs.edit().putString("model", model).apply()
        // Default for new tasks...
        val f = File(runtime.home, ".pi/agent/settings.json")
        val s = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        f.parentFile?.mkdirs()
        f.writeText(s.put("defaultProvider", provider).put("defaultModel", id).toString(2))
        // ...and the open task, live.
        ui?.let { TaskManager.command(it.task, JSONObject().put("type", "set_model").put("provider", provider).put("modelId", id)) }
    }

    fun refreshModels(ui: TaskUi) = TaskManager.command(ui.task, JSONObject().put("type", "get_available_models"))

    fun compact(ui: TaskUi) = TaskManager.command(ui.task, JSONObject().put("type", "compact"))

    fun rename(ui: TaskUi, title: String) {
        TaskManager.rename(ui.task, title); ui.title = title
    }

    /** A reversible home-screen action, shown as a bar with "Undo" for a few seconds. */
    class Undo(val message: String, val revert: () -> Unit, val commit: () -> Unit = {})
    var undo by mutableStateOf<Undo?>(null)
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun offerUndo(u: Undo) {
        undo?.commit?.invoke() // the previous action is final now
        undo = u
        undoHandler.removeCallbacksAndMessages(null)
        undoHandler.postDelayed({ if (undo === u) { undo = null; u.commit() } }, 5000)
    }

    fun undoLast() { val u = undo ?: return; undo = null; undoHandler.removeCallbacksAndMessages(null); u.revert() }

    /** Swipe-to-delete: hides the card now, deletes for real unless undone. */
    fun deleteWithUndo(ui: TaskUi) {
        ui.hidden = true
        offerUndo(Undo("Task #${ui.task.number} deleted", revert = { ui.hidden = false }, commit = { delete(ui) }))
    }

    fun deleteManyWithUndo(list: List<TaskUi>) {
        if (list.isEmpty()) return
        list.forEach { it.hidden = true }
        offerUndo(Undo(if (list.size == 1) "Task #${list[0].task.number} deleted" else "${list.size} tasks deleted",
            revert = { list.forEach { it.hidden = false } }, commit = { list.forEach { delete(it) } }))
    }

    fun setArchived(ui: TaskUi, archived: Boolean, undoable: Boolean = true) {
        ui.archived = archived
        TaskManager.setArchived(ui.task, archived)
        if (undoable) offerUndo(Undo("Task #${ui.task.number} " + if (archived) "archived" else "restored",
            revert = { setArchived(ui, !archived, undoable = false) }))
    }

    // ------------------------------------------------------------- projects

    /** The project the home screen shows (its tasks) and new tasks start in. */
    var project by mutableStateOf<File?>(null)
        private set
    val currentProject: File get() = project?.takeIf { it.isDirectory } ?: runtime.projects

    fun selectProject(dir: File) {
        project = dir
        prefs.edit().putString("project", dir.path).apply()
    }

    fun tasksIn(dir: File) = tasks.filter { runtime.projectName(it.task.cwd) == runtime.projectName(dir) }

    /** Deletes a project folder and its task cards (pi's session files and the artifacts are kept). */
    fun deleteProject(dir: File) {
        if (dir.canonicalFile == runtime.projects.canonicalFile) return
        tasksIn(dir).forEach { delete(it) }
        Thread { dir.deleteRecursively() }.start()
        if (currentProject.canonicalFile == dir.canonicalFile) selectProject(runtime.projects)
    }

    /** An installed package whose extension stops pi from starting (seen in a task's error output). */
    var brokenPackage by mutableStateOf<String?>(null)
    var repairing by mutableStateOf(false)

    fun removeBroken() {
        val spec = brokenPackage ?: return
        repairing = true
        Thread {
            val code = runCatching { io.github.pimobile.store.Store.run(runtime, "remove", spec) {} }.getOrDefault(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                repairing = false
                if (code == 0) {
                    brokenPackage = null
                    // Their history couldn't load while pi was failing: fetch it again.
                    tasks.filter { it.status == Status.FAILED }.forEach { it.lastError = null; if (it.items.isEmpty()) it.historyLoaded = false }
                    lastOpened?.let { if (it.status == Status.FAILED) open(it) }
                }
                android.widget.Toast.makeText(app, if (code == 0) "Removed $spec — tasks will start again" else "Couldn't remove $spec", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun delete(ui: TaskUi) {
        BrowserManager.close(ui.task.id)
        TaskManager.delete(ui.task)
        tasks.remove(ui); byId.remove(ui.task.id)
    }

    /** Back from the terminal: restart RPC and pull in what happened in the TUI. */
    fun terminalClosed(ui: TaskUi) {
        ui.historyStale = true
        ui.echoes.clear()
        TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
    }

    /** Opening a restored task: start its process so history can be fetched. */
    private var lastOpened: TaskUi? = null

    fun open(ui: TaskUi) {
        lastOpened = ui
        if (ui.task.restored && !ui.historyLoaded && ui.items.isEmpty()) {
            TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
        }
    }

    private fun addUser(ui: TaskUi, text: String) {
        ui.items.add(Item.User(ui.key("u"), text))
        ui.echoes.addLast(text)
    }

    // ----------------------------------------------------------------- events

    override fun onTaskEvent(task: TaskManager.Task, record: JSONObject?, log: String?) {
        if (TaskManager.tasks.none { it.id == task.id }) return
        val ui = ui(task)
        ui.status = task.status
        ui.title = task.title
        ui.archived = task.archived
        log?.let { io.github.pimobile.store.Store.packageInError(it) }?.let { brokenPackage = it }
        if (ui.status == Status.FAILED && log != null && ui.lastError == null && log.isNotBlank()) {
            ui.lastError = log.take(300)
        }
        record?.let { reduce(ui, it) }
    }

    private fun reduce(ui: TaskUi, r: JSONObject) {
        when (r.optString("type")) {
            TaskManager.SESSION_STARTED -> {
                TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
                if (commands.isEmpty()) TaskManager.command(ui.task, JSONObject().put("type", "get_commands"))
                if (ui.historyStale || (ui.task.restored && !ui.historyLoaded && ui.items.isEmpty())) {
                    ui.historyLoaded = true; ui.historyStale = false
                    TaskManager.command(ui.task, JSONObject().put("type", "get_messages"))
                }
            }
            "agent_start" -> { ui.runStartedAt = System.currentTimeMillis(); ui.lastError = null }
            "agent_settled" -> { ui.runStartedAt = null; ui.browsing = false; ui.current?.streaming = false; ui.current = null }
            "message_start" -> {
                val m = r.optJSONObject("message") ?: return
                when (m.optString("role")) {
                    "user" -> {
                        // Messages we sent are already shown; pi echoes them in order
                        // (possibly expanded, e.g. /skill:x or prompt templates).
                        if (ui.echoes.isNotEmpty()) ui.echoes.removeFirst()
                        else ui.items.add(Item.User(ui.key("u"), textOf(m)))
                    }
                    "assistant" -> Item.Assistant(ui.key("a")).also { ui.current = it; ui.items.add(it) }
                }
            }
            "message_update" -> {
                val ev = r.optJSONObject("assistantMessageEvent") ?: return
                val cur = ui.current ?: Item.Assistant(ui.key("a")).also { ui.current = it; ui.items.add(it) }
                when (ev.optString("type")) {
                    "text_delta" -> cur.text += ev.optString("delta")
                    "thinking_delta" -> cur.thinking += ev.optString("delta")
                }
            }
            "message_end" -> {
                val m = r.optJSONObject("message") ?: return
                if (m.optString("role") != "assistant") return
                ui.current?.let { cur ->
                    cur.streaming = false
                    val final = textOf(m)
                    if (final.isNotEmpty()) cur.text = final
                    if (cur.text.isBlank() && cur.thinking.isBlank()) ui.items.remove(cur)
                }
                ui.current = null
                m.optJSONObject("usage")?.let { u ->
                    ui.cost += u.optJSONObject("cost")?.optDouble("total") ?: 0.0
                    ui.contextTokens = u.optInt("totalTokens", ui.contextTokens)
                }
                m.optString("errorMessage").takeIf { it.isNotEmpty() }?.let {
                    ui.lastError = it
                    ui.items.add(Item.Notice(ui.key("n"), it, error = true))
                }
            }
            "tool_execution_start" -> {
                if (r.optString("toolName").startsWith("browser_")) ui.browsing = true
                ui.items.add(
                Item.Tool(ui.key("t"), r.optString("toolCallId"), r.optString("toolName"), r.optJSONObject("args") ?: JSONObject())
                )
            }
            "tool_execution_update" -> tool(ui, r)?.output = resultText(r.optJSONObject("partialResult"))
            "tool_execution_end" -> tool(ui, r)?.let {
                it.output = resultText(r.optJSONObject("result"))
                it.state = if (r.optBoolean("isError")) ToolState.ERROR else ToolState.DONE
            }
            "auto_retry_start" -> ui.items.add(Item.Notice(ui.key("n"),
                "Retrying (${r.optInt("attempt")}/${r.optInt("maxAttempts")}): ${r.optString("errorMessage")}", false))
            "compaction_start" -> ui.items.add(Item.Notice(ui.key("n"), "Compacting context…", false))
            "extension_error" -> ui.items.add(Item.Notice(ui.key("n"), "Extension error: ${r.optString("error")}", true))
            "extension_ui_request" -> extensionUi(ui, r)
            "response" -> response(ui, r)
        }
    }

    /** Events from the bundled pimobile-app extension (tree navigation, reload). */
    private fun appEvent(ui: TaskUi, e: JSONObject) {
        when (e.optString("type")) {
            "tree_done" -> {
                e.optJSONObject("result")?.optString("editorText")?.takeIf { it.isNotBlank() }?.let { ui.prefill = it }
                TaskManager.command(ui.task, JSONObject().put("type", "get_messages"))
            }
            "reloaded" -> { commands = emptyList(); TaskManager.command(ui.task, JSONObject().put("type", "get_commands")) }
            "error" -> ui.items.add(Item.Notice(ui.key("n"), "${e.optString("scope")}: ${e.optString("message")}", true))
        }
    }

    /** Sends an RPC command to a task's pi process (starting it if needed). */
    fun rpc(ui: TaskUi, type: String, vararg fields: Pair<String, Any?>) {
        val cmd = JSONObject().put("type", type)
        fields.forEach { (k, v) -> if (v != null) cmd.put(k, v) }
        TaskManager.command(ui.task, cmd)
    }

    /** Runs a hidden pimobile-app extension command in the task's pi process. */
    fun appCommand(ui: TaskUi, name: String, args: JSONObject = JSONObject()) =
        rpc(ui, "prompt", "message" to "/pimobile-$name $args", "id" to "app-${appIds++}")
    private var appIds = 0

    private fun extensionUi(ui: TaskUi, r: JSONObject) {
        when (val method = r.optString("method")) {
            "select", "confirm", "input", "editor" -> {
                val opts = r.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: if (method == "confirm") listOf("Yes", "No") else emptyList()
                val d = Item.Dialog(ui.key("d"), r.optString("id"), method, r.optString("title"),
                    r.optString("message"), opts, r.optString("placeholder"))
                ui.items.add(d)
                ui.pendingDialog = d
            }
            "notify" -> {
                val msg = r.optString("message")
                if (msg.startsWith("pimobile:")) runCatching { appEvent(ui, JSONObject(msg.removePrefix("pimobile:"))) }
                else ui.items.add(Item.Notice(ui.key("n"), msg, r.optString("notifyType") == "error"))
            }
        }
    }

    private fun response(ui: TaskUi, r: JSONObject) {
        if (!r.optBoolean("success")) {
            ui.items.add(Item.Notice(ui.key("n"), "${r.optString("command")}: ${r.optString("error")}", true))
            return
        }
        val data = r.optJSONObject("data")
        ui.responses[r.optString("command")] = data ?: JSONObject()
        when (r.optString("command")) {
            // Extension commands consumed by pi never echo a user message.
            "prompt" -> if (data?.optString("disposition") == "handled" && !r.optString("id").startsWith("app-")) ui.echoes.removeLastOrNull()
            "get_state" -> {
                data?.optJSONObject("model")?.let { m ->
                    if (m.optString("id") != "unknown") {
                        model = "${m.optString("provider")}/${m.optString("id")}"
                        prefs.edit().putString("model", model).apply()
                    }
                }
                data?.optString("thinkingLevel")?.takeIf { it.isNotBlank() }?.let { ui.thinkingLevel = it }
                data?.optString("sessionId")?.let { TaskManager.setSessionId(ui.task, it) }
            }
            // These switch pi to another session: follow it and reload the transcript.
            "fork", "clone", "new_session", "switch_session" -> if (data?.optBoolean("cancelled") != true) {
                if (r.optString("command") == "fork") data?.optString("text")?.takeIf { it.isNotBlank() }?.let { ui.prefill = it }
                if (r.optString("command") == "new_session") { ui.items.clear(); ui.cost = 0.0; ui.contextTokens = 0 }
                TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
                TaskManager.command(ui.task, JSONObject().put("type", "get_messages"))
            }
            "set_thinking_level" -> TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
            "set_model" -> data?.let { model = "${it.optString("provider")}/${it.optString("id")}" }
            "get_commands" -> data?.optJSONArray("commands")?.let { a ->
                commands = (0 until a.length()).map { a.getJSONObject(it) }
                    .filterNot { it.optString("name").startsWith("pimobile-") } // the app's own bridge commands
                    .map { Command(it.optString("name"), it.optString("description"), it.optString("source")) }
            }
            "get_available_models" -> data?.optJSONArray("models")?.let { a ->
                models = (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("provider") to it.optString("id") }
            }
            "get_messages" -> data?.optJSONArray("messages")?.let { rebuild(ui, it) }
        }
    }

    /** Rebuilds a restored task's transcript from pi's stored messages. */
    private fun rebuild(ui: TaskUi, msgs: JSONArray) {
        val out = mutableListOf<Item>()
        val toolsById = mutableMapOf<String, Item.Tool>()
        ui.cost = 0.0; ui.contextTokens = 0 // recomputed from the full history below
        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            when (m.optString("role")) {
                "user" -> out.add(Item.User("h$i", textOf(m)))
                "assistant" -> {
                    val content = m.optJSONArray("content") ?: JSONArray()
                    // Text/thinking before a tool call is its own row; text after it starts a new one.
                    // Keys come from the position in pi's history, so a reload keeps
                    // existing rows stable and only appends what is new.
                    var part = 0
                    var a = Item.Assistant("h$i.a$part").apply { streaming = false }
                    fun flushText() {
                        if (a.text.isNotBlank() || a.thinking.isNotBlank()) {
                            out.add(a)
                            a = Item.Assistant("h$i.a${++part}").apply { streaming = false }
                        }
                    }
                    for (j in 0 until content.length()) {
                        val c = content.getJSONObject(j)
                        when (c.optString("type")) {
                            "text" -> a.text += c.optString("text")
                            "thinking" -> a.thinking += c.optString("thinking")
                            "toolCall" -> {
                                flushText()
                                val t = Item.Tool("h$i.t$j", c.optString("id"), c.optString("name"), c.optJSONObject("arguments") ?: JSONObject())
                                t.state = ToolState.DONE
                                toolsById[t.callId] = t; out.add(t)
                            }
                        }
                    }
                    flushText()
                    m.optJSONObject("usage")?.let { u ->
                        ui.cost += u.optJSONObject("cost")?.optDouble("total") ?: 0.0
                        ui.contextTokens = u.optInt("totalTokens", ui.contextTokens)
                    }
                }
                "toolResult" -> toolsById[m.optString("toolCallId")]?.let {
                    it.output = resultText(m)
                    it.state = if (m.optBoolean("isError")) ToolState.ERROR else ToolState.DONE
                }
            }
        }
        ui.items.clear(); ui.items.addAll(out)
        ui.rebuilds++
    }

    private fun tool(ui: TaskUi, r: JSONObject) =
        ui.items.lastOrNull { it is Item.Tool && it.callId == r.optString("toolCallId") } as? Item.Tool

    private fun textOf(m: JSONObject): String {
        m.opt("content").let { c ->
            if (c is String) return c
            if (c is JSONArray) return (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        }
        return ""
    }

    private fun resultText(r: JSONObject?): String {
        val c = r?.optJSONArray("content") ?: return ""
        return (0 until c.length()).mapNotNull { c.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
            .takeLast(4000)
    }
}

/** "openrouter/deepseek/deepseek-v4-flash" → ("deepseek-v4-flash", "openrouter"). */
fun modelLabel(model: String?): Pair<String, String>? {
    model ?: return null
    val provider = model.substringBefore('/')
    val id = model.substringAfter('/').substringAfterLast('/')
    // OpenRouter-style variants (":free", ":nitro") read better next to the provider.
    val variant = id.substringAfter(':', "")
    return id.substringBefore(':').ifBlank { model } to (if (variant.isNotEmpty()) "$provider $variant" else provider)
}
