package io.github.pimobile.phonecontrol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        PhoneControl.init(this); PhoneControl.loadPrefs(); McpServer.load(this)
        setContent { PhosphorTheme { Screen() } }
    }
}

private val Teal = Color(0xFF6FB7CC)
private val TealInk = Color(0xFF0E1A1E)

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PhoneControlService.instance != null) }
    LaunchedEffect(Unit) { while (true) { on = PhoneControlService.instance != null; delay(700) } }
    fun copy(label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(Modifier.fillMaxSize().background(Ph.Ground).verticalScroll(rememberScrollState())
        .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Teal), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_foreground), null, Modifier.size(48.dp))
            }
            Column {
                Text("Phone Control", style = Ph.heading)
                Text("an MCP server for AI agents on this phone", style = Ph.monoSmall)
            }
        }

        // ---- status
        Card {
            if (!on) {
                Text("Turn it on", style = Ph.title)
                Text("Android Settings → Accessibility → Installed apps → “Phone Control (MCP)”. Then come back here.",
                    style = Ph.body.copy(fontSize = 13.sp))
                Button("Open Accessibility settings", primary = true) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                Text("Switch greyed out (“Restricted setting”)? Android does that to apps installed from a file: App info → ⋮ → Allow restricted settings.",
                    style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
                Button("Open App info") { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (PhoneControl.paused) Ph.Muted else Ph.Green))
                    Text(if (PhoneControl.paused) "Paused" else "Ready for agents", style = Ph.title)
                    Box(Modifier.weight(1f))
                    Text("127.0.0.1:${McpServer.port}", style = Ph.monoSmall)
                }
                Row(Modifier.fillMaxWidth().clickable { PhoneControl.pause(!PhoneControl.paused) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Let agents use the phone", style = Ph.body, modifier = Modifier.weight(1f))
                    Switch(!PhoneControl.paused, null, colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = TealInk,
                        uncheckedTrackColor = Ph.Chip, uncheckedThumbColor = Ph.Muted, uncheckedBorderColor = Ph.LineStrong))
                }
            }
        }
        if (PhoneControl.stopped) Card(border = Ph.AmberLine) {
            Text("You pressed Stop", style = Ph.title.copy(color = Ph.Amber))
            Text("Agents can't use the phone until you resume.", style = Ph.body.copy(fontSize = 13.sp))
            Button("Resume", primary = true) { PhoneControl.stopped = false }
        }

        // ---- connect
        Label("CONNECT AN AGENT")
        Card {
            Text("Add this server to any MCP client on the phone (Saturn, pi, and others).", style = Ph.body.copy(fontSize = 13.sp))
            Mono("URL", McpServer.url)
            var show by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Mono("Token", if (show) McpServer.token else "•".repeat(16) + McpServer.token.takeLast(4)) }
                Text(if (show) "hide" else "show", style = Ph.monoSmall.copy(color = Teal), modifier = Modifier.clickable { show = !show }.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Copy config", primary = true) { copy("MCP config", McpServer.clientConfig()) }
                Button("New token") { McpServer.regenerateToken(); Toast.makeText(ctx, "New token: update your agents' config", Toast.LENGTH_LONG).show() }
            }
            Text("In Saturn or pi: put it in ~/.pi/agent/mcp.json (merge with any servers already there), then /reload.",
                style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
        }

        // ---- rules
        Label("HOW IT KEEPS YOU IN CONTROL")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Point("A Stop button stays on screen", "While an agent works, a pill floats at the top. Tap Stop and nothing more happens until you tap Resume here.")
            Point("It asks before anything big", "Sending, paying, deleting, installing and similar taps wait for your Allow.")
            Point("Hard limits", "Never types passwords, never works on the lock screen, never answers Android permission prompts, never controls this app.")
            Point("Only on your phone, only with the token", "The server listens on 127.0.0.1 and refuses requests without the token or from web pages.")
            Point("What leaves your phone", "This app sends nothing anywhere. What it reads goes to the agent that asked, and from there to that agent's AI provider.")
        }

        // ---- log
        if (PhoneControl.log.isNotEmpty()) {
            Label("RECENT ACTIONS")
            Card {
                val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
                PhoneControl.log.take(15).forEach { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(fmt.format(java.util.Date(e.time)), style = Ph.monoSmall)
                        Text(e.tool.removePrefix("phone_") + (if (e.detail.isNotBlank()) " · ${e.detail}" else ""),
                            style = Ph.mono.copy(color = if (e.ok) Ph.TextSoft else Ph.Red), maxLines = 2)
                    }
                }
            }
        }
        Text("Open source · github.com/gouravraghuwanshi/saturn-agent", style = Ph.monoSmall,
            modifier = Modifier.clickable { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/gouravraghuwanshi/saturn-agent"))) }.padding(vertical = 8.dp))
    }
}

@Composable
private fun Card(border: Color = Ph.Line, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ph.Surface).border(1.dp, border, RoundedCornerShape(16.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun Label(text: String) = Text(text, style = Ph.label, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Mono(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = Ph.label.copy(fontSize = 10.sp))
        Text(value, style = Ph.mono.copy(color = Ph.Text, fontSize = 12.5.sp))
    }
}

@Composable
private fun Button(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (primary) Teal else Color.Transparent)
        .border(1.dp, if (primary) Teal else Ph.LineStrong, RoundedCornerShape(12.dp)).clickable { onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 14.sp, color = if (primary) TealInk else Ph.Text))
    }
}

@Composable
private fun Point(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = Ph.title.copy(fontSize = 14.sp))
        Text(body, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.Muted))
    }
}
