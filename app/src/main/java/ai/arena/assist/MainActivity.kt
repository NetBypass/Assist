package ai.arena.assist

import ai.arena.assist.agent.ConversationEngine
import ai.arena.assist.api.EndpointNormalizer
import ai.arena.assist.api.OpenAiClient
import ai.arena.assist.data.SettingsRepository
import ai.arena.assist.root.RootManager
import ai.arena.assist.service.AssistEvents
import ai.arena.assist.service.AssistantService
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var engine: ConversationEngine
    private val background = Executors.newCachedThreadPool { task ->
        Thread(task, "assist-ui-work").apply { isDaemon = true }
    }

    private lateinit var endpointInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: AutoCompleteTextView
    private lateinit var transcript: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusDot: android.view.View
    private lateinit var rootStatus: TextView
    private lateinit var alwaysListenSwitch: MaterialSwitch

    private var receiverRegistered = false

    private val serviceEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra(AssistEvents.EXTRA_TYPE).orEmpty()
            val text = intent?.getStringExtra(AssistEvents.EXTRA_TEXT).orEmpty()
            when (type) {
                AssistEvents.TYPE_USER -> appendTranscript("You", text)
                AssistEvents.TYPE_ASSISTANT -> appendTranscript("Assist", text)
                AssistEvents.TYPE_STATUS -> setStatus(text, true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsRepository = SettingsRepository(this)
        engine = ConversationEngine(this)
        bindViews()
        populateSettings()
        bindActions()
        checkRootStatus()
    }

    private fun bindViews() {
        endpointInput = findViewById(R.id.endpointInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelInput = findViewById(R.id.modelInput)
        transcript = findViewById(R.id.transcriptText)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        rootStatus = findViewById(R.id.rootStatusText)
        alwaysListenSwitch = findViewById(R.id.alwaysListenSwitch)
        transcript.movementMethod = ScrollingMovementMethod()
    }

    private fun populateSettings() {
        val settings = settingsRepository.load()
        endpointInput.setText(settings.endpoint)
        apiKeyInput.setText(settings.apiKey)
        modelInput.setText(settings.model, false)
        alwaysListenSwitch.isChecked = settings.alwaysListen
        findViewById<MaterialSwitch>(R.id.wakePhraseSwitch).isChecked = settings.requireWakePhrase
        findViewById<MaterialSwitch>(R.id.speakSwitch).isChecked = settings.speakResponses
        findViewById<MaterialSwitch>(R.id.bootSwitch).isChecked = settings.startOnBoot
        findViewById<MaterialSwitch>(R.id.rootToolsSwitch).isChecked = settings.rootToolsEnabled
        setStatus(if (settings.endpoint.isBlank()) "Needs API setup" else "Ready", settings.endpoint.isNotBlank())
    }

    private fun bindActions() {
        findViewById<Button>(R.id.saveConnectionButton).setOnClickListener { saveConnection(showToast = true) }
        findViewById<Button>(R.id.testConnectionButton).setOnClickListener { testConnection() }
        sendButton.setOnClickListener { sendMessage() }
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        findViewById<MaterialSwitch>(R.id.wakePhraseSwitch).setOnCheckedChangeListener { _, checked ->
            settingsRepository.setRequireWakePhrase(checked)
        }
        findViewById<MaterialSwitch>(R.id.speakSwitch).setOnCheckedChangeListener { _, checked ->
            settingsRepository.setSpeakResponses(checked)
        }
        findViewById<MaterialSwitch>(R.id.bootSwitch).setOnCheckedChangeListener { _, checked ->
            settingsRepository.setStartOnBoot(checked)
        }
        alwaysListenSwitch.setOnCheckedChangeListener { _, checked ->
            settingsRepository.setAlwaysListen(checked)
            if (checked) {
                ensureVoicePermissionsAndStart()
            } else {
                startService(
                    Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)
                )
            }
        }
        findViewById<MaterialSwitch>(R.id.rootToolsSwitch).setOnCheckedChangeListener { switch, checked ->
            if (checked) {
                AlertDialog.Builder(this)
                    .setTitle("Allow unrestricted root tools?")
                    .setMessage("The configured AI endpoint will be able to run commands as root, read and write files, control apps and input, and power off the device. Only continue if you trust the endpoint and model.")
                    .setPositiveButton("Allow") { _, _ -> settingsRepository.setRootToolsEnabled(true) }
                    .setNegativeButton("Cancel") { _, _ -> switch.isChecked = false }
                    .setOnCancelListener { switch.isChecked = false }
                    .show()
            } else {
                settingsRepository.setRootToolsEnabled(false)
            }
        }

        findViewById<Button>(R.id.startVoiceButton).setOnClickListener {
            if (!alwaysListenSwitch.isChecked) alwaysListenSwitch.isChecked = true
            else ensureVoicePermissionsAndStart()
        }
        findViewById<Button>(R.id.stopVoiceButton).setOnClickListener {
            alwaysListenSwitch.isChecked = false
            startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_STOP))
            setStatus("Voice service stopped", true)
        }
        findViewById<Button>(R.id.configureRootButton).setOnClickListener { confirmRootSetup() }
    }

    private fun saveConnection(showToast: Boolean): Boolean {
        return runCatching {
            val endpoint = endpointInput.text.toString().trim()
            EndpointNormalizer.chatCompletionsUrl(endpoint)
            settingsRepository.saveConnection(
                endpoint,
                apiKeyInput.text.toString(),
                modelInput.text.toString()
            )
            if (showToast) Toast.makeText(this, "Connection settings saved", Toast.LENGTH_SHORT).show()
            setStatus("Ready", true)
            true
        }.getOrElse {
            showError(it.message ?: "Could not save settings")
            false
        }
    }

    private fun testConnection() {
        if (!saveConnection(showToast = false)) return
        val button = findViewById<Button>(R.id.testConnectionButton)
        button.isEnabled = false
        button.text = "Connecting…"
        setStatus("Testing endpoint…", true)
        val settings = settingsRepository.load()
        background.execute {
            runCatching { OpenAiClient().listModels(settings.endpoint, settings.apiKey) }
                .onSuccess { models ->
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = "Test & load models"
                        val choices = models.ifEmpty { listOf(settings.model) }
                        modelInput.setAdapter(
                            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, choices)
                        )
                        if (models.isNotEmpty() && modelInput.text.toString() !in models) {
                            modelInput.setText(models.first(), false)
                            settingsRepository.saveConnection(
                                endpointInput.text.toString(),
                                apiKeyInput.text.toString(),
                                models.first()
                            )
                        }
                        setStatus("Connected · ${models.size} models", true)
                        Toast.makeText(this, "Endpoint connected", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        button.isEnabled = true
                        button.text = "Test & load models"
                        setStatus("Connection failed", false)
                        showError(error.message ?: "Connection failed")
                    }
                }
        }
    }

    private fun sendMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isBlank()) return
        if (!saveConnection(showToast = false)) return
        messageInput.text?.clear()
        appendTranscript("You", message)
        sendButton.isEnabled = false
        engine.ask(message, object : ConversationEngine.Listener {
            override fun onStatus(message: String) = runOnUiThread { setStatus(message, true) }
            override fun onTool(name: String) = runOnUiThread {
                appendTranscript("Tool", name)
                setStatus("Using $name…", true)
            }
            override fun onAnswer(text: String) = runOnUiThread {
                appendTranscript("Assist", text)
                sendButton.isEnabled = true
                setStatus("Ready", true)
            }
            override fun onError(message: String) = runOnUiThread {
                appendTranscript("Error", message)
                sendButton.isEnabled = true
                setStatus("Request failed", false)
            }
        })
    }

    private fun ensureVoicePermissionsAndStart() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_VOICE_PERMISSIONS)
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_START)
        )
        setStatus("Starting voice listener…", true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_VOICE_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            ensureVoicePermissionsAndStart()
        } else {
            alwaysListenSwitch.isChecked = false
            showError("Microphone and notification permissions are required for headless voice mode")
        }
    }

    private fun checkRootStatus() {
        background.execute {
            val rooted = RootManager.isRootAvailable()
            runOnUiThread {
                rootStatus.text = if (rooted) {
                    "Root access granted. AI tools remain locked until you enable the switch below."
                } else {
                    "No su grant detected. Install/enable Magisk or KernelSU, then approve Assist."
                }
            }
        }
    }

    private fun confirmRootSetup() {
        AlertDialog.Builder(this)
            .setTitle("Configure headless root mode")
            .setMessage("Assist will request su to grant microphone/notification settings, whitelist itself from Doze, allow background operation, and become the default Android assistant. You can reverse these in Android Settings.")
            .setPositiveButton("Configure") { _, _ -> configureRoot() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun configureRoot() {
        rootStatus.text = "Waiting for root approval…"
        background.execute {
            val result = RootManager.configureHeadless(this)
            runOnUiThread {
                rootStatus.text = if (result.successful) {
                    "Headless mode configured. Android may still ask you to confirm the default assistant."
                } else {
                    "Root setup failed: ${result.output.take(400)}"
                }
                checkRootStatus()
            }
        }
    }

    private fun appendTranscript(label: String, text: String) {
        val prefix = if (transcript.text.isNullOrBlank()) "" else "\n\n"
        transcript.append("$prefix$label — $text")
        transcript.post {
            val layout = transcript.layout ?: return@post
            val scroll = layout.getLineTop(transcript.lineCount) - transcript.height
            transcript.scrollTo(0, scroll.coerceAtLeast(0))
        }
    }

    private fun setStatus(text: String, healthy: Boolean) {
        statusText.text = text.take(80)
        val color = ContextCompat.getColor(this, if (healthy) R.color.assist_secondary else R.color.assist_error)
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Assist")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                serviceEvents,
                IntentFilter(AssistEvents.ACTION_EVENT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(serviceEvents)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        engine.close()
        background.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VOICE_PERMISSIONS = 901
    }
}
