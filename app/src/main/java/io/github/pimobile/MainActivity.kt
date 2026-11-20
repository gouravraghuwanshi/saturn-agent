package io.github.pimobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.pimobile.ui.App
import io.github.pimobile.ui.state.UiStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        io.github.pimobile.ui.state.Haptics.view = window.decorView
        UiStore.init(this)
        io.github.pimobile.device.Permissions.attach(this)
        requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        setContent { App() }
        handle(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onStart() { super.onStart(); UiStore.foreground = true }
    override fun onStop() { super.onStop(); UiStore.foreground = false }

    /** Share sheet, notification taps, the widget and the quick-settings tile all land here. */
    private fun handle(i: android.content.Intent?) {
        i ?: return
        i.getStringExtra(EXTRA_OPEN_TASK)?.let { id ->
            io.github.pimobile.runtime.Notifier.clear(this, id)
            UiStore.openRequest = id
        }
        if (i.getBooleanExtra(EXTRA_FOCUS, false)) {
            UiStore.openRequest = HOME
            if (i.getBooleanExtra(EXTRA_VOICE, false)) UiStore.voiceRequest = true else UiStore.focusComposer = true
        }
        if (i.action == android.content.Intent.ACTION_SEND || i.action == android.content.Intent.ACTION_SEND_MULTIPLE) {
            val text = listOfNotNull(i.getStringExtra(android.content.Intent.EXTRA_SUBJECT), i.getStringExtra(android.content.Intent.EXTRA_TEXT))
                .filter { it.isNotBlank() }.distinct().joinToString("\n")
            @Suppress("DEPRECATION")
            val uris = buildList {
                (i.getParcelableExtra<android.os.Parcelable>(android.content.Intent.EXTRA_STREAM) as? android.net.Uri)?.let(::add)
                i.getParcelableArrayListExtra<android.os.Parcelable>(android.content.Intent.EXTRA_STREAM)?.mapNotNull { it as? android.net.Uri }?.let(::addAll)
                i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let(::add) }
            }.distinct()
            if (text.isNotBlank() || uris.isNotEmpty()) {
                UiStore.share = UiStore.Share(text, uris)
                UiStore.openRequest = HOME
            }
            setIntent(android.content.Intent()) // don't re-run on rotation
        }
    }

    companion object {
        const val EXTRA_OPEN_TASK = "saturn.open_task"
        const val EXTRA_FOCUS = "saturn.focus_composer"
        const val EXTRA_VOICE = "saturn.start_voice"
        /** openRequest value meaning "back to the task list". */
        const val HOME = "saturn.home"
    }
}
