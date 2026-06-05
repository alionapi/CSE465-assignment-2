package com.example.pa2.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Wrapper around Android's [SpeechRecognizer] supporting two modes:
 *
 *   1. **One-shot:** [startOneShot]. Captures one utterance and stops. Used
 *      when the user taps the "Voice target" button.
 *
 *   2. **Continuous:** [startContinuous] / [stopContinuous]. Re-arms the
 *      recognizer after every result (or recoverable error) so the user
 *      can issue several voice commands in a row without re-tapping. The
 *      recognizer cycles in ~3-second turns; partial results stream in
 *      between.
 *
 * Continuous mode trades a bit of battery for hands-free UX. We mitigate
 * the worst Google-recognizer quirks (NO_MATCH / SPEECH_TIMEOUT silently
 * killing the session) by treating those as soft errors that just trigger
 * a re-arm after a small delay.
 *
 * All callbacks fire on the main thread because that's where the
 * RecognitionListener lives.
 */
class SpeechController(private val context: Context) {

    interface Callback {
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onError(reason: String) {}
        fun onListeningStarted() {}
        fun onListeningStopped() {}
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** When true, we automatically restart listening after each result/timeout. */
    @Volatile private var continuousMode: Boolean = false
    private var continuousCallback: Callback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rearmRunnable = Runnable { rearm() }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun isContinuous(): Boolean = continuousMode

    /** One-shot listen: captures one utterance, stops, releases the recognizer. */
    fun startOneShot(callback: Callback) {
        if (listening) return
        startInternal(callback, continuous = false)
    }

    /** Continuous listen: keeps cycling until [stopContinuous] is called. */
    fun startContinuous(callback: Callback) {
        if (listening && continuousMode) return
        continuousMode = true
        continuousCallback = callback
        startInternal(callback, continuous = true)
    }

    fun stopContinuous() {
        continuousMode = false
        continuousCallback = null
        mainHandler.removeCallbacks(rearmRunnable)
        try { recognizer?.cancel() } catch (_: Exception) {}
        cleanup()
    }

    /** Stop any current listen, releasing resources. Does NOT exit continuous mode. */
    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        cleanup()
    }

    private fun startInternal(callback: Callback, continuous: Boolean) {
        if (!hasMicPermission()) { callback.onError("Microphone permission denied"); return }
        if (!isAvailable()) { callback.onError("Speech recognition not available"); return }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                callback.onListeningStarted()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback.onListeningStopped() }
            override fun onError(error: Int) {
                listening = false
                callback.onListeningStopped()
                val msg = errorMessage(error)
                callback.onError(msg)
                cleanup()
                if (continuous && continuousMode && isRecoverable(error)) {
                    // Re-arm with a small delay to avoid hammering on repeated NO_MATCH.
                    mainHandler.postDelayed(rearmRunnable, 300L)
                }
            }
            override fun onResults(results: Bundle?) {
                listening = false
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty()
                if (best.isNotBlank()) callback.onFinal(best)
                cleanup()
                if (continuous && continuousMode) {
                    // Restart immediately after a successful result.
                    mainHandler.postDelayed(rearmRunnable, 200L)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(callback::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Tighter silence windows so continuous mode cycles fast.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }

        try { sr.startListening(intent) }
        catch (e: Exception) {
            Log.e("SpeechController", "startListening failed", e)
            callback.onError("Failed to start speech recognition")
            cleanup()
            if (continuous && continuousMode) mainHandler.postDelayed(rearmRunnable, 500L)
        }
    }

    private fun rearm() {
        if (!continuousMode) return
        val cb = continuousCallback ?: return
        startInternal(cb, continuous = true)
    }

    private fun cleanup() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listening = false
    }

    /** Errors we can swallow and retry on in continuous mode. */
    private fun isRecoverable(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK -> true
        else -> false
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Speech error ($code)"
    }
}
