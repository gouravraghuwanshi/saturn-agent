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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.device.Device
import io.github.pimobile.runtime.Computers
import io.github.pimobile.runtime.Diagnostics
import io.github.pimobile.runtime.GitHub
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.Control
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: grouped cards of one-line rows. Anything that needs explaining
 * opens its own screen from the row instead of crowding the list.
 */
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { GitHub.refresh() }
    val version = remember { runCatching { UiStore.app.packageManager.getPackageInfo(UiStore.app.packageName, 0).versionName }.getOrNull() ?: "" }

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text("Settings", style = Ph.heading.copy(fontSize = 26.sp), modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp))

            Group("AI") {
                val signedIn = Control.signedIn.sorted()
                Item(PhIcons.Key, "Accounts & API keys",
                    signedIn.joinToString(" · ").ifEmpty { "Not connected yet" },
                    tint = Ph.Amber, subColor = if (signedIn.isEmpty()) Ph.Amber else Ph.TextBody) { open = "login" }
                Divider()
                Item(PhIcons.Sparkle, "Default model", UiStore.model?.substringAfter('/') ?: "Chosen automatically", tint = Ph.Amber) { open = "model" }
            }

            Group("Connections") {
                Item(PhIcons.Branch, "GitHub", GitHub.user?.let { "Signed in as $it" } ?: "Not connected",
                    subColor = if (GitHub.user != null) Ph.Green else Ph.TextBody) { open = "github" }
                Divider()
                Item(PhIcons.Terminal, "Your computers",
                    Computers.hosts.joinToString(" · ") { it.alias }.ifEmpty { "Work on a laptop or server over SSH" }) { open = "computers" }
            }

            Group("Phone") {
                var features by remember { mutableStateOf(Device.enabled) }
                Toggle(PhIcons.Sparkle, "Phone features", "Torch, camera, location…", features) {
                    features = it; Device.enabled = it
                }
                if (features) { Divider(); Item(PhIcons.Lock, "Permissions", "What Saturn may use") { open = "permissions" } }
            }

            Group("Appearance") {
                Toggle(PhIcons.Sparkle, "Pure black", "True black, saves battery", Ph.amoled) { UiStore.setAmoled(it) }
            }

            Group("Advanced") {
                var runScripts by remember { mutableStateOf(UiStore.runtime.runScripts) }
                Toggle(PhIcons.Terminal, "Run scripts directly", "For scripts and npx tools", runScripts) {
                    runScripts = it; UiStore.runtime.runScripts = it
                    io.github.pimobile.runtime.TaskManager.restartIdle(); Control.restart()
                }
                Divider()
                Item(PhIcons.Check, "Check Saturn's tools", "node, git, curl, gh, ssh…") { open = "diagnostics" }
            }

            Group("About") {
                Item(PhIcons.Lock, "Privacy", "What stays on your phone") { open = "privacy" }
                Divider()
                Item(PhIcons.Info, "About Saturn", "Version $version · open source") { open = "about" }
            }
        }
    }

    when (open) {
        "login" -> LoginSheet { open = null }
        "model" -> ModelSheet(null) { open = null }
        "github" -> GitHubSheet { open = null }
        "computers" -> ComputersSheet { open = null }
        "permissions" -> PermissionsSheet { open = null }
        "diagnostics" -> DiagnosticsSheet { open = null }
        "privacy" -> PhSheet({ open = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SheetTitle("Privacy"); Privacy(); Box(Modifier.size(12.dp)) }
        }
        "about" -> PhSheet({ open = null }) {
            Column(Modifier.verticalScroll(rememberScrollState())) { SheetTitle("About"); About() }
        }
    }
}

// ------------------------------------------------------------- sub-screens

@Composable
private fun PermissionsSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } } // reflect grants made in Android's dialog
    val perms = buildList {
        add(Triple(PhIcons.Camera, "Camera", android.Manifest.permission.CAMERA))
        add(Triple(PhIcons.Mic, "Microphone", android.Manifest.permission.RECORD_AUDIO))
        add(Triple(PhIcons.External, "Location", android.Manifest.permission.ACCESS_FINE_LOCATION))
        if (!io.github.pimobile.BuildConfig.PLAY) add(Triple(PhIcons.Folder, "Contacts", android.Manifest.permission.READ_CONTACTS))
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Triple(PhIcons.Info, "Notifications", android.Manifest.permission.POST_NOTIFICATIONS))
    }
    PhSheet(onDismiss) {
        SheetTitle("Permissions", "Android asks you the first time Saturn needs each one. Tap to ask now, or to change it.")
        Group(null) {
            perms.forEachIndexed { i, (icon, label, perm) ->
                if (i > 0) Divider()
                val granted = remember(tick) { Device.granted(perm) ||
                    perm == android.Manifest.permission.ACCESS_FINE_LOCATION && Device.granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                Item(icon, label, null, trailing = {
                    Text(if (granted) "Allowed" else "Ask first", style = Ph.monoSmall.copy(color = if (granted) Ph.Green else Ph.Muted))
                }) {
                    // After two "Don't allow"s Android stops asking; the app's settings page always works.
                    if (granted) ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)))
                    else io.github.pimobile.device.Permissions.request(perm)
                }
            }
        }
        Text("Calls and texts only open the dialer or Messages filled in. You press call or send.",
            style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Muted), modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
    }
}

@Composable
private fun DiagnosticsSheet(onDismiss: () -> Unit) {
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    fun run() {
        lines.clear(); running = true; result = null
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { Diagnostics.run(UiStore.runtime) { l -> scope.launch { lines.add(l) } } }.getOrElse { lines.add("error: $it"); 1 }
            }
            running = false; result = code == 0
        }
    }
    LaunchedEffect(Unit) { run() }
    PhSheet(onDismiss) {
        SheetTitle("Saturn's tools", when (result) { true -> "Everything works."; false -> "Something failed. Details below."; null -> "Checking…" })
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(lines.joinToString("\n").ifEmpty { "…" }, style = Ph.mono.copy(color = Ph.TextSoft, fontSize = 11.5.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Deep).padding(14.dp))
            if (!running) PrimaryButton("Run again") { run() }
        }
    }
}

// ------------------------------------------------------------ components

@Composable
private fun Group(title: String?, content: @Composable () -> Unit) {
    if (title != null) Text(title.uppercase(), style = Ph.label, modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 8.dp))
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ph.Raised)
        .border(1.dp, Ph.Line, RoundedCornerShape(18.dp))) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 66.dp), thickness = 1.dp, color = Ph.Line)

@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Chip), contentAlignment = Alignment.Center) {
        PhIcon(icon, tint, 18.dp)
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, sub: String?, tint: Color = Ph.TextSoft, subColor: Color = Ph.TextBody,
                 trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconTile(icon, tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = Ph.title.copy(fontSize = 15.sp))
            if (sub != null) Text(sub, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp, color = subColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else PhIcon(PhIcons.Chevron, Ph.Muted, 16.dp, modifier = Modifier.graphicsLayer { rotationZ = -90f })
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Item(icon, title, sub, trailing = {
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = Ph.Amber, checkedThumbColor = Ph.AmberInk,
            uncheckedTrackColor = Ph.Chip, uncheckedThumbColor = Ph.Muted, uncheckedBorderColor = Ph.LineStrong))
    }) { onChange(!checked) }
}
