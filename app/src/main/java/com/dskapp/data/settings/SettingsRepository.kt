package com.dskapp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore

/**
 * Stores the user's OpenAI-compatible endpoint settings (base URL, API key, model).
 *
 * Only the API key is secret, so only the key is encrypted: it is sealed with an
 * AES-256-GCM key that lives inside the Android Keystore and never leaves it
 * (see [KeystoreCipher]). Base URL and model go to plain [SharedPreferences], which is
 * cheap to open and keeps startup fast.
 *
 * This replaces androidx.security's EncryptedSharedPreferences: that library is deprecated
 * and pulls in Tink, which is heavy to initialise. The prefs file is still excluded from
 * backup/transfer in data_extraction_rules.xml.
 *
 * Because base URL + key + model are all user-supplied, the same app works with River AI
 * (https://api.river.ai/v1) or any other OpenAI-compatible server.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    init {
        removeLegacyStorageOnce()
    }

    /**
     * Current endpoint config for the AI client. The key is decrypted here (not held in
     * [AppSettings]) so it is never kept in a long-lived public state object.
     */
    fun providerConfig(): ProviderConfig {
        val settings = readState()
        return ProviderConfig(
            baseUrl = settings.baseUrl,
            apiKey = readApiKey(),
            model = settings.model
        )
    }

    fun saveConnection(baseUrl: String, apiKey: String, model: String) {
        val trimmedKey = apiKey.trim()
        val encrypted = if (trimmedKey.isEmpty()) null else cipher.encrypt(trimmedKey)
        prefs.edit {
            putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            if (encrypted == null) {
                remove(KEY_API_KEY_ENC)
            } else {
                putString(KEY_API_KEY_ENC, encrypted)
            }
            putString(KEY_MODEL, model.trim())
        }
        _state.value = readState()
    }

    fun saveModel(model: String) {
        prefs.edit { putString(KEY_MODEL, model.trim()) }
        _state.value = readState()
    }

    fun clearApiKey() {
        prefs.edit { remove(KEY_API_KEY_ENC) }
        _state.value = readState()
    }

    /**
     * Decrypts the stored key. If a blob exists but can no longer be decrypted (corrupt,
     * key invalidated/removed) we drop the blob and the Keystore entry so the UI asks the
     * user to re-enter the key instead of retrying forever.
     */
    private fun readApiKey(): String {
        val blob = prefs.getString(KEY_API_KEY_ENC, null) ?: return ""
        val plain = cipher.decrypt(blob)
        if (plain == null) {
            prefs.edit { remove(KEY_API_KEY_ENC) }
            cipher.deleteKey()
            _state.value = readState()
            return ""
        }
        return plain
    }

    private fun readState(): AppSettings {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_BASE_URL
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_MODEL
        // Cheap presence check: never decrypt during startup.
        val hasKey = prefs.contains(KEY_API_KEY_ENC)
        return AppSettings(baseUrl = baseUrl, hasApiKey = hasKey, model = model)
    }

    /**
     * Runs the migration/cleanup exactly once. The old encrypted prefs file and its Tink
     * master key are useless now; the user re-enters the key a single time.
     */
    private fun removeLegacyStorageOnce() {
        if (prefs.getBoolean(KEY_LEGACY_REMOVED, false)) return
        runCatching { appContext.deleteSharedPreferences(LEGACY_PREFS_FILE) }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            }
        }
        prefs.edit { putBoolean(KEY_LEGACY_REMOVED, true) }
    }

    /** UI-safe view of settings: never contains the API key itself. */
    data class AppSettings(
        val baseUrl: String,
        val hasApiKey: Boolean,
        val model: String
    ) {
        val isConfigured: Boolean get() = hasApiKey && baseUrl.isNotBlank() && model.isNotBlank()
    }

    private companion object {
        const val PREFS_FILE = "dsk_settings"
        const val KEY_BASE_URL = "provider_base_url"
        const val KEY_API_KEY_ENC = "provider_api_key_enc"
        const val KEY_MODEL = "provider_model"
        const val KEY_LEGACY_REMOVED = "legacy_prefs_removed"

        const val LEGACY_PREFS_FILE = "dsk_secure_prefs"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}
