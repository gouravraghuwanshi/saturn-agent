package io.github.pimobile.device

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How Saturn speaks: the phone's own text-to-speech (Google's or Samsung's engine, whichever the phone uses),
 * with its best offline voice for the phone's language. Used for Read aloud on replies and the agent's
 * termux-tts-speak. The engine's voice and language are chosen in Android's settings ([openSystemSettings]).
 */
object Voice {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("voice", Context.MODE_PRIVATE) }

    var speed by mutableStateOf(1f)
        private set

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        speed = prefs.getFloat("speed", 1f)
        // Test builds briefly had an on-device neural voice (~140 MB download); it's gone, so is its download.
        Thread { runCatching { File(app.filesDir, "voices").deleteRecursively() } }.start()
    }

    fun pickSpeed(s: Float) { speed = s.coerceIn(0.5f, 2f); prefs.edit().putFloat("speed", speed).apply() }

    /** Android's text-to-speech settings: which engine, which voice, its language. */
    fun openSystemSettings(ctx: Context) {
        runCatching { ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @Volatile private var turn = 0
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waits = mutableMapOf<String, CountDownLatch>()

    /** Stops whatever is being said. */
    fun stop() { turn++; tts?.stop() }

    /** Says [text] and blocks until it's done or [stop] is called. Call off the main thread. */
    fun speakBlocking(text: String, rate: Float? = null) {
        val clean = text.trim().ifEmpty { return }
        val my = ++turn
        val t = engine()
        val id = "v$my"
        val latch = CountDownLatch(1)
        synchronized(waits) { waits[id] = latch }
        t.setSpeechRate(rate ?: speed)
        // Long replies go in pieces under the engine's limit, queued one after another.
        val parts = clean.chunked(TextToSpeech.getMaxSpeechInputLength() - 1)
        parts.forEachIndexed { i, p ->
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) id else "$id-$i")
        }
        while (turn == my && !latch.await(200, TimeUnit.MILLISECONDS)) {}
        synchronized(waits) { waits.remove(id) }
    }

    @Synchronized private fun engine(): TextToSpeech {
        tts?.let { if (ready) return it }
        val done = CountDownLatch(1)
        var e: TextToSpeech? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            e = TextToSpeech(app) { ready = it == TextToSpeech.SUCCESS; done.countDown() }
        }
        done.await(10, TimeUnit.SECONDS)
        val engine = e
        if (!ready || engine == null) { tts = null; throw Device.Fail("No text-to-speech engine available") }
        bestVoice(engine)?.let { engine.voice = it }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { synchronized(waits) { waits.remove(id) }?.countDown() }
            @Deprecated("") override fun onError(id: String) { synchronized(waits) { waits.remove(id) }?.countDown() }
        })
        tts = engine
        return engine
    }

    /** The best offline voice in the phone's language (engines often default to a plainer one). */
    private fun bestVoice(e: TextToSpeech): android.speech.tts.Voice? = runCatching {
        val lang = java.util.Locale.getDefault().language
        e.voices?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            ?.maxByOrNull { it.quality - it.latency / 100 }
    }.getOrNull()
}
