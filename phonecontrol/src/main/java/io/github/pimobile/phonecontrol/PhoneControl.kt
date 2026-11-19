package io.github.pimobile.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone_* MCP tools: read the screen as a numbered list of elements, then
 * tap / type / swipe / open apps. Actions return the new screen.
 *
 * Guard rails that no client can switch off:
 *  - nothing works while the phone is locked;
 *  - this app and Android's permission / installer screens are off-limits;
 *  - no typing into password fields;
 *  - taps on things like Send / Pay / Delete / Install wait for the user's Allow;
 *  - the on-screen Stop button blocks everything until the user taps Resume in this app.
 */
object PhoneControl {
    class Fail(message: String) : Exception(message)

    /** A tool result: text, plus an optional JPEG image. */
    class Result(val text: String, val jpeg: ByteArray? = null)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("phone", Context.MODE_PRIVATE) }

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    /** The user's pause switch in this app (the Accessibility switch is Android's). */
    var paused by mutableStateOf(false)
        private set
    fun pause(v: Boolean) { paused = v; prefs.edit().putBoolean("paused", v).apply() }
    fun loadPrefs() { paused = prefs.getBoolean("paused", false) }

    /** Set by the on-screen Stop button; cleared by Resume in the app. */
    var stopped by mutableStateOf(false)

    /** Recent actions, newest first, shown in the app. */
    class LogEntry(val time: Long, val tool: String, val detail: String, val ok: Boolean)
    val log = mutableStateListOf<LogEntry>()
    private fun record(tool: String, detail: String, ok: Boolean) {
        val e = LogEntry(System.currentTimeMillis(), tool, detail.take(120), ok)
        android.os.Handler(android.os.Looper.getMainLooper()).post { log.add(0, e); while (log.size > 30) log.removeAt(log.lastIndex) }
    }

    private val BLOCKED = setOf(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
    )
    private val RISKY = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|uninstall|install|transfer|confirm|post|publish|" +
            "submit|book|subscribe|call|share|sign out|log ?out|reset|erase|format|accept|approve|unfollow|block|report)\\b",
        RegexOption.IGNORE_CASE)

    /** Elements from the last screen read, by number. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private val lock = Any()

    // ------------------------------------------------------------------ tools

    private fun schema(props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
        JSONObject().put("type", "object").put("properties", props).apply { if (required.isNotEmpty()) put("required", JSONArray(required)) }
    private fun p(type: String, desc: String, enum: List<String>? = null) =
        JSONObject().put("type", type).put("description", desc).apply { enum?.let { put("enum", JSONArray(it)) } }

    /** MCP tools/list. */
    val tools: JSONArray get() = JSONArray(listOf(
        tool("phone_screen", "Read what's on screen: the app in front and a numbered list of its elements " +
            "(buttons, fields, text, lists) with positions. Start here; numbers change after every action.", schema()),
        tool("phone_tap", "Tap (or long-press) an element by its number from the latest screen, by its text, or at x,y. " +
            "Taps on things like Send, Pay or Delete wait for the user to Allow. Returns the new screen.", schema(JSONObject()
            .put("element", p("integer", "Element number from the latest screen"))
            .put("text", p("string", "Visible text or label to tap, if no element number"))
            .put("x", p("number", "Screen x in pixels")).put("y", p("number", "Screen y in pixels"))
            .put("long", p("boolean", "Long-press instead of tap")))),
        tool("phone_type", "Set the text of a text field (element number, or the focused field). " +
            "Refuses password fields. Returns the new screen.", schema(JSONObject()
            .put("text", p("string", "Text to put in the field"))
            .put("element", p("integer", "Field's element number; omit for the focused field")), listOf("text"))),
        tool("phone_enter", "Press the keyboard's enter/search/send key in the focused field " +
            "(asks the user first unless it's a search or address box). Returns the new screen.", schema()),
        tool("phone_swipe", "Swipe the screen: a direction (up = scroll content down the page) or exact coordinates. Returns the new screen.",
            schema(JSONObject()
                .put("direction", p("string", "Direction", listOf("up", "down", "left", "right")))
                .put("x1", p("number", "Start x")).put("y1", p("number", "Start y"))
                .put("x2", p("number", "End x")).put("y2", p("number", "End y"))
                .put("duration_ms", p("integer", "Swipe duration, default 300")))),
        tool("phone_scroll", "Scroll a list element forward (down) or back (up). Returns the new screen.", schema(JSONObject()
            .put("element", p("integer", "A list (or anything inside it) from the latest screen"))
            .put("direction", p("string", "Direction", listOf("down", "up"))), listOf("element"))),
        tool("phone_key", "Press a system button. Returns the new screen.", schema(JSONObject()
            .put("key", p("string", "Which button", listOf("back", "home", "recents", "notifications", "quick_settings"))), listOf("key"))),
        tool("phone_open_app", "Open an installed app by name or package (e.g. \"Maps\", \"com.whatsapp\"). Returns its screen.",
            schema(JSONObject().put("app", p("string", "App name or package")), listOf("app"))),
        tool("phone_list_apps", "List installed apps that can be opened (name and package).", schema()),
        tool("phone_wait", "Wait for the screen to change (loading etc.), then return it.",
            schema(JSONObject().put("ms", p("integer", "Milliseconds to wait, 100-15000, default 1000")))),
        tool("phone_screenshot", "Take a screenshot (half size) when the element list isn't enough. " +
            "Coordinates in the image ×2 = screen coordinates for phone_tap x,y.", schema()),
    ))

    private fun tool(name: String, desc: String, input: JSONObject) =
        JSONObject().put("name", name).put("description", desc).put("inputSchema", input)

    /** MCP tools/call. Throws [Fail] with a message meant for the agent. */
    fun call(name: String, a: JSONObject): Result = synchronized(lock) {
        try {
            val s = PhoneControlService.instance
                ?: throw Fail("Phone Control's accessibility service is off. Ask the user to turn on \"Phone Control (MCP)\" in Android Settings → Accessibility.")
            if (paused) throw Fail("The user has paused Phone Control. Ask them to resume it in the Phone Control app.")
            if (stopped) throw Fail("The user pressed Stop. Don't use the phone again unless the user asks you to; they can tap Resume in the Phone Control app.")
            if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                throw Fail("The phone is locked. Ask the user to unlock it; Phone Control never works on the lock screen.")
            s.showPill()
            val r = when (name) {
                "phone_screen" -> Result(screen(s))
                "phone_tap" -> { tap(s, a); Result(settle(s)) }
                "phone_type" -> { type(s, a); Result(settle(s)) }
                "phone_enter" -> { enter(s); Result(settle(s)) }
                "phone_swipe" -> { swipe(s, a); Result(settle(s)) }
                "phone_scroll" -> { scroll(s, a); Result(settle(s)) }
                "phone_key" -> { key(s, a.optString("key")); Result(settle(s)) }
                "phone_open_app" -> { open(s, a.optString("app")); Result(settle(s, 1500)) }
                "phone_list_apps" -> Result(launchable().joinToString("\n") { (l, pkg) -> "$l  ($pkg)" })
                "phone_wait" -> { Thread.sleep(a.optLong("ms", 1000).coerceIn(100, 15_000)); Result(screen(s)) }
                "phone_screenshot" -> screenshot(s)
                else -> throw Fail("unknown tool $name")
            }
            record(name, summary(name, a), true)
            r
        } catch (e: Fail) {
            record(name, e.message.orEmpty(), false); throw e
        }
    }

    private fun summary(name: String, a: JSONObject) = when (name) {
        "phone_tap" -> a.optString("text").ifBlank { if (a.has("element")) "element ${a.optInt("element")}" else "${a.optInt("x")},${a.optInt("y")}" }
        "phone_type" -> "\"" + a.optString("text").take(40) + "\""
        "phone_open_app" -> a.optString("app")
        "phone_key" -> a.optString("key")
        "phone_swipe" -> a.optString("direction").ifBlank { "custom" }
        else -> ""
    }

    // ----------------------------------------------------------------- screen

    private fun root(s: PhoneControlService): AccessibilityNodeInfo {
        repeat(10) { s.rootInActiveWindow?.let { return it }; Thread.sleep(150) }
        throw Fail("Couldn't read the screen")
    }

    private fun guard(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString() ?: return
        if (pkg == app.packageName) throw Fail("The Phone Control app is on screen. It doesn't control itself — use phone_open_app or phone_key home first.")
        if (pkg in BLOCKED) throw Fail("An Android permission or install screen is open. Only the user may answer those — ask them to.")
    }

    private fun appLabel(pkg: String?): String = pkg?.let {
        runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
    } ?: pkg ?: "?"

    private fun text(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "••••"
        val t = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return t?.replace('\n', ' ')?.trim().orEmpty()
    }

    /** A label for a clickable container: its own text or its children's. */
    private fun label(n: AccessibilityNodeInfo): String {
        text(n).takeIf { it.isNotEmpty() }?.let { return it }
        val parts = mutableListOf<String>()
        fun walk(c: AccessibilityNodeInfo, depth: Int) {
            if (parts.size >= 3 || depth > 4) return
            text(c).takeIf { it.isNotEmpty() }?.let { parts += it }
            for (i in 0 until c.childCount) c.getChild(i)?.let { walk(it, depth + 1) }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, 1) }
        return parts.joinToString(" · ")
    }

    private fun screen(s: PhoneControlService): String {
        val root = root(s)
        val pkg = root.packageName?.toString()
        val list = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        val dm = app.resources.displayMetrics
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val interactive = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
            val own = text(n)
            if ((interactive || own.isNotEmpty()) && list.size < 250) {
                list += n
                val r = Rect().also { n.getBoundsInScreen(it) }
                val kind = when {
                    n.isEditable -> "field"; n.isCheckable -> if (n.isChecked) "on" else "off"
                    n.isScrollable -> "list"; n.isClickable || n.isLongClickable -> "button"; else -> "text"
                }
                val shown = (if (interactive) label(n) else own).take(90)
                val hint = if (n.isEditable) n.hintText?.toString()?.takeIf { it.isNotBlank() && it != shown }?.let { " hint=\"$it\"" } ?: "" else ""
                val flags = listOfNotNull("focused".takeIf { n.isFocused && n.isEditable }, "disabled".takeIf { !n.isEnabled },
                    "selected".takeIf { n.isSelected }, "password".takeIf { n.isPassword }).joinToString(",")
                lines += "[${list.size}] $kind \"$shown\"$hint" + (if (flags.isNotEmpty()) " ($flags)" else "") + " @${r.centerX()},${r.centerY()}"
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        nodes = list
        val keyboard = s.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val head = "app: ${appLabel(pkg)} ($pkg) · screen ${dm.widthPixels}x${dm.heightPixels}" + if (keyboard) " · keyboard open" else ""
        val note = when (pkg) {
            app.packageName -> "\n(The Phone Control app is on screen; open another app to act.)"
            in BLOCKED -> "\n(Android permission/install screen: only the user may answer it.)"
            else -> ""
        }
        return head + note + "\n" + lines.joinToString("\n").ifEmpty { "(nothing readable — try phone_screenshot)" }
    }

    /** Waits until the screen stops changing, then lists it. */
    private fun settle(s: PhoneControlService, minMs: Long = 400): String {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(minMs)
        while (SystemClock.uptimeMillis() - start < 4000 && SystemClock.uptimeMillis() - s.lastEvent < 350) Thread.sleep(100)
        return screen(s)
    }

    // ---------------------------------------------------------------- actions

    private fun node(i: Int): AccessibilityNodeInfo {
        val n = nodes.getOrNull(i - 1) ?: throw Fail("no element [$i] — call phone_screen again")
        if (!n.refresh() || !n.isVisibleToUser) throw Fail("element [$i] is gone — the screen changed; call phone_screen again")
        return n
    }

    private fun center(n: AccessibilityNodeInfo) = Rect().also { n.getBoundsInScreen(it) }.let { it.exactCenterX() to it.exactCenterY() }

    /** The deepest visible element under a point, for naming what a coordinate tap hits. */
    private fun at(root: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.contains(x.toInt(), y.toInt())) return
            if (n.isClickable || text(n).isNotEmpty() || best == null) best = n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return best
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        repeat(6) { if (c == null) return null; if (c!!.isClickable) return c; c = c!!.parent }
        return null
    }

    private fun checkRisky(s: PhoneControlService, name: String, verb: String) {
        if (!RISKY.containsMatchIn(name)) return
        val pkg = s.rootInActiveWindow?.packageName?.toString()
        if (!s.confirm("$verb “${name.take(60)}”", "in ${appLabel(pkg)}"))
            throw Fail("The user did not allow: $verb \"$name\". Don't retry it; ask the user what they want instead.")
    }

    private fun tap(s: PhoneControlService, a: JSONObject) {
        val root = root(s).also(::guard)
        val long = a.optBoolean("long")
        val verb = if (long) "long-press" else "tap"
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        fun pressNode(n: AccessibilityNodeInfo) {
            checkRisky(s, label(n), verb)
            if (clickableSelfOrParent(n)?.performAction(action) != true) center(n).let { (x, y) -> press(s, x, y, long) }
        }
        when {
            a.has("element") -> pressNode(node(a.getInt("element")))
            a.has("x") && a.has("y") -> {
                val (x, y) = a.getDouble("x").toFloat() to a.getDouble("y").toFloat()
                checkRisky(s, at(root, x, y)?.let(::label).orEmpty(), verb)
                press(s, x, y, long)
            }
            a.optString("text").isNotBlank() -> {
                val want = a.getString("text").trim().lowercase()
                screen(s) // fresh numbering
                pressNode(nodes.firstOrNull { label(it).lowercase() == want } ?: nodes.firstOrNull { label(it).lowercase().contains(want) }
                    ?: throw Fail("nothing on screen matches \"$want\""))
            }
            else -> throw Fail("give element, text, or x and y")
        }
    }

    private fun press(s: PhoneControlService, x: Float, y: Float, long: Boolean) {
        if (!s.gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)) throw Fail("the tap didn't go through")
    }

    private fun type(s: PhoneControlService, a: JSONObject) {
        val root = root(s).also(::guard)
        val n = if (a.has("element")) node(a.getInt("element"))
            else root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw Fail("no text field is focused — pass its element number")
        if (n.isPassword) throw Fail("That's a password field. Phone Control never types passwords — ask the user to enter it themselves.")
        if (!n.isEditable) throw Fail("element is not a text field")
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, a.optString("text")) })
        if (!ok) throw Fail("the app didn't accept the text")
    }

    private fun enter(s: PhoneControlService) {
        val root = root(s).also(::guard)
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw Fail("no text field is focused")
        val what = listOfNotNull(f.viewIdResourceName, f.hintText?.toString(), f.text?.toString()).joinToString(" ")
        // Enter in a search or address box is harmless; elsewhere it may send a message.
        if (!Regex("search|url|address|query|find", RegexOption.IGNORE_CASE).containsMatchIn(what)) checkRisky(s, "send", "press Enter (may send)")
        if (!f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw Fail("the app didn't take Enter")
    }

    private fun swipe(s: PhoneControlService, a: JSONObject) {
        guard(root(s))
        val dm = app.resources.displayMetrics
        val (w, h) = dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        val path = Path()
        when {
            a.has("x1") && a.has("y1") && a.has("x2") && a.has("y2") -> {
                path.moveTo(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat()); path.lineTo(a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat())
            }
            a.optString("direction") == "up" -> { path.moveTo(w / 2, h * 0.72f); path.lineTo(w / 2, h * 0.28f) }
            a.optString("direction") == "down" -> { path.moveTo(w / 2, h * 0.28f); path.lineTo(w / 2, h * 0.72f) }
            a.optString("direction") == "left" -> { path.moveTo(w * 0.85f, h / 2); path.lineTo(w * 0.15f, h / 2) }
            a.optString("direction") == "right" -> { path.moveTo(w * 0.15f, h / 2); path.lineTo(w * 0.85f, h / 2) }
            else -> throw Fail("give direction (up/down/left/right) or x1,y1,x2,y2")
        }
        if (!s.gesture(path, a.optLong("duration_ms", 300).coerceIn(50, 3000))) throw Fail("the swipe didn't go through")
    }

    private fun scroll(s: PhoneControlService, a: JSONObject) {
        guard(root(s))
        val forward = a.optString("direction", "down") != "up"
        var c: AccessibilityNodeInfo? = node(a.optInt("element"))
        while (c != null && !c.isScrollable) c = c.parent
        val ok = c?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        if (!ok) throw Fail("that element can't scroll ${if (forward) "down" else "up"} (any further)")
    }

    private fun key(s: PhoneControlService, k: String) {
        val action = when (k) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK; "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS; "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw Fail("key must be back, home, recents, notifications or quick_settings")
        }
        if (!s.performGlobalAction(action)) throw Fail("Android refused the action")
    }

    private fun launchable() = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(app.packageManager).toString() to it.activityInfo.packageName }
        .filter { it.second != app.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

    private fun open(s: PhoneControlService, query: String) {
        if (query.isBlank()) throw Fail("give the app's name or package")
        val q = query.trim().lowercase()
        val all = launchable()
        val pkg = all.firstOrNull { it.second.lowercase() == q || it.first.lowercase() == q }?.second
            ?: all.firstOrNull { it.first.lowercase().startsWith(q) }?.second
            ?: all.firstOrNull { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }?.second
            ?: throw Fail("no app matches \"$query\" — see phone_list_apps")
        if (pkg in BLOCKED) throw Fail("that app is off-limits")
        val i = app.packageManager.getLaunchIntentForPackage(pkg) ?: throw Fail("$pkg can't be opened")
        // A running accessibility service may start activities from the background.
        s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun screenshot(s: PhoneControlService): Result {
        guard(root(s))
        val latch = CountDownLatch(1)
        var error: String? = null
        var jpeg: ByteArray? = null
        s.takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace) ?: error("no image")
                    val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    // Half size keeps it readable while costing the model far fewer tokens.
                    val scaled = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                    jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                }.onFailure { error = it.message }
                latch.countDown()
            }
            override fun onFailure(code: Int) {
                error = if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "this app blocks screenshots" else "error $code"
                latch.countDown()
            }
        })
        latch.await(10, TimeUnit.SECONDS)
        error?.let { throw Fail("Screenshot failed: $it") }
        return Result("Screenshot (half size: multiply coordinates by 2 for phone_tap x,y).", jpeg ?: throw Fail("Screenshot timed out"))
    }
}
