package com.dskapp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.ProviderConfig
import com.dskapp.data.ai.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stores the user's OpenAI-compatible endpoint settings (base URL, API key, model) and the
 * app preferences (custom instructions, theme, font size, token prices, usage totals).
 *
 * Only the API key is secret, so only the key is encrypted: it is sealed with an
 * AES-256-GCM key that lives inside the Android Keystore and never leaves it
 * (see [KeystoreCipher]). Everything else goes to plain [SharedPreferences], which is
 * cheap to open and keeps startup fast.
 *
 * This replaces androidx.security's EncryptedSharedPreferences: that library is deprecated
 * and pulls in Tink, which is heavy to initialise. The prefs file is still excluded from
 * backup/transfer in data_extraction_rules.xml.
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private val _usage = MutableStateFlow(readUsage())

    /** Token totals of the current calendar month, for the Settings screen. */
    val usage: StateFlow<MonthlyUsage> = _usage.asStateFlow()

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
        refresh()
    }

    fun saveModel(model: String) {
        prefs.edit { putString(KEY_MODEL, model.trim()) }
        refresh()
    }

    fun clearApiKey() {
        prefs.edit { remove(KEY_API_KEY_ENC) }
        refresh()
    }

    fun saveCustomInstructions(text: String) {
        prefs.edit { putString(KEY_CUSTOM_INSTRUCTIONS, text.trim()) }
        refresh()
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        refresh()
    }

    fun saveFontScale(scale: Float) {
        prefs.edit { putFloat(KEY_FONT_SCALE, scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)) }
        refresh()
    }

    /** USD per one million tokens; null clears the price (only token counts are shown). */
    fun savePrices(inputPerMillion: Double?, outputPerMillion: Double?) {
        prefs.edit {
            if (inputPerMillion == null) remove(KEY_PRICE_IN) else putFloat(KEY_PRICE_IN, inputPerMillion.toFloat())
            if (outputPerMillion == null) remove(KEY_PRICE_OUT) else putFloat(KEY_PRICE_OUT, outputPerMillion.toFloat())
        }
        refresh()
    }

    /** Remembers the server's model list so the per-thread picker works offline. */
    fun saveKnownModels(models: List<String>) {
        prefs.edit { putString(KEY_KNOWN_MODELS, models.joinToString("\n")) }
        refresh()
    }

    /** Adds one call's tokens to this month's total. Called for every AI request. */
    @Synchronized
    fun recordUsage(usage: TokenUsage) {
        val month = currentMonth()
        val current = readUsage()
        val base = if (current.month == month) current else MonthlyUsage(month, 0L, 0L)
        val updated = base.copy(
            promptTokens = base.promptTokens + usage.promptTokens,
            completionTokens = base.completionTokens + usage.completionTokens
        )
        prefs.edit {
            putString(KEY_USAGE_MONTH, updated.month)
            putLong(KEY_USAGE_IN, updated.promptTokens)
            putLong(KEY_USAGE_OUT, updated.completionTokens)
        }
        _usage.value = updated
    }

    private fun refresh() {
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
            refresh()
            return ""
        }
        return plain
    }

    private fun readState(): AppSettings {
        val baseUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_BASE_URL
        val model = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: OpenAiCompatibleClient.DEFAULT_MODEL
        val knownModels = prefs.getString(KEY_KNOWN_MODELS, null)
            ?.split('\n')?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: OpenAiCompatibleClient.SUGGESTED_MODELS
        return AppSettings(
            baseUrl = baseUrl,
            // Cheap presence check: never decrypt during startup.
            hasApiKey = prefs.contains(KEY_API_KEY_ENC),
            model = model,
            customInstructions = prefs.getString(KEY_CUSTOM_INSTRUCTIONS, null).orEmpty(),
            themeMode = prefs.getString(KEY_THEME_MODE, null)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1f),
            priceInputPerMillion = prefs.takeIf { it.contains(KEY_PRICE_IN) }
                ?.getFloat(KEY_PRICE_IN, 0f)?.toDouble(),
            priceOutputPerMillion = prefs.takeIf { it.contains(KEY_PRICE_OUT) }
                ?.getFloat(KEY_PRICE_OUT, 0f)?.toDouble(),
            knownModels = knownModels
        )
    }

    private fun readUsage(): MonthlyUsage {
        val month = currentMonth()
        if (prefs.getString(KEY_USAGE_MONTH, null) != month) return MonthlyUsage(month, 0L, 0L)
        return MonthlyUsage(
            month = month,
            promptTokens = prefs.getLong(KEY_USAGE_IN, 0L),
            completionTokens = prefs.getLong(KEY_USAGE_OUT, 0L)
        )
    }

    private fun currentMonth(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

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
        val model: String,
        val customInstructions: String = "",
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val fontScale: Float = 1f,
        val priceInputPerMillion: Double? = null,
        val priceOutputPerMillion: Double? = null,
        val knownModels: List<String> = OpenAiCompatibleClient.SUGGESTED_MODELS
    ) {
        val isConfigured: Boolean get() = hasApiKey && baseUrl.isNotBlank() && model.isNotBlank()

        /** Cost in USD for the given tokens, or null when no price is set. */
        fun costUsd(promptTokens: Long, completionTokens: Long): Double? {
            if (priceInputPerMillion == null && priceOutputPerMillion == null) return null
            return (promptTokens * (priceInputPerMillion ?: 0.0) +
                completionTokens * (priceOutputPerMillion ?: 0.0)) / 1_000_000.0
        }
    }

    data class MonthlyUsage(val month: String, val promptTokens: Long, val completionTokens: Long)

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    companion object {
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.4f

        private const val PREFS_FILE = "dsk_settings"
        private const val KEY_BASE_URL = "provider_base_url"
        private const val KEY_API_KEY_ENC = "provider_api_key_enc"
        private const val KEY_MODEL = "provider_model"
        private const val KEY_LEGACY_REMOVED = "legacy_prefs_removed"
        private const val KEY_CUSTOM_INSTRUCTIONS = "custom_instructions"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_PRICE_IN = "price_input_per_million"
        private const val KEY_PRICE_OUT = "price_output_per_million"
        private const val KEY_KNOWN_MODELS = "known_models"
        private const val KEY_USAGE_MONTH = "usage_month"
        private const val KEY_USAGE_IN = "usage_prompt_tokens"
        private const val KEY_USAGE_OUT = "usage_completion_tokens"

        private const val LEGACY_PREFS_FILE = "dsk_secure_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }
}
