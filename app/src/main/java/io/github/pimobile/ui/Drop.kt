package io.github.pimobile.ui

import android.content.ClipData
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph

/**
 * Turns clipboard-style content (a drag from another app, a pasted image) into what Share gives:
 * text for the message box, and content/file URIs to attach. Web links are text, not files.
 */
fun ClipData.toShare(taskId: String?): UiStore.Share? {
    val text = StringBuilder(); val uris = mutableListOf<android.net.Uri>()
    for (i in 0 until itemCount) {
        val item = getItemAt(i)
        val uri = item.uri
        if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) uris += uri
        else (item.text?.toString() ?: uri?.toString())?.takeIf { it.isNotBlank() }?.let { if (text.isNotEmpty()) text.append('\n'); text.append(it) }
    }
    if (text.isBlank() && uris.isEmpty()) return null
    return UiStore.Share(text.toString(), uris.distinct(), taskId)
}

/**
 * Lets photos, files, links and text be dragged in from another app (split screen, DeX, a tablet's
 * taskbar). They land like Share does: files become attachments, text goes into the message box,
 * and nothing is sent until you send it. [taskId] is the task this screen shows, or null for home.
 */
@Composable
fun Modifier.dropInto(taskId: String?): Modifier {
    val activity = LocalContext.current as? android.app.Activity
    var over by remember { mutableStateOf(false) }
    fun toast(msg: String) { activity?.let { android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_SHORT).show() } }
    val target = remember(taskId, activity) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { over = true }
            override fun onExited(event: DragAndDropEvent) { over = false }
            override fun onEnded(event: DragAndDropEvent) { over = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                over = false
                val drag = event.toAndroidDragEvent()
                // Another app's files can only be read while we hold its permission; it lasts until Saturn closes.
                runCatching { activity?.requestDragAndDropPermissions(drag) }
                val clip = drag.clipData
                android.util.Log.i("SaturnDrop", "drop on ${taskId ?: "home"}: ${clip?.itemCount ?: 0} item(s), types=${clip?.description?.let { d -> (0 until d.mimeTypeCount).map(d::getMimeType) }}")
                val share = clip?.toShare(taskId) ?: run { toast("Saturn can't use what was dropped"); return false }
                UiStore.share = share
                return true
            }
        }
    }
    return this
        .dragAndDropTarget(shouldStartDragAndDrop = { e ->
            val types = e.mimeTypes()
            types.isNotEmpty() // text, images, any file
        }, target = target)
        .drawWithContent {
            drawContent()
            if (over) {
                val inset = 6.dp.toPx(); val r = 20.dp.toPx()
                drawRect(Ph.Amber.copy(alpha = 0.06f))
                drawRoundRect(Ph.Amber, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(r, r), style = Stroke(2.dp.toPx()))
            }
        }
}
