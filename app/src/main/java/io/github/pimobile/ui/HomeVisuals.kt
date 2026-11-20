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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
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
import kotlin.math.roundToInt
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.withStyle

private val AmberLight = Color(0xFFFFD89A)
private val AmberDeep = Color(0xFFA86A1C)
private val RingA = Color(0xFFD9B27A)
private val RingB = Color(0xFFF0D3A0)
private val RingC = Color(0xFFB89466)
private val Bands = listOf(0f to Color(0xFFF6E2B4), 0.16f to Color(0xFFEBCB8E), 0.27f to Color(0xFFF3DAA6), 0.38f to Color(0xFFDDAE68),
    0.47f to Color(0xFFEFCF95), 0.58f to Color(0xFFD69F58), 0.68f to Color(0xFFE6BF80), 0.8f to Color(0xFFC98E4A), 1f to Color(0xFFB07A3A))
    .map { it.second }

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
    // Saturn drawn in text (design S2): an amber globe, cream rings, a spark riding the ring,
    // and each working task a moon. ~15 frames a second; stops when scrolled off screen.
    var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(66); t += 0.057f } }
    val moons = active.take(6)
    val working = active.count { it.status != Status.NEEDS_INPUT }
    val waiting = active.size - working
    val view = androidx.compose.ui.platform.LocalView.current
    val density0 = androidx.compose.ui.platform.LocalDensity.current
    val glowPx = with(density0) { 9.dp.toPx() }
    val frame = AsciiSaturn.frame(t, moons.map { it.status == Status.NEEDS_INPUT })
    val breathe = 0.5f + 0.5f * sin(t * 1.1f)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            // JetBrains Mono advances 0.6 em per character: size the text so the art spans the width.
            val fontPx = with(density) { maxWidth.toPx() } / (AsciiSaturn.COLS * 0.6f)
            val font = with(density) { fontPx.toSp() }
            val line = with(density) { (fontPx * 1.08f).toSp() }
            Text(frame.text, softWrap = false, maxLines = AsciiSaturn.ROWS,
                style = androidx.compose.ui.text.TextStyle(fontFamily = Ph.Mono, fontWeight = FontWeight.Bold, fontSize = font, lineHeight = line,
                    fontFeatureSettings = "liga 0, calt 0", // no ligatures: "==" must stay two characters
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                        androidx.compose.ui.text.style.LineHeightStyle.Trim.None)),
                modifier = Modifier.pointerInput(moons) {
                    detectTapGestures { p ->
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        val cw = size.width / AsciiSaturn.COLS.toFloat(); val ch = size.height / AsciiSaturn.ROWS.toFloat()
                        val hit = frame.moons.minByOrNull { (c, r, _) -> (Offset((c + 0.5f) * cw, (r + 0.5f) * ch) - p).getDistance() }
                        if (hit != null && (Offset((hit.first + 0.5f) * cw, (hit.second + 0.5f) * ch) - p).getDistance() < 28.dp.toPx())
                            moons.getOrNull(hit.third)?.let(onOpen) ?: onPlanet()
                        else onPlanet()
                    }
                })
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

/** A task that's done (or paused, or failed): H2's quiet card: dot, title, one line of what happened. */
@Composable
fun QuietRow(t: TaskUi, leading: @Composable RowScope.() -> Unit, preview: String?, selected: Boolean, modifier: Modifier = Modifier) {
    val failed = t.lastError != null || t.status == Status.FAILED
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(if (selected) Ph.AmberWash else Ph.Surface)
        .border(1.dp, if (selected) Ph.AmberLine else Ph.Line, shape).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leading()
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(when {
                failed -> Ph.Red; else -> Ph.Green }))
            Text(t.title, style = Ph.title.copy(fontSize = 15.sp, color = Ph.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(if (failed) "failed" else "#${t.task.number}", style = Ph.monoSmall.copy(color = if (failed) Ph.Red else Ph.Muted))
        }
        preview?.let { Text(it, style = Ph.mono.copy(fontSize = 11.5.sp, color = if (failed) Ph.Red else Ph.Muted),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 19.dp)) }
    }
}

/** Saturn ray-traced into characters, the S2 design. */
object AsciiSaturn {
    const val COLS = 72
    const val ROWS = 21
    private const val R = 11.2
    private const val RAMP = " .,:;-=+*#%@"
    private val Planet = Color(0xFFF2B35B); private val PlanetDim = Color(0xFFC08A45)
    private val Ring = Color(0xFFE6D3B0); private val RingDim = Color(0xFFA08A6A)
    private val Spark = Color.White; private val Moon = Color(0xFFFFD89A); private val Waiting = Color(0xFFE9E6DF)

    /** [moons]: the task moons in order, true where that task needs you. Moon hits are (col, row, index). */
    class Frame(val text: androidx.compose.ui.text.AnnotatedString, val moons: List<Triple<Int, Int, Int>>)

    fun frame(t: Float, moons: List<Boolean>, glow: Float = 0f): Frame {
        val tilt = -0.26; val ct = cos(tilt); val st = sin(tilt); val e = 0.27
        val ln = kotlin.math.sqrt(0.5 * 0.5 + 0.62 * 0.62 + 0.6 * 0.6)
        val lx = -0.5 / ln; val ly = -0.62 / ln; val lz = 0.6 / ln
        val hits = ArrayList<Triple<Int, Int, Int>>()
        // Moon centres, in the same units as the cells.
        val mpos = moons.indices.map { k ->
            val a = t * 0.35 + k * 2 * PI / moons.size.coerceAtLeast(1)
            val mx = 2.65 * R * cos(a); val mv = 2.65 * R * e * sin(a)
            Triple(mx * ct - mv * st, mx * st + mv * ct, sin(a) > 0)
        }
        val b = androidx.compose.ui.text.AnnotatedString.Builder()
        var runColor: Color? = null; val run = StringBuilder()
        // Each character glows in its own colour, like a phosphor screen: brighter for the spark and moons.
        fun shadowFor(c: Color?) = when {
            c == null || glow <= 0f -> null
            c == Spark || c == Moon || c == Waiting -> androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.95f), blurRadius = glow * 2.2f)
            c == Planet -> androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.8f), blurRadius = glow * 1.3f)
            c == PlanetDim -> androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.5f), blurRadius = glow)
            else -> androidx.compose.ui.graphics.Shadow(c.copy(alpha = 0.45f), blurRadius = glow)
        }
        fun flush() { if (run.isNotEmpty()) { b.withStyle(androidx.compose.ui.text.SpanStyle(color = runColor ?: Color.Transparent, shadow = shadowFor(runColor))) { append(run.toString()) }; run.clear() } }
        fun put(c: Char, color: Color?) { if (color != runColor) { flush(); runColor = color }; run.append(c) }
        val sparkAt = ((t * 0.6) % (2 * PI)) - PI
        for (j in 0 until ROWS) {
            for (i in 0 until COLS) {
                val px = i - COLS / 2.0 + 0.5; val py = (j - ROWS / 2.0 + 0.5) * 1.9
                val u = px * ct + py * st; val v = -px * st + py * ct
                val rr = kotlin.math.hypot(u, v / e); val ang = kotlin.math.atan2(v / e, u)
                var ring = rr > R * 1.22 && rr < R * 2.3 && !(rr > R * 1.9 && rr < R * 2.0)
                var spark = false
                if (rr > R * 2.0 && rr < R * 2.32) {
                    val d = kotlin.math.atan2(sin(ang - sparkAt), cos(ang - sparkAt))
                    if (d <= 0 && d > -0.5) { ring = true; spark = true }
                }
                var moon = -1; var moonFront = false
                mpos.forEachIndexed { k, (mx, my, front) -> if (kotlin.math.abs(px - mx) < 0.9 && kotlin.math.abs(py - my) < 1.0) { moon = k; moonFront = front } }
                val d2 = px * px + py * py
                var sphere = -1.0
                if (d2 < R * R) {
                    val z = kotlin.math.sqrt(R * R - d2); val nz = z / R; val nv = v / R
                    var lit = maxOf(0.0, px / R * lx + py / R * ly + nz * lz)
                    lit = 0.1 + 0.9 * lit + 0.13 * sin(nv * 13 + 1.2) + 0.06 * sin(kotlin.math.atan2(u / R, nz) * 4 + t * 0.4) * cos(nv * 3)
                    if (v > 0 && v < R * 0.22) lit *= 0.55 // the rings' shadow
                    sphere = lit.coerceIn(0.0, 1.0)
                }
                val front = v > 0
                var ch = ' '; var color: Color? = null
                if (sphere >= 0 && !(ring && front)) {
                    ch = RAMP[(0.25 + sphere * 0.75).times(RAMP.length - 1).roundToInt()]
                    color = if (sphere > 0.45) Planet else PlanetDim
                } else if (ring) {
                    ch = if (spark) '*' else if (rr < R * 1.45) ':' else if (rr < R * 1.9) '=' else '-'
                    color = if (spark) Spark else if (rr < R * 1.45) RingDim else Ring
                }
                if (moon >= 0 && (moonFront || sphere < 0)) {
                    ch = '@'; color = if (moons[moon]) Waiting else Moon
                    hits += Triple(i, j, moon)
                }
                put(ch, color)
            }
            if (j < ROWS - 1) put('\n', runColor)
        }
        flush()
        return Frame(b.toAnnotatedString(), hits)
    }
}

/**
 * The warm light behind Saturn: one wide, soft amber glow that fades very slowly outward, so a
 * little of it reaches the header and the lines around Saturn. Drawn behind the whole screen.
 */
fun DrawScope.saturnGlow(center: Offset, strength: Float, breathe: Float) {
    if (strength <= 0.01f) return
    val r = size.width * 0.95f
    val a = strength * (0.85f + 0.15f * breathe)
    drawCircle(Brush.radialGradient(
        0f to Ph.Amber.copy(alpha = 0.26f * a),
        0.22f to Ph.Amber.copy(alpha = 0.15f * a),
        0.45f to Color(0xFFE08A3A).copy(alpha = 0.07f * a),
        0.7f to Color(0xFFC0682A).copy(alpha = 0.025f * a),
        1f to Color.Transparent,
        center = center, radius = r), r, center)
}
