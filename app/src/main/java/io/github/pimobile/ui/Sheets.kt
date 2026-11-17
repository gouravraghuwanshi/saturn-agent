package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PROVIDERS = listOf(
    "OpenRouter" to "OPENROUTER_API_KEY",
    "Anthropic" to "ANTHROPIC_API_KEY",
    "OpenAI" to "OPENAI_API_KEY",
    "Google Gemini" to "GEMINI_API_KEY",
    "DeepSeek" to "DEEPSEEK_API_KEY",
    "Groq" to "GROQ_API_KEY",
    "xAI" to "XAI_API_KEY",
    "Mistral" to "MISTRAL_API_KEY",
)

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
private fun SheetTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, style = Ph.heading)
        sub?.let { Text(it, style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp)) }
    }
}

@Composable
private fun Field(value: String, onValue: (String) -> Unit, placeholder: String, secret: Boolean = false, mono: Boolean = true) {
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
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) Ph.Amber else Ph.Chip)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Ph.title.copy(fontSize = 14.sp, color = if (enabled) Ph.AmberInk else Ph.Muted)) }
}

// ----------------------------------------------------------------- settings

@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    var secrets by remember { mutableStateOf(UiStore.secrets.all()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    val diag = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle("Settings", "Keys are encrypted with the Android Keystore and only passed to Phi's engine. For subscriptions use /login.")
            Text("API KEYS", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            PROVIDERS.forEach { (name, env) ->
                val set = secrets[env]
                Column(Modifier.fillMaxWidth().clickable { editing = if (editing == env) null else env; draft = "" }
                    .padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhIcon(PhIcons.Key, if (set != null) Ph.Green else Ph.Muted, 18.dp)
                        Text(name, style = Ph.title.copy(fontSize = 14.sp), modifier = Modifier.padding(start = 12.dp).weight(1f))
                        Text(if (set != null) "••••${set.takeLast(4)}" else "add key", style = Ph.monoSmall.copy(color = if (set != null) Ph.Green else Ph.Muted))
                    }
                    if (editing == env) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(draft, { draft = it }, env, secret = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryButton("Save", enabled = draft.isNotBlank()) {
                                    UiStore.secrets.put(env, draft); UiStore.applySecrets()
                                    secrets = UiStore.secrets.all(); editing = null
                                }
                                if (set != null) Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp))
                                    .clickable { UiStore.secrets.put(env, null); UiStore.applySecrets(); secrets = UiStore.secrets.all(); editing = null }
                                    .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                                    Text("Remove", style = Ph.title.copy(fontSize = 14.sp, color = Ph.Red))
                                }
                            }
                            Text("Applies to tasks started from now on.", style = Ph.monoSmall)
                        }
                    }
                }
            }

            Text("RUNTIME", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))
            var runScripts by remember { mutableStateOf(UiStore.runtime.runScripts) }
            Row(Modifier.fillMaxWidth().clickable {
                runScripts = !runScripts
                UiStore.runtime.runScripts = runScripts
                io.github.pimobile.runtime.TaskManager.restartIdle()
            }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Run scripts directly", style = Ph.title.copy(fontSize = 14.sp))
                    Text("Lets ./scripts and tools started via npx (e.g. MCP servers) run. Android blocks running files from app storage; this starts them through node or bash instead.",
                        style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
                }
                androidx.compose.material3.Switch(checked = runScripts, onCheckedChange = null,
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = Ph.Amber, checkedThumbColor = Ph.AmberInk,
                        uncheckedTrackColor = Ph.Chip, uncheckedThumbColor = Ph.Muted, uncheckedBorderColor = Ph.LineStrong))
            }

            Text("DIAGNOSTICS", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (running) "Running…" else "Run toolchain check", enabled = !running) {
                    diag.clear(); running = true
                    scope.launch {
                        val code = withContext(Dispatchers.IO) {
                            runCatching { Diagnostics.run(UiStore.runtime) { l -> scope.launch { diag.add(l) } } }.getOrElse { diag.add("error: $it"); 1 }
                        }
                        diag.add(if (code == 0) "✓ all checks passed" else "✗ exit $code"); running = false
                    }
                }
                if (diag.isNotEmpty()) Text(diag.joinToString("\n"), style = Ph.mono.copy(color = Ph.TextSoft, fontSize = 11.5.sp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Deep).padding(12.dp))
            }

            About()
        }
    }
}

// -------------------------------------------------------------------- model

@Composable
fun ModelSheet(task: TaskUi?, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(task) {
        val t = task ?: UiStore.tasks.firstOrNull()
        if (t != null && UiStore.models.isEmpty()) UiStore.refreshModels(t)
    }
    val models = UiStore.models.filter { (p, id) -> "$p/$id".contains(query, ignoreCase = true) }.take(200)
    PhSheet(onDismiss) {
        SheetTitle("Model", UiStore.model?.let { "Current: $it" } ?: "pi chooses a default from your keys")
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Field(query, { query = it }, "search or provider/model-id") }
        if (UiStore.models.isEmpty()) Text(
            if (UiStore.tasks.isEmpty()) "Start a task once to load the model list — or type provider/model-id and tap Use."
            else "Loading models…",
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
    var dirs by remember { mutableStateOf(root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()) }
    var name by remember { mutableStateOf("") }
    PhSheet(onDismiss) {
        SheetTitle("Project", "Each task runs in a project folder. Tasks can clone repos into it.")
        (listOf(root) + dirs).forEach { d ->
            val label = if (d == root) "~/projects" else d.name
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(d); onDismiss() }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PhIcon(PhIcons.Folder, if (d == current) Ph.Amber else Ph.Muted, 18.dp)
                Text(label, style = Ph.mono.copy(fontSize = 14.sp, color = if (d == current) Ph.Amber else Ph.Text),
                    modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { Field(name, { name = it.filter { c -> c.isLetterOrDigit() || c in "-_." } }, "new-project") }
            PrimaryButton("Create", enabled = name.isNotBlank()) {
                val d = File(root, name).apply { mkdirs() }
                dirs = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
                onPick(d); onDismiss()
            }
        }
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
private fun About() {
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
    Text("ABOUT", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Ph.Amber), contentAlignment = Alignment.Center) {
                Text("φ", style = Ph.mono.copy(fontSize = 20.sp, color = Ph.AmberInk))
            }
            Column {
                Text("Phi $app", style = Ph.title)
                Text("powered by the Pi coding agent $pi", style = Ph.monoSmall)
            }
        }
        Text("Phi runs the real, unmodified Pi on your phone, with its own Linux userland.",
            style = Ph.body.copy(fontSize = 13.sp))
        Text(listOfNotNull("pi $pi", "node ${rt?.optString("node")}", "npm $npm", "bash ${rt?.optString("bash")}",
            "git ${rt?.optString("git")}", "ripgrep ${rt?.optString("ripgrep")}", "curl ${rt?.optString("curl")}").joinToString(" · "),
            style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.TextSoft))
        Text("Open source", style = Ph.title.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp))
        listOf(
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
