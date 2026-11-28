package io.github.pimobile.ui

import io.github.pimobile.ui.state.buzz

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import io.github.pimobile.runtime.TaskManager
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.BlinkCursor
import io.github.pimobile.ui.components.Markdown
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.components.ToolPanel
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.widthIn
import java.util.Locale

/**
 * Transcript rows. Everything Saturn does between your message and its answer
 * (thinking, tool calls, notes on the way) folds into one Work block; the
 * answer itself stays a normal row underneath.
 */
private sealed class Block(val key: String) {
    class Single(val item: Item, val hideThinking: Boolean = false) : Block(item.key)
    class Work(val items: List<Item>, val finalThinking: Item.Assistant?) : Block("work-" + items.first().key)
}

/** What counts as "the answer has started" while Saturn still works: a short note between steps isn't one. */
private const val ANSWER_CHARS = 200

private fun blocks(items: List<Item>, running: Boolean = false): List<Block> {
    val out = mutableListOf<Block>()
    var i = 0
    while (i < items.size) {
        if (items[i] !is Item.Tool && items[i] !is Item.Assistant) {
            // Retries in a row: only the latest one is worth a line.
            val retry = (items[i] as? Item.Notice)?.text?.startsWith("Retrying (") == true
            val nextRetry = (items.getOrNull(i + 1) as? Item.Notice)?.text?.startsWith("Retrying (") == true
            if (!(retry && nextRetry)) out += Block.Single(items[i])
            i++; continue
        }
        var j = i
        while (j < items.size && (items[j] is Item.Tool || items[j] is Item.Assistant)) j++
        val run = items.subList(i, j)
        if (run.none { it is Item.Tool }) run.forEach { out += Block.Single(it) }
        else {
            val last = run.last()
            // While it works, a short note after a step stays in the Work card (otherwise every "Now let me…"
            // between tool calls folded the card and the next step sprang it open again).
            val isLast = j == items.size
            if (last is Item.Assistant && last.text.isNotBlank() && (!running || !isLast || last.text.length >= ANSWER_CHARS)) {
                out += Block.Work(run.dropLast(1), last.takeIf { it.thinking.isNotBlank() })
                out += Block.Single(last, hideThinking = true)
            } else out += Block.Work(run.toList(), null)
        }
        i = j
    }
    return out
}

@OptIn(ExperimentalSharedTransitionApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(t: TaskUi, shared: SharedTransitionScope, visibility: AnimatedVisibilityScope,
               onOpenTask: (TaskUi) -> Unit = {}, onBack: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<UiStore.Attachment>() }
    val shareScope = androidx.compose.runtime.rememberCoroutineScope()
    // Leaving the chat drops an unfinished edit (the box it lived in is gone).
    androidx.compose.runtime.DisposableEffect(t) { onDispose { t.editingLast = false } }
    // Dragged in from another app (or pasted): text into the box, files attached; you review and send.
    UiStore.share?.takeIf { it.taskId == t.task.id }?.let { sh -> LaunchedEffect(sh) {
        UiStore.share = null
        if (sh.text.isNotBlank()) input = input.text.let { cur ->
            val v = if (cur.isBlank()) sh.text else cur.trimEnd() + "\n" + sh.text
            TextFieldValue(v, androidx.compose.ui.text.TextRange(v.length)) }
        // Its own coroutine: clearing UiStore.share above ends this effect, which would cancel the copy.
        shareScope.launch {
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sh.uris.mapNotNull { u -> runCatching { UiStore.attach(u) }
                .onFailure { e -> android.util.Log.w("SaturnDrop", "couldn't attach $u", e)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(UiStore.app, "Couldn't open ${u.lastPathSegment ?: "that file"}: ${e.message ?: e}", android.widget.Toast.LENGTH_LONG).show() } }
                .getOrNull() } }
            attachments.addAll(got) }
    } }
    var followUp by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("chat") }
    var lampOpen by remember { mutableStateOf(false) }
    var lampTouch by remember { mutableStateOf(0L) }
    // The lamp slider folds away 3 s after you last touched it.
    LaunchedEffect(lampOpen, lampTouch) { if (lampOpen) { kotlinx.coroutines.delay(3000); lampOpen = false } }
    // Reading mode: paper colours while this chat is on screen; dark again the moment you leave
    // (so home never flashes cream during the way back), and dark status-bar icons on the page.
    val leaving = visibility.transition.targetState == androidx.compose.animation.EnterExitState.PostExit
    val paperOn = UiStore.reading && tab == "chat" && !leaving
    val rootView = androidx.compose.ui.platform.LocalView.current
    fun barsFor(light: Boolean) {
        val w = (rootView.context as? android.app.Activity)?.window ?: return
        androidx.core.view.WindowCompat.getInsetsController(w, rootView).apply {
            isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
        }
    }
    LaunchedEffect(paperOn) { Ph.paper = paperOn; barsFor(paperOn) }
    LaunchedEffect(UiStore.shade) { Ph.paperShade = UiStore.shade }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Ph.paper = false; barsFor(false) } }
    var holdBrowser by rememberSaveable { mutableStateOf(false) } // user took over
    // Once the agent opened a page it stays until the user closes it: runs often end with
    // "now you finish this in the browser" (sign in, pay, solve a captcha…).
    val showBrowserTab = t.browsing || holdBrowser || io.github.pimobile.browser.BrowserManager.pages[t.task.id] != null
    LaunchedEffect(showBrowserTab) { if (!showBrowserTab && tab == "browser") tab = "chat" }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(tab) { focus.clearFocus(); keyboard?.hide() }
    val running = t.status == Status.RUNNING || t.status == Status.STARTING
    val list = rememberLazyListState()
    val blocks by remember(t) { derivedStateOf { blocks(t.items.toList(), t.status == Status.RUNNING || t.status == Status.STARTING) } }

    LaunchedEffect(t) { UiStore.open(t) }
    // While this task is on screen: a tick for every tool call Saturn starts, a "no" when one fails.
    LaunchedEffect(t) {
        var seen = -1; var failed = -1
        androidx.compose.runtime.snapshotFlow {
            t.items.count { it is Item.Tool } to t.items.count { it is Item.Tool && it.state == io.github.pimobile.ui.state.ToolState.ERROR }
        }.collect { (n, f) ->
            val live = t.status == Status.RUNNING
            if (seen >= 0 && n > seen && live) io.github.pimobile.ui.state.Haptics.tool()
            if (failed >= 0 && f > failed && live) io.github.pimobile.ui.state.Haptics.toolFailed()
            seen = n; failed = f
        }
    }
    // Follow new output until the user scrolls up; resume when they return to the bottom.
    var follow by remember { mutableStateOf(true) }
    // Saturn and the status show only when you pull the chat down; any push up (or the keyboard) hides them.
    var reveal by remember { mutableStateOf(false) }
    val boxTop = remember { FloatArray(1) }
    val flipAt = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    // Pull up past the end and let go: start a new task. [pull] is how far the finger went past the end.
    var pull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val hostView = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pullArm = with(density) { 300.dp.toPx() } // a long, deliberate pull: no accidental tasks
    val liftMax = with(density) { 360.dp.toPx() }  // how far the chat can slide up (it gets heavier)
    val topMax = with(density) { 110.dp.toPx() }   // the softer give at the top
    val liftAtArm = liftMax * (1f - kotlin.math.exp(-pullArm / liftMax))
    var lift by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var liftJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Sideways, like pages: home is to the left (drag right to go back), the terminal to the right (drag left). Same feel as the pull-up.
    var liftX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var sideArmed by remember { mutableStateOf(false) }
    var sideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Swiping home leaves this screen still slid sideways; reopening the task while it's leaving brings
    // the same screen back. Whenever it's coming back, settle it into place.
    LaunchedEffect(visibility.transition.targetState) {
        if (visibility.transition.targetState != androidx.compose.animation.EnterExitState.Visible) return@LaunchedEffect
        sideArmed = false
        val settle = androidx.compose.animation.core.spring<Float>(dampingRatio = 1f, stiffness = androidx.compose.animation.core.Spring.StiffnessMedium)
        if (lift != 0f) launch { androidx.compose.animation.core.animate(lift, 0f, animationSpec = settle) { v, _ -> lift = v } }
        if (liftX != 0f) androidx.compose.animation.core.animate(liftX, 0f, animationSpec = settle) { v, _ -> liftX = v }
    }
    val sideMax = with(density) { 300.dp.toPx() }
    val sideArm = with(density) { 190.dp.toPx() }
    val sideAtArm = sideMax * (1f - kotlin.math.exp(-sideArm / sideMax))
    val hSlop = with(density) { 18.dp.toPx() }
    val lock = remember { booleanArrayOf(false) } // [0]: this gesture went sideways
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val slop = with(density) { 6.dp.toPx() }
    val startNewTask = {
        hostView.buzz(android.view.HapticFeedbackConstants.CONFIRM)
        UiStore.selectProject(t.task.cwd); UiStore.composeRequest = true; onBack()
    }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    snapshotFlow { list.isScrollInProgress }.first { !it } // let the fling settle
                    follow = !list.canScrollForward
                }
            }
        }
    }
    val lastText = (t.items.lastOrNull() as? Item.Assistant)?.text?.length ?: 0
    val lastOutput = (t.items.lastOrNull() as? Item.Tool)?.output?.length ?: 0
    LaunchedEffect(blocks.size, lastText, lastOutput, running) {
        // Offset beyond the end is clamped, so this lands at the very bottom.
        if (follow && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2)
    }
    // Whatever makes the content grow (streamed text, tool output, the header folding),
    // keep the bottom in view while following.
    LaunchedEffect(list) {
        snapshotFlow { follow && list.canScrollForward }.collect { if (it && list.layoutInfo.totalItemsCount > 0)
            list.scrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2) }
    }
    // Opening the task, coming back from the terminal, or a history reload: show the latest.
    LaunchedEffect(t.rebuilds, tab) {
        if (tab == "chat" && blocks.isNotEmpty()) { follow = true; list.scrollToItem(blocks.size, Int.MAX_VALUE / 2) }
    }

    // The header's Saturn and its warm light: bright and breathing while Saturn works,
    // a faint ember once it's done, red if it failed. Folds away while you read back or type.
    val imeOpen = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(imeOpen) { if (imeOpen) reveal = false }
    val collapse by androidx.compose.animation.core.animateFloatAsState(
        // A critically damped spring, not a fixed tween: it eases in and out, and reversing mid-way
        // carries on smoothly from where it is instead of restarting.
        if (reveal && !imeOpen && tab == "chat") 0f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "collapse")
    val failedNow = t.status == Status.FAILED || (t.lastError != null && !running)
    val glow by androidx.compose.animation.core.animateFloatAsState(when {
        running -> 1f; t.status == Status.NEEDS_INPUT -> 0.85f; failedNow -> 0.5f; else -> 0.28f
    }, androidx.compose.animation.core.tween(1400), label = "glow")
    val breatheAnim = androidx.compose.animation.core.rememberInfiniteTransition(label = "breathe")
    val breatheV by breatheAnim.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(2200), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val alive = running || t.status == Status.NEEDS_INPUT
    // Always breathing: gently when idle (done, failed, paused), deeper while Saturn works.
    val breathe = breatheV
    // Seconds the light has been drifting. Ticks only while Saturn works (no redraws when idle), and is
    // read only while drawing, so it repaints the glow without recomposing the chat.
    val drift = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(alive) {
        if (!alive) return@LaunchedEffect
        var last = 0L
        while (true) androidx.compose.runtime.withFrameNanos { now ->
            if (last != 0L) drift.floatValue += (now - last) / 1e9f
            last = now
        }
    }
    var glowAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    var rootAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val ctx = LocalContext.current
    var loginArg by remember { mutableStateOf("") }
    // pi's built-in commands, mapped to RPC, the pimobile-app extension or native screens.
    val builtins = listOf(
        Builtin("model", "Switch model for this chat (or its project)", args = true) { a ->
            if (a.contains('/')) { UiStore.noteRecent(a.trim()); UiStore.chooseInChat(t, model = a.trim(), forProject = UiStore.modelSource(t) == UiStore.Source.PROJECT) }
            else sheet = "model" },
        Builtin("thinking", "Set the thinking level for this chat (or its project)", args = true) { a ->
            if (a.isNotBlank()) UiStore.chooseInChat(t, thinking = a.trim(), forProject = UiStore.thinkingSource(t) == UiStore.Source.PROJECT) else sheet = "model" },
        Builtin("effort", "Same as /thinking", args = true) { a ->
            if (a.isNotBlank()) UiStore.chooseInChat(t, thinking = a.trim(), forProject = UiStore.thinkingSource(t) == UiStore.Source.PROJECT) else sheet = "model" },
        Builtin("login", "Sign in to a provider (subscriptions, API keys)", args = true) { a -> loginArg = a; sheet = "login" },
        Builtin("logout", "Remove a provider credential") { sheet = "logout" },
        Builtin("new", "Start a fresh session in this task") { UiStore.rpc(t, "new_session") },
        Builtin("resume", "Open another session as a task") { sheet = "resume" },
        Builtin("name", "Rename this task", tag = "app", args = true) { a ->
            if (a.isNotBlank()) { UiStore.rename(t, a); UiStore.rpc(t, "set_session_name", "name" to a) } else sheet = "rename" },
        Builtin("session", "Session info and statistics") { sheet = "session" },
        Builtin("tree", "Navigate the session tree") { sheet = "tree" },
        Builtin("fork", "Branch from an earlier message into a new task") { sheet = "fork" },
        Builtin("clone", "Branch this whole chat into a new task") { UiStore.branch(t) },
        Builtin("compact", "Compact context (optional instructions)", args = true) { a ->
            UiStore.rpc(t, "compact", "customInstructions" to a.ifBlank { null }) },
        Builtin("copy", "Copy the last reply") { t.responses.remove("get_last_assistant_text"); UiStore.rpc(t, "get_last_assistant_text") },
        Builtin("export", "Export the session as HTML and share it") {
            val out = java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.html").apply { parentFile?.mkdirs() }
            t.responses.remove("export_html"); UiStore.rpc(t, "export_html", "outputPath" to out.path) },
        Builtin("reload", "Reload extensions, skills, prompts and context") { UiStore.appCommand(t, "reload"); io.github.pimobile.ui.state.Control.restart() },
        Builtin("settings", "App settings", tag = "app") { sheet = "settings" },
        Builtin("changelog", "What's new in pi") { sheet = "changelog" },
        Builtin("stop", "Stop the current run", tag = "app") { UiStore.stop(t) },
        Builtin("quit", "End this task's pi process", tag = "app") { TaskManager.stop(t.task) },
    )
    val commands = paletteOf(builtins)

    // Results of commands that act outside the transcript.
    t.responses["get_last_assistant_text"]?.let { r ->
        LaunchedEffect(r) {
            val text = r.optString("text").takeIf { it.isNotBlank() && it != "null" }
            if (text != null) ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Saturn", text))
            android.widget.Toast.makeText(ctx, if (text != null) "Copied the last reply" else "No reply to copy yet", android.widget.Toast.LENGTH_SHORT).show()
            t.responses.remove("get_last_assistant_text")
        }
    }
    t.responses["export_html"]?.let { r ->
        LaunchedEffect(r) {
            t.responses.remove("export_html")
            val f = java.io.File(r.optString("path").ifBlank { java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.html").path })
            if (f.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/html").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share session"))
            }
        }
    }
    // /fork and /tree hand an earlier message back for editing, like pi's TUI.
    t.prefill?.let { text ->
        LaunchedEffect(text) { input = TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length)); t.prefill = null; tab = "chat" }
    }

    with(shared) {
        Box(
            Modifier.fillMaxSize()
                // Morphs from (and back into) its home card; a task that slipped in from the message box doesn't.
                .then(if (UiStore.slipInto == t.task.id || LocalTwoPane.current) Modifier else Modifier.sharedBounds(rememberSharedContentState("task-${t.task.id}"),
                    visibility, boundsTransform = CardMorph, clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp))))
                .background(Ph.Ground)
                .dropInto(t.task.id)
                .onGloballyPositioned { rootAt = it.boundsInRoot().topLeft }
                .drawBehind {
                    // Lamp light: the shadier the paper, the more the edges fall into shadow while a warm
                    // pool of light stays on the page, as if read under a lamp above it.
                    val sh = if (Ph.paper) Ph.paperShade else 0f
                    if (sh > 0.01f) {
                        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.32f)
                        val r = size.maxDimension * 0.78f
                        drawRect(androidx.compose.ui.graphics.Brush.radialGradient(
                            0f to Color(0xFFFFE6B0).copy(alpha = 0.22f * sh),
                            0.38f to Color(0xFFFFE6B0).copy(alpha = 0.06f * sh),
                            0.62f to Color.Transparent,
                            1f to Color(0xFF2B1B0A).copy(alpha = 0.42f * sh),
                            center = c, radius = r))
                    }
                    // Paper grain: a faint fibre texture, so the page reads as paper, not a flat colour.
                    if (Ph.paper) drawRect(androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(
                        paperGrain, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated)),
                        alpha = 0.55f + 0.3f * Ph.paperShade)
                    // Amber glow is for the dark screen; on paper only a breath of it.
                    // Where the light wanders while Saturn works: two slow waves multiplied, so it mostly lingers,
                    // then now and then sweeps across. -1..1; settles back to centre when work stops.
                    val d = drift.floatValue
                    val wander = if (alive) (kotlin.math.sin(d * 0.42f) * kotlin.math.sin(d * 0.153f + 1.1f)) else 0f
                    val wander2 = if (alive) (kotlin.math.sin(d * 0.31f + 2.4f) * kotlin.math.sin(d * 0.117f)) else 0f
                    if (glowAt != androidx.compose.ui.geometry.Offset.Unspecified)
                        saturnGlow(glowAt - rootAt + androidx.compose.ui.geometry.Offset(wander * size.width * 0.12f, 0f),
                            glow * (1f - collapse) * (if (Ph.paper) 0.35f else 1f), breathe, red = failedNow,
                            depth = if (alive) 0.4f else 0.25f)
                    // While Saturn works, two soft warm lights drift along the top edge (stronger once the big glow
                    // folds away while you read back or type), so you can tell it's busy at a glance.
                    if (alive && !Ph.paper) {
                        val strength = 0.35f + 0.65f * collapse
                        val h = 170.dp.toPx()
                        fun light(x: Float, down: Float, c: Color, a: Float, w: Float) {
                            val r = size.width * w
                            val at = androidx.compose.ui.geometry.Offset(x, -h * 0.15f + down)
                            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                                0f to c.copy(alpha = a), 0.5f to c.copy(alpha = a * 0.35f), 1f to Color.Transparent,
                                center = at, radius = r), r, at)
                        }
                        val a = strength * (0.06f + 0.07f * breathe)
                        // Now and then one light sinks deep into the chat and floats back up (fainter as it goes,
                        // so it never sits heavily behind the text).
                        val sink1 = (kotlin.math.sin(d * 0.19f + 0.7f) * kotlin.math.sin(d * 0.071f)).coerceAtLeast(0f)
                        val sink2 = (kotlin.math.sin(d * 0.23f + 3.1f) * kotlin.math.sin(d * 0.089f + 1.9f)).coerceAtLeast(0f)
                        light(size.width * (0.5f + 0.38f * wander), sink1 * size.height * 0.9f, Ph.Amber, a * (1f - 0.35f * sink1), 0.62f)
                        light(size.width * (0.5f + 0.42f * wander2), sink2 * size.height * 0.8f, Color(0xFFE08A3A), a * 0.8f * (1f - 0.35f * sink2), 0.48f)
                    }
                }
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(t, tab, showBrowserTab, collapse, onSaturn = { glowAt = it },
                    onModel = { sheet = "model" }, onThinking = { sheet = "model" },
                    onTab = { tab = it; if (it != "browser") holdBrowser = false },
                    onBack = { if (tab != "chat") tab = "chat" else onBack() }, onMenu = { sheet = "menu" },
                    lampOpen = lampOpen, onLamp = { lampOpen = it; lampTouch = System.currentTimeMillis() })

                io.github.pimobile.ui.components.BrokenPackageBanner(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (tab == "browser") {
                    BrowserPane(t, onClose = {
                        holdBrowser = false; tab = "chat"; io.github.pimobile.browser.BrowserManager.close(t.task.id)
                    }) { holdBrowser = true; if (running) UiStore.stop(t) }
                    return@Column
                }
                if (tab == "terminal") {
                    if (running) TerminalBusy { UiStore.stop(t) }
                    else TerminalPane(t)
                    return@Column
                }
                Box(Modifier.weight(1f).clipToBounds()
                    .onGloballyPositioned { boxTop[0] = it.positionInRoot().y }
                    .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        liftJob?.cancel()
                        // Measured on the screen, not in this box: the header folding in or out moves the box under
                        // the finger, and reading that as a drag made the header flicker while you held.
                        var lastY = down.position.y + boxTop[0]; var run = 0f; var travel = 0f; var anchor: Float? = null; var ticked = false
                        var sumX = 0f; var sumY = 0f; var vLocked = false
                        var topPull = 0f; var edgeDrag = false
                        // Pulling down at the top first brings the header in; only once it's already
                        // showing does the chat itself give a little. Never both at once (that jittered).
                        val topGive = reveal
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val y = c.position.y + boxTop[0]
                            val dy = y - lastY; lastY = y; travel += kotlin.math.abs(dy)
                            sumX += c.position.x - c.previousPosition.x; sumY += dy
                            if (lock[0]) continue
                            // Edge pulls only once the drag is clearly up/down, so sideways swipes stay sideways.
                            if (!vLocked && kotlin.math.abs(sumY) > slop && kotlin.math.abs(sumY) > 1.2f * kotlin.math.abs(sumX)) vLocked = true
                            // Once this drag is pulling the chat past an edge, it's only about that pull:
                            // dragging back to cancel mustn't bring the header in.
                            // The header follows a clear change of direction only (a deliberate stretch of travel), not every wiggle.
                            run = if (dy == 0f || run == 0f || kotlin.math.sign(dy) == kotlin.math.sign(run)) run + dy else dy
                            if (travel > slop && !edgeDrag && kotlin.math.abs(sumY) > kotlin.math.abs(sumX)) {
                                if (run > flipAt && !reveal) reveal = true else if (run < -flipAt && reveal) reveal = false
                            }
                            // At an edge the whole chat follows your finger (with weight). Measured from where
                            // it hit the edge, so dragging back cancels.
                            val atEnd = !list.canScrollForward; val atTop = !list.canScrollBackward
                            if (!vLocked) { /* not decided yet */ }
                            else if (atEnd || atTop || pull > 0f || topPull > 0f) {
                                val a = anchor ?: y.also { anchor = it }
                                pull = if (atEnd) (a - y).coerceAtLeast(0f) else 0f
                                topPull = if (atTop && topGive) (y - a).coerceAtLeast(0f) else 0f
                                if (pull > 0f || topPull > 0f) { c.consume(); if (pull > slop) edgeDrag = true }
                            } else { anchor = null; pull = 0f; topPull = 0f }
                            if (!ticked && (pull > slop * 2 || topPull > slop * 2)) {
                                ticked = true; hostView.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                            lift = -liftMax * (1f - kotlin.math.exp(-pull / liftMax)) + topMax * (1f - kotlin.math.exp(-topPull / topMax))
                            val nowArmed = pull >= pullArm
                            if (nowArmed != armed) {
                                armed = nowArmed
                                hostView.buzz(if (android.os.Build.VERSION.SDK_INT >= 34) {
                                    if (nowArmed) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                    else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                                } else if (nowArmed) android.view.HapticFeedbackConstants.CONTEXT_CLICK else android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        val go = armed
                        armed = false; pull = 0f
                        if (go) startNewTask()
                        else liftJob = scope.launch {
                            androidx.compose.animation.core.animate(lift, 0f, animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = 0.62f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { v, _ -> lift = v }
                        }
                    }
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        lock[0] = false; sideJob?.cancel()
                        var dx = 0f; var dy = 0f; var on = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val d = c.position - c.previousPosition
                            if (!on) {
                                // Something else took it (the list scrolling, a code block sliding, the pull-up).
                                if (c.isConsumed) break
                                dx += d.x; dy += d.y
                                if (kotlin.math.abs(dx) > hSlop && kotlin.math.abs(dx) > 1.8f * kotlin.math.abs(dy)) { on = true; lock[0] = true }
                                else if (kotlin.math.abs(dy) > hSlop) break
                                else continue
                            } else dx += d.x
                            c.consume()
                            val drag = (kotlin.math.abs(dx) - hSlop).coerceAtLeast(0f)
                            liftX = kotlin.math.sign(dx) * sideMax * (1f - kotlin.math.exp(-drag / sideMax))
                            val now = drag >= sideArm
                            if (now != sideArmed) {
                                sideArmed = now
                                hostView.buzz(if (android.os.Build.VERSION.SDK_INT >= 34) {
                                    if (now) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                    else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                                } else if (now) android.view.HapticFeedbackConstants.CONTEXT_CLICK else android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                        if (on) {
                            val go = sideArmed; val toHome = dx > 0
                            sideArmed = false
                            if (go) hostView.buzz(android.view.HapticFeedbackConstants.CONFIRM)
                            if (go && toHome) onBack()
                            else {
                                if (go) tab = "terminal"
                                sideJob = scope.launch {
                                    androidx.compose.animation.core.animate(liftX, 0f, animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = 0.7f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { v, _ -> liftX = v }
                                }
                            }
                        }
                    }
                }) {
                LazyColumn(
                    state = list, overscrollEffect = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { translationY = lift; translationX = liftX },
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(blocks, key = { _, b -> b.key }) { i, b ->
                        // Saturn's turn starts after each of your messages: label it once.
                        val prev = blocks.getOrNull(i - 1)
                        val isUser = b is Block.Single && b.item is Item.User
                        val turnStart = !isUser && (prev == null || (prev is Block.Single && prev.item is Item.User))
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null).padding(top = if (isUser && i > 0) 6.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (b) {
                                // Live while it's the last thing, or only a short note is being written after it
                                // (the real answer folds it; a "Now let me…" between steps shouldn't).
                                is Block.Work -> WorkBlock(b, live = running && i == blocks.lastIndex, startedAt = t.runStartedAt)
                                is Block.Single -> Row1(t, b.item, b.hideThinking, latest = b.item === t.items.lastOrNull()) { sheet = it }
                            }
                        }
                    }
                    if (running && blocks.lastOrNull() !is Block.Work) item(key = "working") { Working(t) }
                }
                // Behind the chat as it slides sideways: full height, home on the left, terminal on the right.
                val shownX = kotlin.math.abs(liftX)
                if (shownX > 1f) {
                    val toHome = liftX > 0
                    val progress = (shownX / sideAtArm).coerceIn(0f, 1f)
                    val ready = sideArmed
                    val shape = RoundedCornerShape(22.dp)
                    Box(Modifier.align(if (toHome) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight()
                        .width(with(density) { shownX.toDp() }).padding(vertical = 10.dp, horizontal = 8.dp)
                        .clip(shape).background(if (ready) Ph.AmberWash else Ph.Surface)
                        .border(1.dp, if (ready) Ph.AmberLine else Ph.Line, shape)) {
                        Box(Modifier.align(if (toHome) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(progress)
                            .background(Ph.Amber.copy(alpha = if (ready) 0.16f else 0.08f)))
                        val pop by androidx.compose.animation.core.animateFloatAsState(if (ready) 1.2f else 1f,
                            androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "sidePop")
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 6.dp).graphicsLayer { alpha = (progress * 2.2f).coerceAtMost(1f) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(44.dp).graphicsLayer { scaleX = pop; scaleY = pop }.clip(RoundedCornerShape(22.dp))
                                .background(if (ready) Ph.Amber else Ph.Chip), contentAlignment = Alignment.Center) {
                                PhIcon(if (toHome) PhIcons.Back else PhIcons.Terminal, if (ready) Ph.AmberInk else Ph.Amber, 20.dp)
                            }
                            Text(if (toHome) "Home" else "Terminal", style = Ph.title.copy(fontSize = 14.sp, color = if (ready) Ph.Amber else Ph.Text),
                                maxLines = 1)
                            Text(if (ready) "release" else "keep going", style = Ph.monoSmall.copy(color = if (ready) Ph.Amber else Ph.Muted),
                                maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
                // Behind the chat as it slides up, the whole width: like a task card's swipe on home.
                val shown = -lift
                if (shown > 1f) {
                    val progress = (shown / liftAtArm).coerceIn(0f, 1f)
                    val ready = armed
                    val shape = RoundedCornerShape(22.dp)
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(with(density) { shown.toDp() })
                        .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 10.dp)
                        .clip(shape).background(if (ready) Ph.AmberWash else Ph.Surface)
                        .border(1.dp, if (ready) Ph.AmberLine else Ph.Line, shape)) {
                        // Amber rising as you pull.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(progress)
                            .background(Ph.Amber.copy(alpha = if (ready) 0.16f else 0.08f)))
                        val pop by androidx.compose.animation.core.animateFloatAsState(if (ready) 1.2f else 1f,
                            androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "pop")
                        Column(Modifier.align(Alignment.Center).graphicsLayer { alpha = (progress * 2.5f).coerceAtMost(1f) },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(44.dp).graphicsLayer { scaleX = pop; scaleY = pop }.clip(RoundedCornerShape(22.dp))
                                .background(if (ready) Ph.Amber else Ph.Chip), contentAlignment = Alignment.Center) {
                                PhIcon(PhIcons.Plus, if (ready) Ph.AmberInk else Ph.Amber, 22.dp,
                                    modifier = Modifier.graphicsLayer { rotationZ = 90f * progress })
                            }
                            Text(if (ready) "Release to start a new task" else "Keep pulling for a new task",
                                style = Ph.mono.copy(color = if (ready) Ph.Amber else Ph.TextSoft))
                        }
                    }
                }
                }

                // ---- composer
                Column(Modifier.imePadding().navigationBarsPadding().padding(bottom = 4.dp, top = 6.dp)) {
                    AnimatedVisibility(running, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Row(Modifier.padding(horizontal = 18.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Toggle("steer", !followUp) { followUp = false }
                            Toggle("follow-up", followUp) { followUp = true }
                            Box(Modifier.weight(1f))
                            Text(if (followUp) "sent when Saturn finishes" else "sent after current step", style = Ph.monoSmall)
                        }
                    }
                    val pick = rememberAttachPicker { attachments.addAll(it) }
                    // What this model can think at; asked again whenever the model changes.
                    LaunchedEffect(t.model) { UiStore.rpc(t, "get_available_thinking_levels") }
                    val thinkLevels = t.responses["get_available_thinking_levels"]?.optJSONArray("levels")
                        ?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    // The fill takes the new level once the reply is done (it changes after every step while running),
                    // so it animates once per reply.
                    val fillNow = t.contextWindow.takeIf { it > 0 && t.contextTokens > 0 }?.let { t.contextTokens / it.toFloat() }
                    var fillSettled by remember(t.task.id) { mutableStateOf(fillNow) }
                    LaunchedEffect(t.task.id, running, fillNow) { if (!running) fillSettled = fillNow }
                    Composer(
                        contextFill = fillSettled,
                        contextKey = t.task.id,
                        pulls = buildList {
                            if (!running && t.contextTokens > 0) add(PullAction(PhIcons.Compact, "Compact", "Release to compact",
                                note = t.contextWindow.takeIf { it > 0 }?.let { w -> "${(t.contextTokens * 100L / w)}% full" }) { UiStore.rpc(t, "compact") })
                            if (t.items.isNotEmpty()) add(PullAction(PhIcons.Share, "Share", "Release to share", note = "as markdown") { shareMarkdown(ctx, t) })
                        },
                        thinking = thinkLevels?.let { ThinkingControl(t.thinkingLevel, it) { l ->
                            UiStore.chooseInChat(t, thinking = l, forProject = UiStore.thinkingSource(t) == UiStore.Source.PROJECT) } },
                        value = input, onValue = { input = it },
                        placeholder = "Ask Saturn · task #${t.task.number}…",
                        commands = commands,
                        attachments = attachments.toList(), attach = pick, onRemoveAttachment = { attachments.remove(it) },
                        onSend = { text ->
                            input = TextFieldValue("")
                            if (t.editingLast && attachments.isEmpty() && !running) { t.editingLast = false; UiStore.retry(t, text) }
                            else if (attachments.isNotEmpty() || !dispatchSlash(text, builtins)) {
                                t.editingLast = false
                                UiStore.send(t, text, followUp, attachments.toList()); attachments.clear()
                            }
                        },
                        extra = {
                            // Editing your last message: say so, and let it be cancelled (clears the box).
                            if (t.editingLast) Row(
                                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Ph.AmberWash)
                                    .border(1.dp, Ph.AmberLine, RoundedCornerShape(14.dp))
                                    .clickable(onClickLabel = "Stop editing") { t.editingLast = false; input = TextFieldValue("") }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                PhIcon(PhIcons.Pencil, Ph.Amber, 13.dp)
                                Text("editing", style = Ph.monoSmall.copy(color = Ph.Amber))
                                PhIcon(PhIcons.Close, Ph.Amber, 11.dp, "Stop editing")
                            }
                            if (running) Row(
                                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(Ph.RedWash)
                                    .border(1.dp, Ph.RedLine, RoundedCornerShape(14.dp))
                                    .clickable(enabled = !t.stopping, onClickLabel = "Stop task") { UiStore.stop(t) }
                                    .padding(horizontal = if (t.stopping) 12.dp else 15.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                if (t.stopping) { PulseDot(Ph.Red, 6); Text("stopping", style = Ph.monoSmall.copy(color = Ph.Red)) }
                                else PhIcon(PhIcons.Stop, Ph.Red, 14.dp, "Stop task")
                            }
                        },
                    )
                    Box(Modifier.size(6.dp))
                }
            }
        }
    }

    when (sheet) {
        "model" -> ModelPopup(ChoiceTarget.Chat(t)) { sheet = null }
        "rename" -> RenameSheet(t) { sheet = null }
        "settings" -> SettingsSheet { sheet = null }
        "login" -> LoginSheet(initialQuery = loginArg) { sheet = null }
        "logout" -> LoginSheet(logoutMode = true) { sheet = null }
        "thinking" -> ModelPopup(ChoiceTarget.Chat(t)) { sheet = null }
        "session" -> SessionSheet(t) { sheet = null }
        "tree" -> TreeSheet(t) { sheet = null }
        "fork" -> ForkSheet(t) { sheet = null }
        "resume" -> ResumeSheet(onOpen = { other -> if (other !== t) onOpenTask(other) }) { sheet = null }
        "changelog" -> ChangelogSheet { sheet = null }
        "menu" -> {
            fun cmd(name: String) = { builtins.first { it.name == name }.run("") }
            TaskMenuSheet(t, buildList {
                add(MenuAction("Rename", PhIcons.Pencil) { sheet = "rename" })
                add(MenuAction("Model", PhIcons.Cube, hint = io.github.pimobile.ui.state.modelLabel(t.model ?: UiStore.model)?.first) { sheet = "model" })
                add(MenuAction("Session info", PhIcons.Info) { sheet = "session" })
                add(MenuAction("Session tree", PhIcons.Branch) { sheet = "tree" })
                add(MenuAction("Copy last reply", PhIcons.Copy, run = cmd("copy")))
                add(MenuAction("Export & share", PhIcons.Share, hint = "html", run = cmd("export")))
                add(MenuAction("Branch chat", PhIcons.Fork) {
                    if (running) UiStore.branchError = "wait until Saturn finishes, or stop it" else UiStore.branch(t)
                })
                if (running) add(MenuAction("Stop run", PhIcons.Stop) { UiStore.stop(t) })
                add(MenuAction("Delete task", PhIcons.Trash, danger = true, confirm = "Tap again to delete") {
                    onBack(); UiStore.delete(t) })
            }) { if (sheet == "menu") sheet = null }
        }
    }
}

@Composable
private fun Header(t: TaskUi, tab: String, browser: Boolean, collapse: Float, onSaturn: (androidx.compose.ui.geometry.Offset) -> Unit,
                   onModel: () -> Unit, onThinking: () -> Unit,
                   onTab: (String) -> Unit, onBack: () -> Unit, onMenu: () -> Unit,
                   lampOpen: Boolean = false, onLamp: (Boolean) -> Unit = {}) {
    val running = t.status == Status.RUNNING || t.status == Status.STARTING
    val failed = t.status == Status.FAILED || (t.lastError != null && !running)
    val status = when {
        t.status == Status.STARTING -> "starting"; t.status == Status.RUNNING -> "working"; t.status == Status.NEEDS_INPUT -> "needs you"
        failed -> "failed"; t.status == Status.STOPPED -> "paused"; t.items.isEmpty() -> "ready"; else -> "done"
    }
    val tint = when { failed -> Ph.Red; running || t.status == Status.NEEDS_INPUT -> Ph.Amber; status == "done" -> Ph.Green; else -> Ph.Muted }
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (tab == "chat") "Back to tasks" else "Back to chat") { onBack() },
                contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(displayTitle(t.title), style = Ph.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Folded: the status moves up here, one line.
                // It opens up gradually (no jump in the title row's height) and fades in once there's room.
                if (collapse > 0.01f) Box(Modifier.clipToBounds().foldAway(1f - collapse)) {
                    Row(Modifier.padding(top = 2.dp).graphicsLayer { alpha = ((collapse - 0.4f) / 0.6f).coerceIn(0f, 1f) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (running || t.status == Status.NEEDS_INPUT) PulseDot(tint, 6)
                        else Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
                        Text("#${t.task.number} · $status · ${t.task.cwd.name}", style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (browser) HeaderIcon(PhIcons.Globe, tab == "browser", if (tab == "browser") "Back to chat" else "Show browser", dot = true) {
                onTab(if (tab == "browser") "chat" else "browser") }
            if (tab == "chat") ReadingIcon(lampOpen, onLamp)
            HeaderIcon(PhIcons.Terminal, tab == "terminal", if (tab == "terminal") "Back to chat" else "Open terminal") {
                onTab(if (tab == "terminal") "chat" else "terminal") }
            HeaderIcon(PhIcons.More, false, "Task options") { onMenu() }
        }
        // Open: a small Saturn in text, turning while it works, and where things stand.
        // Long-press the book icon and this band becomes the reading lamp for a moment.
        // Follows [collapse] directly (one motion, no second animation lagging behind it); the lamp
        // opening it is its own small animation, combined with it.
        val lampOut by androidx.compose.animation.core.animateFloatAsState(if (lampOpen) 0f else 1f,
            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 320f), label = "lamp")
        val bandFold = collapse * lampOut
        Box(Modifier.fillMaxWidth().clipToBounds().foldAway(bandFold)) {
          androidx.compose.animation.Crossfade(lampOpen, label = "lamp") { lamp ->
            if (lamp) LampSlider(onTouch = { onLamp(true) })
            else
            // Fades out in the first part of the fold, so it's gone before it gets squeezed (and in late on the way back).
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp).graphicsLayer {
                    alpha = (1f - bandFold / 0.6f).coerceIn(0f, 1f); translationY = -bandFold * 10.dp.toPx() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val helpers by remember(t) { androidx.compose.runtime.derivedStateOf { Shapes.liveHelpers(t.items) } }
                MiniSaturn(spinning = running, needsYou = t.status == Status.NEEDS_INPUT, helpers = helpers, modifier =
                    Modifier.width(118.dp).onGloballyPositioned { c -> onSaturn(c.boundsInRoot().center) }
                        .clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Model and thinking for this chat") { UiStore.markHint("tap-saturn"); onModel() })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running || t.status == Status.NEEDS_INPUT) PulseDot(tint, 7)
                        else Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                        androidx.compose.animation.AnimatedContent(status, label = "status") { st ->
                            Text(st.replaceFirstChar { it.uppercase() }, style = Ph.title.copy(fontSize = 17.sp, color = if (failed) Ph.Red else Ph.Text))
                        }
                    }
                    val time = elapsed(if (running) t.runStartedAt else null)
                    Text(listOfNotNull("#${t.task.number}", t.task.cwd.name, time.takeIf { it.isNotEmpty() },
                        t.tools.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "step" else "steps"}" },
                        t.cost.takeIf { it >= 0.005 }?.let { String.format(Locale.US, "$%.2f", it) }).joinToString(" · "),
                        style = Ph.monoSmall.copy(color = Ph.TextSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // What used to sit under the message box: model and thinking (tap to change), context size.
                    Row(Modifier.offset(x = (-6).dp), verticalAlignment = Alignment.CenterVertically) {
                        val tap = Modifier.clip(RoundedCornerShape(8.dp))
                        // A small mark when this chat doesn't use the app-wide default: a folder (its project's) or a dot (its own).
                        val src = listOf(UiStore.modelSource(t), UiStore.thinkingSource(t))
                        if (UiStore.Source.CHAT in src) Box(Modifier.padding(start = 6.dp).size(5.dp).clip(RoundedCornerShape(3.dp)).background(Ph.Amber))
                        else if (UiStore.Source.PROJECT in src) PhIcon(PhIcons.Folder, Ph.Amber, 11.dp, "Project setting", Modifier.padding(start = 6.dp))
                        Text(io.github.pimobile.ui.state.modelLabel(t.model ?: UiStore.model)?.first ?: "model", style = Ph.monoSmall.copy(color = Ph.Amber),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).then(tap).clickable(onClickLabel = "Change model") { onModel() }.padding(horizontal = 6.dp, vertical = 6.dp))
                        t.thinkingLevel?.let {
                            Text("·", style = Ph.monoSmall)
                            Text(it, style = Ph.monoSmall.copy(color = Ph.Amber), modifier = tap.clickable(onClickLabel = "Change thinking level") { onThinking() }
                                .padding(horizontal = 6.dp, vertical = 6.dp))
                        }
                        if (t.contextTokens > 0) Text("· " + String.format(Locale.US, "%.1fk ctx", t.contextTokens / 1000.0), style = Ph.monoSmall,
                            modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                    // Until it's been used once: the little Saturn on the left opens this chat's model and thinking.
                    Hint("tap-saturn", "tap Saturn for model & thinking", arrow = "←")
                }
            }
          }
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(Ph.Line.copy(alpha = 0.4f + 0.6f * collapse)))
    }
}

/** Shrinks to nothing as [f] goes 0 → 1, keeping the top. */
private fun Modifier.foldAway(f: Float) = this.then(Modifier.layout { m, c ->
    val p = m.measure(c)
    val h = (p.height * (1f - f)).toInt().coerceAtLeast(0)
    layout(p.width, h) { p.place(0, 0) }
})

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, label: String, dot: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Ph.Selected else Color.Transparent)
        .clickable(onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        PhIcon(icon, if (on) Ph.Amber else Ph.TextSoft, 19.dp, label)
        if (dot && !on) Box(Modifier.align(Alignment.TopEnd).padding(9.dp)) { PulseDot(Ph.Cyan, 5) }
    }
}

/** Saturn's mark and name at the start of each of its turns. */
@Composable
private fun SaturnLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SaturnMark(Modifier.size(18.dp))
        Text("SATURN", style = Ph.label.copy(fontSize = 10.5.sp, color = Ph.Amber))
    }
}

/**
 * Saturn's work for one answer. Running: a still card where only the current
 * step's line rolls up (terminal-style), a spinner and a growing strip of dots.
 * Done: "Worked · 59 steps", the dots, a summary. Tap for one line per step.
 */
@Composable
private fun WorkBlock(b: Block.Work, live: Boolean, startedAt: Long?) {
    val tools = b.items.filterIsInstance<Item.Tool>()
    val failed = tools.count { it.state == io.github.pimobile.ui.state.ToolState.ERROR }
    val active = live || tools.any { it.state == io.github.pimobile.ui.state.ToolState.RUNNING }
    // Open while Saturn works (you see every step land), folded once the answer starts; a tap overrides.
    var choice by rememberSaveable(b.key) { mutableStateOf<Boolean?>(null) }
    val open = choice ?: active
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ph.Surface)
        .border(1.dp, if (active) Ph.AmberLine else Ph.Line, shape)
        .clickable(onClickLabel = if (open) "Hide steps" else "Show steps") { choice = !open }
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        val few = tools.size <= 3
        // Header: what it's doing / did, and (for longer runs) the dots on the right.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            var frame by remember { mutableStateOf(0) }
            if (active) {
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(90); frame++ } }
                Text("⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[frame % 10].toString(), style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Amber))
                Text("Working", style = Ph.title.copy(fontSize = 14.5.sp))
                Text("· step ${tools.size}", style = Ph.mono.copy(color = Ph.Muted))
            } else {
                Text("Worked", style = Ph.title.copy(fontSize = 14.5.sp, color = Ph.TextSoft))
                Text("· ${tools.size} ${if (tools.size == 1) "step" else "steps"}", style = Ph.mono.copy(color = Ph.Muted))
                if (failed > 0) Text("· $failed failed", style = Ph.mono.copy(color = Ph.Red))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!few) StepDots(tools)
                else if (active && startedAt != null) Text(elapsed(startedAt).let { _ -> frame; elapsed(startedAt) }, style = Ph.monoSmall)
            }
            PhIcon(PhIcons.Chevron, Ph.Muted, 13.dp, modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f })
        }
        // Re-read only when a tool's data changes (not on every spinner frame).
        val rich by remember(b.key) { androidx.compose.runtime.derivedStateOf {
            val ts = b.items.filterIsInstance<Item.Tool>()
            val ct = ts.lastOrNull { Shapes.crew(it.details) != null }
            Triple(ct, ct?.let { Shapes.crew(it.details) }, ts.asReversed().firstNotNullOfOrNull { Shapes.checklist(it.details, it.output, core = it.name in CORE_TOOLS) })
        } }
        val (crewTool, crew, plan) = rich
        if (!open) {
            if (active && crew != null && crewTool?.state == io.github.pimobile.ui.state.ToolState.RUNNING) CrewPanel(crew, live = true)
            else if (few && !active) Column { tools.forEach { StepRow(it, compact = true) } }
            else if (active) {
                // Running: the current step's line, rolling up as each new one lands.
                val lastItem = b.items.lastOrNull()
                val current = when {
                    lastItem is Item.Tool -> lastItem.key to "${lastItem.name}  ${io.github.pimobile.ui.components.toolTarget(lastItem.name, lastItem.args)}"
                    else -> (lastItem?.key ?: "idle") to "thinking…"
                }
                androidx.compose.animation.AnimatedContent(current, label = "step", contentKey = { it.first },
                    transitionSpec = {
                        (slideInVertically(androidx.compose.animation.core.tween(220)) { it } + fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
                            (slideOutVertically(androidx.compose.animation.core.tween(180)) { -it } + fadeOut(androidx.compose.animation.core.tween(150))) using
                            androidx.compose.animation.SizeTransform(clip = true)
                    }) { (_, line) ->
                    Text(line.trim(), style = Ph.mono.copy(fontSize = 12.sp, color = Ph.TextSoft), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(start = 2.dp))
                }
            } else Text(shortSummary(tools), style = Ph.monoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!active && crew != null) Text(listOfNotNull("${crew.lanes.size} ${if (crew.lanes.size == 1) "helper" else "helpers"}",
                crew.lanes.count { it.state == Shapes.LaneState.FAILED }.takeIf { it > 0 }?.let { "$it failed" },
                crew.cost.takeIf { it >= 0.005 }?.let { String.format(Locale.US, "$%.2f", it) }).joinToString(" · "),
                style = Ph.monoSmall.copy(color = Ph.TextSoft))
            plan?.let { ChecklistCard(it, compact = true) }
        }
        // Open: one line per step.
        AnimatedVisibility(open, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                b.items.forEach { item ->
                    when (item) {
                        is Item.Tool -> RichStep(item)
                        is Item.Assistant -> {
                            if (item.thinking.isNotBlank()) ThinkRow(item.key, item.thinking)
                            if (item.text.isNotBlank()) Text(item.text.trim(), style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.TextBody),
                                modifier = Modifier.padding(vertical = 4.dp))
                        }
                        else -> {}
                    }
                }
                b.finalThinking?.let { ThinkRow(it.key + "-f", it.thinking) }
            }
        }
    }
}

/** One dot per step (green done, red failed, amber running) in a single right-aligned row; "+n" when they don't all fit. */
@Composable
private fun StepDots(tools: List<Item.Tool>) {
    if (tools.isEmpty()) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val d = 6.dp; val g = 4.dp
        val fit = ((maxWidth + g) / (d + g)).toInt().coerceAtLeast(1)
        // Not enough room for all of them: show none (the header already says how many).
        if (tools.size > fit) return@BoxWithConstraints
        val shown = tools
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.foundation.Canvas(Modifier.size(width = d * shown.size + g * (shown.size - 1), height = d)) {
                val dp = d.toPx(); val gp = g.toPx()
                shown.forEachIndexed { i, tl ->
                    val c = when (tl.state) {
                        io.github.pimobile.ui.state.ToolState.RUNNING -> Ph.Amber
                        io.github.pimobile.ui.state.ToolState.ERROR -> Ph.Red
                        else -> Ph.Green.copy(alpha = 0.7f)
                    }
                    drawCircle(c, dp / 2, androidx.compose.ui.geometry.Offset(i * (dp + gp) + dp / 2, dp / 2))
                }
            }
        }
    }
}

/** "4 commands · 1 read · 1 edit": short enough for one line. */
private fun shortSummary(tools: List<Item.Tool>): String {
    val by = tools.groupingBy { it.name.lowercase() }.eachCount()
    fun n(c: Int, one: String, many: String) = "$c ${if (c == 1) one else many}"
    val known = setOf("bash", "read", "edit", "write", "grep", "find", "ls")
    return buildList {
        by["bash"]?.let { add(n(it, "command", "commands")) }
        by["read"]?.let { add(n(it, "read", "reads")) }
        ((by["edit"] ?: 0) + (by["write"] ?: 0)).takeIf { it > 0 }?.let { add(n(it, "edit", "edits")) }
        ((by["grep"] ?: 0) + (by["find"] ?: 0) + (by["ls"] ?: 0)).takeIf { it > 0 }?.let { add(n(it, "search", "searches")) }
        val other = tools.size - by.filterKeys { it in known }.values.sum()
        if (other > 0) add(n(other, "other tool", "other tools"))
    }.joinToString(" · ")
}

private val CORE_TOOLS = setOf("read", "bash", "edit", "write", "grep", "find", "ls")

/** A step, with the card its data calls for: a crew's lanes, a plan's checklist, or a progress bar under the line. */
@Composable
private fun RichStep(tool: Item.Tool) {
    val d = tool.details
    val crew = remember(d) { Shapes.crew(d) }
    if (crew != null) { Box(Modifier.padding(vertical = 4.dp)) { CrewPanel(crew, live = tool.state == io.github.pimobile.ui.state.ToolState.RUNNING) }; return }
    StepRow(tool)
    val plan = remember(d, tool.output) { Shapes.checklist(d, tool.output, core = tool.name in CORE_TOOLS) }
    if (plan != null) { Box(Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)) { ChecklistCard(plan) }; return }
    val prog = remember(d) { Shapes.progress(d) }
    if (prog != null && tool.state == io.github.pimobile.ui.state.ToolState.RUNNING) ProgressLine(prog)
}

/** A step as one mono line: ✓/✗ name target. Tap for its full command and output. */
@Composable
private fun StepRow(tool: Item.Tool, compact: Boolean = false) {
    var open by rememberSaveable(tool.key) { mutableStateOf(false) }
    val st = tool.state
    Column {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = if (compact) 1.dp else 2.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (st) { io.github.pimobile.ui.state.ToolState.RUNNING -> "◌"; io.github.pimobile.ui.state.ToolState.ERROR -> "✗"; else -> "✓" },
                style = Ph.mono.copy(color = when (st) { io.github.pimobile.ui.state.ToolState.RUNNING -> Ph.Amber; io.github.pimobile.ui.state.ToolState.ERROR -> Ph.Red; else -> Ph.Green }))
            Text(tool.name, style = Ph.mono.copy(color = Ph.Text), modifier = Modifier.widthIn(min = 44.dp))
            Text(io.github.pimobile.ui.components.toolTarget(tool.name, tool.args), style = Ph.mono.copy(color = Ph.Muted),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open) { Box(Modifier.padding(bottom = 6.dp)) { ToolPanel(listOf(tool)) } }
    }
}

@Composable
private fun ThinkRow(key: String, thinking: String) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 2.dp)) {
        Text("┊ " + if (open) "thinking" else thinking.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim(),
            style = Ph.mono.copy(color = Ph.Muted.copy(alpha = 0.8f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        AnimatedVisibility(open) {
            Text(thinking.trim(), style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 4.dp, start = 12.dp))
        }
    }
}

private fun toolSummary(tools: List<Item.Tool>, running: Boolean): String {
    fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
    val by = tools.groupingBy { it.name.lowercase() }.eachCount()
    val known = setOf("bash", "read", "edit", "write", "grep", "find", "ls")
    val parts = buildList {
        by["bash"]?.let { add("ran " + n(it, "command", "commands")) }
        by["read"]?.let { add("read " + n(it, "file", "files")) }
        ((by["edit"] ?: 0) + (by["write"] ?: 0)).takeIf { it > 0 }?.let { add("edited " + n(it, "file", "files")) }
        ((by["grep"] ?: 0) + (by["find"] ?: 0) + (by["ls"] ?: 0)).takeIf { it > 0 }?.let { add("searched " + n(it, "time", "times")) }
        val other = tools.size - by.filterKeys { it in known }.values.sum()
        if (other > 0) add("used " + n(other, "tool", "tools"))
    }
    val text = parts.joinToString(", ")
    if (running) return "Working: " + tools.last { it.state == io.github.pimobile.ui.state.ToolState.RUNNING }.name + " · " + text
    return text.replaceFirstChar { it.uppercase() }
}

/** Under the composer: the model and thinking level (tap to change) and how full the context is. */
@Composable
private fun ModelLine(t: TaskUi, onModel: () -> Unit, onThinking: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val tap = Modifier.clip(RoundedCornerShape(8.dp))
        Text(io.github.pimobile.ui.state.modelLabel(t.model ?: UiStore.model)?.first ?: "model", style = Ph.monoSmall, maxLines = 1,
            modifier = tap.clickable(onClickLabel = "Change model") { onModel() }.padding(horizontal = 8.dp, vertical = 8.dp))
        t.thinkingLevel?.let {
            Text("·", style = Ph.monoSmall)
            Text(it, style = Ph.monoSmall, modifier = tap.clickable(onClickLabel = "Change thinking level") { onThinking() }.padding(horizontal = 8.dp, vertical = 8.dp))
        }
        if (t.contextTokens > 0) Text("·  " + String.format(Locale.US, "%.1fk ctx", t.contextTokens / 1000.0), style = Ph.monoSmall, modifier = Modifier.padding(end = 8.dp))
        if (t.cost >= 0.005) Text("· " + String.format(Locale.US, "$%.2f", t.cost), style = Ph.monoSmall)
    }
}

@Composable
private fun TerminalBusy(onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        PulseDot(Ph.Amber, 9)
        Text("Saturn is working on this task", style = Ph.title, modifier = Modifier.padding(top = 16.dp))
        Text("The terminal opens this same session in pi's own TUI once the run is idle.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Text("Stop the run", style = Ph.title.copy(fontSize = 14.sp, color = Ph.Red),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.RedLine, RoundedCornerShape(12.dp))
                .clickable { onStop() }.padding(horizontal = 18.dp, vertical = 11.dp))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = Ph.monoSmall.copy(color = if (on) Ph.Amber else Ph.Muted),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Ph.AmberWash else Color.Transparent)
            .border(1.dp, if (on) Ph.AmberLine else Ph.Line, RoundedCornerShape(8.dp))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun Working(t: TaskUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PulseDot(Ph.Amber, 7)
        Text(when { t.stopping -> "stopping…"; t.status == Status.STARTING -> "starting Saturn"; else -> "working · ${elapsed(t.runStartedAt)}" }, style = Ph.mono.copy(color = Ph.Muted))
        BlinkCursor()
    }
}

@Composable
private fun Row1(t: TaskUi, item: Item, hideThinking: Boolean = false, latest: Boolean = false, onSheet: (String) -> Unit = {}) {
    when (item) {
        is Item.User -> UserMessage(t, item)
        is Item.Assistant -> {
            // Copy / speak / flag sit under the latest reply only; tap an older one to bring them up.
            val isLatest = t.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } === item
            var showActions by remember(item.key) { mutableStateOf(false) }
            val canAct = item.text.isNotBlank() && !item.streaming && endOfTurn(t, item)
            Column(Modifier.then(if (canAct && !isLatest) Modifier.pointerInput(item.key) {
                detectTapGestures { showActions = !showActions } } else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.thinking.isNotBlank() && !hideThinking) Thinking(item)
                val cap = rememberCapture()
                if (item.text.isNotBlank()) SelectionContainer { Markdown(item.text, Modifier.capture(cap)) }
                AnimatedVisibility(canAct && (isLatest || showActions)) { ReplyActions(t, item, cap) }
            }
        }
        is Item.Notice -> if (item.error) ErrorCard(t, item.text, actionable = latest && !t.status.let { it == Status.RUNNING || it == Status.STARTING }, onSheet)
            else if (item.text.startsWith("Retrying (")) RetryLine(item.text)
            else Text(item.text, style = Ph.mono.copy(color = Ph.Muted))
        is Item.Dialog -> DialogCard(t, item)
        is Item.Tool -> ToolPanel(listOf(item))
    }
}

@Composable
private fun Thinking(item: Item.Assistant) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 2.dp)) {
        Text(
            (if (open) "▾ thinking" else "▸ thinking  ") + if (open) "" else item.thinking.lines().lastOrNull { it.isNotBlank() }.orEmpty(),
            style = Ph.mono.copy(color = Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        AnimatedVisibility(open) {
            Text(item.thinking.trim(), style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp, lineHeight = 20.sp),
                modifier = Modifier.padding(top = 6.dp, start = 12.dp))
        }
    }
}

@Composable
private fun DialogCard(t: TaskUi, d: Item.Dialog) {
    var text by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Ph.Surface)
            .border(1.dp, if (d.answer == null) Ph.AmberLine else Ph.Line, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(d.title.ifBlank { "Saturn needs your input" }, style = Ph.title.copy(fontSize = 14.sp))
        if (d.message.isNotBlank()) Text(d.message, style = Ph.body.copy(fontSize = 13.sp))
        val answered = d.answer
        if (answered != null) Text("→ $answered", style = Ph.mono.copy(color = Ph.Green))
        else if (d.options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            d.options.forEach { o ->
                Text(o, style = Ph.mono.copy(color = Ph.Text), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).clickable { UiStore.answer(t, d, o) }
                    .padding(horizontal = 12.dp, vertical = 11.dp))
            }
            Text("dismiss", style = Ph.monoSmall, modifier = Modifier.clickable { UiStore.answer(t, d, null) }.padding(vertical = 6.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(text, { text = it }, textStyle = Ph.body.copy(color = Ph.Text), cursorBrush = SolidColor(Ph.Amber),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Ph.Ground)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).padding(12.dp),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text(d.placeholder.ifBlank { "Your answer" }, style = Ph.body.copy(color = Ph.Muted)); inner() } })
            SendButton(text.isNotBlank()) { UiStore.answer(t, d, text) }
        }
    }
}


/** Your message; tap it for Branch from here / Copy. Attached images show as thumbnails. */
@Composable
private fun UserMessage(t: TaskUi, item: Item.User) {
    var open by remember { mutableStateOf(false) }
    val lines = item.text.lines()
    val images = lines.filter { it.startsWith(UiStore.IMAGE_NOTE) }.map { it.removePrefix(UiStore.IMAGE_NOTE).removeSuffix("]") }
    val files = lines.filter { it.startsWith(UiStore.FILE_NOTE) }.map { it.removePrefix(UiStore.FILE_NOTE).removeSuffix("]") }
    val text = lines.filterNot { it.startsWith(UiStore.IMAGE_NOTE) || it.startsWith(UiStore.FILE_NOTE) }.joinToString("\n").trim()
    val context = androidx.compose.ui.platform.LocalContext.current
    val copy = copier()
    // Your message as a prompt line: an amber › and your words, like typing at a terminal.
    // No box; wrapped lines stay aligned after the ›.
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Message actions") { open = !open }
            .padding(vertical = 2.dp, horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Same size and line height as your text, sitting on its first line's baseline.
            Text("›", style = Ph.mono.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = Ph.Amber),
                modifier = Modifier.alignByBaseline())
            Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (text.isNotEmpty()) Text(text, style = Ph.body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = if (Ph.paper) Ph.Text else Color(0xFFF3E6D0)))
                if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { images.forEach { Thumb(it, 92) } }
                files.forEach { path ->
                    FileChip(java.io.File(path).name, onClick = {
                        runCatching {
                            val f = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                        }.onFailure { android.widget.Toast.makeText(context, "Can't open this file", android.widget.Toast.LENGTH_SHORT).show() }
                    })
                }
            }
        }
        AnimatedVisibility(open) {
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val users = t.items.filterIsInstance<Item.User>()
                val index = users.indexOf(item)
                val latest = index == users.lastIndex
                val busy = t.status == Status.RUNNING || t.status == Status.STARTING || t.status == Status.NEEDS_INPUT
                fun wait() = android.widget.Toast.makeText(context, "Wait until Saturn finishes, or stop it", android.widget.Toast.LENGTH_SHORT).show()
                // Your last message: change it in the box; sending replaces it and Saturn answers again.
                // (Older ones: Branch from here keeps this chat and starts a copy from that point.)
                if (latest && text.isNotEmpty()) MsgAction(PhIcons.Pencil, "Edit") {
                    if (busy) wait() else { t.prefill = text; t.editingLast = true; open = false }
                }
                // Your last message is answered again in place; an older one is sent again at the end.
                if (text.isNotEmpty()) MsgAction(PhIcons.Retry, if (UiStore.retrying(t)) "resending…" else "Resend") {
                    if (busy) wait() else { if (latest) UiStore.retry(t) else UiStore.send(t, text); open = false }
                }
                MsgAction(PhIcons.Fork, if (UiStore.branching) "branching…" else "Branch from here") {
                    if (busy) UiStore.branchError = "wait until Saturn finishes, or stop it"
                    else { UiStore.branch(t, index, text); open = false }
                }
                MsgAction(PhIcons.Copy, "Copy") { copy(text); open = false }
            }
        }
    }
}

@Composable
private fun MsgAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PhIcon(icon, Ph.TextSoft, 14.dp)
        Text(label, style = Ph.body.copy(fontSize = 13.sp, color = Ph.TextSoft))
    }
}


/** The last reply before your next message (or the end): where Copy · Report go. */
private fun endOfTurn(t: TaskUi, item: Item.Assistant): Boolean {
    val i = t.items.indexOf(item)
    if (i < 0) return false
    for (j in i + 1 until t.items.size) when (t.items[j]) {
        is Item.User -> return true
        is Item.Assistant -> if ((t.items[j] as Item.Assistant).text.isNotBlank()) return false
        else -> {}
    }
    return true
}

@Composable
private fun ReplyActions(t: TaskUi, item: Item.Assistant, capture: Capture? = null) {
    val copy = copier()
    var report by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val speaking = io.github.pimobile.ui.state.Speaker.speaking == item.key
    val idle = t.status != Status.RUNNING && t.status != Status.STARTING && t.status != Status.NEEDS_INPUT
    val last = t.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } === item
    Row(Modifier.offset(x = (-8).dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        ActIcon(PhIcons.Copy, "Copy reply") { copy(item.text) }
        // Share this reply: as an image of how it looks here, or as Markdown.
        Box {
            var menu by remember { mutableStateOf(false) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            ActIcon(PhIcons.Share, "Share reply") { menu = true }
            ShareMenu(menu, { menu = false }) { how ->
                scope.launch { runCatching { Export.share(ctx, how, item.text, displayTitle(t.title) + " · reply", capture) }
                    .onFailure { android.widget.Toast.makeText(ctx, "Couldn't share: ${it.message}", android.widget.Toast.LENGTH_SHORT).show() } }
            }
        }
        if (last && idle) {
            val busy = UiStore.retrying(t)
            Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy, onClickLabel = "Retry") { UiStore.retry(t) }
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (busy) PulseDot(Ph.Amber, 6) else PhIcon(PhIcons.Retry, Ph.TextSoft, 15.dp)
                Text(if (busy) "Retrying…" else "Retry", style = Ph.body.copy(fontSize = 12.5.sp, color = if (busy) Ph.Amber else Ph.TextSoft))
            }
        }
        ActIcon(if (speaking) PhIcons.Stop else PhIcons.Speaker, if (speaking) "Stop reading" else "Read aloud", if (speaking) Ph.Amber else Ph.Muted) {
            io.github.pimobile.ui.state.Speaker.toggle(ctx, item.key, item.text) }
        ActIcon(PhIcons.Flag, "Report reply") { report = true }
    }
    if (report) ReportSheet(t, item.text) { report = false }
}

@Composable
private fun ActIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Ph.Muted, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        PhIcon(icon, tint, 16.dp, label)
    }
}


/** The chat as Markdown (you, Saturn, and a line per tool step), shared as a .md file. */
private fun shareMarkdown(ctx: android.content.Context, t: TaskUi) {
    val md = buildString {
        append("# ").append(t.title.ifBlank { "Task #${t.task.number}" }).append("\n\n")
        append("_Saturn · task #${t.task.number} · ${t.task.cwd.name}_\n\n")
        t.items.forEach { item ->
            when (item) {
                is Item.User -> append("**You**\n\n").append(item.text.trim()).append("\n\n")
                is Item.Assistant -> if (item.text.isNotBlank()) append("**Saturn**\n\n").append(item.text.trim()).append("\n\n")
                is Item.Tool -> {
                    val arg = item.args.keys().asSequence().firstOrNull()?.let { item.args.optString(it) }?.lines()?.firstOrNull()?.take(120).orEmpty()
                    append("> `").append(item.name).append("`").append(if (arg.isNotBlank()) " $arg" else "")
                        .append(if (item.state == io.github.pimobile.ui.state.ToolState.ERROR) " (failed)" else "").append("\n\n")
                }
                is Item.Notice -> if (item.error) append("> ⚠ ").append(item.text.trim()).append("\n\n")
                else -> {}
            }
        }
    }
    val f = java.io.File(ctx.cacheDir, "exports/task-${t.task.number}.md").apply { parentFile?.mkdirs(); writeText(md) }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/markdown").putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, t.title)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share chat"))
}


/** A provider/run error in plain words: what happened, what to do, the raw text behind "details". */
private data class ErrorKind(val title: String, val hint: String, val actions: List<String>)

private fun classifyError(raw: String): ErrorKind {
    val e = raw.lowercase()
    fun has(vararg k: String) = k.any { it in e }
    return when {
        e.startsWith("extension error") -> ErrorKind("An extension failed", "One of pi's extensions hit an error. The task itself may be fine.", listOf("retry"))
        has("429", "rate limit", "rate_limit", "too many requests", "ratelimit") ->
            ErrorKind("Rate limited", "The AI is getting too many requests right now. Wait a moment, or switch to another model.", listOf("retry", "model"))
        has("402", "insufficient", "credit", "quota", "billing", "payment") ->
            ErrorKind("Out of credits", "This provider says your account has run out of credit or quota.", listOf("model", "login"))
        has("401", "403", "unauthorized", "forbidden", "invalid api key", "invalid_api_key", "authentication", "no api key", "not logged in", "api key") ->
            ErrorKind("Not signed in", "The provider didn't accept your sign-in or key. Log in again, or pick another model.", listOf("login", "model"))
        has("context length", "context_length", "maximum context", "too long", "too many tokens", "413", "prompt is too long", "context window") ->
            ErrorKind("Too much to send", "This chat is longer than the model can take in one go. Compact it, or use a bigger model.", listOf("compact", "model"))
        has("model not found", "no endpoints", "not a valid model", "model_not_found", "does not exist", "404", "unsupported model") ->
            ErrorKind("Model unavailable", "That model isn't available right now. Pick another one.", listOf("model", "retry"))
        has("timeout", "timed out", "econn", "network", "fetch failed", "socket", "enotfound", "eai_again", "offline") ->
            ErrorKind("Connection dropped", "Saturn couldn't reach the AI. Check your internet and try again.", listOf("retry"))
        has("capacity", "resource_exhausted", "model_capacity") ->
            ErrorKind("Model is busy", "The provider has no room for this model right now. Saturn retries by itself; you can also switch models.", listOf("retry", "model"))
        has("500", "502", "503", "529", "overloaded", "server error", "internal error", "bad gateway", "unavailable") ->
            ErrorKind("Provider is having trouble", "The AI service is overloaded or down for a moment. Try again, or switch models.", listOf("retry", "model"))
        else -> ErrorKind("Something went wrong", "The last step didn't finish. You can try it again.", listOf("retry"))
    }
}

@Composable
private fun ErrorCard(t: TaskUi, raw: String, actionable: Boolean, onSheet: (String) -> Unit) {
    val k = remember(raw) { classifyError(raw) }
    var details by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape)
        .drawBehind { drawRect(Ph.Red, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
        .padding(start = 18.dp, end = 14.dp, top = 13.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PhIcon(PhIcons.Warning, Ph.Red, 15.dp)
            Text(k.title, style = Ph.title.copy(fontSize = 14.5.sp), modifier = Modifier.weight(1f))
            Text(if (details) "hide" else "details", style = Ph.monoSmall,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { details = !details }.padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(k.hint, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.TextBody))
        AnimatedVisibility(details) {
            SelectionContainer {
                Text(raw.trim(), style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.Muted), modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Ph.Ground).padding(10.dp))
            }
        }
        if (actionable) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            k.actions.forEachIndexed { i, a ->
                val (label, icon) = when (a) {
                    "retry" -> "Retry" to PhIcons.Retry
                    "model" -> "Switch model" to PhIcons.Cube
                    "login" -> "Log in" to PhIcons.Key
                    else -> "Compact" to PhIcons.Compact
                }
                val primary = i == 0
                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(if (primary) Ph.AmberWash else Color.Transparent)
                    .border(1.dp, if (primary) Ph.AmberLine else Ph.LineStrong, RoundedCornerShape(10.dp))
                    .clickable {
                        when (a) {
                            "retry" -> UiStore.retry(t)
                            "model" -> onSheet("model")
                            "login" -> onSheet("login")
                            else -> UiStore.rpc(t, "compact")
                        }
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PhIcon(icon, if (primary) Ph.Amber else Ph.TextSoft, 14.dp)
                    Text(label, style = Ph.mono.copy(color = if (primary) Ph.Amber else Ph.TextSoft))
                }
            }
        }
    }
}

/** A URL as a title reads badly: show its host instead. */
internal fun displayTitle(title: String): String =
    if (title.startsWith("http://") || title.startsWith("https://"))
        runCatching { java.net.URI(title.trim().substringBefore(' ')).host?.removePrefix("www.") }.getOrNull() ?: title
    else title


/** pi retrying after a provider error: one quiet line ("↻ Retrying 2/3 · Rate limited"), raw text behind details. */
@Composable
private fun RetryLine(raw: String) {
    val count = raw.substringAfter("Retrying (").substringBefore(")")
    val why = raw.substringAfter("): ", "")
    val k = remember(why) { classifyError(why) }
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon(PhIcons.Retry, Ph.Amber, 13.dp)
            Text("Retrying $count", style = Ph.mono.copy(color = Ph.TextSoft))
            Text("· " + k.title, style = Ph.mono.copy(color = Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (why.isNotBlank()) Text(if (details) "hide" else "details", style = Ph.monoSmall,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { details = !details }.padding(horizontal = 6.dp, vertical = 3.dp))
        }
        AnimatedVisibility(details) {
            SelectionContainer {
                Text(why.trim(), style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.Muted), modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp)).background(Ph.Ground).padding(10.dp))
            }
        }
    }
}


/** The reading-mode switch. Tap: paper on/off (or close the lamp slider). Long-press: the lamp slider. */
@Composable
private fun ReadingIcon(lampOpen: Boolean, onLamp: (Boolean) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val on = UiStore.reading
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on || lampOpen) Ph.Selected else Color.Transparent)
        .semantics { contentDescription = if (on) "Reading mode on. Long-press for the lamp" else "Reading mode" }
        .pointerInput(lampOpen) {
            detectTapGestures(
                onTap = {
                    view.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    if (lampOpen) onLamp(false) else UiStore.toggleReading()
                },
                onLongPress = {
                    if (!UiStore.reading) UiStore.toggleReading()
                    view.buzz(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onLamp(true)
                },
            )
        }, contentAlignment = Alignment.Center) {
        PhIcon(if (lampOpen) PhIcons.Lamp else PhIcons.Book, if (on) Ph.Amber else Ph.TextSoft, 19.dp)
    }
}

/**
 * The reading lamp, in place of the Saturn band: a pill that fills with warm light from a little
 * lamp on the left. The beam's edge blooms past the fill, the lamp's halo grows with the level,
 * ten ticks light up along the foot. Slide or tap; a tick every 10%.
 */
@Composable
private fun LampSlider(onTouch: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val v = UiStore.shade
    val f by androidx.compose.animation.core.animateFloatAsState(v,
        androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 1100f), label = "lamp")
    var step by remember { mutableStateOf((v * 10).toInt()) }
    fun set(x: Float, w: Float) {
        val nv = (x / w).coerceIn(0f, 1f)
        UiStore.previewShade(nv); onTouch()
        val now = (nv * 10).toInt()
        if (now != step) { step = now; view.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK) }
    }
    val warm = Color(0xFFFFC46B); val pale = Color(0xFFFFF0CC); val ink = Color(0xFF3B2608)
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 14.dp).height(78.dp)
        .shadow(14.dp, shape, ambientColor = Color(0xFF3B2608), spotColor = Color(0xFF3B2608))
        .clip(shape)
        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2A2219), Color(0xFF1C1712))))
        .pointerInput(Unit) { detectTapGestures { o -> set(o.x, size.width.toFloat()); UiStore.saveShade() } }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = { UiStore.saveShade() }) { c, _ -> c.consume(); set(c.position.x, size.width.toFloat()) }
        }
        .drawBehind {
            val edge = size.width * f
            // The beam: deep amber at the lamp, paling toward its edge.
            if (edge > 1f) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color(0xFFB9701C), 0.55f to warm, 1f to pale, startX = 0f, endX = edge),
                size = androidx.compose.ui.geometry.Size(edge, size.height))
            // Bloom: light spilling just past the edge.
            if (f > 0.01f) drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(pale.copy(alpha = 0.55f * f.coerceAtLeast(0.3f)), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(edge, size.height / 2f), radius = size.height * 0.9f),
                radius = size.height * 0.9f, center = androidx.compose.ui.geometry.Offset(edge, size.height / 2f))
            // A soft sheen along the top, like glass.
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
                endY = size.height * 0.45f))
            // Ten ticks along the foot; lit ones glow.
            val y = size.height - 10.dp.toPx()
            for (i in 1..9) {
                val x = size.width * i / 10f
                val lit = x <= edge
                drawLine(if (lit) ink.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f),
                    androidx.compose.ui.geometry.Offset(x, y - 4.dp.toPx()), androidx.compose.ui.geometry.Offset(x, y),
                    strokeWidth = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // The lamp: a little round button whose halo grows with the light.
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(46.dp).graphicsLayer { scaleX = 0.7f + 0.5f * f; scaleY = scaleX; alpha = 0.25f + 0.6f * f }
                    .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(pale.copy(alpha = 0.9f), Color.Transparent)),
                        androidx.compose.foundation.shape.CircleShape))
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (f > 0.05f) pale else Color(0xFF3A3026)), contentAlignment = Alignment.Center) {
                    PhIcon(PhIcons.Lamp, if (f > 0.05f) ink else Color(0xFFD9C7A8), 18.dp)
                }
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                val dark = f > 0.3f
                Text("Reading lamp", style = Ph.title.copy(fontSize = 14.5.sp, color = if (dark) ink else Color(0xFFF3E6CC)))
                Text(when { f < 0.05f -> "off · fresh paper"; f < 0.35f -> "soft"; f < 0.7f -> "evening"; else -> "lamp-lit" },
                    style = Ph.monoSmall.copy(color = if (dark) ink.copy(alpha = 0.7f) else Color(0xFFBFAF95)))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(f * 100).toInt()}", style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontSize = 30.sp, color = if (f > 0.82f) ink else Color(0xFFF3E6CC)))
                Text("%", style = Ph.monoSmall.copy(color = if (f > 0.85f) ink.copy(alpha = 0.7f) else Color(0xFFBFAF95)),
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            }
        }
    }
}

/** A tile of paper fibre: sparse light and dark specks plus a few short fibres, almost invisible on their own. */
private val paperGrain: androidx.compose.ui.graphics.ImageBitmap by lazy {
    val n = 192
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val rnd = java.util.Random(7)
    val px = IntArray(n * n)
    for (i in px.indices) {
        val v = rnd.nextGaussian()
        px[i] = when {
            v > 1.6 -> android.graphics.Color.argb(14, 255, 250, 235)
            v < -1.5 -> android.graphics.Color.argb(16, 90, 60, 25)
            else -> 0
        }
    }
    bmp.setPixels(px, 0, n, 0, 0, n, n)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.8f; strokeCap = android.graphics.Paint.Cap.ROUND }
    repeat(70) {
        val x = rnd.nextFloat() * n; val y = rnd.nextFloat() * n
        val a = rnd.nextFloat() * Math.PI.toFloat(); val l = 3f + rnd.nextFloat() * 7f
        paint.color = android.graphics.Color.argb(10 + rnd.nextInt(8), 110, 80, 40)
        c.drawLine(x, y, x + kotlin.math.cos(a) * l, y + kotlin.math.sin(a) * l, paint)
    }
    bmp.asImageBitmap()
}
