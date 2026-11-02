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
    val appLock: Boolean = false,
    val errorCount: Int = 0,
    val lastErrorTime: String? = null
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
            usage = repository.usage.value
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshErrorLog()
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
                            appLock = settings.appLock,
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

    /** Sends a one-token prompt to verify URL + key + model together. */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, message = null) }
            val result = aiClient.complete(
                systemPrompt = "당신은 연결 테스트 도우미입니다.",
                messages = listOf(
                    AiMessage(
                        role = AiRole.USER,
                        text = "연결 테스트. '연결 성공'이라고만 답하세요."
                    )
                ),
                maxTokens = 16
            )
            _uiState.update {
                it.copy(
                    isTesting = false,
                    message = when (result) {
                        is AiResult.Success -> "연결 성공: ${result.text.take(60)}"
                        is AiResult.Failure -> result.error.userMessage
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
