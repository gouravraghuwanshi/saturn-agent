package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.device.Voice
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.buzz
import io.github.pimobile.ui.theme.Ph
import kotlin.concurrent.thread

private const val SAMPLE = "Hi, I'm Saturn. I can read my answers to you, out loud."

/** Settings → Voice: how fast Saturn reads, and a way into Android's own voice settings. */
@Composable
fun VoiceSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    Voice.init(ctx)
    fun sample() { Voice.stop(); thread(name = "voice-sample") { runCatching { Voice.speakBlocking(SAMPLE) } } }
    PhSheet({ Voice.stop(); onDismiss() }) {
        SheetTitle("Voice", "Saturn reads replies with your phone's own voice. It works offline and barely uses the battery.")
        Text("SPEED", style = Ph.label, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.8f to "0.8x", 0.9f to "0.9x", 1f to "1x", 1.15f to "1.15x", 1.3f to "1.3x").forEach { (s, label) ->
                val on = kotlin.math.abs(Voice.speed - s) < 0.01f
                Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Ph.Amber else Ph.Chip)
                    .clickable { view.buzz(android.view.HapticFeedbackConstants.SEGMENT_TICK); Voice.pickSpeed(s); sample() },
                    contentAlignment = Alignment.Center) {
                    Text(label, style = Ph.monoSmall.copy(fontSize = 12.sp, color = if (on) Ph.AmberInk else Ph.TextSoft))
                }
            }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().clickable { Voice.openSystemSettings(ctx) }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhIcon(PhIcons.Speaker, Ph.Amber, 18.dp)
            Text("Change the voice or language", style = Ph.body.copy(fontSize = 14.sp, color = Ph.Text), modifier = Modifier.weight(1f))
            PhIcon(PhIcons.External, Ph.Muted, 14.dp)
        }
        Text("Opens Android's text-to-speech settings (Google or Samsung engine).",
            style = Ph.body.copy(fontSize = 12.5.sp, color = Ph.Muted), modifier = Modifier.padding(horizontal = 20.dp))
        Row(Modifier.padding(start = 12.dp, top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { sample() }.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Text("Play a sample", style = Ph.monoSmall.copy(color = Ph.Amber))
        }
    }
}
