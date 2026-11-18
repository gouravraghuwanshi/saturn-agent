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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.Scheduler
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.delay

/** Timers and cron jobs the agent (or the user) scheduled with `saturn-schedule`. */
@Composable
fun ScheduleSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var exact by remember { mutableStateOf(Scheduler.exact) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis(); exact = Scheduler.exact } }
    var confirm by remember { mutableStateOf<String?>(null) }
    val jobs = Scheduler.jobs

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SheetTitle("Scheduled", "Timers and repeating jobs. They run on time even when Saturn is closed.")
            if (!exact) Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
                .clickable {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.fromParts("package", ctx.packageName, null))) }
                }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhIcon(PhIcons.Clock, Ph.Amber, 18.dp)
                Column(Modifier.weight(1f)) {
                    Text("Allow exact timing", style = Ph.title.copy(fontSize = 14.sp))
                    Text("Without it Android may run jobs a few minutes late. Tap, then turn on Alarms & reminders.",
                        style = Ph.body.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Ph.Muted))
                }
            }
            if (jobs.isEmpty()) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Nothing scheduled", style = Ph.title.copy(fontSize = 15.sp))
                Text("Ask Saturn things like “turn off the torch in 10 minutes” or “every weekday at 9, summarise the news”.",
                    style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted))
            }
            jobs.forEach { j ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center) {
                            PhIcon(if (j.prompt != null) PhIcons.Sparkle else PhIcons.Terminal, if (j.prompt != null) Ph.Amber else Ph.TextSoft, 17.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(j.name, style = Ph.body.copy(color = Ph.Text, fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenLabel(j.next, now) + (j.cron?.let { " · repeats ($it)" } ?: ""), style = Ph.monoSmall.copy(color = Ph.Amber))
                        }
                    }
                    Text(j.prompt?.let { "new task: $it" } ?: "$ ${j.command}", style = Ph.mono.copy(fontSize = 12.sp, color = Ph.TextSoft),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ph.Deep).padding(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Run now", Ph.TextSoft) { Scheduler.fire(j.id) }
                        val armed = confirm == j.id
                        Chip(if (armed) "Tap again to cancel" else "Cancel job", Ph.Red) {
                            if (armed) { Scheduler.remove(j.id); confirm = null } else confirm = j.id
                        }
                    }
                }
            }
            Box(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun Chip(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(10.dp))
        .clickable { onClick() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Ph.body.copy(fontSize = 13.sp, color = color))
    }
}

private fun whenLabel(at: Long, now: Long): String {
    val s = ((at - now) / 1000).coerceAtLeast(0)
    val rel = when {
        s < 60 -> "in ${s}s"
        s < 3600 -> "in ${s / 60}m ${s % 60}s"
        s < 86_400 -> "in ${s / 3600}h ${s % 3600 / 60}m"
        else -> "in ${s / 86_400}d"
    }
    val clock = java.text.SimpleDateFormat(if (s < 86_400) "HH:mm" else "EEE d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    return "$rel · $clock"
}
