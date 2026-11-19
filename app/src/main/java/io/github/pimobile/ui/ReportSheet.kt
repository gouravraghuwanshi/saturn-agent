package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import org.json.JSONObject
import java.io.File

private val REASONS = listOf("Offensive or hateful", "Sexual content", "Dangerous or harmful", "Harassment", "Wrong or misleading", "Something else")

/**
 * Report an AI reply. Saturn has no server: the report is kept on the phone
 * (~/.saturn/reports.jsonl) and, if the user wants, opened as a pre-filled
 * GitHub issue they can review before sending.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReportSheet(t: TaskUi, reply: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    PhSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Report this reply", style = Ph.heading)
            if (done) {
                Text("Thanks. The report is saved on your phone.", style = Ph.body)
                Text("Replies come from the AI model you picked (${UiStore.model ?: "your model"}). To reach its makers, report it to that provider too.",
                    style = Ph.body.copy(fontSize = 13.sp, color = Ph.Muted))
                Pill("Done", primary = true) { onDismiss() }
                return@Column
            }
            Text(reply.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(140), style = Ph.mono.copy(color = Ph.TextSoft), maxLines = 3,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ph.Deep).padding(12.dp))
            Text("What's wrong with it?", style = Ph.title.copy(fontSize = 14.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                REASONS.forEach { r ->
                    val on = reason == r
                    Text(r, style = Ph.body.copy(fontSize = 13.sp, color = if (on) Ph.AmberInk else Ph.TextSoft),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ph.Amber else Ph.Chip)
                            .clickable { reason = r }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            BasicTextField(note, { note = it }, textStyle = Ph.body.copy(color = Ph.Text), cursorBrush = SolidColor(Ph.Amber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Ground)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp)).padding(12.dp),
                decorationBox = { inner -> if (note.isEmpty()) Text("Anything to add? (optional)", style = Ph.body.copy(color = Ph.Muted)); inner() })
            fun save() = runCatching {
                val f = File(UiStore.runtime.home, ".saturn/reports.jsonl").apply { parentFile?.mkdirs() }
                f.appendText(JSONObject().put("time", System.currentTimeMillis()).put("reason", reason).put("note", note)
                    .put("model", UiStore.model).put("task", t.task.id).put("reply", reply.take(4000)).toString() + "\n")
            }
            Pill("Report", primary = true, enabled = reason != null) { save(); done = true }
            Pill("Report and send to the developer", enabled = reason != null) {
                save()
                val body = "**Reason:** $reason\n**Model:** ${UiStore.model ?: "?"}\n\n${note.ifBlank { "" }}\n\n**Reply:**\n```\n${reply.take(1500)}\n```"
                runCatching { uri.openUri("https://github.com/gouravraghuwanshi/saturn-agent/issues/new?labels=report&title=" +
                    android.net.Uri.encode("Report: $reason") + "&body=" + android.net.Uri.encode(body)) }
                done = true
            }
            Text("Sending opens a pre-filled GitHub issue in your browser. You can edit or cancel it there.",
                style = Ph.monoSmall)
        }
    }
}

@Composable
private fun Pill(label: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(label, style = Ph.title.copy(fontSize = 14.sp, color = when { !enabled -> Ph.Muted; primary -> Ph.AmberInk; else -> Ph.Text }),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) Ph.Amber else if (primary) Ph.Chip else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (primary) androidx.compose.ui.graphics.Color.Transparent else Ph.LineStrong, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }.padding(vertical = 13.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}
