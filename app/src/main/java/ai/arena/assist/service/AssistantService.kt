package ai.arena.assist.service

import ai.arena.assist.MainActivity
import ai.arena.assist.R
import ai.arena.assist.agent.ConversationEngine
import ai.arena.assist.data.SettingsRepository
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class AssistantService : Service(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: ConversationEngine
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var shouldListen = false
    private var recognitionActive = false
    private var processing = false
    private var awaitingFollowup = false
    private var afterSpeech: (() -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        engine = ConversationEngine(this)
        createNotificationChannel()
        startForegroundSafely(includeMicrophone = false)
        initializeTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                settingsRepository.setAlwaysListen(false)
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (shouldListen) {
                    settingsRepository.setAlwaysListen(false)
                    stopListening()
                } else {
                    settingsRepository.setAlwaysListen(true)
                    startListening()
                }
            }
            ACTION_BOOT -> {
                shouldListen = settingsRepository.load().alwaysListen
                if (shouldListen) handler.postDelayed({ startListening() }, 2_000)
            }
            else -> {
                shouldListen = settingsRepository.load().alwaysListen
                if (shouldListen) startListening()
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun startListening() {
        shouldListen = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendEvent(AssistEvents.TYPE_STATUS, "Microphone permission is required")
            shouldListen = false
            updateNotification("Open Assist to grant microphone permission")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendEvent(AssistEvents.TYPE_STATUS, "No Android speech recognition service is installed")
            shouldListen = false
            updateNotification("Speech recognition is unavailable")
            return
        }
        if (!startForegroundSafely(includeMicrophone = true)) {
            shouldListen = false
            updateNotification("Tap to activate the microphone after boot")
            return
        }
        acquireWakeLock()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
            }
        }
        scheduleRecognition(100)
        updateNotification("Listening for “Hey Assist”")
    }

    private fun stopListening() {
        shouldListen = false
        recognitionActive = false
        handler.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        releaseWakeLock()
        sendEvent(AssistEvents.TYPE_STATUS, "Voice listener paused")
        updateNotification("Voice listener paused")
    }

    private fun scheduleRecognition(delayMs: Long = 800) {
        if (!shouldListen || processing || recognitionActive) return
        handler.removeCallbacks(restartRecognition)
        handler.postDelayed(restartRecognition, delayMs)
    }

    private val restartRecognition = Runnable {
        if (!shouldListen || processing || recognitionActive) return@Runnable
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching {
            recognitionActive = true
            recognizer?.startListening(intent)
        }.onFailure {
            recognitionActive = false
            sendEvent(AssistEvents.TYPE_STATUS, "Speech recognizer failed: ${it.message.orEmpty()}")
            scheduleRecognition(1_500)
        }
    }

    override fun onResults(results: Bundle?) {
        recognitionActive = false
        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty().trim()
        if (phrase.isBlank()) {
            scheduleRecognition()
            return
        }
        handleRecognizedPhrase(phrase)
    }

    private fun handleRecognizedPhrase(phrase: String) {
        val settings = settingsRepository.load()
        val command = when {
            !settings.requireWakePhrase -> phrase
            awaitingFollowup -> phrase.also { awaitingFollowup = false }
            else -> extractWakeCommand(phrase)
        }
        if (command == null) {
            scheduleRecognition(250)
            return
        }
        if (command.isBlank()) {
            awaitingFollowup = true
            speak("Yes?") { scheduleRecognition() }
            return
        }

        processing = true
        runCatching { recognizer?.cancel() }
        sendEvent(AssistEvents.TYPE_USER, command)
        updateNotification("Working on: ${command.take(60)}")
        engine.ask(command, object : ConversationEngine.Listener {
            override fun onStatus(message: String) {
                sendEvent(AssistEvents.TYPE_STATUS, message)
            }

            override fun onTool(name: String) {
                sendEvent(AssistEvents.TYPE_STATUS, "Using $name…")
            }

            override fun onAnswer(text: String) {
                handler.post {
                    processing = false
                    sendEvent(AssistEvents.TYPE_ASSISTANT, text)
                    if (settingsRepository.load().speakResponses) {
                        speak(text) { scheduleRecognition(400) }
                    } else {
                        scheduleRecognition(400)
                    }
                    updateNotification("Listening for “Hey Assist”")
                }
            }

            override fun onError(message: String) {
                handler.post {
                    processing = false
                    val friendly = "I couldn't complete that. $message"
                    sendEvent(AssistEvents.TYPE_ASSISTANT, friendly)
                    if (settingsRepository.load().speakResponses) {
                        speak(friendly) { scheduleRecognition(800) }
                    } else {
                        scheduleRecognition(800)
                    }
                    updateNotification("Assistant request failed")
                }
            }
        })
    }

    private fun extractWakeCommand(phrase: String): String? {
        val match = WAKE_PATTERN.find(phrase.trim()) ?: return null
        return match.groupValues.getOrElse(2) { "" }.trim()
    }

    private fun initializeTts() {
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onError(utteranceId: String?) = completeSpeech()
                    override fun onDone(utteranceId: String?) = completeSpeech()
                })
            }
        }
    }

    private fun speak(text: String, onComplete: () -> Unit) {
        recognitionActive = false
        runCatching { recognizer?.cancel() }
        if (!ttsReady) {
            onComplete()
            return
        }
        afterSpeech = onComplete
        tts?.speak(text.take(3_500), TextToSpeech.QUEUE_FLUSH, null, "assist-${System.nanoTime()}")
    }

    private fun completeSpeech() {
        handler.post {
            val callback = afterSpeech
            afterSpeech = null
            callback?.invoke()
        }
    }

    override fun onError(error: Int) {
        recognitionActive = false
        if (!shouldListen || processing) return
        if (error !in setOf(
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_CLIENT
            )
        ) {
            sendEvent(AssistEvents.TYPE_STATUS, "Speech recognition error $error; retrying")
        }
        scheduleRecognition(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2_000 else 800)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        sendEvent(AssistEvents.TYPE_STATUS, "Listening…")
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { recognitionActive = false }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun sendEvent(type: String, text: String) {
        sendBroadcast(Intent(AssistEvents.ACTION_EVENT).apply {
            setPackage(packageName)
            putExtra(AssistEvents.EXTRA_TYPE, type)
            putExtra(AssistEvents.EXTRA_TEXT, text)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafely(includeMicrophone: Boolean): Boolean {
        return runCatching {
            val type = when {
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                Build.VERSION.SDK_INT >= 30 && includeMicrophone -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        }.getOrElse {
            sendEvent(AssistEvents.TYPE_STATUS, "Android blocked background microphone start; open Assist and tap Start voice")
            false
        }
    }

    private fun updateNotification(message: String? = null) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message)
        )
    }

    private fun buildNotification(message: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AssistantService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, AssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_assist)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(message ?: if (shouldListen) "Listening for “Hey Assist”" else "Voice listener paused")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, if (shouldListen) "Pause" else "Listen", toggleIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:voice-listener")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stopSelfCleanly() {
        stopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shouldListen = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        engine.close()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "ai.arena.assist.action.START"
        const val ACTION_BOOT = "ai.arena.assist.action.BOOT"
        const val ACTION_STOP = "ai.arena.assist.action.STOP"
        const val ACTION_TOGGLE = "ai.arena.assist.action.TOGGLE"
        private const val CHANNEL_ID = "assist_voice"
        private const val NOTIFICATION_ID = 4107
        private val WAKE_PATTERN = Regex(
            "^(?:(?:hey|hi|ok|okay)\\s+)?(assist|assistant)\\b[\\s,:-]*(.*)$",
            RegexOption.IGNORE_CASE
        )
    }
}
