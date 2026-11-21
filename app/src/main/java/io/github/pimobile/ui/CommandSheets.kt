package io.github.pimobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
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
import kotlinx.coroutines.launch
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
private fun Btn(label: String, primary: Boolean = false, color: Color = Ph.Text, enabled: Boolean = true, wide: Boolean = false, onClick: () -> Unit) =
    Box((if (wide) Modifier.fillMaxWidth() else Modifier).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
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
internal fun copier(): (String) -> Unit {
    val ctx = LocalContext.current
    return { t -> ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Saturn", t)) }
}

// ------------------------------------------------------------------- login

/** Providers people reach for first; shown above the full list when not searching. */
private val POPULAR = listOf("anthropic", "openai", "openai-codex", "openrouter", "google", "github-copilot", "deepseek", "xai", "groq")

/** /login and /logout: pi's own provider flows (OAuth subscriptions, device codes, API keys). */
@Composable
fun LoginSheet(logoutMode: Boolean = false, initialQuery: String = "", onDismiss: () -> Unit) {
    val login = Control.login
    var query by remember { mutableStateOf(initialQuery) }
    LaunchedEffect(Unit) { if (Control.providers == null) Control.loadProviders(); Control.refreshSignedIn() }
    PhSheet({ if (login.running) Control.cancelLogin(); login.reset(); onDismiss() }) {
        val p = login.provider
        AnimatedContent(p != null && (login.running || login.done || login.error != null), label = "login",
            transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (targetState) it / 6 else -it / 6 }) togetherWith fadeOut(tween(140)) }) { flow ->
            Column(Modifier.fillMaxWidth()) {
                if (flow && p != null) LoginFlow(p, onBack = { login.reset(); login.provider = null })
                else ProviderList(logoutMode, query, { query = it })
            }
        }
    }
}

/** pi's /login: first choose how to sign in, then the provider. */
@Composable
private fun ProviderList(logoutMode: Boolean, query: String, onQuery: (String) -> Unit) {
    val providers = Control.providers
    var method by remember { mutableStateOf<String?>(if (logoutMode || query.isNotBlank()) "any" else null) }
    val radius = providers?.firstOrNull { it.id == "radius" }

    if (method == null) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp)) {
            Text("Log in", style = Ph.heading)
            Text("Choose how Saturn connects to an AI.", style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp))
        }
        MethodRow(PhIcons.Key, "API key", "OpenRouter, Anthropic, OpenAI, Gemini, Groq and 40 more") { method = "api_key" }
        MethodRow(PhIcons.Sparkle, "Subscription", "Free OpenRouter, Claude Pro/Max, ChatGPT Plus/Pro, GitHub Copilot…",
            tag = "free option") { method = "oauth" }
        when {
            radius != null -> MethodRow(PhIcons.Globe, radius.name, "One account for many models", done = radius.id in Control.signedIn) {
                Control.startLogin(radius, "oauth") }
            providers == null && Control.providersError == null -> Loading("Loading providers…")
        }
        Control.providersError?.let { Text("Couldn't load providers: $it", style = Ph.body.copy(color = Ph.Red), modifier = Modifier.padding(20.dp)) }
        return
    }

    if (method == "custom") { CustomEndpoint(onBack = { method = "api_key" }); return }

    Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!logoutMode) Box(Modifier.size(40.dp).clip(CircleShape).clickable { method = null; onQuery("") }, contentAlignment = Alignment.Center) {
            PhIcon(PhIcons.Back, Ph.TextSoft, 20.dp)
        } else Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(when { logoutMode -> "Log out"; method == "oauth" -> "Use a subscription"; method == "api_key" -> "Paste an API key"; else -> "Log in" }, style = Ph.heading.copy(fontSize = 20.sp))
            Text(when { logoutMode -> "Remove a saved sign-in from this phone"; method == "oauth" -> "Pick your plan, then finish in the browser"; method == "api_key" -> "Pick a provider, then paste its key"; else -> "Pick a provider" },
                style = Ph.monoSmall)
        }
    }
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
        .border(1.dp, if (query.isEmpty()) Ph.Line else Ph.AmberLine, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("›", style = Ph.mono.copy(color = Ph.Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            BasicTextField(query, onQuery, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
                cursorBrush = SolidColor(Ph.Amber), modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("search providers", style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
        }
        if (query.isNotEmpty()) Box(Modifier.size(22.dp).clip(CircleShape).clickable { onQuery("") }, contentAlignment = Alignment.Center) { PhIcon(PhIcons.Close, Ph.Muted, 14.dp) }
    }
    when {
        Control.providersError != null -> Text("Couldn't load providers: ${Control.providersError}", style = Ph.body.copy(color = Ph.Red),
            modifier = Modifier.padding(20.dp))
        providers == null -> Loading("Loading providers from pi…")
        else -> {
            val shown = providers.filter {
                (when (method) { "oauth" -> it.oauth && it.id != "radius"; "api_key" -> it.apiKey; else -> true }) &&
                    (!logoutMode || it.id in Control.signedIn) &&
                    (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true))
            }
            if (shown.isEmpty()) Text(if (logoutMode) "You're not signed in to any provider." else "Nothing matches “$query”.",
                style = Ph.body.copy(color = Ph.Muted), modifier = Modifier.padding(20.dp))
            // Signed-in first, then the popular ones, then everything else A→Z.
            val groups = if (query.isNotBlank() || logoutMode || shown.size < 8) listOf<Pair<String?, List<Control.Provider>>>(null to shown)
            else {
                val signed = shown.filter { it.id in Control.signedIn }
                val popular = shown.filter { it.id !in Control.signedIn && it.id in POPULAR }.sortedBy { POPULAR.indexOf(it.id) }
                val rest = shown.filter { it !in signed && it !in popular }.sortedBy { it.name.lowercase() }
                listOf("SIGNED IN" to signed, "POPULAR" to popular, "ALL PROVIDERS · ${rest.size}" to rest).filter { it.second.isNotEmpty() }
            }
            val openrouter = providers.firstOrNull { it.id == "openrouter" }
            LazyColumn(Modifier.heightIn(max = 540.dp)) {
                if (method == "api_key" && query.isBlank()) item(key = "custom") {
                    MethodRow(PhIcons.Terminal, "Custom endpoint", "Any OpenAI- or Anthropic-compatible API: Ollama, LM Studio, vLLM, a proxy…") { method = "custom" }
                }
                if (method == "oauth" && query.isBlank() && openrouter != null) item(key = "free") {
                    Column {
                        Section("RECOMMENDED", Ph.Amber)
                        MethodRow(PhIcons.Sparkle, "Free with OpenRouter", "Make a free key in about a minute. Saturn uses free models only, so you're never charged.",
                            tag = "free", highlight = true, done = openrouter.id in Control.signedIn) {
                            Control.login.pickFree = true
                            Control.startLogin(openrouter, "api_key")
                        }
                        if (groups.firstOrNull()?.first == null) Section("SUBSCRIPTIONS")
                    }
                }
                groups.forEach { (label, list) ->
                    if (label != null) item(key = "h-$label") { Section(label, if (label == "SIGNED IN") Ph.Green else Ph.Muted) }
                    items(list, key = { (label ?: "") + it.id }) { p -> ProviderRow(p, logoutMode, method ?: "any") }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String, color: Color = Ph.Muted) =
    Text(text, style = Ph.label.copy(color = color), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Loading(text: String) = Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp)) { PulseDot(Ph.Amber); Text(text, style = Ph.body.copy(color = Ph.Muted)) }

@Composable
private fun MethodRow(icon: ImageVector, title: String, sub: String, tag: String? = null, highlight: Boolean = false,
                      done: Boolean = false, onClick: () -> Unit) = Row(
    Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (highlight) Ph.AmberWash else Ph.Raised)
        .border(1.dp, if (highlight) Ph.AmberLine else Ph.Line, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(14.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (highlight) Ph.Amber else Ph.Ground)
        .border(1.dp, if (highlight) Ph.Amber else Ph.LineStrong, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        PhIcon(icon, if (highlight) Ph.AmberInk else Ph.Amber, 20.dp)
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = Ph.title)
            tag?.let { Text(it, style = Ph.monoSmall.copy(color = Ph.Amber, fontSize = 10.sp)) }
        }
        Text(sub, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.Muted))
    }
    if (done) PhIcon(PhIcons.Check, Ph.Green, 18.dp)
    else PhIcon(PhIcons.Chevron, Ph.Muted, 18.dp, modifier = Modifier.rotate(-90f))
}

/** A two-letter monogram tile: providers have no logos we can ship, so give each a steady mark. */
@Composable
private fun Monogram(p: Control.Provider, size: Int = 38, signedIn: Boolean = false) {
    val letters = p.name.split(' ', '-', '.').filter { it.isNotBlank() }.let { w ->
        if (w.size >= 2) "${w[0][0]}${w[1][0]}" else p.name.take(2) }.uppercase()
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3f).dp)).background(if (signedIn) Ph.Green.copy(alpha = 0.12f) else Ph.Ground)
        .border(1.dp, if (signedIn) Ph.Green.copy(alpha = 0.45f) else Ph.LineStrong, RoundedCornerShape((size * 0.3f).dp)),
        contentAlignment = Alignment.Center) {
        Text(letters, style = Ph.mono.copy(fontSize = (size * 0.34f).sp, fontWeight = FontWeight.SemiBold, color = if (signedIn) Ph.Green else Ph.Amber))
    }
}

@Composable
private fun ProviderRow(p: Control.Provider, logoutMode: Boolean, method: String) {
    val signedIn = p.id in Control.signedIn
    Row(Modifier.fillMaxWidth().clickable {
        Control.login.pickFree = false
        when {
            logoutMode -> Control.logout(p)
            method == "oauth" -> Control.startLogin(p, "oauth")
            method == "api_key" -> Control.startLogin(p, "api_key")
            else -> Control.startLogin(p, if (p.oauth) "oauth" else "api_key")
        }
    }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Monogram(p, signedIn = signedIn)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (method == "oauth") p.oauthLabel ?: p.name else p.name, style = Ph.title.copy(fontSize = 14.5.sp),
                    modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.subscription && method == "oauth") Badge("plan", Ph.Amber)
            }
            Text(listOfNotNull(p.id, p.source?.let { "via $it" }, if (signedIn) "signed in" else null).joinToString(" · "),
                style = Ph.monoSmall.copy(color = if (signedIn) Ph.Green.copy(alpha = 0.8f) else Ph.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            logoutMode -> Text("log out", style = Ph.mono.copy(color = Ph.Red))
            signedIn -> PhIcon(PhIcons.Check, Ph.Green, 16.dp)
            else -> PhIcon(PhIcons.Chevron, Ph.Muted, 16.dp, modifier = Modifier.rotate(-90f))
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) = Text(text, style = Ph.monoSmall.copy(color = color, fontSize = 10.sp),
    modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))

@Composable
private fun LoginFlow(p: Control.Provider, onBack: () -> Unit) {
    val login = Control.login
    val uri = LocalUriHandler.current
    val copy = copier()
    var openedUrl by remember { mutableStateOf<String?>(null) }
    // Open the sign-in page automatically; pi's local callback server finishes the login.
    LaunchedEffect(login.done) {
        if (login.done && login.pickFree && p.id == "openrouter") { UiStore.setModel(null, "openrouter", Onboarding.FREE_MODEL); login.pickFree = false }
    }
    LaunchedEffect(login.authUrl) { login.authUrl?.let { if (openedUrl != it) { openedUrl = it; runCatching { uri.openUri(it) } } } }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (login.running) Control.cancelLogin(); onBack() }, contentAlignment = Alignment.Center) {
                PhIcon(PhIcons.Back, Ph.TextSoft, 20.dp)
            }
            Monogram(p, 44, signedIn = login.done)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(p.name, style = Ph.heading.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (login.authType == "oauth") "signing in with ${p.oauthLabel ?: "your account"}" else "connecting with an API key", style = Ph.monoSmall)
            }
        }
        login.authUrl?.takeIf { !login.done }?.let { url ->
            Card {
                StepHead("1", "Finish in your browser")
                Text(login.authInstructions ?: "We opened the sign-in page. Once you approve, Saturn picks it up by itself. If it doesn't, paste the final URL or code below.",
                    style = Ph.body.copy(fontSize = 13.sp, lineHeight = 20.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Open sign-in page", primary = true) { uri.openUri(url) }
                    Btn("Copy link") { copy(url) }
                }
            }
        }
        login.deviceCode?.takeIf { !login.done }?.let { (code, verify) ->
            Card {
                StepHead("1", "Enter this code at ${verify.removePrefix("https://").removeSuffix("/")}")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground).border(1.dp, Ph.AmberLine, RoundedCornerShape(12.dp))
                    .clickable { copy(code) }.padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                    Text(code, style = Ph.mono.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Ph.Amber, letterSpacing = 4.sp))
                }
                Btn("Copy code & open", primary = true) { copy(code); uri.openUri(verify) }
            }
        }
        login.dialog?.let { DialogStep(it) }
        if (login.lines.isNotEmpty() && !login.done) Text(login.lines.takeLast(4).joinToString("\n"), style = Ph.monoSmall.copy(color = Ph.Muted),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        when {
            login.done -> Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Ph.Green.copy(alpha = 0.12f)).border(1.dp, Ph.Green.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center) { PhIcon(PhIcons.Check, Ph.Green, 28.dp) }
                Text("Connected to ${p.name}", style = Ph.heading.copy(fontSize = 19.sp))
                Text("Its models are in /model now. Running tasks switch on their next message.",
                    style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted, textAlign = TextAlign.Center))
                Box(Modifier.padding(top = 6.dp).fillMaxWidth()) { Btn("Done", primary = true, wide = true) { onBack() } }
            }
            login.error != null -> Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Ph.RedWash).border(1.dp, Ph.Red.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(PhIcons.Warning, Ph.Red, 16.dp); Text("Sign-in didn't finish", style = Ph.title.copy(color = Ph.Red))
                }
                Text(login.error!!, style = Ph.mono.copy(color = Ph.TextSoft))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Try again", primary = true) { Control.startLogin(p, login.authType) }
                    Btn("Back") { onBack() }
                }
            }
            login.running && login.dialog == null -> Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PulseDot(Ph.Amber)
                Text(if (login.authUrl != null || login.deviceCode != null) "Waiting for you to approve…" else "Starting…", style = Ph.mono.copy(color = Ph.TextSoft))
            }
        }
        if (login.running) Text("Cancel", style = Ph.mono.copy(color = Ph.Red), modifier = Modifier.padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp)).clickable { Control.cancelLogin(); onBack() }.padding(12.dp))
    }
}

@Composable
private fun StepHead(n: String, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(22.dp).clip(CircleShape).background(Ph.Amber), contentAlignment = Alignment.Center) {
        Text(n, style = Ph.monoSmall.copy(color = Ph.AmberInk, fontWeight = FontWeight.Bold))
    }
    Text(text, style = Ph.title.copy(fontSize = 14.5.sp))
}

@Composable
private fun DialogStep(d: Control.Dialog) {
    var value by remember(d.requestId) { mutableStateOf("") }
    Card {
        StepHead(if (Control.login.authUrl != null || Control.login.deviceCode != null) "2" else "1", d.title)
        val uri = LocalUriHandler.current
        if (Control.login.pickFree && Control.login.provider?.id == "openrouter" && d.kind == "secret")
            Btn("Get a free key at openrouter.ai", wide = true) { runCatching { uri.openUri("https://openrouter.ai/keys") } }
        if (d.method == "select") d.options.forEach { o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
                .border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp)).clickable { Control.answer(o) }.padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(o, style = Ph.mono.copy(color = Ph.Text, fontSize = 13.sp), modifier = Modifier.weight(1f))
                PhIcon(PhIcons.Chevron, Ph.Muted, 14.dp, modifier = Modifier.rotate(-90f))
            }
        } else {
            val secret = d.kind == "secret"
            var reveal by remember(d.requestId) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
                .border(1.dp, if (value.isEmpty()) Ph.LineStrong else Ph.AmberLine, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value, { value = it }, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
                    cursorBrush = SolidColor(Ph.Amber),
                    visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
                    modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                    decorationBox = { inner -> Box { if (value.isEmpty()) Text(d.placeholder.ifBlank { if (d.kind == "manual_code") "Paste redirect URL or code" else if (secret) "Paste your key" else "" }, style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
                if (secret && value.isNotEmpty()) Text(if (reveal) "hide" else "show", style = Ph.monoSmall.copy(color = Ph.Amber),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { reveal = !reveal }.padding(8.dp))
                val paste = LocalContext.current
                if (value.isEmpty()) Text("paste", style = Ph.monoSmall.copy(color = Ph.Amber), modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    paste.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(paste)
                        ?.toString()?.trim()?.let { value = it }
                }.padding(8.dp))
            }
            if (secret) Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(PhIcons.Lock, Ph.Muted, 12.dp); Text("  Saved only on this phone, in Saturn's private storage", style = Ph.monoSmall)
            }
            Btn("Continue", primary = true, enabled = value.isNotBlank(), wide = true) { Control.answer(value.trim()) }
        }
    }
}

/** API styles pi can talk to, as (pi api id, label). */
private val API_KINDS = listOf("openai-completions" to "OpenAI-compatible", "anthropic-messages" to "Anthropic",
    "openai-responses" to "OpenAI Responses", "google-generative-ai" to "Gemini")

@Composable
private fun Field(label: String, value: String, hint: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
                  onChange: (String) -> Unit) = Column(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
    Text(label, style = Ph.label, modifier = Modifier.padding(bottom = 6.dp))
    BasicTextField(value, onChange, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
        cursorBrush = SolidColor(Ph.Amber), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
            .border(1.dp, if (value.isEmpty()) Ph.LineStrong else Ph.AmberLine, RoundedCornerShape(12.dp)).padding(14.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
}

/** Any compatible endpoint, saved to pi's models.json. "Find models" asks the endpoint's /models. */
@Composable
private fun CustomEndpoint(onBack: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var api by remember { mutableStateOf(API_KINDS[0].first) }
    var models by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ids = models.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val okUrl = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 12.dp, end = 20.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onBack() }, contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 20.dp) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Custom endpoint", style = Ph.heading.copy(fontSize = 20.sp))
                Text("Point Saturn at any compatible API", style = Ph.monoSmall)
            }
        }
        if (saved) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Ph.Green.copy(alpha = 0.12f)).border(1.dp, Ph.Green.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center) { PhIcon(PhIcons.Check, Ph.Green, 28.dp) }
                Text("${name.ifBlank { "Custom" }} added", style = Ph.heading.copy(fontSize = 19.sp))
                Text("${ids.first()} is now the default model. The others are in /model.",
                    style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted, textAlign = TextAlign.Center))
            }
            return@Column
        }
        Field("NAME", name, "e.g. My Ollama") { name = it }
        Field("BASE URL", url, "http://192.168.1.5:11434/v1", keyboard = KeyboardType.Uri) { url = it.trim() }
        Text("API STYLE", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 6.dp))
        Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            API_KINDS.forEach { (id, label) ->
                val on = id == api
                Text(label, style = Ph.mono.copy(color = if (on) Ph.AmberInk else Ph.TextSoft), modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (on) Ph.Amber else Ph.Raised).border(1.dp, if (on) Ph.Amber else Ph.LineStrong, RoundedCornerShape(10.dp))
                    .clickable { api = id }.padding(horizontal = 12.dp, vertical = 9.dp))
            }
        }
        Field("API KEY", key, "optional for local servers", secret = true, keyboard = KeyboardType.Password) { key = it.trim() }
        Field("MODELS", models, "model id, or several separated by commas") { models = it }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Find models", enabled = okUrl) {
                status = "Asking ${url.trim().trimEnd('/')}/models…"
                scope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { listModels(url, key, api) } }
                    r.onSuccess { found = it; status = if (it.isEmpty()) "No models listed; type the id yourself." else "${it.size} found, tap to add" }
                        .onFailure { status = "Couldn't list models: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
            status?.let { Text(it, style = Ph.monoSmall, modifier = Modifier.weight(1f)) }
        }
        if (found.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            found.forEach { m ->
                val on = m in ids
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                    models = (if (on) ids - m else ids + m).joinToString(", ")
                }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "●" else "○", style = Ph.mono.copy(color = if (on) Ph.Amber else Ph.Muted), modifier = Modifier.width(20.dp))
                    Text(m, style = Ph.mono.copy(color = if (on) Ph.Text else Ph.TextSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Btn("Save endpoint", primary = true, enabled = okUrl && ids.isNotEmpty(), wide = true) {
                val slug = name.ifBlank { url.substringAfter("//").substringBefore('/').substringBefore(':') }
                    .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "endpoint" }
                UiStore.saveCustomProvider("custom-$slug", name.ifBlank { slug }, url, api, key, ids)
                saved = true
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            PhIcon(PhIcons.Lock, Ph.Muted, 12.dp); Text("  Saved in pi's models.json on this phone", style = Ph.monoSmall)
        }
    }
}

/** GET {base}/models, the way each API style authenticates. */
private fun listModels(base: String, key: String, api: String): List<String> {
    val b = base.trim().trimEnd('/')
    val url = if (api == "google-generative-ai") "$b/models" + (if (key.isNotBlank()) "?key=$key" else "") else "$b/models"
    val headers = buildMap {
        if (key.isNotBlank()) when (api) {
            "anthropic-messages" -> { put("x-api-key", key); put("anthropic-version", "2023-06-01") }
            "google-generative-ai" -> {}
            else -> put("Authorization", "Bearer $key")
        }
    }
    val body = if (url.startsWith("http://")) plainGet(java.net.URL(url), headers) else {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().readText()
    }
    val j = JSONObject(body)
    val arr = j.optJSONArray("data") ?: j.optJSONArray("models") ?: JSONArray()
    return (0 until arr.length()).mapNotNull { i ->
        arr.optJSONObject(i)?.let { o -> (o.optString("id").ifBlank { o.optString("name") }).removePrefix("models/").ifBlank { null } } ?: arr.optString(i).ifBlank { null }
    }.sorted()
}

/**
 * Android refuses the app's own cleartext HTTP, but local servers (Ollama, LM
 * Studio on the LAN) rarely have TLS. A bare socket GET is enough for /models.
 */
private fun plainGet(u: java.net.URL, headers: Map<String, String>): String {
    java.net.Socket().use { sock ->
        sock.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else 80), 8000); sock.soTimeout = 10000
        val path = (u.path.ifBlank { "/" }) + (u.query?.let { "?$it" } ?: "")
        val req = buildString {
            append("GET $path HTTP/1.1\r\nHost: ${u.host}${if (u.port > 0) ":${u.port}" else ""}\r\nAccept: application/json\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        sock.getOutputStream().apply { write(req.toByteArray()); flush() }
        val raw = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
        val head = raw.substringBefore("\r\n\r\n"); var rest = raw.substringAfter("\r\n\r\n", "")
        val code = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        if (code !in 200..299) error("HTTP $code")
        if (head.contains("transfer-encoding: chunked", ignoreCase = true)) rest = buildString {
            var r = rest
            while (true) {
                val n = r.substringBefore("\r\n").trim().substringBefore(';').toIntOrNull(16) ?: break
                if (n == 0) break
                val start = r.indexOf("\r\n") + 2
                // sizes are bytes; the payload is JSON (ASCII-safe in practice)
                append(r.substring(start, minOf(r.length, start + n))); r = r.substring(minOf(r.length, start + n + 2))
            }
        }
        return rest
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
        Title("Branch", "Pick one of your messages: a new task starts just before it, with the message ready to edit. This chat stays as it is.")
        if (msgs == null) Row(Modifier.padding(20.dp)) { PulseDot(Ph.Amber) }
        else LazyColumn(Modifier.heightIn(max = 520.dp)) {
            val all = (0 until msgs.length()).map { msgs.getJSONObject(it) }
            items(all.reversed(), key = { it.optString("entryId") }) { m ->
                Text(m.optString("text").lines().first().take(160), style = Ph.body.copy(fontSize = 13.sp, color = Ph.Text),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable { UiStore.branch(t, all.indexOf(m), m.optString("text")); onDismiss() }
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
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { UiStore.setThinking(t, l); onDismiss() }
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
