package io.github.pimobile.ui

import io.github.pimobile.ui.state.buzz

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager.Status
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.StatusBadge
import io.github.pimobile.ui.components.SweepBar
import io.github.pimobile.ui.components.diffStats
import io.github.pimobile.ui.components.toolTarget
import io.github.pimobile.ui.state.Item
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.ToolState
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import java.io.File
import java.util.Locale

private val ACTIVE = setOf(Status.STARTING, Status.RUNNING, Status.NEEDS_INPUT)

/** pi built-ins that only make sense inside a task. */
private val TASK_ONLY = setOf("tree", "fork", "clone", "session", "name", "new", "compact", "copy", "export",
    "thinking", "stop", "quit", "import", "share", "scoped-models", "hotkeys", "trust", "bug")

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    shared: SharedTransitionScope,
    visibility: AnimatedVisibilityScope,
    onStore: () -> Unit,
    onTerminal: () -> Unit,
    onArtifacts: () -> Unit,
    onUsage: () -> Unit,
    onOpen: (TaskUi) -> Unit,
) {
    val selected = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { selected.clear() }
    fun toggle(t: TaskUi) { if (t.task.id in selected) selected.remove(t.task.id) else selected.add(t.task.id) }
    val ctxT = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun toast(msg: String) = android.widget.Toast.makeText(ctxT, msg, android.widget.Toast.LENGTH_SHORT).show()
    val exportTo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val list = UiStore.tasks.filter { it.task.id in selected }.map { it.task }
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { io.github.pimobile.runtime.Transfer.export(ctxT, list, uri) } }
            r.onSuccess { n -> toast(if (n == 1) "Exported 1 task" else "Exported $n tasks"); selected.clear() }
                .onFailure { toast("Export failed: ${it.message}") }
        }
    }
    val importFrom = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { io.github.pimobile.runtime.Transfer.import(ctxT, uri) } }
            r.onSuccess { res ->
                res.added.forEach { UiStore.ui(it) }
                toast(buildString {
                    append(if (res.added.size == 1) "Imported 1 task" else "Imported ${res.added.size} tasks")
                    if (res.skipped > 0) append(" · ${res.skipped} already here or unreadable")
                })
            }.onFailure { toast("Import failed: ${it.message}") }
        }
    }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val attachments = remember { androidx.compose.runtime.mutableStateListOf<UiStore.Attachment>() }
    val homeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Shared from another app: text into the box, files as attachments; the user reviews and sends.
    UiStore.share?.let { sh -> LaunchedEffect(sh) {
        UiStore.share = null
        if (sh.text.isNotBlank()) input = TextFieldValue(sh.text, androidx.compose.ui.text.TextRange(sh.text.length))
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            sh.uris.mapNotNull { u -> runCatching { UiStore.attach(u) }.getOrNull() }
        }
        attachments.addAll(got)
        runCatching { homeFocus.requestFocus() }
    } }
    if (UiStore.focusComposer) LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350); UiStore.focusComposer = false
        runCatching { homeFocus.requestFocus(); keyboard?.show() }
    }
    val projectDir = UiStore.currentProject
    val project = projectDir.path
    val isGeneral = projectDir.canonicalPath == UiStore.runtime.projects.canonicalPath
    val projectLabel = if (isGeneral) "General" else projectDir.name
    val projectTasks = UiStore.tasksIn(projectDir)
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    // Two pages under Saturn: tasks and routines. Swipe Saturn (or tap the dots) to switch; it remembers.
    var page by rememberSaveable { mutableStateOf(UiStore.homePage) }
    LaunchedEffect(page) { UiStore.homePage = page }
    var routineEdit by remember { mutableStateOf<io.github.pimobile.runtime.Scheduler.Job?>(null) }
    val showList = projectTasks.isNotEmpty() || page == 1
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val spent = projectTasks.sumOf { it.cost }
    // Work going on in other projects stays visible from here.
    val elsewhere = UiStore.tasks.count { it.status in ACTIVE } - projectTasks.count { it.status in ACTIVE }

    // Read the chats you're likely to open next, quietly, once home has settled.
    LaunchedEffect(projectTasks.size, project) {
        kotlinx.coroutines.delay(900)
        UiStore.prefetch(projectTasks.filterNot { it.hidden || it.archived }.take(10))
    }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var heroDrag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var swipeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val hostView = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val heroPx = with(density) { 200.dp.toPx() }
    val heroSize by remember { androidx.compose.runtime.derivedStateOf {
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }?.size?.toFloat()?.takeIf { it > 0f } ?: heroPx } }
    // Keyed on showList: the first task arriving must bring Saturn in without leaving the screen.
    val collapse by remember(showList) { androidx.compose.runtime.derivedStateOf {
        if (!showList || list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / heroSize).coerceIn(0f, 1f)
    } }
    // A short list can't scroll, so Saturn could never tuck away: pad the end so it always can.
    var fillerPx by remember { mutableStateOf(0) }
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo }.collect { info ->
            val items = info.visibleItemsInfo
            if (items.size < info.totalItemsCount || items.none { it.key == "hero" }) return@collect
            val content = items.filter { it.key != "filler" && it.key != "hero" }.sumOf { it.size } +
                info.mainAxisItemSpacing * (items.size - 1) + info.afterContentPadding
            val hero = items.first { it.key == "hero" }.size
            fillerPx = (info.viewportSize.height - content - info.mainAxisItemSpacing).coerceAtLeast(0).let { if (it > hero * 3) 0 else it }
        }
    }
    // Typing: tuck Saturn away so the tasks and the box you're typing in have the room.
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && showList && list.firstVisibleItemIndex == 0) list.animateScrollToItem(1)
    }
    // Let go halfway and Saturn settles: fully open or fully tucked away.
    LaunchedEffect(list) {
        androidx.compose.runtime.snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling || list.firstVisibleItemIndex != 0) return@collect
            val h = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "hero" }?.size ?: return@collect
            val o = list.firstVisibleItemScrollOffset
            if (o in 1 until h) {
                if (o > h * 0.35f) list.animateScrollToItem(1) else list.animateScrollToItem(0)
                hostView.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK)
            }
        }
    }

    // Pull down past the top (a long pull) to search every chat; home blurs behind the search.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var pull by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var pullJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val pullAt = with(density) { 110.dp.toPx() }
    val pullMax = with(density) { 190.dp.toPx() }
    val pullConn = remember(pullAt, pullMax) { object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        var armed = false
        fun crossed() {
            val now = pull >= pullAt
            if (now == armed) return
            armed = now
            hostView.buzz(when {
                android.os.Build.VERSION.SDK_INT >= 34 -> if (now) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    else android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                else -> android.view.HapticFeedbackConstants.CLOCK_TICK
            })
        }
        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (pull > 0f && available.y < 0f) {
                val d = maxOf(available.y, -pull); pull += d; crossed()
                return androidx.compose.ui.geometry.Offset(0f, d)
            }
            return androidx.compose.ui.geometry.Offset.Zero
        }
        override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                                  source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
            if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) {
                pullJob?.cancel()
                // Heavier the further it goes.
                pull = (pull + available.y * 0.55f * (1f - pull / pullMax).coerceAtLeast(0.06f)).coerceAtMost(pullMax); crossed()
                return androidx.compose.ui.geometry.Offset(0f, available.y)
            }
            return androidx.compose.ui.geometry.Offset.Zero
        }
        override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
            if (pull <= 0f) return androidx.compose.ui.unit.Velocity.Zero
            if (pull >= pullAt) { searchOpen = true; hostView.buzz(android.view.HapticFeedbackConstants.CONFIRM) }
            armed = false
            pullJob = scope.launch { androidx.compose.animation.core.animate(pull, 0f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 380f)) { v, _ -> pull = v } }
            return available
        }
    } }
    val blurR by androidx.compose.animation.core.animateDpAsState(if (searchOpen) 18.dp else 0.dp,
        androidx.compose.animation.core.tween(260), label = "blur")

    // Swiping sideways switches Tasks ↔ Routines: always on an empty home, and anywhere on the list when the
    // setting says swipes are for pages (the cards then don't swipe). Saturn leans along with your finger.
    val pageSwipe = Modifier.pointerInput(page, UiStore.swipePages, showList) {
        if (showList && !UiStore.swipePages) return@pointerInput
        val threshold = 72.dp.toPx()
        var crossed = false
        detectHorizontalDragGestures(
            onDragStart = { swipeJob?.cancel(); crossed = false },
            onDragEnd = {
                val go = (page == 0 && heroDrag < -threshold) || (page == 1 && heroDrag > threshold)
                if (go) { page = 1 - page; hostView.buzz(android.view.HapticFeedbackConstants.CONFIRM) }
                swipeJob = scope.launch { androidx.compose.animation.core.animate(heroDrag, 0f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f)) { v, _ -> heroDrag = v } }
            },
            onDragCancel = { swipeJob = scope.launch { androidx.compose.animation.core.animate(heroDrag, 0f) { v, _ -> heroDrag = v } } },
        ) { change, dx ->
            change.consume()
            val toward = (page == 0 && dx < 0) || (page == 1 && dx > 0) || (page == 0 && heroDrag < 0) || (page == 1 && heroDrag > 0)
            heroDrag = (heroDrag + dx * if (toward) 0.6f else 0.15f).coerceIn(-size.width * 0.45f, size.width * 0.45f)
            val now = kotlin.math.abs(heroDrag) > threshold && ((page == 0 && heroDrag < 0) || (page == 1 && heroDrag > 0))
            if (now != crossed) { crossed = now; hostView.buzz(android.view.HapticFeedbackConstants.CLOCK_TICK) }
        }
    }

    var loginArg by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val builtins = listOf(
        Builtin("login", "Sign in to a provider (subscriptions, API keys)", args = true) { a -> loginArg = a; sheet = "login" },
        Builtin("logout", "Remove a provider credential") { sheet = "logout" },
        Builtin("model", "Default model for all tasks", args = true) { a ->
            if (a.contains('/')) UiStore.setModel(null, a.substringBefore('/'), a.substringAfter('/')) else sheet = "model" },
        Builtin("resume", "Reopen an earlier session as a task") { sheet = "resume" },
        Builtin("project", "Choose the project folder", tag = "app") { sheet = "project" },
        Builtin("settings", "App settings", tag = "app") { sheet = "settings" },
        Builtin("reload", "Reload extensions & skills in idle tasks") { io.github.pimobile.runtime.TaskManager.restartIdle(); io.github.pimobile.ui.state.Control.restart(); UiStore.commands = emptyList() },
        Builtin("changelog", "What's new in pi") { sheet = "changelog" },
    )
    val commands = paletteOf(builtins)

    // Saturn's warm light, behind everything: follows Saturn as it scrolls and fades as it tucks away.
    var heroCenter by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val breathe by androidx.compose.animation.core.rememberInfiniteTransition(label = "glow").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(4_000), androidx.compose.animation.core.RepeatMode.Reverse), label = "breathe")
    Box(Modifier.fillMaxSize().background(Ph.Ground).drawBehind {
        val c = heroCenter
        if (c != null && showList) saturnGlow(c, 1f - collapse, breathe)
    }) {
        Column(Modifier.fillMaxSize().blur(blurR).statusBarsPadding()) {
            // ---- header (a selection bar while tasks are selected)
            if (selecting) Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBox(PhIcons.Close, "Clear selection") { selected.clear() }
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} selected", style = Ph.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text))
                    val visibleIds = projectTasks.filterNot { it.hidden }.map { it.task.id }
                    Text(if (selected.size < visibleIds.size) "select all" else "select none", style = Ph.monoSmall.copy(color = Ph.Amber),
                        modifier = Modifier.clickable {
                            if (selected.size < visibleIds.size) { selected.clear(); selected.addAll(visibleIds) } else selected.clear()
                        }.padding(vertical = 2.dp))
                }
                IconBox(PhIcons.Share, "Export selected") {
                    exportTo.launch("saturn-tasks-" + java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date()) + ".zip")
                }
                IconBox(PhIcons.Trash, "Delete selected") {
                    UiStore.deleteManyWithUndo(UiStore.tasks.filter { it.task.id in selected }); selected.clear()
                }
            } else Row(Modifier.padding(start = 10.dp + (4 * collapse).dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The big Saturn below tucks away as you scroll; this small one takes its place.
                Box(Modifier.size(width = (34 * collapse).dp, height = 34.dp).graphicsLayer { alpha = collapse; scaleX = 0.6f + 0.4f * collapse; scaleY = scaleX }
                    .clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Saturn menu") { sheet = "saturn" }, contentAlignment = Alignment.Center) {
                    SaturnMark(Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f).clickable { sheet = "model" }) {
                    // The wordmark in the app's own mono face, tracked out, with a faint phosphor glow (none on paper).
                    Text("SATURN", style = Ph.mono.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text,
                        letterSpacing = 0.32.em, shadow = if (Ph.paper) null else androidx.compose.ui.graphics.Shadow(Ph.Amber.copy(alpha = 0.55f), blurRadius = 14f)))
                    val label = io.github.pimobile.ui.state.modelLabel(UiStore.model)
                    if (label != null) Text(androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Ph.TextSoft)) { append(label.first) }
                        append(" · ${label.second}")
                    }, style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) else Text(if (UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty()) "auto model" else "not signed in · /login",
                        style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBox(PhIcons.Chart, "Usage") { onUsage() }
                IconBox(PhIcons.Cube, "Extension store") { onStore() }
                IconBox(PhIcons.Settings, "Settings") { sheet = "settings" }
            }


            io.github.pimobile.ui.components.BrokenPackageBanner(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            // ---- project switcher: the list below is this project's tasks
            @Composable fun ProjectRow(modifier: Modifier) = Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
                    .clickable(onClickLabel = "Switch project") { sheet = "project" }.padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(PhIcons.Folder, Ph.Amber, 16.dp)
                    Text(projectLabel, style = Ph.mono.copy(color = Ph.Text, fontWeight = FontWeight.SemiBold), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    PhIcon(PhIcons.Chevron, Ph.Muted, 14.dp)
                }
                Spacer(Modifier.weight(1f))
                if (elsewhere > 0) Text("$elsewhere running elsewhere", style = Ph.monoSmall.copy(color = Ph.Amber),
                    modifier = Modifier.clickable { sheet = "project" })
                else if (spent >= 0.005) Text(String.format(Locale.US, "$%.2f spent", spent), style = Ph.monoSmall)
            }
            // ---- tasks
            if (!showList) ProjectRow(Modifier.padding(start = 16.dp, end = 20.dp, bottom = 12.dp))
            if (!showList) EmptyState(Modifier.weight(1f).then(pageSwipe).graphicsLayer { translationX = heroDrag * 0.5f }, UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty(),
                project = projectLabel.takeIf { UiStore.tasks.isNotEmpty() }) { sheet = "login" }
            else Box(Modifier.weight(1f).then(pageSwipe)) {
            PullToSearchHint(pull / pullAt, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(pullConn).graphicsLayer { translationY = pull }, state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val visible = projectTasks.filterNot { it.hidden }
                val active = visible.filter { it.status in ACTIVE }
                // Saturn and its moons: shrinks, rises and fades as the list scrolls up.
                item(key = "hero") {
                    OrbitHero(UiStore.tasks.filter { it.status in ACTIVE }, visible.size, onOpen = { if (!selecting) onOpen(it) }, onPlanet = { sheet = "saturn" },
                        page = page, pageStatus = if (page == 1) routinesStatus() else null, onPage = { page = it; hostView.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK) },
                        modifier = Modifier.onGloballyPositioned { heroCenter = it.boundsInRoot().center }
                            // Swipe Saturn sideways: it follows your finger (with weight) and flips the page past the threshold.
                            .pointerInput(page) {
                                val threshold = 72.dp.toPx()
                                var crossed = false
                                detectHorizontalDragGestures(
                                    onDragStart = { swipeJob?.cancel(); crossed = false },
                                    onDragEnd = {
                                        val go = (page == 0 && heroDrag < -threshold) || (page == 1 && heroDrag > threshold)
                                        if (go) { page = 1 - page; hostView.buzz(android.view.HapticFeedbackConstants.CONFIRM) }
                                        swipeJob = scope.launch { androidx.compose.animation.core.animate(heroDrag, 0f,
                                            animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f)) { v, _ -> heroDrag = v } }
                                    },
                                    onDragCancel = { swipeJob = scope.launch { androidx.compose.animation.core.animate(heroDrag, 0f) { v, _ -> heroDrag = v } } },
                                ) { change, dx ->
                                    change.consume()
                                    // The way that leads somewhere moves freely; the other way barely gives.
                                    val toward = (page == 0 && dx < 0) || (page == 1 && dx > 0) || (page == 0 && heroDrag < 0) || (page == 1 && heroDrag > 0)
                                    heroDrag = (heroDrag + dx * if (toward) 0.6f else 0.15f).coerceIn(-size.width * 0.45f, size.width * 0.45f)
                                    val now = kotlin.math.abs(heroDrag) > threshold && ((page == 0 && heroDrag < 0) || (page == 1 && heroDrag > 0))
                                    if (now != crossed) { crossed = now; hostView.buzz(
                                        if (now) android.view.HapticFeedbackConstants.CLOCK_TICK else android.view.HapticFeedbackConstants.CLOCK_TICK) }
                                }
                            }.graphicsLayer {
                            val p = collapse
                            translationX = heroDrag
                            rotationZ = heroDrag / size.width * 8f
                            translationY = if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset * 0.45f else 0f
                            scaleX = 1f - 0.45f * p; scaleY = scaleX; alpha = 1f - p
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.3f)
                        })
                }
                if (page == 1) {
                    routinesPage(onNew = { j -> routineEdit = j; sheet = "routine" }, onOpenTask = { id -> UiStore.find(id)?.let(onOpen) })
                    item(key = "filler") { Spacer(Modifier.height(with(density) { fillerPx.toDp() })) }
                    return@LazyColumn
                }
                item(key = "project") { ProjectRow(Modifier.padding(start = 0.dp, end = 4.dp, bottom = 2.dp)) }
                val earlier = visible.filter { it.status !in ACTIVE && !it.archived }
                val archived = visible.filter { it.status !in ACTIVE && it.archived }
                val spentLabel: String? = null // shown next to the project switcher
                fun section(list: List<TaskUi>, key: String, title: String, trailing: String?, first: Boolean,
                            onHeader: (() -> Unit)? = null, collapsed: Boolean = false) {
                    if (list.isEmpty()) return
                    item(key = "h-$key") {
                        Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .then(if (onHeader != null) Modifier.clickable(onClickLabel = if (collapsed) "Show $key" else "Hide $key") { onHeader() } else Modifier)
                            .padding(start = 4.dp, end = 4.dp, top = if (first) 0.dp else 14.dp, bottom = if (onHeader != null) 4.dp else 0.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = Ph.label)
                            trailing?.let { Text(it, style = Ph.monoSmall) }
                        }
                    }
                    if (!collapsed) items(list, key = { it.task.id }) { t ->
                        with(shared) {
                            Box(Modifier.animateItem().sharedBounds(rememberSharedContentState("task-${t.task.id}"), visibility,
                                boundsTransform = CardMorph, clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(18.dp)))) {
                                SwipeCard(t, enabled = !selecting && !UiStore.swipePages) {
                                    TaskCard(t, selected = if (selecting) t.task.id in selected else null,
                                        onOpen = { if (selecting) toggle(t) else onOpen(t) }, onSelect = { toggle(t) })
                                }
                            }
                        }
                    }
                }
                section(active, "active", "NOW · ${active.size}", spentLabel, first = true)
                section(earlier, "earlier", if (active.isEmpty()) "TASKS" else "EARLIER · ${earlier.size}",
                    spentLabel.takeIf { active.isEmpty() }, first = active.isEmpty())
                section(archived, "archived", "ARCHIVED · ${archived.size}", if (showArchived) "hide" else "show",
                    first = active.isEmpty() && earlier.isEmpty(), onHeader = { showArchived = !showArchived }, collapsed = !showArchived)
                item(key = "filler") { Spacer(Modifier.height(with(density) { fillerPx.toDp() })) }
            }
            }
        }

        // ---- composer
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().blur(blurR)
                .background(Brush.verticalGradient(0f to Ph.Ground.copy(alpha = 0f), 0.25f to Ph.Ground))
                // Taps that miss the composer's controls must not reach the task card underneath.
                .pointerInput(Unit) { detectTapGestures { } }
                .imePadding().navigationBarsPadding().padding(top = 24.dp, bottom = 12.dp)
        ) {
            UiStore.undo?.let { u -> UndoBar(u) }
            Column {
            val pick = rememberAttachPicker { attachments.addAll(it) }
            // Typing only recomposes the message box, not all of home: re-laying out the task list on the
            // first keystroke made its cards jump down and spring back (right after the list had tucked up).
            Isolated {
            Composer(
                value = input, onValue = { input = it },
                attachments = attachments.toList(), attach = pick, onRemoveAttachment = { attachments.remove(it) },
                focus = homeFocus, acceptVoiceRequest = true,
                thinking = ThinkingControl(UiStore.thinkingDefault, THINKING_LEVELS) { UiStore.setThinking(null, it) },
                placeholder = "Ask Saturn to do something…",
                commands = commands,
                onSend = { text ->
                    input = TextFieldValue("")
                    val name = text.removePrefix("/").substringBefore(' ')
                    when {
                        dispatchSlash(text, builtins) -> {}
                        // pi built-ins that act on one task: don't start a task (and an LLM call) by accident.
                        text.startsWith("/") && name in TASK_ONLY -> android.widget.Toast.makeText(context,
                            "Open a task to use /$name", android.widget.Toast.LENGTH_SHORT).show()
                        else -> {
                            val t = UiStore.newTask(text, File(project), attachments.toList()); attachments.clear()
                            if (UiStore.openNewTasks) { UiStore.slipInto = t.task.id; onOpen(t) }
                        }
                    }
                },
            )
            }
            // The gesture hint is for newcomers (and while selecting); it goes once there are a few tasks.
            if (selecting || projectTasks.count { !it.hidden } in 1..3)
                Text(if (selecting) "tap tasks to select · back to cancel" else if (UiStore.swipePages) "swipe sideways for routines  ·  hold to select" else "swipe ← delete · archive →  ·  hold to select", style = Ph.monoSmall.copy(color = Ph.Muted.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(searchOpen, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(90)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) {
            SearchOverlay(onOpen = { searchOpen = false; onOpen(it) }, onClose = { searchOpen = false })
        }
    }

    when (sheet) {
        "settings" -> SettingsSheet { sheet = null }
        "model" -> ModelSheet(null) { sheet = null }
        "project" -> ProjectSheet(projectDir, { selected.clear(); UiStore.selectProject(it) }) { sheet = null }
        "login" -> LoginSheet(initialQuery = loginArg) { sheet = null }
        "logout" -> LoginSheet(logoutMode = true) { sheet = null }
        "resume" -> ResumeSheet(onOpen = { onOpen(it) }) { sheet = null }
        "changelog" -> ChangelogSheet { sheet = null }
        "saturn" -> SaturnSheet(
            onTerminal = onTerminal, onArtifacts = onArtifacts, onStore = onStore,
            onImport = { importFrom.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "*/*")) },
            onSelect = { projectTasks.firstOrNull { !it.hidden }?.let { selected.add(it.task.id) } },
            onScheduled = { page = 1; sheet = null },
        ) { if (sheet == "saturn") sheet = null }
        "scheduled" -> ScheduleSheet { sheet = null }
        "routine" -> RoutineSheet(routineEdit) { sheet = null; routineEdit = null }
    }
}

/** Tapping the Saturn mark: the app's own places, away from the task list. */
@Composable
private fun SaturnSheet(onTerminal: () -> Unit, onArtifacts: () -> Unit, onStore: () -> Unit,
                        onImport: () -> Unit, onSelect: () -> Unit, onScheduled: () -> Unit, onDismiss: () -> Unit) {
    val artifacts = remember { UiStore.runtime.artifacts.walkTopDown().maxDepth(2).count { it.isFile && !it.name.startsWith(".") } }
    PhSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Amber)) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_foreground), null, Modifier.fillMaxSize())
            }
            Column {
                Text("Saturn", style = Ph.heading)
                Text("${UiStore.tasks.size} tasks · $artifacts artifacts", style = Ph.monoSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        listOf(
            Triple(PhIcons.Sparkle, "Artifacts", "Files Saturn made for you") to onArtifacts,
            Triple(PhIcons.Terminal, "Terminal", "bash · npm · git · node") to onTerminal,
            Triple(PhIcons.Clock, "Routines", io.github.pimobile.runtime.Scheduler.jobs.size.let { if (it == 0) "Saturn on a schedule" else "$it ${if (it == 1) "routine" else "routines"}" }) to onScheduled,
            Triple(PhIcons.Cube, "Extension store", "Packages from pi.dev") to onStore,
            Triple(PhIcons.Check, "Select tasks", "Export or delete several") to onSelect,
            Triple(PhIcons.Import, "Import tasks", "From a Saturn export or pi session") to onImport,
        ).forEach { (row, action) ->
            val (icon, title, sub) = row
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClickLabel = title) { onDismiss(); action() }
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center) { PhIcon(icon, if (title == "Artifacts") Ph.Amber else Ph.TextSoft, 19.dp) }
                Column(Modifier.weight(1f)) {
                    Text(title, style = Ph.body.copy(color = Ph.Text, fontSize = 15.sp))
                    Text(sub, style = Ph.monoSmall)
                }
            }
        }
    }
}

@Composable
fun IconBox(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description) { onClick() },
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, Ph.TextSoft, 20.dp, description) }
}

@Composable
private fun EmptyState(modifier: Modifier, hasKey: Boolean, project: String? = null, onSettings: () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_glyph), contentDescription = null, tint = Ph.LineStrong, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (!hasKey) "Connect a model to begin" else if (project != null) "No tasks in $project yet" else "What should Saturn work on?",
            style = Ph.title.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasKey) "Every instruction becomes its own task. Run several at once — tap one to continue it."
            else "Sign in with a subscription (Claude, ChatGPT, Copilot…) or add an API key.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center,
        )
        if (!hasKey) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Amber).clickable { onSettings() }
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text("Sign in", style = Ph.title.copy(fontSize = 14.sp, color = Ph.AmberInk))
            }
        }
        Spacer(Modifier.height(28.dp))
        TrustLine()
        if (hasKey) {
            Spacer(Modifier.height(14.dp))
            Text("‹  swipe for routines", style = Ph.monoSmall.copy(color = Ph.Muted.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.height(140.dp))
    }
}

/** The one-line trust note: quiet, never louder than the content around it. */
@Composable
fun TrustLine(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PhIcon(PhIcons.Lock, Ph.Muted, 12.dp)
        Text("Open source · runs on your phone", style = Ph.monoSmall.copy(color = Ph.Muted, fontSize = 11.sp))
    }
}

/** Ticks once a second while [since] is set; returns "1m 12s". */
@Composable
fun elapsed(since: Long?): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (since != null) { now = System.currentTimeMillis(); delay(1000) } }
    since ?: return ""
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCard(t: TaskUi, selected: Boolean?, onOpen: () -> Unit, onSelect: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val needs = t.status == Status.NEEDS_INPUT
    val working = t.status == Status.RUNNING || t.status == Status.STARTING
    val tap = Modifier.combinedClickable(onClick = onOpen, onClickLabel = if (selected != null) "Select task" else "Open task",
        onLongClick = { haptics.buzz(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onSelect() },
        onLongClickLabel = "Select task")
    // The tick circle slides in when selecting starts (pushing the title over), fills with a little
    // bounce when picked, and slides away again when selecting ends.
    val check: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        androidx.compose.animation.AnimatedVisibility(selected != null,
            enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 520f), expandFrom = Alignment.Start) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 520f), initialScale = 0.3f) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 700f), shrinkTowards = Alignment.Start) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(140), targetScale = 0.3f) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))) {
            val on = selected == true
            val fill by androidx.compose.animation.animateColorAsState(if (on) Ph.Amber else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.animation.core.tween(160), label = "fill")
            val ring by androidx.compose.animation.animateColorAsState(if (on) Ph.Amber else Ph.LineStrong, androidx.compose.animation.core.tween(160), label = "ring")
            val tick by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), label = "tick")
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(fill).border(1.5.dp, ring, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) {
                if (tick > 0.01f) PhIcon(PhIcons.Check, Ph.AmberInk, 14.dp, modifier = Modifier.graphicsLayer { scaleX = tick; scaleY = tick; alpha = tick.coerceIn(0f, 1f) })
            }
        }
    }
    // A picked card sinks in a touch, on a spring.
    val sink by androidx.compose.animation.core.animateFloatAsState(if (selected == true) 0.97f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f), label = "sink")
    Box(Modifier.graphicsLayer { scaleX = sink; scaleY = sink }) {
    when {
        working -> LiquidCard(t, check, Modifier.clip(RoundedCornerShape(22.dp)).then(tap))
        needs -> {
            val shape = RoundedCornerShape(22.dp)
            val needBg by androidx.compose.animation.animateColorAsState(if (selected == true) Ph.AmberWash else if (Ph.amoled) androidx.compose.ui.graphics.Color(0xFF0F0D09) else androidx.compose.ui.graphics.Color(0xFF15130F), androidx.compose.animation.core.tween(200), label = "bg")
            Column(Modifier.fillMaxWidth().clip(shape).background(needBg)
                .border(1.dp, Ph.AmberLine, shape).then(tap).animateContentSize()) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    check()
                    io.github.pimobile.ui.components.PulseDot(Ph.Text, 7)
                    Text(t.title, style = Ph.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("needs you", style = Ph.monoSmall.copy(color = Ph.Amber))
                }
                NeedsYou(t)
            }
        }
        else -> {
            val last = t.lastError ?: (t.items.lastOrNull { it is Item.Assistant && it.text.isNotBlank() } as? Item.Assistant)?.text ?: t.savedReply
            // Don't repeat the title (now taken from Saturn's first reply) as the preview.
            val head = t.title.removeSuffix("…").take(40)
            QuietRow(t, check, last?.takeIf { it.isNotBlank() }?.let { plainPreview(it) }?.takeIf { it.isNotBlank() && !(head.length >= 12 && it.startsWith(head)) }, selected == true, tap)
        }
    }
    }
}

private val Color3A3222 = androidx.compose.ui.graphics.Color(0xFF3A3222)

/** Answer a pi dialog straight from the home screen. */
@Composable
private fun NeedsYou(t: TaskUi) {
    val d = t.pendingDialog ?: return
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(d.title.ifBlank { d.message }, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp))
        if (d.options.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.options.take(3).forEach { o ->
                Box(Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
                    .clickable { UiStore.answer(t, d, o) }.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(o, style = Ph.mono.copy(color = Ph.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else Text("Tap to answer", style = Ph.monoSmall.copy(color = Ph.Amber))
    }
}

/**
 * Swipe left to delete (with undo), right to archive (or restore, in Archived).
 * - It only counts as a swipe when the drag is clearly sideways; otherwise the list scrolls.
 * - Nothing happens until you lift your finger: drag back before letting go and it's cancelled.
 * - Crossing the threshold ticks (and back again ticks lighter); holding there buzzes firmly.
 */
@Composable
private fun SwipeCard(t: TaskUi, enabled: Boolean, content: @Composable () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val canArchive = t.status !in ACTIVE
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    var width by remember { mutableStateOf(1f) }
    var armed by remember { mutableStateOf(false) }
    val threshold = width * 0.38f
    fun tick(c: Int) { view.buzz(c) }
    val armOn = if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.CONTEXT_CLICK
    val armOff = if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else android.view.HapticFeedbackConstants.CLOCK_TICK

    Box(Modifier.onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }.pointerInput(enabled, canArchive) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var dx = 0f; var dy = 0f
            // 1. Decide the direction: clearly sideways, or leave it to the list.
            var horizontal = false
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!c.pressed) return@awaitEachGesture
                val d = c.positionChange()
                dx += d.x; dy += d.y
                if (kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx) * 0.8f) return@awaitEachGesture
                if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f) { horizontal = true; c.consume(); break }
            }
            if (!horizontal) return@awaitEachGesture
            // 2. Follow the finger; tick on crossing the threshold, buzz if held there.
            val velocity = androidx.compose.ui.input.pointer.util.VelocityTracker()
            var heldSince = 0L; var buzzed = false
            var pos = 0f
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                velocity.addPosition(c.uptimeMillis, c.position)
                if (!c.pressed) break
                val d = c.positionChange().x
                c.consume()
                pos += d
                // Right is archive: only for finished tasks; otherwise it barely moves.
                val shown = when {
                    pos > 0 && !canArchive -> kotlin.math.min(pos * 0.15f, 24.dp.toPx())
                    kotlin.math.abs(pos) > threshold -> kotlin.math.sign(pos) * (threshold + (kotlin.math.abs(pos) - threshold) * 0.45f)
                    else -> pos
                }
                scope.launch { offset.snapTo(shown) }
                val nowArmed = kotlin.math.abs(shown) >= threshold && !(pos > 0 && !canArchive)
                if (nowArmed != armed) { armed = nowArmed; tick(if (nowArmed) armOn else armOff); heldSince = c.uptimeMillis; buzzed = false }
                if (armed && kotlin.math.abs(d) > 2f) heldSince = c.uptimeMillis
                if (armed && !buzzed && c.uptimeMillis - heldSince > 550) { buzzed = true; tick(android.view.HapticFeedbackConstants.LONG_PRESS) }
            }
            // 3. Let go: do it only if still past the threshold (or flung hard that way).
            val vx = velocity.calculateVelocity().x
            val toLeft = offset.value < 0
            val fling = kotlin.math.abs(vx) > 2200f && kotlin.math.sign(vx) == kotlin.math.sign(offset.value) && kotlin.math.abs(offset.value) > threshold * 0.4f
            val go = (armed || fling) && !(!toLeft && !canArchive)
            armed = false
            scope.launch {
                if (go) {
                    if (fling) tick(android.view.HapticFeedbackConstants.CONFIRM)
                    offset.animateTo(if (toLeft) -width * 1.1f else width * 1.1f, androidx.compose.animation.core.tween(180))
                    if (toLeft) UiStore.deleteWithUndo(t) else UiStore.setArchived(t, !t.archived)
                    offset.snapTo(0f)
                } else offset.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow))
            }
        }
    }) {
        // What's underneath: delete (left) or archive/restore (right); the icon grows once armed.
        val o = offset.value
        if (o != 0f) {
            val delete = o < 0
            val ready = kotlin.math.abs(o) >= threshold
            Row(Modifier.matchParentSize().clip(RoundedCornerShape(18.dp))
                .background(if (delete) Ph.RedWash else Ph.AmberWash)
                .border(1.dp, if (delete) androidx.compose.ui.graphics.Color(0xFF3A2A28) else Ph.AmberLine, RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (delete) Arrangement.End else Arrangement.Start) {
                val (icon, label, color) = when {
                    delete -> Triple(PhIcons.Trash, if (ready) "Release to delete" else "Delete", Ph.Red)
                    t.archived -> Triple(PhIcons.Archive, if (ready) "Release to restore" else "Restore", Ph.Amber)
                    else -> Triple(PhIcons.Archive, if (ready) "Release to archive" else "Archive", Ph.Amber)
                }
                val scale by androidx.compose.animation.core.animateFloatAsState(if (ready) 1.25f else 1f, androidx.compose.animation.core.spring(0.5f), label = "arm")
                if (delete) Text(label, style = Ph.mono.copy(color = color), modifier = Modifier.padding(end = 10.dp))
                PhIcon(icon, color, 20.dp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                if (!delete) Text(label, style = Ph.mono.copy(color = color), modifier = Modifier.padding(start = 10.dp))
            }
        }
        Box(Modifier.graphicsLayer { translationX = offset.value }) { content() }
    }
}

@Composable
private fun UndoBar(u: UiStore.Undo) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Ph.Raised).border(1.dp, Ph.LineStrong, RoundedCornerShape(14.dp))
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(u.message, style = Ph.body.copy(color = Ph.Text, fontSize = 14.sp), modifier = Modifier.weight(1f))
        Text("Undo", style = Ph.mono.copy(color = Ph.Amber, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Undo") { UiStore.undoLast() }
                .padding(horizontal = 14.dp, vertical = 12.dp))
    }
}


/** First line of a reply without markdown marks, for the card preview. */
private fun plainPreview(md: String): String =
    md.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("```") && !it.matches(Regex("[-*_|: ]+")) }.orEmpty()
        .replace(Regex("^(#{1,6}\\s+|[-*+]\\s+|>\\s*|\\d+\\.\\s+)"), "")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_`~]{1,3}"), "")

/** Runs [content] in its own recomposition scope, so state it reads doesn't recompose the caller. */
@Composable
private fun Isolated(content: @Composable () -> Unit) = content()
