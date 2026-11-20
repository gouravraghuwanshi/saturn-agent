package io.github.pimobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import io.github.pimobile.runtime.TaskManager
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.BlinkCursor
import io.github.pimobile.ui.components.Markdown
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.components.ToolPanel
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import java.util.Locale

/** Transcript rows, with consecutive tool calls merged into one panel. */
private sealed class Block(val key: String) {
    class Single(val item: Item) : Block(item.key)
    class Tools(val tools: List<Item.Tool>) : Block("tools-" + tools.first().key)
}

private fun blocks(items: List<Item>): List<Block> {
    val out = mutableListOf<Block>()
    var run = mutableListOf<Item.Tool>()
    fun flush() { if (run.isNotEmpty()) { out += Block.Tools(run); run = mutableListOf() } }
    for (i in items) if (i is Item.Tool) run += i else { flush(); out += Block.Single(i) }
    flush()
    return out
}

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(t: TaskUi, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
               onOpenTask: (TaskUi) -> Unit = {}, onBack: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<UiStore.Attachment>() }
    var followUp by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("chat") }
    var holdBrowser by rememberSaveable { mutableStateOf(false) } // user took over
    // Once the agent opened a page it stays until the user closes it: runs often end with
    // "now you finish this in the browser" (sign in, pay, solve a captcha…).
    val showBrowserTab = t.browsing || holdBrowser || io.github.pimobile.browser.BrowserManager.pages[t.task.id] != null
    LaunchedEffect(showBrowserTab) { if (!showBrowserTab && tab == "browser") tab = "chat" }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(tab) { focus.clearFocus(); keyboard?.hide() }
    val running = t.status == Status.RUNNING || t.status == Status.STARTING
    val list = rememberLazyListState()
    val blocks by remember(t) { derivedStateOf { blocks(t.items.toList()) } }

    LaunchedEffect(t) { UiStore.open(t) }
    // While this task is on screen: a tick for every tool call Saturn starts, a "no" when one fails.
    LaunchedEffect(t) {
        var seen = -1; var failed = -1
        androidx.compose.runtime.snapshotFlow {
            t.items.count { it is Item.Tool } to t.items.count { it is Item.Tool && it.state == io.github.pimobile.ui.state.ToolState.ERROR }
        }.collect { (n, f) ->
            val live = t.status == Status.RUNNING
            if (seen >= 0 && n > seen && live) io.github.pimobile.ui.state.Haptics.tool()
            if (failed >= 0 && f > failed && live) io.github.pimobile.ui.state.Haptics.toolFailed()
            seen = n; failed = f
        }
    }
    // Follow new output until the user scrolls up; resume when they return to the bottom.
    var follow by remember { mutableStateOf(true) }
    // Saturn and the status show only when you pull the chat down; any push up (or the keyboard) hides them.
    var reveal by remember { mutableStateOf(false) }
    // Pull up past the end and let go: start a new task. [pull] is how far the finger went past the end.
    var pull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val hostView = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pullArm = with(density) { 150.dp.toPx() }
    val slop = with(density) { 6.dp.toPx() }
    val startNewTask = {
        hostView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        UiStore.selectProject(t.task.cwd); UiStore.composeRequest = true; onBack()
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    snapshotFlow { list.isScrollInProgress }.first { !it } // let the fling settle
                    follow = !list.canScrollForward
                }
            }
        }
    }
    val lastText = (t.items.lastOrNull() as? Item.Assistant)?.text?.length ?: 0
    val lastOutput = (t.items.lastOrNull() as? Item.Tool)?.output?.length ?: 0
    LaunchedEffect(blocks.size, lastText, lastOutput, running) {
        // Offset beyond the end is clamped, so this lands at the very bottom.
        if (follow && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2)
    }
    // Whatever makes the content grow (streamed text, tool output, the header folding),
    // keep the bottom in view while following.
    LaunchedEffect(list) {
        snapshotFlow { follow && list.canScrollForward }.collect { if (it && list.layoutInfo.totalItemsCount > 0)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2) }
    }
    // Opening the task, coming back from the terminal, or a history reload: show the latest.
    LaunchedEffect(t.rebuilds, tab) {
        if (tab == "chat" && blocks.isNotEmpty()) { follow = true; list.scrollToItem(blocks.size, Int.MAX_VALUE / 2) }
    }

    // The header's Saturn and its warm light: bright and breathing while Saturn works,
    // a faint ember once it's done, red if it failed. Folds away while you read back or type.
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(imeOpen) { if (imeOpen) reveal = false }
    val collapse by androidx.compose.animation.core.animateFloatAsState(
        if (reveal && !imeOpen && tab == "chat") 0f else 1f, androidx.compose.animation.core.tween(320), label = "collapse")
    val failedNow = t.status == Status.FAILED || (t.lastError != null && !running)
    val glow by androidx.compose.animation.core.animateFloatAsState(when {
        running -> 1f; t.status == Status.NEEDS_INPUT -> 0.85f; failedNow -> 0.5f; else -> 0.28f
    }, androidx.compose.animation.core.tween(1400), label = "glow")
    val breatheAnim = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
    val breatheV by breatheAnim.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(2200), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val breathe = if (running || t.status == Status.NEEDS_INPUT) breatheV else 1f
    var glowAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    var rootAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val ctx = LocalContext.current
    var loginArg by remember { mutableStateOf("") }
    // pi's built-in commands, mapped to RPC, the pimobile-app extension or native screens.
    val builtins = listOf(
        Builtin("model", "Switch model (saved as your default)", args = true) { a ->
            if (a.contains('/')) UiStore.setModel(t, a.substringBefore('/'), a.substringAfter('/')) else sheet = "model" },
        Builtin("thinking", "Set the thinking level (saved as your default)", args = true) { a ->
            if (a.isNotBlank()) UiStore.setThinking(t, a.trim()) else sheet = "thinking" },
        Builtin("effort", "Same as /thinking", args = true) { a ->
            if (a.isNotBlank()) UiStore.setThinking(t, a.trim()) else sheet = "thinking" },
        Builtin("login", "Sign in to a provider (subscriptions, API keys)", args = true) { a -> loginArg = a; sheet = "login" },
        Builtin("logout", "Remove a provider credential") { sheet = "logout" },
        Builtin("new", "Start a fresh session in this task") { UiStore.rpc(t, "new_session") },
        Builtin("resume", "Open another session as a task") { sheet = "resume" },
        Builtin("name", "Rename this task", tag = "app", args = true) { a ->
            if (a.isNotBlank()) { UiStore.rename(t, a); UiStore.rpc(t, "set_session_name", "name" to a) } else sheet = "rename" },
        Builtin("session", "Session info and statistics") { sheet = "session" },
        Builtin("tree", "Navigate the session tree") { sheet = "tree" },
        Builtin("fork", "Branch from an earlier message into a new task") { sheet = "fork" },
        Builtin("clone", "Branch this whole chat into a new task") { UiStore.branch(t) },
        Builtin("compact", "Compact context (optional instructions)", args = true) { a ->
            UiStore.rpc(t, "compact", "customInstructions" to a.ifBlank { null }) },
        Builtin("copy", "Copy the last reply") { t.responses.remove("get_last_assistant_text"); UiStore.rpc(t, "get_last_assistant_text") },
        Builtin("export", "Export the session as HTML and share it") {
            val out = java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.html").apply { parentFile?.mkdirs() }
            t.responses.remove("export_html"); UiStore.rpc(t, "export_html", "outputPath" to out.path) },
        Builtin("reload", "Reload extensions, skills, prompts and context") { UiStore.appCommand(t, "reload"); io.github.pimobile.ui.state.Control.restart() },
        Builtin("settings", "App settings", tag = "app") { sheet = "settings" },
        Builtin("changelog", "What's new in pi") { sheet = "changelog" },
        Builtin("stop", "Stop the current run", tag = "app") { UiStore.stop(t) },
        Builtin("quit", "End this task's pi process", tag = "app") { TaskManager.stop(t.task) },
    )
    val commands = paletteOf(builtins)

    // Results of commands that act outside the transcript.
    t.responses["get_last_assistant_text"]?.let { r ->
        LaunchedEffect(r) {
            val text = r.optString("text").takeIf { it.isNotBlank() && it != "null" }
            if (text != null) ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Saturn", text))
            android.widget.Toast.makeText(ctx, if (text != null) "Copied the last reply" else "No reply to copy yet", android.widget.Toast.LENGTH_SHORT).show()
            t.responses.remove("get_last_assistant_text")
        }
    }
    t.responses["export_html"]?.let { r ->
        LaunchedEffect(r) {
            t.responses.remove("export_html")
            val f = java.io.File(r.optString("path").ifBlank { java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.html").path })
            if (f.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/html").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share session"))
            }
        }
    }
    // /fork and /tree hand an earlier message back for editing, like pi's TUI.
    t.prefill?.let { text ->
        LaunchedEffect(text) { input = TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)); t.prefill = null; tab = "chat" }
    }

    with(shared) {
        Box(
            Modifier.fillMaxSize()
                .sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility, boundsTransform = CardMorph,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))
                .background(Ph.Ground)
                .onGloballyPositioned { rootAt = it.boundsInRoot().topLeft }
                .drawBehind { if (glowAt != androidx.compose.ui.geometry.Offset.Unspecified)
                    saturnGlow(glowAt - rootAt, glow * (1f - collapse), breathe, red = failedNow) }
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(t, tab, showBrowserTab, collapse, onSaturn = { glowAt = it },
                    onModel = { sheet = "model" }, onThinking = { sheet = "thinking" },
                    onTab = { tab = it; if (it != "browser") holdBrowser = false },
                    onBack = { if (tab != "chat") tab = "chat" else onBack() }, onMenu = { sheet = "menu" })
                io.github.pimobile.ui.components.BrokenPackageBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (tab == "browser") {
                    BrowserPane(t, onClose = {
                        holdBrowser = false; tab = "chat"; io.github.pimobile.browser.BrowserManager.close(t.task.id)
                    }) { holdBrowser = true; if (running) UiStore.stop(t) }
                    return@Column
                }
                if (tab == "terminal") {
                    if (running) TerminalBusy { UiStore.stop(t) }
                    else TerminalPane(t)
                    return@Column
                }
                Box(Modifier.weight(1f).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var lastY = down.position.y; var travel = 0f; var anchor: Float? = null
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val dy = c.position.y - lastY; lastY = c.position.y; travel += kotlin.math.abs(dy)
                            if (travel > slop) { if (dy > 1.5f) reveal = true else if (dy < -1.5f) reveal = false }
                            // Measured from where the list hit its end, so dragging back down cancels.
                            if (!list.canScrollForward) {
                                val a = anchor ?: c.position.y.also { anchor = it }
                                pull = (a - c.position.y).coerceAtLeast(0f)
                            } else { anchor = null; pull = 0f }
                            val nowArmed = pull >= pullArm
                            if (nowArmed != armed) {
                                armed = nowArmed
                                hostView.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) {
                                    if (nowArmed) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                    else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                                } else if (nowArmed) android.view.HapticFeedbackConstants.CONTEXT_CLICK else android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        if (armed) startNewTask()
                        armed = false; pull = 0f
                    }
                }) {
                LazyColumn(
                    state = list, modifier = Modifier.fillMaxSize().clipToBounds(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(blocks, key = { _, b -> b.key }) { i, b ->
                        // Saturn's turn starts after each of your messages: label it once.
                        val prev = blocks.getOrNull(i - 1)
                        val isUser = b is Block.Single && b.item is Item.User
                        val turnStart = !isUser && (prev == null || (prev is Block.Single && prev.item is Item.User))
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).padding(top = if (isUser && i > 0) 10.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (turnStart) SaturnLabel()
                            when (b) {
                                is Block.Tools -> ToolsBlock(b.tools)
                                is Block.Single -> Row1(t, b.item)
                            }
                        }
                    }
                    if (running) item(key = "working") { Working(t) }
                }
                // Revealed as the list springs up past its end.
                val hint = with(density) { ((pull - 20.dp.toPx()) / 50.dp.toPx()).coerceIn(0f, 1f) }
                if (hint > 0f) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).graphicsLayer { alpha = hint }
                    .clip(RoundedCornerShape(20.dp)).background(if (armed) Ph.AmberWash else Ph.Surface)
                    .border(1.dp, if (armed) Ph.AmberLine else Ph.Line, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(PhIcons.Plus, if (armed) Ph.Amber else Ph.TextSoft, 15.dp,
                        modifier = Modifier.graphicsLayer { val k = if (armed) 1.25f else 1f; scaleX = k; scaleY = k })
                    Text(if (armed) "Release to start a new task" else "Pull up for a new task",
                        style = Ph.mono.copy(color = if (armed) Ph.Amber else Ph.TextSoft))
                }
                }

                // ---- composer
                Column(Modifier.imePadding().navigationBarsPadding().padding(bottom = 4.dp, top = 6.dp)) {
                    AnimatedVisibility(running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Toggle("steer", !followUp) { followUp = false }
                            Toggle("follow-up", followUp) { followUp = true }
                            Box(Modifier.weight(1f))
                            Text(if (followUp) "sent when Saturn finishes" else "sent after current step", style = Ph.monoSmall)
                        }
                    }
                    val pick = rememberAttachPicker { attachments.addAll(it) }
                    Composer(
                        value = input, onValue = { input = it },
                        placeholder = "Ask Saturn · task #${t.task.number}…",
                        commands = commands,
                        attachments = attachments.toList(), attach = pick, onRemoveAttachment = { attachments.remove(it) },
                        onSend = { text ->
                            input = TextFieldValue("")
                            if (attachments.isNotEmpty() || !dispatchSlash(text, builtins)) {
                                UiStore.send(t, text, followUp, attachments.toList()); attachments.clear()
                            }
                        },
                        extra = {
                            if (running) Row(
                                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Ph.RedWash)
                                    .border(1.dp, Color(0xFF3A2A28), RoundedCornerShape(14.dp))
                                    .clickable(enabled = !t.stopping, onClickLabel = "Stop task") { UiStore.stop(t) }
                                    .padding(horizontal = if (t.stopping) 12.dp else 15.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                if (t.stopping) { PulseDot(Ph.Red, 6); Text("stopping", style = Ph.monoSmall.copy(color = Ph.Red)) }
                                else PhIcon(PhIcons.Stop, Ph.Red, 14.dp, "Stop task")
                            }
                        },
                    )
                    Box(Modifier.size(6.dp))
                }
            }
        }
    }

    when (sheet) {
        "model" -> ModelSheet(t) { sheet = null }
        "rename" -> RenameSheet(t) { sheet = null }
        "settings" -> SettingsSheet { sheet = null }
        "login" -> LoginSheet(initialQuery = loginArg) { sheet = null }
        "logout" -> LoginSheet(logoutMode = true) { sheet = null }
        "thinking" -> ThinkingSheet(t) { sheet = null }
        "session" -> SessionSheet(t) { sheet = null }
        "tree" -> TreeSheet(t) { sheet = null }
        "fork" -> ForkSheet(t) { sheet = null }
        "resume" -> ResumeSheet(onOpen = { other -> if (other !== t) onOpenTask(other) }) { sheet = null }
        "changelog" -> ChangelogSheet { sheet = null }
        "menu" -> {
            fun cmd(name: String) = { builtins.first { it.name == name }.run("") }
            TaskMenuSheet(t, buildList {
                add(MenuAction("Rename", PhIcons.Pencil) { sheet = "rename" })
                add(MenuAction("Model", PhIcons.Cube, hint = io.github.pimobile.ui.state.modelLabel(UiStore.model)?.first) { sheet = "model" })
                add(MenuAction("Session info", PhIcons.Info) { sheet = "session" })
                add(MenuAction("Session tree", PhIcons.Branch) { sheet = "tree" })
                add(MenuAction("Copy last reply", PhIcons.Copy, run = cmd("copy")))
                add(MenuAction("Export & share", PhIcons.Share, hint = "html", run = cmd("export")))
                add(MenuAction("Branch chat", PhIcons.Fork) {
                    if (running) UiStore.branchError = "wait until Saturn finishes, or stop it" else UiStore.branch(t)
                })
                if (running) add(MenuAction("Stop run", PhIcons.Stop) { UiStore.stop(t) })
                add(MenuAction("Delete task", PhIcons.Trash, danger = true, confirm = "Tap again to delete") {
                    onBack(); UiStore.delete(t) })
            }) { if (sheet == "menu") sheet = null }
        }
    }
}

@Composable
private fun Header(t: TaskUi, tab: String, browser: Boolean, collapse: Float, onSaturn: (androidx.compose.ui.geometry.Offset) -> Unit,
                   onModel: () -> Unit, onThinking: () -> Unit,
                   onTab: (String) -> Unit, onBack: () -> Unit, onMenu: () -> Unit) {
    val running = t.status == Status.RUNNING || t.status == Status.STARTING
    val failed = t.status == Status.FAILED || (t.lastError != null && !running)
    val status = when {
        t.status == Status.STARTING -> "starting"; t.status == Status.RUNNING -> "working"; t.status == Status.NEEDS_INPUT -> "needs you"
        failed -> "failed"; t.status == Status.STOPPED -> "paused"; t.items.isEmpty() -> "ready"; else -> "done"
    }
    val tint = when { failed -> Ph.Red; running || t.status == Status.NEEDS_INPUT -> Ph.Amber; status == "done" -> Ph.Green; else -> Ph.Muted }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (tab == "chat") "Back to tasks" else "Back to chat") { onBack() },
                contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(t.title, style = Ph.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Folded: the status moves up here, one line.
                if (collapse > 0.5f) Row(Modifier.padding(top = 2.dp).graphicsLayer { alpha = (collapse - 0.5f) * 2f },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (running || t.status == Status.NEEDS_INPUT) PulseDot(tint, 6)
                    else Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
                    Text("#${t.task.number} · $status · ${t.task.cwd.name}", style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (browser) HeaderIcon(PhIcons.Globe, tab == "browser", if (tab == "browser") "Back to chat" else "Show browser", dot = true) {
                onTab(if (tab == "browser") "chat" else "browser") }
            HeaderIcon(PhIcons.Terminal, tab == "terminal", if (tab == "terminal") "Back to chat" else "Open terminal") {
                onTab(if (tab == "terminal") "chat" else "terminal") }
            HeaderIcon(PhIcons.More, false, "Task options") { onMenu() }
        }
        // Open: a small Saturn in text, turning while it works, and where things stand.
        Box(Modifier.fillMaxWidth().clipToBounds().foldAway(collapse)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp).graphicsLayer { alpha = 1f - collapse },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniSaturn(spinning = running, needsYou = t.status == Status.NEEDS_INPUT,
                    Modifier.width(118.dp).onGloballyPositioned { c -> onSaturn(c.boundsInRoot().center) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running || t.status == Status.NEEDS_INPUT) PulseDot(tint, 7)
                        else Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                        androidx.compose.animation.AnimatedContent(status, label = "status") { st ->
                            Text(st.replaceFirstChar { it.uppercase() }, style = Ph.title.copy(fontSize = 17.sp, color = if (failed) Ph.Red else Ph.Text))
                        }
                    }
                    val time = elapsed(if (running) t.runStartedAt else null)
                    Text(listOfNotNull("#${t.task.number}", t.task.cwd.name, time.takeIf { it.isNotEmpty() },
                        t.tools.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "step" else "steps"}" },
                        t.cost.takeIf { it >= 0.005 }?.let { String.format(Locale.US, "$%.2f", it) }).joinToString(" · "),
                        style = Ph.monoSmall.copy(color = Ph.TextSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // What used to sit under the message box: model and thinking (tap to change), context size.
                    Row(Modifier.offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
                        val tap = Modifier.clip(RoundedCornerShape(8.dp))
                        Text(io.github.pimobile.ui.state.modelLabel(UiStore.model)?.first ?: "model", style = Ph.monoSmall.copy(color = Ph.Amber),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).then(tap).clickable(onClickLabel = "Change model") { onModel() }.padding(horizontal = 6.dp, vertical = 6.dp))
                        t.thinkingLevel?.let {
                            Text("·", style = Ph.monoSmall)
                            Text(it, style = Ph.monoSmall.copy(color = Ph.Amber), modifier = tap.clickable(onClickLabel = "Change thinking level") { onThinking() }
                                .padding(horizontal = 6.dp, vertical = 6.dp))
                        }
                        if (t.contextTokens > 0) Text("· " + String.format(Locale.US, "%.1fk ctx", t.contextTokens / 1000.0), style = Ph.monoSmall,
                            modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Ph.Line.copy(alpha = 0.4f + 0.6f * collapse)))
    }
}

/** Shrinks to nothing as [f] goes 0 → 1, keeping the top. */
private fun Modifier.foldAway(f: Float) = this.then(Modifier.layout { m, c ->
    val p = m.measure(c)
    val h = (p.height * (1f - f)).toInt().coerceAtLeast(0)
    layout(p.width, h) { p.place(0, 0) }
})

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, label: String, dot: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Ph.Selected else Color.Transparent)
        .clickable(onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        PhIcon(icon, if (on) Ph.Amber else Ph.TextSoft, 19.dp, label)
        if (dot && !on) Box(Modifier.align(Alignment.TopEnd).padding(9.dp)) { PulseDot(Ph.Cyan, 5) }
    }
}

/** Saturn's mark and name at the start of each of its turns. */
@Composable
private fun SaturnLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SaturnMark(Modifier.size(18.dp))
        Text("SATURN", style = Ph.label.copy(fontSize = 10.5.sp, color = Ph.Amber))
    }
}

/** A run of tool calls folded into one line ("Ran 2 commands, edited 1 file"); tap for the details. Open while running. */
@Composable
private fun ToolsBlock(tools: List<Item.Tool>) {
    val running = tools.any { it.state == io.github.pimobile.ui.state.ToolState.RUNNING }
    val failed = tools.count { it.state == io.github.pimobile.ui.state.ToolState.ERROR }
    // Live output shows while it runs unless you hide it; afterwards it's folded unless you open it.
    var choice by rememberSaveable(tools.first().key) { mutableStateOf<Boolean?>(null) }
    val open = choice ?: running
    val toggle = Modifier.clickable(onClickLabel = if (open) "Hide steps" else "Show steps") { choice = !open }
    val last = tools.last()
    val target = "${last.name}  ${io.github.pimobile.ui.components.toolTarget(last.name, last.args)}".trim()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.animation.AnimatedContent(running, label = "tools",
            transitionSpec = { fadeIn(androidx.compose.animation.core.tween(300)) togetherWith fadeOut(androidx.compose.animation.core.tween(200)) using
                androidx.compose.animation.SizeTransform(clip = false) }) { live ->
            if (live) {
                // Same as the task's card on home: light round the edge, liquid along the foot.
                LiquidFrame(Modifier.clip(RoundedCornerShape(22.dp)).then(toggle), wave = 40.dp, footer = {
                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                        Text(toolSummary(tools, false), style = Ph.monoSmall.copy(color = Ph.Text), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("step ${tools.size}", style = Ph.monoSmall.copy(color = Ph.Text))
                    }
                }) {
                    Row(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PulseDot(Ph.Amber, 7)
                        Text("Working", style = Ph.title.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        PhIcon(PhIcons.Chevron, Ph.Muted, 14.dp, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f })
                    }
                    StepLine(target, Ph.TextSoft, Modifier.padding(start = 33.dp, end = 16.dp, top = 8.dp))
                }
            } else {
                // Done: home's quiet card. A dot, what it did, the last thing it touched.
                val shape = RoundedCornerShape(18.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape).then(toggle)
                    .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(if (failed > 0) Ph.Red else Ph.Green))
                        Text(toolSummary(tools, false), style = Ph.title.copy(fontSize = 14.5.sp, color = Ph.TextSoft), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (failed > 0) "$failed failed" else "${tools.size} ${if (tools.size == 1) "step" else "steps"}",
                            style = Ph.monoSmall.copy(color = if (failed > 0) Ph.Red else Ph.Muted))
                        PhIcon(PhIcons.Chevron, Ph.Muted, 13.dp, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f })
                    }
                    Text(target, style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.Muted), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 19.dp))
                }
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { ToolPanel(tools) }
    }
}

private fun toolSummary(tools: List<Item.Tool>, running: Boolean): String {
    fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
    val by = tools.groupingBy { it.name.lowercase() }.eachCount()
    val known = setOf("bash", "read", "edit", "write", "grep", "find", "ls")
    val parts = buildList {
        by["bash"]?.let { add("ran " + n(it, "command", "commands")) }
        by["read"]?.let { add("read " + n(it, "file", "files")) }
        ((by["edit"] ?: 0) + (by["write"] ?: 0)).takeIf { it > 0 }?.let { add("edited " + n(it, "file", "files")) }
        ((by["grep"] ?: 0) + (by["find"] ?: 0) + (by["ls"] ?: 0)).takeIf { it > 0 }?.let { add("searched " + n(it, "time", "times")) }
        val other = tools.size - by.filterKeys { it in known }.values.sum()
        if (other > 0) add("used " + n(other, "tool", "tools"))
    }
    val text = parts.joinToString(", ")
    if (running) return "Working: " + tools.last { it.state == io.github.pimobile.ui.state.ToolState.RUNNING }.name + " · " + text
    return text.replaceFirstChar { it.uppercase() }
}

/** Under the composer: the model and thinking level (tap to change) and how full the context is. */
@Composable
private fun ModelLine(t: TaskUi, onModel: () -> Unit, onThinking: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val tap = Modifier.clip(RoundedCornerShape(8.dp))
        Text(io.github.pimobile.ui.state.modelLabel(UiStore.model)?.first ?: "model", style = Ph.monoSmall, maxLines = 1,
            modifier = tap.clickable(onClickLabel = "Change model") { onModel() }.padding(horizontal = 8.dp, vertical = 8.dp))
        t.thinkingLevel?.let {
            Text("·", style = Ph.monoSmall)
            Text(it, style = Ph.monoSmall, modifier = tap.clickable(onClickLabel = "Change thinking level") { onThinking() }.padding(horizontal = 8.dp, vertical = 8.dp))
        }
        if (t.contextTokens > 0) Text("·  " + String.format(Locale.US, "%.1fk ctx", t.contextTokens / 1000.0), style = Ph.monoSmall, modifier = Modifier.padding(end = 8.dp))
        if (t.cost >= 0.005) Text("· " + String.format(Locale.US, "$%.2f", t.cost), style = Ph.monoSmall)
    }
}

@Composable
private fun TerminalBusy(onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        PulseDot(Ph.Amber, 9)
        Text("Saturn is working on this task", style = Ph.title, modifier = Modifier.padding(top = 16.dp))
        Text("The terminal opens this same session in pi's own TUI once the run is idle.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Text("Stop the run", style = Ph.title.copy(fontSize = 14.sp, color = Ph.Red),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0xFF3A2A28), RoundedCornerShape(12.dp))
                .clickable { onStop() }.padding(horizontal = 18.dp, vertical = 11.dp))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = Ph.monoSmall.copy(color = if (on) Ph.Amber else Ph.Muted),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Ph.AmberWash else Color.Transparent)
            .border(1.dp, if (on) Ph.AmberLine else Ph.Line, RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun Working(t: TaskUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PulseDot(Ph.Amber, 7)
        Text(when { t.stopping -> "stopping…"; t.status == Status.STARTING -> "starting Saturn"; else -> "working · ${elapsed(t.runStartedAt)}" }, style = Ph.mono.copy(color = Ph.Muted))
        BlinkCursor()
    }
}

@Composable
private fun Row1(t: TaskUi, item: Item) {
    when (item) {
        is Item.User -> UserMessage(t, item)
        is Item.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.thinking.isNotBlank()) Thinking(item)
            if (item.text.isNotBlank()) SelectionContainer { Markdown(item.text) }
            if (item.text.isNotBlank() && !item.streaming && endOfTurn(t, item)) ReplyActions(t, item)
        }
        is Item.Notice -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.error) PhIcon(PhIcons.Warning, Ph.Red, 16.dp)
            Text(item.text, style = Ph.mono.copy(color = if (item.error) Ph.Red else Ph.Muted))
        }
        is Item.Dialog -> DialogCard(t, item)
        is Item.Tool -> ToolPanel(listOf(item))
    }
}

@Composable
private fun Thinking(item: Item.Assistant) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 2.dp)) {
        Text(
            (if (open) "▾ thinking" else "▸ thinking  ") + if (open) "" else item.thinking.lines().lastOrNull { it.isNotBlank() }.orEmpty(),
            style = Ph.mono.copy(color = Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(open) {
            Text(item.thinking.trim(), style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 6.dp, start = 12.dp))
        }
    }
}

@Composable
private fun DialogCard(t: TaskUi, d: Item.Dialog) {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ph.Surface)
            .border(1.dp, if (d.answer == null) Ph.AmberLine else Ph.Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(d.title.ifBlank { "Saturn needs your input" }, style = Ph.title.copy(fontSize = 14.sp))
        if (d.message.isNotBlank()) Text(d.message, style = Ph.body.copy(fontSize = 13.sp))
        val answered = d.answer
        if (answered != null) Text("→ $answered", style = Ph.mono.copy(color = Ph.Green))
        else if (d.options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.options.forEach { o ->
                Text(o, style = Ph.mono.copy(color = Ph.Text), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).clickable { UiStore.answer(t, d, o) }
                    .padding(horizontal = 12.dp, vertical = 11.dp))
            }
            Text("dismiss", style = Ph.monoSmall, modifier = Modifier.clickable { UiStore.answer(t, d, null) }.padding(vertical = 6.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(text, { text = it }, textStyle = Ph.body.copy(color = Ph.Text), cursorBrush = SolidColor(Ph.Amber),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Ph.Ground)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).padding(12.dp),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text(d.placeholder.ifBlank { "Your answer" }, style = Ph.body.copy(color = Ph.Muted)); inner() } })
            SendButton(text.isNotBlank()) { UiStore.answer(t, d, text) }
        }
    }
}


/** Your message; tap it for Branch from here / Copy. Attached images show as thumbnails. */
@Composable
private fun UserMessage(t: TaskUi, item: Item.User) {
    var open by remember { mutableStateOf(false) }
    val lines = item.text.lines()
    val images = lines.filter { it.startsWith(UiStore.IMAGE_NOTE) }.map { it.removePrefix(UiStore.IMAGE_NOTE).removeSuffix("]") }
    val files = lines.filter { it.startsWith(UiStore.FILE_NOTE) }.map { it.removePrefix(UiStore.FILE_NOTE).removeSuffix("]") }
    val text = lines.filterNot { it.startsWith(UiStore.IMAGE_NOTE) || it.startsWith(UiStore.FILE_NOTE) }.joinToString("\n").trim()
    val context = androidx.compose.ui.platform.LocalContext.current
    val copy = copier()
    // Your message as a prompt line: an amber › and your words, like typing at a terminal.
    // No box; wrapped lines stay aligned after the ›.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Message actions") { open = !open }
            .padding(vertical = 6.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("›", style = Ph.mono.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Ph.Amber))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text.isNotEmpty()) Text(text, style = Ph.body.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFFF3E6D0)))
                if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { images.forEach { Thumb(it, 92) } }
                files.forEach { path ->
                    FileChip(java.io.File(path).name, onClick = {
                        runCatching {
                            val f = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }.onFailure { android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show() }
                    })
                }
            }
        }
        AnimatedVisibility(open) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val index = t.items.filterIsInstance<Item.User>().indexOf(item)
                MsgAction(PhIcons.Fork, if (UiStore.branching) "branching…" else "Branch from here") {
                    if (t.status == Status.RUNNING || t.status == Status.NEEDS_INPUT) UiStore.branchError = "wait until Saturn finishes, or stop it"
                    else { UiStore.branch(t, index, text); open = false }
                }
                MsgAction(PhIcons.Copy, "Copy") { copy(text); open = false }
            }
        }
    }
}

@Composable
private fun MsgAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PhIcon(icon, Ph.TextSoft, 14.dp)
        Text(label, style = Ph.body.copy(fontSize = 13.sp, color = Ph.TextSoft))
    }
}


/** The last reply before your next message (or the end): where Copy · Report go. */
private fun endOfTurn(t: TaskUi, item: Item.Assistant): Boolean {
    val i = t.items.indexOf(item)
    if (i < 0) return false
    for (j in i + 1 until t.items.size) when (t.items[j]) {
        is Item.User -> return true
        is Item.Assistant -> if ((t.items[j] as Item.Assistant).text.isNotBlank()) return false
        else -> {}
    }
    return true
}

@Composable
private fun ReplyActions(t: TaskUi, item: Item.Assistant) {
    val copy = copier()
    var report by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val speaking = io.github.pimobile.ui.state.Speaker.speaking == item.key
    val idle = t.status != Status.RUNNING && t.status != Status.STARTING && t.status != Status.NEEDS_INPUT
    val last = t.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } === item
    Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ActIcon(PhIcons.Copy, "Copy reply") { copy(item.text) }
        if (last && idle) {
            val busy = UiStore.retrying(t)
            Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy, onClickLabel = "Retry") { UiStore.retry(t) }
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) PulseDot(Ph.Amber, 6) else PhIcon(PhIcons.Retry, Ph.TextSoft, 15.dp)
                Text(if (busy) "Retrying…" else "Retry", style = Ph.body.copy(fontSize = 12.5.sp, color = if (busy) Ph.Amber else Ph.TextSoft))
            }
        }
        ActIcon(if (speaking) PhIcons.Stop else PhIcons.Speaker, if (speaking) "Stop reading" else "Read aloud", if (speaking) Ph.Amber else Ph.Muted) {
            io.github.pimobile.ui.state.Speaker.toggle(ctx, item.key, item.text) }
        ActIcon(PhIcons.Flag, "Report reply") { report = true }
    }
    if (report) ReportSheet(t, item.text) { report = false }
}

@Composable
private fun ActIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Ph.Muted, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        PhIcon(icon, tint, 16.dp, label)
    }
}
