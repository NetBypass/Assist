package com.netbypass.assist

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.netbypass.assist.ai.OpenAiClient
import com.netbypass.assist.databinding.ActivityMainBinding
import com.netbypass.assist.service.AssistantService
import com.netbypass.assist.tools.RootShell
import com.netbypass.assist.util.Logger
import com.netbypass.assist.voice.SpeechToText
import com.netbypass.assist.voice.TextToSpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Logger.w("MainActivity", "Permissions denied: $denied")
            Toast.makeText(this, "Some permissions were denied: $denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        loadSettingsIntoUi()
        requestRuntimePermissions()
        wireButtons()
        observeLogs()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val toRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun loadSettingsIntoUi() = with(binding) {
        etBaseUrl.setText(settings.baseUrl)
        etApiKey.setText(settings.apiKey)
        etModel.setText(settings.model)
        etWakeWord.setText(settings.wakeWord)
        swStartOnBoot.isChecked = settings.startOnBoot
        swHeadless.isChecked = settings.headlessMode
        swConfirmDangerous.isChecked = settings.confirmDangerous
    }

    private fun saveUiIntoSettings() = with(binding) {
        settings.baseUrl = etBaseUrl.text.toString().ifBlank { BuildConfig.DEFAULT_BASE_URL }
        settings.apiKey = etApiKey.text.toString()
        settings.model = etModel.text.toString().ifBlank { BuildConfig.DEFAULT_MODEL }
        settings.wakeWord = etWakeWord.text.toString().ifBlank { BuildConfig.DEFAULT_WAKE_WORD }
        settings.startOnBoot = swStartOnBoot.isChecked
        settings.headlessMode = swHeadless.isChecked
        settings.confirmDangerous = swConfirmDangerous.isChecked
    }

    private fun wireButtons() = with(binding) {
        btnSave.setOnClickListener {
            saveUiIntoSettings()
            Toast.makeText(this@MainActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            Logger.i("MainActivity", "Settings saved: baseUrl=${settings.baseUrl} model=${settings.model}")
        }

        btnStart.setOnClickListener {
            saveUiIntoSettings()
            settings.serviceEnabled = true
            ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, AssistantService::class.java))
            refreshStatus()
            Toast.makeText(this@MainActivity, "Assistant starting…", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            settings.serviceEnabled = false
            stopService(Intent(this@MainActivity, AssistantService::class.java))
            refreshStatus()
            Toast.makeText(this@MainActivity, "Assistant stopped", Toast.LENGTH_SHORT).show()
        }

        btnGrantRoot.setOnClickListener { testRoot() }
        btnFetchModels.setOnClickListener { fetchModels() }
        btnTestVoice.setOnClickListener { testVoice() }

        btnBatteryOptimization.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not open battery settings: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshStatus() {
        val running = isServiceRunning()
        binding.tvStatus.text = "Status: ${if (running) "running, listening for \"${settings.wakeWord}\"" else "stopped"}"
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun isServiceRunning(): Boolean = settings.serviceEnabled

    private fun testRoot() {
        binding.tvRootStatus.text = "Root: checking…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val shell = RootShell()
                val r = shell.exec("id")
                shell.close()
                r
            }
            binding.tvRootStatus.text = if (result.success && result.output.contains("uid=0")) {
                "Root: granted (${result.output.trim()})"
            } else {
                "Root: NOT granted — ${result.error.ifBlank { result.output }.ifBlank { "su unavailable" }}"
            }
        }
    }

    private fun fetchModels() {
        saveUiIntoSettings()
        Toast.makeText(this, "Fetching models…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val client = OpenAiClient()
            val result = client.listModels(settings.baseUrl, settings.apiKey)
            result.onSuccess { models ->
                Logger.i("MainActivity", "Models: $models")
                if (models.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Endpoint returned no models", Toast.LENGTH_LONG).show()
                } else {
                    binding.etModel.setText(models.first())
                    Toast.makeText(this@MainActivity, "Found ${models.size} models, using '${models.first()}'. See logs for full list.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { e ->
                Logger.e("MainActivity", "Fetch models failed", e)
                Toast.makeText(this@MainActivity, "Could not fetch models: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testVoice() {
        lifecycleScope.launch {
            val tts = TextToSpeechManager(this@MainActivity)
            if (!tts.init()) {
                Toast.makeText(this@MainActivity, "Text-to-speech failed to initialize", Toast.LENGTH_LONG).show()
                return@launch
            }
            tts.speak("Hello, I am Assist. Please say something after the beep.")
            val stt = SpeechToText(this@MainActivity)
            if (!stt.isAvailable()) {
                Toast.makeText(this@MainActivity, "No speech recognizer available on this device", Toast.LENGTH_LONG).show()
                tts.shutdown()
                return@launch
            }
            val heard = stt.listenOnce(timeoutMs = 8_000)
            if (heard.isNullOrBlank()) {
                tts.speak("I did not hear anything.")
            } else {
                tts.speak("I heard: $heard")
            }
            tts.shutdown()
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            Logger.lines.collect { text ->
                binding.tvLogs.text = text
            }
        }
    }

}
