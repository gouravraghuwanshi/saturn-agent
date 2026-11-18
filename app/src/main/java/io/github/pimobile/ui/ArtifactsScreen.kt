package io.github.pimobile.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.github.pimobile.ui.components.Markdown
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
private val TEXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "js", "ts", "css", "xml", "yaml", "yml",
    "sh", "py", "kt", "java", "c", "h", "go", "rs", "log", "ini", "toml", "svg")

/** Accent per kind, so a grid of files reads at a glance. */
private fun kindColor(f: File) = when {
    f.isDirectory -> Ph.TextSoft
    f.extension.lowercase() in setOf("html", "htm") -> Ph.Cyan
    f.extension.lowercase() in IMAGE -> Ph.Green
    f.extension.lowercase() in setOf("md", "markdown", "txt") -> Ph.Amber
    else -> Ph.TextSoft
}

private fun list(dir: File): List<File> =
    (dir.listFiles()?.filterNot { it.name.startsWith(".") } ?: emptyList())
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() })

private fun size(f: File): String {
    if (f.isDirectory) return "${f.listFiles()?.size ?: 0} items"
    val b = f.length()
    return when { b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"; else -> String.format(Locale.US, "%.1f MB", b / 1048576.0) }
}

private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
    ?: if (f.extension.lowercase() in TEXT) "text/plain" else "application/octet-stream"

private fun uriOf(ctx: android.content.Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

/** Files Saturn made (in $SATURN_ARTIFACTS). Tap to preview; share or open them elsewhere. */
@Composable
fun ArtifactsScreen(onBack: () -> Unit) {
    val root = UiStore.runtime.artifacts
    var path by rememberSaveable { mutableStateOf(root.path) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val dir = File(path)
    BackHandler { if (open != null) open = null else if (dir != root) path = dir.parent ?: root.path else onBack() }

    // Saturn may be writing files right now: keep the grid fresh while it's on screen.
    val files by produceState(emptyList<File>(), path) {
        while (true) {
            val next = withContext(Dispatchers.IO) { root.mkdirs(); list(File(path)) }
            if (next.map { it.path to it.lastModified() } != value.map { it.path to it.lastModified() }) value = next
            delay(1500)
        }
    }

    open?.let { p -> ArtifactViewer(File(p), onClose = { open = null }); return }

    Column(Modifier.fillMaxSize().background(Ph.Ground).statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Back") { if (dir != root) path = dir.parent ?: root.path else onBack() },
                contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(if (dir == root) "Artifacts" else dir.name, style = Ph.title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (dir == root) "${files.size} ${if (files.size == 1) "item" else "items"} · made by Saturn" else "artifacts/" + dir.relativeTo(root).path,
                    style = Ph.monoSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (files.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                PhIcon(PhIcons.Sparkle, Ph.LineStrong, 64.dp)
                Spacer(Modifier.height(16.dp))
                Text("Nothing here yet", style = Ph.title.copy(fontSize = 18.sp))
                Spacer(Modifier.height(8.dp))
                Text("Ask Saturn to make something — a web page, a report, a chart, a CSV. It lands here, ready to preview and share.",
                    style = Ph.body.copy(color = Ph.Muted, fontSize = 13.sp), textAlign = TextAlign.Center)
                Spacer(Modifier.height(120.dp))
            }
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(files, key = { it.path }) { f ->
                ArtifactCard(f) { if (f.isDirectory) path = f.path else open = f.path }
            }
        }
    }
}

@Composable
private fun ArtifactCard(f: File, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val ext = f.extension.lowercase()
    Column(Modifier.clip(shape).background(Ph.Surface).border(1.dp, Ph.Line, shape).clickable(onClickLabel = "Open ${f.name}") { onClick() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f).background(Ph.Deep), contentAlignment = Alignment.Center) {
            if (ext in IMAGE) {
                val bmp by produceState<android.graphics.Bitmap?>(null, f.path, f.lastModified()) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(f.path, o)
                            o.inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / 400)
                            o.inJustDecodeBounds = false
                            BitmapFactory.decodeFile(f.path, o)
                        }.getOrNull()
                    }
                }
                bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            } else if (f.isDirectory) PhIcon(PhIcons.Folder, Ph.TextSoft, 34.dp)
            else if (ext in TEXT && ext != "svg") {
                val head by produceState("", f.path, f.lastModified()) {
                    value = withContext(Dispatchers.IO) { runCatching { f.bufferedReader().use { r -> CharArray(400).let { String(it, 0, maxOf(0, r.read(it))) } } }.getOrDefault("") }
                }
                Text(head, style = Ph.mono.copy(fontSize = 8.sp, lineHeight = 11.sp, color = Ph.Muted), overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 34.dp))
            } else Text(ext.uppercase().ifBlank { "FILE" }.take(5), style = Ph.mono.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = kindColor(f)))
            // kind tag
            if (!f.isDirectory) Text(ext.uppercase().ifBlank { "FILE" }.take(5),
                style = Ph.monoSmall.copy(fontSize = 9.sp, color = kindColor(f)),
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                    .background(Ph.Surface.copy(alpha = 0.9f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(f.name, style = Ph.body.copy(color = Ph.Text, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${size(f)} · ${SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(f.lastModified()))}", style = Ph.monoSmall.copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

@Composable
private fun ArtifactViewer(f: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ext = f.extension.lowercase()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { delay(3000); confirmDelete = false } }
    fun send(action: String) {
        val uri = uriOf(ctx, f)
        val i = Intent(action).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (action == Intent.ACTION_SEND) i.setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri) else i.setDataAndType(uri, mime(f))
        runCatching { ctx.startActivity(Intent.createChooser(i, if (action == Intent.ACTION_SEND) "Share ${f.name}" else "Open ${f.name} with")) }
    }

    Column(Modifier.fillMaxSize().background(Ph.Ground).statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Close") { onClose() },
                contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Close") }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(f.name, style = Ph.title.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(size(f), style = Ph.monoSmall)
            }
            IconBox(PhIcons.Share, "Share") { send(Intent.ACTION_SEND) }
            Spacer(Modifier.size(6.dp))
            IconBox(PhIcons.External, "Open with") { send(Intent.ACTION_VIEW) }
            Spacer(Modifier.size(6.dp))
            Box(Modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
                .background(if (confirmDelete) Ph.RedWash else Color.Transparent)
                .border(1.dp, if (confirmDelete) Color(0xFF3A2A28) else Ph.Line, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Delete") { if (confirmDelete) { f.delete(); onClose() } else confirmDelete = true }
                .padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon(PhIcons.Trash, if (confirmDelete) Ph.Red else Ph.TextSoft, 20.dp, "Delete")
                    if (confirmDelete) Text("Delete?", style = Ph.mono.copy(color = Ph.Red))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            when {
                ext == "html" || ext == "htm" || ext == "svg" -> AndroidView({ c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true // its own folder's images/css; no XHR to other files
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.WHITE)
                        loadUrl(android.net.Uri.fromFile(f).toString())
                    }
                }, Modifier.fillMaxSize())
                ext in IMAGE -> {
                    val bmp = remember(f.path) { runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
                    if (bmp != null) Image(bmp.asImageBitmap(), f.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(12.dp))
                }
                ext in TEXT || f.length() < 200_000 && runCatching { f.readBytes().none { it == 0.toByte() } }.getOrDefault(false) -> {
                    val text = remember(f.path, f.lastModified()) { runCatching { f.readText().take(300_000) }.getOrDefault("") }
                    if (ext == "md" || ext == "markdown") Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
                        SelectionContainer { Markdown(text) }
                    } else SelectionContainer {
                        Text(text, style = Ph.mono.copy(fontSize = 12.sp, lineHeight = 18.sp, color = Ph.TextBody),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp))
                    }
                }
                else -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ext.uppercase().ifBlank { "FILE" }, style = Ph.mono.copy(fontSize = 28.sp, color = Ph.TextSoft))
                    Spacer(Modifier.height(10.dp))
                    Text("No preview for this kind of file.", style = Ph.body.copy(color = Ph.Muted))
                    Spacer(Modifier.height(18.dp))
                    Text("Open with…", style = Ph.title.copy(fontSize = 14.sp, color = Ph.AmberInk),
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Amber).clickable { send(Intent.ACTION_VIEW) }
                            .padding(horizontal = 18.dp, vertical = 12.dp))
                }
            }
        }
    }
}
