package io.github.pimobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.store.Store
import io.github.pimobile.ui.components.Markdown
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.components.PulseDot
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun downloads(n: Long) = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM/wk", n / 1e6)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk/wk", n / 1e3)
    else -> "$n/wk"
}

@Composable
fun StoreScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Store.Filter.ALL) }
    val results = remember { mutableStateListOf<Store.Pkg>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(Store.installed(UiStore.runtime)) }
    var open by remember { mutableStateOf<Store.Pkg?>(null) }
    var endReached by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    suspend fun load(reset: Boolean) {
        if (filter == Store.Filter.INSTALLED) {
            results.clear(); endReached = true
            installed.filter { it.startsWith("npm:") }.map { it.removePrefix("npm:") }
                .filter { it.contains(query, ignoreCase = true) }.forEach {
                    results.add(Store.Pkg(it, "", "Installed", 0, emptyList(), ""))
                }
            return
        }
        loading = true; error = null
        val from = if (reset) 0 else results.size
        runCatching { withContext(Dispatchers.IO) { Store.search(query, filter, from) } }
            .onSuccess { page -> if (reset) results.clear(); results.addAll(page.filter { p -> results.none { it.name == p.name } }); endReached = page.size < 30 }
            .onFailure { error = "Couldn't reach the package registry (${it.message})" }
        loading = false
    }

    // Debounced search on query/filter changes.
    LaunchedEffect(query, filter) { delay(350); load(reset = true); list.scrollToItem(0) }
    // Infinite scroll.
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > results.size - 6 } }
    LaunchedEffect(nearEnd, results.size) { if (nearEnd && !loading && !endReached && results.isNotEmpty()) load(reset = false) }

    Column(Modifier.fillMaxSize().background(Ph.Ground).statusBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Back") { onBack() },
                contentAlignment = Alignment.Center) { PhIcon(PhIcons.Back, Ph.TextSoft, 22.dp, "Back") }
            Text("Extensions", style = Ph.heading, modifier = Modifier.weight(1f))
            Text("from pi.dev", style = Ph.monoSmall)
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(14.dp)).background(Ph.Surface)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("⌕", style = Ph.title.copy(color = Ph.Muted))
                BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = Ph.mono.copy(fontSize = 14.sp, color = Ph.Text), cursorBrush = SolidColor(Ph.Amber),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search packages", style = Ph.mono.copy(fontSize = 14.sp, color = Ph.Muted)); inner() } })
                if (loading) PulseDot(Ph.Amber, 7)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Store.Filter.entries.forEach { f ->
                    val on = f == filter
                    Text(f.label + if (f == Store.Filter.INSTALLED && installed.isNotEmpty()) " ${installed.size}" else "",
                        style = Ph.mono.copy(color = if (on) Ph.Ground else Ph.TextSoft, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ph.Text else Color.Transparent)
                            .border(1.dp, if (on) Ph.Text else Ph.LineStrong, RoundedCornerShape(10.dp))
                            .clickable { filter = f }.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }

        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { item { Text(it, style = Ph.body.copy(color = Ph.Red)) } }
            if (!loading && results.isEmpty() && error == null) item {
                Text(if (filter == Store.Filter.INSTALLED) "No packages installed yet." else "No packages found.",
                    style = Ph.body.copy(color = Ph.Muted))
            }
            items(results, key = { it.name }) { p ->
                PackageCard(p, installed = p.installSpec in installed, onOpen = { open = p })
            }
        }

        Row(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(Color(0xFF1A1610)).border(1.dp, Color(0xFF3A3222), RoundedCornerShape(14.dp))
            .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhIcon(PhIcons.Warning, Ph.Amber, 18.dp)
            Text("Packages run code with access to your projects. Review the source before you install.",
                style = Ph.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = Color(0xFFD9C9A8)))
        }
    }

    open?.let { p ->
        PackageSheet(p, isInstalled = p.installSpec in installed, onDismiss = { open = null },
            onChanged = {
                UiStore.commands = emptyList() // refetched from pi so new skills/commands show in the / palette
                scope.launch { installed = withContext(Dispatchers.IO) { Store.installed(UiStore.runtime) } }
            })
    }
}

@Composable
private fun PackageCard(p: Store.Pkg, installed: Boolean, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ph.Surface)
            .border(1.dp, Ph.Line, RoundedCornerShape(18.dp)).clickable(onClickLabel = "Open ${p.name}") { onOpen() }
            .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name, style = Ph.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ph.Text),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (installed) Text("installed", style = Ph.monoSmall.copy(color = Ph.Green),
                modifier = Modifier.border(1.dp, Color(0xFF2C4A38), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
            else if (p.desktopOnly) Text("desktop only", style = Ph.monoSmall.copy(color = Ph.Amber),
                modifier = Modifier.border(1.dp, Ph.AmberLine, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
        }
        if (p.description.isNotBlank()) Text(p.description, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Ph.TextBody),
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(p.kind, p.version.ifBlank { null }?.let { "v$it" },
                p.weeklyDownloads.takeIf { it > 0 }?.let { downloads(it) }).joinToString(" · "),
                style = Ph.monoSmall, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Ph.LineStrong, RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Copy install source") {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("pi package", p.installSpec))
                }, contentAlignment = Alignment.Center) { Text("⧉", style = Ph.title.copy(color = Ph.TextSoft)) }
            Text(if (installed) "Manage" else "Install",
                style = Ph.title.copy(fontSize = 13.sp, color = if (installed) Ph.Green else Ph.AmberInk),
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (installed) Color.Transparent else Ph.Amber)
                    .border(1.dp, if (installed) Color(0xFF2C4A38) else Ph.Amber, RoundedCornerShape(12.dp))
                    .clickable { onOpen() }.padding(horizontal = 16.dp, vertical = 11.dp))
        }
    }
}

/** Details + install/remove with live output. */
@Composable
private fun PackageSheet(p: Store.Pkg, isInstalled: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var details by remember { mutableStateOf<Store.Details?>(null) }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var installedNow by remember { mutableStateOf(isInstalled) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(p.name) {
        runCatching { withContext(Dispatchers.IO) { Store.details(p.name) } }
            .onSuccess { d -> details = d; d.imageUrl?.let { image = withContext(Dispatchers.IO) { Store.image(it) } } }
            .onFailure { failed = it.message }
    }

    fun run(action: String) {
        busy = true; log.clear(); confirm = false
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching { Store.run(UiStore.runtime, action, p.installSpec) { l -> scope.launch { log.add(l) } } }
                    .getOrElse { log.add("error: ${it.message}"); 1 }
            }
            busy = false
            if (code == 0) {
                installedNow = action == "install"
                log.add(if (installedNow) "✓ Installed. Tasks load it on their next message." else "✓ Removed.")
                onChanged()
            } else if (code == Store.REJECTED) {
                installedNow = false
                log.add("✗ Not installed — this package stops pi from starting on this phone, so Saturn removed it again.")
                onChanged()
            } else log.add("✗ pi $action failed (exit $code)")
        }
    }

    PhSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(p.name, style = Ph.title.copy(fontFamily = Ph.Mono, fontSize = 17.sp))
            Text(listOfNotNull(p.kind, p.version.ifBlank { null }?.let { "v$it" }, p.updated.ifBlank { null }?.let { "updated $it" },
                p.weeklyDownloads.takeIf { it > 0 }?.let { downloads(it) }).joinToString(" · "), style = Ph.monoSmall)
            image?.let {
                Image(it.asImageBitmap(), contentDescription = "${p.name} preview", contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(14.dp)))
            }
            val d = details
            if (d == null) Text(failed?.let { "Couldn't load details: $it" } ?: "Loading…", style = Ph.body.copy(color = Ph.Muted))
            else {
                if (d.contents.isNotEmpty()) Text("Contains: " + d.contents.joinToString(", "), style = Ph.mono.copy(color = Ph.TextSoft))
                // Mobile compatibility: flag dependencies that need desktop Chrome or native modules.
                if (d.mobileWarnings.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1610))
                        .border(1.dp, Color(0xFF3A3222), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("May not work on Android", style = Ph.title.copy(fontSize = 13.sp, color = Ph.Amber))
                    d.mobileWarnings.forEach { Text("• $it", style = Ph.monoSmall.copy(color = Color(0xFFD9C9A8))) }
                } else Text("✓ No desktop-only dependencies found", style = Ph.monoSmall.copy(color = Ph.Green))
                d.repository?.let { r -> Text("Source: $r", style = Ph.monoSmall.copy(color = Ph.Cyan),
                    modifier = Modifier.clickable { uri.openUri(r) }) }
            }

            // Actions
            if (confirm) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ph.Raised)
                    .border(1.dp, Ph.AmberLine, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Install ${p.name}?", style = Ph.title)
                Text("It can run code with access to your projects and files in this app. Only install packages you trust.",
                    style = Ph.body.copy(fontSize = 13.sp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetButton("Install", primary = true) { run("install") }
                    SheetButton("Cancel") { confirm = false }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (installedNow) SheetButton(if (busy) "Removing…" else "Remove", danger = true, enabled = !busy) { run("remove") }
                else SheetButton(if (busy) "Installing…" else "Install", primary = true, enabled = !busy) { confirm = true }
                SheetButton("pi.dev") { uri.openUri(p.pageUrl) }
            }
            if (log.isNotEmpty()) Text(log.takeLast(40).joinToString("\n"),
                style = Ph.mono.copy(fontSize = 11.5.sp, color = Ph.TextSoft),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ph.Deep).padding(12.dp))

            d?.readme?.takeIf { it.isNotBlank() }?.let {
                Text("README", style = Ph.label, modifier = Modifier.padding(top = 6.dp))
                Markdown(it.take(8000))
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Ph.Chip; primary -> Ph.Amber; else -> Color.Transparent }
    val fg = when { !enabled -> Ph.Muted; primary -> Ph.AmberInk; danger -> Ph.Red; else -> Ph.Text }
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(bg)
        .border(1.dp, if (primary && enabled) Ph.Amber else Ph.LineStrong, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled) { onClick() }.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(label, style = Ph.title.copy(fontSize = 14.sp, color = fg))
    }
}
