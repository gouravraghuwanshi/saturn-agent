package io.github.pimobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.theme.Ph
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Rich cards for extension tools, chosen by the shape of what a tool reports (its `details`, or its text),
 * never by the extension's name, so a new extension that reports the same kind of thing gets the same card:
 *  - a crew: several workers, each with a name and a status (subagents, parallel jobs)
 *  - a checklist: items that are done or not (todo lists, plans), or "- [x]" lines in plain text
 *  - progress: "n of total" or a percentage
 */
object Shapes {
    enum class LaneState { WAITING, RUNNING, DONE, FAILED }
    class Lane(val name: String, val task: String, val state: LaneState, val now: String?, val steps: Int,
               val cost: Double, val ms: Long, val error: String?, val recent: List<String>)
    class Crew(val mode: String?, val lanes: List<Lane>) {
        val running get() = lanes.count { it.state == LaneState.RUNNING }
        val cost get() = lanes.sumOf { it.cost }
    }
    class Check(val text: String, val done: Boolean, val active: Boolean)
    class Progress(val fraction: Float, val label: String)

    private val NAME = listOf("agent", "name", "worker", "role")
    private val STATUS = listOf("status", "state")
    private val TEXT = listOf("content", "text", "title", "task", "description", "label", "step")

    private fun JSONObject.firstString(keys: List<String>) = keys.firstNotNullOfOrNull { k -> optString(k).takeIf { has(k) && it.isNotBlank() && it != "null" } }
    private fun objects(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** Every array of objects in [d], one level of nesting deep, with its key. */
    private fun arrays(d: JSONObject): List<Pair<String, List<JSONObject>>> {
        val out = mutableListOf<Pair<String, List<JSONObject>>>()
        fun scan(o: JSONObject, depth: Int) {
            o.keys().forEach { k ->
                when (val v = o.opt(k)) {
                    is JSONArray -> objects(v).takeIf { it.isNotEmpty() && it.size == v.length() }?.let { out += k to it }
                    is JSONObject -> if (depth < 1) scan(v, depth + 1)
                }
            }
        }
        scan(d, 0)
        return out
    }

    private fun laneState(o: JSONObject, result: JSONObject?): LaneState {
        val s = o.firstString(STATUS)?.lowercase()
        val exit = (result ?: o).takeIf { it.has("exitCode") }?.optInt("exitCode")
        return when {
            s in setOf("failed", "error", "errored", "cancelled", "canceled", "timeout") -> LaneState.FAILED
            s in setOf("completed", "complete", "done", "success", "succeeded", "ok", "finished") -> LaneState.DONE
            s in setOf("running", "in_progress", "active", "working", "detached", "started") -> LaneState.RUNNING
            s in setOf("pending", "queued", "waiting", "idle") -> LaneState.WAITING
            exit != null -> if (exit == 0) LaneState.DONE else LaneState.FAILED
            else -> LaneState.RUNNING
        }
    }

    fun crew(d: JSONObject?): Crew? {
        d ?: return null
        val all = arrays(d)
        fun isWorker(o: JSONObject) = o.firstString(NAME) != null && (STATUS.any { o.has(it) } || o.has("exitCode"))
        // Prefer live progress; fall back to final results. Others: any array of named workers with a status.
        val live = all.firstOrNull { it.first == "progress" && it.second.all(::isWorker) }?.second
        val results = all.firstOrNull { it.first == "results" && it.second.all { o -> o.firstString(NAME) != null } }?.second
        val base = live ?: results?.takeIf { r -> r.all(::isWorker) }
            ?: all.firstOrNull { (_, l) -> l.size >= 2 && l.all(::isWorker) }?.second ?: return null
        val lanes = base.mapIndexed { i, o ->
            val name = o.firstString(NAME)!!
            val res = results?.let { r -> r.getOrNull(o.optInt("index", i))?.takeIf { it.firstString(NAME) == name } ?: r.firstOrNull { it.firstString(NAME) == name && it.optString("task") == o.optString("task") } }
                ?.takeIf { it !== o }
            val now = o.optString("currentTool").takeIf { it.isNotBlank() }?.let { t -> (t + "  " + o.optString("currentToolArgs")).trim() }
                ?: o.optString("progressText").takeIf { it.isNotBlank() }
                ?: o.optJSONArray("recentTools")?.let { a -> a.optJSONObject(a.length() - 1)?.let { (it.optString("tool") + "  " + it.optString("args")).trim() } }
            val recent = o.optJSONArray("recentOutput")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.takeLast(3) } ?: emptyList()
            Lane(name, o.firstString(listOf("task", "description", "prompt", "title")).orEmpty(), laneState(o, res), now?.take(160),
                o.optInt("toolCount", o.optInt("steps", o.optInt("turnCount"))),
                (res ?: o).optJSONObject("usage")?.optDouble("cost")?.takeIf { !it.isNaN() } ?: o.optDouble("cost").takeIf { !it.isNaN() } ?: 0.0,
                o.optLong("durationMs"), (o.optString("error").ifBlank { res?.optString("error").orEmpty() }).takeIf { it.isNotBlank() }, recent)
        }
        return Crew(d.optString("mode").takeIf { it.isNotBlank() }, lanes)
    }

    /** [core]: one of pi's own file/shell tools, whose text is file contents (a TODO.md read isn't a live plan). */
    fun checklist(d: JSONObject?, text: String, core: Boolean = false): List<Check>? {
        d?.let { arrays(it) }?.forEach { (_, l) ->
            if (l.size < 2) return@forEach
            // Named workers with a status are a crew, not a plan.
            if (l.all { o -> o.firstString(NAME) != null && (STATUS.any { o.has(it) } || o.has("exitCode")) }) return@forEach
            val items = l.map { o ->
                val t = o.firstString(TEXT) ?: return@forEach
                val s = o.firstString(STATUS)?.lowercase()
                val done = listOf("done", "completed", "checked", "complete").any { o.optBoolean(it, false) } ||
                    s in setOf("done", "completed", "complete", "finished")
                val known = s != null || listOf("done", "completed", "checked", "complete").any { o.has(it) }
                if (!known) return@forEach
                Check(t, done, s in setOf("in_progress", "active", "doing", "running"))
            }
            return items
        }
        if (core) return null
        val lines = text.lines().mapNotNull { Regex("^\\s*(?:[-*]\\s*)?\\[([ xX✓])]\\s+(.+)").find(it) }
        if (lines.size >= 2) return lines.map { Check(it.groupValues[2].trim(), it.groupValues[1].isNotBlank(), false) }
        return null
    }

    fun progress(d: JSONObject?): Progress? {
        d ?: return null
        val label = d.firstString(listOf("message", "label", "stage", "phase")).orEmpty()
        val total = listOf("total", "of", "count").firstNotNullOfOrNull { k -> d.optDouble(k).takeIf { d.has(k) && !it.isNaN() && it > 0 } }
        val done = listOf("completed", "done", "current", "processed", "finished").firstNotNullOfOrNull { k -> d.optDouble(k).takeIf { d.has(k) && !it.isNaN() } }
        if (total != null && done != null) return Progress((done / total).toFloat().coerceIn(0f, 1f), "${fmt(done)} of ${fmt(total)}" + if (label.isNotBlank()) " · $label" else "")
        val pct = listOf("percent", "percentage", "progress").firstNotNullOfOrNull { k -> d.optDouble(k).takeIf { d.has(k) && !it.isNaN() } }
        if (pct != null) { val f = if (pct > 1.0) pct / 100 else pct; return Progress(f.toFloat().coerceIn(0f, 1f), "${(f * 100).toInt()}%" + if (label.isNotBlank()) " · $label" else "") }
        return null
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    /** The helpers working right now in this chat (for the moons around the header's Saturn). */
    fun liveHelpers(items: List<Item>): Int {
        val t = items.lastOrNull { it is Item.Tool && it.state == ToolState.RUNNING && it.details != null } as? Item.Tool ?: return 0
        return crew(t.details)?.running ?: 0
    }
}

fun durationLabel(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

// ------------------------------------------------------------------ the crew

/** Saturn's helpers as lanes: who, what they're doing right now, how far along. Tap one for its task and latest output. */
@Composable
fun CrewPanel(crew: Shapes.Crew, live: Boolean) {
    val chain = crew.mode == "chain"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Deep)
        .border(1.dp, if (live) Ph.AmberLine else Ph.Line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
        .animateContentSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            val n = crew.lanes.size
            Text(if (live) "Sent out $n ${if (n == 1) "helper" else "helpers"}" else "$n ${if (n == 1) "helper" else "helpers"} worked on this",
                style = Ph.title.copy(fontSize = 13.5.sp), modifier = Modifier.weight(1f))
            crew.mode?.let { m ->
                Text(when (m) { "parallel" -> "side by side"; "chain" -> "one after another"; "single" -> "solo"; else -> m },
                    style = Ph.monoSmall.copy(color = Ph.TextSoft), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Ph.Chip)
                        .padding(horizontal = 7.dp, vertical = 3.dp))
            }
            if (crew.cost >= 0.005) Text(String.format(Locale.US, "$%.2f", crew.cost), style = Ph.monoSmall)
        }
        crew.lanes.forEachIndexed { i, lane -> LaneRow(lane, first = i == 0, last = i == crew.lanes.lastIndex, chain = chain) }
    }
}

@Composable
private fun LaneRow(lane: Shapes.Lane, first: Boolean, last: Boolean, chain: Boolean) {
    var open by rememberSaveable(lane.name + lane.task.take(40)) { mutableStateOf(false) }
    val color = when (lane.state) {
        Shapes.LaneState.RUNNING -> Ph.Amber; Shapes.LaneState.DONE -> Ph.Green
        Shapes.LaneState.FAILED -> Ph.Red; Shapes.LaneState.WAITING -> Ph.Muted
    }
    var frame by remember { mutableIntStateOf(0) }
    if (lane.state == Shapes.LaneState.RUNNING) LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(90); frame++ } }
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).clickable { open = !open },
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The orbit line: lanes hang off one thread; in a chain, the work passes down it.
        Box(Modifier.width(16.dp).fillMaxHeight()) {
            val line = Ph.LineStrong
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val cx = size.width / 2; val dotY = 14.dp.toPx()
                if (!first) drawLine(line, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, dotY - 6.dp.toPx()), 1.dp.toPx())
                if (!last) drawLine(line, androidx.compose.ui.geometry.Offset(cx, dotY + 6.dp.toPx()), androidx.compose.ui.geometry.Offset(cx, size.height), 1.dp.toPx())
                if (chain && !last) drawLine(line, androidx.compose.ui.geometry.Offset(cx - 3.dp.toPx(), size.height - 5.dp.toPx()),
                    androidx.compose.ui.geometry.Offset(cx, size.height - 1.dp.toPx()), 1.dp.toPx())
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Text(when (lane.state) {
                    Shapes.LaneState.RUNNING -> "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[frame % 10].toString()
                    Shapes.LaneState.DONE -> "✓"; Shapes.LaneState.FAILED -> "✗"; Shapes.LaneState.WAITING -> "○"
                }, style = Ph.mono.copy(fontSize = 12.sp, color = color))
            }
        }
        Column(Modifier.weight(1f).padding(top = 5.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(lane.name, style = Ph.mono.copy(fontSize = 12.5.sp, color = if (lane.state == Shapes.LaneState.WAITING) Ph.Muted else Ph.Text))
                Text(listOfNotNull(lane.steps.takeIf { it > 0 }?.let { "$it ${if (it == 1) "step" else "steps"}" },
                    lane.ms.takeIf { it >= 1000 }?.let(::durationLabel)).joinToString(" · "),
                    style = Ph.monoSmall, modifier = Modifier.weight(1f), maxLines = 1)
            }
            val line = when (lane.state) {
                Shapes.LaneState.WAITING -> if (chain) "waiting for the one before…" else "waiting to start…"
                Shapes.LaneState.FAILED -> lane.error ?: "didn't finish"
                Shapes.LaneState.DONE -> lane.task.ifBlank { "done" }
                Shapes.LaneState.RUNNING -> lane.now ?: lane.task.ifBlank { "working…" }
            }
            Text(line, style = Ph.mono.copy(fontSize = 11.5.sp, color = if (lane.state == Shapes.LaneState.FAILED) Ph.Red else Ph.Muted),
                maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (lane.task.isNotBlank() && lane.state != Shapes.LaneState.DONE)
                        Text(lane.task, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.TextBody), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    lane.recent.forEach { Text("› $it", style = Ph.monoSmall.copy(color = Ph.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ checklist + progress

@Composable
fun ChecklistCard(items: List<Shapes.Check>, compact: Boolean = false) {
    val done = items.count { it.done }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Deep).border(1.dp, Ph.Line, RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Plan", style = Ph.title.copy(fontSize = 13.5.sp))
            Bar(done.toFloat() / items.size.coerceAtLeast(1), Modifier.weight(1f))
            Text("$done/${items.size}", style = Ph.monoSmall.copy(color = if (done == items.size) Ph.Green else Ph.TextSoft))
        }
        // Folded: just where things stand (the item being worked on, or the next one).
        val shown = if (compact) listOfNotNull(items.firstOrNull { it.active } ?: items.firstOrNull { !it.done }) else items
        shown.forEach { c ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.padding(top = 3.dp).size(13.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (c.done) Ph.Green.copy(alpha = 0.85f) else Color.Transparent)
                    .border(1.2.dp, when { c.done -> Ph.Green; c.active -> Ph.Amber; else -> Ph.LineStrong }, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center) {
                    if (c.done) Text("✓", style = Ph.monoSmall.copy(fontSize = 9.sp, color = Ph.Deep))
                }
                Text(c.text, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp,
                    color = when { c.done -> Ph.Muted; c.active -> Ph.Text; else -> Ph.TextSoft },
                    textDecoration = if (c.done) TextDecoration.LineThrough else null), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun ProgressLine(p: Shapes.Progress) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Bar(p.fraction, Modifier.weight(1f))
        Text(p.label, style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier) {
    val f by animateFloatAsState(fraction, tween(400), label = "bar")
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Line)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (f >= 0.999f) Ph.Green else Ph.Amber))
    }
}
