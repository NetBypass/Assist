package com.netbypass.assist.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.netbypass.assist.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Coroutine wrapper around [TextToSpeech] so the rest of the app can simply `speak(text)` and
 * move on once the device has finished talking (needed so we don't start listening for the next
 * command while Assist is still speaking over itself). All engine interaction happens on the
 * main thread, since [TextToSpeech] relies on a Looper being present on the creating thread.
 */
class TextToSpeechManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val appContext = context.applicationContext

    suspend fun init(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var resumed = false
            tts = TextToSpeech(appContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(1.0f)
                } else {
                    Logger.e("TTS", "TextToSpeech init failed, status=$status")
                }
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ready)
                }
            }
        }
    }

    fun isReady(): Boolean = ready

    suspend fun speak(text: String) {
        val engine = tts
        if (!ready || engine == null || text.isBlank()) return
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { cont ->
                val utteranceId = UUID.randomUUID().toString()
                var resumed = false
                fun finish() {
                    if (resumed) return
                    resumed = true
                    if (cont.isActive) cont.resume(Unit)
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = finish()
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finish()
                    override fun onError(utteranceId: String?, errorCode: Int) = finish()
                })
                cont.invokeOnCancellation {
                    try { engine.stop() } catch (_: Exception) { }
                }
                val params = Bundle()
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                if (result == TextToSpeech.ERROR) finish()
            }
        }
    }

    fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) { }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
