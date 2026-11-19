package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.Control
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.launch

/**
 * First run: what Saturn is → connect a model (free OpenRouter in about a
 * minute, a subscription, or an API key) → try a sample task.
 */
object Onboarding {
    /** OpenRouter's router across its free models: new users can't get charged by surprise. */
    const val FREE_MODEL = "openrouter/free"

    private val prefs get() = UiStore.app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    private var done by mutableStateOf<Boolean?>(null)

    /** Existing users (already connected) never see it. */
    fun needed(): Boolean {
        val d = done ?: (prefs.getBoolean("onboarded", false) || UiStore.hasCredentials || Control.signedIn.isNotEmpty()).also { done = it }
        return !d
    }

    fun finish() { prefs.edit().putBoolean("onboarded", true).apply(); done = true }

    private val connected get() = UiStore.hasCredentials || Control.signedIn.isNotEmpty()

    @Composable
    fun Screen() {
        var step by remember { mutableStateOf(0) }
        var sheet by remember { mutableStateOf<String?>(null) }
        // Signing in through a sheet moves on by itself.
        LaunchedEffect(connected, sheet) { if (connected && step == 1) step = 2 }

        Column(Modifier.fillMaxSize().background(Ph.Ground).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Ph.Amber)) {
                    androidx.compose.foundation.Image(painterResource(io.github.pimobile.R.drawable.ic_saturn_foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.weight(1f))
                if (step < 2) Text("skip", style = Ph.mono.copy(color = Ph.Muted), modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable { finish() }.padding(10.dp))
            }
            Dots(step)
            when (step) {
                0 -> Welcome { step = 1 }
                1 -> Connect(onSheet = { sheet = it }) { step = 2 }
                else -> TryOne()
            }
        }
        when (sheet) {
            "login" -> LoginSheet(initialQuery = "") { sheet = null }
        }
    }

    @Composable
    private fun Dots(step: Int) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 18.dp)) {
        repeat(3) { i -> Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(RoundedCornerShape(4.dp))
            .background(if (i <= step) Ph.Amber else Ph.Chip)) }
    }

    @Composable
    private fun Welcome(next: () -> Unit) {
        Text("Meet Saturn", style = Ph.heading.copy(fontSize = 30.sp))
        Text("An AI agent that lives on your phone. It doesn't just chat: it runs real tools, makes things you can keep, and uses your phone's features.",
            style = Ph.body.copy(fontSize = 16.sp, lineHeight = 24.sp))
        Spacer(Modifier.height(4.dp))
        Point(PhIcons.Terminal, "It does the work", "Every request becomes a task. It runs commands, browses, writes files, sets timers.")
        Point(PhIcons.Sparkle, "Things to keep", "Little web apps, notes and reports land in Artifacts, ready to share.")
        Point(PhIcons.Lock, "Your stuff stays yours", "Open source, no accounts, no tracking. Only your chats go to the AI you pick.")
        Spacer(Modifier.height(10.dp))
        Primary("Get started", onClick = next)
    }

    @Composable
    private fun Connect(onSheet: (String) -> Unit, next: () -> Unit) {
        val uri = LocalUriHandler.current
        var key by remember { mutableStateOf("") }
        var open by remember { mutableStateOf(true) }
        Text("Pick an AI to power it", style = Ph.heading.copy(fontSize = 26.sp))
        Text("Saturn works with most AI providers. You can change this any time.", style = Ph.body)

        Card(highlight = true, onClick = { open = !open }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free, with OpenRouter", style = Ph.title.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
                Text("recommended", style = Ph.monoSmall.copy(color = Ph.Amber))
            }
            Text("Make a free account and key (about a minute), paste it here, and Saturn uses free models only.",
                style = Ph.body.copy(fontSize = 13.sp))
            if (open) {
                Ghost("1 · Get a free key at openrouter.ai") { runCatching { uri.openUri("https://openrouter.ai/keys") } }
                BasicTextField(key, { key = it.trim() }, singleLine = true, textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text),
                    cursorBrush = SolidColor(Ph.Amber), visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
                        .border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp)).padding(14.dp),
                    decorationBox = { inner -> if (key.isEmpty()) Text("2 · Paste it here (sk-or-…)", style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() })
                Primary("Save and continue", enabled = key.length > 10) {
                    UiStore.saveApiKey("openrouter", key)
                    // pi's OpenRouter catalog id for the free router is literally "openrouter/free".
                    UiStore.setModel(null, "openrouter", FREE_MODEL)
                    next()
                }
                Text("Stored encrypted on this phone. Free models can be slower; pick any model later in /model.", style = Ph.monoSmall)
            }
        }
        Card(onClick = { onSheet("login") }) {
            Text("Use a subscription you already have", style = Ph.title.copy(fontSize = 16.sp))
            Text("ChatGPT Plus/Pro, Claude, GitHub Copilot and more. Signs in through your browser.", style = Ph.body.copy(fontSize = 13.sp))
        }
        Card(onClick = { onSheet("login") }) {
            Text("I have an API key", style = Ph.title.copy(fontSize = 16.sp))
            Text("Anthropic, OpenAI, Gemini, DeepSeek, Groq, xAI, Mistral…", style = Ph.body.copy(fontSize = 13.sp))
        }
    }

    @Composable
    private fun TryOne() {
        Text("You're set. Try one", style = Ph.heading.copy(fontSize = 26.sp))
        Text("Tap one to start it, or write your own on the next screen.", style = Ph.body)
        listOf(
            "What's my battery at, and how hot is the phone?",
            "Make me a simple to-do list web app",
            "Turn on the torch, then turn it off in 30 seconds",
            "Explain what you can do on this phone",
        ).forEach { prompt ->
            Card(onClick = { UiStore.newTask(prompt, UiStore.currentProject); finish() }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("›", style = Ph.mono.copy(color = Ph.Amber, fontSize = 16.sp))
                    Text(prompt, style = Ph.body.copy(color = Ph.Text, fontSize = 15.sp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Ghost("I'll write my own") { finish() }
    }

    @Composable
    private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center) { PhIcon(icon, Ph.Amber, 18.dp) }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Ph.title.copy(fontSize = 15.sp))
                Text(body, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.Muted))
            }
        }
    }

    @Composable
    private fun Card(highlight: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ph.Surface)
            .border(1.dp, if (highlight) Ph.AmberLine else Ph.Line, RoundedCornerShape(18.dp))
            .clickable { onClick() }.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }

    @Composable
    private fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) Ph.Amber else Ph.Chip)
            .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
            Text(label, style = Ph.title.copy(fontSize = 16.sp, color = if (enabled) Ph.AmberInk else Ph.Muted))
        }
    }

    @Composable
    private fun Ghost(label: String, onClick: () -> Unit) {
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp))
            .clickable { onClick() }, contentAlignment = Alignment.Center) {
            Text(label, style = Ph.title.copy(fontSize = 14.sp))
        }
    }
}
