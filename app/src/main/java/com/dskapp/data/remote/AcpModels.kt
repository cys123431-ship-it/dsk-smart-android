package com.dskapp.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Parsed views of the Agent Client Protocol (ACP) payloads that goose serve speaks over
 * WebSocket. Everything arrives as free-form JSON, so each model reads the fields it needs
 * defensively instead of mapping the whole schema with strict serializers: one unknown field
 * in a future goose version must not break the stream.
 *
 * The shapes below were checked against real goose 1.57.0 frames.
 */
object AcpJson {

    fun obj(text: String): JsonObject? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    }.getOrNull()

    /** Trims and strips a leading "ws://" / "http://" the user may have pasted. */
    fun normalizeServerUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        when {
            value.startsWith("https://", ignoreCase = true) ->
                value = "wss://" + value.substring("https://".length)
            value.startsWith("http://", ignoreCase = true) ->
                value = "ws://" + value.substring("http://".length)
            value.startsWith("wss://", ignoreCase = true) ->
                value = "wss://" + value.substring("wss://".length)
            value.startsWith("ws://", ignoreCase = true) ->
                value = "ws://" + value.substring("ws://".length)
            else -> value = "ws://$value"
        }
        value = value.trimEnd('/')
        // Most people paste just "100.x.x.x:3284": the ACP endpoint is always /acp.
        val withoutScheme = value.substringAfter("://")
        if (!withoutScheme.contains('/')) value += "/acp"
        return value
    }
}

/** Result of `initialize`. */
data class AcpInitialize(
    val agentName: String,
    val agentVersion: String,
    val protocolVersion: String,
    val canLoadSession: Boolean,
    val canListSessions: Boolean
)

/** One entry of `session/list`. */
data class AcpSessionSummary(
    val sessionId: String,
    val title: String,
    val updatedAt: String,
    val cwd: String,
    val messageCount: Int,
    val providerId: String,
    val modelId: String
) {
    /** Short "repo 2026-11-07 14:30" style label for the list. */
    val subtitle: String
        get() = listOf(
            cwd.substringAfterLast('/').substringAfterLast('\\'),
            updatedAt.replace('T', ' ').take(16)
        ).filter { it.isNotBlank() }.joinToString(" · ")
}

/** A selectable agent mode reported by `session/new` / `session/load`. */
data class AcpMode(val id: String, val name: String, val description: String)

/** Everything the UI needs from `session/new` or `session/load`'s result. */
data class AcpSession(
    val sessionId: String,
    val currentMode: String,
    val modes: List<AcpMode>,
    /** History replayed by `session/load`, in order. */
    val history: List<AcpHistoryItem>
) {
    companion object {
        fun from(json: JsonObject, fallbackSessionId: String = ""): AcpSession {
            val modesObject = json["modes"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val modeList = modesObject?.get("availableModes")?.let { element ->
                runCatching {
                    element.jsonArray.mapNotNull { item ->
                        val mode = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val id = mode.string("id") ?: return@mapNotNull null
                        AcpMode(id, mode.string("name") ?: id, mode.string("description").orEmpty())
                    }
                }.getOrNull()
            }.orEmpty()
            val ownId = json.string("sessionId")
            return AcpSession(
                sessionId = ownId?.takeIf { it.isNotBlank() } ?: fallbackSessionId,
                currentMode = modesObject?.string("currentModeId").orEmpty(),
                modes = modeList,
                history = emptyList()
            )
        }
    }
}

/** A completed message reconstructed from a `session/load` replay. */
data class AcpHistoryItem(val fromUser: Boolean, val text: String)

/** Token counters from `usage_update`. */
data class AcpUsage(val used: Int, val size: Int) {
    val fraction: Float get() = if (size <= 0) 0f else (used.toFloat() / size).coerceIn(0f, 1f)
}

/** A tool invocation reported by `tool_call` / `tool_call_update`. */
data class AcpToolCall(
    val toolCallId: String,
    /** "" when the frame has no title (a `tool_call_update` usually has none). */
    val title: String,
    /** "pending", "in_progress", "completed", "failed", or "" when the frame has none. */
    val status: String,
    /** "read", "edit", "execute", ... or null when goose omits it. */
    val kind: String?,
    /** Short argument summary (the shell command, or compact JSON of rawInput). */
    val detail: String = ""
) {
    val isDone: Boolean get() = status == "completed" || status == "failed"

    /** Title that is safe to show even for a frame that carries no title. */
    val displayTitle: String get() = title.ifBlank { "도구" }

    /**
     * Folds a later frame for the same id into this one. Blank/missing values in [update]
     * keep this object's value; otherwise the update wins.
     */
    fun mergedWith(update: AcpToolCall): AcpToolCall = AcpToolCall(
        toolCallId = toolCallId,
        title = update.title.ifBlank { title },
        status = update.status.ifBlank { status },
        kind = update.kind ?: kind,
        detail = update.detail.ifBlank { detail }
    )

    companion object {
        /** Builds the detail line from rawInput / content of a tool-call frame. */
        fun detailOf(update: JsonObject, limit: Int = 300): String {
            val rawInput = update["rawInput"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val command = rawInput?.string("command")
            val text = when {
                command != null -> command
                rawInput != null && rawInput.isNotEmpty() -> rawInput.toString()
                else -> AcpBlocks.text(update["content"])
            }
            return text.trim().take(limit)
        }
    }
}

/** Content-block kinds we can show. Others (images, audio) degrade to a placeholder. */
data class AcpBlock(val text: String, val isThought: Boolean)

object AcpBlocks {
    /**
     * Flattens a `content` value into viewable text blocks. goose sends a single content
     * object for message chunks and an array of `{"type":"content","content":{...}}`
     * wrappers for tool results.
     */
    fun parse(content: JsonElement?): List<AcpBlock> {
        if (content == null || content is JsonNull) return emptyList()
        val elements: List<JsonElement> = when (content) {
            is JsonArray -> content
            is JsonObject -> listOf(content)
            else -> emptyList()
        }
        return elements.flatMap { element ->
            val block = runCatching { element.jsonObject }.getOrNull() ?: return@flatMap emptyList()
            // Tool results wrap their blocks: {"type":"content","content":{...}} - keep all of them.
            if (block.string("type") == "content") parse(block["content"]) else listOfNotNull(single(block))
        }
    }

    private fun single(block: JsonObject): AcpBlock? =
        when (block.string("type")) {
            "text" -> block.string("text")
                ?.takeIf { it.isNotEmpty() }
                ?.let { AcpBlock(it, isThought = false) }
            "thinking" -> (block.string("text") ?: block.string("thinking"))
                ?.takeIf { it.isNotEmpty() }
                ?.let { AcpBlock(it, isThought = true) }
            "image" -> AcpBlock("[이미지]", isThought = false)
            "audio" -> AcpBlock("[오디오]", isThought = false)
            "resource_link" -> block.string("name")
                ?.let { AcpBlock("[$it]", isThought = false) }
            "resource" -> AcpBlock("[첨부 자료]", isThought = false)
            "diff" -> {
                val path = block.string("path")
                val label = if (path.isNullOrBlank()) "[파일 변경]" else "[파일 변경: $path]"
                AcpBlock(label, isThought = false)
            }
            else -> null
        }

    /** Flat text of a `content` value (object or array). */
    fun text(content: JsonElement?): String = parse(content).joinToString("") { it.text }
}

/**
 * One decoded `session/update` notification. [kind] is the ACP `sessionUpdate` string so the
 * caller can ignore variants it does not render and still log them.
 */
data class AcpUpdate(
    val kind: String,
    val text: String = "",
    val toolCall: AcpToolCall? = null,
    val usage: AcpUsage? = null,
    val messageId: String = "",
    val raw: JsonObject? = null
) {
    companion object {
        fun parse(params: JsonObject?): AcpUpdate? {
            if (params == null) return null
            val update = params["update"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
            val kind = update.string("sessionUpdate") ?: return null
            val messageId = update.string("messageId").orEmpty()
            return when (kind) {
                "agent_message_chunk", "agent_thought_chunk", "user_message_chunk" -> AcpUpdate(
                    kind,
                    text = AcpBlocks.text(update["content"]),
                    messageId = messageId,
                    raw = update
                )
                "tool_call" -> AcpUpdate(
                    kind,
                    toolCall = AcpToolCall(
                        toolCallId = update.string("toolCallId").orEmpty(),
                        title = update.string("title").orEmpty(),
                        status = update.string("status") ?: "pending",
                        kind = update.string("kind"),
                        detail = AcpToolCall.detailOf(update)
                    ),
                    messageId = messageId,
                    raw = update
                )
                "tool_call_update" -> AcpUpdate(
                    kind,
                    toolCall = AcpToolCall(
                        toolCallId = update.string("toolCallId").orEmpty(),
                        title = update.string("title").orEmpty(),
                        status = update.string("status").orEmpty(),
                        kind = update.string("kind"),
                        detail = AcpToolCall.detailOf(update)
                    ),
                    messageId = messageId,
                    raw = update
                )
                "usage_update" -> AcpUpdate(
                    kind,
                    usage = parseUsage(update),
                    raw = update
                )
                "current_mode_update" -> AcpUpdate(
                    kind,
                    text = update.string("currentModeId").orEmpty(),
                    raw = update
                )
                "session_info_update" -> AcpUpdate(
                    kind,
                    text = update.string("title").orEmpty(),
                    raw = update
                )
                else -> AcpUpdate(kind, raw = update)
            }
        }

        private fun parseUsage(update: JsonObject): AcpUsage? {
            val topUsed = update.long("used")
            val topSize = update.long("size")
            if (topUsed != null && topSize != null) {
                return AcpUsage(topUsed.toInt(), topSize.toInt())
            }
            // Older builds nested the counters under "usage".
            val nested = update["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: return null
            val used = nested.long("used") ?: return null
            val size = nested.long("size") ?: return null
            return AcpUsage(used.toInt(), size.toInt())
        }
    }
}

/** One option in a permission request, e.g. "allow_once". */
data class AcpPermissionOption(val id: String, val name: String, val kind: String)

/** A `session/request_permission` request that the user must allow or reject. */
data class AcpPermissionRequest(
    /** The JSON-RPC id exactly as the server sent it (string or number); the answer must echo it. */
    val requestId: JsonPrimitive,
    val sessionId: String,
    val title: String,
    val detail: String,
    val options: List<AcpPermissionOption>
) {
    /** Option that allows this one call only. Never falls back to an "always" option. */
    val allowOnceId: String?
        get() = options.firstOrNull { it.kind == "allow_once" }?.id
            ?: options.firstOrNull { it.id == "allow_once" }?.id

    /** Option that rejects this one call only. */
    val rejectOnceId: String?
        get() = options.firstOrNull { it.kind == "reject_once" }?.id
            ?: options.firstOrNull { it.id == "reject_once" }?.id

    companion object {
        fun parse(requestId: JsonPrimitive, params: JsonObject?): AcpPermissionRequest? {
            if (params == null) return null
            val toolCall = params["toolCall"]?.let { runCatching { it.jsonObject }.getOrNull() }
            val options = params["options"]?.let { element ->
                runCatching {
                    element.jsonArray.mapNotNull { option ->
                        val obj = runCatching { option.jsonObject }.getOrNull() ?: return@mapNotNull null
                        val id = obj.string("optionId") ?: return@mapNotNull null
                        val kind = obj.string("kind").orEmpty()
                        val name = obj.string("name") ?: kind.ifBlank { id }
                        AcpPermissionOption(id, name, kind)
                    }
                }.getOrNull()
            }.orEmpty()
            val detail = toolCall?.let { AcpToolCall.detailOf(it, limit = 400) }.orEmpty()
            return AcpPermissionRequest(
                requestId = requestId,
                sessionId = params.string("sessionId").orEmpty(),
                title = toolCall?.string("title") ?: "도구 실행 승인",
                detail = detail,
                options = options
            )
        }
    }
}

/** Builds the JSON-RPC `result` of a permission answer in the shape goose accepts. */
object AcpPermissionOutcome {
    fun selected(optionId: String): JsonObject = buildJsonObject {
        putJsonObject("outcome") {
            put("outcome", "selected")
            put("optionId", optionId)
        }
    }

    fun cancelled(): JsonObject = buildJsonObject {
        putJsonObject("outcome") {
            put("outcome", "cancelled")
        }
    }
}

// ---- Small defensive JSON helpers ------------------------------------------------

internal fun JsonObject.string(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.takeIf { it.isString }?.contentOrNull
        ?: if (primitive is JsonNull) null else primitive.contentOrNull
}

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.array(key: String): JsonArray? =
    runCatching { this[key]?.jsonArray }.getOrNull()
