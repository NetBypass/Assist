package com.netbypass.assist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.netbypass.assist.MainActivity
import com.netbypass.assist.R
import com.netbypass.assist.SettingsStore
import com.netbypass.assist.ai.ConversationManager
import com.netbypass.assist.tools.RootShell
import com.netbypass.assist.tools.ToolRegistry
import com.netbypass.assist.util.Logger
import com.netbypass.assist.voice.SpeechToText
import com.netbypass.assist.voice.TextToSpeechManager
import com.netbypass.assist.voice.WakeWordListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The always-on heart of the assistant: a foreground "microphone" service that loops
 * wake-word -> listen -> ask the AI -> run tools -> speak, forever, until stopped. Designed to
 * survive with the screen off (headless / smart-speaker mode) thanks to a partial wake lock and
 * being whitelisted from battery optimizations (see [ToolRegistry.toolSetHeadlessMode] / the
 * in-app "Disable battery optimization" button).
 */
class AssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    @Volatile private var running = false

    private lateinit var settings: SettingsStore
    private lateinit var rootShell: RootShell
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var stt: SpeechToText
    private lateinit var tts: TextToSpeechManager
    private lateinit var wakeWordListener: WakeWordListener
    private lateinit var conversation: ConversationManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        rootShell = RootShell()
        toolRegistry = ToolRegistry(applicationContext, rootShell)
        stt = SpeechToText(applicationContext)
        tts = TextToSpeechManager(applicationContext)
        wakeWordListener = WakeWordListener(stt)
        conversation = ConversationManager(settings, toolRegistry) { question -> confirmByVoice(question) }

        createChannel()
        startForegroundCompat("Starting…")
        acquireWakeLock()

        scope.launch(Dispatchers.IO) { rootShell.open() }

        settings.serviceEnabled = true
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob == null || loopJob?.isActive != true) startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        running = true
        loopJob = scope.launch {
            val ttsReady = tts.init()
            if (!ttsReady) Logger.w("AssistantService", "TTS failed to initialize")

            if (!stt.isAvailable()) {
                Logger.e("AssistantService", "No speech recognizer available on this device/ROM")
                updateNotification("No speech recognizer available on this device")
                return@launch
            }

            if (ContextCompat.checkSelfPermission(this@AssistantService, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Logger.e("AssistantService", "RECORD_AUDIO permission not granted; stopping")
                updateNotification("Microphone permission not granted")
                return@launch
            }

            tts.speak("Assist is now listening for ${settings.wakeWord}")

            while (running) {
                try {
                    runCycle()
                } catch (e: Exception) {
                    Logger.e("AssistantService", "Cycle crashed, recovering", e)
                }
            }
        }
    }

    private suspend fun runCycle() {
        updateNotification("Listening for \"${settings.wakeWord}\"…")
        val remainder = wakeWordListener.waitForWakeWord(settings.wakeWord) { running } ?: run {
            running = false
            return
        }

        val commandText = if (remainder.isNotBlank()) {
            remainder
        } else {
            updateNotification("Listening…")
            tts.speak("Yes?")
            stt.listenOnce(timeoutMs = 8_000)
        }

        if (commandText.isNullOrBlank()) return

        Logger.i("AssistantService", "User said: $commandText")
        updateNotification("Thinking…")
        val reply = conversation.handleUserUtterance(commandText)
        Logger.i("AssistantService", "Assist replied: $reply")
        updateNotification("Speaking…")
        tts.speak(reply)
    }

    private suspend fun confirmByVoice(question: String): Boolean {
        updateNotification("Waiting for confirmation…")
        tts.speak(question)
        val answer = stt.listenOnce(timeoutMs = 7_000) ?: return false
        val yes = Regex("\\b(yes|yeah|yep|confirm|sure|go ahead|do it|affirmative)\\b", RegexOption.IGNORE_CASE)
        return yes.containsMatchIn(answer)
    }

    override fun onDestroy() {
        running = false
        settings.serviceEnabled = false
        loopJob?.cancel()
        scope.cancel()
        tts.shutdown()
        rootShell.close()
        releaseWakeLock()
        super.onDestroy()
    }

    // ---- Foreground notification plumbing ------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Assist::ServiceWakeLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12h safety cap, renewed by service restarts
            }
        } catch (e: Exception) {
            Logger.e("AssistantService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "assist_service_channel"
        private const val NOTIF_ID = 42
    }
}
