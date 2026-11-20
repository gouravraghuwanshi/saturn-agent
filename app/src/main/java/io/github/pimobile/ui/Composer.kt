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
) {
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
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .shadow(28.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape(22.dp)).background(Ph.Raised)
                .border(1.dp, if (showPalette) accent else Ph.LineStrong, RoundedCornerShape(22.dp))
                .pointerInput(Unit) { detectTapGestures { focus.requestFocus(); keyboard?.show() } }
                .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
        ) {
            if (attachments.isNotEmpty() || attach?.busy == true) Row(Modifier.padding(bottom = 10.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                attachments.forEach { a ->
                    if (a.isImage) Thumb(a.path, 58) { onRemoveAttachment(a) } else FileChip(a.name, onRemove = { onRemoveAttachment(a) })
                }
                if (attach?.busy == true) Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) { io.github.pimobile.ui.components.PulseDot(Ph.Amber, 7) }
            }
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
                        if (text.isEmpty()) Text(if (dictation.listening) "Listening…" else placeholder,
                            style = Ph.body.copy(fontSize = 16.sp, color = if (dictation.listening) Ph.Amber else Ph.Muted))
                        inner()
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dictation.listening) VoiceWave(dictation, Modifier.weight(1f).padding(end = 6.dp))
                else {
                    IconGhost(description = "Commands", onClick = {
                        onValue(TextFieldValue("/", selection = androidx.compose.ui.text.TextRange(1)))
                        focus.requestFocus()
                    }) { Text("/", style = Ph.mono.copy(fontSize = 17.sp, color = Ph.TextSoft)) }
                    if (attach != null) AttachButton(attach)
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
