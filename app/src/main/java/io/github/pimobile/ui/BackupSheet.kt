package io.github.pimobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.runtime.Backup
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Settings → Backup & restore: everything in one file, and back again (here or on a new phone). */
@Composable
fun BackupSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf(true) }
    var signIns by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var made by remember { mutableStateOf<Backup.Made?>(null) }
    var restored by remember { mutableStateOf<Backup.Restored?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = TaskManager.tasks.any { it.status == TaskManager.Status.RUNNING || it.status == TaskManager.Status.STARTING || it.status == TaskManager.Status.NEEDS_INPUT }

    val save = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        error = null; made = null; restored = null; busy = "Packing…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Backup.create(ctx, uri, Backup.Options(projects, signIns)) { n -> busy = "Packing… $n files" } }
            }
            busy = null
            r.onSuccess { made = it }.onFailure { error = "Backup failed: ${it.message}" }
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        error = null; made = null; restored = null; busy = "Restoring…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Backup.restore(ctx, uri) { n -> busy = "Restoring… $n files" } } }
            busy = null
            r.onSuccess { restored = it }.onFailure { error = it.message ?: "Restore failed" }
        }
    }

    PhSheet({ if (busy == null) onDismiss() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Backup & restore", style = Ph.heading.copy(fontSize = 24.sp))
            Text("One file with your chats, routines and settings. Keep it in Drive or on your computer, and restore it here or on a new phone. " +
                "Restoring adds to what's here; it never wipes anything.", style = Ph.body.copy(fontSize = 13.5.sp, lineHeight = 20.sp, color = Ph.TextSoft))

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ph.Raised).border(1.dp, Ph.Line, RoundedCornerShape(16.dp))) {
                Included("Chats, routines, settings", "always", null)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ph.Line))
                Included("Project files", "skips node_modules, build folders and caches", projects) { projects = it }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ph.Line))
                Included("Sign-ins and keys", if (signIns) "anyone with the file could use them, so keep it private" else "off: sign in again after restoring",
                    signIns, warn = signIns) { signIns = it }
            }

            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
            Big("Back up now", primary = true, enabled = busy == null) { save.launch("saturn-backup-$stamp.zip") }
            Big(if (running) "Restore (stop running tasks first)" else "Restore from a backup", primary = false, enabled = busy == null && !running) {
                open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }

            busy?.let { Text(it, style = Ph.mono.copy(color = Ph.Amber), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            error?.let { Note(PhIcons.Warning, Ph.Red, it, null) }
            made?.let { m ->
                Note(PhIcons.Check, Ph.Green, "Saved · ${m.chats} chats · ${m.files} files · ${size(m.bytes)}",
                    if (signIns) "This file holds your sign-ins: share it with no one." else null)
            }
            restored?.let { r ->
                Note(PhIcons.Check, Ph.Green, "Restored ${r.chats} new chats, ${r.routines} routines, ${r.files} files",
                    "Saturn restarts to load them.")
                Big("Restart Saturn", primary = true, enabled = true) { Backup.restart(ctx) }
            }
        }
    }
}

@Composable
private fun Included(title: String, sub: String, on: Boolean?, warn: Boolean = false, onChange: (Boolean) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().then(if (on != null) Modifier.clickable { onChange(!on) } else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val lit = on != false
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (lit) Ph.Amber else Color.Transparent)
            .border(1.5.dp, if (lit) Ph.Amber else Ph.LineStrong, CircleShape), contentAlignment = Alignment.Center) {
            if (lit) PhIcon(PhIcons.Check, Ph.AmberInk, 13.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Ph.title.copy(fontSize = 14.5.sp))
            Text(sub, style = Ph.monoSmall.copy(color = if (warn) Ph.Amber else Ph.Muted))
        }
    }
}

@Composable
private fun Big(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(15.dp))
        .background(if (primary && enabled) Ph.Amber else Ph.Surface)
        .border(1.dp, if (primary && enabled) Ph.Amber else Ph.LineStrong, RoundedCornerShape(15.dp))
        .clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 15.sp, color = when { !enabled -> Ph.Muted; primary -> Ph.AmberInk; else -> Ph.Text }))
    }
}

@Composable
private fun Note(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, sub: String?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Surface).border(1.dp, Ph.Line, RoundedCornerShape(14.dp))
        .padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PhIcon(icon, tint, 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Ph.title.copy(fontSize = 14.sp))
            sub?.let { Text(it, style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp, color = Ph.Muted)) }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.US, "%.1f MB", b / 1e6)
    else -> String.format(Locale.US, "%d KB", (b / 1000).coerceAtLeast(1))
}
