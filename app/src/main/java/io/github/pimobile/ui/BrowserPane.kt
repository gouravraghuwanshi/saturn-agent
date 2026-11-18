package io.github.pimobile.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.pimobile.browser.BrowserManager
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.components.toolTarget
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.theme.Ph

/** Puts [view] into a fresh container, taking it from wherever it was attached. */
private fun adopt(ctx: android.content.Context, view: android.webkit.WebView) = FrameLayout(ctx).apply {
    (view.parent as? ViewGroup)?.removeView(view)
    addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/**
 * Keeps every task's WebView attached and rendering behind the UI while it
 * is not on screen, so pages keep running and CDP screenshots work.
 */
@Composable
fun BrowserHost(shownTaskId: String?) {
    Box(Modifier.fillMaxSize()) {
        BrowserManager.pages.values.filter { it.taskId != shownTaskId }.forEach { page ->
            androidx.compose.runtime.key(page.taskId) {
                AndroidView({ adopt(it, page.view) }, Modifier.fillMaxSize())
            }
        }
    }
}

/** The task's live browser: watch pi drive it, or take over. */
@Composable
fun BrowserPane(t: TaskUi, onClose: () -> Unit, onTakeOver: () -> Unit) {
    val page = BrowserManager.pages[t.task.id]
    DisposableEffect(t.task.id) {
        BrowserManager.shown = t.task.id
        onDispose { if (BrowserManager.shown == t.task.id) BrowserManager.shown = null }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFF2B4650), RoundedCornerShape(20.dp)).background(Color.White)
        ) {
            // URL bar
            Row(Modifier.fillMaxWidth().background(Ph.Raised).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val secure = page?.url?.startsWith("https://") == true
                Text(if (secure) "🔒" else "○", style = Ph.monoSmall.copy(color = if (secure) Ph.Green else Ph.Muted))
                Text(page?.url?.removePrefix("https://")?.takeIf { !it.startsWith("data:") } ?: "about:blank",
                    style = Ph.mono.copy(color = Ph.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (t.browsing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(Ph.Cyan, 6); Text("pi driving", style = Ph.monoSmall.copy(color = Ph.Cyan))
                }
            }
            if (page != null && page.progress < 100) Box(Modifier.fillMaxWidth().height(2.dp).background(Ph.Chip)) {
                Box(Modifier.fillMaxWidth(page.progress / 100f).height(2.dp).background(Ph.Cyan))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (page == null) Text("The browser opens when pi first uses it.", style = Ph.body.copy(color = Ph.Muted),
                    modifier = Modifier.align(Alignment.Center))
                else androidx.compose.runtime.key(page.taskId) {
                    AndroidView({ adopt(it, page.view) }, Modifier.fillMaxSize())
                }
            }
            // Action log: the last few browser tool calls.
            val log = t.tools.filter { it.name.startsWith("browser_") }.takeLast(3)
            if (log.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Color(0xFF111318)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                log.forEach { tool ->
                    val (icon, c) = when (tool.state) {
                        ToolState.RUNNING -> "⚙" to Ph.Amber; ToolState.DONE -> "✓" to Ph.Cyan; ToolState.ERROR -> "✗" to Ph.Red
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(icon, style = Ph.mono.copy(color = c))
                        Text(tool.name.removePrefix("browser_").padEnd(9), style = Ph.mono.copy(color = Ph.Text))
                        Text(toolTarget(tool.name, tool.args).ifBlank { tool.args.optString("ref").let { if (it.isNotBlank()) "[$it]" else "" } },
                            style = Ph.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(Ph.Raised)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(16.dp)).clickable(onClickLabel = "Take over the browser") { onTakeOver() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (t.browsing) "Take over" else "You're in control", style = Ph.title)
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ph.Raised).border(1.dp, Ph.LineStrong, RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Reload page") { page?.view?.reload() }, contentAlignment = Alignment.Center) {
                Text("↻", style = Ph.title.copy(color = Ph.TextSoft))
            }
            if (!t.browsing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ph.Raised).border(1.dp, Ph.LineStrong, RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Close browser") { onClose() }, contentAlignment = Alignment.Center) {
                io.github.pimobile.ui.components.PhIcon(io.github.pimobile.ui.components.PhIcons.Close, Ph.TextSoft, 18.dp, "Close browser")
            }
        }
    }
}
