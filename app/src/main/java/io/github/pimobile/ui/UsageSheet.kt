package io.github.pimobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.Usage
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private enum class Range(val label: String, val days: Int?) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30), ALL("All", null) }

/** Settings → Usage: what you've spent and how many tokens, by day, model and task. */
@Composable
fun UsageSheet(onDismiss: () -> Unit) {
    var replies by remember { mutableStateOf<List<Usage.Reply>?>(null) }
    var range by remember { mutableStateOf(Range.WEEK) }
    LaunchedEffect(Unit) { replies = withContext(Dispatchers.IO) { runCatching { Usage.scan(UiStore.app) }.getOrDefault(emptyList()) } }

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Usage", style = Ph.heading.copy(fontSize = 26.sp), modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Range.entries.forEach { r ->
                    val on = r == range
                    Text(r.label, style = Ph.mono.copy(color = if (on) Ph.Amber else Ph.Muted),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ph.AmberWash else Ph.Surface)
                            .border(1.dp, if (on) Ph.AmberLine else Ph.Line, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = "Show ${r.label}") { range = r }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            val all = replies
            if (all == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(Ph.Amber); Text("Reading your sessions…", style = Ph.body.copy(color = Ph.Muted))
            } else Content(all, range)
            Box(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun Content(all: List<Usage.Reply>, range: Range) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val from = range.days?.let { today.minusDays(it - 1L).atStartOfDay(zone).toInstant().toEpochMilli() } ?: 0L
    val list = all.filter { it.at >= from }
    val cost = list.sumOf { it.cost }
    val tokens = list.sumOf { it.tokens }

    // Total
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(money(cost), style = Ph.heading.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp))
        Text("${compact(tokens)} tokens · ${list.size} ${if (list.size == 1) "reply" else "replies"}", style = Ph.mono.copy(color = Ph.TextSoft))
    }
    if (list.isEmpty()) {
        Text("Nothing yet in this range.", style = Ph.body.copy(color = Ph.Muted)); return
    }

    // Per day (not for "Today")
    val days = when (range) { Range.TODAY -> 0; Range.ALL -> minOf(30, (today.toEpochDay() - java.time.Instant.ofEpochMilli(all.first().at).atZone(zone).toLocalDate().toEpochDay() + 1).toInt()); else -> range.days!! }
    if (days > 1) {
        val byDay = list.groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
        val values = (days - 1 downTo 0).map { d -> today.minusDays(d.toLong()).let { day -> byDay[day].orEmpty().let { r -> r.sumOf { it.cost } to r.sumOf { it.tokens } } } }
        // Subscriptions report $0: chart tokens then, so the bars still mean something.
        val useCost = values.any { it.first > 0 }
        Bars(values.map { if (useCost) it.first else it.second.toDouble() }, key = range)
        Row {
            Text(today.minusDays(days - 1L).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), style = Ph.monoSmall, modifier = Modifier.weight(1f))
            Text(if (useCost) "cost per day" else "tokens per day", style = Ph.monoSmall)
            Text("today", style = Ph.monoSmall, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }

    // By model
    Section("By model")
    val models = list.groupBy { it.provider to it.model }.map { (k, r) -> Triple(k, r.sumOf { it.cost }, r.sumOf { it.tokens }) }
        .sortedWith(compareByDescending<Triple<Pair<String, String>, Double, Long>> { it.second }.thenByDescending { it.third })
    val maxModel = models.maxOf { if (cost > 0) it.second else it.third.toDouble() }.coerceAtLeast(1e-9)
    models.take(8).forEach { (k, c, t) ->
        ShareRow(k.second, k.first, if (c > 0) money(c) else "${compact(t)} tok", (if (cost > 0) c else t.toDouble()) / maxModel,
            sub2 = if (c > 0) "${compact(t)} tokens" else null)
    }
    if (models.any { it.second == 0.0 && it.third > 0 })
        Text("Subscriptions (ChatGPT, Claude, Copilot…) show tokens instead of dollars: you pay them a flat fee.",
            style = Ph.body.copy(fontSize = 12.sp, color = Ph.Muted, lineHeight = 17.sp))

    // By task
    val titles = UiStore.tasks.associate { it.task.sessionId to "#${it.task.number} ${it.title}" }
    val tasks = list.groupBy { it.sessionId }.map { (s, r) -> Triple(titles[s] ?: "Earlier session", r.sumOf { it.cost }, r.sumOf { it.tokens }) }
        .sortedWith(compareByDescending<Triple<String, Double, Long>> { it.second }.thenByDescending { it.third }).take(6)
    if (tasks.isNotEmpty()) {
        Section("Top tasks")
        val maxTask = tasks.maxOf { if (cost > 0) it.second else it.third.toDouble() }.coerceAtLeast(1e-9)
        tasks.forEach { (title, c, t) -> ShareRow(title, null, if (c > 0) money(c) else "${compact(t)} tok", (if (cost > 0) c else t.toDouble()) / maxTask) }
    }
}

@Composable
private fun Section(title: String) = Text(title.uppercase(), style = Ph.label, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Bars(values: List<Double>, key: Any) {
    val grow = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val max = values.max().coerceAtLeast(1e-9)
    val amber = Ph.Amber; val empty = Ph.Chip
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val gap = if (values.size > 14) 3.dp.toPx() else 6.dp.toPx()
        val w = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = if (v <= 0) 3.dp.toPx() else (size.height * (v / max).toFloat() * grow.value).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (v <= 0) empty else amber.copy(alpha = if (i == values.lastIndex) 1f else 0.7f),
                topLeft = Offset(i * (w + gap), size.height - h), size = Size(w, h), cornerRadius = CornerRadius(minOf(w / 2, 4.dp.toPx())))
        }
    }
}

@Composable
private fun ShareRow(title: String, sub: String?, value: String, share: Double, sub2: String? = null) {
    val fill = remember(title, share) { Animatable(0f) }
    LaunchedEffect(title, share) { fill.animateTo(share.toFloat().coerceIn(0.02f, 1f), tween(600, easing = FastOutSlowInEasing)) }
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Ph.title.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(sub, sub2).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = Ph.monoSmall) }
            }
            Text(value, style = Ph.mono.copy(color = Ph.Text, fontWeight = FontWeight.SemiBold))
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Chip)) {
            Box(Modifier.fillMaxWidth(fill.value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Amber))
        }
    }
}

private fun money(v: Double) = when {
    v == 0.0 -> "$0"
    v < 0.01 -> "<$0.01"
    else -> String.format(Locale.US, "$%.2f", v)
}

private fun compact(n: Long) = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1e3)
    else -> n.toString()
}
