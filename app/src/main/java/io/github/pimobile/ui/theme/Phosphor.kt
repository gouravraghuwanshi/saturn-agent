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
    /** Book-like reading on the chat screen: cream page, dark ink, serif replies. Set only while a chat is open. */
    var paper by androidx.compose.runtime.mutableStateOf(false)
    private fun pick(dark: Long, oled: Long, page: Long) = Color(if (paper) page else if (amoled) oled else dark)
    private fun pick(dark: Long, page: Long) = Color(if (paper) page else dark)
    val Ground get() = pick(0xFF0B0C0F, 0xFF000000, 0xFFF4ECD8)
    val Deep get() = pick(0xFF08090B, 0xFF000000, 0xFFEDE3CB)
    val Surface get() = pick(0xFF14161B, 0xFF09090A, 0xFFEDE2C8)
    val Raised get() = pick(0xFF16181E, 0xFF0E0F11, 0xFFF9F2E3)
    val Chip get() = pick(0xFF1E2128, 0xFF17181C, 0xFFE3D6B8)
    val Selected get() = pick(0xFF23262E, 0xFF1C1E23, 0xFFDCCDAD)
    val Line get() = pick(0xFF22262E, 0xFF1A1B20, 0xFFDDD0B1)
    val LineStrong get() = pick(0xFF2E333D, 0xFF26282F, 0xFFCBBB97)
    val Text get() = pick(0xFFE9E6DF, 0xFF2A2017)
    val TextSoft get() = pick(0xFFC9C5BD, 0xFF3A2F23)
    val TextBody get() = pick(0xFFA9AEB7, 0xFF4B4034)
    val Muted get() = pick(0xFF8E949E, 0xFF8A7A63)
    val Amber get() = pick(0xFFF2B35B, 0xFFAF6A14)
    val AmberInk get() = pick(0xFF14110B, 0xFFFFF8EA)
    val AmberWash get() = pick(0xFF2A2418, 0xFFF1DFBC)
    val AmberLine get() = pick(0xFF4A3C22, 0xFFD8B47A)
    val Cyan get() = pick(0xFF6FB7CC, 0xFF2D768A)
    val Green get() = pick(0xFF7CC79A, 0xFF3B8657)
    val Red get() = pick(0xFFF0857A, 0xFFB2463A)
    val RedWash get() = pick(0xFF1E1615, 0xFFF4DCD4)

    private fun weights(res: Int, vararg w: Int) = w.map {
        Font(res, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    }

    val Mono = FontFamily(weights(R.font.jetbrains_mono, 400, 500, 600))
    val Sans = FontFamily(weights(R.font.inter, 400, 500, 600))

    val mono get() = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 19.sp, color = TextBody)
    val monoSmall get() = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, color = Muted)
    val label get() = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Muted)
    /** Prose. On paper it's set like a book: serif, a little larger, more leading. */
    val body get() = if (paper) TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp, color = TextSoft)
        else TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 22.sp, color = TextSoft)
    val title get() = TextStyle(fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Text)
    val heading get() = TextStyle(fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = Text)
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
