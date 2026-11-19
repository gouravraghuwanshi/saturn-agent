package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.Diagnostics
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.state.modelLabel
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ph.Surface,
        scrimColor = Ph.Deep.copy(alpha = 0.7f),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).clip(RoundedCornerShape(2.dp)).background(Ph.LineStrong).padding(horizontal = 18.dp, vertical = 2.dp)) },
    ) { Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) { content() } }
}

@Composable
internal fun SheetTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = Ph.heading)
        sub?.let { Text(it, style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp)) }
    }
}

@Composable
internal fun Field(value: String, onValue: (String) -> Unit, placeholder: String, secret: Boolean = false, mono: Boolean = true) {
    BasicTextField(
        value, onValue, singleLine = true,
        textStyle = (if (mono) Ph.mono.copy(fontSize = 14.sp) else Ph.body).copy(color = Ph.Text),
        cursorBrush = SolidColor(Ph.Amber),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
            .border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp)).padding(14.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } },
    )
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) Ph.Amber else Ph.Chip)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ph.title.copy(fontSize = 14.sp, color = if (enabled) Ph.AmberInk else Ph.Muted)) }
}

// ----------------------------------------------------------------- settings

// -------------------------------------------------------------------- model

@Composable
fun ModelSheet(task: TaskUi?, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    // Always ask fresh: a sign-in since the last look adds models.
    LaunchedEffect(Unit) { io.github.pimobile.ui.state.Control.loadModels() }
    // Newest first: compare ids by their numbers (gpt-5.5 > gpt-4o > gpt-4), the default's provider on top.
    val models = UiStore.models.filter { (p, id) -> "$p/$id".contains(query, ignoreCase = true) }
        .sortedWith(compareBy<Pair<String, String>> { if (it.first == UiStore.model?.substringBefore('/')) 0 else 1 }
            .thenBy { it.first }.then { a, b -> versionCompare(b.second, a.second) })
        .take(200)
    PhSheet(onDismiss) {
        SheetTitle("Model", UiStore.model?.let { "Current: $it" } ?: "pi chooses a default from your keys")
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Field(query, { query = it }, "search or provider/model-id") }
        if (UiStore.models.isEmpty()) Text(
            if (UiStore.hasCredentials || io.github.pimobile.ui.state.Control.signedIn.isNotEmpty()) "Loading models…"
            else "Sign in first (/login) — or type provider/model-id and tap Use.",
            style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (query.contains('/') && models.isEmpty()) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            PrimaryButton("Use $query") {
                UiStore.setModel(task, query.substringBefore('/'), query.substringAfter('/')); onDismiss()
            }
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(models, key = { "${it.first}/${it.second}" }) { (p, id) ->
                val current = UiStore.model == "$p/$id"
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { UiStore.setModel(task, p, id); onDismiss() }
                    .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(id, style = Ph.mono.copy(fontSize = 13.sp, color = if (current) Ph.Amber else Ph.Text),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(p, style = Ph.monoSmall)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ project

@Composable
fun ProjectSheet(current: File, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val root = UiStore.runtime.projects
    fun scan() = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()
    var dirs by remember { mutableStateOf(scan()) }
    var name by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf<String?>(null) } // project whose delete was tapped once
    PhSheet(onDismiss) {
        SheetTitle("Projects", "Each project has its own tasks and artifacts.")
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            items(listOf(root) + dirs, key = { it.path }) { d ->
                val isRoot = d == root
                val on = d.canonicalPath == current.canonicalPath
                val tasks = UiStore.tasksIn(d)
                val running = tasks.count { it.status == io.github.pimobile.runtime.TaskManager.Status.RUNNING }
                val isArmed = armed == d.path
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .background(if (isArmed) Ph.RedWash else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { armed = null; onPick(d); onDismiss() }.padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PhIcon(PhIcons.Folder, if (on) Ph.Amber else Ph.Muted, 18.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (isRoot) "General" else d.name, style = Ph.mono.copy(fontSize = 14.sp, color = if (on) Ph.Amber else Ph.Text),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (isArmed) "Deletes its files" + (if (tasks.isEmpty()) "" else " and ${tasks.size} task${if (tasks.size == 1) "" else "s"}") + " · tap again"
                            else buildString {
                                append(if (tasks.size == 1) "1 task" else "${tasks.size} tasks")
                                if (running > 0) append(" · $running running")
                                if (isRoot) append(" · ~/projects")
                            }, style = Ph.monoSmall.copy(color = if (isArmed) Ph.Red else Ph.Muted))
                    }
                    if (!isRoot) Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Delete project ${d.name}") {
                        if (isArmed) { UiStore.deleteProject(d); dirs = dirs - d; armed = null } else armed = d.path
                    }, contentAlignment = Alignment.Center) { PhIcon(PhIcons.Trash, if (isArmed) Ph.Red else Ph.Muted, 18.dp, "Delete project") }
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(name, { name = it.filter { c -> c.isLetterOrDigit() || c in "-_." } }, "new-project") }
            PrimaryButton("Create", enabled = name.isNotBlank() && !name.equals(io.github.pimobile.runtime.PiRuntime.GENERAL, true)) {
                val d = File(root, name).apply { mkdirs() }
                dirs = scan()
                onPick(d); onDismiss()
            }
        }
        // Clone a GitHub repo as a new project (public repos work signed out; private ones need Settings → GitHub).
        var repo by remember { mutableStateOf("") }
        var cloning by remember { mutableStateOf(false) }
        var cloneError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(repo, { repo = it.trim(); cloneError = null }, "owner/repo or GitHub link") }
            PrimaryButton(if (cloning) "Cloning…" else "Clone", enabled = repo.isNotBlank() && !cloning) {
                cloning = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { io.github.pimobile.runtime.GitHub.clone(repo, root) } }
                    cloning = false
                    r.onSuccess { d -> dirs = scan(); onPick(d); onDismiss() }.onFailure { cloneError = it.message }
                }
            }
        }
        cloneError?.let { Text(it, style = Ph.monoSmall.copy(color = Ph.Red), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    }
}

// ------------------------------------------------------------------- rename

@Composable
fun RenameSheet(task: TaskUi, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(task.title) }
    PhSheet(onDismiss) {
        SheetTitle("Rename task #${task.task.number}")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(title, { title = it }, "Task title", mono = false)
            PrimaryButton("Save", enabled = title.isNotBlank()) { UiStore.rename(task, title.trim()); onDismiss() }
        }
    }
}

// -------------------------------------------------------------------- about

@Composable
internal fun Privacy() {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            Triple(PhIcons.Lock, "Runs on this phone", "Pi, your projects, files, artifacts and terminal live in Saturn's private storage. Nothing is backed up to the cloud."),
            Triple(PhIcons.Key, "Your keys stay here", "API keys and account sign-ins are kept in Saturn's private storage, which other apps can't read. Nothing is backed up to the cloud."),
            Triple(PhIcons.External, "What leaves your phone", "Your messages, and the files Saturn reads for them, go to the model provider you choose. Web pages and packages are downloaded when you or Saturn ask for them."),
            Triple(PhIcons.Check, "No tracking", "No accounts, analytics, ads or telemetry. Saturn has no server of its own."),
        ).forEach { (icon, title, body) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhIcon(icon, Ph.TextSoft, 18.dp, modifier = Modifier.padding(top = 1.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = Ph.title.copy(fontSize = 14.sp))
                    Text(body, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.Muted))
                }
            }
        }
    }
}

@Composable
internal fun About() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val info = remember {
        val app = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        val bundle = runCatching { ctx.assets.open("pi-bundle.version").bufferedReader().readText().trim() }.getOrDefault("?")
        val rt = runCatching { org.json.JSONObject(ctx.assets.open("runtime-info.json").bufferedReader().readText()) }.getOrNull()
        Triple(app, bundle, rt)
    }
    val (app, bundle, rt) = info
    val pi = bundle.substringBefore("+")
    val npm = bundle.substringAfter("+npm", "?")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Ph.Amber), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.pimobile.R.drawable.ic_saturn_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Column {
                Text("Saturn Agent $app", style = Ph.title)
                Text("powered by the Pi coding agent $pi", style = Ph.monoSmall)
            }
        }
        Text("Saturn is open source. It runs the real, unmodified Pi on your phone, with its own Linux userland.",
            style = Ph.body.copy(fontSize = 13.sp))
        Text(listOfNotNull("pi $pi", "node ${rt?.optString("node")}", "npm $npm", "bash ${rt?.optString("bash")}",
            "git ${rt?.optString("git")}", "ripgrep ${rt?.optString("ripgrep")}", "curl ${rt?.optString("curl")}").joinToString(" · "),
            style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.TextSoft))
        Text("Open source", style = Ph.title.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
        listOf(
            "Saturn source code — Apache-2.0" to "https://github.com/gouravraghuwanshi/saturn-agent",
            "Privacy" to "https://github.com/gouravraghuwanshi/saturn-agent/blob/main/PRIVACY.md",
            "Pi coding agent — MIT" to "https://pi.dev",
            "Node.js — MIT" to "https://nodejs.org",
            "npm — Artistic-2.0" to "https://github.com/npm/cli",
            "Termux packages (bash, git, curl, ripgrep, OpenSSL…) — their own licenses" to "https://github.com/termux/termux-packages",
            "Terminal emulator & view (from Termux) — Apache-2.0" to "https://github.com/termux/termux-app",
            "Inter, JetBrains Mono — SIL Open Font License 1.1" to "https://openfontlicense.org",
        ).forEach { (label, link) ->
            Text(label, style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Cyan),
                modifier = Modifier.clickable { uri.openUri(link) }.padding(vertical = 2.dp))
        }
        Box(Modifier.size(1.dp))
    }
}

// --------------------------------------------------------------- task menu

/** One row of a task's action sheet. [confirm] asks for a second tap first. */
class MenuAction(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val hint: String? = null,
                 val danger: Boolean = false, val confirm: String? = null, val run: () -> Unit)

@Composable
fun TaskMenuSheet(task: TaskUi, actions: List<MenuAction>, onDismiss: () -> Unit) {
    var armed by remember { mutableStateOf<String?>(null) }
    PhSheet(onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${task.task.number} ", style = Ph.heading.copy(fontFamily = Ph.Mono, color = Ph.Muted))
            Text(task.title, style = Ph.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(task.task.cwd.name,
            style = Ph.monoSmall, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp))
        actions.forEachIndexed { i, a ->
            if (a.danger && i > 0) Box(Modifier.padding(vertical = 6.dp, horizontal = 20.dp).fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(Ph.Line))
            val isArmed = armed == a.label
            val color = if (a.danger) Ph.Red else Ph.Text
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .background(if (isArmed) Ph.RedWash else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(onClickLabel = a.label) {
                        if (a.confirm != null && !isArmed) armed = a.label else { onDismiss(); a.run() }
                    }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PhIcon(a.icon, if (a.danger) Ph.Red else Ph.TextSoft, 20.dp)
                Text(if (isArmed) a.confirm!! else a.label, style = Ph.body.copy(color = color, fontSize = 15.sp), modifier = Modifier.weight(1f))
                (if (isArmed) "session kept on disk" else a.hint)?.let { Text(it, style = Ph.monoSmall) }
            }
        }
    }
}


/** Natural order on model ids: digit runs compare as numbers, so "gpt-5.5" > "gpt-5" > "gpt-4o". */
private fun versionCompare(a: String, b: String): Int {
    val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]; val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigIntegerOrNull() ?: 0.toBigInteger()).compareTo(y.toBigIntegerOrNull() ?: 0.toBigInteger())
            else x.compareTo(y)
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}
