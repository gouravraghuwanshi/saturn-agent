package io.github.pimobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.ui.components.Markdown
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.state.Control
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

// ------------------------------------------------------------------ shared

@Composable
private fun Title(title: String, sub: String? = null) = Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
    Text(title, style = Ph.heading)
    sub?.let { Text(it, style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp)) }
}

@Composable
private fun Btn(label: String, primary: Boolean = false, color: Color = Ph.Text, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .background(if (primary && enabled) Ph.Amber else Color.Transparent)
        .border(1.dp, if (primary && enabled) Ph.Amber else Ph.LineStrong, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 14.sp, color = if (!enabled) Ph.Muted else if (primary) Ph.AmberInk else color))
    }

@Composable
private fun Card(content: @Composable () -> Unit) = Column(
    Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(Ph.Raised).border(1.dp, Ph.LineStrong, RoundedCornerShape(14.dp)).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) { content() }

@Composable
private fun copier(): (String) -> Unit {
    val ctx = LocalContext.current
    return { t -> ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Saturn", t)) }
}

// ------------------------------------------------------------------- login

/** /login and /logout: pi's own provider flows (OAuth subscriptions, device codes, API keys). */
@Composable
fun LoginSheet(logoutMode: Boolean = false, initialQuery: String = "", onDismiss: () -> Unit) {
    val login = Control.login
    var query by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(Unit) { if (Control.providers == null) Control.loadProviders(); Control.refreshSignedIn() }
    PhSheet({ if (login.running) Control.cancelLogin(); login.reset(); onDismiss() }) {
        val p = login.provider
        if (p != null && (login.running || login.done || login.error != null)) LoginFlow(p, onBack = { login.reset(); login.provider = null })
        else ProviderList(logoutMode, query, { query = it })
    }
}

/** pi's /login: first choose how to sign in, then the provider. */
@Composable
private fun ProviderList(logoutMode: Boolean, query: String, onQuery: (String) -> Unit) {
    val providers = Control.providers
    var method by remember { mutableStateOf<String?>(if (logoutMode || query.isNotBlank()) "any" else null) }
    val radius = providers?.firstOrNull { it.id == "radius" }

    if (method == null) {
        Title("Log in", "How do you want to sign in?")
        MethodRow("Sign in with an account", "Claude Pro/Max, ChatGPT Plus/Pro, GitHub Copilot and more — uses your subscription") { method = "oauth" }
        MethodRow("Sign in with an API key", "OpenRouter, Anthropic, OpenAI, Gemini, Groq and 40 more") { method = "api_key" }
        if (radius != null) MethodRow("Sign in with ${radius.name}" + if (radius.id in Control.signedIn) "  ✓" else "", "One account for many models") {
            Control.startLogin(radius, "oauth")
        } else if (providers == null) Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) { PulseDot(Ph.Amber); Text("Loading providers from pi…", style = Ph.body.copy(color = Ph.Muted)) }
        Control.providersError?.let { Text("Couldn't load providers: $it", style = Ph.body.copy(color = Ph.Red), modifier = Modifier.padding(20.dp)) }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!logoutMode) Text("‹", style = Ph.heading.copy(color = Ph.TextSoft), modifier = Modifier.clickable { method = null; onQuery("") }.padding(start = 20.dp, end = 4.dp))
        Title(when { logoutMode -> "Log out"; method == "oauth" -> "Sign in with an account"; method == "api_key" -> "Sign in with an API key"; else -> "Log in" },
            if (logoutMode) "Remove a saved credential from pi." else null)
    }
    BasicTextField(query, onQuery, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
        cursorBrush = SolidColor(Ph.Amber),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Ph.Ground).border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp)).padding(14.dp),
        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search providers", style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
    when {
        Control.providersError != null -> Text("Couldn't load providers: ${Control.providersError}", style = Ph.body.copy(color = Ph.Red),
            modifier = Modifier.padding(20.dp))
        providers == null -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulseDot(Ph.Amber); Text("Loading providers from pi…", style = Ph.body.copy(color = Ph.Muted))
        }
        else -> {
            val shown = providers.filter {
                (when (method) { "oauth" -> it.oauth && it.id != "radius"; "api_key" -> it.apiKey; else -> true }) &&
                    (!logoutMode || it.id in Control.signedIn) &&
                    (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true))
            }
            if (shown.isEmpty()) Text(if (logoutMode) "You're not signed in to any provider." else "No provider matches.",
                style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(shown, key = { it.id }) { p -> ProviderRow(p, logoutMode, method ?: "any") }
            }
        }
    }
}

@Composable
private fun MethodRow(title: String, sub: String, onClick: () -> Unit) = Column(
    Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Raised)
        .border(1.dp, Ph.LineStrong, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Text(title, style = Ph.title)
    Text(sub, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.Muted))
}

@Composable
private fun ProviderRow(p: Control.Provider, logoutMode: Boolean, method: String) {
    val signedIn = p.id in Control.signedIn
    Column(Modifier.fillMaxWidth().clickable {
        when {
            logoutMode -> Control.logout(p)
            method == "oauth" -> Control.startLogin(p, "oauth")
            method == "api_key" -> Control.startLogin(p, "api_key")
            else -> Control.startLogin(p, if (p.oauth) "oauth" else "api_key")
        }
    }.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (method == "oauth") p.oauthLabel ?: p.name else p.name, style = Ph.title.copy(fontSize = 14.sp),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.subscription && method == "oauth") Badge("subscription", Ph.Amber)
            if (signedIn) Badge("signed in", Ph.Green)
            if (logoutMode) Text("log out", style = Ph.monoSmall.copy(color = Ph.Red))
        }
        Text(listOfNotNull(p.id, p.source?.let { "via $it" }).joinToString(" · "), style = Ph.monoSmall)
    }
}

@Composable
private fun Badge(text: String, color: Color) = Text(text, style = Ph.monoSmall.copy(color = color, fontSize = 10.5.sp),
    modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))

@Composable
private fun LoginFlow(p: Control.Provider, onBack: () -> Unit) {
    val login = Control.login
    val uri = LocalUriHandler.current
    val copy = copier()
    var openedUrl by remember { mutableStateOf<String?>(null) }
    // Open the sign-in page automatically; pi's local callback server finishes the login.
    LaunchedEffect(login.authUrl) { login.authUrl?.let { if (openedUrl != it) { openedUrl = it; runCatching { uri.openUri(it) } } } }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Title(p.name, if (login.authType == "oauth") "Signing in through ${p.oauthLabel ?: "the provider"}" else "API key")
        login.authUrl?.let { url ->
            Card {
                Text("Finish signing in in your browser", style = Ph.title.copy(fontSize = 14.sp))
                Text(login.authInstructions ?: "When it redirects back, pi completes the login automatically. If it doesn't, paste the final URL or code below.",
                    style = Ph.body.copy(fontSize = 13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Open sign-in page", primary = true) { uri.openUri(url) }
                    Btn("Copy link") { copy(url) }
                }
            }
        }
        login.deviceCode?.let { (code, verify) ->
            Card {
                Text("Enter this code at ${verify.removePrefix("https://")}", style = Ph.body.copy(fontSize = 13.sp))
                Text(code, style = Ph.mono.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ph.Amber, letterSpacing = 3.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Copy & open", primary = true) { copy(code); uri.openUri(verify) }
                    Btn("Copy code") { copy(code) }
                }
            }
        }
        login.dialog?.let { DialogStep(it) }
        if (login.lines.isNotEmpty()) Text(login.lines.takeLast(6).joinToString("\n"), style = Ph.mono.copy(color = Ph.TextSoft),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        when {
            login.done -> Card {
                Text("✓ Signed in to ${p.name}", style = Ph.title.copy(color = Ph.Green))
                Text("Its models are now available in /model. Idle tasks pick it up on their next message.", style = Ph.body.copy(fontSize = 13.sp))
                Btn("Done") { onBack() }
            }
            login.error != null -> Card {
                Text("Sign-in didn't complete", style = Ph.title.copy(color = Ph.Red))
                Text(login.error!!, style = Ph.mono.copy(color = Ph.TextSoft))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Try again", primary = true) { Control.startLogin(p, login.authType) }
                    Btn("Back") { onBack() }
                }
            }
            login.running && login.dialog == null && login.authUrl == null && login.deviceCode == null ->
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulseDot(Ph.Amber); Text("Waiting for pi…", style = Ph.body.copy(color = Ph.Muted))
                }
        }
        if (login.running) Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Btn("Cancel", color = Ph.Red) { Control.cancelLogin(); onBack() } }
    }
}

@Composable
private fun DialogStep(d: Control.Dialog) {
    var value by remember(d.requestId) { mutableStateOf("") }
    Card {
        Text(d.title, style = Ph.title.copy(fontSize = 14.sp))
        if (d.method == "select") d.options.forEach { o ->
            Text(o, style = Ph.mono.copy(color = Ph.Text), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).clickable { Control.answer(o) }.padding(horizontal = 12.dp, vertical = 12.dp))
        } else {
            val secret = d.kind == "secret"
            BasicTextField(value, { value = it }, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
                cursorBrush = SolidColor(Ph.Amber),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ph.Ground)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp)).padding(12.dp),
                decorationBox = { inner -> Box { if (value.isEmpty()) Text(d.placeholder.ifBlank { if (d.kind == "manual_code") "Paste redirect URL or code" else "" }, style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
            if (secret) Text("Saved only on this phone, in Saturn's private storage.", style = Ph.monoSmall.copy(color = Ph.Muted))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Continue", primary = true, enabled = value.isNotBlank()) { Control.answer(value.trim()) }
            }
        }
    }
}

// -------------------------------------------------------------------- tree

/** /tree: pick any earlier point of this task's session to continue from. */
@Composable
fun TreeSheet(t: TaskUi, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { t.responses.remove("get_tree"); UiStore.rpc(t, "get_tree") }
    val data = t.responses["get_tree"]
    PhSheet(onDismiss) {
        Title("Session tree", "Tap a point to continue from there. Nothing is lost — other branches stay in the tree.")
        if (data == null) Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { PulseDot(Ph.Amber); Text("Loading…", style = Ph.body.copy(color = Ph.Muted)) }
        else {
            val rows = remember(data) { flattenTree(data) }
            if (rows.isEmpty()) Text("No messages yet.", style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.padding(20.dp))
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                items(rows, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().clickable {
                        UiStore.appCommand(t, "tree", JSONObject().put("entryId", r.id))
                        // Like pi's TUI: choosing one of your messages puts it back in the editor.
                        if (r.user) t.prefill = r.full
                        onDismiss()
                    }.padding(start = (20 + r.depth * 14).dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (r.user) "›" else "◦", style = Ph.mono.copy(color = if (r.onPath) Ph.Amber else Ph.Muted), modifier = Modifier.width(12.dp))
                        Text(r.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            style = (if (r.user) Ph.body else Ph.mono).copy(fontSize = 13.sp, color = if (r.onPath) Ph.Text else Ph.TextBody))
                        if (r.leaf) Text("● here", style = Ph.monoSmall.copy(color = Ph.Amber))
                        else if (r.branches > 1) Text("${r.branches} branches", style = Ph.monoSmall.copy(color = Ph.Cyan))
                    }
                }
            }
        }
    }
}

private data class TreeRow(val id: String, val text: String, val user: Boolean, val depth: Int,
                           val onPath: Boolean, val leaf: Boolean, val branches: Int, val full: String = text)

private fun flattenTree(data: JSONObject): List<TreeRow> {
    val leaf = data.optString("leafId")
    val parent = mutableMapOf<String, String?>()
    fun index(n: JSONObject, p: String?) {
        val id = n.optJSONObject("entry")?.optString("id") ?: return
        parent[id] = p
        n.optJSONArray("children")?.let { c -> for (i in 0 until c.length()) index(c.getJSONObject(i), id) }
    }
    val roots = data.optJSONArray("tree") ?: JSONArray()
    for (i in 0 until roots.length()) index(roots.getJSONObject(i), null)
    val path = generateSequence(leaf.takeIf { it.isNotBlank() }) { parent[it] }.toSet()
    val out = mutableListOf<TreeRow>()
    fun walk(n: JSONObject, depth: Int) {
        val e = n.optJSONObject("entry") ?: return
        val id = e.optString("id")
        val kids = n.optJSONArray("children") ?: JSONArray()
        val m = e.optJSONObject("message")
        val role = m?.optString("role")
        val text = when {
            role == "user" -> contentText(m)
            role == "assistant" -> contentText(m).ifBlank { "(tool calls)" }
            e.optString("type") == "branch_summary" -> "summary: " + e.optString("summary")
            else -> null
        }?.lines()?.firstOrNull { it.isNotBlank() }?.take(140)
        if (text != null) out += TreeRow(id, text, role == "user", depth, id in path, id == leaf, kids.length(),
            full = if (role == "user") contentText(m!!) else text)
        val childDepth = if (kids.length() > 1) depth + 1 else depth
        for (i in 0 until kids.length()) walk(kids.getJSONObject(i), childDepth)
    }
    for (i in 0 until roots.length()) walk(roots.getJSONObject(i), 0)
    return out
}

private fun contentText(m: JSONObject): String = when (val c = m.opt("content")) {
    is String -> c
    is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }
        .joinToString(" ") { it.optString("text") }
    else -> ""
}

// -------------------------------------------------------------------- fork

/** /fork: start a new session from an earlier message (its text comes back for editing). */
@Composable
fun ForkSheet(t: TaskUi, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { t.responses.remove("get_fork_messages"); UiStore.rpc(t, "get_fork_messages") }
    val msgs = t.responses["get_fork_messages"]?.optJSONArray("messages")
    PhSheet(onDismiss) {
        Title("Fork", "Start a new branch from one of your earlier messages.")
        if (msgs == null) Row(Modifier.padding(20.dp)) { PulseDot(Ph.Amber) }
        else LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items((0 until msgs.length()).map { msgs.getJSONObject(it) }.reversed(), key = { it.optString("entryId") }) { m ->
                Text(m.optString("text").lines().first().take(160), style = Ph.body.copy(fontSize = 13.sp, color = Ph.Text),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { UiStore.rpc(t, "fork", "entryId" to m.optString("entryId")); onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

// ----------------------------------------------------------------- session

/** /session: statistics for this task's session. */
@Composable
fun SessionSheet(t: TaskUi, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { t.responses.remove("get_session_stats"); UiStore.rpc(t, "get_session_stats") }
    val d = t.responses["get_session_stats"]
    PhSheet(onDismiss) {
        Title("Session", t.title)
        if (d == null) Row(Modifier.padding(20.dp)) { PulseDot(Ph.Amber) }
        else {
            val tok = d.optJSONObject("tokens") ?: JSONObject()
            val ctx = d.optJSONObject("contextUsage")
            fun k(n: Long) = if (n >= 1000) String.format(Locale.US, "%.1fk", n / 1000.0) else "$n"
            val rows = listOf(
                "Messages" to "${d.optInt("userMessages")} you · ${d.optInt("assistantMessages")} Saturn · ${d.optInt("toolCalls")} tool calls",
                "Tokens" to "${k(tok.optLong("input"))} in · ${k(tok.optLong("output"))} out · ${k(tok.optLong("cacheRead"))} cached",
                "Cost" to String.format(Locale.US, "$%.4f", d.optDouble("cost", 0.0)),
                "Context" to (ctx?.let { "${k(it.optLong("tokens"))}" + (it.optDouble("percent", -1.0).takeIf { p -> p >= 0 }?.let { p -> String.format(Locale.US, " (%.1f%%)", p) } ?: "") } ?: "—"),
                "Model" to (UiStore.model ?: "—"),
                "Thinking" to (t.thinkingLevel ?: "—"),
                "Session id" to d.optString("sessionId"),
                "Folder" to t.task.cwd.path,
            )
            Card { rows.forEach { (a, b) -> Row { Text(a, style = Ph.monoSmall, modifier = Modifier.width(96.dp)); Text(b, style = Ph.mono.copy(color = Ph.TextSoft)) } } }
        }
    }
}

// ---------------------------------------------------------------- thinking

/** /thinking: how much the model reasons before answering (when it supports it). */
@Composable
fun ThinkingSheet(t: TaskUi, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { UiStore.rpc(t, "get_available_thinking_levels"); UiStore.rpc(t, "get_state") }
    val levels = t.responses["get_available_thinking_levels"]?.optJSONArray("levels")
    PhSheet(onDismiss) {
        Title("Thinking level", "Higher levels think longer: better on hard problems, slower and pricier.")
        if (levels == null) Row(Modifier.padding(20.dp)) { PulseDot(Ph.Amber) }
        else if (levels.length() == 0) Text("This model has no thinking levels.", style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.padding(20.dp))
        else (0 until levels.length()).map { levels.getString(it) }.forEach { l ->
            val on = l == t.thinkingLevel
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { UiStore.rpc(t, "set_thinking_level", "level" to l); t.thinkingLevel = l; onDismiss() }
                .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l, style = Ph.mono.copy(fontSize = 14.sp, color = if (on) Ph.Amber else Ph.Text), modifier = Modifier.weight(1f))
                if (on) Text("current", style = Ph.monoSmall.copy(color = Ph.Amber))
            }
        }
    }
}

// ------------------------------------------------------------------ resume

/** /resume: reopen any earlier pi session (from this app) as a task. */
@Composable
fun ResumeSheet(onOpen: (TaskUi) -> Unit, onDismiss: () -> Unit) {
    val sessions = remember { listSessions() }
    PhSheet(onDismiss) {
        Title("Resume", "Reopen an earlier session as a task.")
        if (sessions.isEmpty()) Text("No saved sessions yet.", style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.padding(20.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            items(sessions, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().clickable {
                    val existing = UiStore.tasks.firstOrNull { it.task.sessionId == s.id }
                    onOpen(existing ?: UiStore.ui(TaskManager.adopt(s.id, s.title, File(s.cwd))))
                    onDismiss()
                }.padding(horizontal = 20.dp, vertical = 11.dp)) {
                    Text(s.title, style = Ph.body.copy(fontSize = 14.sp, color = Ph.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.date} · ${File(s.cwd).name} · ${s.messages} messages", style = Ph.monoSmall)
                }
            }
        }
    }
}

private data class SessionInfo(val id: String, val title: String, val cwd: String, val date: String, val messages: Int)

private fun listSessions(): List<SessionInfo> {
    val root = File(UiStore.runtime.home, ".pi/agent/sessions")
    return root.walkTopDown().maxDepth(2).filter { it.isFile && it.name.endsWith(".jsonl") }
        .sortedByDescending { it.lastModified() }.take(100).mapNotNull { f ->
            runCatching {
                var id = ""; var cwd = ""; var title = ""; var name = ""; var count = 0
                f.useLines { lines ->
                    for (l in lines) {
                        val e = JSONObject(l)
                        when (e.optString("type")) {
                            "session" -> { id = e.optString("id"); cwd = e.optString("cwd") }
                            "session_info" -> e.optString("name").takeIf { it.isNotBlank() }?.let { name = it }
                            "message" -> {
                                val m = e.optJSONObject("message")
                                if (m?.optString("role") == "user") { count++; if (title.isBlank()) title = contentText(m) }
                                else if (m?.optString("role") == "assistant") count++
                            }
                        }
                    }
                }
                if (id.isBlank() || count == 0) null
                else SessionInfo(id, name.ifBlank { title.lines().first().take(100) }, cwd,
                    java.text.SimpleDateFormat("MMM d, HH:mm", Locale.US).format(java.util.Date(f.lastModified())), count)
            }.getOrNull()
        }.toList()
}

// --------------------------------------------------------------- changelog

@Composable
fun ChangelogSheet(onDismiss: () -> Unit) {
    val text = remember {
        runCatching { File(UiStore.runtime.piRoot, "node_modules/@earendil-works/pi-coding-agent/CHANGELOG.md").readText().take(14_000) }
            .getOrDefault("Changelog not found.")
    }
    PhSheet(onDismiss) {
        Title("What's new in pi")
        Box(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) { Markdown(text) }
    }
}
