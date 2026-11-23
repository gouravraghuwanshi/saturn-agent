package io.github.pimobile.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.theme.Ph
import java.io.File

/** What something can be shared as. */
enum class ShareAs(val label: String, val sub: String) {
    IMAGE("Image", ".png"), MARKDOWN("Markdown", ".md"),
}

// ------------------------------------------------------------------ capturing what's on screen

/** Records what a composable draws, so it can be saved as an image (whole content, even what's scrolled out of view). */
class Capture(val layer: GraphicsLayer)

@Composable
fun rememberCapture(): Capture { val l = rememberGraphicsLayer(); return remember(l) { Capture(l) } }

fun Modifier.capture(c: Capture) = drawWithContent {
    c.layer.record { this@drawWithContent.drawContent() }
    drawLayer(c.layer)
}

/** A little menu: Image or Markdown. */
@Composable
fun ShareMenu(open: Boolean, onDismiss: () -> Unit, onPick: (ShareAs) -> Unit) {
    DropdownMenu(open, onDismiss, containerColor = Ph.Raised, shape = RoundedCornerShape(14.dp)) {
        Text("SHARE AS", style = Ph.label, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp))
        ShareAs.entries.forEach { f ->
            DropdownMenuItem(
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(f.label, style = Ph.body.copy(fontSize = 14.sp, color = Ph.Text))
                        Text(f.sub, style = Ph.monoSmall)
                    }
                },
                leadingIcon = { PhIcon(when (f) { ShareAs.IMAGE -> PhIcons.Image; ShareAs.MARKDOWN -> PhIcons.File }, Ph.Amber, 17.dp) },
                onClick = { onDismiss(); onPick(f) })
        }
    }
}

object Export {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun share(ctx: Context, f: File, mime: String, subject: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "saturn" }

    /** Share [md] as Markdown, or (from [capture]) as an image. */
    suspend fun share(ctx: Context, how: ShareAs, md: String, title: String, capture: Capture?) {
        val name = slug(title)
        when (how) {
            ShareAs.MARKDOWN -> share(ctx, File(dir(ctx), "$name.md").apply { writeText(md) }, "text/markdown", title)
            ShareAs.IMAGE -> {
                val c = capture ?: return
                val img = c.layer.toImageBitmap().asAndroidBitmap()
                val f = File(dir(ctx), "$name.png")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { framed(img, ctx).let { b -> f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } }
                share(ctx, f, "image/png", title)
            }
        }
    }

    /** The capture on the app's own background, with room around it and a small Saturn mark. */
    private fun framed(src0: android.graphics.Bitmap, ctx: Context): android.graphics.Bitmap {
        val src = if (src0.config == android.graphics.Bitmap.Config.HARDWARE) src0.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src0
        val d = ctx.resources.displayMetrics.density
        val pad = (20 * d).toInt(); val foot = (30 * d).toInt()
        val out = android.graphics.Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad + foot, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawColor(Ph.Ground.toArgb())
        cv.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Ph.Muted.toArgb(); textSize = 11 * d; typeface = android.graphics.Typeface.MONOSPACE; textAlign = android.graphics.Paint.Align.RIGHT
        }
        cv.drawText("◍ Saturn", (out.width - pad).toFloat(), (out.height - pad * 0.9f), p)
        return out
    }
}

/** Where a *single* / _single_ italic span opening at [i] closes, or -1 (snake_case and lone stars stay as they are). */
fun italicEnd(s: String, i: Int): Int {
    val c = s[i]
    if (s.getOrNull(i + 1) == c || s.getOrNull(i + 1)?.isWhitespace() != false) return -1
    if (i > 0 && s[i - 1].isLetterOrDigit()) return -1
    var j = i + 1
    while (j < s.length) {
        if (s[j] == c && !s[j - 1].isWhitespace() && s.getOrNull(j + 1)?.isLetterOrDigit() != true && s.getOrNull(j + 1) != c) return j
        j++
    }
    return -1
}

/** For callers outside this package that can't use the extension directly. */
fun captureModifier(c: Capture): Modifier = Modifier.capture(c)
