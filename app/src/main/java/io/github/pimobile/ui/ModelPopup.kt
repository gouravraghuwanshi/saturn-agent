package io.github.pimobile.ui

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.state.buzz
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sin

/** What the model popup sets: one chat (with a switch for its whole project), or a project from Home. */
sealed class ChoiceTarget {
    class Chat(val ui: TaskUi) : ChoiceTarget()
    class Project(val dir: File, val name: String) : ChoiceTarget()
}

/**
 * Model and thinking level for a chat or a project: a frosted card that springs open from the top, with
 * Saturn's glow breathing behind its title. Changes apply as you tap; running chats switch right away.
 */
@Composable
fun ModelPopup(target: ChoiceTarget, onDismiss: () -> Unit) = FrostedPopup(onDismiss) { close -> Card(target, onDone = close) }

/**
 * A frosted card that springs open from the top over a blurred, dimmed screen; tap outside or Back to close.
 * [content] gets a close() that plays the closing animation first.
 */
@Composable
fun FrostedPopup(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 380f)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch { shown.animateTo(0f, tween(160)); onDismiss() }
    }

    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Blur what's behind (Android 12+, where the phone allows it); a dim scrim does the rest.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window ?: return@LaunchedEffect
            window.setDimAmount(0f)
            if (Build.VERSION.SDK_INT >= 31 && window.windowManager.isCrossWindowBlurEnabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = 48 }
            }
        }
        Box(Modifier.fillMaxSize()
            .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
            .background(Ph.Deep.copy(alpha = if (Ph.paper) 0.45f else 0.6f))
            .clickable(remember { MutableInteractionSource() }, null) { close() }
            .statusBarsPadding().imePadding(),
            contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(horizontal = 14.dp).padding(top = 10.dp).widthIn(max = 480.dp).fillMaxWidth()
                .graphicsLayer {
                    val s = shown.value
                    alpha = s.coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * s; scaleY = 0.86f + 0.14f * s
                    translationY = -(1f - s) * 28.dp.toPx()
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                // taps on the card mustn't close it
                .clickable(remember { MutableInteractionSource() }, null) {}) {
                content(::close)
            }
        }
    }
}

/** The card itself: frosted surface, amber edge, Saturn's slow breath behind the top. */
@Composable
fun GlowCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { val start = withFrameNanos { it }; while (true) withFrameNanos { t = (it - start) / 1e9f } }
    val shape = RoundedCornerShape(26.dp)
    Column(Modifier.fillMaxWidth().clip(shape)
        .background(Ph.Surface.copy(alpha = if (Ph.paper) 0.96f else 0.88f))
        .border(1.dp, Ph.AmberLine.copy(alpha = 0.7f), shape)
        .drawBehind {
            if (!Ph.paper) {
                val breathe = (sin(t * 1.3f) + 1f) / 2f
                saturnGlow(Offset(size.width * (0.5f + 0.06f * sin(t * 0.37f)), -size.height * 0.05f), 0.9f, breathe, depth = 0.35f)
            }
        }
        .padding(top = 18.dp, bottom = 14.dp), content = content)
}

@Composable
private fun Card(target: ChoiceTarget, onDone: () -> Unit) {
    val view = LocalView.current
    val chat = (target as? ChoiceTarget.Chat)?.ui
    val dir = when (target) { is ChoiceTarget.Chat -> target.ui.task.cwd; is ChoiceTarget.Project -> target.dir }
    val projectName = when (target) {
        is ChoiceTarget.Project -> target.name
        is ChoiceTarget.Chat -> UiStore.runtime.projectName(target.ui.task.cwd)
            .let { if (it == io.github.pimobile.runtime.PiRuntime.GENERAL) "General" else it }
    }
    val proj = UiStore.projectChoice(dir)
    val own = chat?.let { UiStore.chatChoice(it) }
    // The switch is on when this chat has no choice of its own and its project has one.
    val forProject = chat == null || (own?.isEmpty == true && !proj.isEmpty)
    val model = chat?.let { it.model ?: UiStore.wantedModel(it.task) } ?: proj.model ?: UiStore.model
    val thinking = chat?.let { it.thinkingLevel ?: UiStore.wantedThinking(it.task) } ?: proj.thinking ?: UiStore.thinkingDefault
    val levels = chat?.responses?.get("get_available_thinking_levels")?.optJSONArray("levels")
        ?.let { a -> (0 until a.length()).map { a.getString(it) } }?.takeIf { it.isNotEmpty() } ?: THINKING_LEVELS
    LaunchedEffect(chat, model) { chat?.let { UiStore.rpc(it, "get_available_thinking_levels") } }
    LaunchedEffect(Unit) { io.github.pimobile.ui.state.Control.loadModels() }

    fun pickModel(m: String) {
        view.buzz(android.view.HapticFeedbackConstants.CONFIRM)
        UiStore.noteRecent(m)
        if (chat != null) UiStore.chooseInChat(chat, model = m, forProject = forProject) else UiStore.chooseForProject(dir, model = m)
    }
    fun pickLevel(l: String) {
        view.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK)
        if (chat != null) UiStore.chooseInChat(chat, thinking = l, forProject = forProject) else UiStore.chooseForProject(dir, thinking = l)
    }

    GlowCard {
        // ---- title
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SaturnMark(Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(if (chat != null) "This chat" else projectName, style = Ph.title.copy(fontSize = 17.sp))
                Text(when {
                    chat == null -> if (proj.isEmpty) "Every chat here uses your default" else "Every chat in $projectName uses this"
                    forProject && !proj.isEmpty -> "Following $projectName"
                    own?.isEmpty == false -> "Just this chat"
                    else -> "Using your default"
                }, style = Ph.monoSmall)
            }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Close") { onDone() }, contentAlignment = Alignment.Center) {
                PhIcon(PhIcons.Close, Ph.Muted, 15.dp, "Close")
            }
        }

        // ---- thinking
        Label("THINKING", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
        LevelPicker(levels, thinking, ::pickLevel, Modifier.padding(horizontal = 16.dp))

        // ---- model
        Label("MODEL", Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
        ModelList(model, ::pickModel)

        // ---- scope
        if (chat != null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable {
                    view.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK); UiStore.setChatFollowsProject(chat, !forProject) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhIcon(PhIcons.Folder, if (forProject) Ph.Amber else Ph.Muted, 16.dp)
                Column(Modifier.weight(1f)) {
                    Text("Use for every chat in this project", style = Ph.body.copy(fontSize = 14.sp, color = Ph.Text))
                    Text(projectName, style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Toggle(forProject)
            }
        }
        val canReset = if (chat != null) own?.isEmpty == false || (forProject && !proj.isEmpty) else !proj.isEmpty
        if (canReset) Text(if (chat != null && !forProject && !proj.isEmpty) "Use $projectName's choice" else "Back to my default",
            style = Ph.monoSmall.copy(color = Ph.Amber),
            modifier = Modifier.padding(start = 12.dp, top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable {
                view.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK)
                when {
                    chat == null -> UiStore.resetProject(dir)
                    !forProject -> UiStore.resetChat(chat)
                    else -> UiStore.resetProject(dir)
                }
            }.padding(horizontal = 8.dp, vertical = 8.dp))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) = Text(text, style = Ph.label, modifier = modifier)

/** One pill per level; the chosen one fills amber. Little bars show how hard each one thinks. */
@Composable
private fun LevelPicker(levels: List<String>, current: String?, onPick: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        levels.forEachIndexed { i, l ->
            val on = l == current
            val bg by androidx.compose.animation.animateColorAsState(if (on) Ph.Amber else Ph.Chip, spring(stiffness = Spring.StiffnessMediumLow), label = "lvl")
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable(onClickLabel = "Think: $l") { onPick(l) }
                .padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    // 4 bars; "off" lights none, the top level lights all.
                    val lit = if (levels.size > 1) Math.round(i * 4f / (levels.size - 1)) else 0
                    repeat(4) { b ->
                        Box(Modifier.size(width = 3.dp, height = (4 + b * 2).dp).clip(RoundedCornerShape(1.dp))
                            .background(if (on) Ph.AmberInk.copy(alpha = if (b < lit) 1f else 0.3f) else if (b < lit) Ph.Amber else Ph.LineStrong))
                    }
                }
                Text(shortName(l), style = Ph.monoSmall.copy(fontSize = 10.5.sp, color = if (on) Ph.AmberInk else Ph.TextSoft), maxLines = 1)
            }
        }
    }
}

private fun shortName(l: String) = when (l) { "minimal" -> "min"; "medium" -> "med"; "xhigh" -> "xhi"; else -> l }

/** Recent models first, then the rest (newest versions first); search when the list is long. */
/**
 * Starred models first, then recent ones, then everything else (newest versions first). With hundreds of
 * models (OpenRouter), search, or tap the star chip to see only your favourites. Tap a row's star to (un)star it.
 */
@Composable
internal fun ModelList(current: String?, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var onlyFavs by remember { mutableStateOf(false) }
    val view = LocalView.current
    val all = UiStore.models.map { (p, id) -> "$p/$id" }
    val favs = UiStore.favoriteModels
    val q = query.trim()
    fun matches(m: String) = q.isEmpty() || m.contains(q, ignoreCase = true)
    val favRows = favs.filter { (it in all || it == current) && matches(it) }
    val recent = if (onlyFavs || q.isNotEmpty()) emptyList()
        else (listOfNotNull(current) + UiStore.recentModels).distinct().filter { (it in all || it == current) && it !in favs }
    val rest = if (onlyFavs) emptyList() else UiStore.models
        .sortedWith(compareBy<Pair<String, String>> { it.first }.then { a, b -> versionCompare(b.second, a.second) })
        .map { (p, id) -> "$p/$id" }.filter { matches(it) && it !in favs && it !in recent }.take(200)
    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (all.size > 8) BasicTextField(query, { query = it }, singleLine = true,
            textStyle = Ph.mono.copy(fontSize = 13.sp, color = Ph.Text), cursorBrush = SolidColor(Ph.Amber),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .background(Ph.Ground.copy(alpha = 0.7f)).border(1.dp, Ph.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon(PhIcons.Search, Ph.Muted, 13.dp)
                Box { if (query.isEmpty()) Text("Search ${all.size} models", style = Ph.mono.copy(fontSize = 13.sp, color = Ph.Muted)); inner() }
            } })
        if (favs.isNotEmpty()) Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (onlyFavs) Ph.Amber else Ph.Chip).clickable(onClickLabel = if (onlyFavs) "Show all models" else "Show favourites only") {
                view.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK); onlyFavs = !onlyFavs }
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PhIcon(PhIcons.StarFilled, if (onlyFavs) Ph.AmberInk else Ph.Amber, 13.dp)
            Text("${favs.size}", style = Ph.monoSmall.copy(color = if (onlyFavs) Ph.AmberInk else Ph.TextSoft))
        }
    }
    if (all.isEmpty()) Text(if (UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty()) "Loading models…" else "Sign in first (/login) to pick a model.",
        style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    else if (favs.isEmpty() && q.isEmpty()) Text("Tap ☆ on a model to keep it at the top.", style = Ph.monoSmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
    LazyColumn(Modifier.heightIn(max = 320.dp)) {
        @Composable fun header(text: String) = Text(text, style = Ph.label.copy(fontSize = 10.sp), modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp))
        if (favRows.isNotEmpty()) item(key = "h:fav") { header("FAVOURITES") }
        items(favRows, key = { "f:$it" }) { m -> ModelRow(m, m == current, onPick) }
        if (recent.isNotEmpty()) item(key = "h:recent") { header("RECENT") }
        items(recent, key = { "r:$it" }) { m -> ModelRow(m, m == current, onPick) }
        if (rest.isNotEmpty() && (favRows.isNotEmpty() || recent.isNotEmpty())) item(key = "h:all") { header("ALL MODELS") }
        items(rest, key = { "a:$it" }) { m -> ModelRow(m, m == current, onPick) }
    }
}

@Composable
private fun ModelRow(m: String, on: Boolean, onPick: (String) -> Unit) {
    val view = LocalView.current
    val label = io.github.pimobile.ui.state.modelLabel(m)
    val fav = m in UiStore.favoriteModels
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp))
        .background(if (on) Ph.AmberWash.copy(alpha = 0.8f) else Color.Transparent)
        .clickable(onClickLabel = "Use $m") { onPick(m) }.padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (on) Ph.Amber else Color.Transparent))
        Text(label?.first ?: m.substringAfter('/'), style = Ph.mono.copy(fontSize = 13.sp, color = if (on) Ph.Amber else Ph.Text),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(label?.second ?: m.substringBefore('/'), style = Ph.monoSmall, maxLines = 1)
        // Its own tap target: starring doesn't pick the model.
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = if (fav) "Remove from favourites" else "Add to favourites") {
                view.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK); UiStore.toggleFavorite(m) },
            contentAlignment = Alignment.Center) {
            PhIcon(if (fav) PhIcons.StarFilled else PhIcons.Star, if (fav) Ph.Amber else Ph.Muted, 16.dp, if (fav) "Favourite" else "Not a favourite")
        }
    }
}

@Composable
internal fun Toggle(on: Boolean) {
    val x by animateDpAsState(if (on) 19.dp else 3.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "knob")
    Box(Modifier.size(width = 44.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Ph.Amber else Ph.LineStrong)) {
        Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (on) Ph.AmberInk else Ph.Muted))
    }
}
