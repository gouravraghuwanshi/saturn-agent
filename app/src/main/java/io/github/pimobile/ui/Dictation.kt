package io.github.pimobile.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * In-app dictation (no system pop-up). Prefers the on-device recognizer, so
 * speech stays on the phone when the phone has one. Main thread only.
 */
class Dictation(private val ctx: Context) {
    var listening by mutableStateOf(false)
        private set
    /** Mic level 0..1 for the pulse. */
    var level by mutableFloatStateOf(0f)
        private set
    private var rec: SpeechRecognizer? = null

    /** False when the phone has no speech recognizer at all. */
    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))

    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        stopAndRelease()
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onPartialResults(partial: Bundle?) { first(partial).takeIf { it.isNotBlank() }?.let(onPartial) }
            override fun onResults(results: Bundle?) { done(); first(results).let(onFinal) }
            override fun onError(error: Int) {
                done()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_CLIENT -> {}
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Saturn needs microphone permission to listen")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> onError("Speech recognition needs a connection on this phone")
                    else -> onError("Couldn't hear that (error $error)")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listening = true
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
    }

    /** Stop listening; the final result still arrives. */
    fun stop() { rec?.stopListening() }

    private fun done() { listening = false; level = 0f; rec?.destroy(); rec = null }
    fun stopAndRelease() { runCatching { rec?.cancel() }; done() }
}
