package io.github.pimobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.pimobile.R
import io.github.pimobile.runtime.TaskManager
import io.github.pimobile.ui.state.TaskUi
import io.github.pimobile.ui.state.UiStore
import io.github.pimobile.ui.theme.Ph
import java.util.concurrent.Executors

private val io = Executors.newSingleThreadExecutor()
private val main = android.os.Handler(android.os.Looper.getMainLooper())

/**
 * The task's session in pi's real TUI, in a PTY. While it is open the RPC
 * process is stopped; on close the chat view re-reads the session.
 */
@Composable
fun TerminalPane(t: TaskUi) {
    val ctx = LocalContext.current
    var generation by remember { mutableIntStateOf(0) } // bump to restart pi
    var exited by remember { mutableStateOf<Int?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var textSize by remember { mutableIntStateOf((11 * ctx.resources.displayMetrics.scaledDensity).toInt()) }

    val view = remember {
        TerminalView(ctx, null).apply {
            isFocusable = true; isFocusableInTouchMode = true
            setBackgroundColor(Ph.Deep.toArgb())
            setTextSize(textSize) // creates the renderer; setTypeface() needs it
            ResourcesCompat.getFont(ctx, R.font.jetbrains_mono)?.let { setTypeface(it) }
            keepScreenOn = true
        }
    }

    fun showKeyboard() {
        view.requestFocus()
        ctx.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    val sessionClient = remember {
        object : TerminalSessionClient {
            override fun onTextChanged(s: TerminalSession) = view.onScreenUpdated()
            override fun onTitleChanged(s: TerminalSession) {}
            override fun onSessionFinished(s: TerminalSession) { exited = s.exitStatus }
            override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("pi", text))
            }
            override fun onPasteTextFromClipboard(s: TerminalSession?) {
                val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                clip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { s?.emulator?.paste(it) }
            }
            override fun onBell(s: TerminalSession) {}
            override fun onColorsChanged(s: TerminalSession) = view.invalidate()
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    val d = ctx.resources.displayMetrics.scaledDensity
                    textSize = (textSize * scale).toInt().coerceIn((8 * d).toInt(), (24 * d).toInt())
                    view.setTextSize(textSize)
                    return 1f
                }
                return scale
            }
            override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = true
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
            override fun onLongPress(event: MotionEvent?) = false
            override fun readControlKey() = ctrl.also { if (it) ctrl = false }
            override fun readAltKey() = alt.also { if (it) alt = false }
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
            override fun onEmulatorSet() {
                // Phosphor palette on top of the standard 256 colors.
                view.mEmulator?.mColors?.mCurrentColors?.let { c ->
                    c[TextStyle.COLOR_INDEX_BACKGROUND] = Ph.Deep.toArgb()
                    c[TextStyle.COLOR_INDEX_FOREGROUND] = Ph.Text.toArgb()
                    c[TextStyle.COLOR_INDEX_CURSOR] = Ph.Amber.toArgb()
                }
            }
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
    }

    // Start pi's TUI on this task's session (again on Restart)...
    val holder = remember { arrayOfNulls<TerminalSession>(1) }
    DisposableEffect(generation) {
        exited = null
        io.execute {
            stopAndWait(holder[0])
            TaskManager.releaseForTerminal(t.task) // one pi process per session at a time
            val rt = UiStore.runtime
            rt.prepare()
            val env = (rt.env() + TaskManager.envFor(t.task)).map { (k, v) -> "$k=$v" }.toTypedArray()
            view.post {
                val s = TerminalSession(rt.node.path, t.task.cwd.path,
                    arrayOf("pi", rt.piCli.path, "--session-id", t.task.id), env, 3000, sessionClient)
                holder[0] = s
                view.setTerminalViewClient(viewClient)
                view.attachSession(s)
                showKeyboard()
            }
        }
        onDispose {}
    }
    // ...and when leaving the tab, hand the session back to chat only after the TUI is gone.
    DisposableEffect(Unit) {
        onDispose {
            ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
            val s = holder[0]
            io.execute {
                stopAndWait(s)
                main.post { UiStore.terminalClosed(t) } // the view may be detached by now
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Ph.Deep)) {
            AndroidView({ view }, Modifier.fillMaxSize().padding(horizontal = 6.dp))
            exited?.let { code ->
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Ph.Surface)
                    .border(1.dp, Ph.LineStrong, RoundedCornerShape(16.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("pi exited ($code)", style = Ph.title)
                    Text("Restart", style = Ph.title.copy(color = Ph.AmberInk), textAlign = TextAlign.Center,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Ph.Amber)
                            .clickable { generation++ }.padding(horizontal = 18.dp, vertical = 10.dp))
                }
            }
        }
        ExtraKeys(
            ctrl = ctrl, alt = alt,
            onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt },
            onKey = { code -> view.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)) },
            onText = { s -> view.mTermSession?.write(s) },
        )
    }
}

private data class XKey(val label: String, val description: String, val code: Int = 0, val text: String? = null)

private val ROW1 = listOf(
    XKey("esc", "Escape", KeyEvent.KEYCODE_ESCAPE), XKey("tab", "Tab", KeyEvent.KEYCODE_TAB),
    XKey("ctrl", "Control"), XKey("alt", "Alt"), XKey("/", "Slash", text = "/"), XKey("@", "At sign", text = "@"),
    XKey("↑", "Up", KeyEvent.KEYCODE_DPAD_UP), XKey("⏎", "Enter", KeyEvent.KEYCODE_ENTER),
)
private val ROW2 = listOf(
    XKey("home", "Home", KeyEvent.KEYCODE_MOVE_HOME), XKey("end", "End", KeyEvent.KEYCODE_MOVE_END),
    XKey("pgup", "Page up", KeyEvent.KEYCODE_PAGE_UP), XKey("pgdn", "Page down", KeyEvent.KEYCODE_PAGE_DOWN),
    XKey("|", "Pipe", text = "|"), XKey("←", "Left", KeyEvent.KEYCODE_DPAD_LEFT),
    XKey("↓", "Down", KeyEvent.KEYCODE_DPAD_DOWN), XKey("→", "Right", KeyEvent.KEYCODE_DPAD_RIGHT),
)

@Composable
private fun ExtraKeys(ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit,
                      onKey: (Int) -> Unit, onText: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Ph.Raised).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ROW1, ROW2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val on = (k.label == "ctrl" && ctrl) || (k.label == "alt" && alt)
                    Box(
                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (on) Ph.AmberWash else Ph.Surface)
                            .border(1.dp, if (on) Ph.AmberLine else Ph.Line, RoundedCornerShape(10.dp))
                            .clickable(onClickLabel = k.description) {
                                when {
                                    k.label == "ctrl" -> onCtrl()
                                    k.label == "alt" -> onAlt()
                                    k.text != null -> onText(k.text)
                                    else -> onKey(k.code)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(k.label, style = Ph.mono.copy(color = if (on) Ph.Amber else Ph.TextSoft)) }
                }
            }
        }
    }
}

/** SIGTERM (pi shuts down cleanly; its session is already on disk), then SIGKILL after 2 s. */
private fun stopAndWait(s: TerminalSession?) {
    s ?: return
    if (s.isRunning) android.os.Process.sendSignal(s.pid, 15)
    repeat(20) { if (!s.isRunning) return; Thread.sleep(100) }
    s.finishIfRunning()
}
