package ai.arena.assist.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/** System entry point when Assist is selected as Android's default assistant. */
class AssistVoiceInteractionService : VoiceInteractionService() {
    override fun onLaunchVoiceAssistFromKeyguard() {
        showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
    }
}
