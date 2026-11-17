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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
fun TaskScreen(t: TaskUi, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope, onBack: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var followUp by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val running = t.status == Status.RUNNING || t.status == Status.STARTING
    val list = rememberLazyListState()
    val blocks by remember(t) { derivedStateOf { blocks(t.items.toList()) } }

    LaunchedEffect(t) { UiStore.open(t) }
    // Follow the conversation while the user is at the bottom.
    val atBottom by remember { derivedStateOf { !list.canScrollForward || list.layoutInfo.totalItemsCount == 0 } }
    val lastText = (t.items.lastOrNull() as? Item.Assistant)?.text?.length ?: 0
    LaunchedEffect(blocks.size, lastText) { if (atBottom && blocks.isNotEmpty()) list.animateScrollToItem(blocks.size) }

    val commands = slashCommands(listOf(
        SlashCommand("model", "Switch model for this task", "pi") { sheet = "model" },
        SlashCommand("compact", "Summarize to free context", "pi") { UiStore.compact(t) },
        SlashCommand("name", "Rename this task", "app") { sheet = "rename" },
        SlashCommand("stop", "Stop the current run", "app") { UiStore.stop(t) },
        SlashCommand("login", "Add a provider API key", "pi") { sheet = "settings" },
    ))

    with(shared) {
        Box(
            Modifier.fillMaxSize()
                .sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility,
                    clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))
                .background(Ph.Ground)
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(t, onBack, onMenu = { sheet = "rename" })
                LazyColumn(
                    state = list, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(blocks, key = { it.key }) { b ->
                        Box(Modifier.animateItem()) {
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
                            Text(if (followUp) "sent when pi finishes" else "sent after current step", style = Ph.monoSmall)
                        }
                    }
                    Composer(
                        value = input, onValue = { input = it },
                        placeholder = "Continue task #${t.task.number}…",
                        commands = commands,
                        onSend = { text -> input = TextFieldValue(""); UiStore.send(t, text, followUp) },
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
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Rename task") { onMenu() },
            contentAlignment = Alignment.Center) { PhIcon(PhIcons.More, Ph.TextSoft, 20.dp, "Task options") }
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
        Text(if (t.status == Status.STARTING) "starting pi" else "working · ${elapsed(t.runStartedAt)}", style = Ph.mono.copy(color = Ph.Muted))
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
        Text(d.title.ifBlank { "pi needs your input" }, style = Ph.title.copy(fontSize = 14.sp))
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
