package com.netbypass.assist.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.netbypass.assist.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Thin coroutine wrapper around Android's built-in [SpeechRecognizer]. Using the platform
 * recognizer keeps the app free of large bundled offline models (which this build environment
 * has no way to download anyway) while still working fully offline on devices whose
 * RecognitionService supports on-device recognition.
 */
class SpeechToText(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Listens for a single utterance and returns the best-guess transcript, or null on
     * silence/timeout/error. Must be (and is) dispatched to the main thread internally since
     * [SpeechRecognizer] requires a Looper thread.
     */
    suspend fun listenOnce(preferOffline: Boolean = true, timeoutMs: Long = 12_000): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                if (!isAvailable()) {
                    Logger.w("SpeechToText", "SpeechRecognizer not available on this device/ROM")
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }

                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                val handler = Handler(Looper.getMainLooper())
                var finished = false

                fun finish(text: String?) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    try { recognizer.stopListening() } catch (_: Exception) { }
                    try { recognizer.destroy() } catch (_: Exception) { }
                    if (cont.isActive) cont.resume(text)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Logger.d("SpeechToText", "recognizer error code=$error")
                        finish(null)
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        finish(list?.firstOrNull { it.isNotBlank() })
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                cont.invokeOnCancellation { finish(null) }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                }

                try {
                    recognizer.startListening(intent)
                } catch (e: Exception) {
                    Logger.e("SpeechToText", "startListening failed", e)
                    finish(null)
                    return@suspendCancellableCoroutine
                }

                handler.postDelayed({ finish(null) }, timeoutMs)
            }
        }
}
