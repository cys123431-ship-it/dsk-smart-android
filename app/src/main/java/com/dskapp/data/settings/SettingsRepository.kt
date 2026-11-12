package com.dskapp.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dskapp.agent.AgentLevel
import com.dskapp.data.ai.ModelCatalog
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.ProviderConfig
import com.dskapp.data.ai.TokenUsage
import com.dskapp.data.remote.KnownPc
import com.dskapp.data.remote.KnownPcsCodec
import com.dskapp.data.search.SearchProviderType
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

    /** Separate Keystore alias so clearing the AI key never wipes the remote secret. */
    private val remoteCipher = KeystoreCipher(REMOTE_KEY_ALIAS)

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
        // readApiKey() drops an undecryptable blob, but readState() still reports hasApiKey
        // from the prefs file. Reconcile here so the UI stops claiming "설정 완료" and asks
        // for the key again instead of failing every request with 401.
        val key = readApiKey()
        if (key.isEmpty() && settings.hasApiKey) {
            _state.value = _state.value.copy(hasApiKey = false)
        }
        return ProviderConfig(
            baseUrl = settings.baseUrl,
            apiKey = key,
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

    fun saveAgentLevel(level: AgentLevel) {
        prefs.edit { putString(KEY_AGENT_LEVEL, level.name) }
        refresh()
    }

    fun saveAgentMaxSteps(steps: Int) {
        prefs.edit { putInt(KEY_AGENT_MAX_STEPS, steps.coerceIn(MIN_AGENT_STEPS, MAX_AGENT_STEPS)) }
        refresh()
    }

    fun saveAgentScreenshots(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AGENT_SCREENSHOTS, enabled) }
        refresh()
    }

    /** Package names the agent may operate even though they look like banking/payment apps. */
    fun saveAgentAllowed(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_AGENT_ALLOWED, packages) }
        refresh()
    }

    /** Package names the agent must never touch, on top of the built-in finance keywords. */
    fun saveAgentBlocked(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_AGENT_BLOCKED, packages) }
        refresh()
    }

    /** PC goose remote: server address, cwd, permission mode and (encrypted) secret key. */
    fun saveRemote(serverUrl: String, secretKey: String, cwd: String, mode: String) {
        val trimmedSecret = secretKey.trim()
        val encrypted = if (trimmedSecret.isEmpty()) null else remoteCipher.encrypt(trimmedSecret)
        prefs.edit {
            putString(KEY_REMOTE_URL, serverUrl.trim().trimEnd('/'))
            if (encrypted == null) remove(KEY_REMOTE_SECRET_ENC) else putString(KEY_REMOTE_SECRET_ENC, encrypted)
            putString(KEY_REMOTE_CWD, cwd.trim())
            putString(KEY_REMOTE_MODE, mode.trim())
        }
        refresh()
    }

    fun saveRemoteCwd(cwd: String) {
        prefs.edit { putString(KEY_REMOTE_CWD, cwd.trim()) }
        refresh()
    }

    fun saveRemoteMode(mode: String) {
        prefs.edit { putString(KEY_REMOTE_MODE, mode.trim()) }
        refresh()
    }

    /** PCs this phone has connected to, newest first. */
    fun knownPcs(): List<KnownPc> =
        KnownPcsCodec.decode(prefs.getString(KEY_REMOTE_KNOWN, null))

    /**
     * Remembers [pc] so the next launch can offer it (or fall back to it) without a scan.
     * Also makes it the active address, because a successful connection means it works.
     */
    fun rememberPc(pc: KnownPc) {
        val merged = KnownPcsCodec.merge(knownPcs(), pc)
        prefs.edit {
            putString(KEY_REMOTE_KNOWN, KnownPcsCodec.encode(merged))
            putString(KEY_REMOTE_URL, pc.address.trim().trimEnd('/'))
        }
        refresh()
    }

    fun forgetPc(address: String) {
        val remaining = knownPcs().filterNot { it.address.equals(address, ignoreCase = true) }
        prefs.edit { putString(KEY_REMOTE_KNOWN, KnownPcsCodec.encode(remaining)) }
        refresh()
    }

    fun clearKnownPcs() {
        prefs.edit { remove(KEY_REMOTE_KNOWN) }
        refresh()
    }

    /** Decrypts the remote secret on demand; empty when none is stored or it is unreadable. */
    fun remoteSecret(): String {
        val blob = prefs.getString(KEY_REMOTE_SECRET_ENC, null) ?: return ""
        val plain = remoteCipher.decrypt(blob)
        if (plain == null) {
            prefs.edit { remove(KEY_REMOTE_SECRET_ENC) }
            remoteCipher.deleteKey()
            refresh()
            return ""
        }
        return plain
    }

    fun saveAppLock(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_APP_LOCK, enabled) }
        refresh()
    }

    fun saveAutoReadLinks(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_READ_LINKS, enabled) }
        refresh()
    }

    fun saveWebSearch(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WEB_SEARCH, enabled) }
        refresh()
    }

    fun saveSearchProvider(provider: SearchProviderType) {
        prefs.edit { putString(KEY_SEARCH_PROVIDER, provider.name) }
        refresh()
    }

    /**
     * Remembers the server's model list so the per-thread picker works offline.
     * [fetchedFrom] is the base URL the list came from; null (a placeholder list after a
     * server change) leaves the list "never fetched" so the next check loads it at once.
     */
    fun saveKnownModels(models: List<String>, fetchedFrom: String? = null) {
        prefs.edit {
            putString(KEY_KNOWN_MODELS, models.joinToString("\n"))
            if (fetchedFrom != null) {
                putLong(KEY_MODELS_FETCHED_AT, System.currentTimeMillis())
                putString(KEY_MODELS_FETCHED_URL, fetchedFrom.trim().trimEnd('/'))
                // A fresh list from the server: models it no longer serves cannot stay flagged.
                val flagged = prefs.getStringSet(KEY_UNAVAILABLE_MODELS, null).orEmpty()
                putStringSet(KEY_UNAVAILABLE_MODELS, flagged.filter { it in models }.toSet())
            } else {
                remove(KEY_MODELS_FETCHED_AT)
                remove(KEY_MODELS_FETCHED_URL)
            }
        }
        refresh()
    }

    /**
     * True when the saved model list is older than [maxAgeMs] or came from another server.
     * Providers add and drop models without telling anyone, so the app re-reads the list on
     * its own instead of waiting for the user to press "모델 목록 불러오기".
     */
    fun modelsStale(maxAgeMs: Long): Boolean {
        val at = prefs.getLong(KEY_MODELS_FETCHED_AT, 0L)
        val from = prefs.getString(KEY_MODELS_FETCHED_URL, null)
        val current = readState().baseUrl.trim().trimEnd('/')
        if (at == 0L || from != current) return true
        val age = System.currentTimeMillis() - at
        return age < 0 || age > maxAgeMs
    }

    /** Model the user was last warned about as missing from the server list, to warn once. */
    fun missingModelWarned(): String? = prefs.getString(KEY_MISSING_MODEL_WARNED, null)

    fun setMissingModelWarned(model: String?) {
        prefs.edit {
            if (model == null) remove(KEY_MISSING_MODEL_WARNED) else putString(KEY_MISSING_MODEL_WARNED, model)
        }
    }

    /**
     * A model the server listed but could not answer with ("route unavailable", "not found").
     * Shown with a warning in the pickers until it answers once or the list is refreshed
     * without it.
     */
    fun markModelUnavailable(model: String) {
        if (model.isBlank()) return
        val current = prefs.getStringSet(KEY_UNAVAILABLE_MODELS, null).orEmpty()
        if (model in current) return
        prefs.edit { putStringSet(KEY_UNAVAILABLE_MODELS, current + model) }
        refresh()
    }

    fun markModelWorking(model: String) {
        val current = prefs.getStringSet(KEY_UNAVAILABLE_MODELS, null).orEmpty()
        if (model !in current) return
        prefs.edit { putStringSet(KEY_UNAVAILABLE_MODELS, current - model) }
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
        // Lists saved by older versions still hold embedding / OCR / rerank models: drop
        // them here too, but never the model the user has chosen.
        val knownModels = prefs.getString(KEY_KNOWN_MODELS, null)
            ?.split('\n')?.filter { it.isNotBlank() }
            ?.filter { it == model || ModelCatalog.isChatModel(it) }
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
            knownModels = knownModels,
            unavailableModels = prefs.getStringSet(KEY_UNAVAILABLE_MODELS, null).orEmpty().toSet(),
            autoReadLinks = prefs.getBoolean(KEY_AUTO_READ_LINKS, true),
            webSearch = prefs.getBoolean(KEY_WEB_SEARCH, true),
            searchProvider = SearchProviderType.fromName(prefs.getString(KEY_SEARCH_PROVIDER, null)),
            appLock = prefs.getBoolean(KEY_APP_LOCK, false),
            agentLevel = prefs.getString(KEY_AGENT_LEVEL, null)
                ?.let { name -> AgentLevel.entries.firstOrNull { it.name == name } }
                ?: AgentLevel.CONTROL,
            agentMaxSteps = prefs.getInt(KEY_AGENT_MAX_STEPS, DEFAULT_AGENT_STEPS),
            agentScreenshots = prefs.getBoolean(KEY_AGENT_SCREENSHOTS, false),
            agentAllowed = prefs.getStringSet(KEY_AGENT_ALLOWED, null).orEmpty().toSet(),
            // Previously hard-coded to emptySet(): the allow-list had nothing to override and
            // a user could never block an extra package beyond the built-in keyword list.
            agentBlocked = prefs.getStringSet(KEY_AGENT_BLOCKED, null).orEmpty().toSet(),
            remoteUrl = prefs.getString(KEY_REMOTE_URL, null).orEmpty(),
            hasRemoteSecret = prefs.contains(KEY_REMOTE_SECRET_ENC),
            remoteCwd = prefs.getString(KEY_REMOTE_CWD, null).orEmpty(),
            remoteMode = prefs.getString(KEY_REMOTE_MODE, null)?.takeIf { it.isNotBlank() } ?: "approve"
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
        val knownModels: List<String> = OpenAiCompatibleClient.SUGGESTED_MODELS,
        /** Listed by the server but failed to answer recently; pickers mark these. */
        val unavailableModels: Set<String> = emptySet(),
        /** Fetch web pages for URLs in a sent message and attach their text. */
        val autoReadLinks: Boolean = true,
        /** Let the model ask for web searches; [searchProvider] runs them. */
        val webSearch: Boolean = true,
        val searchProvider: SearchProviderType = SearchProviderType.DUCKDUCKGO,
        /** Ask for fingerprint / face / screen lock when the app is opened. */
        val appLock: Boolean = false,
        /** Phone agent: how far it may go, how many steps, extra screenshot input, allow-list. */
        val agentLevel: AgentLevel = AgentLevel.CONTROL,
        val agentMaxSteps: Int = DEFAULT_AGENT_STEPS,
        val agentScreenshots: Boolean = false,
        val agentAllowed: Set<String> = emptySet(),
        val agentBlocked: Set<String> = emptySet(),
        /** PC goose (Agent Client Protocol) server, e.g. "ws://100.64.0.5:3284/acp". */
        val remoteUrl: String = "",
        val hasRemoteSecret: Boolean = false,
        /** Working directory the remote agent opens. Empty = the app sends ~/goose default. */
        val remoteCwd: String = "",
        /** ACP mode id: auto / approve / smart_approve / chat. */
        val remoteMode: String = "approve"
    ) {
        val isConfigured: Boolean get() = hasApiKey && baseUrl.isNotBlank() && model.isNotBlank()

        val isRemoteConfigured: Boolean
            get() = remoteUrl.isNotBlank() && hasRemoteSecret

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
        const val MIN_AGENT_STEPS = 5
        const val MAX_AGENT_STEPS = 40
        const val DEFAULT_AGENT_STEPS = 25

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
        private const val KEY_MODELS_FETCHED_AT = "known_models_fetched_at"
        private const val KEY_MODELS_FETCHED_URL = "known_models_fetched_url"
        private const val KEY_UNAVAILABLE_MODELS = "unavailable_models"
        private const val KEY_MISSING_MODEL_WARNED = "missing_model_warned"
        private const val KEY_AUTO_READ_LINKS = "auto_read_links"
        private const val KEY_WEB_SEARCH = "web_search"
        private const val KEY_SEARCH_PROVIDER = "search_provider"
        private const val KEY_APP_LOCK = "app_lock"
        private const val KEY_AGENT_LEVEL = "agent_level"
        private const val KEY_AGENT_MAX_STEPS = "agent_max_steps"
        private const val KEY_AGENT_SCREENSHOTS = "agent_screenshots"
        private const val KEY_AGENT_ALLOWED = "agent_allowed"
        private const val KEY_AGENT_BLOCKED = "agent_blocked"
        private const val KEY_REMOTE_URL = "remote_goose_url"
        private const val KEY_REMOTE_SECRET_ENC = "remote_goose_secret_enc"
        private const val KEY_REMOTE_CWD = "remote_goose_cwd"
        private const val KEY_REMOTE_MODE = "remote_goose_mode"
        private const val KEY_REMOTE_KNOWN = "remote_goose_known_pcs"
        private const val KEY_USAGE_MONTH = "usage_month"
        private const val KEY_USAGE_IN = "usage_prompt_tokens"
        private const val KEY_USAGE_OUT = "usage_completion_tokens"

        private const val LEGACY_PREFS_FILE = "dsk_secure_prefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
        private const val REMOTE_KEY_ALIAS = "dsk_remote_secret"
    }
}
