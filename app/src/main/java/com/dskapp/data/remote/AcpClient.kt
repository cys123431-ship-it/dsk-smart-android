package com.dskapp.data.remote

import com.dskapp.BuildConfig
import com.dskapp.data.log.ErrorLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Thrown for connection / JSON-RPC failures; [message] is already user-facing Korean. */
class AcpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Agent Client Protocol client for the goose desktop agent, spoken over WebSocket.
 *
 * goose serve exposes `ws://host:3284/acp` and *only* WebSocket works for sessions
 * (HTTP POST answers `initialize` but 404s on `session/new`), so OkHttp's WebSocket is the
 * transport. Two headers are required: `X-Secret-Key` (GOOSE_SERVER__SECRET_KEY on the PC)
 * and `Acp-Connection-Id` (any UUID, one per connection).
 *
 * Everything the agent pushes without being asked — `session/update` chunks, tool calls and
 * `session/request_permission` — is delivered through [events]; the caller must keep
 * collecting it or permission requests will never be answered and the turn will hang.
 */
class AcpClient(
    baseClient: OkHttpClient,
    private val serverUrl: String,
    private val secretKey: String
) {
    private val http = baseClient.newBuilder()
        // ACP turns can idle for a long time while the agent thinks or runs tools.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    // Unlimited on purpose: a session/load replay pushes hundreds of updates in a burst, and a
    // bounded channel silently dropped the ones the UI had not consumed yet (holes in history).
    private val _events = Channel<AcpEvent>(Channel.UNLIMITED)

    /** Stream of notifications (updates, permission requests). One consumer only. */
    val events: Flow<AcpEvent> = _events.receiveAsFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedReason: String? = null

    private fun socketOrThrow(): WebSocket =
        socket ?: throw AcpException(closedReason ?: "PC goose에 연결되어 있지 않습니다.")

    /**
     * Opens the socket. Returns once the HTTP 101 handshake succeeded or throws.
     *
     * The handshake gets its own deadline: the shared HTTP client has no read timeout for
     * this socket (turns idle for minutes), so a host that accepts the TCP connection and
     * then says nothing would otherwise leave "연결하는 중…" on screen for ten minutes.
     */
    suspend fun connect() {
        try {
            kotlinx.coroutines.withTimeout(CONNECT_TIMEOUT_MS) { openSocket() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val reason = "PC가 ${CONNECT_TIMEOUT_MS / 1000}초 안에 응답하지 않습니다. " +
                "주소가 맞는지, PC와 DSK-Remote가 켜져 있는지 확인해 주세요."
            closedReason = reason
            socket = null
            throw AcpException(reason)
        }
    }

    private suspend fun openSocket(): Unit = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(AcpJson.normalizeServerUrl(serverUrl))
            .header("X-Secret-Key", secretKey)
            .header("Acp-Connection-Id", UUID.randomUUID().toString())
            .build()
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        // True once the handshake succeeded: a later close/failure is a dropped connection.
        val established = java.util.concurrent.atomic.AtomicBoolean(false)
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                established.set(true)
                if (opened.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val closed = "PC goose와의 연결이 닫혔습니다 (code $code)."
                if (closedReason == null) closedReason = closed
                // Event first, then the pending calls: the UI learns "connection lost" before
                // a waiting prompt fails, so it shows one explanation instead of two.
                if (established.get()) _events.trySend(AcpEvent.Disconnected(closed))
                failAll(closed)
                if (opened.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWith(Result.failure(AcpException("서버가 연결을 닫았습니다 (code $code).")))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val reason = when {
                    code == 401 || code == 403 ->
                        "Secret Key가 올바르지 않습니다 (HTTP $code)."
                    // The PC's front door (proxy.ps1) answers 502 itself when goose is not
                    // running behind it: "PC reachable, goose down", not a wrong address.
                    code == 502 ->
                        "PC에는 닿았지만 goose가 꺼져 있습니다 (HTTP 502). " +
                            "PC에서 DSK-Remote.bat을 다시 실행해 주세요."
                    code != null -> "PC goose가 연결을 거부했습니다 (HTTP $code)."
                    else -> "PC goose에 연결할 수 없습니다: ${t.message ?: t::class.java.simpleName}"
                }
                closedReason = reason
                ErrorLog.record("remote", "acp socket failed: ${t.message ?: "no detail"} code=$code")
                // After a successful handshake this is a dropped connection (Wi-Fi <-> LTE, PC
                // asleep): tell the UI, which otherwise keeps showing a live session.
                if (established.get()) _events.trySend(AcpEvent.Disconnected(reason))
                failAll(reason)
                if (opened.compareAndSet(false, true) && cont.isActive) {
                    cont.resumeWith(Result.failure(AcpException(reason, t)))
                }
            }
        })
        socket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
        closedReason = "연결을 닫았습니다."
        failAll("연결을 닫았습니다.")
        _events.close()
    }

    // ---- ACP methods -------------------------------------------------------------

    suspend fun initialize(): AcpInitialize {
        val result = call(
            "initialize",
            buildJsonObject {
                put("protocolVersion", 1)
                putJsonObject("clientCapabilities") {
                    putJsonObject("fs") {
                        put("readTextFile", false)
                        put("writeTextFile", false)
                    }
                    put("terminal", false)
                }
                putJsonObject("clientInfo") {
                    put("name", "DSK Smart")
                    put("version", BuildConfig.VERSION_NAME)
                }
            }
        )
        val info = result["agentInfo"]?.jsonObjectOrNull()
        val capabilities = result["agentCapabilities"]?.jsonObjectOrNull()
        val sessionCaps = capabilities?.get("sessionCapabilities")?.jsonObjectOrNull()
        return AcpInitialize(
            agentName = info?.string("name").orEmpty(),
            agentVersion = info?.string("version").orEmpty(),
            protocolVersion = (result["protocolVersion"] as? JsonPrimitive)?.content ?: "?",
            canLoadSession = capabilities?.bool("loadSession") == true,
            canListSessions = sessionCaps?.containsKey("list") == true
        )
    }

    /** Creates a session in [cwd] with no MCP servers (goose desktop's own tools are used). */
    suspend fun newSession(cwd: String, mode: String? = null): AcpSession {
        val result = call(
            "session/new",
            buildJsonObject {
                put("cwd", cwd)
                putJsonArray("mcpServers") {}
                if (!mode.isNullOrBlank()) {
                    putJsonObject("_meta") { put("mode", mode) }
                }
            }
        )
        return AcpSession.from(result)
    }

    /**
     * Loads an existing goose session and replays its history. The server may push the
     * replayed messages as `session/update` *before* the response arrives, so the caller
     * should already be collecting [events].
     */
    suspend fun loadSession(sessionId: String, cwd: String): AcpSession {
        val result = call(
            "session/load",
            buildJsonObject {
                put("sessionId", sessionId)
                put("cwd", cwd)
                putJsonArray("mcpServers") {}
            },
            // Replays a long conversation; give it more headroom than a normal call.
            timeoutMs = 120_000
        )
        // goose's session/load result carries no sessionId (session/new's does): the id that
        // was asked for is the id. Requiring one from the server made every reopen fail.
        val session = AcpSession.from(result, fallbackSessionId = sessionId)
        // Sent after the response, so it queues behind every replayed update: the UI can
        // close the last replayed bubble exactly when the history is complete.
        _events.trySend(AcpEvent.HistoryLoaded)
        return session
    }

    /**
     * Sets one of the session's config options (goose offers "provider", "model", "mode",
     * "thinking_effort") for this session only; the PC's own goose settings are untouched.
     */
    suspend fun setConfigOption(sessionId: String, configId: String, value: String) {
        call(
            "session/set_config_option",
            buildJsonObject {
                put("sessionId", sessionId)
                put("configId", configId)
                put("value", value)
            },
            timeoutMs = 20_000
        )
    }

    /**
     * Switches the permission mode ("auto", "approve", "smart_approve", "chat") of a session.
     * goose ignores a mode passed to session/new, so this call is the only way to apply it.
     */
    suspend fun setMode(sessionId: String, modeId: String) {
        call(
            "session/set_mode",
            buildJsonObject {
                put("sessionId", sessionId)
                put("modeId", modeId)
            },
            timeoutMs = 20_000
        )
    }

    suspend fun listSessions(cwd: String?): List<AcpSessionSummary> {
        val result = call(
            "session/list",
            buildJsonObject {
                if (!cwd.isNullOrBlank()) put("cwd", cwd)
            },
            timeoutMs = 60_000
        )
        val sessions = result["sessions"]?.let { runCatching { it.jsonArrayOrNull() }.getOrNull() }
            ?: return emptyList()
        return sessions.mapNotNull { element ->
            val obj = element.jsonObjectOrNull() ?: return@mapNotNull null
            val id = obj.string("sessionId") ?: return@mapNotNull null
            val meta = obj["_meta"]?.jsonObjectOrNull()
            AcpSessionSummary(
                sessionId = id,
                title = obj.string("title") ?: meta?.string("title") ?: id,
                updatedAt = obj.string("updatedAt") ?: meta?.string("lastMessageAt").orEmpty(),
                cwd = obj.string("cwd").orEmpty(),
                messageCount = meta?.int("messageCount") ?: 0,
                providerId = meta?.string("providerId").orEmpty(),
                modelId = meta?.string("modelId").orEmpty()
            )
        }.sortedByDescending { it.updatedAt }
    }

    /**
     * Sends one user turn. Returns the stop reason ("end_turn", "cancelled", ...).
     * Output arrives through [events] while this suspends, so the UI must not wait for the
     * return value before rendering.
     */
    suspend fun prompt(sessionId: String, text: String): String {
        val result = call(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                putJsonArray("prompt") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    )
                }
            },
            // A long autonomous turn (many tool calls) can run for many minutes.
            timeoutMs = 30 * 60_000
        )
        return result.string("stopReason") ?: "end_turn"
    }

    /** Asks the agent to stop the current turn. It is cooperative, not a process kill. */
    suspend fun cancel(sessionId: String) {
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "session/cancel")
            putJsonObject("params") { put("sessionId", sessionId) }
        })
    }

    /**
     * Answers a `session/request_permission` request. [optionId] is one of the option ids the
     * request offered; null reports the request as cancelled. The id is echoed exactly as the
     * server sent it (goose uses a string uuid) and the result has the nested `outcome`
     * object goose expects - the flat shape used before was read as a refusal.
     */
    suspend fun respondPermission(requestId: JsonPrimitive, optionId: String?) {
        val result = if (optionId == null) {
            AcpPermissionOutcome.cancelled()
        } else {
            AcpPermissionOutcome.selected(optionId)
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            // The helper answers a *request*, so it uses `result`, not `method`.
            put("id", requestId)
            put("result", result)
        })
    }

    // ---- JSON-RPC plumbing --------------------------------------------------------

    private suspend fun call(
        method: String,
        params: JsonObject?,
        timeoutMs: Long = 60_000
    ): JsonObject {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        try {
            send(payload)
        } catch (e: Exception) {
            // Nothing will ever answer a request that was not sent.
            pending.remove(id)
            throw e
        }
        val result = try {
            kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pending.remove(id)
            throw AcpException("PC goose가 '$method'에 응답하지 않습니다.")
        }
        return result
    }

    private fun send(payload: JsonObject) {
        val ws = socketOrThrow()
        if (!ws.send(payload.toString())) {
            throw AcpException("메시지를 보내지 못했습니다 (연결 상태 확인 필요).")
        }
    }

    private fun dispatch(text: String) {
        val message = AcpJson.obj(text)
        if (message == null) {
            ErrorLog.record("remote", "unparsable acp message: ${text.take(200)}")
            return
        }
        val idPrimitive = message["id"] as? JsonPrimitive
        if (idPrimitive != null && message.containsKey("method")) {
            // Server -> client request (permission prompt, or fs/terminal if advertised).
            handleServerRequest(idPrimitive, message)
            return
        }
        if (idPrimitive != null) {
            // Response to one of our calls.
            val idNum = idPrimitive.content.toLongOrNull() ?: return
            val deferred = pending.remove(idNum) ?: return
            if (message.containsKey("error")) {
                val error = message["error"]?.jsonObjectOrNull()
                deferred.completeExceptionally(AcpException(errorText(error)))
            } else {
                val result = message["result"]?.jsonObjectOrNull() ?: JsonObject(emptyMap())
                deferred.complete(result)
            }
            return
        }
        val method = message.string("method") ?: return
        when (method) {
            "session/update" -> {
                val update = AcpUpdate.parse(message["params"]?.jsonObjectOrNull())
                if (update != null) _events.trySend(AcpEvent.Update(update))
            }
            // A permission "request" without an id cannot be answered; nothing to show.
            "session/request_permission" -> Unit
            else -> _events.trySend(AcpEvent.Other(method, message["params"]?.jsonObjectOrNull()))
        }
    }

    private fun handleServerRequest(id: JsonPrimitive, message: JsonObject) {
        when (message.string("method")) {
            "session/request_permission" -> {
                val request = AcpPermissionRequest.parse(id, message["params"]?.jsonObjectOrNull())
                if (request != null) _events.trySend(AcpEvent.PermissionRequest(request))
            }
            else -> {
                // We advertised no fs/terminal support; answer politely instead of hanging.
                _events.trySend(AcpEvent.Other(message.string("method").orEmpty(), null))
                send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("error", buildJsonObject {
                        put("code", -32601)
                        put("message", "Method not supported by DSK Smart")
                    })
                })
            }
        }
    }

    /**
     * "message: data" from a JSON-RPC error. goose puts the useful part in `data`
     * (e.g. "Invalid params" + "cwd must be an absolute path"); showing only the message left
     * the phone with a two-word riddle.
     */
    private fun errorText(error: JsonObject?): String {
        val message = error?.string("message")?.takeIf { it.isNotBlank() } ?: "알 수 없는 오류"
        val data = (error?.get("data") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        return if (data.isNullOrEmpty() || message.contains(data)) message else "$message: ${data.take(300)}"
    }

    private fun failAll(reason: String) {
        val snapshot = pending.keys.toList()
        snapshot.forEach { id ->
            pending.remove(id)?.completeExceptionally(AcpException(reason))
        }
        if (closedReason != null) {
            // The socket is gone: stop holding callers that try again until connect() runs.
            socket = null
        }
    }

    companion object {
        /** Deadline for the WebSocket handshake; ACP calls have their own timeouts. */
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}

/** Everything the server pushes at us outside of a request/response pair. */
sealed interface AcpEvent {
    data class Update(val update: AcpUpdate) : AcpEvent
    data class PermissionRequest(val request: AcpPermissionRequest) : AcpEvent
    data class Other(val method: String, val params: JsonObject?) : AcpEvent

    /** The socket closed or failed after it had been open. No further events follow. */
    data class Disconnected(val reason: String) : AcpEvent

    /** `session/load` finished: every replayed update before this one is history. */
    data object HistoryLoaded : AcpEvent
}

internal fun JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { (this as JsonObject) }.getOrNull()

internal fun JsonElement.jsonArrayOrNull(): JsonArray? =
    runCatching { this as JsonArray }.getOrNull()
