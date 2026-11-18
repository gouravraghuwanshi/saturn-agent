package io.github.pimobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Stroke icons on a 24px grid, matching the approved design. */
object PhIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun stroke(vararg paths: String, width: Float = 1.8f) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    private fun fill(path: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply { addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }.build()

    val Back = stroke("M15 5l-7 7 7 7")
    val Settings = stroke("M4 7h10M18 7h2M4 17h4M12 17h8", circle(16f, 7f, 2f), circle(10f, 17f, 2f), width = 1.7f)
    val Plus = stroke("M12 5v14M5 12h14")
    val Folder = stroke("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Send = stroke("M12 19V5M5 12l7-7 7 7", width = 2.2f)
    val Stop = fill("M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val More = fill(circle(5f, 12f, 1.6f) + circle(12f, 12f, 1.6f) + circle(19f, 12f, 1.6f))
    val Cube = stroke("M12 3l8 4.5v9L12 21l-8-4.5v-9z", "M12 12l8-4.5M12 12v9M12 12L4 7.5", width = 1.7f)
    val Warning = stroke("M12 3l9 16H3z", "M12 10v4M12 17h.01")
    val Chevron = stroke("M6 9l6 6 6-6")
    val Pencil = stroke("M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val Info = stroke(circle(12f, 12f, 9f), "M12 11v5M12 8h.01")
    val Branch = stroke(circle(6f, 6f, 2f), circle(6f, 18f, 2f), circle(18f, 8f, 2f), "M6 8v8M18 10c0 4-6 3-12 6")
    val Share = stroke("M12 15V4M8 8l4-4 4 4", "M5 13v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5")
    val Copy = stroke("M9 9h10v10H9z", "M5 15V5h10")
    val Trash = stroke("M4 7h16M10 11v6M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3")
    val Archive = stroke("M3 5h18v4H3z", "M5 9v10h14V9M10 13h4")
    val Terminal = stroke("M4 5h16v14H4z", "M8 10l3 2-3 2M13 15h3")
    val Sparkle = stroke("M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z")
    val External = stroke("M14 4h6v6M20 4l-9 9", "M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
    val Import = stroke("M12 4v11M8 11l4 4 4-4", "M5 15v3a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3")
    val Check = stroke("M5 12l5 5 9-10", width = 2.4f)
    val Close = stroke("M6 6l12 12M18 6L6 18")
    val Key = stroke(circle(8f, 15f, 4f), "M11 12l9-9M17 6l3 3")
    val Lock = stroke("M6 11h12v9H6zM8.5 11V8a3.5 3.5 0 0 1 7 0v3")
}

@Composable
fun PhIcon(icon: ImageVector, tint: Color, size: Dp = 20.dp, description: String? = null, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription = description, tint = tint, modifier = modifier.size(size))
