package io.github.pimobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.PiRuntime
import io.github.pimobile.runtime.Scheduler
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/** Ready-made routines: tap one to start from it. */
private class Starter(val name: String, val prompt: String, val kind: Kind, val minutes: Int = 8 * 60, val every: Int = 2)
private val starters = listOf(
    Starter("Morning brief", "Give me a short morning brief: what my Saturn tasks finished or got stuck on since yesterday, " +
        "anything in my projects that needs me (open PRs, failing CI if gh is set up), and what I spent. 5 lines at most.", Kind.DAILY),
    Starter("PR + CI check", "Check my open pull requests and the latest CI runs with gh. Tell me only what needs me, in 3 lines.", Kind.EVERY, every = 2),
    Starter("Overnight tidy", "Tidy this project: delete build leftovers and temp files, list anything large or unused, and summarise what you removed.",
        Kind.OVERNIGHT, minutes = 60),
)

enum class Kind(val label: String, val sub: String) {
    DAILY("Every day", "at a time"), WEEKDAYS("Weekdays", "Mon–Fri"),
    EVERY("Every few hours", "9:00–21:00"), OVERNIGHT("Overnight", "charging + Wi-Fi"),
}

/** The Routines page of home (swipe Saturn): its rows go into home's list, under Saturn. */
fun LazyListScope.routinesPage(onNew: (Scheduler.Job?) -> Unit, onOpenTask: (String) -> Unit) {
    val jobs = Scheduler.jobs
    item(key = "r-head") {
        Row(Modifier.animateItem().fillMaxWidth().padding(start = 4.dp, end = 0.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (jobs.isEmpty()) "ROUTINES" else "ROUTINES · ${jobs.size}", style = Ph.label)
                Text("Saturn works on these by itself, even when the app is closed.", style = Ph.monoSmall, maxLines = 2)
            }
            Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Amber)
                .clickable(onClickLabel = "New routine") { onNew(null) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhIcon(PhIcons.Plus, Ph.AmberInk, 15.dp)
                Text("New", style = Ph.title.copy(fontSize = 13.5.sp, color = Ph.AmberInk))
            }
        }
    }
    if (!Scheduler.exact && jobs.any { !it.overnight && !it.paused }) item(key = "r-exact") { ExactHint(Modifier.animateItem()) }
    if (jobs.isEmpty()) item(key = "r-empty") { EmptyRoutines(Modifier.animateItem()) { s -> onNew(starterJob(s)) } }
    items(jobs, key = { "r-" + it.id }) { j -> RoutineCard(j, Modifier.animateItem(), onEdit = { onNew(j) }, onOpenTask = onOpenTask) }
}

@Composable
private fun ExactHint(modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(14.dp))
        .clickable {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.fromParts("package", ctx.packageName, null))) }
        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhIcon(PhIcons.Clock, Ph.Amber, 18.dp)
        Column(Modifier.weight(1f)) {
            Text("Allow exact timing", style = Ph.title.copy(fontSize = 14.sp))
            Text("Otherwise Android may start routines a few minutes late. Tap, then turn on Alarms & reminders.",
                style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyRoutines(modifier: Modifier, onStarter: (Starter) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nothing on a schedule yet", style = Ph.title.copy(fontSize = 15.sp))
        Text("Give Saturn a job that repeats: a brief every morning, a check every few hours, or heavy work overnight while the phone charges. " +
            "Each run becomes a task you can open.", style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.TextSoft))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { s ->
                Text(s.name, style = Ph.mono.copy(color = Ph.Amber), modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Ph.AmberLine, RoundedCornerShape(10.dp)).clickable { onStarter(s) }.padding(horizontal = 11.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun RoutineCard(j: Scheduler.Job, modifier: Modifier, onEdit: () -> Unit, onOpenTask: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val running = j.runs.lastOrNull()?.state == "running" && j.runs.last().taskId?.let { UiStore.find(it) }?.status.let {
        it == io.github.pimobile.runtime.TaskManager.Status.RUNNING || it == io.github.pimobile.runtime.TaskManager.Status.STARTING }
    val on = !j.paused
    val shape = RoundedCornerShape(18.dp)
    val alpha by animateFloatAsState(if (on) 1f else 0.6f, label = "on")
    Column(modifier.fillMaxWidth().clip(shape).background(if (running) Ph.AmberWash else Ph.Surface)
        .border(1.dp, if (running) Ph.AmberLine else Ph.Line, shape)
        .clickable(onClickLabel = if (open) "Hide actions" else "Routine actions") { open = !open; confirm = false }
        .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) PulseDot(Ph.Amber, 7)
            else PhIcon(if (j.prompt != null) PhIcons.Sparkle else PhIcons.Terminal, if (on) Ph.Amber else Ph.Muted, 15.dp)
            Text(j.name, style = Ph.title.copy(fontSize = 15.sp, color = if (on) Ph.Text else Ph.TextSoft), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Switch(on) { Scheduler.setPaused(j.id, on) }
        }
        Column(Modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(trigger(j), projectOf(j), j.cap?.let { String.format(Locale.US, "cap $%.2f", it) },
                if (j.quiet) "tells you only if new" else null).joinToString(" · "),
                style = Ph.monoSmall.copy(color = if (j.overnight) Ph.Cyan else Ph.TextSoft), maxLines = 2)
            if (on && !running) Text(nextLabel(j), style = Ph.monoSmall.copy(color = Ph.Amber))
            if (j.runs.isNotEmpty()) History(j, onOpenTask)
            j.lastResult?.takeIf { it.isNotBlank() }?.let {
                Text("last: $it", style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.Muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(open) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("Run now") { Scheduler.fire(j.id, manual = true); open = false }
                if (j.prompt != null) Action("Edit") { onEdit(); open = false }
                Action(if (confirm) "Tap again to delete" else "Delete", Ph.Red) {
                    if (confirm) Scheduler.remove(j.id) else confirm = true
                }
            }
        }
    }
}

/** The last runs as little bars: green done, grey nothing new, red failed, amber running or stopped at the cap. Tap one to open it. */
@Composable
private fun History(j: Scheduler.Job, onOpenTask: (String) -> Unit) {
    val runs = j.runs.takeLast(10)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        runs.forEach { r ->
            val c = when (r.state) { "ok" -> Ph.Green; "failed" -> Ph.Red; "quiet" -> Ph.LineStrong; else -> Ph.Amber }
            val task = r.taskId?.let { UiStore.find(it) }
            Box(Modifier.size(width = 18.dp, height = 22.dp).clip(RoundedCornerShape(5.dp))
                .then(if (task != null) Modifier.clickable(onClickLabel = "Open this run") { onOpenTask(task.task.id) } else Modifier),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 16.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(c))
            }
        }
        val spent = runs.sumOf { it.cost }
        Text(listOfNotNull("${runs.size} ${if (runs.size == 1) "run" else "runs"}",
            spent.takeIf { it >= 0.005 }?.let { String.format(Locale.US, "$%.2f", it) }).joinToString(" · "),
            style = Ph.monoSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Switch(on: Boolean, onToggle: () -> Unit) {
    val x by animateDpAsState(if (on) 19.dp else 3.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "knob")
    Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Ph.Amber else Ph.LineStrong)
        .clickable(onClickLabel = if (on) "Pause routine" else "Turn routine on") { onToggle() }) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Ph.AmberInk else Ph.Muted))
    }
}

@Composable
private fun Action(label: String, color: Color = Ph.TextSoft, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Ph.body.copy(fontSize = 13.sp, color = color))
    }
}

// ------------------------------------------------------------------ describing a routine

private fun hm(minutes: Int) = String.format(Locale.US, "%d:%02d", minutes / 60, minutes % 60)

/** What our editor writes, read back: (kind, minutes of the day, every N hours). */
private fun parse(j: Scheduler.Job): Triple<Kind, Int, Int>? {
    val p = j.cron?.trim()?.split(Regex("\\s+")) ?: return null
    if (p.size != 5) return null
    val min = p[0].toIntOrNull()
    if (min != null && p[1].toIntOrNull() != null && p[2] == "*" && p[3] == "*") {
        val m = p[1].toInt() * 60 + min
        return when { j.overnight -> Triple(Kind.OVERNIGHT, m, 2); p[4] == "1-5" -> Triple(Kind.WEEKDAYS, m, 2); p[4] == "*" -> Triple(Kind.DAILY, m, 2); else -> null }
    }
    Regex("9-21/(\\d+)").matchEntire(p[1])?.let { if (p[0] == "0" && p[2] == "*" && p[4] == "*") return Triple(Kind.EVERY, 9 * 60, it.groupValues[1].toInt()) }
    return null
}

private fun trigger(j: Scheduler.Job): String {
    val k = parse(j)
    return when {
        k == null && j.cron == null -> "once"
        k == null -> "cron ${j.cron}"
        k.first == Kind.DAILY -> "daily ${hm(k.second)}"
        k.first == Kind.WEEKDAYS -> "weekdays ${hm(k.second)}"
        k.first == Kind.EVERY -> "every ${k.third}h, 9–21"
        else -> "overnight from ${hm(k.second)}"
    }
}

private fun projectOf(j: Scheduler.Job): String? {
    if (j.cwd.isBlank()) return null
    val n = UiStore.runtime.projectName(File(j.cwd))
    return if (n == PiRuntime.GENERAL) "General" else n
}

private fun nextLabel(j: Scheduler.Job): String {
    val s = ((j.next - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    val rel = when { s < 60 -> "in under a minute"; s < 3600 -> "in ${s / 60}m"; s < 86_400 -> "in ${s / 3600}h ${s % 3600 / 60}m"; else -> "in ${s / 86_400}d" }
    val clock = java.text.SimpleDateFormat(if (s < 86_400) "HH:mm" else "EEE HH:mm", Locale.getDefault()).format(java.util.Date(j.next))
    return if (j.overnight) "next: tonight after $clock, once charging on Wi-Fi" else "next $rel · $clock"
}

private fun starterJob(s: Starter) = Scheduler.Job("", s.name, null, s.prompt, UiStore.currentProject.path, null,
    cronOf(s.kind, s.minutes, s.every), 0, quiet = s.kind != Kind.OVERNIGHT, cap = 0.25, overnight = s.kind == Kind.OVERNIGHT)

private fun cronOf(kind: Kind, minutes: Int, every: Int) = when (kind) {
    Kind.DAILY, Kind.OVERNIGHT -> "${minutes % 60} ${minutes / 60} * * *"
    Kind.WEEKDAYS -> "${minutes % 60} ${minutes / 60} * * 1-5"
    Kind.EVERY -> "0 9-21/$every * * *"
}

/** Every Saturn project, General first. */
private fun projects(): List<Pair<String, File>> {
    val rt = UiStore.runtime
    return listOf("General" to rt.projects) + rt.projects.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        .sortedBy { it.name.lowercase() }.map { it.name to it }
}

// ------------------------------------------------------------------ the editor

/** New routine, or edit one ([job] with an empty id: from a starter). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineSheet(job: Scheduler.Job?, onDismiss: () -> Unit) {
    val parsed = job?.let { parse(it) }
    var name by remember { mutableStateOf(job?.name.orEmpty()) }
    var prompt by remember { mutableStateOf(job?.prompt.orEmpty()) }
    var kind by remember { mutableStateOf(parsed?.first ?: Kind.DAILY) }
    var minutes by remember { mutableStateOf(parsed?.second ?: 8 * 60) }
    var every by remember { mutableStateOf(parsed?.third ?: 2) }
    var cwd by remember { mutableStateOf(job?.cwd?.takeIf { it.isNotBlank() } ?: UiStore.currentProject.path) }
    var cap by remember { mutableStateOf(job?.cap ?: 0.25) }
    var quiet by remember { mutableStateOf(job?.quiet ?: true) }
    val editing = job != null && job.id.isNotEmpty()
    // Overnight defaults to 1:00, the others to 8:00, when you switch.
    fun pick(k: Kind) { if ((k == Kind.OVERNIGHT) != (kind == Kind.OVERNIGHT)) minutes = if (k == Kind.OVERNIGHT) 60 else 8 * 60; kind = k }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val cron = cronOf(kind, minutes, every)
    val next = runCatching { Scheduler.Cron(cron).next(now) }.getOrNull()
    val ready = prompt.isNotBlank()

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (editing) "Edit routine" else "New routine", style = Ph.heading.copy(fontSize = 24.sp), modifier = Modifier.weight(1f))
                Text("runs on this phone", style = Ph.monoSmall)
            }
            if (!editing) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                starters.forEach { s ->
                    Text(s.name, style = Ph.mono.copy(fontSize = 12.sp, color = Ph.TextSoft), modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).clickable {
                            name = s.name; prompt = s.prompt; pick(s.kind); minutes = s.minutes; every = s.every; quiet = s.kind != Kind.OVERNIGHT
                        }.padding(horizontal = 10.dp, vertical = 7.dp))
                }
            }
            Field("WHAT SHOULD SATURN DO", prompt, "e.g. Check my open PRs and CI, tell me what needs me in 3 lines", 3) { prompt = it }
            Field("NAME", name, prompt.split(' ').take(4).joinToString(" ").ifBlank { "Morning brief" }, 1) { name = it }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WHEN", style = Ph.label)
                Kind.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            val sel = k == kind
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) Ph.AmberWash else Ph.Ground)
                                .border(1.dp, if (sel) Ph.Amber else Ph.Line, RoundedCornerShape(14.dp)).clickable { pick(k) }
                                .padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(k.label, style = Ph.title.copy(fontSize = 14.sp, color = if (sel) Ph.Amber else Ph.Text))
                                Text(k.sub, style = Ph.monoSmall)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Ground).border(1.dp, Ph.Line, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (kind == Kind.EVERY) {
                        Text("Every", style = Ph.body.copy(fontSize = 14.sp, color = Ph.TextSoft), modifier = Modifier.weight(1f))
                        listOf(1, 2, 3, 4, 6).forEach { h ->
                            Text("${h}h", style = Ph.mono.copy(color = if (h == every) Ph.AmberInk else Ph.TextSoft), modifier = Modifier
                                .padding(start = 4.dp).clip(RoundedCornerShape(9.dp)).background(if (h == every) Ph.Amber else Color.Transparent)
                                .clickable { every = h }.padding(horizontal = 9.dp, vertical = 8.dp))
                        }
                    } else {
                        Text(if (kind == Kind.OVERNIGHT) "Starts after" else "Time", style = Ph.body.copy(fontSize = 14.sp, color = Ph.TextSoft),
                            modifier = Modifier.weight(1f))
                        Step("−") { minutes = (minutes - 30 + 1440) % 1440 }
                        Text(hm(minutes), style = Ph.title.copy(fontSize = 17.sp, color = Ph.Amber), modifier = Modifier.width(64.dp), textAlign = TextAlign.Center)
                        Step("+") { minutes = (minutes + 30) % 1440 }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PROJECT", style = Ph.label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects().forEach { (n, f) -> Pill(n, File(cwd).canonicalPath == f.canonicalPath) { cwd = f.path } }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("STOP A RUN IF IT COSTS MORE THAN", style = Ph.label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.10, 0.25, 0.50, 1.0).forEach { c -> Pill(String.format(Locale.US, "$%.2f", c), cap == c) { cap = c } }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TELL ME", style = Ph.label)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("only if something's new", quiet) { quiet = true }
                    Pill("every time", !quiet) { quiet = false }
                }
                if (quiet) Text("Runs with nothing new go straight to the archive, without a notification.", style = Ph.monoSmall)
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    kind == Kind.OVERNIGHT -> "Runs tonight after ${hm(minutes)}, once the phone is charging on Wi-Fi."
                    next != null -> "Next run " + java.text.SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(java.util.Date(next))
                    else -> ""
                }, style = Ph.monoSmall.copy(color = Ph.TextSoft), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (ready) Ph.Amber else Ph.Chip)
                    .clickable(enabled = ready) {
                        val nowMs = System.currentTimeMillis()
                        val id = job?.id?.takeIf { it.isNotEmpty() } ?: ("r" + java.lang.Long.toString(nowMs % 1_000_000_000L, 36))
                        val j = Scheduler.Job(id, name.ifBlank { prompt.split(' ').take(4).joinToString(" ") }, null, prompt.trim(), cwd, null,
                            cron, Scheduler.Cron(cron).next(nowMs), paused = job?.paused == true && editing, cap = cap, quiet = quiet,
                            overnight = kind == Kind.OVERNIGHT)
                        if (editing) Scheduler.update(j) else Scheduler.add(j)
                        onDismiss()
                    }, contentAlignment = Alignment.Center) {
                    Text(if (editing) "Save" else "Create routine", style = Ph.title.copy(fontSize = 15.sp, color = if (ready) Ph.AmberInk else Ph.Muted))
                }
            }
            Box(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, lines: Int, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Ph.label)
        BasicTextField(value, onValue, textStyle = Ph.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFFF3E6D0)),
            cursorBrush = SolidColor(Ph.Amber), minLines = lines, maxLines = if (lines > 1) 8 else 1,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ph.Ground)
                .border(1.dp, if (lines > 1) Ph.AmberLine else Ph.Line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = Ph.body.copy(fontSize = 15.sp, color = Ph.Muted)); inner() } })
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = Ph.mono.copy(color = if (on) Ph.Amber else Ph.TextSoft, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
        modifier = Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Ph.AmberWash else Color.Transparent)
            .border(1.dp, if (on) Ph.AmberLine else Ph.LineStrong, RoundedCornerShape(10.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp))
}

@Composable
private fun Step(label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Chip).clickable(onClickLabel = if (label == "+") "Later" else "Earlier") { onClick() },
        contentAlignment = Alignment.Center) { Text(label, style = Ph.title.copy(fontSize = 18.sp, color = Ph.TextSoft)) }
}

/** For Saturn's status line on the Routines page: "3 routines · next 8:00". */
fun routinesStatus(): String {
    val on = Scheduler.jobs.filter { !it.paused }
    if (Scheduler.jobs.isEmpty()) return "No routines yet"
    val next = on.filter { !it.overnight }.minByOrNull { it.next }
    val clock = next?.let { java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(it.next)) }
    return listOfNotNull("${Scheduler.jobs.size} ${if (Scheduler.jobs.size == 1) "routine" else "routines"}",
        clock?.let { "next $it" } ?: if (on.any { it.overnight }) "tonight" else if (on.isEmpty()) "all paused" else null).joinToString(" · ")
}

