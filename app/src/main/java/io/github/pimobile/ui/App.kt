package io.github.pimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.pimobile.browser.BrowserManager
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import io.github.pimobile.ui.theme.PhosphorTheme

private const val STORE = "store"
private const val TERMINAL = "app-terminal"
private const val ARTIFACTS = "app-artifacts"
const val USAGE = "app-usage"

/** Home <-> task (the tapped card morphs into the task screen), and the store. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App() {
    PhosphorTheme {
        var openId by rememberSaveable { mutableStateOf<String?>(null) }
        BackHandler(enabled = openId != null) { openId = null }
        // e.g. a new branch: open its card.
        UiStore.openRequest?.let { id -> LaunchedEffect(id) { openId = if (id == io.github.pimobile.MainActivity.HOME) null else id; UiStore.openRequest = null } }
        // The slip-in applies to that one task's way in and out; anything else navigates as usual.
        LaunchedEffect(openId) { if (openId != UiStore.slipInto && openId != null) UiStore.slipInto = null
            if (openId == null && UiStore.slipInto != null) { kotlinx.coroutines.delay(450); UiStore.slipInto = null } }
        LaunchedEffect(openId) {
            // Other screens (store, terminal, artifacts) aren't a task: treat them as "elsewhere".
            UiStore.openTaskId = openId?.takeIf { UiStore.find(it) != null } ?: if (openId == null) null else "screen:$openId"
            // Opening a task clears its "done" notification.
            openId?.let { runCatching { io.github.pimobile.runtime.Notifier.clear(io.github.pimobile.ui.state.UiStore.app, it) } }
        }
        if (Onboarding.needed()) {
            // On a tablet, the welcome pages sit in a phone-width column in the middle.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Ph.Ground)) { Readable(600.dp) { Onboarding.Screen() } }
            return@PhosphorTheme
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        UiStore.branchError?.let { e -> LaunchedEffect(e) {
            android.widget.Toast.makeText(ctx, "Couldn't branch: $e", android.widget.Toast.LENGTH_LONG).show(); UiStore.branchError = null } }

        // Live WebViews that aren't on screen keep rendering here, under the opaque UI.
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        BrowserHost(BrowserManager.shown)
        MeasureRoom { room ->
        androidx.compose.runtime.CompositionLocalProvider(LocalRoom provides room) {
        SharedTransitionLayout(Modifier.fillMaxSize().background(Ph.Ground)) {
            val home: @Composable (androidx.compose.animation.AnimatedVisibilityScope) -> Unit = { scope ->
                HomeScreen(this@SharedTransitionLayout, scope, onStore = { openId = STORE },
                    onTerminal = { openId = TERMINAL }, onArtifacts = { openId = ARTIFACTS }, onUsage = { openId = USAGE }) { UiStore.open(it); openId = it.task.id }
            }
            // Anything that isn't home: a task, the store, the terminal, artifacts, usage. Null when there's nothing to show.
            val detail: @Composable (String?, androidx.compose.animation.AnimatedVisibilityScope) -> Boolean = { id, scope ->
                val task = id?.let { UiStore.find(it) }
                when {
                    id == STORE -> { StoreScreen { openId = null }; true }
                    id == TERMINAL -> { ShellScreen { openId = null }; true }
                    id == ARTIFACTS -> { ArtifactsScreen { openId = null }; true }
                    id == USAGE -> { UsageScreen(onOpenTask = { openId = it }) { openId = null }; true }
                    task != null -> { TaskScreen(task, this@SharedTransitionLayout, scope, onOpenTask = { openId = it.task.id }) { openId = null }; true }
                    else -> false
                }
            }
            if (room.twoPane) {
                // Tablets, unfolded foldables, DeX: the task list stays on the left, the open task fills the right.
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalTwoPane provides true) {
                        androidx.compose.foundation.layout.Box(Modifier.width(listPaneWidth(room.width)).fillMaxHeight()) {
                            androidx.compose.animation.AnimatedVisibility(true, label = "list") { home(this) }
                        }
                        androidx.compose.foundation.layout.Box(Modifier.width(1.dp).fillMaxHeight().background(Ph.Line))
                        androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxHeight()) {
                            AnimatedContent(targetState = openId,
                                transitionSpec = { (fadeIn(tween(220)) + androidx.compose.animation.slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(160)) },
                                label = "detail") { id ->
                                Readable(880.dp) { if (!detail(id, this)) EmptyPane() }
                            }
                        }
                    }
                }
            } else AnimatedContent(
                targetState = openId,
                transitionSpec = {
                    val slip = UiStore.slipInto
                    val glide = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.92f, stiffness = 260f)
                    when {
                        // A new task from the message box: the chat rises out of the box while home sinks back and dims.
                        slip != null && initialState == null && targetState == slip ->
                            (androidx.compose.animation.slideInVertically(glide) { it / 3 } + fadeIn(tween(260)))
                                .togetherWith(androidx.compose.animation.scaleOut(tween(380), targetScale = 0.94f) +
                                    androidx.compose.animation.slideOutVertically(tween(380)) { -it / 14 } + fadeOut(tween(300)))
                        // And back: the chat drops away, home comes forward again.
                        slip != null && initialState == slip && targetState == null ->
                            (androidx.compose.animation.scaleIn(tween(360), initialScale = 0.94f) + fadeIn(tween(280)))
                                .togetherWith(androidx.compose.animation.slideOutVertically(tween(320)) { it / 3 } + fadeOut(tween(260)))
                        else -> fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    }
                },
                label = "nav",
            ) { id ->
                // Portrait tablets and phones on their side: one screen at a time, kept to a readable width.
                Readable { if (!detail(id, this)) home(this) }
            }
        }
        }
        }
        }
    }
}

/** The right pane of the two-pane layout when no task is open. */
@Composable
private fun EmptyPane() {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        io.github.pimobile.ui.components.PhIcon(io.github.pimobile.ui.components.PhIcons.Sparkle, Ph.LineStrong, 56.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Text("Open a task, or start one on the left.", style = Ph.mono.copy(color = Ph.Muted))
    }
}
