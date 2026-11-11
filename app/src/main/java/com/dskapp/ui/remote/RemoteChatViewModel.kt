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
import com.dskapp.data.remote.AcpSession
import com.dskapp.data.remote.AcpSessionSummary
import com.dskapp.data.remote.AcpToolCall
import com.dskapp.data.remote.AcpUsage
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
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
    /**
     * Tool calls waiting for the user's answer, oldest first. A queue, not a single slot:
     * goose can ask about two tools at once, and an overwritten request would never be
     * answered, leaving the turn hanging.
     */
    val permissionQueue: List<AcpPermissionRequest> = emptyList(),
    val isTurnRunning: Boolean = false,
    val sessionId: String? = null,
    val sessionTitle: String = "",
    val modes: List<AcpMode> = emptyList(),
    val currentMode: String = "",
    /** Why the connection is [Connection.FAILED]; shown with the retry button. */
    val failure: String = "",
    val error: String? = null
) {
    enum class Connection { IDLE, CONNECTING, READY, FAILED }

    val pendingPermission: AcpPermissionRequest? get() = permissionQueue.firstOrNull()
}

/**
 * One remote chat with the PC's goose desktop agent.
 *
 * [open] either creates a session in the configured cwd or loads one goose already has and
 * replays its history. Output is rendered as it streams in — the `session/prompt` call only
 * returns at the end of the turn, so waiting for it would show an empty screen for minutes.
 *
 * Three things here are about safety rather than looks:
 *  - the permission mode chosen in Settings is applied with `session/set_mode` before the
 *    session is usable, and the connection is abandoned if that fails. goose starts every
 *    session in its own default ("auto": run every tool without asking), so skipping this
 *    would silently ignore "매번 승인";
 *  - "승인" answers with the *one-time* option only. goose lists `allow_always` first, and
 *    taking the first option would have switched approval off for that tool for good;
 *  - a dropped socket is shown as such, so nobody types into a session that is gone.
 */
class RemoteChatViewModel(
    private val settings: SettingsRepository,
    aiClient: AiClient
) : ViewModel() {

    private val httpClient = aiClient.testHttpClient()
    private var client: AcpClient? = null
    private var eventJob: Job? = null
    private var nextMessageId = 1L

    /** What this screen was asked to show, so re-entering it does not start over. */
    private var openedKey: String? = null
    private var openedTitle: String = ""

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    /**
     * Shows a new session ([loadSessionId] null) or an existing one. Calling it again for the
     * same target is a no-op: the screen calls this every time it comes back on screen (for
     * example after a look at the session list), and reconnecting then threw the running
     * conversation away.
     */
    fun open(loadSessionId: String?, title: String) {
        val key = loadSessionId ?: NEW_SESSION_KEY
        if (key == openedKey && _uiState.value.connection != RemoteUiState.Connection.IDLE) return
        openedKey = key
        openedTitle = title
        connectAndSession(loadSessionId, title)
    }

    /** Creates a fresh session in the configured directory. */
    fun startNew() = open(loadSessionId = null, title = "")

    /** Loads an existing goose session (its history is replayed into the transcript). */
    fun requestLoad(sessionId: String, title: String) = open(sessionId, title)

    /** Loads an existing goose session (its history is replayed into the transcript). */
    fun openExisting(summary: AcpSessionSummary) = open(summary.sessionId, summary.title)

    /**
     * Connects again after a failure. A session that already exists on the PC is reloaded
     * (goose replays its history), so a dropped connection does not lose the conversation.
     */
    fun retry() {
        val state = _uiState.value
        if (state.connection != RemoteUiState.Connection.FAILED) return
        val sessionId = state.sessionId ?: openedKey?.takeIf { it != NEW_SESSION_KEY }
        connectAndSession(sessionId, state.sessionTitle.ifBlank { openedTitle })
    }

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
                    failure = "설정 > PC goose 원격에서 주소와 Secret Key를 먼저 저장해 주세요."
                )
            }
            return
        }
        _uiState.update {
            RemoteUiState(
                connection = RemoteUiState.Connection.CONNECTING,
                statusText = "PC goose에 연결하는 중…",
                sessionTitle = title,
                // Kept so a failed reconnect can still be retried into the same session.
                sessionId = loadSessionId
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
                _uiState.update { it.copy(statusText = "권한 모드를 맞추는 중…", sessionId = session.sessionId) }
                val mode = applyMode(newClient, session, appSettings.remoteMode)
                _uiState.update {
                    it.copy(
                        connection = RemoteUiState.Connection.READY,
                        statusText = "",
                        failure = "",
                        sessionId = session.sessionId,
                        modes = session.modes,
                        currentMode = mode,
                        sessionTitle = it.sessionTitle.ifBlank {
                            if (loadSessionId == null) "새 원격 대화" else loadSessionId
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "PC goose에 연결하지 못했습니다."
                ErrorLog.record("remote", "session open failed: $message")
                if (client === newClient) fail(message)
            }
        }
    }

    /**
     * Puts the session into the permission mode chosen in Settings and returns the mode that
     * is now active. Fails closed: if the mode cannot be set, the session must not be used,
     * because it would run in goose's own default instead of the one the user picked.
     */
    private suspend fun applyMode(active: AcpClient, session: AcpSession, desired: String): String {
        val want = desired.trim()
        if (want.isEmpty() || want == session.currentMode) return session.currentMode
        if (session.modes.isNotEmpty() && session.modes.none { it.id == want }) {
            throw AcpException(
                "PC의 goose에 '$want' 권한 모드가 없어 연결을 중단했습니다. " +
                    "설정 > PC goose 원격에서 권한 모드를 다시 골라 주세요."
            )
        }
        try {
            active.setMode(session.sessionId, want)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AcpException(
                "권한 모드를 '$want'(으)로 바꾸지 못해 연결을 중단했습니다: ${e.message ?: "원인을 알 수 없음"}"
            )
        }
        return want
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
        _uiState.update {
            it.copy(
                messages = RemoteTranscript.userTurn(it.messages, trimmed, placeholder = true, newId = ::newId),
                isTurnRunning = true,
                error = null,
                permissionQueue = emptyList(),
                tools = emptyList()
            )
        }
        viewModelScope.launch {
            try {
                val stopReason = active.prompt(sessionId, trimmed)
                _uiState.update {
                    it.copy(
                        messages = RemoteTranscript.finish(it.messages),
                        isTurnRunning = false,
                        permissionQueue = emptyList(),
                        statusText = if (stopReason == "cancelled") "중단됨" else ""
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "요청을 보내지 못했습니다."
                ErrorLog.record("remote", "prompt failed: $message")
                _uiState.update {
                    it.copy(
                        messages = RemoteTranscript.finish(it.messages),
                        isTurnRunning = false,
                        permissionQueue = emptyList(),
                        // A dropped connection is already explained by the failure card.
                        error = if (it.connection == RemoteUiState.Connection.FAILED) it.error else message
                    )
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

    /**
     * Approves the tool call at the head of the queue, for this one call only. If goose did
     * not offer a one-time option the call is refused instead: quietly granting "always"
     * is exactly what this screen exists to prevent.
     */
    fun allowPermission() {
        val request = _uiState.value.pendingPermission ?: return
        val active = client ?: return
        val optionId = request.allowOnceId
        _uiState.update {
            it.copy(
                permissionQueue = it.permissionQueue.drop(1),
                error = if (optionId == null) "goose가 '이번만 허용'을 제공하지 않아 거절했습니다." else it.error
            )
        }
        viewModelScope.launch {
            runCatching { active.respondPermission(request.requestId, optionId ?: request.rejectOnceId) }
                .onFailure { ErrorLog.record("remote", "permission allow failed: ${it.message}") }
        }
    }

    /** Refuses the tool call at the head of the queue (this call only). */
    fun denyPermission() {
        val request = _uiState.value.pendingPermission ?: return
        val active = client ?: return
        _uiState.update { it.copy(permissionQueue = it.permissionQueue.drop(1)) }
        viewModelScope.launch {
            // No reject option offered: answering "cancelled" is also read as a refusal.
            runCatching { active.respondPermission(request.requestId, request.rejectOnceId) }
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
                    it.copy(permissionQueue = it.permissionQueue + event.request)
                }
                // The replay is complete: nothing more will be appended to those bubbles.
                AcpEvent.HistoryLoaded -> _uiState.update {
                    it.copy(messages = RemoteTranscript.finish(it.messages))
                }
                is AcpEvent.Disconnected -> {
                    ErrorLog.record("remote", "connection lost: ${event.reason}")
                    fail(event.reason)
                }
                is AcpEvent.Other -> ErrorLog.record("remote", "unhandled acp event: ${event.method}")
            }
        }
    }

    private fun handleUpdate(event: AcpEvent.Update) {
        val update = event.update
        when (update.kind) {
            "agent_message_chunk" -> appendChunk(update.text, thought = false)
            "agent_thought_chunk" -> appendChunk(update.text, thought = true)
            "user_message_chunk" -> {
                // Replay of session/load history. During a live turn the user's own message is
                // already on screen, so an echo is ignored.
                if (!_uiState.value.isTurnRunning && update.text.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            messages = RemoteTranscript.userTurn(
                                it.messages, update.text.trim(), placeholder = false, newId = ::newId
                            )
                        )
                    }
                }
            }
            "tool_call", "tool_call_update" -> update.toolCall?.let { call ->
                _uiState.update { state ->
                    val existing = state.tools.indexOfFirst { it.toolCallId == call.toolCallId }
                    val merged = if (existing >= 0) {
                        // Updates carry only what changed (usually just the status).
                        state.tools.toMutableList().apply { set(existing, state.tools[existing].mergedWith(call)) }
                    } else {
                        state.tools + call.copy(status = call.status.ifBlank { "pending" })
                    }
                    state.copy(tools = merged)
                }
            }
            "usage_update" -> update.usage?.let { usage ->
                _uiState.update { it.copy(usage = usage) }
            }
            "current_mode_update" -> if (update.text.isNotBlank()) {
                _uiState.update { it.copy(currentMode = update.text) }
            }
            // goose names the session after the first exchange.
            "session_info_update" -> if (update.text.isNotBlank()) {
                _uiState.update { it.copy(sessionTitle = update.text) }
            }
            else -> Unit
        }
    }

    private fun appendChunk(text: String, thought: Boolean) {
        if (text.isEmpty()) return
        _uiState.update { it.copy(messages = RemoteTranscript.append(it.messages, text, thought, ::newId)) }
    }

    private fun newId(): Long = nextMessageId++

    /** Marks the connection as gone and releases the socket; the transcript stays readable. */
    private fun fail(message: String) {
        closeClient()
        _uiState.update {
            it.copy(
                connection = RemoteUiState.Connection.FAILED,
                statusText = "",
                isTurnRunning = false,
                permissionQueue = emptyList(),
                messages = RemoteTranscript.finish(it.messages),
                failure = message
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

        private const val NEW_SESSION_KEY = "\u0000new"

        fun factory(settings: SettingsRepository, aiClient: AiClient): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { RemoteChatViewModel(settings, aiClient) }
            }
    }
}
