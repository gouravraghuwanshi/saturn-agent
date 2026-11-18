package io.github.pimobile.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.StatusBadge
import io.github.pimobile.ui.components.SweepBar
import io.github.pimobile.ui.components.diffStats
import io.github.pimobile.ui.components.toolTarget
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private val ACTIVE = setOf(Status.STARTING, Status.RUNNING, Status.NEEDS_INPUT)

/** pi built-ins that only make sense inside a task. */
private val TASK_ONLY = setOf("tree", "fork", "clone", "session", "name", "new", "compact", "copy", "export",
    "thinking", "stop", "quit", "import", "share", "scoped-models", "hotkeys", "trust", "bug")

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    onStore: () -> Unit,
    onOpen: (TaskUi) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var project by rememberSaveable { mutableStateOf(UiStore.runtime.projects.path) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val activeCount = UiStore.tasks.count { it.status in ACTIVE }
    val spent = UiStore.tasks.sumOf { it.cost }

    var loginArg by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val builtins = listOf(
        Builtin("login", "Sign in to a provider (subscriptions, API keys)", args = true) { a -> loginArg = a; sheet = "login" },
        Builtin("logout", "Remove a provider credential") { sheet = "logout" },
        Builtin("model", "Default model for new tasks", args = true) { a ->
            if (a.contains('/')) UiStore.setModel(null, a.substringBefore('/'), a.substringAfter('/')) else sheet = "model" },
        Builtin("resume", "Reopen an earlier session as a task") { sheet = "resume" },
        Builtin("project", "Choose the project folder", tag = "app") { sheet = "project" },
        Builtin("settings", "App settings", tag = "app") { sheet = "settings" },
        Builtin("reload", "Reload extensions & skills in idle tasks") { io.github.pimobile.runtime.TaskManager.restartIdle(); UiStore.commands = emptyList() },
        Builtin("changelog", "What's new in pi") { sheet = "changelog" },
    )
    val commands = paletteOf(builtins)

    Box(Modifier.fillMaxSize().background(Ph.Ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- header
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Amber), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).clickable { sheet = "model" }) {
                    Text("Saturn", style = Ph.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text))
                    val label = io.github.pimobile.ui.state.modelLabel(UiStore.model)
                    if (label != null) Text(androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Ph.TextSoft)) { append(label.first) }
                        append(" · ${label.second}")
                    }, style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) else Text(if (UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty()) "auto model" else "not signed in · /login",
                        style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBox(PhIcons.Cube, "Extension store") { onStore() }
                IconBox(PhIcons.Settings, "Settings") { sheet = "settings" }
            }


            // ---- tasks
            if (UiStore.tasks.isEmpty()) EmptyState(Modifier.weight(1f), UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty()) { sheet = "login" }
            else LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val visible = UiStore.tasks.filterNot { it.hidden }
                val active = visible.filter { it.status in ACTIVE }
                val earlier = visible.filter { it.status !in ACTIVE && !it.archived }
                val archived = visible.filter { it.status !in ACTIVE && it.archived }
                val spentLabel = if (spent >= 0.005) String.format(Locale.US, "$%.2f spent", spent) else null
                fun section(list: List<TaskUi>, key: String, title: String, trailing: String?, first: Boolean,
                            onHeader: (() -> Unit)? = null, collapsed: Boolean = false) {
                    if (list.isEmpty()) return
                    item(key = "h-$key") {
                        Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .then(if (onHeader != null) Modifier.clickable(onClickLabel = if (collapsed) "Show $key" else "Hide $key") { onHeader() } else Modifier)
                            .padding(start = 4.dp, end = 4.dp, top = if (first) 0.dp else 14.dp, bottom = if (onHeader != null) 4.dp else 0.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = Ph.label)
                            trailing?.let { Text(it, style = Ph.monoSmall) }
                        }
                    }
                    if (!collapsed) items(list, key = { it.task.id }) { t ->
                        with(shared) {
                            Box(Modifier.animateItem().sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility,
                                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))) {
                                SwipeCard(t) { TaskCard(t, onOpen = { onOpen(t) }) }
                            }
                        }
                    }
                }
                section(active, "active", "ACTIVE · ${active.size}", spentLabel, first = true)
                section(earlier, "earlier", if (active.isEmpty()) "TASKS" else "EARLIER · ${earlier.size}",
                    spentLabel.takeIf { active.isEmpty() }, first = active.isEmpty())
                section(archived, "archived", "ARCHIVED · ${archived.size}", if (showArchived) "hide" else "show",
                    first = active.isEmpty() && earlier.isEmpty(), onHeader = { showArchived = !showArchived }, collapsed = !showArchived)
            }
        }

        // ---- composer
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Ph.Ground.copy(alpha = 0f), 0.25f to Ph.Ground))
                // Taps that miss the composer's controls must not reach the task card underneath.
                .pointerInput(Unit) { detectTapGestures { } }
                .imePadding().navigationBarsPadding().padding(top = 24.dp, bottom = 12.dp)
        ) {
            UiStore.undo?.let { u -> UndoBar(u) }
            Column {
            Composer(
                value = input, onValue = { input = it },
                placeholder = "Ask Saturn to do something…",
                commands = commands,
                onSend = { text ->
                    input = TextFieldValue("")
                    val name = text.removePrefix("/").substringBefore(' ')
                    when {
                        dispatchSlash(text, builtins) -> {}
                        // pi built-ins that act on one task: don't start a task (and an LLM call) by accident.
                        text.startsWith("/") && name in TASK_ONLY -> android.widget.Toast.makeText(context,
                            "Open a task to use /$name", android.widget.Toast.LENGTH_SHORT).show()
                        else -> UiStore.newTask(text, File(project))
                    }
                },
                leading = {
                    ChipButton(File(project).let { if (it == UiStore.runtime.projects) "projects" else it.name },
                        icon = PhIcons.Folder, description = "Choose project") { sheet = "project" }
                },
            )
            Text("swipe a task  ← delete  ·  archive →", style = Ph.monoSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
    }

    when (sheet) {
        "settings" -> SettingsSheet { sheet = null }
        "model" -> ModelSheet(null) { sheet = null }
        "project" -> ProjectSheet(File(project), { project = it.path }) { sheet = null }
        "login" -> LoginSheet(initialQuery = loginArg) { sheet = null }
        "logout" -> LoginSheet(logoutMode = true) { sheet = null }
        "resume" -> ResumeSheet(onOpen = { onOpen(it) }) { sheet = null }
        "changelog" -> ChangelogSheet { sheet = null }
    }
}

@Composable
fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, Ph.TextSoft, 20.dp, description) }
}

@Composable
private fun EmptyState(modifier: Modifier, hasKey: Boolean, onSettings: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_glyph), contentDescription = null, tint = Ph.LineStrong, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (hasKey) "What should Saturn work on?" else "Connect a model to begin",
            style = Ph.title.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "Every instruction becomes its own task. Run several at once — tap one to continue it."
            else "Sign in with a subscription (Claude, ChatGPT, Copilot…) or add an API key.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center,
        )
        if (!hasKey) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Amber).clickable { onSettings() }
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Sign in", style = Ph.title.copy(fontSize = 14.sp, color = Ph.AmberInk))
            }
        }
        Spacer(Modifier.height(140.dp))
    }
}

/** Ticks once a second while [since] is set; returns "1m 12s". */
@Composable
fun elapsed(since: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (since != null) { now = System.currentTimeMillis(); delay(1000) } }
    since ?: return ""
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(t: TaskUi, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val active = t.status in ACTIVE
    val needs = t.status == Status.NEEDS_INPUT
    val shape = RoundedCornerShape(18.dp)
    val hasError = t.lastError != null

    Box {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(if (active) Ph.Surface else Ph.Ground)
                .border(1.dp, when { needs -> Color3A3222; active -> Ph.LineStrong; else -> Ph.Line }, shape)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true }, onClickLabel = "Open task")
                .animateContentSize()
        ) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (active) 0.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("#${t.task.number}", style = Ph.mono.copy(fontWeight = FontWeight.SemiBold,
                    color = if (t.status == Status.RUNNING || t.status == Status.STARTING) Ph.Amber else Ph.Muted))
                Text(t.title, style = if (active) Ph.title else Ph.title.copy(fontWeight = FontWeight.Normal, color = Ph.TextSoft, fontSize = 14.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusBadge(t.status, hasError, t.browsing)
            }

            if (t.browsing) io.github.pimobile.browser.BrowserManager.pages[t.task.id]?.let { page ->
                Text("◉ " + page.url.removePrefix("https://").takeIf { !it.startsWith("data:") }.orEmpty(),
                    style = Ph.mono.copy(color = Ph.Cyan), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
            if (needs) NeedsYou(t)
            else if (active) {
                // Live tail: last three tool calls (or the streaming reply).
                val tools = t.tools.takeLast(3)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (tools.isEmpty()) {
                        val reply = (t.items.lastOrNull { it is Item.Assistant } as? Item.Assistant)?.text.orEmpty()
                        Text(reply.ifBlank { "thinking…" }.takeLast(140), style = Ph.mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    tools.forEach { tool ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val (icon, c) = when (tool.state) {
                                ToolState.RUNNING -> "⚙" to Ph.Amber; ToolState.DONE -> "✓" to Ph.Cyan; ToolState.ERROR -> "✗" to Ph.Red
                            }
                            Text(icon, style = Ph.mono.copy(color = c))
                            Text("${tool.name}  ${toolTarget(tool.name, tool.args)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = Ph.mono.copy(color = if (tool.state == ToolState.RUNNING) Ph.Text else Ph.TextBody),
                                modifier = Modifier.weight(1f, fill = false))
                            diffStats(tool)?.let { (a, d) ->
                                Text("+$a", style = Ph.mono.copy(color = Ph.Green)); if (d > 0) Text("−$d", style = Ph.mono.copy(color = Ph.Red))
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ph.Line))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(t.task.cwd.name, style = Ph.monoSmall)
                    if (t.tools.isNotEmpty()) Text("step ${t.tools.size}", style = Ph.monoSmall)
                    elapsed(t.runStartedAt).takeIf { it.isNotEmpty() }?.let { Text(it, style = Ph.monoSmall) }
                    if (t.cost >= 0.005) Text(String.format(Locale.US, "$%.2f", t.cost), style = Ph.monoSmall)
                }
                if (t.status == Status.RUNNING || t.status == Status.STARTING) SweepBar()
            } else {
                val last = t.lastError ?: (t.items.lastOrNull { it is Item.Assistant } as? Item.Assistant)?.text
                last?.takeIf { it.isNotBlank() }?.let {
                    Text(it.lines().firstOrNull { l -> l.isNotBlank() }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = Ph.monoSmall.copy(color = if (t.lastError != null) Ph.Red else Ph.Muted),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
                }
            }
        }
    }
    if (menu) TaskMenuSheet(t, buildList {
        add(MenuAction("Rename", PhIcons.Pencil) { rename = true })
        if (active) add(MenuAction("Stop run", PhIcons.Stop) { UiStore.stop(t) })
        else add(MenuAction(if (t.archived) "Restore" else "Archive", PhIcons.Archive) { UiStore.setArchived(t, !t.archived) })
        add(MenuAction("Delete task", PhIcons.Trash, danger = true, confirm = "Tap again to delete") { UiStore.delete(t) })
    }) { menu = false }
    if (rename) RenameSheet(t) { rename = false }
}

private val Color3A3222 = androidx.compose.ui.graphics.Color(0xFF3A3222)

/** Answer a pi dialog straight from the home screen. */
@Composable
private fun NeedsYou(t: TaskUi) {
    val d = t.pendingDialog ?: return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(d.title.ifBlank { d.message }, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp))
        if (d.options.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.options.take(3).forEach { o ->
                Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
                    .clickable { UiStore.answer(t, d, o) }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(o, style = Ph.mono.copy(color = Ph.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else Text("Tap to answer", style = Ph.monoSmall.copy(color = Ph.Amber))
    }
}

/** Swipe left to delete (with undo), right to archive — or, in Archived, to restore. */
@Composable
private fun SwipeCard(t: TaskUi, content: @Composable () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val canArchive = t.status !in ACTIVE
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); UiStore.deleteWithUndo(t) }
            SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm); UiStore.setArchived(t, !t.archived) }
            else -> {}
        }
        false // the card leaves its section on its own; snap back so a restored card looks right
    })
    SwipeToDismissBox(
        state, enableDismissFromStartToEnd = canArchive,
        backgroundContent = {
            val dir = state.dismissDirection
            val delete = dir == SwipeToDismissBoxValue.EndToStart
            if (dir == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
                    .background(if (delete) Ph.RedWash else Ph.AmberWash)
                    .border(1.dp, if (delete) androidx.compose.ui.graphics.Color(0xFF3A2A28) else Ph.AmberLine, RoundedCornerShape(18.dp))
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (delete) Arrangement.End else Arrangement.Start,
            ) {
                val (icon, label, color) = when {
                    delete -> Triple(PhIcons.Trash, "Delete", Ph.Red)
                    t.archived -> Triple(PhIcons.Archive, "Restore", Ph.Amber)
                    else -> Triple(PhIcons.Archive, "Archive", Ph.Amber)
                }
                if (delete) Text(label, style = Ph.mono.copy(color = color), modifier = Modifier.padding(end = 10.dp))
                PhIcon(icon, color, 20.dp)
                if (!delete) Text(label, style = Ph.mono.copy(color = color), modifier = Modifier.padding(start = 10.dp))
            }
        },
    ) { content() }
}

@Composable
private fun UndoBar(u: UiStore.Undo) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Ph.Raised).border(1.dp, Ph.LineStrong, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(u.message, style = Ph.body.copy(color = Ph.Text, fontSize = 14.sp), modifier = Modifier.weight(1f))
        Text("Undo", style = Ph.mono.copy(color = Ph.Amber, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Undo") { UiStore.undoLast() }
                .padding(horizontal = 14.dp, vertical = 12.dp))
    }
}
