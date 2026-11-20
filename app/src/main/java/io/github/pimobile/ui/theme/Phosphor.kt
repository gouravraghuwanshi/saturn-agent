package io.github.pimobile.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import io.github.pimobile.R
import kotlinx.coroutines.launch
import androidx.compose.ui.node.currentValueOf

/** "Phosphor": a premium terminal. Amber means only "pi is working / needs you". */
object Ph {
    /** Pure-black surfaces for OLED screens (Settings → Appearance). Reading any color below in a composable follows it. */
    var amoled by androidx.compose.runtime.mutableStateOf(false)
    val Ground get() = if (amoled) Color(0xFF000000) else Color(0xFF0B0C0F)
    val Deep get() = if (amoled) Color(0xFF000000) else Color(0xFF08090B)
    val Surface get() = if (amoled) Color(0xFF09090A) else Color(0xFF14161B)
    val Raised get() = if (amoled) Color(0xFF0E0F11) else Color(0xFF16181E)
    val Chip get() = if (amoled) Color(0xFF17181C) else Color(0xFF1E2128)
    val Selected get() = if (amoled) Color(0xFF1C1E23) else Color(0xFF23262E)
    val Line get() = if (amoled) Color(0xFF1A1B20) else Color(0xFF22262E)
    val LineStrong get() = if (amoled) Color(0xFF26282F) else Color(0xFF2E333D)
    val Text = Color(0xFFE9E6DF)
    val TextSoft = Color(0xFFC9C5BD)
    val TextBody = Color(0xFFA9AEB7)
    val Muted = Color(0xFF8E949E)
    val Amber = Color(0xFFF2B35B)
    val AmberInk = Color(0xFF14110B)
    val AmberWash = Color(0xFF2A2418)
    val AmberLine = Color(0xFF4A3C22)
    val Cyan = Color(0xFF6FB7CC)
    val Green = Color(0xFF7CC79A)
    val Red = Color(0xFFF0857A)
    val RedWash = Color(0xFF1E1615)

    private fun weights(res: Int, vararg w: Int) = w.map {
        Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    }

    val Mono = FontFamily(weights(R.font.jetbrains_mono, 400, 500, 600))
    val Sans = FontFamily(weights(R.font.inter, 400, 500, 600))

    val mono = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 19.sp, color = TextBody)
    val monoSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Muted)
    val label = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted)
    val body = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 22.sp, color = TextSoft)
    val title = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Text)
    val heading = TextStyle(fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = Text)
}

@Composable
fun PhosphorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ph.Amber, onPrimary = Ph.AmberInk,
            background = Ph.Ground, onBackground = Ph.Text,
            surface = Ph.Surface, onSurface = Ph.Text,
            surfaceContainer = Ph.Raised, outline = Ph.LineStrong,
            error = Ph.Red,
        ),
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val maxPx = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
        val bounce = androidx.compose.runtime.remember(view, scope, maxPx) { BounceFactory(scope, view, maxPx) }
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(Ph.Amber, Ph.Amber.copy(alpha = 0.3f)),
            // Lists end with a weighted spring and a tick, not Android's stretch.
            androidx.compose.foundation.LocalOverscrollFactory provides bounce,
            // Every button, card and row gives a light tick under your finger.
            androidx.compose.foundation.LocalIndication provides HapticIndication(androidx.compose.material3.ripple()),
            content = content,
        )
    }
}

/** The usual ripple, plus a light haptic tick when a press lands. Respects the phone's touch-feedback setting. */
private class HapticIndication(private val ripple: androidx.compose.foundation.IndicationNodeFactory) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        HapticNode(interactionSource, ripple.create(interactionSource))
    override fun equals(other: Any?) = other is HapticIndication && other.ripple == ripple
    override fun hashCode() = ripple.hashCode()
}

private class HapticNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource,
    inner: androidx.compose.ui.node.DelegatableNode,
) : androidx.compose.ui.node.DelegatingNode(), androidx.compose.ui.node.CompositionLocalConsumerModifierNode {
    init { delegate(inner) }
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect {
                if (it is androidx.compose.foundation.interaction.PressInteraction.Press)
                    currentValueOf(androidx.compose.ui.platform.LocalView).performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
    }
}
