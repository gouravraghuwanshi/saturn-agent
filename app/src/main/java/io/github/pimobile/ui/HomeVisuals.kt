package io.github.pimobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.components.toolTarget
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.theme.Ph
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val AmberLight = Color(0xFFFFD89A)
private val AmberDeep = Color(0xFFA86A1C)

/** The small Saturn mark: planet and tilted ring. */
@Composable
fun SaturnMark(modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        drawCircle(Ph.Amber, radius = size.minDimension * 5f / 24f, center = c)
        rotate(-20f, c) {
            drawOval(Ph.Amber, topLeft = Offset(c.x - size.width * 10.5f / 24f, c.y - size.height * 3.6f / 24f),
                size = Size(size.width * 21f / 24f, size.height * 7.2f / 24f), style = Stroke(width = size.minDimension / 16f))
        }
    }
}

/**
 * The top of the home screen: Saturn turning slowly, each working task a moon on
 * its ring (a task that needs you pings white). Tap a moon to open it, the planet
 * for Saturn's menu.
 */
@Composable
fun OrbitHero(active: List<TaskUi>, total: Int, onOpen: (TaskUi) -> Unit, onPlanet: () -> Unit, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "orbit")
    val turn by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "turn")
    val glow by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val ping by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "ping")
    val moons = active.take(6)
    val spots = remember { ArrayList<Pair<Offset, TaskUi>>() }
    val working = active.count { it.status != Status.NEEDS_INPUT }
    val waiting = active.size - working

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(190.dp).pointerInput(moons) {
            detectTapGestures { p ->
                val hit = synchronized(spots) { spots.minByOrNull { (it.first - p).getDistance() } }
                if (hit != null && (hit.first - p).getDistance() < 30.dp.toPx()) onOpen(hit.second)
                else if ((p - Offset(size.width / 2f, size.height / 2f)).getDistance() < 80.dp.toPx()) onPlanet()
            }
        }) {
            val c = center
            val rx = min(size.width * 0.4f, 150.dp.toPx())
            val ry = rx * 0.27f
            val tilt = -14f
            val rad = tilt * PI / 180
            fun place(a: Double): Offset {
                val x = rx * cos(a); val y = ry * sin(a)
                return Offset((c.x + x * cos(rad) - y * sin(rad)).toFloat(), (c.y + x * sin(rad) + y * cos(rad)).toFloat())
            }
            val placed = moons.mapIndexed { i, t ->
                val a = 2 * PI * (turn + i.toDouble() / moons.size.coerceAtLeast(1))
                Triple(place(a), sin(a) < 0, t) // sin < 0: the far side, behind the planet
            }
            synchronized(spots) { spots.clear(); placed.forEach { spots += it.first to it.third } }

            // Rings (whole), the far moons, the planet, then the near half of the ring and the near moons.
            rotate(tilt, c) {
                drawOval(Ph.Amber.copy(alpha = 0.28f), Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(1.2.dp.toPx()))
                drawOval(Ph.Amber.copy(alpha = 0.12f), Offset(c.x - rx * 0.79f, c.y - ry * 0.77f), Size(rx * 1.58f, ry * 1.54f), style = Stroke(1.dp.toPx()))
            }
            placed.filter { it.second }.forEach { moon(it.first, it.third, ping) }
            val pr = 40.dp.toPx()
            val halo = pr * 2.6f
            drawCircle(Brush.radialGradient(listOf(Ph.Amber.copy(alpha = 0.16f + 0.14f * glow), Color.Transparent), center = c, radius = halo), halo, c)
            drawCircle(Brush.radialGradient(listOf(AmberLight, Ph.Amber, AmberDeep), center = c + Offset(-pr * 0.35f, -pr * 0.45f), radius = pr * 1.7f), pr, c)
            rotate(tilt, c) {
                drawArc(Ph.Amber.copy(alpha = 0.55f), 0f, 180f, false, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(1.4.dp.toPx()))
            }
            placed.filterNot { it.second }.forEach { moon(it.first, it.third, ping) }
        }
        Text(
            when {
                active.isEmpty() && total == 0 -> "Ask below to start a task"
                active.isEmpty() -> "All quiet · $total ${if (total == 1) "task" else "tasks"}"
                else -> listOfNotNull(working.takeIf { it > 0 }?.let { "$it working" }, waiting.takeIf { it > 0 }?.let { "$it ${if (it == 1) "needs" else "need"} you" }).joinToString(" · ")
            },
            style = Ph.mono.copy(color = if (waiting > 0) Ph.Text else Ph.TextSoft), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
    }
}

private fun DrawScope.moon(at: Offset, t: TaskUi, ping: Float) {
    if (t.status == Status.NEEDS_INPUT) {
        drawCircle(Ph.Text.copy(alpha = (1f - ping) * 0.9f), radius = 6.dp.toPx() * (1f + 1.6f * ping), center = at, style = Stroke(1.5.dp.toPx()))
        drawCircle(Ph.Text, radius = 4.5.dp.toPx(), center = at)
    } else {
        drawCircle(Brush.radialGradient(listOf(Ph.Amber.copy(alpha = 0.55f), Color.Transparent), center = at, radius = 14.dp.toPx()), 14.dp.toPx(), at)
        drawCircle(AmberLight, radius = 6.dp.toPx(), center = at)
    }
}

/**
 * A running task: light travels round the card's edge, liquid flows along its
 * foot, and the current step fades in as it changes.
 */
@Composable
fun LiquidCard(t: TaskUi, leading: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "liquid")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3_600, easing = LinearEasing)), label = "spin")
    val flow by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "flow")
    val outer = RoundedCornerShape(22.dp)
    val inner = RoundedCornerShape(20.5.dp)
    val sweep = Brush.sweepGradient(0f to Color.Transparent, 0.14f to Ph.Amber, 0.22f to AmberLight, 0.39f to Color.Transparent, 1f to Color.Transparent)

    Box(modifier.fillMaxWidth().clip(outer).background(Ph.LineStrong)
        .drawBehind { rotate(spin) { drawCircle(sweep, radius = size.maxDimension, center = center) } }
        .padding(1.5.dp)) {
        Column(Modifier.fillMaxWidth().clip(inner).background(if (Ph.amoled) Color(0xFF0A0A0B) else Color(0xFF121317))) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                PulseDot(Ph.Amber, 7)
                Text(t.title, style = Ph.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                elapsed(t.runStartedAt).takeIf { it.isNotEmpty() }?.let { Text(it, style = Ph.monoSmall.copy(color = Ph.Amber)) }
            }
            val page = if (t.browsing) io.github.pimobile.browser.BrowserManager.pages[t.task.id] else null
            val tool = t.tools.lastOrNull()
            val step = when {
                page != null -> "◉ " + page.url.removePrefix("https://").takeIf { !it.startsWith("data:") }.orEmpty()
                tool != null && tool.state == ToolState.RUNNING -> "${tool.name}  ${toolTarget(tool.name, tool.args)}"
                t.status == Status.STARTING -> "starting Saturn…"
                else -> (t.items.lastOrNull { it is Item.Assistant } as? Item.Assistant)?.text?.lines()?.lastOrNull { it.isNotBlank() }?.trim()
                    ?: tool?.let { "${it.name}  ${toolTarget(it.name, it.args)}" } ?: "thinking…"
            }
            AnimatedContent(step, transitionSpec = {
                (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 2 }) togetherWith (fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 2 })
            }, label = "step", modifier = Modifier.padding(start = 33.dp, end = 16.dp, top = 10.dp)) { s ->
                Text(s, style = Ph.mono.copy(color = if (page != null) Ph.Cyan else Ph.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().height(54.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawPath(wave(size, size.height * 0.40f, 6.dp.toPx(), 97.dp.toPx(), -flow * 1.5f), Ph.Amber.copy(alpha = 0.10f))
                    drawPath(wave(size, size.height * 0.52f, 5.dp.toPx(), 130.dp.toPx(), flow), Ph.Amber.copy(alpha = 0.16f))
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 9.dp)) {
                    Text(listOfNotNull(t.tools.size.takeIf { it > 0 }?.let { "step $it" }, t.task.cwd.name).joinToString(" · "),
                        style = Ph.monoSmall.copy(color = Ph.Text), modifier = Modifier.weight(1f))
                    if (t.cost >= 0.005) Text(String.format(Locale.US, "$%.2f", t.cost), style = Ph.monoSmall.copy(color = Ph.Text))
                }
            }
        }
    }
}

private fun wave(size: Size, base: Float, amp: Float, length: Float, phase: Float) = Path().apply {
    moveTo(0f, size.height); lineTo(0f, base)
    var x = 0f
    while (x <= size.width) { lineTo(x, base + amp * sin(2 * PI * x / length + phase).toFloat()); x += 6f }
    lineTo(size.width, base); lineTo(size.width, size.height); close()
}

/** A task that's done (or paused, or failed): one quiet row. */
@Composable
fun QuietRow(t: TaskUi, leading: @Composable RowScope.() -> Unit, preview: String?, selected: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Ph.AmberWash else Ph.Ground)
        .padding(horizontal = 6.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading()
        Box(Modifier.padding(start = 2.dp).clip(RoundedCornerShape(3.dp)).background(when {
            t.lastError != null || t.status == Status.FAILED -> Ph.Red
            t.status == Status.IDLE -> Ph.Green
            else -> Ph.Muted
        }).padding(3.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = Ph.body.copy(fontSize = 14.5.sp, color = Ph.Text, fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            preview?.let { Text(it, style = Ph.monoSmall.copy(color = if (t.lastError != null) Ph.Red else Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text("#${t.task.number}", style = Ph.monoSmall)
    }
}
