package com.dskapp.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.ai.AiClient
import com.dskapp.data.log.ErrorLog
import com.dskapp.data.remote.AcpClient
import com.dskapp.data.remote.AcpEvent
import com.dskapp.data.remote.AcpException
import com.dskapp.data.remote.AcpMode
import com.dskapp.data.remote.AcpPermissionRequest
import com.dskapp.data.remote.AcpSessionSummary
import com.dskapp.data.remote.AcpToolCall
import com.dskapp.data.remote.AcpUsage
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line in the remote transcript. */
data class RemoteMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Hidden reasoning streamed by the agent (agent_thought_chunk). */
    val isThought: Boolean = false,
    /** True while chunks are still appending to this message. */
    val streaming: Boolean = false
)

data class RemoteUiState(
    val connection: Connection = Connection.IDLE,
    val statusText: String = "",
    val agentLabel: String = "",
    val messages: List<RemoteMessage> = emptyList(),
    val tools: List<AcpToolCall> = emptyList(),
    val usage: AcpUsage? = null,
    val pendingPermission: AcpPermissionRequest? = null,
    val isTurnRunning: Boolean = false,
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val modes: List<AcpMode> = emptyList(),
    val currentMode: String = "",
    val error: String? = null
) {
    enum class Connection { IDLE, CONNECTING, READY, FAILED }
}

/**
 * One remote chat with the PC's goose desktop agent.
 *
 * Both entry points end up in the same state: [startNew] creates a session in the configured
 * cwd, [openExisting] loads one of the sessions goose already has and replays its history.
 * Output is rendered as it streams in — the `session/prompt` call only returns at the end of
 * the turn, so waiting for it would show an empty screen for minutes.
 */
class RemoteChatViewModel(
    private val settings: SettingsRepository,
    aiClient: AiClient
) : ViewModel() {

    private val httpClient = aiClient.testHttpClient()
    private var client: AcpClient? = null
    private var eventJob: Job? = null
    private var nextMessageId = 1L

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    /** Creates a fresh session in the configured directory. */
    fun startNew() = connectAndSession(loadSessionId = null)

    /** Loads an existing goose session (its history is replayed into the transcript). */
    fun requestLoad(sessionId: String, title: String) = connectAndSession(loadSessionId = sessionId, title = title)

    /** Loads an existing goose session (its history is replayed into the transcript). */
    fun openExisting(summary: AcpSessionSummary) =
        connectAndSession(loadSessionId = summary.sessionId, title = summary.title)

    private fun connectAndSession(loadSessionId: String?, title: String = "") {
        if (_uiState.value.connection == RemoteUiState.Connection.CONNECTING) return
        closeClient()
        val appSettings = settings.state.value
        val secret = settings.remoteSecret()
        val url = appSettings.remoteUrl
        if (url.isBlank() || secret.isBlank()) {
            _uiState.update {
                it.copy(
                    connection = RemoteUiState.Connection.FAILED,
                    statusText = "",
                    error = "설정 > PC goose 원격에서 주소와 Secret Key를 먼저 저장해 주세요."
                )
            }
            return
        }
        _uiState.update {
            RemoteUiState(
                connection = RemoteUiState.Connection.CONNECTING,
                statusText = "PC goose에 연결하는 중…",
                sessionTitle = title
            )
        }
        viewModelScope.launch {
            val newClient = AcpClient(httpClient, url, secret)
            client = newClient
            // Collect first: session/update and permission requests arrive during other calls.
            eventJob = viewModelScope.launch { collectEvents(newClient) }
            try {
                newClient.connect()
                val init = newClient.initialize()
                _uiState.update {
                    it.copy(
                        agentLabel = listOf(init.agentName, init.agentVersion)
                            .filter { part -> part.isNotBlank() }
                            .joinToString(" ")
                    )
                }
                if (!init.canLoadSession && loadSessionId != null) {
                    throw AcpException("이 goose는 세션 이어하기를 지원하지 않습니다.")
                }
                if (loadSessionId != null && !init.canListSessions) {
                    throw AcpException("이 goose는 세션 목록을 지원하지 않습니다 (goose 업데이트 필요).")
                }
                val cwd = effectiveCwd(appSettings.remoteCwd)
                val session = if (loadSessionId == null) {
                    newClient.newSession(cwd)
                } else {
                    _uiState.update {
                        it.copy(statusText = "대화 기록을 불러오는 중…", messages = emptyList())
                    }
                    newClient.loadSession(loadSessionId, cwd)
                }
                if (session.sessionId.isBlank()) {
                    throw AcpException("서버가 세션 ID를 주지 않았습니다.")
                }
                _uiState.update {
                    it.copy(
                        connection = RemoteUiState.Connection.READY,
                        statusText = "",
                        sessionId = session.sessionId,
                        modes = session.modes,
                        currentMode = session.currentMode.ifBlank { appSettings.remoteMode },
                        sessionTitle = it.sessionTitle.ifBlank {
                            if (loadSessionId == null) "새 원격 대화" else loadSessionId
                        }
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "PC goose에 연결하지 못했습니다."
                ErrorLog.record("remote", "session open failed: $message")
                _uiState.update {
                    it.copy(
                        connection = RemoteUiState.Connection.FAILED,
                        statusText = "",
                        error = message
                    )
                }
            }
        }
    }

    /** Sends one user turn and streams the answer. */
    fun send(text: String) {
        val state = _uiState.value
        val active = client
        val sessionId = state.sessionId
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        when {
            state.isTurnRunning -> return
            state.connection != RemoteUiState.Connection.READY || active == null || sessionId.isNullOrBlank() -> {
                _uiState.update { it.copy(error = "먼저 PC에 연결해 주세요.") }
                return
            }
        }
        appendMessage(RemoteMessage(id = nextMessageId++, fromUser = true, text = trimmed))
        appendMessage(RemoteMessage(id = nextMessageId++, fromUser = false, text = "", streaming = true))
        _uiState.update {
            it.copy(isTurnRunning = true, error = null, pendingPermission = null, tools = emptyList())
        }
        viewModelScope.launch {
            try {
                val stopReason = active.prompt(sessionId, trimmed)
                finishStreaming()
                _uiState.update {
                    it.copy(
                        isTurnRunning = false,
                        pendingPermission = null,
                        statusText = if (stopReason == "cancelled") "중단됨" else ""
                    )
                }
            } catch (e: Exception) {
                val message = e.message ?: "요청을 보내지 못했습니다."
                ErrorLog.record("remote", "prompt failed: $message")
                finishStreaming()
                _uiState.update {
                    it.copy(isTurnRunning = false, pendingPermission = null, error = message)
                }
            }
        }
    }

    fun cancel() {
        val active = client ?: return
        val sessionId = _uiState.value.sessionId ?: return
        viewModelScope.launch {
            runCatching { active.cancel(sessionId) }
                .onFailure { ErrorLog.record("remote", "cancel failed: ${it.message}") }
            _uiState.update { it.copy(statusText = "중단하는 중…") }
        }
    }

    /** Approves the pending tool call with the first option goose offered (usually "allow"). */
    fun allowPermission() {
        val request = _uiState.value.pendingPermission ?: return
        val active = client ?: return
        _uiState.update { it.copy(pendingPermission = null) }
        viewModelScope.launch {
            runCatching { active.respondPermission(request.requestId, request.options.firstOrNull()?.first) }
                .onFailure { ErrorLog.record("remote", "permission allow failed: ${it.message}") }
        }
    }

    fun denyPermission() {
        val request = _uiState.value.pendingPermission ?: return
        val active = client ?: return
        _uiState.update { it.copy(pendingPermission = null) }
        viewModelScope.launch {
            runCatching { active.respondPermission(request.requestId, null, cancelled = true) }
                .onFailure { ErrorLog.record("remote", "permission deny failed: ${it.message}") }
        }
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    private suspend fun collectEvents(active: AcpClient) {
        active.events.collect { event ->
            if (client !== active) return@collect
            when (event) {
                is AcpEvent.Update -> handleUpdate(event)
                is AcpEvent.PermissionRequest -> _uiState.update {
                    it.copy(pendingPermission = event.request)
                }
                is AcpEvent.Other -> ErrorLog.record("remote", "unhandled acp event: ${event.method}")
            }
        }
    }

    private fun handleUpdate(event: AcpEvent.Update) {
        val update = event.update
        when (update.kind) {
            "agent_message_chunk" -> if (update.text.isNotEmpty()) appendToStreaming(update.text)
            "agent_thought_chunk" -> if (update.text.isNotEmpty()) appendToStreaming(update.text, thought = true)
            "user_message_chunk" -> {
                // Replay of session/load history (or an echo); only add when not streaming.
                if (!_uiState.value.isTurnRunning && update.text.isNotBlank()) {
                    appendMessage(RemoteMessage(id = nextMessageId++, fromUser = true, text = update.text))
                    appendMessage(RemoteMessage(id = nextMessageId++, fromUser = false, text = "", streaming = true))
                }
            }
            "tool_call", "tool_call_update" -> update.toolCall?.let { call ->
                _uiState.update { state ->
                    val existing = state.tools.indexOfFirst { it.toolCallId == call.toolCallId }
                    val merged = if (existing >= 0) {
                        // Updates may omit the title; keep the one we already have.
                        val old = state.tools[existing]
                        state.tools.toMutableList().apply {
                            set(existing, call.copy(title = call.title.ifBlank { old.title }))
                        }
                    } else {
                        state.tools + call
                    }
                    state.copy(tools = merged)
                }
            }
            "usage_update" -> update.usage?.let { usage ->
                _uiState.update { it.copy(usage = usage) }
            }
            "available_commands_update" -> Unit
        }
    }

    private fun appendMessage(message: RemoteMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    private fun appendToStreaming(text: String, thought: Boolean = false) {
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            val index = messages.indexOfLast { !it.fromUser && it.streaming }
            if (index < 0) {
                messages += RemoteMessage(nextMessageId++, fromUser = false, text = text, streaming = true, isThought = thought)
            } else {
                val current = messages[index]
                messages[index] = current.copy(text = current.text + text).copy(isThought = current.isThought && thought)
            }
            state.copy(messages = messages)
        }
    }

    private fun finishStreaming() {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { if (it.streaming) it.copy(streaming = false) else it }
                    // A turn that produced nothing but an empty bubble would look broken.
                    .filter { it.fromUser || it.text.isNotBlank() }
            )
        }
    }

    private fun effectiveCwd(configured: String): String =
        configured.trim().ifBlank { DEFAULT_REMOTE_CWD }

    private fun closeClient() {
        eventJob?.cancel()
        eventJob = null
        runCatching { client?.close() }
        client = null
    }

    override fun onCleared() {
        closeClient()
        super.onCleared()
    }

    companion object {
        /**
         * goose defaults cwd to the directory it was started in; the CLI expands "~" in
         * cwd on the server side, so this is a path the PC can resolve without knowing the
         * phone's filesystem.
         */
        const val DEFAULT_REMOTE_CWD = "~"

        fun factory(settings: SettingsRepository, aiClient: AiClient): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { RemoteChatViewModel(settings, aiClient) }
            }
    }
}

