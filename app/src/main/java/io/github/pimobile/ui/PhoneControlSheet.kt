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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.phone.PhoneControl
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.delay

/** Explains phone control honestly, then walks the user through Android's Accessibility switch. */
@Composable
fun PhoneControlSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PhoneControl.enabled) }
    var service by remember { mutableStateOf(PhoneControl.serviceOn) }
    LaunchedEffect(Unit) { while (true) { delay(800); service = PhoneControl.serviceOn } }

    fun openAccessibility() = runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openAppInfo() = runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", ctx.packageName, null)))
    }

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetTitle("Let Saturn use your apps", "Ask things like “open Maps and find a café nearby” — Saturn reads the screen and taps, types and swipes for you.")
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Point(PhIcons.Stop, "You stay in control", "While Saturn works, a Stop pill floats on screen. Tap it and Saturn stops until you message it again.")
                Point(PhIcons.Warning, "It asks before anything big", "Sending, paying, deleting, installing and similar taps wait for your Allow.")
                Point(PhIcons.Lock, "Hard limits", "Never types passwords, never works on the lock screen, never answers Android permission prompts, never controls Saturn itself.")
                Point(PhIcons.External, "What leaves your phone", "When Saturn looks at the screen, its text (and any screenshot it takes) goes to the model provider you chose. It only looks while working on your request.")
            }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    on && service -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhIcon(PhIcons.Check, Ph.Green, 18.dp); Text("Phone control is on", style = Ph.title.copy(fontSize = 14.sp, color = Ph.Green))
                        }
                        Ghost("Turn off") { PhoneControl.enabled = false; on = false }
                        Text("To remove Android's access too, turn off “Saturn: use your apps” in Accessibility.", style = Ph.monoSmall)
                        Ghost("Open Accessibility settings") { openAccessibility() }
                    }
                    on -> {
                        Text("One more step", style = Ph.title.copy(fontSize = 14.sp))
                        Text("In Accessibility, open Installed apps → “Saturn: use your apps” and turn it on. Then come back here.",
                            style = Ph.body.copy(fontSize = 13.sp, color = Ph.TextSoft))
                        PrimaryButton("Open Accessibility settings") { openAccessibility() }
                        Text("Switch greyed out (“Restricted setting”)? Android does that for apps installed from a file. Open App info → ⋮ (top right) → Allow restricted settings, then try again.",
                            style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
                        Ghost("Open App info") { openAppInfo() }
                        Ghost("Cancel") { PhoneControl.enabled = false; on = false }
                    }
                    else -> PrimaryButton("Turn on") {
                        PhoneControl.enabled = true; on = true
                        if (!service) openAccessibility()
                    }
                }
            }
        }
    }
}

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) { PhIcon(icon, Ph.TextSoft, 17.dp) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Ph.title.copy(fontSize = 14.sp))
            Text(body, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Ph.Muted))
        }
    }
}

@Composable
private fun Ghost(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp))
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 14.sp))
    }
}
