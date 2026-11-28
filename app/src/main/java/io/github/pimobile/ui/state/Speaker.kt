package io.github.pimobile.ui.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pimobile.device.Voice
import kotlin.concurrent.thread

/** Read aloud on replies, with the phone's own voice (see [Voice]). */
object Speaker {
    /** Key of the reply being read, for the listen/stop toggle. */
    var speaking by mutableStateOf<String?>(null)
        private set

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun toggle(ctx: Context, key: String, text: String) {
        if (speaking == key) { stop(); return }
        Voice.init(ctx)
        Voice.stop()
        speaking = key
        val clean = io.github.pimobile.runtime.Notifier.plain(text)
        thread(name = "read-aloud") {
            runCatching { Voice.speakBlocking(clean) }
            main.post { if (speaking == key) speaking = null }
        }
    }

    fun stop() { Voice.stop(); speaking = null }
}
