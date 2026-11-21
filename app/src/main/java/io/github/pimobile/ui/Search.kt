package io.github.pimobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What shows above the list while you pull down from the top: a small lens whose ring fills as you pull,
 * then "pull more to search", then "release to search" once it's far enough.
 */
@Composable
fun PullToSearchHint(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0.08f) return
    val ready = progress >= 1f
    Row(modifier.graphicsLayer {
        alpha = ((progress - 0.08f) / 0.35f).coerceIn(0f, 1f)
        val s = 0.8f + 0.2f * progress.coerceAtMost(1f) + if (ready) 0.04f else 0f
        scaleX = s; scaleY = s
    }.clip(RoundedCornerShape(50)).background(if (ready) Ph.AmberWash else Ph.Surface)
        .border(1.dp, if (ready) Ph.Amber else Ph.Line, RoundedCornerShape(50))
        .padding(start = 8.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val ring = Ph.Amber; val track = Ph.Line
        Box(Modifier.size(24.dp).drawBehind {
            val w = 2.dp.toPx()
            drawCircle(track, radius = size.minDimension / 2 - w, style = Stroke(w))
            drawArc(ring, -90f, 360f * progress.coerceAtMost(1f), false,
                topLeft = Offset(w, w), size = androidx.compose.ui.geometry.Size(size.width - 2 * w, size.height - 2 * w),
                style = Stroke(w, cap = StrokeCap.Round))
        }, contentAlignment = Alignment.Center) { PhIcon(PhIcons.Search, if (ready) Ph.Amber else Ph.TextSoft, 12.dp) }
        Text(when { ready -> "release to search"; progress > 0.45f -> "pull more to search"; else -> "keep pulling" },
            style = Ph.mono.copy(fontSize = 12.sp, color = if (ready) Ph.Amber else Ph.TextSoft))
    }
}

/** Search every chat, across projects. Drawn over home, which blurs behind it. */
@Composable
fun SearchOverlay(onOpen: (TaskUi) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<UiStore.Hit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(120); runCatching { focus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(160)
        searching = true
        hits = withContext(Dispatchers.IO) { runCatching { UiStore.search(query) }.getOrDefault(emptyList()) }
        searching = false
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }

    Box(Modifier.fillMaxSize()
        .background(Ph.Ground.copy(alpha = 0.55f * enter.value.coerceIn(0f, 1f)))
        .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(top = 10.dp)) {
            // ---- the search field drops in from where you pulled
            Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth()
                .graphicsLayer { translationY = (1f - enter.value) * -60.dp.toPx(); alpha = enter.value.coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(18.dp)).background(Ph.Raised).border(1.dp, Ph.AmberLine, RoundedCornerShape(18.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { }
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhIcon(PhIcons.Search, Ph.Amber, 18.dp)
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) Text("Search all chats", style = Ph.body.copy(fontSize = 15.sp, color = Ph.Muted))
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(Ph.Amber),
                        textStyle = Ph.body.copy(fontSize = 15.sp, color = Ph.Text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { hits?.firstOrNull()?.let { onOpen(it.ui) } ?: keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                Text(if (query.isEmpty()) "close" else "clear", style = Ph.monoSmall.copy(color = Ph.TextSoft),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (query.isEmpty()) onClose() else query = "" }
                        .padding(horizontal = 10.dp, vertical = 10.dp))
            }

            val list = hits
            Text(when {
                list == null || (searching && list.isEmpty()) -> "looking…"
                query.isBlank() -> "RECENT"
                list.isEmpty() -> "nothing found for \"${query.trim()}\""
                else -> "${list.size} ${if (list.size == 1) "chat" else "chats"} · ${list.sumOf { it.count }} mentions"
            }, style = Ph.label, modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 8.dp)
                .graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) })

            if (list != null) LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(list, key = { _, h -> h.ui.task.id }) { i, h -> HitCard(h, i, Modifier.animateItem()) { onOpen(h.ui) } }
            }
        }
    }
}

@Composable
private fun HitCard(h: UiStore.Hit, index: Int, modifier: Modifier, onClick: () -> Unit) {
    // Each result rises in a beat after the one above it.
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(18L * index.coerceAtMost(10)); rise.animateTo(1f, tween(260)) }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().graphicsLayer { alpha = rise.value; translationY = (1f - rise.value) * 14.dp.toPx() }
        .clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape).clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(displayTitle(h.ui.title), style = Ph.title.copy(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            Text(ago(h.time), style = Ph.monoSmall)
        }
        if (h.snippet.isNotBlank()) Text(buildAnnotatedString {
            if (h.len > 0 && h.at + h.len <= h.snippet.length) {
                append(h.snippet.substring(0, h.at))
                withStyle(SpanStyle(color = Ph.Amber, fontWeight = FontWeight.SemiBold, background = Ph.AmberWash)) {
                    append(h.snippet.substring(h.at, h.at + h.len))
                }
                append(h.snippet.substring(h.at + h.len))
            } else append(h.snippet)
        }, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.TextBody), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(
            UiStore.runtime.projectName(h.ui.task.cwd).let { if (it == io.github.pimobile.runtime.PiRuntime.GENERAL) "General" else it },
            "#${h.ui.task.number}",
            h.count.takeIf { it > 1 }?.let { "$it mentions" },
            "archived".takeIf { h.ui.archived },
        ).joinToString(" · "), style = Ph.monoSmall)
    }
}

private fun ago(t: Long): String {
    if (t <= 0) return ""
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "now"; s < 3600 -> "${s / 60}m"; s < 86_400 -> "${s / 3600}h"; s < 7 * 86_400 -> "${s / 86_400}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}
