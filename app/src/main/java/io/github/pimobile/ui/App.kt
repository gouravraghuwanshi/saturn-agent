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
import androidx.compose.foundation.layout.fillMaxSize
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
        LaunchedEffect(openId) {
            // Other screens (store, terminal, artifacts) aren't a task: treat them as "elsewhere".
            UiStore.openTaskId = openId?.takeIf { UiStore.find(it) != null } ?: if (openId == null) null else "screen:$openId"
            // Opening a task clears its "done" notification.
            openId?.let { runCatching { io.github.pimobile.runtime.Notifier.clear(io.github.pimobile.ui.state.UiStore.app, it) } }
        }
        if (Onboarding.needed()) { Onboarding.Screen(); return@PhosphorTheme }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        UiStore.branchError?.let { e -> LaunchedEffect(e) {
            android.widget.Toast.makeText(ctx, "Couldn't branch: $e", android.widget.Toast.LENGTH_LONG).show(); UiStore.branchError = null } }

        // Live WebViews that aren't on screen keep rendering here, under the opaque UI.
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        BrowserHost(BrowserManager.shown)
        SharedTransitionLayout(Modifier.fillMaxSize().background(Ph.Ground)) {
            AnimatedContent(
                targetState = openId,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "nav",
            ) { id ->
                val task = id?.let { UiStore.find(it) }
                if (id == STORE) StoreScreen { openId = null }
                else if (id == TERMINAL) ShellScreen { openId = null }
                else if (id == ARTIFACTS) ArtifactsScreen { openId = null }
                else if (id == USAGE) UsageScreen(onOpenTask = { openId = it }) { openId = null }
                else if (task == null) HomeScreen(this@SharedTransitionLayout, this, onStore = { openId = STORE },
                    onTerminal = { openId = TERMINAL }, onArtifacts = { openId = ARTIFACTS }, onUsage = { openId = USAGE }) { UiStore.open(it); openId = it.task.id }
                else TaskScreen(task, this@SharedTransitionLayout, this, onOpenTask = { openId = it.task.id }) { openId = null }
            }
        }
        }
    }
}
