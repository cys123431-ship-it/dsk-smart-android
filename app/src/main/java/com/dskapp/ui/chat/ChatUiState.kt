package com.dskapp.ui.chat

import com.dskapp.data.chat.Attachment

/** UI model of a chat message (DB entity resolved to renderable form). */
data class ChatMessage(
    val id: Long,
    val isUser: Boolean,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val isThinking: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val reasoning: String? = null,
    val isBookmarked: Boolean = false
) {
    /** A real assistant answer (not an error bubble or an empty placeholder). */
    val isExportable: Boolean
        get() = !isUser && !isError && content.isNotBlank()
}

/** Everything the chat screen needs to render. */
data class ChatUiState(
    val conversationId: Long = 0L,
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val isSending: Boolean = false,
    val isImporting: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val banner: String? = null,
    val needsSetup: Boolean = false,
    /** This thread's model override; null uses [defaultModel]. */
    val threadModel: String? = null,
    val defaultModel: String = "",
    val knownModels: List<String> = emptyList()
) {
    val canSend: Boolean
        get() = !isSending && (input.isNotBlank() || pendingAttachments.isNotEmpty())

    val isEmpty: Boolean
        get() = messages.isEmpty()

    val activeModel: String
        get() = threadModel ?: defaultModel

    /** Id of the last stored assistant message, the only one that can be regenerated. */
    val regenerableId: Long?
        get() = messages.lastOrNull()?.takeIf { !it.isUser && it.id > 0 && !isSending }?.id
}
