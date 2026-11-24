package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.Computers
import io.github.pimobile.runtime.GitHub
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.theme.Ph

// ------------------------------------------------------------------ GitHub


@Composable
internal fun GitHubSheet(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val copy = copier()
    var armed by remember { mutableStateOf(false) }
    PhSheet({ if (GitHub.signingIn) GitHub.cancel(); onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("GitHub", style = Ph.heading)
            when {
                GitHub.user != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhIcon(PhIcons.Check, Ph.Green, 18.dp); Text("Signed in as ${GitHub.user}", style = Ph.title.copy(color = Ph.Green))
                    }
                    Text("Ask Saturn to clone a repo, commit and push, or open a pull request. You can also clone from the project picker.",
                        style = Ph.body.copy(fontSize = 13.sp))
                    Pill(if (armed) "Tap again to sign out" else "Sign out", danger = true) {
                        if (armed) { GitHub.signOut(); armed = false } else armed = true
                    }
                }
                GitHub.signingIn -> {
                    Text("Enter this code on GitHub", style = Ph.title)
                    Text(GitHub.code ?: "…", style = Ph.mono.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ph.Amber, letterSpacing = 3.sp),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = GitHub.code != null) { GitHub.code?.let(copy) }.padding(6.dp))
                    Text("Tap the code to copy it, then paste it on GitHub. Saturn signs in by itself when you approve.",
                        style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted))
                    Pill("Copy code & open GitHub", primary = true, enabled = GitHub.code != null) {
                        GitHub.code?.let(copy); runCatching { uri.openUri(GitHub.url ?: "https://github.com/login/device") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        io.github.pimobile.ui.components.PulseDot(Ph.Amber); Text("Waiting for you to approve on github.com…", style = Ph.body.copy(color = Ph.Muted))
                    }
                    Pill("Cancel") { GitHub.cancel() }
                }
                else -> {
                    Text("Connect your GitHub account so Saturn can work on your repos: clone, commit, push, pull requests and issues.",
                        style = Ph.body)
                    Text("Uses GitHub's own sign-in for its CLI: you get a short code to enter on github.com. The login stays on this phone.",
                        style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted))
                    GitHub.error?.let { Text(it, style = Ph.mono.copy(color = Ph.Red)) }
                    Pill("Sign in with GitHub", primary = true) { GitHub.signIn() }
                }
            }
            Box(Modifier.size(8.dp))
        }
    }
}

// --------------------------------------------------------------- computers


@Composable
internal fun ComputersSheet(onDismiss: () -> Unit) {
    val copy = copier()
    LaunchedEffect(Unit) { Computers.ensureKey() }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var error by remember { mutableStateOf<String?>(null) }
    var armed by remember { mutableStateOf<String?>(null) }
    PhSheet(onDismiss) {
        // Fixed height: the key, test results and keyboard change the content, and a sheet that
        // re-measures to fit its content while scrolling bounces up and down.
        Column(Modifier.fillMaxHeight(0.92f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your computers", style = Ph.heading)
            Text("Saturn can work on your laptop or a server over SSH: you chat on the phone, the commands run there. " +
                "The computer needs SSH turned on and must be reachable (same Wi-Fi, or a VPN like Tailscale).",
                style = Ph.body.copy(fontSize = 13.sp))

            Text("1 · Give the computer Saturn's key", style = Ph.title.copy(fontSize = 14.sp))
            val key = Computers.publicKey
            Text(key ?: "Making a key…", style = Ph.mono.copy(fontSize = 11.sp, color = Ph.TextSoft), maxLines = 3,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ph.Deep).padding(10.dp))
            Text("On the computer, run this once (it adds the key to ~/.ssh/authorized_keys):", style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Muted))
            val cmd = key?.let { "mkdir -p ~/.ssh && echo '$it' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys" }
            Pill("Copy the command", enabled = cmd != null) { cmd?.let(copy) }

            Text("2 · Add it here", style = Ph.title.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
            Field(name, { name = it; error = null }, "name, e.g. laptop")
            Field(target, { target = it.trim(); error = null }, "user@address, e.g. me@192.168.1.10")
            Field(port, { port = it.filter(Char::isDigit).take(5) }, "port (22)")
            error?.let { Text(it, style = Ph.monoSmall.copy(color = Ph.Red)) }
            Pill("Add computer", primary = true, enabled = name.isNotBlank() && target.isNotBlank()) {
                error = Computers.add(name, target, port.toIntOrNull() ?: 22)
                if (error == null) { Computers.test(Computers.hosts.last().alias); name = ""; target = ""; port = "22" }
            }

            if (Computers.hosts.isNotEmpty()) Text("3 · Then just ask, e.g. “on laptop, run the tests in ~/code/app”", style = Ph.title.copy(fontSize = 14.sp),
                modifier = Modifier.padding(top = 6.dp))
            Computers.hosts.forEach { h ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.Line, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.alias, style = Ph.title.copy(fontSize = 15.sp))
                            Text("${h.user}@${h.host}" + if (h.port != 22) ":${h.port}" else "", style = Ph.monoSmall)
                        }
                        Text(if (Computers.testing == h.alias) "testing…" else "test", style = Ph.mono.copy(color = Ph.Cyan),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Computers.test(h.alias) }.padding(8.dp))
                        Text(if (armed == h.alias) "remove?" else "remove", style = Ph.mono.copy(color = Ph.Red),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                if (armed == h.alias) { Computers.remove(h.alias); armed = null } else armed = h.alias
                            }.padding(8.dp))
                    }
                    Computers.results[h.alias]?.let {
                        Text(it, style = Ph.monoSmall.copy(color = if (it.startsWith("✓")) Ph.Green else Ph.Red))
                    }
                }
            }
            Box(Modifier.size(8.dp))
        }
    }
}

// ----------------------------------------------------------------- shared

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String,
                       subColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhIcon(icon, Ph.TextSoft, 18.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = Ph.title.copy(fontSize = 14.sp))
            Text(sub, style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = subColor))
        }
        PhIcon(PhIcons.Chevron, Ph.Muted, 16.dp)
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp))
        .background(if (primary && enabled) Ph.Amber else androidx.compose.ui.graphics.Color.Transparent)
        .border(1.dp, if (primary) androidx.compose.ui.graphics.Color.Transparent else Ph.LineStrong, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 14.sp, color = when { !enabled -> Ph.Muted; primary -> Ph.AmberInk; danger -> Ph.Red; else -> Ph.Text }))
    }
}
