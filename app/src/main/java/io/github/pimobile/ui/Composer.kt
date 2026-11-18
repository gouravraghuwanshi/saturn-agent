package io.github.pimobile.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pimobile.ui.components.PhIcon
import io.github.pimobile.ui.components.PhIcons
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph

/** A command shown in the slash palette. [run] = handled by the app; else inserted. */
data class SlashCommand(val name: String, val description: String, val tag: String, val run: (() -> Unit)? = null)

/** App-handled commands (pi's interactive built-ins don't exist in RPC mode) + pi's own. */
fun slashCommands(builtins: List<SlashCommand>): List<SlashCommand> =
    builtins + UiStore.commands.map {
        SlashCommand(it.name, it.description, when (it.source) { "extension" -> "ext"; "skill" -> "skill"; "prompt" -> "prompt"; else -> it.source })
    }

@Composable
fun SlashPalette(query: String, commands: List<SlashCommand>, onPick: (SlashCommand) -> Unit) {
    val q = query.removePrefix("/").lowercase()
    val shown = remember(q, commands) { commands.filter { it.name.lowercase().contains(q) } }
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(22.dp)).background(Ph.Surface)
            .border(1.dp, Ph.LineStrong, RoundedCornerShape(22.dp))
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("COMMANDS", style = Ph.label)
            Text("tap to run", style = Ph.monoSmall)
        }
        if (shown.isEmpty()) Text("No matching command", style = Ph.body.copy(color = Ph.Muted),
            modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.heightIn(max = 300.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
            items(shown, key = { it.name }) { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(c) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("/${c.name}", style = Ph.mono.copy(color = Ph.Text, fontSize = 13.5.sp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                    Text(c.description, style = Ph.body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Ph.TextBody),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(c.tag, style = Ph.monoSmall.copy(fontSize = 10.sp))
                }
            }
        }
    }
}

/**
 * The floating input. Shows the slash palette while the text starts with "/".
 * [leading]: chips under the text; [extra]: buttons before send (e.g. stop).
 */
@Composable
fun Composer(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    commands: List<SlashCommand>,
    onSend: (String) -> Unit,
    focus: FocusRequester = remember { FocusRequester() },
    accent: Color = Ph.Amber,
    leading: @Composable RowScope.() -> Unit = {},
    extra: @Composable RowScope.() -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val text = value.text
    val showPalette = text.startsWith("/") && !text.contains(' ')
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSend(t)
    }

    Column {
        // weight(fill=false): the input is measured first and always keeps its
        // full height; the palette gets whatever space is left (and scrolls).
        AnimatedVisibility(showPalette, modifier = Modifier.weight(1f, fill = false),
            enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut() + slideOutVertically { it / 6 }) {
            Box(Modifier.padding(bottom = 10.dp)) {
                SlashPalette(text, commands) { c ->
                    if (c.run != null) { onValue(TextFieldValue("")); c.run.invoke() }
                    else onValue(TextFieldValue("/${c.name} ").let { it.copy(selection = androidx.compose.ui.text.TextRange(it.text.length)) })
                }
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .shadow(28.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp)).background(Ph.Raised)
                .border(1.dp, if (showPalette) accent else Ph.LineStrong, RoundedCornerShape(22.dp))
                .pointerInput(Unit) { detectTapGestures { focus.requestFocus(); keyboard?.show() } }
                .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
        ) {
            BasicTextField(
                value = value, onValueChange = onValue,
                textStyle = (if (text.startsWith("/")) Ph.mono.copy(fontSize = 16.sp) else Ph.body.copy(fontSize = 16.sp)).copy(color = Ph.Text),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 12.dp).focusRequester(focus)
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(placeholder, style = Ph.body.copy(fontSize = 16.sp, color = Ph.Muted))
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("/", mono = true) {
                    onValue(TextFieldValue("/", selection = androidx.compose.ui.text.TextRange(1)))
                    focus.requestFocus()
                }
                leading()
                Box(Modifier.weight(1f))
                extra()
                SendButton(enabled = text.isNotBlank(), color = accent) { send() }
            }
        }
    }
}

@Composable
fun ChipButton(label: String, mono: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
               description: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(Ph.Chip)
            .clickable(onClickLabel = description ?: label) { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { PhIcon(it, Ph.TextSoft, 15.dp) }
        Text(label, style = (if (mono) Ph.mono.copy(fontSize = 13.sp) else Ph.mono).copy(color = Ph.TextSoft),
            modifier = Modifier.padding(vertical = 9.dp))
    }
}

@Composable
fun SendButton(enabled: Boolean, color: Color = Ph.Amber, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else Ph.Chip)
            .clickable(enabled = enabled, onClickLabel = "Send") { onClick() },
        contentAlignment = Alignment.Center,
    ) { PhIcon(PhIcons.Send, if (enabled) Ph.AmberInk else Ph.Muted, 20.dp, "Send") }
}
