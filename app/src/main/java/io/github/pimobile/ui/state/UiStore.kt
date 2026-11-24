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
        /** The tool's structured result (`details`), live while it runs: what rich cards (crew, checklist, progress) are drawn from. */
        var details by mutableStateOf<JSONObject?>(null)
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
    /** The model's context window (tokens), from pi's state; 0 until known. */
    var contextWindow by mutableStateOf(0)
    var runStartedAt by mutableStateOf<Long?>(null)
    var lastError by mutableStateOf<String?>(null)
    /** Saturn's last reply read from the session file, for the home card before the chat is loaded. */
    var savedReply by mutableStateOf<String?>(null)
    /** Steps (tool calls) the task has run, read from its session before the chat is loaded. */
    var stepsDone by mutableStateOf<Int?>(null)
    /** When the task last finished a run (or its session last changed). */
    var lastRunAt by mutableStateOf<Long?>(null)
    /** Time Saturn has spent working on this task, over all its runs. */
    var workMs by mutableStateOf(0L)
    /** How the last run ended: "ok", "stopped" or "failed" (null while it runs, or if unknown). */
    var ended by mutableStateOf<String?>(null)
    /** What it made or changed, read from its session before the chat is loaded. */
    var savedOutcome by mutableStateOf<Outcome?>(null)
    var pendingDialog by mutableStateOf<Item.Dialog?>(null)
    /** True while the current run is using the in-app browser (shows the browser tab). */
    var browsing by mutableStateOf(false)
    /** Latest successful RPC response data per command (feeds the command sheets). */
    val responses = androidx.compose.runtime.mutableStateMapOf<String, JSONObject>()
    /** Text to put into the composer (e.g. the message chosen in /fork or /tree). */
    var prefill by mutableStateOf<String?>(null)
    /** Editing your last message: the next send replaces it (and Saturn answers the new version). */
    var editingLast by mutableStateOf(false)
    var thinkingLevel by mutableStateOf<String?>(null)
    /** Stop was pressed; cleared when the run ends. */
    var stopping by mutableStateOf(false)
    /** Bumped whenever the transcript is replaced from pi's history. */
    var rebuilds by mutableStateOf(0)
    /** Set while this task is a run of a routine (Scheduler job id). */
    var routineId: String? = null
    internal var current: Item.Assistant? = null
    internal val echoes = ArrayDeque<String>()
    internal var historyLoaded = false
    /** open() is under way (so a second call from the screen doesn't read twice). */
    internal var opening = false
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

    lateinit var app: Context
        private set
    lateinit var secrets: SecretStore
        private set
    private val prefs by lazy { app.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        secrets = SecretStore(app)
        moveKeysToPi()
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
        io.github.pimobile.device.Device.init(app)
        io.github.pimobile.runtime.Scheduler.init(app)
        io.github.pimobile.runtime.Tools.init(app)
        io.github.pimobile.runtime.Updater.init(app)
        io.github.pimobile.runtime.Computers.load()
        Thread { runCatching { io.github.pimobile.runtime.Computers.writeConfig() } }.start()
        applySecrets()
        loadDefaults()
        io.github.pimobile.ui.theme.Ph.amoled = prefs.getBoolean("amoled", false)
        project = prefs.getString("project", null)?.let { File(it) }
        TaskManager.tasks.forEach { ui(it) }
        TaskManager.addListener(this)
    }

    /** pi provider id for each key env var the old Settings → API keys section stored. */
    private val KEY_PROVIDERS = mapOf(
        "OPENROUTER_API_KEY" to "openrouter", "ANTHROPIC_API_KEY" to "anthropic", "OPENAI_API_KEY" to "openai",
        "GEMINI_API_KEY" to "google", "DEEPSEEK_API_KEY" to "deepseek", "GROQ_API_KEY" to "groq",
        "XAI_API_KEY" to "xai", "MISTRAL_API_KEY" to "mistral",
    )

    private val authFile get() = File(runtime.home, ".pi/agent/auth.json")

    /** Saves an API key the way pi's own /login does (auth.json), so /login and /logout show it. */
    fun saveApiKey(provider: String, key: String) {
        val f = authFile
        val auth = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        auth.put(provider, JSONObject().put("type", "api_key").put("key", key.trim()))
        f.parentFile?.mkdirs(); f.writeText(auth.toString(2))
        Control.refreshSignedIn(); Control.restart(); TaskManager.restartIdle()
    }

    /**
     * A custom endpoint (Ollama, LM Studio, vLLM, a proxy…) goes into pi's
     * models.json as its own provider; the first model becomes the default.
     */
    fun saveCustomProvider(id: String, name: String, baseUrl: String, api: String, key: String, models: List<String>) {
        val f = File(runtime.home, ".pi/agent/models.json")
        val root = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        // A literal key: pi reads a leading "$" or "!" as env/command, "$$"/"$!" escape them.
        val literal = key.trim().ifBlank { "none" }.let { if (it.startsWith("$") || it.startsWith("!")) "$" + it else it }
        val list = org.json.JSONArray().apply { models.forEach { put(JSONObject().put("id", it)) } }
        providers.put(id, JSONObject().put("name", name).put("baseUrl", baseUrl.trim().trimEnd('/')).put("api", api)
            .put("apiKey", literal).put("models", list))
        f.parentFile?.mkdirs(); f.writeText(root.toString(2))
        models.firstOrNull()?.let { setModel(null, id, it) }
        Control.restart(); TaskManager.restartIdle()
    }

    /**
     * API keys now live in /login (pi's auth.json). Keys saved by the old Settings
     * section move there once, unless pi already has a credential for that provider.
     */
    private fun moveKeysToPi() = runCatching {
        val old = secrets.all().filterKeys { it in KEY_PROVIDERS }
        if (old.isEmpty()) return@runCatching
        val f = authFile
        val auth = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        val moved = old.filter { (env, key) ->
            val p = KEY_PROVIDERS.getValue(env)
            val existing = auth.optJSONObject(p)
            when {
                existing == null -> { auth.put(p, JSONObject().put("type", "api_key").put("key", key)); true }
                existing.optString("key") == key -> true // already there
                else -> false // pi has another credential for it: keep this one where it is
            }
        }
        f.parentFile?.mkdirs(); f.writeText(auth.toString(2))
        moved.keys.forEach { secrets.put(it, null) }
    }

    fun applySecrets() {
        val s = secrets.all()
        TaskManager.sessionEnv = s + ("PIMOBILE_BROWSER_URL" to BrowserBridge.baseUrl)
        hasCredentials = s.isNotEmpty()
    }

    val runtime get() = PiRuntime.get(app)

    fun ui(task: TaskManager.Task): TaskUi = byId.getOrPut(task.id) {
        TaskUi(task).also { ui ->
            tasks.add(ui); sortTasks()
            // A task restored after a restart has no transcript until it's opened: read its last reply for the card.
            if (task.restored) previewIo.execute {
                val sum = io.github.pimobile.runtime.Transfer.summary(app, task.sessionId)
                if (sum != null) android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (ui.savedReply == null) ui.savedReply = sum.lastReply
                    if (ui.stepsDone == null) ui.stepsDone = sum.steps
                    if (ui.lastRunAt == null) ui.lastRunAt = sum.at
                    if (ui.workMs == 0L) ui.workMs = sum.workMs
                    if (ui.ended == null) ui.ended = sum.ended
                    if (ui.savedOutcome == null) ui.savedOutcome = Outcome.of(sum.calls)
                }
            }
        }
    }
    private val previewIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun find(id: String) = byId[id]

    private fun sortTasks() {
        val sorted = tasks.sortedByDescending { it.task.number }
        tasks.clear(); tasks.addAll(sorted)
    }

    // ---------------------------------------------------------------- actions

    fun newTask(text: String, cwd: File, images: List<Attachment> = emptyList()): TaskUi {
        val t = TaskManager.createTask(withImages(text, images), cwd, imagesJson(images))
        val ui = ui(t)
        addUser(ui, withImages(text, images))
        return ui
    }

    fun send(ui: TaskUi, text: String, followUp: Boolean = false, images: List<Attachment> = emptyList()) {
        val message = withImages(text, images)
        addUser(ui, message)
        if (ui.status == Status.RUNNING) {
            TaskManager.command(ui.task, JSONObject().put("type", "prompt").put("message", message)
                .put("streamingBehavior", if (followUp) "followUp" else "steer")
                .apply { imagesJson(images)?.let { put("images", it) } })
        } else TaskManager.send(ui.task, message, imagesJson(images))
    }

    /**
     * Retry: answer my last message again, in place. Steps: find its entry
     * (get_fork_messages) → move the session to just before it (/tree) →
     * reload the transcript → send the same text. taskId → "F" | "T:text" | "S:text".
     */
    private val retryStep = androidx.compose.runtime.mutableStateMapOf<String, String>()
    fun retrying(ui: TaskUi) = ui.task.id in retryStep
    /** Replacement text for an in-place retry (editing your last message); none means the same text again. */
    private val retryWith = mutableMapOf<String, String>()
    fun retry(ui: TaskUi, replacement: String? = null) {
        if (retrying(ui) || ui.status == Status.RUNNING || ui.status == Status.STARTING || ui.status == Status.NEEDS_INPUT) return
        if (replacement != null) retryWith[ui.task.id] = replacement else retryWith.remove(ui.task.id)
        retryStep[ui.task.id] = "F"
        rpc(ui, "get_fork_messages")
    }

    fun stop(ui: TaskUi) {
        if (ui.status != Status.RUNNING && ui.status != Status.NEEDS_INPUT && ui.status != Status.STARTING) return
        ui.stopping = true
        TaskManager.abort(ui.task)
    }

    // ------------------------------------------------------------- images

    /**
     * Something the user attached, saved under ~/uploads so the agent's tools
     * can open it. Images also go to the model directly ([base64]).
     */
    class Attachment(val path: String, val base64: String?, val mime: String, val name: String) {
        val isImage get() = base64 != null
    }

    private fun uploadsFile(name: String): File {
        val dir = File(runtime.home, "uploads").apply { mkdirs() }
        val clean = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "file" }
        var f = File(dir, clean)
        var n = 1
        while (f.exists()) f = File(dir, clean.substringBeforeLast('.') + "-" + n++ + (clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }))
        return f
    }

    /** A new file in ~/uploads for the camera to write into. */
    fun cameraFile(): File = uploadsFile("Camera-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg")

    /** Blocking; call off the main thread. */
    fun attach(uri: android.net.Uri): Attachment {
        val cr = app.contentResolver
        val mime = cr.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.toString().substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val name = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        // Photos (not GIF/SVG, which lose their point when re-encoded) are downscaled and also sent inline.
        if (mime.startsWith("image/") && mime != "image/gif" && mime != "image/svg+xml") runCatching {
            val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                val max = 1568 // larger images are downscaled by the model providers anyway
                val (w, h) = info.size.width to info.size.height
                val scale = minOf(1.0, max.toDouble() / maxOf(w, h))
                d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val f = uploadsFile(name.substringBeforeLast('.') + ".jpg")
            val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            f.writeBytes(bytes)
            return Attachment(f.path, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP), "image/jpeg", f.name)
        }
        val f = uploadsFile(name)
        cr.openInputStream(uri)?.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                    if (total > 100L * 1024 * 1024) { out.close(); f.delete(); error("$name is over 100 MB") }
                    out.write(buf, 0, n)
                }
            }
        } ?: error("Couldn't read $name")
        return Attachment(f.path, null, mime, f.name)
    }

    const val IMAGE_NOTE = "[Attached image: "
    const val FILE_NOTE = "[Attached file: "

    /** The message text pi gets: what the user wrote plus where each attachment is saved. */
    private fun withImages(text: String, items: List<Attachment>) =
        if (items.isEmpty()) text
        else (if (text.isBlank()) "" else text + "\n\n") + items.joinToString("\n") { (if (it.isImage) IMAGE_NOTE else FILE_NOTE) + it.path + "]" }

    private fun imagesJson(items: List<Attachment>) = items.filter { it.isImage }.let { images ->
        if (images.isEmpty()) null else JSONArray().apply {
            images.forEach { put(JSONObject().put("type", "image").put("data", it.base64).put("mimeType", it.mime)) }
        }
    }

    // ------------------------------------------------------------ branches

    /** Something shared into Saturn from another app, waiting in the home composer. */
    /** [taskId]: dropped onto an open task (its message box); null means the home message box. */
    class Share(val text: String, val uris: List<android.net.Uri>, val taskId: String? = null)
    var share by mutableStateOf<Share?>(null)
    /** Put the cursor in the home composer (widget, quick-settings tile). */
    var focusComposer by mutableStateOf(false)
    /** The widget's mic: the home composer starts listening once. */
    var voiceRequest by mutableStateOf(false)
    /** Home should open its composer (e.g. after "pull up for a new task"). */
    var composeRequest by mutableStateOf(false)
    /** MainActivity is on screen (so finished tasks don't need a notification). */
    @Volatile var foreground = false
    /** The task currently open on screen, if any. */
    @Volatile var openTaskId: String? = null
    /** True while the home list or the task's own screen is in front of the user: no notification needed. */
    fun watching(taskId: String) = foreground && (openTaskId == taskId || openTaskId == null)

    /** Asks the App to open a task (e.g. a new branch). */
    var openRequest by mutableStateOf<String?>(null)
    var branchError by mutableStateOf<String?>(null)
    var branching by mutableStateOf(false)

    /**
     * New task card from this one: before the [userIndex]-th message of yours
     * (its text goes back in the box to edit), or a copy of the whole chat.
     */
    fun branch(ui: TaskUi, userIndex: Int? = null, userText: String? = null) {
        if (branching) return
        branching = true; branchError = null
        TaskManager.branch(ui.task, userIndex, userText) { t, text, err ->
            branching = false
            if (t == null) { branchError = err; return@branch }
            val nu = ui(t)
            nu.prefill = text
            openRequest = t.id
        }
    }

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

    fun setAmoled(on: Boolean) { io.github.pimobile.ui.theme.Ph.amoled = on; prefs.edit().putBoolean("amoled", on).apply() }

    /** The app-wide default thinking level (pi's defaultThinkingLevel), or null for pi's own default. */
    var thinkingDefault by mutableStateOf<String?>(null)
        private set

    private val piSettings get() = File(runtime.home, ".pi/agent/settings.json")
    private fun readSettings() = runCatching { JSONObject(piSettings.readText()) }.getOrElse { JSONObject() }
    private fun writeSettings(change: (JSONObject) -> Unit) {
        val s = readSettings(); change(s)
        piSettings.parentFile?.mkdirs(); piSettings.writeText(s.toString(2))
    }

    /** pi's settings.json is the source of truth for the defaults (same file pi's Ctrl+S writes). */
    private fun loadDefaults() {
        val s = readSettings()
        model = s.optString("defaultProvider").takeIf { it.isNotBlank() }?.let { p -> s.optString("defaultModel").takeIf { it.isNotBlank() }?.let { "$p/$it" } }
            ?: prefs.getString("model", null)
        thinkingDefault = s.optString("defaultThinkingLevel").takeIf { it.isNotBlank() }
    }

    /**
     * Picking a model is saved as the default (like Ctrl+S in pi's TUI) and
     * applied to the open task now; other tasks switch when they next start.
     */
    fun setModel(ui: TaskUi?, provider: String, id: String) {
        model = "$provider/$id"
        prefs.edit().putString("model", model).apply()
        writeSettings { it.put("defaultProvider", provider).put("defaultModel", id) }
        ui?.let { TaskManager.command(it.task, JSONObject().put("type", "set_model").put("provider", provider).put("modelId", id)) }
    }

    /** Same for the thinking level ("effort"). */
    fun setThinking(ui: TaskUi?, level: String) {
        thinkingDefault = level
        writeSettings { it.put("defaultThinkingLevel", level) }
        ui?.let { it.thinkingLevel = level; TaskManager.command(it.task, JSONObject().put("type", "set_thinking_level").put("level", level)) }
    }

    /** A task whose session remembers another model or level is brought in line with the defaults. */
    private fun align(ui: TaskUi, state: JSONObject) {
        val m = state.optJSONObject("model")
        val current = m?.let { "${it.optString("provider")}/${it.optString("id")}" }
        val want = model
        if (want != null && current != null && current != want && m.optString("id") != "unknown") {
            TaskManager.command(ui.task, JSONObject().put("id", "align-model").put("type", "set_model")
                .put("provider", want.substringBefore('/')).put("modelId", want.substringAfter('/')))
        }
        val level = thinkingDefault
        if (level != null && state.optString("thinkingLevel").let { it.isNotBlank() && it != level }) {
            TaskManager.command(ui.task, JSONObject().put("id", "align-thinking").put("type", "set_thinking_level").put("level", level))
        }
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

    /** Make the pending action final now (the app is leaving the screen). */
    fun commitUndo() { val u = undo ?: return; undo = null; undoHandler.removeCallbacksAndMessages(null); u.commit() }

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

    /** Whether you've ever tapped Saturn on home (until then its status line hints that you can). */
    val saturnKnown: Boolean get() = prefs.getBoolean("saturn_tapped", false)
    fun markSaturnTapped() { prefs.edit().putBoolean("saturn_tapped", true).apply() }
    /** Chat screen in paper (book) colours. */
    // Loaded on first use: prefs need the app context, which isn't set yet when this object is created.
    // Home settings (read on first use, like reading mode: prefs need the app context).
    /** A task just created and opened from home's message box: it slips in from the box instead of morphing from its card. */
    var slipInto by mutableStateOf<String?>(null)

    private var swipePagesState by mutableStateOf<Boolean?>(null)
    /** Swiping sideways on home switches Tasks ↔ Routines; task cards then don't swipe to archive/delete. */
    var swipePages: Boolean
        get() = swipePagesState ?: prefs.getBoolean("swipe_pages", false).also { swipePagesState = it }
        set(v) { swipePagesState = v; prefs.edit().putBoolean("swipe_pages", v).apply() }
    private var hapticsState by mutableStateOf<Boolean?>(null)
    /** Vibrations for taps, swipes, steps and finished runs (all of them go through buzz()). */
    var haptics: Boolean
        get() = hapticsState ?: prefs.getBoolean("haptics", true).also { hapticsState = it }
        set(v) { hapticsState = v; prefs.edit().putBoolean("haptics", v).apply() }
    private var openNewState by mutableStateOf<Boolean?>(null)
    /** Sending a new task from home goes straight into its chat. */
    var openNewTasks: Boolean
        get() = openNewState ?: prefs.getBoolean("open_new_tasks", false).also { openNewState = it }
        set(v) { openNewState = v; prefs.edit().putBoolean("open_new_tasks", v).apply() }

    private var readingState by mutableStateOf<Boolean?>(null)
    val reading: Boolean get() = readingState ?: prefs.getBoolean("reading", false).also { readingState = it }
    fun toggleReading() { val v = !reading; readingState = v; prefs.edit().putBoolean("reading", v).apply() }
    /** Paper shade 0..1 (long-press the book icon and drag down). */
    private var shadeState by mutableStateOf<Float?>(null)
    val shade: Float get() = shadeState ?: prefs.getFloat("reading_shade", 0f).also { shadeState = it }
    fun previewShade(v: Float) { shadeState = v.coerceIn(0f, 1f) }
    fun saveShade() { prefs.edit().putFloat("reading_shade", shade).apply() }
    /** Times the chat box's hold actions were used; the hint stops after a few. */
    var holdUses: Int
        get() = prefs.getInt("hold_uses", 0)
        set(v) { prefs.edit().putInt("hold_uses", v).apply() }
    /** Home's page (0 tasks, 1 routines): it reopens where you left it. */
    var homePage: Int
        get() = prefs.getInt("home_page", 0)
        set(v) { prefs.edit().putInt("home_page", v).apply() }

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

    /** Deleted tasks: late events from their process (it's still stopping) must not bring the card back. */
    private val deleted = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun delete(ui: TaskUi) {
        deleted += ui.task.id
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

    // Chats read ahead of time (home preloads the likely ones), so opening one shows it on the first frame.
    private val preread = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "history").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private fun unread(ui: TaskUi) = ui.task.restored && !ui.historyLoaded && ui.items.isEmpty()

    /** Read these chats' history in the background, quietly, so they open instantly. */
    fun prefetch(list: List<TaskUi>) {
        list.filter { unread(it) && !preread.containsKey(it.task.id) && it.status !in ACTIVE_STATES }.forEach { ui ->
            reader.execute {
                if (!unread(ui) || preread.containsKey(ui.task.id)) return@execute
                runCatching { readSessionMessages(ui.task.sessionId, ui.task.cwd) }.getOrNull()
                    ?.takeIf { it.length() > 0 }?.let { preread[ui.task.id] = it }
            }
        }
    }
    private val ACTIVE_STATES = setOf(Status.RUNNING, Status.STARTING, Status.NEEDS_INPUT)

    /** Called on tap, before the chat screen exists (and again from the screen; the second call does nothing). */
    fun open(ui: TaskUi) {
        lastOpened = ui
        if (!unread(ui) || ui.opening) return
        ui.opening = true
        val t0 = android.os.SystemClock.uptimeMillis()
        val ready = preread.remove(ui.task.id)
        if (ready != null) {
            ui.historyLoaded = true; rebuild(ui, ready)
            android.util.Log.d("SaturnPerf", "open #${ui.task.number}: preloaded, ${ready.length()} msgs in ${android.os.SystemClock.uptimeMillis() - t0}ms")
        } else {
            // Show the chat straight from pi's session file, instead of waiting for pi to hand it back.
            val sessionId = ui.task.sessionId
            val cwd = ui.task.cwd
            Thread {
                val msgs = runCatching { readSessionMessages(sessionId, cwd) }.getOrNull()
                if (msgs != null && msgs.length() > 0) android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (ui.items.isEmpty() && !ui.historyLoaded) { ui.historyLoaded = true; rebuild(ui, msgs) }
                    android.util.Log.d("SaturnPerf", "open #${ui.task.number}: read ${msgs.length()} msgs in ${android.os.SystemClock.uptimeMillis() - t0}ms")
                }
            }.apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
        }
        // pi starts once the opening animation is over: a cold node start would otherwise take the CPU
        // the animation and first frame need. Sending a message starts it at once anyway.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ui.opening = false
            if (ui.task.session == null) TaskManager.warm(ui.task)
        }, 700)
    }

    /**
     * The messages on a session's current branch, read from its .jsonl: the last entry is the
     * leaf; follow parentId back to the root. Same shape as pi's get_messages.
     */
    private fun sessionFile(sessionId: String, cwd: File): File? {
        val dir = File(runtime.home, ".pi/agent/sessions/" + io.github.pimobile.runtime.Usage.folderFor(cwd))
        return dir.listFiles()?.firstOrNull { it.name.endsWith("_$sessionId.jsonl") }
            ?: File(runtime.home, ".pi/agent/sessions").walkTopDown().maxDepth(2)
                .firstOrNull { it.isFile && it.name.endsWith("_$sessionId.jsonl") }
    }

    // ----------------------------------------------------------------- search

    /** One task that matches a search: where ([snippet], match at [at] for [len] chars) and how often. */
    class Hit(val ui: TaskUi, val snippet: String, val at: Int, val len: Int, val count: Int, val inTitle: Boolean, val time: Long)

    private class Indexed(val mtime: Long, val text: String)
    private val index = java.util.concurrent.ConcurrentHashMap<String, Indexed>()

    /** What a task said and was told, as plain text (cached until its session file changes). Call off the main thread. */
    private fun textOf(t: TaskUi): Pair<String, Long> {
        val f = sessionFile(t.task.sessionId, t.task.cwd) ?: return "" to 0L
        val m = f.lastModified()
        index[t.task.id]?.takeIf { it.mtime == m }?.let { return it.text to m }
        val sb = StringBuilder()
        readSessionMessages(t.task.sessionId, t.task.cwd)?.let { a ->
            for (i in 0 until a.length()) {
                val msg = a.optJSONObject(i) ?: continue
                if (msg.optString("role") !in setOf("user", "assistant")) continue
                when (val c = msg.opt("content")) {
                    is String -> sb.append(c).append('\n')
                    is JSONArray -> for (j in 0 until c.length()) c.optJSONObject(j)?.takeIf { it.optString("type") == "text" }
                        ?.let { sb.append(it.optString("text")).append('\n') }
                }
            }
        }
        val text = sb.toString().replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ")
        index[t.task.id] = Indexed(m, text)
        return text to m
    }

    /** Every task (all projects) whose title or chat contains [query]; titles first, then the most recent. Off the main thread. */
    fun search(query: String): List<Hit> {
        val q = query.trim()
        val all = tasks.toList().filterNot { it.hidden }
        if (q.isEmpty()) return all.map { val (_, m) = textOf(it); Hit(it, "", 0, 0, 0, false, m) }.sortedByDescending { it.time }.take(8)
        return all.mapNotNull { t ->
            val (text, m) = textOf(t)
            val inTitle = t.title.contains(q, ignoreCase = true)
            var count = 0; var first = -1; var from = 0
            while (true) { val i = text.indexOf(q, from, ignoreCase = true); if (i < 0) break; if (first < 0) first = i; count++; from = i + q.length }
            if (!inTitle && count == 0) return@mapNotNull null
            if (first < 0) Hit(t, text.take(110), 0, 0, 0, true, m)
            else {
                val start = (first - 42).coerceAtLeast(0).let { s0 -> text.lastIndexOf(' ', s0).takeIf { it in (s0 - 12)..s0 }?.plus(1) ?: s0 }
                val end = (first + q.length + 80).coerceAtMost(text.length)
                val lead = if (start > 0) "…" else ""
                Hit(t, lead + text.substring(start, end) + if (end < text.length) "…" else "", first - start + lead.length, q.length, count, inTitle, m)
            }
        }.sortedWith(compareByDescending<Hit> { it.inTitle }.thenByDescending { it.time })
    }

    private fun readSessionMessages(sessionId: String, cwd: File): JSONArray? {
        val file = sessionFile(sessionId, cwd) ?: return null
        val parent = HashMap<String, String?>()
        val messages = HashMap<String, JSONObject>()
        var leaf: String? = null
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val e = runCatching { JSONObject(line) }.getOrNull() ?: continue
                val id = e.optString("id").ifBlank { null } ?: continue
                parent[id] = e.optString("parentId").ifBlank { null }
                leaf = id
                if (e.optString("type") == "message") e.optJSONObject("message")?.let { m ->
                    if (m.optString("role") in setOf("user", "assistant", "toolResult")) messages[id] = m
                }
            }
        }
        val path = ArrayList<JSONObject>()
        var cur = leaf
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) { messages[cur]?.let { path += it }; cur = parent[cur] }
        return JSONArray().apply { path.asReversed().forEach { put(it) } }
    }

    private fun addUser(ui: TaskUi, text: String) {
        ui.items.add(Item.User(ui.key("u"), text))
        ui.echoes.addLast(text)
    }

    // ----------------------------------------------------------------- events

    override fun onTaskEvent(task: TaskManager.Task, record: JSONObject?, log: String?) {
        if (task.id in deleted || TaskManager.tasks.none { it.id == task.id }) return
        preread.remove(task.id)   // its history moved on: read it fresh when opened
        val ui = ui(task)
        if (task.status == Status.NEEDS_INPUT && ui.status != Status.NEEDS_INPUT) Haptics.finished(ok = true)
        if (task.status == Status.NEEDS_INPUT && ui.status != Status.NEEDS_INPUT &&
            !watching(task.id)) io.github.pimobile.runtime.Notifier.show(app, ui,
                "Task #${task.number} needs you", "Saturn is waiting for your answer: ${ui.title}")
        ui.status = task.status
        ui.title = task.title
        ui.archived = task.archived
        log?.let { io.github.pimobile.store.Store.packageInError(it) }?.let { brokenPackage = it }
        if (ui.status == Status.FAILED && log != null && ui.lastError == null && log.isNotBlank()) {
            ui.lastError = log.take(300)
        }
        record?.let { reduce(ui, it) }
    }

    /**
     * A new task is named after Saturn's first words back, not your prompt: once its first reply
     * lands, the title becomes that reply's first sentence (unless you've renamed the task).
     */
    private fun agentTitle(ui: TaskUi, text: String) {
        if (text.isBlank()) return
        val users = ui.items.filterIsInstance<Item.User>()
        if (users.size != 1 || ui.items.count { it is Item.Assistant && it.text.isNotBlank() } != 1) return
        val prompt = users[0].text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("[Attached image:") && !it.startsWith("[Attached file:") }
        if (prompt == null || ui.task.title != prompt.take(80)) return
        val title = headline(text) ?: return
        TaskManager.rename(ui.task, title); ui.title = title
    }

    /** The first sentence of [md] as plain text, short enough for a card title. */
    internal fun headline(md: String): String? {
        val line = md.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace(Regex("[*_`]"), "").replace(Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1").trim() }
            .firstOrNull { it.isNotBlank() } ?: return null
        val end = Regex("[.!?](\\s|$)").findAll(line).map { it.range.first }.firstOrNull { it >= 8 }
        var s = if (end != null && end <= 90) line.substring(0, end + 1) else line
        if (s.length > 72) s = s.take(72).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ') + "…"
        return s.removeSuffix(".").trim().ifBlank { null }
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
            "agent_start" -> { ui.runStartedAt = System.currentTimeMillis(); ui.lastError = null; ui.ended = null }
            TaskManager.FORCE_STOPPED -> {
                ui.runStartedAt?.let { ui.workMs += System.currentTimeMillis() - it }
                ui.ended = "stopped"
                ui.stopping = false; ui.runStartedAt = null; ui.browsing = false; ui.current?.streaming = false; ui.current = null
                ui.items.add(Item.Notice(ui.key("n"), "Stopped. Saturn ended the run and everything it had started.", false))
            }
            "agent_settled" -> {
                // A routine with nothing new to say goes straight to the archive, without a notification.
                val quiet = runCatching { io.github.pimobile.runtime.Scheduler.settled(ui) }.getOrDefault(false)
                if (quiet) setArchived(ui, true, undoable = false)
                else { Haptics.finished(ok = ui.lastError == null && !ui.stopping); io.github.pimobile.runtime.Notifier.settled(app, ui) }
                ui.lastRunAt = System.currentTimeMillis()
                ui.runStartedAt?.let { ui.workMs += ui.lastRunAt!! - it }
                if (ui.ended == null) ui.ended = when { ui.stopping -> "stopped"; ui.lastError != null -> "failed"; else -> "ok" }
                ui.stopping = false; ui.runStartedAt = null; ui.browsing = false; ui.current?.streaming = false; ui.current = null }
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
                    else agentTitle(ui, cur.text)
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
            "tool_execution_update" -> tool(ui, r)?.let {
                val p = r.optJSONObject("partialResult")
                it.output = resultText(p)
                p?.optJSONObject("details")?.let { d -> it.details = d }
            }
            "tool_execution_end" -> tool(ui, r)?.let {
                it.output = resultText(r.optJSONObject("result"))
                r.optJSONObject("result")?.optJSONObject("details")?.let { d -> it.details = d }
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
                val retry = retryStep[ui.task.id]
                if (retry != null && retry.startsWith("T:")) retryStep[ui.task.id] = "S:" + retry.drop(2)
                else e.optJSONObject("result")?.optString("editorText")?.takeIf { it.isNotBlank() }?.let { ui.prefill = it }
                TaskManager.command(ui.task, JSONObject().put("type", "get_messages"))
            }
            "reloaded" -> { commands = emptyList(); TaskManager.command(ui.task, JSONObject().put("type", "get_commands")) }
            "error" -> { retryStep.remove(ui.task.id); ui.items.add(Item.Notice(ui.key("n"), "${e.optString("scope")}: ${e.optString("message")}", true)) }
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
                if (!watching(ui.task.id)) io.github.pimobile.runtime.Notifier.ask(app, ui, d)
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
            // A task that can't use the default (e.g. its provider was signed out) just keeps its own model.
            if (r.optString("id").startsWith("align")) return
            retryStep.remove(ui.task.id)
            ui.items.add(Item.Notice(ui.key("n"), "${r.optString("command")}: ${r.optString("error")}", true))
            return
        }
        val data = r.optJSONObject("data")
        ui.responses[r.optString("command")] = data ?: JSONObject()
        when (r.optString("command")) {
            // Extension commands consumed by pi never echo a user message.
            "prompt" -> if (data?.optString("disposition") == "handled" && !r.optString("id").startsWith("app-")) ui.echoes.removeLastOrNull()
            // set_model answers with the model itself.
            "set_model" -> data?.optInt("contextWindow", 0)?.takeIf { it > 0 }?.let { ui.contextWindow = it }
            "get_state" -> {
                data?.optJSONObject("model")?.optInt("contextWindow", 0)?.takeIf { it > 0 }?.let { ui.contextWindow = it }
                data?.optString("thinkingLevel")?.takeIf { it.isNotBlank() }?.let { ui.thinkingLevel = it }
                data?.optString("sessionId")?.let { TaskManager.setSessionId(ui.task, it) }
                // The header shows the app-wide default; tasks follow it rather than redefining it.
                if (model == null) data?.optJSONObject("model")?.let { m ->
                    if (m.optString("id") != "unknown") model = "${m.optString("provider")}/${m.optString("id")}"
                }
                data?.let { align(ui, it) }
            }
            // These switch pi to another session: follow it and reload the transcript.
            "fork", "clone", "new_session", "switch_session" -> if (data?.optBoolean("cancelled") != true) {
                if (r.optString("command") == "fork") data?.optString("text")?.takeIf { it.isNotBlank() }?.let { ui.prefill = it }
                if (r.optString("command") == "new_session") { ui.items.clear(); ui.cost = 0.0; ui.contextTokens = 0 }
                TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
                TaskManager.command(ui.task, JSONObject().put("type", "get_messages"))
            }
            "set_thinking_level" -> if (!r.optString("id").startsWith("align")) TaskManager.command(ui.task, JSONObject().put("type", "get_state"))
            "set_model" -> if (!r.optString("id").startsWith("align")) data?.let { model = "${it.optString("provider")}/${it.optString("id")}" }
            "get_commands" -> data?.optJSONArray("commands")?.let { a ->
                commands = (0 until a.length()).map { a.getJSONObject(it) }
                    .filterNot { it.optString("name").startsWith("pimobile-") } // the app's own bridge commands
                    .map { Command(it.optString("name"), it.optString("description"), it.optString("source")) }
            }
            "get_available_models" -> data?.optJSONArray("models")?.let { a ->
                models = (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("provider") to it.optString("id") }
            }
            "get_messages" -> {
                data?.optJSONArray("messages")?.let { rebuild(ui, it) }
                retryStep[ui.task.id]?.takeIf { it.startsWith("S:") }?.let { retryStep.remove(ui.task.id); send(ui, it.drop(2)) }
            }
            "get_fork_messages" -> if (retryStep[ui.task.id] == "F") {
                val msgs = data?.optJSONArray("messages")
                val last = msgs?.optJSONObject(msgs.length() - 1)
                if (last == null) { retryStep.remove(ui.task.id); retryWith.remove(ui.task.id); ui.items.add(Item.Notice(ui.key("n"), "Nothing to retry yet", false)) }
                else {
                    retryStep[ui.task.id] = "T:" + (retryWith.remove(ui.task.id) ?: last.optString("text"))
                    appCommand(ui, "tree", JSONObject().put("entryId", last.optString("entryId")))
                }
            }
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
                    it.details = m.optJSONObject("details")
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
