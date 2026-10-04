package com.netbypass.assist.voice

import com.netbypass.assist.util.Logger

/**
 * Repeatedly listens with [SpeechToText] until it hears a phrase containing the configured
 * wake word (e.g. "hey assist"). Returns whatever text followed the wake word in the same
 * utterance (so "hey assist what's the weather" is captured in one shot), or an empty string if
 * only the wake word itself was heard.
 *
 * This is a best-effort, battery-hungry approximation of a real always-on wake-word engine
 * (Porcupine/Snowboy-style), built entirely on the standard Android SpeechRecognizer so it needs
 * no extra native libraries or model downloads. See README for notes on swapping in a dedicated
 * offline wake-word engine later.
 */
class WakeWordListener(private val stt: SpeechToText) {

    suspend fun waitForWakeWord(wakeWord: String, isActive: () -> Boolean): String? {
        val needle = wakeWord.trim().lowercase().ifBlank { "hey assist" }
        while (isActive()) {
            val heard = try {
                stt.listenOnce(preferOffline = true, timeoutMs = 8_000)
            } catch (e: Exception) {
                Logger.e("WakeWordListener", "listen error", e)
                null
            } ?: continue

            val lower = heard.lowercase()
            if (lower.contains(needle)) {
                val remainder = lower.substringAfter(needle).trim(' ', ',', '.', '!', '?')
                Logger.i("WakeWordListener", "Wake word detected, remainder='$remainder'")
                return remainder
            }
        }
        return null
    }
}
