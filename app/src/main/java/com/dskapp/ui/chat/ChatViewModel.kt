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
import com.dskapp.data.ai.TokenUsage
import com.dskapp.data.ai.userMessage
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.attachment.DocumentImporter
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
 * One ChatGPT-style conversation: send text and/or images, receive a streamed answer,
 * regenerate the last answer or edit an earlier message.
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
        observeConversation()
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

    /** Title and per-thread model come from the conversation row. */
    private fun observeConversation() {
        viewModelScope.launch {
            chatRepository.observeConversation(conversationId).collectLatest { conversation ->
                if (conversation != null) {
                    _uiState.update { it.copy(title = conversation.title, threadModel = conversation.model) }
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.state.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        needsSetup = !settings.isConfigured,
                        defaultModel = settings.model,
                        knownModels = settings.knownModels
                    )
                }
            }
        }
    }

    private fun MessageEntity.toUi() = ChatMessage(
        id = id,
        isUser = role == ROLE_USER,
        content = content,
        attachments = AttachmentCodec.decode(attachmentsJson),
        isError = isError,
        promptTokens = promptTokens,
        completionTokens = completionTokens
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

    /** Picks the model for this thread only; null or the default model clears the override. */
    fun setThreadModel(model: String?) {
        val override = model?.takeIf { it.isNotBlank() && it != _uiState.value.defaultModel }
        viewModelScope.launch { chatRepository.setModel(conversationId, override) }
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

    /** Text, Word, PowerPoint or PDF; PDFs without a text layer become page images. */
    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            val result = DocumentImporter.import(appContext, uri)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { attachments ->
                        state.copy(
                            isImporting = false,
                            pendingAttachments = state.pendingAttachments + attachments
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isImporting = false,
                            banner = error.message ?: "파일을 읽지 못했습니다."
                        )
                    }
                )
            }
        }
    }

    /** Content shared from another app: text goes to the input, files become attachments. */
    fun acceptShare(text: String?, uris: List<Uri>) {
        if (!text.isNullOrBlank()) {
            _uiState.update { state ->
                val joined = if (state.input.isBlank()) text.trim() else state.input + "\n" + text.trim()
                state.copy(input = joined)
            }
        }
        uris.forEach { uri ->
            val type = runCatching { appContext.contentResolver.getType(uri) }.getOrNull().orEmpty()
            if (type.startsWith("image/")) attachImage(uri) else attachDocument(uri)
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
            if (!persistUserTurn(text, attachments)) {
                _uiState.update {
                    it.copy(input = text, pendingAttachments = attachments, banner = "이 대화가 삭제되어 보낼 수 없습니다.")
                }
                return@launch
            }
            streamAnswer(photoAttached = attachments.any { it.kind == AttachmentKind.IMAGE })
            if (isFirstMessage) generateTitle(text)
        }
    }

    /** Throws away the last answer and asks again with the same history. */
    fun regenerate() {
        val state = _uiState.value
        val targetId = state.regenerableId ?: return
        if (state.needsSetup) return
        _uiState.update { it.copy(isSending = true) }

        viewModelScope.launch {
            chatRepository.truncateFrom(conversationId, targetId)
            val lastUser = chatRepository.messages(conversationId).lastOrNull { it.role == ROLE_USER }
            val photo = lastUser?.let { m ->
                AttachmentCodec.decode(m.attachmentsJson).any { it.kind == AttachmentKind.IMAGE }
            } ?: false
            streamAnswer(photoAttached = photo)
        }
    }

    /**
     * Replaces a user message with [newText] and drops everything after it, then answers
     * again. The message keeps its attachments.
     */
    fun editMessage(messageId: Long, newText: String) {
        val state = _uiState.value
        if (state.isSending || state.needsSetup) return
        val original = state.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val text = newText.trim()
        if (text.isEmpty() && original.attachments.isEmpty()) return
        _uiState.update { it.copy(isSending = true) }

        viewModelScope.launch {
            chatRepository.truncateFrom(
                conversationId,
                messageId,
                keepPaths = original.attachments.map { it.localPath }.toSet()
            )
            if (!persistUserTurn(text, original.attachments)) return@launch
            streamAnswer(photoAttached = original.attachments.any { it.kind == AttachmentKind.IMAGE })
        }
    }

    /** Stores the user's turn; false (and sending reset) when the thread no longer exists. */
    private suspend fun persistUserTurn(text: String, attachments: List<Attachment>): Boolean {
        val saved = runCatching {
            chatRepository.appendMessage(
                conversationId = conversationId,
                role = ROLE_USER,
                content = text,
                attachmentsJson = AttachmentCodec.encode(attachments)
            )
        }
        if (saved.isFailure) _uiState.update { it.copy(isSending = false) }
        return saved.isSuccess
    }

    /** Streams an answer to the stored history into a placeholder, then persists it. */
    private suspend fun streamAnswer(photoAttached: Boolean) {
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

        val model = _uiState.value.threadModel
        val context = buildContext(model)
        val system = buildSystemPrompt(context.summary, photoAttached)

        val builder = StringBuilder()
        var failed = false
        var usage: TokenUsage? = null

        val job = viewModelScope.launch {
            aiClient.stream(
                systemPrompt = system,
                messages = context.messages,
                model = model,
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
                },
                onUsage = { usage = it }
            ).collect { }
        }
        streamJob = job

        // Persist whatever arrived, even when the user pressed stop or left the screen
        // (viewModelScope cancelled): NonCancellable lets the final write finish.
        try {
            job.join()
        } finally {
            withContext(NonCancellable) {
                val finalText = synchronized(builder) { builder.toString() }.trim()
                finishAnswer(placeholderId, finalText, failed, stopped = job.isCancelled, usage = usage)
                if (streamJob === job) streamJob = null
            }
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
        stopped: Boolean,
        usage: TokenUsage?
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
                    isError = !hasAnswer,
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens
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
                    isError = !hasAnswer,
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens
                )
            }
            current.copy(isSending = false, messages = messages)
        }
    }

    private fun buildSystemPrompt(summary: String?, photoAttached: Boolean): String = buildString {
        append(Prompts.SYSTEM)
        val custom = settingsRepository.state.value.customInstructions
        if (custom.isNotBlank()) {
            append("\n\n").append(Prompts.CUSTOM_INSTRUCTIONS_HEADER).append('\n').append(custom)
        }
        if (!summary.isNullOrBlank()) {
            append("\n\n").append(Prompts.SUMMARY_HEADER).append('\n').append(summary)
        }
        if (photoAttached) append("\n\n").append(Prompts.PHOTO_HINT)
    }

    private class HistoryContext(val summary: String?, val messages: List<AiMessage>)

    /**
     * Rebuilds the conversation in API form.
     *
     * Error bubbles are app text, not model output, so they are never sent. When more than
     * [MAX_HISTORY_MESSAGES] messages are not yet covered by the thread's summary, the older
     * ones are folded into the summary (one extra short call) and only the latest
     * [KEEP_AFTER_SUMMARY] are sent in full. If summarising fails, the oldest messages are
     * simply dropped as before.
     */
    private suspend fun buildContext(model: String?): HistoryContext {
        val all = chatRepository.messages(conversationId).filterNot { it.isError }
        val conversation = chatRepository.conversation(conversationId)
        var summary = conversation?.summary
        val upTo = conversation?.summaryUpToId ?: 0L
        var pending = all.filter { it.id > upTo }

        if (pending.size > MAX_HISTORY_MESSAGES) {
            val toFold = pending.dropLast(KEEP_AFTER_SUMMARY)
            val folded = summarize(summary, toFold, model)
            if (folded != null) {
                runCatching { chatRepository.setSummary(conversationId, folded, toFold.last().id) }
                summary = folded
                pending = pending.takeLast(KEEP_AFTER_SUMMARY)
            } else {
                pending = pending.takeLast(MAX_HISTORY_MESSAGES)
            }
        }

        return HistoryContext(summary, toAiMessages(pending))
    }

    private suspend fun summarize(previous: String?, messages: List<MessageEntity>, model: String?): String? {
        val transcript = buildString {
            if (!previous.isNullOrBlank()) append("[이전 요약]\n").append(previous).append("\n\n")
            append("[대화]\n")
            messages.forEach { m ->
                append(if (m.role == ROLE_USER) "사용자: " else "AI: ")
                append(m.content.take(SUMMARY_CHARS_PER_MESSAGE))
                val names = AttachmentCodec.decode(m.attachmentsJson).map { it.displayName }
                if (names.isNotEmpty()) append(" (첨부: ").append(names.joinToString()).append(')')
                append('\n')
            }
        }
        val result = aiClient.complete(
            systemPrompt = Prompts.SUMMARY_PROMPT,
            messages = listOf(AiMessage(AiRole.USER, transcript)),
            maxTokens = SUMMARY_MAX_TOKENS,
            model = model
        )
        return (result as? AiResult.Success)?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Photos and file contents are only resent for the last [ATTACHMENT_WINDOW_MESSAGES]
     * messages: resending every old photo as base64 on each turn costs megabytes of upload
     * and many tokens. Older attachments are replaced by a one-line note.
     */
    private suspend fun toAiMessages(recent: List<MessageEntity>): List<AiMessage> {
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
                maxTokens = 64,
                model = _uiState.value.threadModel
            )
            val generated = (result as? AiResult.Success)?.text
            val title = generated
                ?.lineSequence()?.firstOrNull()?.trim()?.trim('"', '.', '。', ' ')
                ?.takeIf { it.isNotBlank() }
                ?.take(30)
                ?: firstMessage.lineSequence().first().take(24)

            runCatching { chatRepository.renameConversation(conversationId, title) }
        }
    }

    /**
     * Stops an in-flight answer. Cancelling closes the HTTP stream; the sender then stores
     * the text that already arrived and clears [ChatUiState.isSending].
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

        /** Unsummarised messages allowed before old ones are folded into the summary. */
        private const val MAX_HISTORY_MESSAGES = 30

        /** Messages kept verbatim after a fold, so the latest turns stay exact. */
        private const val KEEP_AFTER_SUMMARY = 10

        /** Only the latest messages resend their photos/files (about the last two turns). */
        private const val ATTACHMENT_WINDOW_MESSAGES = 4

        private const val SUMMARY_MAX_TOKENS = 1024
        private const val SUMMARY_CHARS_PER_MESSAGE = 4000

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
