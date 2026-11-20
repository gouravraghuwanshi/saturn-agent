package io.github.pimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.runtime.Usage
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private enum class Span(val label: String, val days: Int?) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30), ALL("All", null) }

/**
 * Home → Usage: what the AI has cost and how many tokens, read from pi's own session files.
 * Live while open (rescans every few seconds; unchanged files come from cache).
 */
@Composable
fun UsageScreen(onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val replies by produceState<List<Usage.Reply>?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { Usage.scan(UiStore.app) }.getOrDefault(value ?: emptyList()) }
            delay(4_000)
        }
    }
    var span by rememberSaveable { mutableStateOf(Span.WEEK) }

    Column(Modifier.fillMaxSize().background(Ph.Ground).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Back") { onBack() }, contentAlignment = Alignment.Center) {
                PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back")
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Usage", style = Ph.title.copy(fontSize = 18.sp))
                Text("from pi's session files · on this phone", style = Ph.monoSmall, maxLines = 1)
            }
            val live = UiStore.tasks.count { it.status == Status.RUNNING || it.status == Status.STARTING }
            if (live > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PulseDot(Ph.Amber, 6); Text("live · $live running", style = Ph.monoSmall.copy(color = Ph.Amber))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Span.entries.forEach { r ->
                    val on = r == span
                    Text(r.label, style = Ph.mono.copy(color = if (on) Ph.Amber else Ph.Muted),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ph.AmberWash else Ph.Surface)
                            .border(1.dp, if (on) Ph.AmberLine else Ph.Line, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "Show ${r.label}") { span = r }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            val all = replies
            if (all == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Ph.Amber); Text("Reading your sessions…", style = Ph.body.copy(color = Ph.Muted))
            } else Report(all, span, onOpenTask)
            Box(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun Report(all: List<Usage.Reply>, span: Span, onOpenTask: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val today = LocalDate.now(zone)
    fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    fun startOf(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
    val from = span.days?.let { startOf(today.minusDays(it - 1L)) } ?: 0L
    val list = all.filter { it.at >= from }
    // The same stretch just before, to say whether it's going up or down. Today compares with yesterday up to this time.
    val previous = when (span) {
        Span.ALL -> null
        Span.TODAY -> all.filter { it.at >= startOf(today.minusDays(1)) && it.at < now - 86_400_000L }
        else -> all.filter { it.at >= startOf(today.minusDays(2L * span.days!! - 1)) && it.at < from }
    }
    // Subscriptions report $0: then everything is measured in tokens instead.
    val money = all.any { it.cost > 0 }
    fun value(r: List<Usage.Reply>) = if (money) r.sumOf { it.cost } else r.sumOf { it.tokens }.toDouble()
    fun fmt(v: Double) = if (money) dollars(v) else compact(v.toLong()) + " tok"

    // ---- the total, glowing
    val total = value(list)
    val shown by animateFloatAsState(total.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "total")
    Column(Modifier.fillMaxWidth().drawBehind { saturnGlow(Offset(size.width * 0.18f, size.height * 0.45f), 0.7f, 1f) }.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(fmt(shown.toDouble()), style = Ph.heading.copy(fontSize = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(
                if (money) "${compact(list.sumOf { it.tokens })} tokens" else null,
                "${list.size} ${if (list.size == 1) "reply" else "replies"}",
                if (money && list.isNotEmpty()) "${dollars(total / list.size)} each" else null,
            ).joinToString(" · "), style = Ph.mono.copy(color = Ph.TextSoft))
        }
        previous?.let { prev ->
            val before = value(prev)
            if (before > 0 || total > 0) {
                val up = total >= before
                val pct = if (before > 0) ((total - before) / before * 100).toInt() else null
                val label = when {
                    pct == null -> "new · nothing ${if (span == Span.TODAY) "yesterday by now" else "the ${span.label} before"}"
                    abs(pct) < 1 -> "same as ${if (span == Span.TODAY) "yesterday by now" else "the ${span.label} before"}"
                    else -> "${if (up) "↑" else "↓"} ${abs(pct)}% vs ${if (span == Span.TODAY) "yesterday by now" else "the ${span.label} before"}"
                }
                Text(label, style = Ph.monoSmall.copy(color = if (pct == null || abs(pct) < 1) Ph.Muted else if (up) Ph.Amber else Ph.Green),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
    }

    // ---- this month and where it's heading
    if (money) {
        val monthStart = startOf(today.withDayOfMonth(1))
        val soFar = all.filter { it.at >= monthStart }.sumOf { it.cost }
        val pace = soFar / today.dayOfMonth * today.lengthOfMonth()
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())).uppercase(), style = Ph.label)
                    Text(dollars(soFar), style = Ph.title.copy(fontSize = 20.sp))
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("ON PACE FOR", style = Ph.label)
                    Text(dollars(pace), style = Ph.title.copy(fontSize = 20.sp, color = Ph.TextSoft))
                }
            }
            Bar(today.dayOfMonth.toFloat() / today.lengthOfMonth(), Modifier.padding(top = 12.dp))
            Text("day ${today.dayOfMonth} of ${today.lengthOfMonth()}", style = Ph.monoSmall, modifier = Modifier.padding(top = 6.dp))
        }
    }

    if (list.isEmpty()) { Text("Nothing in this range yet.", style = Ph.body.copy(color = Ph.Muted)); return }

    // ---- over time: hours for today, days otherwise. Tap a bar to read it.
    val buckets: List<Pair<String, List<Usage.Reply>>> = if (span == Span.TODAY) {
        val byHour = list.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).hour }
        (0..23).map { h -> String.format(Locale.US, "%02d:00", h) to byHour[h].orEmpty() }
    } else {
        val days = span.days ?: (today.toEpochDay() - dayOf(all.first().at).toEpochDay() + 1).toInt().coerceIn(1, 60)
        val byDay = list.groupBy { dayOf(it.at) }
        (days - 1 downTo 0).map { d -> today.minusDays(d.toLong()).let { day ->
            (if (d == 0) "today" else day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))) to byDay[day].orEmpty() } }
    }
    Section(if (span == Span.TODAY) "By hour" else "By day")
    Card {
        var picked by remember(span) { mutableStateOf(buckets.indexOfLast { it.second.isNotEmpty() }.coerceAtLeast(0)) }
        val sel = buckets.getOrNull(picked)
        AnimatedContent(picked, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "pick") { i ->
            val b = buckets.getOrNull(i)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(b?.first ?: "", style = Ph.mono.copy(color = Ph.TextSoft), modifier = Modifier.weight(1f))
                Text(b?.let { fmt(value(it.second)) } ?: "", style = Ph.title.copy(fontSize = 16.sp))
            }
        }
        Bars(buckets.map { value(it.second) }, picked, key = span, Modifier.padding(top = 12.dp)) { picked = it }
        Row(Modifier.padding(top = 6.dp)) {
            Text(buckets.first().first, style = Ph.monoSmall, modifier = Modifier.weight(1f))
            Text(if (sel != null && sel.second.isNotEmpty()) "${sel.second.size} replies" else "", style = Ph.monoSmall)
            Text(buckets.last().first, style = Ph.monoSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }

    // ---- what the tokens were
    val input = list.sumOf { it.input }; val output = list.sumOf { it.output }
    val cacheRead = list.sumOf { it.cacheRead }; val cacheWrite = list.sumOf { it.cacheWrite }
    val mixTotal = (input + output + cacheRead + cacheWrite).toDouble()
    if (mixTotal > 0) {
        Section("Tokens")
        Card {
            val parts = listOf(
                Triple("Read (input)", input, Ph.Amber), Triple("Written (output)", output, Ph.Green),
                Triple("From cache", cacheRead, Ph.Cyan), Triple("Into cache", cacheWrite, Ph.Muted),
            ).filter { it.second > 0 }
            val grow = remember(span) { Animatable(0f) }
            LaunchedEffect(span) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
            Canvas(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Ph.Chip)) {
                var x = 0f
                parts.forEach { (_, n, c) ->
                    val w = (size.width * (n / mixTotal).toFloat() * grow.value)
                    drawRect(c, topLeft = Offset(x, 0f), size = Size((w - 2.dp.toPx()).coerceAtLeast(1f), size.height)); x += w
                }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parts.forEach { (name, n, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                        Text(name, style = Ph.body.copy(fontSize = 13.sp, color = Ph.TextSoft), modifier = Modifier.weight(1f))
                        Text(compact(n), style = Ph.mono.copy(color = Ph.Text))
                        Text("${(n / mixTotal * 100).toInt()}%", style = Ph.monoSmall, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                    }
                }
            }
            // Cache reads are billed far below fresh input: estimate the difference, per model.
            val saved = list.groupBy { it.provider to it.model }.values.sumOf { r ->
                val inTok = r.sumOf { it.input }; val crTok = r.sumOf { it.cacheRead }
                if (inTok == 0L || crTok == 0L) 0.0
                else (crTok * (r.sumOf { it.inputCost } / inTok - r.sumOf { it.cacheReadCost } / crTok)).coerceAtLeast(0.0)
            }
            if (saved >= 0.01) Text("Caching saved you about ${dollars(saved)} here.",
                style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Cyan), modifier = Modifier.padding(top = 12.dp))
            else if (cacheRead > 0 && !money) Text("${(cacheRead / mixTotal * 100).toInt()}% came from cache: faster replies, less of your limit.",
                style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Cyan), modifier = Modifier.padding(top = 12.dp))
        }
    }

    // ---- by model
    Section("By model")
    val models = list.groupBy { it.provider to it.model }.map { (k, r) -> Triple(k, value(r), r) }.sortedByDescending { it.second }
    val maxModel = models.maxOf { it.second }.coerceAtLeast(1e-9)
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            models.take(8).forEach { (k, v, r) ->
                Share(k.second.ifBlank { "unknown" }, listOfNotNull(k.first.ifBlank { null }, "${r.size} replies",
                    if (money) "${compact(r.sumOf { it.tokens })} tok" else null).joinToString(" · "), fmt(v), v / maxModel)
            }
        }
    }
    if (models.any { (_, _, r) -> r.all { it.cost == 0.0 } && r.any { it.tokens > 0 } })
        Text("Subscriptions (ChatGPT, Claude, Copilot…) show tokens, not dollars: you pay those a flat fee.",
            style = Ph.body.copy(fontSize = 12.sp, color = Ph.Muted, lineHeight = 17.sp))

    // ---- by project
    val names = remember(UiStore.tasks.size) { projectNames() }
    val projects = list.groupBy { names[it.folder] ?: "Elsewhere" }.map { (n, r) -> Triple(n, value(r), r.map { it.sessionId }.toSet().size) }
        .sortedByDescending { it.second }
    if (projects.size > 1 || projects.firstOrNull()?.first != "Elsewhere") {
        Section("By project")
        val maxP = projects.maxOf { it.second }.coerceAtLeast(1e-9)
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                projects.take(6).forEach { (n, v, sessions) -> Share(n, "$sessions ${if (sessions == 1) "session" else "sessions"}", fmt(v), v / maxP) }
            }
        }
    }

    // ---- top tasks: tap to open
    val tasks = UiStore.tasks.associateBy { it.task.sessionId }
    val top = list.groupBy { it.sessionId }.map { (s, r) -> Triple(s, value(r), r.size) }.sortedByDescending { it.second }.take(6)
    if (top.isNotEmpty()) {
        Section("Top tasks")
        val maxT = top.maxOf { it.second }.coerceAtLeast(1e-9)
        Card {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                top.forEach { (s, v, n) ->
                    val t = tasks[s]
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).then(if (t != null) Modifier.clickable(onClickLabel = "Open task") { onOpenTask(t.task.id) } else Modifier)
                        .padding(vertical = 4.dp)) {
                        Share(t?.let { "#${it.task.number} ${it.title}" } ?: "Earlier session", "$n replies" + if (t != null) " · open" else "", fmt(v), v / maxT)
                    }
                }
            }
        }
    }
    val failed = list.count { it.failed }
    if (failed > 0) Text("$failed ${if (failed == 1) "reply" else "replies"} ended in an error (those usually cost nothing).",
        style = Ph.body.copy(fontSize = 12.sp, color = Ph.Muted))
}

/** pi's session folder → project name, for every project folder and every task's working directory. */
private fun projectNames(): Map<String, String> {
    val rt = UiStore.runtime
    val out = HashMap<String, String>()
    out[Usage.folderFor(rt.projects)] = "General"
    rt.projects.listFiles { f -> f.isDirectory }.orEmpty().forEach { out[Usage.folderFor(it)] = it.name }
    UiStore.tasks.forEach { t -> out.getOrPut(Usage.folderFor(t.task.cwd)) {
        rt.projectName(t.task.cwd).let { if (it == io.github.pimobile.runtime.PiRuntime.GENERAL) "General" else it } } }
    return out
}

@Composable
private fun Section(title: String) = Text(title.uppercase(), style = Ph.label, modifier = Modifier.padding(top = 4.dp, start = 2.dp))

@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape).padding(16.dp)) { content() }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(fraction) { fill.animateTo(fraction.coerceIn(0.02f, 1f), tween(600, easing = FastOutSlowInEasing)) }
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Chip)) {
        Box(Modifier.fillMaxWidth(fill.value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Amber))
    }
}

@Composable
private fun Bars(values: List<Double>, picked: Int, key: Any, modifier: Modifier, onPick: (Int) -> Unit) {
    val grow = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val max = values.max().coerceAtLeast(1e-9)
    val amber = Ph.Amber; val empty = Ph.Chip
    val view = androidx.compose.ui.platform.LocalView.current
    Canvas(modifier.fillMaxWidth().height(120.dp).pointerInput(values.size) {
        detectTapGestures { p ->
            val i = (p.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onPick(i)
        }
    }) {
        val gap = if (values.size > 14) 3.dp.toPx() else 6.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = if (v <= 0) 3.dp.toPx() else (size.height * (v / max).toFloat() * grow.value).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (v <= 0) empty else amber.copy(alpha = if (i == picked) 1f else 0.45f),
                topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(minOf(w / 2, 4.dp.toPx())))
        }
    }
}

@Composable
private fun Share(title: String, sub: String, value: String, share: Double) {
    Column(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Ph.title.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = Ph.mono.copy(color = Ph.Text, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 10.dp))
        }
        Bar(share.toFloat())
    }
}

private fun dollars(v: Double) = when {
    v == 0.0 -> "$0"
    v < 0.01 -> "<$0.01"
    v >= 1000 -> String.format(Locale.US, "$%,.0f", v)
    else -> String.format(Locale.US, "$%.2f", v)
}

private fun compact(n: Long) = when {
    n >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", n / 1e9)
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
    else -> n.toString()
}

