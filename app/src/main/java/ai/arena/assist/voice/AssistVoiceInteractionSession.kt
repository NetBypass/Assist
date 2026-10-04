package ai.arena.assist.voice

import ai.arena.assist.agent.ConversationEngine
import ai.arena.assist.data.SettingsRepository
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

class AssistVoiceInteractionSession(private val appContext: Context) :
    VoiceInteractionSession(appContext), RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private val engine = ConversationEngine(appContext)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
        }
    }

    override fun onCreateContentView(): View {
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(23, 27, 37))
                cornerRadius = dp(24).toFloat()
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        panel.addView(TextView(appContext).apply {
            text = "Assist"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        progress = ProgressBar(appContext).apply { isIndeterminate = true }
        panel.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            topMargin = dp(14)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        statusView = TextView(appContext).apply {
            text = "Listening…"
            textSize = 16f
            setTextColor(Color.rgb(220, 225, 235))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        panel.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(Button(appContext).apply {
            text = "Close"
            setOnClickListener { hide() }
        })
        return panel
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startRecognition()
    }

    private fun startRecognition() {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showStatus("Open Assist once and grant microphone permission.", false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            showStatus("No speech recognition service is installed.", false)
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
            }
        }
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        })
        showStatus("Listening…", true)
    }

    override fun onResults(results: Bundle?) {
        val request = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()
        if (request.isBlank()) {
            showStatus("I didn't catch that. Try again.", false)
            handler.postDelayed(::startRecognition, 800)
            return
        }
        showStatus(request, true)
        engine.ask(request, object : ConversationEngine.Listener {
            override fun onStatus(message: String) = handler.post { showStatus(message, true) }
            override fun onTool(name: String) = handler.post { showStatus("Using $name…", true) }
            override fun onAnswer(text: String) = handler.post {
                showStatus(text, false)
                if (SettingsRepository(appContext).load().speakResponses) {
                    tts?.speak(text.take(3_500), TextToSpeech.QUEUE_FLUSH, null, "assist-session")
                }
            }
            override fun onError(message: String) = handler.post {
                showStatus("Request failed: $message", false)
            }
        })
    }

    override fun onError(error: Int) {
        showStatus("I didn't catch that. Tap the assistant gesture to try again.", false)
    }

    private fun showStatus(text: String, busy: Boolean) {
        if (::statusView.isInitialized) statusView.text = text
        if (::progress.isInitialized) progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (partial.isNotBlank()) showStatus(partial, true)
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onHide() {
        recognizer?.cancel()
        super.onHide()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        engine.close()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
