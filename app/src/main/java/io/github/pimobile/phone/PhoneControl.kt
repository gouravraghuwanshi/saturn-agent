package io.github.pimobile.phone

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `saturn-phone` commands: read the screen as a numbered list of elements,
 * then tap / type / swipe / open apps. Every action returns the new screen.
 *
 * Guard rails (not configurable by the agent):
 *  - nothing works while the phone is locked;
 *  - Saturn itself and Android's permission / installer screens are off-limits;
 *  - no typing into password fields;
 *  - taps on things like Send / Pay / Delete / Install wait for the user's OK;
 *  - the user's Stop button blocks everything until they send Saturn a new message.
 */
object PhoneControl {
    class Fail(message: String) : Exception(message)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("phone", Context.MODE_PRIVATE) }

    /** Saturn's own switch (the system Accessibility switch must be on as well). */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) { prefs.edit().putBoolean("enabled", v).apply(); if (!v) PhoneControlService.instance?.removePill() }

    @Volatile private var stopped = false

    fun init(context: Context) { app = context.applicationContext }

    val serviceOn get() = PhoneControlService.instance != null

    /** The Stop pill. */
    fun stop() { stopped = true }
    /** The user wrote to Saturn again: phone control may continue. */
    fun resume() { stopped = false }

    private val BLOCKED = setOf(
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
    )
    private val RISKY = Regex(
        "\\b(send|pay|buy|purchase|order|checkout|check out|delete|remove|uninstall|install|transfer|confirm|post|publish|" +
            "submit|book|subscribe|call|share|sign out|log ?out|reset|erase|format|accept|approve|unfollow|block|report)\\b",
        RegexOption.IGNORE_CASE)

    /** Elements from the last `screen`, by number. */
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private val lock = Any()

    fun run(cmd: String, argv: List<String>): String = synchronized(lock) {
        val artifacts = argv.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)
        val a = argv.drop(2)
        if (cmd == "help" || cmd.isBlank()) return USAGE
        if (!enabled) throw Fail("Phone control is off. The user can turn it on in Saturn → Settings → Phone control (\"Let Saturn use your apps\").")
        val s = PhoneControlService.instance
            ?: throw Fail("Saturn's accessibility service isn't running. The user needs to turn on Saturn in Android Settings → Accessibility (Saturn → Settings → Phone control explains how).")
        if (stopped) throw Fail("The user pressed Stop. Don't use the phone again until the user asks you to.")
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) throw Fail("The phone is locked. Ask the user to unlock it; Saturn never works on the lock screen.")
        s.showPill()
        return when (cmd) {
            "screen" -> screen(s)
            "tap", "click" -> { tap(s, a, long = false); settleAndScreen(s) }
            "longpress", "long-press" -> { tap(s, a, long = true); settleAndScreen(s) }
            "type" -> { type(s, a); settleAndScreen(s) }
            "enter" -> { enter(s); settleAndScreen(s) }
            "swipe" -> { swipe(s, a); settleAndScreen(s) }
            "scroll" -> { scroll(s, a); settleAndScreen(s) }
            "back" -> { global(s, AccessibilityService.GLOBAL_ACTION_BACK); settleAndScreen(s) }
            "home" -> { global(s, AccessibilityService.GLOBAL_ACTION_HOME); settleAndScreen(s) }
            "recents" -> { global(s, AccessibilityService.GLOBAL_ACTION_RECENTS); settleAndScreen(s) }
            "notifications" -> { global(s, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); settleAndScreen(s) }
            "quicksettings" -> { global(s, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); settleAndScreen(s) }
            "apps" -> apps()
            "open" -> { open(s, a.joinToString(" ")); settleAndScreen(s, 1500) }
            "wait" -> { Thread.sleep((a.firstOrNull()?.toLongOrNull() ?: 1000).coerceIn(100, 15_000)); screen(s) }
            "screenshot" -> screenshot(s, a.firstOrNull()?.let(::File) ?: File(app.cacheDir, "screens/screen-${SystemClock.uptimeMillis()}.jpg"))
            else -> throw Fail("unknown command \"$cmd\"\n$USAGE")
        }
    }

    const val USAGE = """usage: saturn-phone <command>
  screen                       list what's on screen, numbered
  tap <n> | tap <x> <y> | tap "<text>"
  longpress <n> | longpress <x> <y>
  type [<n>] <text>            set the text of field n (or the focused field)
  enter                        press the keyboard's enter / search / send key
  swipe up|down|left|right     or: swipe <x1> <y1> <x2> <y2> [ms]
  scroll <n> up|down           scroll a list
  back | home | recents | notifications | quicksettings
  apps                         list installed apps
  open <app name or package>
  wait [ms]
  screenshot [file.jpg]        save a screenshot (to read it as an image)
Actions print the new screen."""

    // ---------------------------------------------------------------- screen

    private fun root(s: PhoneControlService): AccessibilityNodeInfo {
        repeat(10) { s.rootInActiveWindow?.let { return it }; Thread.sleep(150) }
        throw Fail("Couldn't read the screen")
    }

    private fun guard(root: AccessibilityNodeInfo) {
        val pkg = root.packageName?.toString() ?: return
        if (pkg == app.packageName) throw Fail("Saturn is on screen. Saturn doesn't control itself — use `saturn-phone open <app>` or `home` first.")
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
                val hint = if (n.isEditable && Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.takeIf { it.isNotBlank() && it != shown }?.let { " hint=\"$it\"" } ?: "" else ""
                val flags = listOfNotNull("focused".takeIf { n.isFocused && n.isEditable }, "disabled".takeIf { !n.isEnabled },
                    "selected".takeIf { n.isSelected }, "password".takeIf { n.isPassword }).joinToString(",")
                lines += "[${list.size}] $kind \"$shown\"$hint" + (if (flags.isNotEmpty()) " ($flags)" else "") + " @${r.centerX()},${r.centerY()}"
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        nodes = list
        val keyboard = s.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val head = "app: ${appLabel(pkg)} ($pkg) · screen ${dm.widthPixels}x${dm.heightPixels}" + if (keyboard) " · keyboard open" else ""
        val note = when (pkg) {
            app.packageName -> "\n(Saturn is on screen; open another app to act.)"
            in BLOCKED -> "\n(Android permission/install screen: only the user may answer it.)"
            else -> ""
        }
        return head + note + "\n" + lines.joinToString("\n").ifEmpty { "(nothing readable — try `saturn-phone screenshot`)" }
    }

    /** Waits until the screen stops changing, then lists it. */
    private fun settleAndScreen(s: PhoneControlService, minMs: Long = 400): String {
        val start = SystemClock.uptimeMillis()
        Thread.sleep(minMs)
        while (SystemClock.uptimeMillis() - start < 4000 && SystemClock.uptimeMillis() - s.lastEvent < 350) Thread.sleep(100)
        return screen(s)
    }

    // --------------------------------------------------------------- actions

    private fun node(arg: String): AccessibilityNodeInfo {
        val i = arg.toIntOrNull() ?: throw Fail("expected an element number from `saturn-phone screen`")
        val n = nodes.getOrNull(i - 1) ?: throw Fail("no element [$i] — run `saturn-phone screen` again")
        if (!n.refresh() || !n.isVisibleToUser) throw Fail("element [$i] is gone — the screen changed; run `saturn-phone screen` again")
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

    private fun tap(s: PhoneControlService, a: List<String>, long: Boolean) {
        val root = root(s).also(::guard)
        val verb = if (long) "long-press" else "tap"
        when {
            a.size >= 2 && a[0].toFloatOrNull() != null && a[1].toFloatOrNull() != null -> {
                val (x, y) = a[0].toFloat() to a[1].toFloat()
                checkRisky(s, at(root, x, y)?.let(::label).orEmpty(), verb)
                press(s, x, y, long)
            }
            a.size == 1 && a[0].toIntOrNull() != null -> {
                val n = node(a[0])
                checkRisky(s, label(n), verb)
                val target = clickableSelfOrParent(n)
                val done = target?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!done) center(n).let { (x, y) -> press(s, x, y, long) }
            }
            a.isNotEmpty() -> {
                val want = a.joinToString(" ").trim('"').lowercase()
                screen(s) // refresh numbering
                val n = nodes.firstOrNull { label(it).lowercase() == want } ?: nodes.firstOrNull { label(it).lowercase().contains(want) }
                    ?: throw Fail("nothing on screen matches \"$want\"")
                checkRisky(s, label(n), verb)
                val target = clickableSelfOrParent(n)
                if (target?.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK) != true)
                    center(n).let { (x, y) -> press(s, x, y, long) }
            }
            else -> throw Fail("usage: saturn-phone tap <n> | <x> <y> | \"text\"")
        }
    }

    private fun press(s: PhoneControlService, x: Float, y: Float, long: Boolean) {
        if (!s.gesture(Path().apply { moveTo(x, y) }, if (long) 700 else 60)) throw Fail("the tap didn't go through")
    }

    private fun type(s: PhoneControlService, a: List<String>) {
        guard(root(s))
        val (n, text) = if (a.size >= 2 && a[0].toIntOrNull() != null) node(a[0]) to a.drop(1).joinToString(" ")
            else (root(s).findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw Fail("no text field is focused — give its number: type <n> <text>")) to a.joinToString(" ")
        if (n.isPassword) throw Fail("That's a password field. Saturn never types passwords — ask the user to enter it themselves.")
        if (!n.isEditable) throw Fail("element is not a text field")
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        if (!ok) throw Fail("the app didn't accept the text")
    }

    private fun enter(s: PhoneControlService) {
        val root = root(s).also(::guard)
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: throw Fail("no text field is focused")
        val what = listOfNotNull(f.viewIdResourceName, if (Build.VERSION.SDK_INT >= 26) f.hintText?.toString() else null, f.text?.toString()).joinToString(" ")
        // Enter in a search or address box is harmless; elsewhere it may send a message.
        if (!Regex("search|url|address|query|find", RegexOption.IGNORE_CASE).containsMatchIn(what)) checkRisky(s, "send", "press Enter (may send)")
        if (Build.VERSION.SDK_INT < 30) throw Fail("enter needs Android 11+")
        if (!f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) throw Fail("the app didn't take Enter")
    }

    private fun swipe(s: PhoneControlService, a: List<String>) {
        guard(root(s))
        val dm = app.resources.displayMetrics
        val (w, h) = dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        val nums = a.mapNotNull { it.toFloatOrNull() }
        val path = Path()
        var ms = 300L
        when {
            nums.size >= 4 -> { path.moveTo(nums[0], nums[1]); path.lineTo(nums[2], nums[3]); ms = nums.getOrNull(4)?.toLong() ?: 300 }
            // "swipe up" moves the content up (finger goes from bottom to top), like scrolling down a page.
            a.firstOrNull() == "up" -> { path.moveTo(w / 2, h * 0.72f); path.lineTo(w / 2, h * 0.28f) }
            a.firstOrNull() == "down" -> { path.moveTo(w / 2, h * 0.28f); path.lineTo(w / 2, h * 0.72f) }
            a.firstOrNull() == "left" -> { path.moveTo(w * 0.85f, h / 2); path.lineTo(w * 0.15f, h / 2) }
            a.firstOrNull() == "right" -> { path.moveTo(w * 0.15f, h / 2); path.lineTo(w * 0.85f, h / 2) }
            else -> throw Fail("usage: saturn-phone swipe up|down|left|right  or  swipe x1 y1 x2 y2 [ms]")
        }
        if (!s.gesture(path, ms.coerceIn(50, 3000))) throw Fail("the swipe didn't go through")
    }

    private fun scroll(s: PhoneControlService, a: List<String>) {
        guard(root(s))
        val n = node(a.getOrNull(0) ?: throw Fail("usage: saturn-phone scroll <n> up|down"))
        val forward = a.getOrNull(1) != "up"
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isScrollable) c = c.parent
        val ok = c?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        if (!ok) throw Fail("that element can't scroll ${if (forward) "down" else "up"} (any further)")
    }

    private fun global(s: PhoneControlService, action: Int) {
        if (!s.performGlobalAction(action)) throw Fail("Android refused the action")
    }

    private fun launchable() = app.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(app.packageManager).toString() to it.activityInfo.packageName }
        .filter { it.second != app.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }

    private fun apps() = launchable().joinToString("\n") { (label, pkg) -> "$label  ($pkg)" }

    private fun open(s: PhoneControlService, query: String) {
        if (query.isBlank()) throw Fail("usage: saturn-phone open <app name or package>")
        val q = query.trim().lowercase()
        val all = launchable()
        val pkg = all.firstOrNull { it.second.lowercase() == q || it.first.lowercase() == q }?.second
            ?: all.firstOrNull { it.first.lowercase().startsWith(q) }?.second
            ?: all.firstOrNull { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }?.second
            ?: throw Fail("no app matches \"$query\" — see `saturn-phone apps`")
        if (pkg in BLOCKED) throw Fail("that app is off-limits")
        val i = app.packageManager.getLaunchIntentForPackage(pkg) ?: throw Fail("$pkg can't be opened")
        // A running accessibility service may start activities from the background.
        s.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun screenshot(s: PhoneControlService, out: File): String {
        if (Build.VERSION.SDK_INT < 30) throw Fail("screenshots need Android 11+")
        guard(root(s))
        out.parentFile?.mkdirs()
        val latch = CountDownLatch(1)
        var error: String? = null
        s.takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace) ?: error("no image")
                    val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    // Half size keeps it readable while costing the model far fewer tokens.
                    val scaled = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
                    out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
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
        return out.path + "\n(image is half-size: multiply coordinates by 2 for tap x y)"
    }
}
