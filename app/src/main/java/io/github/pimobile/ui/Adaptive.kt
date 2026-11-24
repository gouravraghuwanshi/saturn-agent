package io.github.pimobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room Saturn has: a phone, a phone on its side, an unfolded foldable, a tablet, DeX.
 * Measured from the window (not the device), so split screen and freeform windows work too.
 */
data class Room(val width: Dp, val height: Dp) {
    /** List and task side by side: tablets, unfolded foldables held wide, desktop windows. */
    val twoPane get() = width >= 800.dp && height >= 480.dp
    /** A phone on its side: little height, so big decorations shrink. */
    val short get() = height < 480.dp
}

val LocalRoom = compositionLocalOf { Room(400.dp, 800.dp) }
/** True inside the left (list) pane, or the right (detail) pane, of the two-pane layout. */
val LocalTwoPane = compositionLocalOf { false }

/** Widest a single column of reading gets; past this, lines get too long to follow. */
val ReadableWidth = 720.dp
/** The task list's pane in the two-pane layout. */
fun listPaneWidth(total: Dp): Dp = (total * 0.36f).coerceIn(340.dp, 440.dp)

@Composable
fun MeasureRoom(content: @Composable (Room) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) { content(Room(maxWidth, maxHeight)) }
}

/** Centres a screen in a readable column on wide windows; does nothing on a phone. */
@Composable
fun Readable(max: Dp = ReadableWidth, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxHeight()) { content() }
}
