package com.dskapp.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.ai.AiClient
import com.dskapp.data.log.ErrorLog
import com.dskapp.data.remote.AcpClient
import com.dskapp.data.remote.AcpSessionSummary
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RemoteSessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<AcpSessionSummary> = emptyList(),
    val error: String? = null,
    val cwd: String = "",
    val filteredByCwd: Boolean = false,
    val loadedOnce: Boolean = false
)

/**
 * Lists the chats already stored on the PC by goose desktop, so one can be picked and
 * continued from the phone (`session/list` -> `session/load` in the chat screen).
 *
 * `session/list` is filtered by cwd when the user configured a working folder, because goose
 * groups history per directory; a filtered empty result is retried without the filter so an
 * older chat in another folder is still reachable.
 */
class RemoteSessionsViewModel(
    private val settings: SettingsRepository,
    aiClient: AiClient
) : ViewModel() {

    private val httpClient = aiClient.testHttpClient()

    private val _uiState = MutableStateFlow(RemoteSessionsUiState())
    val uiState: StateFlow<RemoteSessionsUiState> = _uiState.asStateFlow()

    fun load() {
        val appSettings = settings.state.value
        val secret = settings.remoteSecret()
        if (appSettings.remoteUrl.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(error = "설정 > PC goose 원격에서 주소와 Secret Key를 먼저 저장해 주세요.")
            }
            return
        }
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val client = AcpClient(httpClient, appSettings.remoteUrl, secret)
            try {
                client.connect()
                val init = client.initialize()
                if (!init.canListSessions) {
                    throw IllegalStateException("이 goose 버전은 세션 목록을 지원하지 않습니다. PC의 goose를 업데이트해 주세요.")
                }
                val cwd = appSettings.remoteCwd.trim()
                var sessions = client.listSessions(cwd.ifBlank { null })
                var filtered = cwd.isNotBlank()
                if (sessions.isEmpty() && cwd.isNotBlank()) {
                    sessions = client.listSessions(null)
                    filtered = false
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sessions = sessions,
                        cwd = cwd,
                        filteredByCwd = filtered,
                        loadedOnce = true
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "세션 목록을 불러오지 못했습니다."
                ErrorLog.record("remote", "session/list failed: $message")
                _uiState.update { it.copy(isLoading = false, error = message, loadedOnce = true) }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    companion object {
        fun factory(settings: SettingsRepository, aiClient: AiClient): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { RemoteSessionsViewModel(settings, aiClient) }
            }
    }
}
