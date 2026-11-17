package io.github.pimobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.theme.Ph
import org.json.JSONObject

// ------------------------------------------------------------------ motion

@Composable
fun PulseDot(color: Color, size: Int = 7) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size.dp).alpha(a).clip(CircleShape).background(color))
}

/** The thin amber line sweeping along the bottom of a running card. */
@Composable
fun SweepBar(color: Color = Ph.Amber, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sweep")
    val x by t.animateFloat(-0.4f, 1.1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.fillMaxWidth().height(2.dp).background(Ph.Chip)) {
        Box(
            Modifier.width(maxWidth * 0.4f).height(2.dp)
                .graphicsLayer { translationX = x * constraints.maxWidth }
                .background(color)
        )
    }
}

@Composable
fun BlinkCursor(color: Color = Ph.Amber) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = { if (it < 0.5f) 0f else 1f })), label = "c")
    Text("▍", style = Ph.mono.copy(color = color), modifier = Modifier.alpha(1f - a))
}

// ------------------------------------------------------------------ status

@Composable
fun StatusBadge(status: Status, hasError: Boolean, browsing: Boolean = false) {
    if (browsing && status == Status.RUNNING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Ph.Cyan); Text(" browsing", style = Ph.monoSmall.copy(color = Ph.Cyan))
        }
        return
    }
    when (status) {
        Status.STARTING, Status.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(Ph.Amber)
            Text(if (status == Status.STARTING) " starting" else " running", style = Ph.monoSmall.copy(color = Ph.Amber))
        }
        Status.NEEDS_INPUT -> Text(
            "needs you",
            style = Ph.monoSmall.copy(color = Ph.AmberInk, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Ph.Amber).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Status.FAILED -> Text("✗ failed", style = Ph.monoSmall.copy(color = Ph.Red))
        Status.IDLE -> Text(if (hasError) "✗ error" else "✓ done", style = Ph.monoSmall.copy(color = if (hasError) Ph.Red else Ph.Green))
        Status.STOPPED -> Text("paused", style = Ph.monoSmall)
    }
}

// ---------------------------------------------------------------- markdown

/** Small, fast Markdown: paragraphs, headings, bullets, fenced code, **bold**, `code`. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val lines = text.lines()
        var i = 0
        val para = StringBuilder()
        @Composable fun flush() {
            if (para.isNotBlank()) Text(inline(para.toString().trim()), style = Ph.body)
            para.clear()
        }
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.trimStart().startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    CodeBlock(code.toString().trimEnd())
                }
                l.startsWith("#") -> {
                    flush()
                    Text(inline(l.trimStart('#', ' ')), style = Ph.title)
                }
                Regex("^\\s*([-*]|\\d+\\.)\\s+").containsMatchIn(l) -> {
                    flush()
                    val m = Regex("^(\\s*)([-*]|\\d+\\.)\\s+(.*)").find(l)!!
                    val bullet = m.groupValues[2].let { if (it == "-" || it == "*") "•" else it }
                    Row(Modifier.padding(start = (m.groupValues[1].length * 6).dp)) {
                        Text(bullet, style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.width(20.dp))
                        Text(inline(m.groupValues[3]), style = Ph.body)
                    }
                }
                l.isBlank() -> flush()
                else -> para.append(l).append(' ')
            }
            i++
        }
        flush()
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> {
                val end = s.indexOf("**", i + 2)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Ph.Text)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> {
                val end = s.indexOf('`', i + 1)
                withStyle(SpanStyle(fontFamily = Ph.Mono, fontSize = 13.sp, color = Ph.Text, background = Ph.Chip)) {
                    append(s.substring(i + 1, end))
                }
                i = end + 1
            }
            else -> { append(s[i]); i++ }
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Deep)
            .border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState()).padding(12.dp)
    ) { Text(code, style = Ph.mono.copy(color = Ph.TextSoft, fontSize = 11.5.sp, lineHeight = 18.sp), softWrap = false) }
}

// ------------------------------------------------------------------- tools

/** The most useful argument of a tool call, for one-line summaries. */
fun toolTarget(name: String, args: JSONObject): String =
    listOf("path", "command", "pattern", "url", "query").firstNotNullOfOrNull { args.optString(it).ifBlank { null } }
        ?.lineSequence()?.first() ?: args.keys().asSequence().firstOrNull()?.let { args.optString(it) }.orEmpty()

/** (+added, -removed) line counts for edit/write calls. */
fun diffStats(t: Item.Tool): Pair<Int, Int>? = when (t.name) {
    "edit" -> t.args.optJSONArray("edits")?.let { a ->
        var add = 0; var del = 0
        for (i in 0 until a.length()) a.optJSONObject(i)?.let {
            add += it.optString("newText").lines().size; del += it.optString("oldText").lines().size
        }
        add to del
    }
    "write" -> t.args.optString("content").lines().size to 0
    else -> null
}

private fun diffPreview(t: Item.Tool): List<Pair<Char, String>> {
    val out = mutableListOf<Pair<Char, String>>()
    when (t.name) {
        "edit" -> t.args.optJSONArray("edits")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { e ->
                e.optString("oldText").lines().forEach { out += '-' to it }
                e.optString("newText").lines().forEach { out += '+' to it }
            }
        }
        "write" -> t.args.optString("content").lines().forEach { out += '+' to it }
    }
    return out
}

/** One tool call inside a tool panel; tap to expand output or diff. */
@Composable
fun ToolRow(t: Item.Tool, divider: Boolean) {
    var open by rememberSaveable(t.key) { mutableStateOf(false) }
    val (icon, iconColor) = when (t.state) {
        ToolState.RUNNING -> "⚙" to Ph.Amber
        ToolState.DONE -> "✓" to Ph.Cyan
        ToolState.ERROR -> "✗" to Ph.Red
    }
    Column(
        Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(icon, style = Ph.mono.copy(color = iconColor))
            Text(t.name, style = Ph.mono.copy(color = Ph.Text))
            Text(toolTarget(t.name, t.args), style = Ph.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            diffStats(t)?.let { (a, d) ->
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = Ph.Green)) { append("+$a") }
                    if (d > 0) withStyle(SpanStyle(color = Ph.Red)) { append(" −$d") }
                }, style = Ph.mono)
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            val diff = remember(t.key) { diffPreview(t) }
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).horizontalScroll(rememberScrollState())) {
                if (diff.isNotEmpty()) diff.take(40).forEach { (k, l) ->
                    val c = if (k == '+') Ph.Green else Ph.Red
                    Text("$k $l", softWrap = false, style = Ph.mono.copy(color = c, fontSize = 11.5.sp, lineHeight = 18.sp),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = 0.08f)).padding(horizontal = 6.dp))
                } else Text(t.output.ifBlank { if (t.state == ToolState.RUNNING) "running…" else "(no output)" }.lines().takeLast(24).joinToString("\n"),
                    softWrap = false, style = Ph.mono.copy(color = Ph.Muted, fontSize = 11.5.sp, lineHeight = 17.sp))
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Ph.Chip))
    }
}

/** A run of consecutive tool calls drawn as one bordered panel. */
@Composable
fun ToolPanel(tools: List<Item.Tool>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF111318))
            .border(1.dp, Ph.Line, RoundedCornerShape(14.dp))
    ) { tools.forEachIndexed { i, t -> ToolRow(t, divider = i < tools.lastIndex) } }
}
