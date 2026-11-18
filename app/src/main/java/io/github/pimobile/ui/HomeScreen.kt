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
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
    onTerminal: () -> Unit,
    onArtifacts: () -> Unit,
    onOpen: (TaskUi) -> Unit,
) {
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(t: TaskUi) { if (t.task.id in selected) selected.remove(t.task.id) else selected.add(t.task.id) }
    val ctxT = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun toast(msg: String) = android.widget.Toast.makeText(ctxT, msg, android.widget.Toast.LENGTH_SHORT).show()
    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val list = UiStore.tasks.filter { it.task.id in selected }.map { it.task }
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { io.github.pimobile.runtime.Transfer.export(ctxT, list, uri) } }
            r.onSuccess { n -> toast(if (n == 1) "Exported 1 task" else "Exported $n tasks"); selected.clear() }
                .onFailure { toast("Export failed: ${it.message}") }
        }
    }
    val importFrom = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { io.github.pimobile.runtime.Transfer.import(ctxT, uri) } }
            r.onSuccess { res ->
                res.added.forEach { UiStore.ui(it) }
                toast(buildString {
                    append(if (res.added.size == 1) "Imported 1 task" else "Imported ${res.added.size} tasks")
                    if (res.skipped > 0) append(" · ${res.skipped} already here or unreadable")
                })
            }.onFailure { toast("Import failed: ${it.message}") }
        }
    }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<UiStore.Attachment>() }
    val projectDir = UiStore.currentProject
    val project = projectDir.path
    val isGeneral = projectDir.canonicalPath == UiStore.runtime.projects.canonicalPath
    val projectLabel = if (isGeneral) "General" else projectDir.name
    val projectTasks = UiStore.tasksIn(projectDir)
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val spent = projectTasks.sumOf { it.cost }
    // Work going on in other projects stays visible from here.
    val elsewhere = UiStore.tasks.count { it.status in ACTIVE } - projectTasks.count { it.status in ACTIVE }

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
            // ---- header (a selection bar while tasks are selected)
            if (selecting) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBox(PhIcons.Close, "Clear selection") { selected.clear() }
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} selected", style = Ph.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text))
                    val visibleIds = projectTasks.filterNot { it.hidden }.map { it.task.id }
                    Text(if (selected.size < visibleIds.size) "select all" else "select none", style = Ph.monoSmall.copy(color = Ph.Amber),
                        modifier = Modifier.clickable {
                            if (selected.size < visibleIds.size) { selected.clear(); selected.addAll(visibleIds) } else selected.clear()
                        }.padding(vertical = 2.dp))
                }
                IconBox(PhIcons.Share, "Export selected") {
                    exportTo.launch("saturn-tasks-" + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) + ".zip")
                }
                IconBox(PhIcons.Trash, "Delete selected") {
                    UiStore.deleteManyWithUndo(UiStore.tasks.filter { it.task.id in selected }); selected.clear()
                }
            } else Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Amber)
                    .clickable(onClickLabel = "Saturn menu") { sheet = "saturn" }, contentAlignment = Alignment.Center) {
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


            io.github.pimobile.ui.components.BrokenPackageBanner(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            // ---- project switcher: the list below is this project's tasks
            Row(Modifier.padding(start = 16.dp, end = 20.dp, bottom = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Switch project") { sheet = "project" }.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(PhIcons.Folder, Ph.Amber, 16.dp)
                    Text(projectLabel, style = Ph.mono.copy(color = Ph.Text, fontWeight = FontWeight.SemiBold), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    PhIcon(PhIcons.Chevron, Ph.Muted, 14.dp)
                }
                Spacer(Modifier.weight(1f))
                if (elsewhere > 0) Text("$elsewhere running elsewhere", style = Ph.monoSmall.copy(color = Ph.Amber),
                    modifier = Modifier.clickable { sheet = "project" })
                else if (spent >= 0.005) Text(String.format(Locale.US, "$%.2f spent", spent), style = Ph.monoSmall)
            }
            // ---- tasks
            if (projectTasks.isEmpty()) EmptyState(Modifier.weight(1f), UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty(),
                project = projectLabel.takeIf { UiStore.tasks.isNotEmpty() }) { sheet = "login" }
            else LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val visible = projectTasks.filterNot { it.hidden }
                val active = visible.filter { it.status in ACTIVE }
                val earlier = visible.filter { it.status !in ACTIVE && !it.archived }
                val archived = visible.filter { it.status !in ACTIVE && it.archived }
                val spentLabel: String? = null // shown next to the project switcher
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
                                SwipeCard(t, enabled = !selecting) {
                                    TaskCard(t, selected = if (selecting) t.task.id in selected else null,
                                        onOpen = { if (selecting) toggle(t) else onOpen(t) }, onSelect = { toggle(t) })
                                }
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
            val pick = rememberAttachPicker { attachments.addAll(it) }
            Composer(
                value = input, onValue = { input = it },
                attachments = attachments.toList(), attach = pick, onRemoveAttachment = { attachments.remove(it) },
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
                        else -> { UiStore.newTask(text, File(project), attachments.toList()); attachments.clear() }
                    }
                },
                leading = {
                    ChipButton(projectLabel,
                        icon = PhIcons.Folder, description = "Choose project") { sheet = "project" }
                },
            )
            Text(if (selecting) "tap tasks to select · back to cancel" else "swipe ← delete · archive →  ·  hold to select", style = Ph.monoSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
    }

    when (sheet) {
        "settings" -> SettingsSheet { sheet = null }
        "model" -> ModelSheet(null) { sheet = null }
        "project" -> ProjectSheet(projectDir, { selected.clear(); UiStore.selectProject(it) }) { sheet = null }
        "login" -> LoginSheet(initialQuery = loginArg) { sheet = null }
        "logout" -> LoginSheet(logoutMode = true) { sheet = null }
        "resume" -> ResumeSheet(onOpen = { onOpen(it) }) { sheet = null }
        "changelog" -> ChangelogSheet { sheet = null }
        "saturn" -> SaturnSheet(
            onTerminal = onTerminal, onArtifacts = onArtifacts, onStore = onStore,
            onImport = { importFrom.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "*/*")) },
            onSelect = { projectTasks.firstOrNull { !it.hidden }?.let { selected.add(it.task.id) } },
            onScheduled = { sheet = "scheduled" },
        ) { if (sheet == "saturn") sheet = null }
        "scheduled" -> ScheduleSheet { sheet = null }
    }
}

/** Tapping the Saturn mark: the app's own places, away from the task list. */
@Composable
private fun SaturnSheet(onTerminal: () -> Unit, onArtifacts: () -> Unit, onStore: () -> Unit,
                        onImport: () -> Unit, onSelect: () -> Unit, onScheduled: () -> Unit, onDismiss: () -> Unit) {
    val artifacts = remember { UiStore.runtime.artifacts.walkTopDown().maxDepth(2).count { it.isFile && !it.name.startsWith(".") } }
    PhSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Amber)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_foreground), null, Modifier.fillMaxSize())
            }
            Column {
                Text("Saturn", style = Ph.heading)
                Text("${UiStore.tasks.size} tasks · $artifacts artifacts", style = Ph.monoSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        listOf(
            Triple(PhIcons.Sparkle, "Artifacts", "Files Saturn made for you") to onArtifacts,
            Triple(PhIcons.Terminal, "Terminal", "bash · npm · git · node") to onTerminal,
            Triple(PhIcons.Clock, "Scheduled", io.github.pimobile.runtime.Scheduler.jobs.size.let { if (it == 0) "Timers and repeating jobs" else "$it job${if (it == 1) "" else "s"} waiting" }) to onScheduled,
            Triple(PhIcons.Cube, "Extension store", "Packages from pi.dev") to onStore,
            Triple(PhIcons.Check, "Select tasks", "Export or delete several") to onSelect,
            Triple(PhIcons.Import, "Import tasks", "From a Saturn export or pi session") to onImport,
        ).forEach { (row, action) ->
            val (icon, title, sub) = row
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClickLabel = title) { onDismiss(); action() }
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center) { PhIcon(icon, if (title == "Artifacts") Ph.Amber else Ph.TextSoft, 19.dp) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = Ph.body.copy(color = Ph.Text, fontSize = 15.sp))
                    Text(sub, style = Ph.monoSmall)
                }
            }
        }
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
private fun EmptyState(modifier: Modifier, hasKey: Boolean, project: String? = null, onSettings: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_glyph), contentDescription = null, tint = Ph.LineStrong, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (!hasKey) "Connect a model to begin" else if (project != null) "No tasks in $project yet" else "What should Saturn work on?",
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
        Spacer(Modifier.height(28.dp))
        TrustLine()
        Spacer(Modifier.height(140.dp))
    }
}

/** The one-line trust note: quiet, never louder than the content around it. */
@Composable
fun TrustLine(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PhIcon(PhIcons.Lock, Ph.Muted, 12.dp)
        Text("Open source · runs on your phone", style = Ph.monoSmall.copy(color = Ph.Muted, fontSize = 11.sp))
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
private fun TaskCard(t: TaskUi, selected: Boolean?, onOpen: () -> Unit, onSelect: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val active = t.status in ACTIVE
    val needs = t.status == Status.NEEDS_INPUT
    val shape = RoundedCornerShape(18.dp)
    val hasError = t.lastError != null

    Box {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(when { selected == true -> Ph.AmberWash; active -> Ph.Surface; else -> Ph.Ground })
                .border(1.dp, when { selected == true -> Ph.AmberLine; needs -> Color3A3222; active -> Ph.LineStrong; else -> Ph.Line }, shape)
                .combinedClickable(onClick = onOpen, onClickLabel = if (selected != null) "Select task" else "Open task",
                    onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onSelect() },
                    onLongClickLabel = "Select task")
                .animateContentSize()
        ) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (active) 0.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selected != null) Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Ph.Amber else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.5.dp, if (selected) Ph.Amber else Ph.LineStrong, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center) { if (selected) PhIcon(PhIcons.Check, Ph.AmberInk, 14.dp) }
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
private fun SwipeCard(t: TaskUi, enabled: Boolean, content: @Composable () -> Unit) {
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
        state, enableDismissFromStartToEnd = canArchive && enabled, enableDismissFromEndToStart = enabled,
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
