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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    onOpen: (TaskUi) -> Unit,
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var project by rememberSaveable { mutableStateOf(UiStore.runtime.projects.path) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCount = UiStore.tasks.count { it.status in ACTIVE }
    val spent = UiStore.tasks.sumOf { it.cost }

    val commands = slashCommands(listOf(
        SlashCommand("model", "Switch model", "pi") { sheet = "model" },
        SlashCommand("login", "Add a provider API key", "pi") { sheet = "settings" },
        SlashCommand("project", "Choose the project folder", "app") { sheet = "project" },
    ))

    Box(Modifier.fillMaxSize().background(Ph.Ground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- header
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Amber), contentAlignment = Alignment.Center) {
                    Text("π", style = Ph.mono.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ph.AmberInk))
                }
                Column(Modifier.weight(1f).clickable { sheet = "model" }) {
                    Text("pi", style = Ph.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text))
                    Text(UiStore.model ?: if (UiStore.hasCredentials) "auto model" else "no provider key yet",
                        style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBox(PhIcons.Settings, "Settings") { sheet = "settings" }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (activeCount > 0) "TASKS · $activeCount ACTIVE" else "TASKS", style = Ph.label)
                if (spent >= 0.005) Text(String.format(Locale.US, "$%.2f spent", spent), style = Ph.monoSmall)
            }

            // ---- tasks
            if (UiStore.tasks.isEmpty()) EmptyState(Modifier.weight(1f), UiStore.hasCredentials) { sheet = "settings" }
            else LazyColumn(
                Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(UiStore.tasks, key = { it.task.id }) { t ->
                    with(shared) {
                        Box(Modifier.animateItem().sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility,
                            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))) {
                            TaskCard(t, onOpen = { onOpen(t) })
                        }
                    }
                }
            }
        }

        // ---- composer
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(0f to Ph.Ground.copy(alpha = 0f), 0.25f to Ph.Ground))
                .imePadding().navigationBarsPadding().padding(top = 24.dp, bottom = 12.dp)
        ) {
            Composer(
                value = input, onValue = { input = it },
                placeholder = "Ask pi to do something…",
                commands = commands,
                onSend = { text ->
                    input = TextFieldValue("")
                    UiStore.newTask(text, File(project))
                },
                leading = {
                    ChipButton(File(project).let { if (it == UiStore.runtime.projects) "projects" else it.name },
                        icon = PhIcons.Folder, description = "Choose project") { sheet = "project" }
                },
            )
            Text("↵ starts a new task · tap a task to continue it", style = Ph.monoSmall, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }

    when (sheet) {
        "settings" -> SettingsSheet { sheet = null }
        "model" -> ModelSheet(null) { sheet = null }
        "project" -> ProjectSheet(File(project), { project = it.path }) { sheet = null }
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
        Text("π", style = Ph.mono.copy(fontSize = 56.sp, color = Ph.LineStrong))
        Spacer(Modifier.height(16.dp))
        Text(if (hasKey) "What should pi work on?" else "Connect a model to begin",
            style = Ph.title.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "Every instruction becomes its own task. Run several at once — tap one to continue it."
            else "Add an API key for OpenRouter, Anthropic, OpenAI or another provider.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center,
        )
        if (!hasKey) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Amber).clickable { onSettings() }
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Add a key", style = Ph.title.copy(fontSize = 14.sp, color = Ph.AmberInk))
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
                StatusBadge(t.status, hasError)
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
        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = Ph.Raised) {
            DropdownMenuItem(text = { Text("Rename", style = Ph.body.copy(color = Ph.Text)) }, onClick = { menu = false; rename = true })
            if (active) DropdownMenuItem(text = { Text("Stop", style = Ph.body.copy(color = Ph.Text)) }, onClick = { menu = false; UiStore.stop(t) })
            DropdownMenuItem(text = { Text("Delete", style = Ph.body.copy(color = Ph.Red)) }, onClick = { menu = false; UiStore.delete(t) })
        }
    }
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
