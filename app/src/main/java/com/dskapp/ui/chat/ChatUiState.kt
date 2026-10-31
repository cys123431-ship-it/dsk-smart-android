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
    val isThinking: Boolean = false
)

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
    val needsSetup: Boolean = false
) {
    val canSend: Boolean
        get() = !isSending && (input.isNotBlank() || pendingAttachments.isNotEmpty())

    val isEmpty: Boolean
        get() = messages.isEmpty()
}
