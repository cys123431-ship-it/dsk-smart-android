package com.dskapp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.data.ai.AiClient
import com.dskapp.data.ai.AiImage
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.Prompts
import com.dskapp.data.ai.userMessage
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentCodec
import com.dskapp.data.chat.AttachmentKind
import com.dskapp.data.chat.ChatRepository
import com.dskapp.data.chat.MessageEntity
import com.dskapp.data.image.ImageProcessor
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One ChatGPT-style conversation: send text and/or images, receive a streamed answer.
 *
 * Streaming is written straight into the in-memory message list (not the DB) on every
 * delta for a smooth typing effect, then persisted once when the stream finishes. The
 * in-flight answer is a placeholder with a negative id; DB updates are merged around it so
 * the user's own message shows up immediately even while the answer is still streaming.
 */
class ChatViewModel(
    private val appContext: Context,
    private val conversationId: Long,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val aiClient: AiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            conversationId = conversationId,
            suggestions = Prompts.SUGGESTIONS,
            needsSetup = !settingsRepository.state.value.isConfigured
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var autoTitleJob: Job? = null

    init {
        observeMessages()
        observeTitle()
        observeSettings()
    }

    private fun observeMessages() {
        viewModelScope.launch {
            chatRepository.observeMessages(conversationId).collectLatest { entities ->
                val stored = entities.map { e -> e.toUi() }
                _uiState.update { state ->
                    // Keep the streaming placeholder (negative id) below the stored history.
                    state.copy(messages = stored + state.messages.filter { it.id < 0 })
                }
            }
        }
    }

    /** Shows the stored title when an existing thread is reopened. */
    private fun observeTitle() {
        viewModelScope.launch {
            chatRepository.observeConversation(conversationId).collectLatest { conversation ->
                if (conversation != null) _uiState.update { it.copy(title = conversation.title) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.state.collectLatest { settings ->
                _uiState.update { it.copy(needsSetup = !settings.isConfigured) }
            }
        }
    }

    private fun MessageEntity.toUi() = ChatMessage(
        id = id,
        isUser = role == ROLE_USER,
        content = content,
        attachments = AttachmentCodec.decode(attachmentsJson),
        isError = isError
    )

    // ---------------------------------------------------------------- input state

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun onSuggestionClick(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    fun consumeBanner() {
        _uiState.update { it.copy(banner = null) }
    }

    // ---------------------------------------------------------------- attachments

    /**
     * Imports a photo: downscale + EXIF-rotate, then keep it as a pending attachment.
     * [tempFile] is a full-size camera capture that is deleted once re-encoded.
     */
    fun attachImage(uri: Uri, tempFile: File? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val bytes = ImageProcessor.toJpegBytes(appContext, uri)
            tempFile?.let { withContext(Dispatchers.IO) { it.delete() } }
            if (bytes.isFailure) {
                _uiState.update {
                    it.copy(isImporting = false, banner = "사진을 불러오지 못했습니다.")
                }
                return@launch
            }
            val attachment = AttachmentStore.importImage(appContext, bytes.getOrThrow())
            _uiState.update {
                it.copy(
                    isImporting = false,
                    pendingAttachments = it.pendingAttachments + attachment
                )
            }
        }
    }

    fun attachTextFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val result = AttachmentStore.importTextFile(appContext, uri)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { attachment ->
                        state.copy(
                            isImporting = false,
                            pendingAttachments = state.pendingAttachments + attachment
                        )
                    },
                    onFailure = {
                        state.copy(
                            isImporting = false,
                            banner = "파일을 읽지 못했습니다. 텍스트 파일인지 확인해 주세요."
                        )
                    }
                )
            }
        }
    }

    fun removePendingAttachment(attachment: Attachment) {
        AttachmentStore.delete(attachment)
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - attachment) }
    }

    // ---------------------------------------------------------------- sending

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        if (state.needsSetup) {
            _uiState.update { it.copy(banner = "설정에서 서버 주소와 API 키를 먼저 입력해 주세요.") }
            return
        }

        val text = state.input.trim()
        val attachments = state.pendingAttachments
        val isFirstMessage = state.messages.isEmpty()

        _uiState.update { it.copy(input = "", pendingAttachments = emptyList(), isSending = true) }

        viewModelScope.launch {
            // 1. Persist the user's turn. Fails only if the thread was just deleted.
            val saved = runCatching {
                chatRepository.appendMessage(
                    conversationId = conversationId,
                    role = ROLE_USER,
                    content = text,
                    attachmentsJson = AttachmentCodec.encode(attachments)
                )
            }
            if (saved.isFailure) {
                _uiState.update {
                    it.copy(
                        input = text,
                        pendingAttachments = attachments,
                        isSending = false,
                        banner = "이 대화가 삭제되어 보낼 수 없습니다."
                    )
                }
                return@launch
            }

            // 2. Build the API history from the DB (authoritative ordering).
            val history = buildHistory()
            val placeholderId = -System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(
                        id = placeholderId,
                        isUser = false,
                        content = "",
                        isThinking = true
                    )
                )
            }

            val system = if (attachments.any { a -> a.kind == AttachmentKind.IMAGE }) {
                Prompts.SYSTEM + "\n\n" + Prompts.PHOTO_HINT
            } else {
                Prompts.SYSTEM
            }

            val builder = StringBuilder()
            var failed = false

            // 3. Stream the answer into the placeholder for a typing effect.
            val job = viewModelScope.launch {
                aiClient.stream(
                    systemPrompt = system,
                    messages = history,
                    onText = { delta ->
                        val snapshot = synchronized(builder) { builder.append(delta).toString() }
                        _uiState.update { current ->
                            current.copy(
                                messages = current.messages.map { message ->
                                    if (message.id == placeholderId) {
                                        message.copy(content = snapshot, isThinking = false)
                                    } else {
                                        message
                                    }
                                }
                            )
                        }
                    },
                    onError = { error ->
                        failed = true
                        _uiState.update { it.copy(banner = error.userMessage) }
                    }
                ).collect { }
            }
            streamJob = job

            // 4. Persist whatever arrived, even when the user pressed stop or left the screen
            //    (viewModelScope cancelled): NonCancellable lets the final write finish.
            try {
                job.join()
            } finally {
                withContext(NonCancellable) {
                    val finalText = synchronized(builder) { builder.toString() }.trim()
                    finishAnswer(placeholderId, finalText, failed, stopped = job.isCancelled)
                    if (streamJob === job) streamJob = null
                }
            }

            if (isFirstMessage) generateTitle(text)
        }
    }

    /**
     * Replaces the streaming placeholder with the stored answer. A stop before any text
     * stores nothing; a failure stores a short error bubble that is never resent to the model.
     */
    private suspend fun finishAnswer(
        placeholderId: Long,
        finalText: String,
        failed: Boolean,
        stopped: Boolean
    ) {
        val hasAnswer = finalText.isNotEmpty()
        val content = when {
            hasAnswer -> finalText
            stopped -> null
            failed -> "응답을 받지 못했습니다. 다시 시도해 주세요."
            else -> "응답이 비어 있습니다."
        }

        // The thread may have been deleted mid-answer; then there is nothing to write into.
        val persistedId = content?.let {
            runCatching {
                chatRepository.appendMessage(
                    conversationId = conversationId,
                    role = ROLE_ASSISTANT,
                    content = it,
                    isError = !hasAnswer
                )
            }.getOrNull()
        }

        _uiState.update { current ->
            val withoutPlaceholder = current.messages.filterNot { it.id == placeholderId }
            // The DB echo may already have added the stored row; never add it twice
            // (duplicate LazyColumn keys crash).
            val alreadyShown = withoutPlaceholder.any { it.id == persistedId }
            val messages = if (content == null || persistedId == null || alreadyShown) {
                withoutPlaceholder
            } else {
                withoutPlaceholder + ChatMessage(
                    id = persistedId,
                    isUser = false,
                    content = content,
                    isError = !hasAnswer
                )
            }
            current.copy(isSending = false, messages = messages)
        }
    }

    /**
     * Rebuilds the conversation in API form, resolving attachments to base64 on demand.
     *
     * Error bubbles are app text, not model output, so they are never sent. Photos and file
     * contents are only resent for the last [ATTACHMENT_WINDOW_MESSAGES] messages: resending
     * every old photo as base64 on each turn costs megabytes of upload and many tokens. Older
     * attachments are replaced by a one-line note so the model still knows they existed.
     */
    private suspend fun buildHistory(): List<AiMessage> {
        val recent = chatRepository.messages(conversationId)
            .filterNot { it.isError }
            .takeLast(MAX_HISTORY_MESSAGES)
        val attachmentWindowStart = recent.size - ATTACHMENT_WINDOW_MESSAGES

        return recent.mapIndexedNotNull { index, entity ->
            val allAttachments = AttachmentCodec.decode(entity.attachmentsJson)
            val resend = index >= attachmentWindowStart
            val attachments = if (resend) allAttachments else emptyList()

            val images = attachments
                .filter { it.kind == AttachmentKind.IMAGE }
                .mapNotNull { attachment ->
                    AttachmentStore.readBytes(attachment)?.let { bytes ->
                        AiImage(
                            mimeType = attachment.mimeType,
                            base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        )
                    }
                }

            val textWithFiles = buildString {
                if (entity.content.isNotBlank()) append(entity.content)
                if (!resend && allAttachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("(이전에 첨부한 파일: ")
                    append(allAttachments.joinToString { it.displayName })
                    append(")")
                }
                attachments.filter { it.kind == AttachmentKind.TEXT_FILE }.forEach { file ->
                    val content = AttachmentStore.readText(file)
                    if (content.isNotBlank()) {
                        if (isNotEmpty()) append("\n\n")
                        append("### 첨부 파일: ${file.displayName}\n```\n")
                        append(content)
                        append("\n```")
                    }
                }
            }

            if (textWithFiles.isBlank() && images.isEmpty()) {
                null
            } else {
                AiMessage(
                    role = if (entity.role == ROLE_USER) AiRole.USER else AiRole.ASSISTANT,
                    text = textWithFiles,
                    images = images
                )
            }
        }
    }

    /** Auto-names the thread from the first user message, like ChatGPT's history. */
    private fun generateTitle(firstMessage: String) {
        if (firstMessage.isBlank()) return
        autoTitleJob?.cancel()
        autoTitleJob = viewModelScope.launch {
            val result = aiClient.complete(
                systemPrompt = Prompts.TITLE_PROMPT,
                messages = listOf(AiMessage(AiRole.USER, firstMessage)),
                maxTokens = 64
            )
            val generated = (result as? AiResult.Success)?.text
            val title = generated
                ?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '.', '。', ' ')
                ?.takeIf { it.isNotBlank() }
                ?.take(30)
                ?: firstMessage.lineSequence().first().take(24)

            chatRepository.renameConversation(conversationId, title)
            _uiState.update { it.copy(title = title) }
        }
    }

    /**
     * Stops an in-flight answer. Cancelling closes the HTTP stream; [send] then stores the
     * text that already arrived and clears [ChatUiState.isSending].
     */
    fun stopGenerating() {
        streamJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        // Photos picked but never sent would otherwise stay in app storage.
        _uiState.value.pendingAttachments.forEach(AttachmentStore::delete)
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** How many past turns are resent; keeps long threads inside the context window. */
        private const val MAX_HISTORY_MESSAGES = 30

        /** Only the latest messages resend their photos/files (about the last two turns). */
        private const val ATTACHMENT_WINDOW_MESSAGES = 4

        fun factory(
            context: Context,
            conversationId: Long,
            chatRepository: ChatRepository,
            settingsRepository: SettingsRepository,
            aiClient: AiClient
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    appContext = context.applicationContext,
                    conversationId = conversationId,
                    chatRepository = chatRepository,
                    settingsRepository = settingsRepository,
                    aiClient = aiClient
                )
            }
        }
    }
}
