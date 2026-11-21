package io.github.pimobile.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
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
    attachments: List<UiStore.Attachment> = emptyList(),
    attach: AttachPicker? = null,
    onRemoveAttachment: (UiStore.Attachment) -> Unit = {},
    acceptVoiceRequest: Boolean = false,
    thinking: ThinkingControl? = null,
    /** How full the model's context is (0..1), drawn as a ring round the mic/send button; null hides it. */
    contextFill: Float? = null,
    /** Hold the box and pull it up to do this (e.g. compact), like the other pull actions. */
    pulls: List<PullAction> = emptyList(),
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val liftMax = with(density) { 26.dp.toPx() }       // the box only gives a little: it's being pulled, not dragged
    val revealPx = with(density) { 54.dp.toPx() }      // how far until the action bar is fully out
    val startPx = with(density) { 34.dp.toPx() }
    val armPx = with(density) { 150.dp.toPx() }        // pull this far to arm the action under your finger
    var pulled by remember { mutableStateOf(0f) }      // upward travel since the pull locked
    var pulling by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var pressAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var hoverIdx by remember { mutableStateOf(0) }
    var armed by remember { mutableStateOf(false) }
    var boxW by remember { mutableStateOf(1f) }
    var inputFocused by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lift = -liftMax * (1f - kotlin.math.exp(-pulled / (liftMax * 3f)))
    val reveal = (pulled / revealPx).coerceIn(0f, 1f)
    val fillUp = ((pulled - startPx) / (armPx - startPx)).coerceIn(0f, 1f)
    // The wave: a little while held, growing as you pull.
    val waveTarget = when { pulling -> 0.35f + 0.65f * fillUp; holding -> 0.3f; else -> 0f }
    val wave by androidx.compose.animation.core.animateFloatAsState(waveTarget,
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 220f), label = "wave")
    var clock by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(wave > 0.001f) {
        if (wave <= 0.001f) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { clock = (it - start) / 1e9f }
    }
    var thinkOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val text = value.text
    val showPalette = text.startsWith("/") && !text.contains(' ')
    fun send() {
        val t = text.trim()
        if (t.isEmpty() && attachments.isEmpty()) return
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onSend(t)
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val dictation = remember { Dictation(context) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { dictation.stopAndRelease() } }
    var dictationBase by remember { mutableStateOf("") }
    fun put(t: String) = onValue(TextFieldValue(t, selection = androidx.compose.ui.text.TextRange(t.length)))
    fun listen() {
        dictationBase = text.trimEnd().let { if (it.isEmpty()) "" else "$it " }
        dictation.start(
            onPartial = { put(dictationBase + it) },
            onFinal = { if (it.isNotBlank()) put(dictationBase + it) },
            onError = { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() },
        )
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else android.widget.Toast.makeText(context, "Microphone permission is needed to talk to Saturn", android.widget.Toast.LENGTH_SHORT).show()
    }
    // Phones without an in-app recognizer: Android's own dialog.
    val systemDialog = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { put(text.trimEnd().let { t -> if (t.isEmpty()) it else "$t $it" }) }
    }
    fun startDictation() {
        when {
            !dictation.available -> runCatching {
                systemDialog.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
            }.onFailure { android.widget.Toast.makeText(context, "No speech recognizer on this phone", android.widget.Toast.LENGTH_SHORT).show() }
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> listen()
            else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    if (acceptVoiceRequest && UiStore.composeRequest) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(450); UiStore.composeRequest = false; runCatching { focus.requestFocus() }; keyboard?.show()
    }
    if (acceptVoiceRequest && UiStore.voiceRequest) androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400); UiStore.voiceRequest = false; startDictation()
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
        Box {
        // The action bar: slides up out from behind the box as you pull, one half per action.
        if (pulls.isNotEmpty() && reveal > 0.01f) {
            val barH = with(density) { 58.dp.toPx() }
            val gap = with(density) { 10.dp.toPx() }
            Box(Modifier.matchParentSize().padding(horizontal = 16.dp).layout { m, c ->
                val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth, barH.toInt()))
                layout(c.maxWidth, c.maxHeight) { p.place(0, (lift - (barH + gap) * reveal).toInt()) }
            }.graphicsLayer { alpha = reveal }) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pulls.forEachIndexed { i, a ->
                        val on = i == hoverIdx
                        val hot = on && armed
                        val shape = RoundedCornerShape(18.dp)
                        val pop by androidx.compose.animation.core.animateFloatAsState(if (hot) 1.04f else 1f,
                            androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), label = "bar$i")
                        Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { scaleX = pop; scaleY = pop }.clip(shape)
                            .background(if (hot) Ph.Amber else Ph.Surface)
                            .border(1.dp, if (hot) Ph.Amber else if (on) Ph.AmberLine else Ph.Line, shape)) {
                            // Amber rising in the half under your finger.
                            if (on && !hot) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fillUp)
                                .background(Ph.Amber.copy(alpha = 0.16f)))
                            Row(Modifier.align(Alignment.Center).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PhIcon(a.icon, if (hot) Ph.AmberInk else if (on) Ph.Amber else Ph.Muted, 18.dp)
                                Column {
                                    Text(if (hot) a.ready else a.label, maxLines = 1,
                                        style = Ph.title.copy(fontSize = 13.5.sp, color = if (hot) Ph.AmberInk else if (on) Ph.Text else Ph.TextSoft))
                                    a.note?.let { Text(it, maxLines = 1, style = Ph.monoSmall.copy(fontSize = 10.sp, color = if (hot) Ph.AmberInk.copy(alpha = 0.7f) else Ph.Muted)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .onSizeChanged { boxW = it.width.toFloat() }
                .then(if (pulls.isEmpty()) Modifier else Modifier.pointerInput(pulls.size) {
                    // Hold the box (it ripples), pull up: the bar comes out; the half under your finger fills; full = armed.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        pressAt = down.position
                        val holdJob = scope.launch { kotlinx.coroutines.delay(170); holding = true }
                        var dx = 0f; var dy = 0f; var locked = false
                        val slop = viewConfiguration.touchSlop
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - ch.previousPosition
                                dx += d.x; dy += d.y
                                if (!locked) {
                                    if (kotlin.math.abs(dx) > slop || dy > slop) { holdJob.cancel(); holding = false }
                                    if (kotlin.math.abs(dx) > slop * 1.5f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) return@awaitEachGesture
                                    if (dy < -slop && kotlin.math.abs(dy) > 1.5f * kotlin.math.abs(dx)) {
                                        locked = true; pulling = true; dy = 0f
                                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                    } else continue
                                }
                                ch.consume()
                                pulled = (-dy).coerceAtLeast(0f)
                                pressAt = androidx.compose.ui.geometry.Offset(ch.position.x, pressAt.y)
                                val idx = (ch.position.x / boxW * pulls.size).toInt().coerceIn(0, pulls.size - 1)
                                if (idx != hoverIdx && reveal >= 1f) view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
                                hoverIdx = idx
                                val now = pulled >= armPx
                                if (now != armed) {
                                    armed = now
                                    view.performHapticFeedback(if (now) (if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                                        else (if (android.os.Build.VERSION.SDK_INT >= 34) android.view.HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else android.view.HapticFeedbackConstants.CLOCK_TICK))
                                }
                            }
                        } finally {
                            holdJob.cancel(); holding = false
                            if (locked) {
                                if (armed) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); pulls[hoverIdx].onRelease() }
                                armed = false
                                val from = pulled
                                scope.launch {
                                    androidx.compose.animation.core.animate(from, 0f,
                                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f)) { v, _ -> pulled = v }
                                    pulling = false
                                }
                            }
                        }
                    }
                })
                .graphicsLayer { translationY = lift }
                .shadow(28.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .drawWithContent {
                    val r = 22.dp.toPx()
                    val amp = 2.6.dp.toPx() * wave
                    val outline = wavyRoundRect(size, r, amp, clock, pressAt.x)
                    clipPath(outline) {
                        drawRect(Ph.Raised)
                        // How full the context is: a faint wash filling the box from the left.
                        val f = (contextFill ?: 0f).coerceIn(0f, 1f)
                        if (f > 0.003f) drawRect(Ph.Text.copy(alpha = if (f >= 0.9f) 0.085f else 0.05f),
                            size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
                        // Rings spreading from your finger, like a drop in water.
                        if (wave > 0.01f) for (k in 0 until 3) {
                            val ph = ((clock * 0.62f) + k / 3f) % 1f
                            drawCircle(Ph.Text.copy(alpha = 0.07f * wave * (1f - ph)), radius = size.width * 0.75f * ph, center = pressAt,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx() + 6.dp.toPx() * (1f - ph)))
                        }
                        this@drawWithContent.drawContent()
                    }
                    val edge = if (armed) Ph.AmberLine else if (showPalette) accent else Ph.LineStrong
                    drawPath(outline, edge, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                .pointerInput(Unit) { detectTapGestures(onLongPress = {}) { focus.requestFocus(); keyboard?.show() } }
                .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
        ) {
            if (attachments.isNotEmpty() || attach?.busy == true) Row(Modifier.padding(bottom = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                attachments.forEach { a ->
                    if (a.isImage) Thumb(a.path, 58) { onRemoveAttachment(a) } else FileChip(a.name, onRemove = { onRemoveAttachment(a) })
                }
                if (attach?.busy == true) Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { io.github.pimobile.ui.components.PulseDot(Ph.Amber, 7) }
            }
            Box {
            BasicTextField(
                value = value, onValueChange = onValue,
                textStyle = (if (text.startsWith("/")) Ph.mono.copy(fontSize = 16.sp) else Ph.body.copy(fontSize = 16.sp)).copy(color = Ph.Text),
                cursorBrush = SolidColor(accent),
                // Enter makes a new line; sending is the round button's job.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 12.dp).focusRequester(focus)
                    .onFocusChanged { inputFocused = it.isFocused }
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(if (dictation.listening) "Listening…" else placeholder,
                            style = Ph.body.copy(fontSize = 16.sp, color = if (dictation.listening) Ph.Amber else Ph.Muted))
                        inner()
                    }
                },
            )
            // Until you're typing, a short tap opens the keyboard; a hold doesn't (it's for pulling).
            if (!inputFocused) Box(Modifier.matchParentSize().pointerInput(Unit) {
                detectTapGestures(onLongPress = {}) {
                    onValue(value.copy(selection = androidx.compose.ui.text.TextRange(value.text.length)))
                    focus.requestFocus(); keyboard?.show()
                }
            })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dictation.listening) VoiceWave(dictation, Modifier.weight(1f).padding(end = 6.dp))
                else if (thinkOpen && thinking != null) ThinkingSlider(thinking, Modifier.weight(1f)) { thinkOpen = false }
                else {
                    IconGhost(description = "Commands", onClick = {
                        onValue(TextFieldValue("/", selection = androidx.compose.ui.text.TextRange(1)))
                        focus.requestFocus()
                    }) { Text("/", style = Ph.mono.copy(fontSize = 17.sp, color = Ph.TextSoft)) }
                    if (attach != null) AttachButton(attach)
                    if (thinking != null && thinking.levels.size > 1) ThinkingChip(thinking.level) { thinkOpen = true }
                    leading()
                    Box(Modifier.weight(1f))
                    extra()
                }
                // One round button: the mic while the box is empty, Send once there's something to send.
                if (dictation.listening || (text.isBlank() && attachments.isEmpty())) MicButton(dictation) {
                        if (dictation.listening) dictation.stop() else startDictation()
                    } else SendButton(enabled = true, color = accent) { send() }
            }
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
        if (label.isNotEmpty()) Text(label, style = (if (mono) Ph.mono.copy(fontSize = 13.sp) else Ph.mono).copy(color = Ph.TextSoft),
            modifier = Modifier.padding(vertical = 9.dp))
        else Box(Modifier.padding(vertical = 9.dp).size(width = 1.dp, height = 18.dp))
    }
}

@Composable
fun SendButton(enabled: Boolean, color: Color = Ph.Amber, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (enabled) color else Ph.Chip)
            .clickable(enabled = enabled, onClickLabel = "Send") { onClick() },
        contentAlignment = Alignment.Center,
    ) { PhIcon(PhIcons.Send, if (enabled) Ph.AmberInk else Ph.Muted, 20.dp, "Send") }
}


/** A small image preview from a file; [onRemove] adds a × badge. */
@Composable
fun Thumb(path: String, size: Int, onRemove: (() -> Unit)? = null) {
    val bmp = remember(path) {
        runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, o)
            o.inSampleSize = maxOf(1, maxOf(o.outWidth, o.outHeight) / (size * 4))
            o.inJustDecodeBounds = false
            android.graphics.BitmapFactory.decodeFile(path, o)?.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.size(size.dp)) {
        Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Chip).border(1.dp, Ph.Line, RoundedCornerShape(12.dp))) {
            if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.size(size.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else PhIcon(PhIcons.Image, Ph.Muted, 20.dp)
        }
        if (onRemove != null) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0B0C0F)).clickable(onClickLabel = "Remove image") { onRemove() }, contentAlignment = Alignment.Center) {
            PhIcon(PhIcons.Close, Ph.Text, 12.dp)
        }
    }
}

/** The composer's + : photos, camera or any file. */
class AttachPicker(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit) {
    var busy by androidx.compose.runtime.mutableStateOf(false)
}

@Composable
fun rememberAttachPicker(onPicked: (List<UiStore.Attachment>) -> Unit): AttachPicker {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    lateinit var picker: AttachPicker
    fun load(uris: List<android.net.Uri>, cleanup: () -> Unit = {}) {
        if (uris.isEmpty()) return
        picker.busy = true
        scope.launch {
            val results = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                uris.map { u -> runCatching { UiStore.attach(u) } }.also { cleanup() }
            }
            picker.busy = false
            results.mapNotNull { it.exceptionOrNull() }.firstOrNull()?.let {
                android.widget.Toast.makeText(context, "Couldn't attach: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            onPicked(results.mapNotNull { it.getOrNull() })
        }
    }
    val photos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(8)) { load(it) }
    val files = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { load(it) }
    var shot by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.io.File?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        val f = shot ?: return@rememberLauncherForActivityResult
        shot = null
        if (ok && f.length() > 0) load(listOf(android.net.Uri.fromFile(f))) { f.delete() } else f.delete()
    }
    fun openCamera() {
        val f = UiStore.cameraFile()
        shot = f
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
        camera.launch(uri)
    }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
        else android.widget.Toast.makeText(context, "Camera permission is needed to take a photo", android.widget.Toast.LENGTH_SHORT).show()
    }
    picker = androidx.compose.runtime.remember {
        AttachPicker(
            photos = { photos.launch(androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            // Saturn declares CAMERA (for termux-camera-photo), so Android requires it for the camera app too.
            camera = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
                else cameraPermission.launch(android.Manifest.permission.CAMERA)
            },
            files = { files.launch(arrayOf("*/*")) },
        )
    }
    return picker
}

@Composable
private fun AttachButton(picker: AttachPicker) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        IconGhost(description = "Attach", active = open, onClick = { open = true }) {
            PhIcon(PhIcons.Plus, if (open) Ph.Amber else Ph.TextSoft, 19.dp, "Attach")
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Ph.Raised,
            shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Ph.LineStrong)) {
            listOf(Triple(PhIcons.Image, "Photos", picker.photos), Triple(PhIcons.Camera, "Camera", picker.camera),
                Triple(PhIcons.File, "Files", picker.files)).forEach { (icon, label, action) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label, style = Ph.body.copy(fontSize = 15.sp, color = Ph.Text)) },
                    leadingIcon = { PhIcon(icon, Ph.TextSoft, 18.dp) },
                    onClick = { open = false; action() },
                )
            }
        }
    }
}

/** A non-image attachment: icon, name, extension. */
@Composable
fun FileChip(name: String, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Row(Modifier.height(58.dp).widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)).background(Ph.Chip)
        .border(1.dp, Ph.Line, RoundedCornerShape(12.dp))
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(start = 12.dp, end = if (onRemove != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhIcon(PhIcons.File, Ph.Amber, 20.dp)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name.substringBeforeLast('.'), style = Ph.body.copy(fontSize = 13.sp, color = Ph.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(name.substringAfterLast('.', "file").uppercase(), style = Ph.monoSmall)
        }
        if (onRemove != null) Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Remove file") { onRemove() },
            contentAlignment = Alignment.Center) { PhIcon(PhIcons.Close, Ph.Muted, 13.dp) }
    }
}


/**
 * The mic: listens inside Saturn (no system pop-up). While listening it turns
 * amber and pulses with your voice; tap again to stop. Falls back to Android's
 * own dialog on phones without an in-app recognizer.
 */
@Composable
fun MicButton(d: Dictation, onTap: () -> Unit) {
    val pulse by androidx.compose.animation.core.animateFloatAsState(if (d.listening) 1f + d.level * 0.45f else 1f,
        androidx.compose.animation.core.tween(120), label = "pulse")
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (d.listening) Box(Modifier.size(44.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
            .clip(androidx.compose.foundation.shape.CircleShape).background(Ph.Amber.copy(alpha = 0.22f)))
        Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (d.listening) Ph.Amber else Ph.Chip)
            .border(1.dp, if (d.listening) Ph.Amber else Ph.LineStrong, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClickLabel = if (d.listening) "Stop listening" else "Speak") { onTap() },
            contentAlignment = Alignment.Center) {
            if (d.listening) Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Ph.AmberInk))
            else PhIcon(PhIcons.Mic, Ph.TextSoft, 19.dp, "Speak")
        }
    }
}

/** A light, borderless round icon button for the composer's tools row. */
@Composable
fun IconGhost(description: String, active: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape)
        .border(1.dp, if (active) Ph.AmberLine else Ph.Line, androidx.compose.foundation.shape.CircleShape)
        .background(if (active) Ph.AmberWash else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(onClickLabel = description) { onClick() }, contentAlignment = Alignment.Center) { content() }
}


/**
 * A live waveform while dictating: bars scroll in from the right, each as
 * tall as your voice was at that moment (like a voice-note recorder).
 */
@Composable
private fun VoiceWave(d: Dictation, modifier: Modifier) {
    val bars = 32
    val history = remember { androidx.compose.runtime.mutableStateListOf<Float>().apply { repeat(bars) { add(0f) } } }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        var t = 0
        while (true) {
            // A touch of movement even in silence, so it reads as "listening".
            val idle = 0.06f + 0.04f * kotlin.math.sin(t++ / 2.0).toFloat()
            history.removeAt(0); history.add(maxOf(idle, d.level * (0.75f + 0.5f * kotlin.random.Random.nextFloat())))
            kotlinx.coroutines.delay(70)
        }
    }
    Row(modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        history.forEachIndexed { i, v ->
            val h by androidx.compose.animation.core.animateFloatAsState(v, androidx.compose.animation.core.tween(90), label = "bar")
            // Older bars fade toward the left edge.
            Box(Modifier.width(3.dp).height((4 + h.coerceIn(0f, 1f) * 32).dp).clip(RoundedCornerShape(2.dp))
                .background(Ph.TextSoft.copy(alpha = 0.25f + 0.65f * (i + 1) / bars)))
        }
    }
}


/** What the composer's thinking chip controls: the current level, the levels the model offers, and how to set one. */
class ThinkingControl(val level: String?, val levels: List<String>, val onSet: (String) -> Unit)

/** pi's levels, used where no task can tell us what the model supports. */
val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high")

private fun shortLevel(l: String?) = when (l) { null -> "think"; "minimal" -> "min"; "medium" -> "med"; else -> l }

/** A little meter + level ("▮▮▯ med"): tap to drag a new level. */
@Composable
private fun ThinkingChip(level: String?, onClick: () -> Unit) {
    val idx = THINKING_LEVELS.indexOf(level).let { if (it < 0 && level == "xhigh") 5 else it }
    // Quiet like the / and + buttons: thin outline, no fill, no amber.
    Row(Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).border(1.dp, Ph.Line, RoundedCornerShape(19.dp))
        .clickable(onClickLabel = "Thinking level") { onClick() }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..4) Box(Modifier.size(width = 2.dp, height = (3 + i * 2.5f).dp).clip(RoundedCornerShape(1.dp))
                .background(if (idx >= i) Ph.TextSoft else Ph.LineStrong))
        }
        Text(shortLevel(level), style = Ph.mono.copy(fontSize = 12.sp, color = Ph.TextSoft))
    }
}

/**
 * The chip opened into a track across the tools row: drag (or tap) along it,
 * it snaps to each level with a tick, and letting go sets it and folds back.
 */
@Composable
private fun ThinkingSlider(c: ThinkingControl, modifier: Modifier, onDone: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    val levels = c.levels
    var pick by remember { mutableStateOf(levels.indexOf(c.level).coerceAtLeast(0)) }
    val pos by androidx.compose.animation.core.animateFloatAsState(pick.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f), label = "thumb")
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var widthPx by remember { mutableStateOf(1f) }
    fun at(x: Float) = ((x / widthPx) * (levels.size - 1) + 0.5f).toInt().coerceIn(0, levels.size - 1)
    fun move(i: Int) { if (i != pick) { pick = i; view.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK) } }
    fun commit() {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        c.onSet(levels[pick])
        scope.launch { kotlinx.coroutines.delay(350); onDone() }
    }
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 12.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(levels) {
                detectTapGestures { o -> move(at(o.x)); commit() }
            }
            .pointerInput(levels) {
                detectHorizontalDragGestures(onDragEnd = { commit() }) { ch, _ -> ch.consume(); move(at(ch.position.x)) }
            }) {
            val frac = if (levels.size > 1) pos / (levels.size - 1) else 0f
            // track + amber fill up to the thumb
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Chip))
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Ph.Amber))
            // notches with labels above them
            levels.forEachIndexed { i, l ->
                val f = if (levels.size > 1) i.toFloat() / (levels.size - 1) else 0f
                Box(Modifier.fillMaxSize().layout { m, cons ->
                    val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                    layout(cons.maxWidth, cons.maxHeight) { p.place((cons.maxWidth * f - p.width / 2f).toInt(), (cons.maxHeight - p.height) / 2) }
                }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(shortLevel(l), style = Ph.monoSmall.copy(fontSize = 9.5.sp, color = if (i == pick) Ph.Amber else Ph.Muted))
                        Box(Modifier.padding(top = 3.dp, bottom = 16.dp).size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (i <= pos + 0.01f) Ph.Amber else Ph.LineStrong))
                    }
                }
            }
            // the thumb
            Box(Modifier.fillMaxSize().layout { m, cons ->
                val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
                layout(cons.maxWidth, cons.maxHeight) { p.place((cons.maxWidth * frac - p.width / 2f).toInt(), (cons.maxHeight - p.height) / 2) }
            }) {
                Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ph.Amber)
                    .border(3.dp, Ph.AmberInk, androidx.compose.foundation.shape.CircleShape))
            }
        }
        Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClickLabel = "Close") { onDone() },
            contentAlignment = Alignment.Center) { PhIcon(PhIcons.Close, Ph.Muted, 15.dp) }
    }
}

/** A drop target shown above the composer while it's pulled up: its icon, label, label while hovered, and what it does. */
class PullAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val ready: String,
                 val note: String? = null, val onRelease: () -> Unit)


/**
 * A rounded rectangle whose edge ripples: each point is pushed along its normal
 * by a travelling sine, strongest near [focusX] (where your finger is).
 */
private fun wavyRoundRect(size: androidx.compose.ui.geometry.Size, r: Float, amp: Float, t: Float, focusX: Float): androidx.compose.ui.graphics.Path {
    val base = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(r)))
    }
    if (amp < 0.2f) return base
    val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(base, true) }
    val len = pm.length
    val n = 180
    val waveLen = 46f * (size.width / 360f).coerceAtLeast(1f) + 60f
    val out = androidx.compose.ui.graphics.Path()
    for (i in 0..n) {
        val s = len * i / n
        val p = pm.getPosition(s); val tan = pm.getTangent(s)
        val env = 0.35f + 0.65f * kotlin.math.exp(-((p.x - focusX) / (size.width * 0.32f)).let { it * it })
        val o = amp * env * kotlin.math.sin(2f * Math.PI.toFloat() * s / waveLen - t * 7f)
        // Outward normal, whichever way the path runs; inset by amp so the crest stays inside the box.
        var nx = tan.y; var ny = -tan.x
        if (nx * (p.x - size.width / 2f) + ny * (p.y - size.height / 2f) < 0f) { nx = -nx; ny = -ny }
        val x = p.x + nx * (o - amp)
        val y = p.y + ny * (o - amp)
        if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
    }
    out.close()
    return out
}
