package com.dskapp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores the user's OpenAI-compatible endpoint settings (base URL, API key, model).
 *
 * Everything lives in [EncryptedSharedPreferences] (AES-256-GCM via the Android Keystore)
 * and is excluded from backup/transfer in data_extraction_rules.xml, so the key never ends
 * up in a plain-text XML file or a cloud backup.
 *
 * Because base URL + key + model are all user-supplied, the same app works with River AI
 * (https://api.river.ai/v1) or any other OpenAI-compatible server.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /**
     * Current endpoint config for the AI client. The key is read from prefs here (not from
     * [AppSettings]) so it is never held in a long-lived public state object.
     */
    fun providerConfig(): ProviderConfig {
        val settings = readState()
        return ProviderConfig(
            baseUrl = settings.baseUrl,
            apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
            model = settings.model
        )
    }

    fun saveConnection(baseUrl: String, apiKey: String, model: String) {
        prefs.edit {
            putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            putString(KEY_API_KEY, apiKey.trim())
            putString(KEY_MODEL, model.trim())
        }
        _state.value = readState()
    }

    fun saveModel(model: String) {
        prefs.edit { putString(KEY_MODEL, model.trim()) }
        _state.value = readState()
    }

    fun clearApiKey() {
        prefs.edit { remove(KEY_API_KEY) }
        _state.value = readState()
    }

    private fun readState(): AppSettings {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_BASE_URL
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_MODEL
        val hasKey = !prefs.getString(KEY_API_KEY, null).isNullOrBlank()
        return AppSettings(baseUrl = baseUrl, hasApiKey = hasKey, model = model)
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
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
        const val PREFS_FILE = "dsk_secure_prefs"
        const val KEY_BASE_URL = "provider_base_url"
        const val KEY_API_KEY = "provider_api_key"
        const val KEY_MODEL = "provider_model"
    }
}
