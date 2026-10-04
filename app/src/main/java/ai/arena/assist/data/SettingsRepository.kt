package ai.arena.assist.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AssistSettings(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val alwaysListen: Boolean,
    val requireWakePhrase: Boolean,
    val speakResponses: Boolean,
    val startOnBoot: Boolean,
    val rootToolsEnabled: Boolean
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secretStore = KeystoreSecretStore(prefs)

    fun load(): AssistSettings = AssistSettings(
        endpoint = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT).orEmpty(),
        apiKey = secretStore.read(),
        model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL },
        alwaysListen = prefs.getBoolean(KEY_ALWAYS_LISTEN, false),
        requireWakePhrase = prefs.getBoolean(KEY_WAKE_PHRASE, true),
        speakResponses = prefs.getBoolean(KEY_SPEAK, true),
        startOnBoot = prefs.getBoolean(KEY_START_BOOT, false),
        rootToolsEnabled = prefs.getBoolean(KEY_ROOT_TOOLS, false)
    )

    fun saveConnection(endpoint: String, apiKey: String, model: String) {
        prefs.edit()
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
        secretStore.write(apiKey.trim())
    }

    fun setAlwaysListen(value: Boolean) = setBoolean(KEY_ALWAYS_LISTEN, value)
    fun setRequireWakePhrase(value: Boolean) = setBoolean(KEY_WAKE_PHRASE, value)
    fun setSpeakResponses(value: Boolean) = setBoolean(KEY_SPEAK, value)
    fun setStartOnBoot(value: Boolean) = setBoolean(KEY_START_BOOT, value)
    fun setRootToolsEnabled(value: Boolean) = setBoolean(KEY_ROOT_TOOLS, value)

    private fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        private const val PREFS = "assist_settings"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_ALWAYS_LISTEN = "always_listen"
        private const val KEY_WAKE_PHRASE = "wake_phrase"
        private const val KEY_SPEAK = "speak"
        private const val KEY_START_BOOT = "start_boot"
        private const val KEY_ROOT_TOOLS = "root_tools"
        const val DEFAULT_ENDPOINT = ""
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

/** Stores only ciphertext in SharedPreferences; the AES key never leaves Android Keystore. */
private class KeystoreSecretStore(private val prefs: SharedPreferences) {
    fun write(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            prefs.edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        }.getOrElse {
            throw IllegalStateException("Unable to protect API key with Android Keystore", it)
        }
    }

    fun read(): String {
        val encryptedText = prefs.getString(KEY_CIPHERTEXT, null) ?: return ""
        val ivText = prefs.getString(KEY_IV, null) ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            )
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "assist_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_CIPHERTEXT = "api_key_ciphertext"
        private const val KEY_IV = "api_key_iv"
    }
}
