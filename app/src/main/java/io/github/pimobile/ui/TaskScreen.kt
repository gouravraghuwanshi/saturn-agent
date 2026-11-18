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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TaskScreen(t: TaskUi, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
               onOpenTask: (TaskUi) -> Unit = {}, onBack: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
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
    // Follow new output until the user scrolls up; resume when they return to the bottom.
    var follow by remember { mutableStateOf(true) }
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
    // Opening the task, coming back from the terminal, or a history reload: show the latest.
    LaunchedEffect(t.rebuilds, tab) {
        if (tab == "chat" && blocks.isNotEmpty()) { follow = true; list.scrollToItem(blocks.size, Int.MAX_VALUE / 2) }
    }

    val ctx = LocalContext.current
    var loginArg by remember { mutableStateOf("") }
    // pi's built-in commands, mapped to RPC, the pimobile-app extension or native screens.
    val builtins = listOf(
        Builtin("model", "Switch model for this task", args = true) { a ->
            if (a.contains('/')) UiStore.setModel(t, a.substringBefore('/'), a.substringAfter('/')) else sheet = "model" },
        Builtin("thinking", "Set the thinking level", args = true) { a ->
            if (a.isNotBlank()) UiStore.rpc(t, "set_thinking_level", "level" to a) else sheet = "thinking" },
        Builtin("login", "Sign in to a provider (subscriptions, API keys)", args = true) { a -> loginArg = a; sheet = "login" },
        Builtin("logout", "Remove a provider credential") { sheet = "logout" },
        Builtin("new", "Start a fresh session in this task") { UiStore.rpc(t, "new_session") },
        Builtin("resume", "Open another session as a task") { sheet = "resume" },
        Builtin("name", "Rename this task", tag = "app", args = true) { a ->
            if (a.isNotBlank()) { UiStore.rename(t, a); UiStore.rpc(t, "set_session_name", "name" to a) } else sheet = "rename" },
        Builtin("session", "Session info and statistics") { sheet = "session" },
        Builtin("tree", "Navigate the session tree") { sheet = "tree" },
        Builtin("fork", "New session from an earlier message") { sheet = "fork" },
        Builtin("clone", "Duplicate this session at this point") { UiStore.rpc(t, "clone") },
        Builtin("compact", "Compact context (optional instructions)", args = true) { a ->
            UiStore.rpc(t, "compact", "customInstructions" to a.ifBlank { null }) },
        Builtin("copy", "Copy the last reply") { t.responses.remove("get_last_assistant_text"); UiStore.rpc(t, "get_last_assistant_text") },
        Builtin("export", "Export the session as HTML and share it") {
            val out = java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.html").apply { parentFile?.mkdirs() }
            t.responses.remove("export_html"); UiStore.rpc(t, "export_html", "outputPath" to out.path) },
        Builtin("reload", "Reload extensions, skills, prompts and context") { UiStore.appCommand(t, "reload") },
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
                .sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))
                .background(Ph.Ground)
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(t, onBack, onMenu = { sheet = "menu" })
                Tabs(tab, showBrowserTab) { tab = it; if (it != "browser") holdBrowser = false }
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
                LazyColumn(
                    state = list, modifier = Modifier.weight(1f).clipToBounds(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(blocks, key = { it.key }) { b ->
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                            when (b) {
                                is Block.Tools -> ToolPanel(b.tools)
                                is Block.Single -> Row1(t, b.item)
                            }
                        }
                    }
                    if (running) item(key = "working") { Working(t) }
                }

                // ---- composer
                Column(Modifier.imePadding().navigationBarsPadding().padding(bottom = 12.dp, top = 6.dp)) {
                    AnimatedVisibility(running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Toggle("steer", !followUp) { followUp = false }
                            Toggle("follow-up", followUp) { followUp = true }
                            Box(Modifier.weight(1f))
                            Text(if (followUp) "sent when Saturn finishes" else "sent after current step", style = Ph.monoSmall)
                        }
                    }
                    Composer(
                        value = input, onValue = { input = it },
                        placeholder = "Continue task #${t.task.number}…",
                        commands = commands,
                        onSend = { text ->
                            input = TextFieldValue("")
                            if (!dispatchSlash(text, builtins)) UiStore.send(t, text, followUp)
                        },
                        extra = {
                            if (running) Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Ph.RedWash)
                                    .border(1.dp, Color(0xFF3A2A28), RoundedCornerShape(14.dp))
                                    .clickable(onClickLabel = "Stop task") { UiStore.stop(t) },
                                contentAlignment = Alignment.Center,
                            ) { PhIcon(PhIcons.Stop, Ph.Red, 14.dp, "Stop task") }
                        },
                    )
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
                if (running) add(MenuAction("Stop run", PhIcons.Stop) { UiStore.stop(t) })
                add(MenuAction("Delete task", PhIcons.Trash, danger = true, confirm = "Tap again to delete") {
                    onBack(); UiStore.delete(t) })
            }) { if (sheet == "menu") sheet = null }
        }
    }
}

@Composable
private fun Header(t: TaskUi, onBack: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Back to tasks") { onBack() },
            contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back") }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Row {
                Text("#${t.task.number} ", style = Ph.title.copy(fontFamily = Ph.Mono,
                    color = if (t.status == Status.RUNNING) Ph.Amber else Ph.Muted, fontSize = 16.sp))
                Text(t.title, style = Ph.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (t.status) {
                    Status.RUNNING, Status.STARTING -> PulseDot(Ph.Amber, 6)
                    Status.NEEDS_INPUT -> PulseDot(Ph.Amber, 6)
                    else -> {}
                }
                val parts = buildList {
                    add(when (t.status) {
                        Status.STARTING -> "starting"; Status.RUNNING -> "running"; Status.NEEDS_INPUT -> "needs you"
                        Status.IDLE -> "ready"; Status.STOPPED -> "paused"; Status.FAILED -> "failed"
                    })
                    add(t.task.cwd.name)
                    if (t.contextTokens > 0) add(String.format(Locale.US, "%.1fk ctx", t.contextTokens / 1000.0))
                    if (t.cost >= 0.005) add(String.format(Locale.US, "$%.2f", t.cost))
                }
                Text(parts.joinToString(" · "), style = Ph.monoSmall, maxLines = 1)
            }
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Task options") { onMenu() },
            contentAlignment = Alignment.Center) { PhIcon(PhIcons.More, Ph.TextSoft, 20.dp, "Task options") }
    }
}

/** chat · terminal (+ browser once the agent has opened a page, until the user closes it). */
@Composable
private fun Tabs(tab: String, browser: Boolean, onTab: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (if (browser) listOf("chat", "terminal", "browser") else listOf("chat", "terminal")).forEach { name ->
            val on = tab == name
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Ph.Selected else Color.Transparent).clickable(onClickLabel = "Show $name") { onTab(name) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (name == "browser") PulseDot(Ph.Cyan, 6)
                    Text(name, style = Ph.mono.copy(color = if (on) Ph.Text else Ph.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
                }
            }
        }
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
        Text(if (t.status == Status.STARTING) "starting Saturn" else "working · ${elapsed(t.runStartedAt)}", style = Ph.mono.copy(color = Ph.Muted))
        BlinkCursor()
    }
}

@Composable
private fun Row1(t: TaskUi, item: Item) {
    when (item) {
        is Item.User -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("›", style = Ph.mono.copy(color = Ph.Amber, fontSize = 14.sp, lineHeight = 21.sp))
            SelectionContainer { Text(item.text, style = Ph.body.copy(color = Ph.Text)) }
        }
        is Item.Assistant -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.thinking.isNotBlank()) Thinking(item)
            if (item.text.isNotBlank()) SelectionContainer { Markdown(item.text) }
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
