package com.dskapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.ai.AiClient
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.ai.userMessage
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val isSaved: Boolean = false
)

/**
 * Settings for the OpenAI-compatible endpoint. All three inputs are required, and the base
 * URL is pre-filled with River AI so the common path needs no typing beyond the key.
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    private val aiClient: AiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrl = repository.state.value.baseUrl,
            model = repository.state.value.model,
            hasStoredKey = repository.state.value.hasApiKey
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
                    com.dskapp.data.ai.AiMessage(
                        role = com.dskapp.data.ai.AiRole.USER,
                        text = "연결 테스트. '연결 성공'이라고만 답하세요."
                    )
                ),
                maxTokens = 16
            )
            _uiState.update {
                it.copy(
                    isTesting = false,
                    message = when (result) {
                        is com.dskapp.data.ai.AiResult.Success -> "연결 성공: ${result.text.take(60)}"
                        is com.dskapp.data.ai.AiResult.Failure -> result.error.userMessage
                    }
                )
            }
        }
    }

    companion object {
        fun factory(
            repository: SettingsRepository,
            aiClient: AiClient
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(repository, aiClient) }
        }
    }
}
