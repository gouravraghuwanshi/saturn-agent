package io.github.pimobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph

/**
 * A small amber pill pointing at a gesture people wouldn't guess ("Hold for model & thinking"). It slides in
 * a moment after the screen settles, its arrow nudging gently, and goes away for good once [key] is marked
 * (UiStore.markHint) by using the gesture.
 */
@Composable
fun Hint(key: String, text: String, arrow: String = "←", modifier: Modifier = Modifier, show: Boolean = true) {
    if (UiStore.hintSeen(key)) return
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(700); ready = true }
    AnimatedVisibility(ready && show && !UiStore.hintSeen(key), modifier,
        enter = fadeIn(tween(260)) + expandHorizontally(spring(dampingRatio = 0.8f, stiffness = 380f), expandFrom = Alignment.Start),
        exit = fadeOut(tween(160)) + shrinkHorizontally(tween(220), shrinkTowards = Alignment.Start)) {
        val nudge by rememberInfiniteTransition(label = "hint").animateFloat(0f, 1f,
            infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "nudge")
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Ph.AmberWash).border(1.dp, Ph.AmberLine, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(arrow, style = Ph.monoSmall.copy(fontSize = 11.sp, color = Ph.Amber),
                modifier = Modifier.graphicsLayer { translationX = (if (arrow == "←" || arrow == "↖") -1f else 1f) * nudge * 3.dp.toPx() })
            Text(text, style = Ph.monoSmall.copy(fontSize = 11.sp, color = Ph.Amber), maxLines = 1)
        }
    }
}
