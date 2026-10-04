package com.netbypass.assist

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netbypass.assist.util.Logger

/**
 * Persists all user-configurable settings (API base URL / key / model, wake word, behavior
 * toggles). Backed by [EncryptedSharedPreferences] so the API key is not stored in plain text
 * on disk, which matters since this app is granted root and lives on a device that may be
 * shared or flashed.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "assist_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Fall back to plain prefs rather than crash the app if the keystore is unavailable
            // (seen on some custom ROMs / emulators without a working Android Keystore).
            Logger.e("SettingsStore", "Falling back to plain SharedPreferences", t)
            appContext.getSharedPreferences("assist_plain_prefs", Context.MODE_PRIVATE)
        }
    }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, BuildConfig.DEFAULT_BASE_URL) ?: BuildConfig.DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, BuildConfig.DEFAULT_API_KEY) ?: BuildConfig.DEFAULT_API_KEY
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, BuildConfig.DEFAULT_MODEL) ?: BuildConfig.DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim()).apply()

    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, BuildConfig.DEFAULT_WAKE_WORD) ?: BuildConfig.DEFAULT_WAKE_WORD
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value.trim().lowercase()).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var headlessMode: Boolean
        get() = prefs.getBoolean(KEY_HEADLESS, false)
        set(value) = prefs.edit().putBoolean(KEY_HEADLESS, value).apply()

    var confirmDangerous: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_DANGEROUS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM_DANGEROUS, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_WAKE_WORD = "wake_word"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_HEADLESS = "headless_mode"
        private const val KEY_CONFIRM_DANGEROUS = "confirm_dangerous"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
    }
}
