package io.github.pimobile.ui.state

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Reads replies aloud with the phone's text-to-speech (works offline with most engines). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    /** Key of the reply being read, for the listen/stop toggle. */
    var speaking by mutableStateOf<String?>(null)
        private set

    fun toggle(ctx: Context, key: String, text: String) {
        if (speaking == key) { stop(); return }
        val t = tts ?: TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            pending?.let { (k, s) -> pending = null; if (ready) speak(k, s) else speaking = null }
        }.also { engine ->
            tts = engine
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { if (speaking == id) speaking = null }
                @Deprecated("") override fun onError(id: String) { if (speaking == id) speaking = null }
            })
        }
        speaking = key
        if (ready) speak(key, text) else pending = key to text
    }

    private fun speak(key: String, text: String) {
        val clean = io.github.pimobile.runtime.Notifier.plain(text).take(TextToSpeech.getMaxSpeechInputLength() - 1)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    fun stop() { tts?.stop(); speaking = null }
}
