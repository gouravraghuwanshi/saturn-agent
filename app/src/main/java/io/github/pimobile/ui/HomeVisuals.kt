package io.github.pimobile.ui

import io.github.pimobile.ui.state.buzz

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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
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
fun OrbitHero(active: List<TaskUi>, total: Int, onOpen: (TaskUi) -> Unit, onPlanet: () -> Unit, modifier: Modifier = Modifier,
              page: Int = 0, pageStatus: String? = null, onPage: (Int) -> Unit = {}) {
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
    // The status line keeps teaching what Saturn does: status → "tap" hint → status → "swipe" hint → …
    var nudge by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(6_000); nudge = 1; kotlinx.coroutines.delay(2_800); nudge = 0
            kotlinx.coroutines.delay(6_000); nudge = 2; kotlinx.coroutines.delay(2_800); nudge = 0
        }
    }
    val onPlanetTap = onPlanet

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
                        view.buzz(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        val cw = size.width / AsciiSaturn.COLS.toFloat(); val ch = size.height / AsciiSaturn.ROWS.toFloat()
                        val hit = frame.moons.minByOrNull { (c, r, _) -> (Offset((c + 0.5f) * cw, (r + 0.5f) * ch) - p).getDistance() }
                        if (hit != null && (Offset((hit.first + 0.5f) * cw, (hit.second + 0.5f) * ch) - p).getDistance() < 28.dp.toPx())
                            moons.getOrNull(hit.third)?.let(onOpen) ?: onPlanetTap()
                        else onPlanetTap()
                    }
                })
        }
        val status = pageStatus ?: when {
                active.isEmpty() && total == 0 -> "Ask below to start a task"
                active.isEmpty() -> "All quiet · $total ${if (total == 1) "task" else "tasks"}"
                else -> listOfNotNull(working.takeIf { it > 0 }?.let { "$it working" }, waiting.takeIf { it > 0 }?.let { "$it ${if (it == 1) "needs" else "need"} you" }).joinToString(" · ")
            }
        val hints = listOf("tap Saturn for more", if (page == 0) "swipe Saturn for routines" else "swipe Saturn for tasks")
        androidx.compose.animation.AnimatedContent(if (nudge == 0) status else hints[nudge - 1], label = "status",
            transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(400)) }) { s ->
            Text(s, style = Ph.mono.copy(color = if (s in hints) Ph.Amber else if (waiting > 0 && pageStatus == null) Ph.Text else Ph.TextSoft),
                modifier = Modifier.padding(top = 2.dp))
        }
        // Two pages, tasks and routines: the dots say which, and switch on tap.
        Row(Modifier.padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            (0..1).forEach { i ->
                val w by androidx.compose.animation.core.animateDpAsState(if (i == page) 18.dp else 6.dp,
                    androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f), label = "dot")
                Box(Modifier.size(width = 30.dp, height = 22.dp).clip(RoundedCornerShape(11.dp))
                    .clickable(onClickLabel = if (i == 0) "Show tasks" else "Show routines") { if (i != page) onPage(i) },
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = w, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == page) Ph.Amber else Ph.LineStrong))
                }
            }
        }
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
 * A running task: a small moon orbits the card, and the current step fades in as it changes.
 */
@Composable
fun LiquidCard(t: TaskUi, leading: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    LiquidFrame(modifier, steps = t.tools.size, footer = {
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 9.dp)) {
            Text(listOfNotNull(t.tools.size.takeIf { it > 0 }?.let { "step $it" }, t.task.cwd.name).joinToString(" · "),
                style = Ph.monoSmall.copy(color = Ph.Text), modifier = Modifier.weight(1f))
            if (t.cost >= 0.005) Text(String.format(Locale.US, "$%.2f", t.cost), style = Ph.monoSmall.copy(color = Ph.Text))
        }
    }) {
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
        StepLine(step, if (page != null) Ph.Cyan else Ph.TextSoft, Modifier.padding(start = 33.dp, end = 16.dp, top = 10.dp))
    }
}

/** The current step, sliding up as it changes. */
@Composable
fun StepLine(step: String, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(step, transitionSpec = {
        (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 2 }) togetherWith (fadeOut(tween(250)) + slideOutVertically(tween(250)) { -it / 2 })
    }, label = "step", modifier = modifier) { s ->
        Text(s, style = Ph.mono.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A running task: a night-sky card with a small moon on a tilted orbit. It passes in front of the
 * text and behind it, trailing light, and sends out a pulse each time a step lands ([steps]).
 */
@Composable
fun LiquidFrame(modifier: Modifier = Modifier, wave: androidx.compose.ui.unit.Dp = 54.dp, steps: Int = 0,
                footer: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
                content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    // Seconds since this card appeared, ticking every frame (only while a task runs).
    val time by androidx.compose.runtime.produceState(0f) {
        var start = -1L
        while (true) androidx.compose.runtime.withFrameNanos { n -> if (start < 0) start = n; value = (n - start) / 1e9f }
    }
    val pulses = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    var seen by remember { androidx.compose.runtime.mutableIntStateOf(steps) }
    androidx.compose.runtime.LaunchedEffect(steps) {
        if (steps > seen) pulses.add(time)
        seen = steps
    }
    val stars = remember { val r = java.util.Random(); List(26) { Star(r.nextFloat(), r.nextFloat(), 0.25f + r.nextFloat() * r.nextFloat() * 1.1f, r.nextFloat() * 6.283f, 0.5f + r.nextFloat() * 1.8f) } }
    val shape = RoundedCornerShape(22.dp)
    val sky = if (Ph.amoled) Color(0xFF050506) else Color(0xFF0A0B0E)

    Column(modifier.fillMaxWidth().clip(shape).background(sky).border(1.dp, Color(0x12FFECC8), shape)
        .drawBehind { orbit(time, stars, pulses) }) {
        content()
        Box(Modifier.fillMaxWidth().height(wave)) { footer() }
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)

private fun DrawScope.orbit(t: Float, stars: List<Star>, pulses: MutableList<Float>) {
    val w = size.width; val h = size.height
    for (s in stars) {
        val a = (0.2f + 0.6f * (0.5f + 0.5f * sin(t * s.speed + s.phase))) * 0.7f
        drawCircle(Color(0xFFFFF6E8).copy(alpha = a), s.r.dp.toPx(), Offset(s.x * w, s.y * h))
    }
    val cx = w * 0.56f; val cy = h * 0.56f; val rx = w * 0.5f; val ry = h * 0.2f; val rot = -0.12f
    val cr = kotlin.math.cos(rot); val sr = kotlin.math.sin(rot)
    fun at(a: Float): Offset { val x = kotlin.math.cos(a) * rx; val y = sin(a) * ry; return Offset(cx + x * cr - y * sr, cy + x * sr + y * cr) }
    pulses.removeAll { t - it > 1.1f || it > t }
    val flash = pulses.lastOrNull()?.let { kotlin.math.exp(-(t - it) / 0.6f) } ?: 0f
    // The orbit itself, a hairline that brightens with each step.
    rotate(Math.toDegrees(rot.toDouble()).toFloat(), Offset(cx, cy)) {
        drawOval(AmberLight.copy(alpha = 0.08f + 0.14f * flash), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2), style = Stroke(0.8.dp.toPx()))
    }
    val a = t * 0.55f
    // A trail of light behind the moon: brighter on the near side of the orbit.
    for (k in 1..46) {
        val b = a - k * 0.028f; val p = at(b); val near = sin(b) > 0; val f = 1f - k / 46f
        drawCircle(AmberLight.copy(alpha = (if (near) 0.45f else 0.2f) * f), ((if (near) 1.5f else 1f) * f + 0.2f).dp.toPx(), p)
    }
    val m = at(a); val near = sin(a) > 0
    val r = (if (near) 4.8f else 3.2f).dp.toPx(); val dim = if (near) 1f else 0.5f
    val halo = 20.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(Ph.Amber.copy(alpha = (0.3f * dim + 0.35f * flash).coerceAtMost(1f)), Color.Transparent), m, halo), halo, m)
    drawCircle(Brush.radialGradient(0f to Color(0xFFFFECCD).copy(alpha = dim), 0.55f to Ph.Amber.copy(alpha = dim), 1f to Color(0xFF5A3714).copy(alpha = dim),
        center = Offset(m.x - r * 0.4f, m.y - r * 0.45f), radius = r * 1.4f), r, m)
    // A pulse out from the moon for each step.
    for (p0 in pulses) {
        val p = (t - p0) / 1.1f
        drawCircle(AmberLight.copy(alpha = 0.45f * (1f - p)), r + p * 26.dp.toPx(), m, style = Stroke(1.dp.toPx()))
    }
}

/** Card ⇄ task screen: the card grows into the chat and shrinks back, on a soft spring. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val CardMorph = androidx.compose.animation.BoundsTransform { _, _ ->
    androidx.compose.animation.core.spring<androidx.compose.ui.geometry.Rect>(dampingRatio = 0.86f, stiffness = 380f)
}

/** A small, live Saturn in text for the chat header: turns while [spinning], a white moon when Saturn needs you. */
@Composable
fun MiniSaturn(spinning: Boolean, needsYou: Boolean, modifier: Modifier = Modifier, helpers: Int = 0) {
    var t by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.6f) }
    androidx.compose.runtime.LaunchedEffect(spinning) { while (spinning) { kotlinx.coroutines.delay(66); t += 0.08f } }
    val cols = 30; val rows = 9
    // Moons: one per helper (subagent) at work right now, plus a pale one while Saturn waits for you.
    val frame = AsciiSaturn.frame(t, List(helpers.coerceAtMost(6)) { false } + if (needsYou) listOf(true) else emptyList(), cols = cols, rows = rows, r = 4.6)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val fontPx = with(density) { maxWidth.toPx() } / (cols * 0.6f)
        Text(frame.text, softWrap = false, maxLines = rows,
            style = androidx.compose.ui.text.TextStyle(fontFamily = Ph.Mono, fontWeight = FontWeight.Bold,
                fontSize = with(density) { fontPx.toSp() }, lineHeight = with(density) { (fontPx * 1.08f).toSp() },
                fontFeatureSettings = "liga 0, calt 0",
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    androidx.compose.ui.text.style.LineHeightStyle.Trim.None)))
    }
}

/**
 * A task that's done (or stopped, or failed). How it ended is written up its front edge, sideways:
 * DONE, STOPPED or FAILED. Then the title and when it ran, its last reply, and one line of what
 * happened: the steps, how long it took, and what it made in Artifacts (or else the files it changed).
 */
@Composable
fun QuietRow(t: TaskUi, leading: @Composable RowScope.() -> Unit, preview: String?, selected: Boolean, modifier: Modifier = Modifier) {
    val failed = t.lastError != null || t.status == Status.FAILED || t.ended == "failed"
    val stopped = !failed && t.ended == "stopped"
    val shape = RoundedCornerShape(18.dp)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loaded = t.items.isNotEmpty()
    val steps = if (loaded) t.tools.size else t.stepsDone
    val outcome = if (loaded) remember(t.items.size) { io.github.pimobile.ui.state.Outcome.of(t.tools.map { it.name to it.args }) } else t.savedOutcome
    val stamp = t.lastRunAt?.let { at -> remember(at) { stamp(ctx, at) } }
    val bg by androidx.compose.animation.animateColorAsState(if (selected) Ph.AmberWash else Ph.Surface, tween(200), label = "bg")
    val edge by androidx.compose.animation.animateColorAsState(if (selected) Ph.AmberLine else Ph.Line, tween(200), label = "edge")
    val spine = 24.dp

    Box(modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, edge, shape)) {
        // How it ended, in thin plain letters up the front edge.
        Box(Modifier.matchParentSize()) {
            Box(Modifier.fillMaxHeight().width(spine), contentAlignment = Alignment.Center) {
                Text(when { failed -> "FAILED"; stopped -> "STOPPED"; else -> "DONE" }, maxLines = 1, softWrap = false,
                    style = Ph.monoSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Light, letterSpacing = 0.32.em, color = Ph.Muted),
                    modifier = Modifier.sideways())
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = spine + 6.dp, end = 16.dp, top = 13.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                Text(t.title, style = Ph.title.copy(fontSize = 15.sp, color = Ph.Text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                stamp?.let { Text(it, style = Ph.monoSmall.copy(color = Ph.Muted)) }
            }
            preview?.let { Text(it, style = Ph.mono.copy(fontSize = 11.5.sp, color = if (failed) Ph.Red else Ph.TextSoft),
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val made = outcome?.made.orEmpty(); val changed = outcome?.changed.orEmpty()
            val files = (made.ifEmpty { changed }).let { f -> if (f.isEmpty()) null else f.take(2).joinToString(", ") + if (f.size > 2) " +${f.size - 2}" else "" }
            val facts = listOfNotNull(steps?.takeIf { it > 0 }?.let { if (it == 1) "1 step" else "$it steps" }, t.workMs.takeIf { it >= 1000 }?.let { took(it) })
            if (facts.isNotEmpty() || files != null) Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (steps != null && steps > 0) StepTrail(steps, failed)
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append(facts.joinToString(" · "))
                    if (files != null) {
                        if (facts.isNotEmpty()) append(" · ")
                        withStyle(androidx.compose.ui.text.SpanStyle(color = if (made.isNotEmpty()) Ph.Amber else Ph.TextSoft)) { append(files) }
                    }
                }, style = Ph.monoSmall.copy(color = Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Lays text out turned a quarter to the left, reading upwards. */
private fun Modifier.sideways() = this.layout { m, _ ->
    val p = m.measure(androidx.compose.ui.unit.Constraints())
    layout(p.height, p.width) { p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f } }
}

/** "42s", "3m 12s", "1h 4m". */
private fun took(ms: Long): String {
    val s = ms / 1000
    return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m ${s % 60}s"; else -> "${s / 3600}h ${s % 3600 / 60}m" }
}

/** One dot per step (up to 12), brightening towards the latest; the last is red if the task failed. */
@Composable
private fun StepTrail(n: Int, failed: Boolean) {
    val shown = n.coerceAtMost(12)
    Canvas(Modifier.size(width = (shown * 6).dp, height = 6.dp)) {
        val gap = 6.dp.toPx(); val r = 1.8.dp.toPx()
        for (i in 0 until shown) {
            val c = if (failed && i == shown - 1) Ph.Red else Ph.Amber
            drawCircle(c.copy(alpha = 0.3f + 0.6f * (i + 1) / shown), r, Offset(gap * i + gap / 2, size.height / 2))
        }
    }
}

/** "14:41", "Yesterday 14:41", "24 Nov 14:41", or "24 Nov 2025" (in the phone's 12/24-hour style). */
private fun stamp(ctx: android.content.Context, ms: Long): String {
    val time = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))
    val now = java.util.Calendar.getInstance(); val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    fun same(a: java.util.Calendar, b: java.util.Calendar) = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    val loc = java.util.Locale.getDefault()
    return when {
        same(c, now) -> time
        same(c, yesterday) -> "Yesterday $time"
        c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) -> java.text.SimpleDateFormat("d MMM", loc).format(java.util.Date(ms)) + " $time"
        else -> java.text.SimpleDateFormat("d MMM yyyy", loc).format(java.util.Date(ms))
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

    fun frame(t: Float, moons: List<Boolean>, glow: Float = 0f, cols: Int = COLS, rows: Int = ROWS, r: Double = R): Frame {
        val COLS = cols; val ROWS = rows; val R = r
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
fun DrawScope.saturnGlow(center: Offset, strength: Float, breathe: Float, red: Boolean = false) {
    if (strength <= 0.01f) return
    val r = size.width * 0.95f
    val a = strength * (0.85f + 0.15f * breathe)
    drawCircle(Brush.radialGradient(
        0f to (if (red) Ph.Red else Ph.Amber).copy(alpha = 0.26f * a),
        0.22f to (if (red) Ph.Red else Ph.Amber).copy(alpha = 0.15f * a),
        0.45f to (if (red) Ph.Red else Color(0xFFE08A3A)).copy(alpha = 0.07f * a),
        0.7f to (if (red) Ph.Red else Color(0xFFC0682A)).copy(alpha = 0.025f * a),
        1f to Color.Transparent,
        center = center, radius = r), r, center)
}
