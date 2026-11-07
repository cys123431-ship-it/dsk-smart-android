package com.dskapp.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.ai.AiClient
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.userMessage
import com.dskapp.data.backup.BackupService
import com.dskapp.data.chat.ChatRepository
import com.dskapp.data.commands.CommandRepository
import com.dskapp.data.commands.PromptCommand
import com.dskapp.data.log.ErrorLog
import com.dskapp.data.remote.AcpClient
import com.dskapp.data.remote.AcpJson
import com.dskapp.agent.AgentLevel
import com.dskapp.ui.lock.AppLock
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = OpenAiCompatibleClient.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = OpenAiCompatibleClient.DEFAULT_MODEL,
    val hasStoredKey: Boolean = false,
    val availableModels: List<String> = OpenAiCompatibleClient.SUGGESTED_MODELS,
    val isLoadingModels: Boolean = false,
    val isTesting: Boolean = false,
    val message: String? = null,
    val isSaved: Boolean = false,
    val customInstructions: String = "",
    val themeMode: SettingsRepository.ThemeMode = SettingsRepository.ThemeMode.SYSTEM,
    val fontScale: Float = 1f,
    val priceInput: String = "",
    val priceOutput: String = "",
    val usage: SettingsRepository.MonthlyUsage? = null,
    val estimatedCostUsd: Double? = null,
    val isBackupRunning: Boolean = false,
    val autoReadLinks: Boolean = true,
    val webSearch: Boolean = true,
    val searchEngine: String = "",
    val appLock: Boolean = false,
    val agentLevel: AgentLevel = AgentLevel.CONTROL,
    val agentMaxSteps: Int = 25,
    val agentScreenshots: Boolean = false,
    val agentAllowedText: String = "",
    val agentBlockedText: String = "",
    val errorCount: Int = 0,
    val lastErrorTime: String? = null,
    // PC goose remote
    val remoteUrl: String = "",
    val remoteSecret: String = "",
    val remoteCwd: String = "",
    val remoteMode: String = "approve",
    val hasRemoteSecret: Boolean = false,
    val isRemoteTesting: Boolean = false,
    /** True while the user is typing in the remote fields; the state collector leaves them alone. */
    val isRemoteEditing: Boolean = false,
    val remoteStatus: String? = null
)

/**
 * Settings for the OpenAI-compatible endpoint plus the personalisation, usage and backup
 * controls on the same screen. All three connection inputs are required, and the base URL is
 * pre-filled with River AI so the common path needs no typing beyond the key.
 */
class SettingsViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val aiClient: AiClient,
    private val commandRepository: CommandRepository
) : ViewModel() {

    /** Saved one-tap commands, edited in the "나만의 명령어" section. */
    val commands: StateFlow<List<PromptCommand>> = commandRepository.commands

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrl = repository.state.value.baseUrl,
            model = repository.state.value.model,
            hasStoredKey = repository.state.value.hasApiKey,
            availableModels = repository.state.value.knownModels.ifEmpty {
                OpenAiCompatibleClient.SUGGESTED_MODELS
            },
            customInstructions = repository.state.value.customInstructions,
            themeMode = repository.state.value.themeMode,
            fontScale = repository.state.value.fontScale,
            priceInput = formatPrice(repository.state.value.priceInputPerMillion),
            priceOutput = formatPrice(repository.state.value.priceOutputPerMillion),
            usage = repository.usage.value,
            remoteUrl = repository.state.value.remoteUrl,
            remoteCwd = repository.state.value.remoteCwd,
            remoteMode = repository.state.value.remoteMode,
            hasRemoteSecret = repository.state.value.hasRemoteSecret
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshErrorLog()
        verifyStoredKey()
        // Usage and stored settings change from other screens (chat writes tokens, the theme
        // can be toggled elsewhere); recombine so cost and appearance stay in sync without
        // the screen re-reading the repository on every recomposition.
        viewModelScope.launch {
            combine(repository.state, repository.usage) { settings, usage -> settings to usage }
                .collect { (settings, usage) ->
                    _uiState.update { current ->
                        current.copy(
                            themeMode = settings.themeMode,
                            fontScale = settings.fontScale,
                            autoReadLinks = settings.autoReadLinks,
                            webSearch = settings.webSearch,
                            searchEngine = settings.searchProvider.label,
                            appLock = settings.appLock,
                            agentLevel = settings.agentLevel,
                            agentMaxSteps = settings.agentMaxSteps,
                            agentScreenshots = settings.agentScreenshots,
                            agentAllowedText = settings.agentAllowed.sorted().joinToString("\n"),
                            agentBlockedText = settings.agentBlocked.sorted().joinToString("\n"),
                            remoteUrl = if (current.isRemoteEditing) current.remoteUrl else settings.remoteUrl,
                            remoteCwd = if (current.isRemoteEditing) current.remoteCwd else settings.remoteCwd,
                            remoteMode = settings.remoteMode,
                            hasRemoteSecret = settings.hasRemoteSecret,
                            usage = usage,
                            estimatedCostUsd = settings.costUsd(
                                usage.promptTokens,
                                usage.completionTokens
                            )
                        )
                    }
                }
        }
    }

    /**
     * The prefs file only says a key blob exists. If the Keystore key was invalidated (device
     * restore, lock-screen change on some ROMs) the blob can no longer be decrypted, and every
     * request would fail with 401 while the screen still claimed "저장된 키 사용 중".
     * Decrypt once on entry and tell the user clearly when it is gone.
     */
    private fun verifyStoredKey() {
        if (!repository.state.value.hasApiKey) return
        val key = repository.providerConfig().apiKey
        if (key.isNotEmpty()) return
        _uiState.update {
            it.copy(
                hasStoredKey = false,
                message = "저장된 API 키를 읽을 수 없습니다(기기 보안 키가 바뀜). API 키를 다시 입력해 주세요."
            )
        }
        ErrorLog.record("settings", "stored API key could not be decrypted; asked the user to re-enter it")
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, isSaved = false) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, isSaved = false) }
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, isSaved = false) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun save() {
        val state = _uiState.value
        val baseUrl = state.baseUrl.trim()
        val model = state.model.trim()
        val apiKey = state.apiKey.trim()

        when {
            baseUrl.isBlank() -> {
                _uiState.update { it.copy(message = "서버 주소를 입력해 주세요.") }
                return
            }
            model.isBlank() -> {
                _uiState.update { it.copy(message = "모델 이름을 입력해 주세요.") }
                return
            }
            apiKey.isBlank() && !state.hasStoredKey -> {
                _uiState.update { it.copy(message = "API 키를 입력해 주세요.") }
                return
            }
        }

        // Keep the previously stored key when the field is left blank on edit.
        val effectiveKey = apiKey.ifBlank {
            repository.providerConfig().apiKey
        }
        repository.saveConnection(baseUrl, effectiveKey, model)
        _uiState.update {
            it.copy(apiKey = "", hasStoredKey = true, isSaved = true, message = "저장했습니다.")
        }
    }

    /** Calls GET /models so the user can pick a model without leaving the app. */
    fun loadModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, message = null) }
            val result = aiClient.listModels()
            _uiState.update { state ->
                result.fold(
                    onSuccess = { models ->
                        // Remember the served list so the picker is populated offline too.
                        if (models.isNotEmpty()) {
                            repository.saveKnownModels(models)
                        }
                        state.copy(
                            isLoadingModels = false,
                            availableModels = if (models.isEmpty()) state.availableModels else models,
                            message = if (models.isEmpty()) {
                                "서버가 모델 목록을 제공하지 않습니다."
                            } else {
                                "모델 ${models.size}개를 불러왔습니다."
                            }
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isLoadingModels = false,
                            message = error.message ?: "모델 목록을 불러오지 못했습니다."
                        )
                    }
                )
            }
        }
    }

    /**
     * Verifies URL + key + model together with one short call.
     *
     * Two details keep this in step with normal chat, which is why the old version could
     * fail while chat worked: (1) the token budget must leave room for a reasoning model's
     * hidden thinking, and (2) the *screen's* values are tested even before "저장", falling
     * back to the stored key when the field is left blank, otherwise the test used the
     * previous endpoint while the user was editing a new one.
     */
    fun testConnection() {
        val state = _uiState.value
        if (state.isTesting) return
        if (state.baseUrl.isBlank() || state.model.isBlank()) {
            _uiState.update { it.copy(message = "서버 주소와 모델을 먼저 입력해 주세요.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, message = "연결 테스트 중…") }

            // Use what is typed on screen; an empty key field means "keep the stored key".
            val typedKey = state.apiKey.trim()
            val effectiveKey = typedKey.ifBlank { repository.providerConfig().apiKey }
            val testClient = OpenAiCompatibleClient(
                client = aiClient.testHttpClient(),
                configProvider = {
                    com.dskapp.data.ai.ProviderConfig(
                        baseUrl = state.baseUrl.trim(),
                        apiKey = effectiveKey,
                        model = state.model.trim()
                    )
                }
            )

            val startedAt = System.currentTimeMillis()
            val result = testClient.complete(
                systemPrompt = "당신은 연결 테스트 도우미입니다. 인사 없이 짧게 답하세요.",
                messages = listOf(
                    AiMessage(role = AiRole.USER, text = "연결 확인. '연결 성공'이라고만 답하세요.")
                ),
                // Reasoning models burn the budget on thinking first: 16 tokens was always
                // empty for DeepSeek / Kimi / GLM and showed up as a failed test.
                maxTokens = 512
            )
            val elapsed = System.currentTimeMillis() - startedAt

            _uiState.update {
                it.copy(
                    isTesting = false,
                    message = when (result) {
                        is AiResult.Success -> {
                            val usage = result.usage
                            buildString {
                                append("연결 성공 (").append(elapsed).append("ms)")
                                append(" · 모델 ").append(state.model.trim())
                                if (usage != null) {
                                    append(" · 토큰 ").append(usage.promptTokens).append('+')
                                        .append(usage.completionTokens)
                                }
                                val preview = result.text.replace('\n', ' ').trim().take(40)
                                if (preview.isNotEmpty()) append(" · \"").append(preview).append('"')
                            }
                        }
                        is AiResult.Failure -> {
                            ErrorLog.record("test", "connection test failed: ${result.error}")
                            "연결 실패: " + result.error.userMessage
                        }
                    }
                )
            }
        }
    }

    fun onCustomInstructionsChange(value: String) {
        _uiState.update { it.copy(customInstructions = value) }
    }

    fun saveCustomInstructions() {
        repository.saveCustomInstructions(_uiState.value.customInstructions)
        _uiState.update { it.copy(message = "지시문을 저장했습니다.") }
    }

    fun setThemeMode(mode: SettingsRepository.ThemeMode) {
        // Persist immediately: the theme applies app-wide, so a deferred save would flash.
        repository.saveThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun onFontScaleChange(scale: Float) {
        // Drag preview only; persisting on every frame would rewrite prefs constantly.
        _uiState.update { it.copy(fontScale = scale) }
    }

    fun saveFontScale() {
        repository.saveFontScale(_uiState.value.fontScale)
    }

    fun onPriceInputChange(value: String) {
        _uiState.update { it.copy(priceInput = value) }
    }

    fun onPriceOutputChange(value: String) {
        _uiState.update { it.copy(priceOutput = value) }
    }

    fun savePrices() {
        val state = _uiState.value
        val input = state.priceInput.trim()
        val output = state.priceOutput.trim()
        val inputPrice = parsePrice(input)
        val outputPrice = parsePrice(output)
        // Blank clears a price; anything else must be a non-negative number.
        if ((input.isNotEmpty() && inputPrice == null) || (output.isNotEmpty() && outputPrice == null)) {
            _uiState.update { it.copy(message = "단가는 0 이상의 숫자로 입력해 주세요.") }
            return
        }
        repository.savePrices(inputPrice, outputPrice)
        _uiState.update { it.copy(message = "단가를 저장했습니다.") }
    }

    fun addCommand(label: String, prompt: String) = commandRepository.add(label, prompt)
    fun updateCommand(command: PromptCommand) = commandRepository.update(command)
    fun deleteCommand(id: String) = commandRepository.delete(id)
    fun moveCommand(id: String, up: Boolean) = commandRepository.move(id, up)
    fun resetCommands() = commandRepository.resetDefaults()

    fun setAutoReadLinks(enabled: Boolean) = repository.saveAutoReadLinks(enabled)

    fun setWebSearch(enabled: Boolean) = repository.saveWebSearch(enabled)

    fun setAgentLevel(level: AgentLevel) = repository.saveAgentLevel(level)
    fun setAgentMaxSteps(steps: Int) = repository.saveAgentMaxSteps(steps)
    fun setAgentScreenshots(enabled: Boolean) = repository.saveAgentScreenshots(enabled)

    fun saveAgentAllowed(text: String) {
        val packages = text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        repository.saveAgentAllowed(packages)
        _uiState.update { it.copy(message = "허용 목록을 저장했습니다 (${packages.size}개).") }
    }

    /** Extra packages the agent must never touch (on top of the built-in finance keywords). */
    fun saveAgentBlocked(text: String) {
        val packages = text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        repository.saveAgentBlocked(packages)
        _uiState.update { it.copy(message = "차단 목록을 저장했습니다 (${packages.size}개).") }
    }

    // ---- PC goose remote (ACP over WebSocket) --------------------------------------

    fun onRemoteUrlChange(value: String) {
        _uiState.update { it.copy(remoteUrl = value, isRemoteEditing = true, remoteStatus = null) }
    }

    fun onRemoteSecretChange(value: String) {
        _uiState.update { it.copy(remoteSecret = value, isRemoteEditing = true, remoteStatus = null) }
    }

    fun onRemoteCwdChange(value: String) {
        _uiState.update { it.copy(remoteCwd = value, isRemoteEditing = true, remoteStatus = null) }
    }

    /** Persisted at once: the per-chat remote toggle reads it before a chat starts. */
    fun onRemoteModeChange(mode: String) {
        repository.saveRemoteMode(mode)
        _uiState.update { it.copy(remoteMode = mode, isRemoteEditing = false) }
    }

    /** Empties the field and tells the user to re-enter the key (blank on save keeps it). */
    fun saveRemoteSettings() {
        val state = _uiState.value
        val url = AcpJson.normalizeServerUrl(state.remoteUrl)
        if (url.isBlank()) {
            _uiState.update { it.copy(remoteStatus = "PC 주소를 입력해 주세요 (예: ws://100.64.0.5:3284/acp).") }
            return
        }
        val typed = state.remoteSecret.trim()
        val effectiveSecret = typed.ifBlank { repository.remoteSecret() }
        if (effectiveSecret.isBlank()) {
            _uiState.update { it.copy(remoteStatus = "Secret Key를 입력해 주세요 (PC의 GOOSE_SERVER__SECRET_KEY).") }
            return
        }
        repository.saveRemote(url, effectiveSecret, state.remoteCwd.trim(), state.remoteMode)
        _uiState.update {
            it.copy(
                remoteUrl = url,
                remoteSecret = "",
                remoteStatus = "저장했습니다. '연결 테스트'로 확인해 보세요.",
                isRemoteEditing = false
            )
        }
    }

    /** Opens a throwaway ACP connection, runs initialize and reports agent + mode support. */
    fun testRemote() {
        val state = _uiState.value
        if (state.isRemoteTesting) return
        val url = AcpJson.normalizeServerUrl(state.remoteUrl)
        if (url.isBlank()) {
            _uiState.update { it.copy(remoteStatus = "PC 주소를 입력해 주세요.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRemoteTesting = true, remoteStatus = "PC goose에 연결하는 중…") }
            val secret = state.remoteSecret.trim().ifBlank { repository.remoteSecret() }
            val client = AcpClient(aiClient.testHttpClient(), url, secret)
            val startedAt = System.currentTimeMillis()
            val status = try {
                client.connect()
                val init = client.initialize()
                val elapsed = System.currentTimeMillis() - startedAt
                buildString {
                    append("연결 성공 (").append(elapsed).append("ms)")
                    if (init.agentName.isNotBlank()) append(" · ").append(init.agentName)
                    if (init.agentVersion.isNotBlank()) append(' ').append(init.agentVersion)
                    append(" · 프로토콜 ").append(init.protocolVersion)
                    append(if (init.canListSessions) " · 세션 목록 지원" else " · 세션 목록 미지원")
                    append(if (init.canLoadSession) " · 이어하기 지원" else " · 이어하기 미지원")
                }
            } catch (e: Exception) {
                val msg = e.message ?: e::class.java.simpleName
                ErrorLog.record("remote", "remote connection test failed: $msg")
                "연결 실패: $msg"
            } finally {
                runCatching { client.close() }
            }
            _uiState.update { it.copy(isRemoteTesting = false, remoteStatus = status) }
        }
    }

    /** The lock needs a phone PIN / pattern / password to fall back on. */
    fun setAppLock(enabled: Boolean) {
        if (enabled && !AppLock.isDeviceSecure(appContext)) {
            _uiState.update { it.copy(message = "휴대폰 화면 잠금(PIN·패턴·비밀번호)을 먼저 설정해 주세요.") }
            return
        }
        repository.saveAppLock(enabled)
        _uiState.update { it.copy(message = if (enabled) "앱 잠금을 켰습니다." else "앱 잠금을 껐습니다.") }
    }

    fun readErrorLog(): String = ErrorLog.read()

    fun clearErrorLog() {
        ErrorLog.clear()
        refreshErrorLog()
    }

    /** The log is a plain file, so the counts are re-read whenever the screen may show them. */
    fun refreshErrorLog() {
        _uiState.update {
            it.copy(errorCount = ErrorLog.entryCount(), lastErrorTime = ErrorLog.lastEntryTime())
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupRunning = true, message = null) }
            val result = runCatching {
                val output = appContext.contentResolver.openOutputStream(uri)
                    ?: error("저장 위치를 열 수 없습니다.")
                output.use { BackupService(appContext, chatRepository).export(it) }
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { r ->
                        current.copy(
                            isBackupRunning = false,
                            message = "대화 ${r.conversations}개, 메시지 ${r.messages}개, " +
                                "파일 ${r.files}개를 백업했습니다."
                        )
                    },
                    onFailure = { e ->
                        current.copy(
                            isBackupRunning = false,
                            message = "백업 실패: ${e.message}"
                        )
                    }
                )
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBackupRunning = true, message = null) }
            val result = runCatching {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: error("파일을 열 수 없습니다.")
                input.use { BackupService(appContext, chatRepository).import(it) }
            }
            _uiState.update { current ->
                result.fold(
                    onSuccess = { r ->
                        current.copy(
                            isBackupRunning = false,
                            message = "대화 ${r.conversations}개를 불러왔습니다. " +
                                "(메시지 ${r.messages}개, 파일 ${r.files}개)"
                        )
                    },
                    onFailure = { e ->
                        current.copy(
                            isBackupRunning = false,
                            message = "불러오기 실패: ${e.message}"
                        )
                    }
                )
            }
        }
    }

    companion object {
        fun factory(
            context: Context,
            repository: SettingsRepository,
            chatRepository: ChatRepository,
            aiClient: AiClient,
            commandRepository: CommandRepository
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    context.applicationContext,
                    repository,
                    chatRepository,
                    aiClient,
                    commandRepository
                )
            }
        }

        /** Renders a stored price without exponent or trailing zeros; null -> "". */
        private fun formatPrice(value: Double?): String =
            value?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: ""

        /**
         * A non-negative price, or null for blank / unparsable text. Comma is accepted as a
         * decimal separator because some keyboards only offer it.
         */
        private fun parsePrice(text: String): Double? =
            text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }
    }
}
