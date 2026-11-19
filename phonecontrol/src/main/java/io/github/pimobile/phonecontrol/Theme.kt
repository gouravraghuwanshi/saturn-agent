package io.github.pimobile.phonecontrol

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import io.github.pimobile.phonecontrol.R

/** "Phosphor": a premium terminal. Amber means only "pi is working / needs you". */
object Ph {
    val Ground = Color(0xFF0B0C0F)
    val Deep = Color(0xFF08090B)
    val Surface = Color(0xFF14161B)
    val Raised = Color(0xFF16181E)
    val Chip = Color(0xFF1E2128)
    val Selected = Color(0xFF23262E)
    val Line = Color(0xFF22262E)
    val LineStrong = Color(0xFF2E333D)
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
        CompositionLocalProvider(
            LocalTextSelectionColors provides TextSelectionColors(Ph.Amber, Ph.Amber.copy(alpha = 0.3f)),
            content = content,
        )
    }
}
