package com.dskapp.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dskapp.agent.AgentBridge
import com.dskapp.data.ai.AiClient
import com.dskapp.data.ai.AiError
import com.dskapp.data.ai.AiImage
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.Prompts
import com.dskapp.data.ai.ThinkTags
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
import com.dskapp.data.link.LinkReader
import com.dskapp.data.link.UrlFinder
import com.dskapp.data.log.ErrorLog
import com.dskapp.data.search.SearchProtocol
import com.dskapp.data.search.WebSearchRunner
import com.dskapp.data.settings.SettingsRepository
import com.dskapp.di.ServiceLocator
import kotlinx.coroutines.CancellationException
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
    private val aiClient: AiClient,
    private val linkReader: LinkReader,
    private val webSearch: WebSearchRunner
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
        completionTokens = completionTokens,
        reasoning = reasoning,
        isBookmarked = isBookmarked
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

    /** Stars or un-stars an answer for the 즐겨찾기 list. */
    fun toggleBookmark(messageId: Long) {
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.id > 0 } ?: return
        viewModelScope.launch {
            runCatching { chatRepository.setBookmarked(messageId, !message.isBookmarked) }
        }
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
                val error = bytes.exceptionOrNull()
                ErrorLog.record("photo", error?.message ?: "사진을 불러오지 못했습니다.", error)
                _uiState.update {
                    it.copy(isImporting = false, banner = "사진을 불러오지 못했습니다: ${error?.message ?: "알 수 없는 오류"}")
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

    /** Several photos picked at once; see [importBatch]. */
    fun attachImages(uris: List<Uri>) = importBatch(uris, what = "사진") { uri ->
        val bytes = ImageProcessor.toJpegBytes(appContext, uri).getOrThrow()
        listOf(AttachmentStore.importImage(appContext, bytes))
    }

    /** Several documents picked at once; see [importBatch]. */
    fun attachDocuments(uris: List<Uri>) = importBatch(uris, what = "파일") { uri ->
        DocumentImporter.import(appContext, uri).getOrThrow()
    }

    /**
     * Imports [uris] one after another in a single job.
     *
     * One job instead of one per item: separate jobs each switched "불러오는 중" off when they
     * finished, so the indicator flickered and the send button came back while photos were
     * still being processed. Items appear as soon as each is ready; failures are collected
     * into one message instead of one snackbar per file. At most [MAX_PENDING_ATTACHMENTS]
     * attachments wait for a message - every photo is uploaded with it, so a large pile makes
     * the request slow and expensive.
     */
    private fun importBatch(uris: List<Uri>, what: String, import: suspend (Uri) -> List<Attachment>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = (MAX_PENDING_ATTACHMENTS - _uiState.value.pendingAttachments.size).coerceAtLeast(0)
            val accepted = uris.distinct().take(room)
            val skipped = uris.distinct().size - accepted.size
            _uiState.update { it.copy(isImporting = true) }
            val failures = mutableListOf<String>()
            for (uri in accepted) {
                try {
                    val added = import(uri)
                    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + added) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val name = AttachmentStore.displayName(appContext, uri) ?: what
                    ErrorLog.record("attach", "$name: ${e.message}", e)
                    failures += "$name (${e.message ?: "알 수 없는 오류"})"
                }
            }
            val notes = buildList {
                if (failures.isNotEmpty()) {
                    add(
                        if (accepted.size == 1) {
                            "${what}을(를) 불러오지 못했습니다: ${failures.first()}"
                        } else {
                            "${accepted.size}개 중 ${failures.size}개를 불러오지 못했습니다: " +
                                failures.take(3).joinToString(", ") + if (failures.size > 3) " 외" else ""
                        }
                    )
                }
                if (skipped > 0) add("첨부는 한 번에 ${MAX_PENDING_ATTACHMENTS}개까지라 ${skipped}개는 넣지 않았습니다.")
            }
            _uiState.update {
                it.copy(isImporting = false, banner = notes.joinToString("\n").ifEmpty { it.banner })
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
        val (images, documents) = uris.partition { uri ->
            runCatching { appContext.contentResolver.getType(uri) }.getOrNull().orEmpty().startsWith("image/")
        }
        attachImages(images)
        attachDocuments(documents)
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

        if (state.agentMode) {
            startAgent(text, isFirstMessage)
            return
        }

        _uiState.update { it.copy(input = "", pendingAttachments = emptyList(), isSending = true) }

        viewModelScope.launch {
            val withLinks = attachments + readLinks(text)
            if (!persistUserTurn(text, withLinks)) {
                // The pages read for this turn belong to nothing now.
                AttachmentStore.deleteAll(withLinks.drop(attachments.size))
                _uiState.update {
                    it.copy(input = text, pendingAttachments = attachments, banner = "이 대화가 삭제되어 보낼 수 없습니다.")
                }
                return@launch
            }
            streamAnswer(photoAttached = attachments.any { it.kind == AttachmentKind.IMAGE })
            if (isFirstMessage) generateTitle(text)
        }
    }

    fun toggleAgentMode() {
        _uiState.update { it.copy(agentMode = !it.agentMode) }
    }

    /**
     * Hands [goal] to the phone agent. The run happens in the accessibility service while
     * other apps are in front; its record arrives in this thread when it ends.
     */
    private fun startAgent(goal: String, isFirstMessage: Boolean) {
        if (goal.isBlank()) return
        if (!AgentBridge.isEnabled) {
            _uiState.update { it.copy(banner = "설정 > 폰 조작에서 접근성 권한을 먼저 켜 주세요.") }
            return
        }
        // Checked before the goal is stored: a refused start used to leave the "📱 goal"
        // message in the thread, and sending again added it a second time.
        if (AgentBridge.running.value) {
            _uiState.update { it.copy(banner = "이미 폰 조작이 진행 중입니다.") }
            return
        }
        _uiState.update { it.copy(input = "", agentMode = false) }
        viewModelScope.launch {
            if (!persistUserTurn("📱 $goal", emptyList())) return@launch
            val started = AgentBridge.start(appContext, goal, conversationId, _uiState.value.threadModel)
            if (!started) {
                _uiState.update { it.copy(input = goal, agentMode = true, banner = "이미 폰 조작이 진행 중입니다.") }
            } else if (isFirstMessage) {
                generateTitle(goal)
            }
        }
    }

    /**
     * Fetches the pages behind URLs in [text] and turns each into a text attachment
     * ("🔗 title"), so the answer can use the page and the resend window treats it like a
     * file. A link that cannot be read is reported but never blocks the message.
     */
    private suspend fun readLinks(text: String): List<Attachment> {
        if (!settingsRepository.state.value.autoReadLinks) return emptyList()
        val urls = UrlFinder.find(text, max = MAX_LINKS)
        if (urls.isEmpty()) return emptyList()

        _uiState.update { it.copy(isImporting = true) }
        val attachments = urls.mapNotNull { url ->
            linkReader.read(url).fold(
                onSuccess = { page ->
                    val name = "🔗 " + (page.title ?: page.url.substringAfter("://").substringBefore('/'))
                    AttachmentStore.saveText(appContext, name.take(60), "출처: ${page.url}\n\n${page.text}")
                },
                onFailure = { error ->
                    ErrorLog.record("link", "$url: ${error.message}", error)
                    _uiState.update { it.copy(banner = "링크를 읽지 못했습니다: ${error.message}") }
                    null
                }
            )
        }
        _uiState.update { it.copy(isImporting = false) }
        return attachments
    }

    /**
     * The error text for the chat. When this thread has its own model and that model is the
     * one the server no longer has, changing the default in Settings would not help - the
     * thread keeps its override - so the user is told where to change it.
     */
    private fun bannerFor(error: AiError, threadModel: String?): String =
        if (error is AiError.ModelUnavailable && !threadModel.isNullOrBlank()) {
            "이 대화는 모델을 따로 '$threadModel'(으)로 정해 두었는데, 이 AI 서버에서 더 이상 쓸 수 없습니다. " +
                "화면 위쪽의 모델 이름을 눌러 다른 모델이나 기본 모델로 바꿔 주세요."
        } else {
            error.userMessage
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
        val searchEnabled = settingsRepository.state.value.webSearch
        val system = buildSystemPrompt(context.summary, photoAttached, searchEnabled)

        val builder = StringBuilder()
        val thoughts = StringBuilder()
        var failed = false
        // The last round stopped at the token limit: the answer is cut off, not finished.
        var truncated = false
        var usage: TokenUsage? = null
        var status: String? = null
        // Search results the answer used; stored with it so follow-up questions still see them.
        val searchFiles = mutableListOf<Attachment>()

        // Shows the streamed answer and thinking so far; inline <think> blocks are split off.
        // A reply that may still turn into a <search> request is held back.
        fun render() {
            val (raw, streamed) = synchronized(builder) { builder.toString() to thoughts.toString() }
            val (answer, reasoning) = splitReasoning(raw, streamed)
            val shown = if (searchEnabled && SearchProtocol.mayBeRequest(answer)) "" else answer
            _uiState.update { current ->
                current.copy(
                    messages = current.messages.map { message ->
                        if (message.id == placeholderId) {
                            message.copy(
                                content = shown,
                                reasoning = reasoning,
                                isThinking = shown.isEmpty(),
                                status = status
                            )
                        } else {
                            message
                        }
                    }
                )
            }
        }

        val job = viewModelScope.launch {
            var messages = context.messages
            var round = 0
            while (true) {
                truncated = false
                aiClient.stream(
                    systemPrompt = system,
                    messages = messages,
                    model = model,
                    onText = { delta ->
                        synchronized(builder) { builder.append(delta) }
                        render()
                    },
                    onReasoning = { delta ->
                        synchronized(builder) { thoughts.append(delta) }
                        render()
                    },
                    onError = { error ->
                        failed = true
                        ErrorLog.record("chat", "${error::class.simpleName}: ${error.userMessage}")
                        _uiState.update { it.copy(banner = bannerFor(error, model)) }
                    },
                    onUsage = { usage = usage?.plus(it) ?: it },
                    onTruncated = { truncated = true }
                ).collect { }

                if (failed || !searchEnabled || round >= SearchProtocol.MAX_ROUNDS) break
                val raw = synchronized(builder) { builder.toString() }
                val (inlineThoughts, answer) = ThinkTags.split(raw)
                val queries = SearchProtocol.parseRequest(answer) ?: break

                // Run the search, then ask again with the results as the next user turn.
                round++
                status = "🔍 ${queries.joinToString(", ")} 검색 중…"
                synchronized(builder) {
                    builder.setLength(0)
                    inlineThoughts?.let { thoughts.append(it) }
                    if (thoughts.isNotEmpty()) thoughts.append("\n\n")
                }
                render()
                val report = webSearch.run(queries)
                report.errors.forEach { ErrorLog.record("search", it) }
                val results = SearchProtocol.resultsMessage(report, finalRound = round >= SearchProtocol.MAX_ROUNDS)
                if (report.hits.isNotEmpty()) {
                    searchFiles += AttachmentStore.saveText(appContext, "🔍 ${queries.first()}".take(60), results)
                }
                messages = messages + AiMessage(AiRole.ASSISTANT, answer.trim()) + AiMessage(AiRole.USER, results)
                status = null
                render()
            }
        }
        streamJob = job

        // Persist whatever arrived, even when the user pressed stop or left the screen
        // (viewModelScope cancelled): NonCancellable lets the final write finish.
        try {
            job.join()
        } finally {
            withContext(NonCancellable) {
                val (raw, streamed) = synchronized(builder) { builder.toString() to thoughts.toString() }
                val (answer, reasoning) = splitReasoning(raw, streamed)
                // A request still pending after the last round (or a stop mid-search) is not an answer.
                val finalText = if (searchEnabled) SearchProtocol.stripRequests(answer) else answer.trim()
                val stored = finishAnswer(
                    placeholderId,
                    finalText,
                    failed,
                    stopped = job.isCancelled,
                    usage = usage,
                    reasoning = reasoning,
                    attachments = searchFiles
                )
                if (!stored) AttachmentStore.deleteAll(searchFiles)
                if (stored && truncated && !job.isCancelled) {
                    _uiState.update {
                        it.copy(banner = "답변이 길이 제한에 걸려 중간에 끊겼습니다. \"이어서\"라고 보내면 계속 씁니다.")
                    }
                }
                if (streamJob === job) streamJob = null
            }
        }
    }


    /**
     * Replaces the streaming placeholder with the stored answer. A stop before any text
     * stores nothing; a failure stores a short error bubble that is never resent to the model.
     * True when a real answer was stored (and [attachments] with it).
     */
    private suspend fun finishAnswer(
        placeholderId: Long,
        finalText: String,
        failed: Boolean,
        stopped: Boolean,
        usage: TokenUsage?,
        reasoning: String?,
        attachments: List<Attachment> = emptyList()
    ): Boolean {
        val hasAnswer = finalText.isNotEmpty()
        val keptAttachments = if (hasAnswer) attachments else emptyList()
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
                    completionTokens = usage?.completionTokens,
                    reasoning = reasoning,
                    attachmentsJson = AttachmentCodec.encode(keptAttachments)
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
                    completionTokens = usage?.completionTokens,
                    reasoning = reasoning,
                    attachments = keptAttachments
                )
            }
            current.copy(isSending = false, messages = messages)
        }
        return hasAnswer && persistedId != null
    }

    /** (answer, reasoning): streamed thinking plus any inline <think> block. */
    private fun splitReasoning(raw: String, streamed: String): Pair<String, String?> {
        val (inline, answer) = ThinkTags.split(raw)
        val reasoning = listOfNotNull(streamed.trim().ifEmpty { null }, inline?.ifEmpty { null })
            .joinToString("\n\n")
            .ifEmpty { null }
        return answer to reasoning
    }

    private fun buildSystemPrompt(summary: String?, photoAttached: Boolean, webSearch: Boolean): String = buildString {
        append(Prompts.SYSTEM)
        if (webSearch) append("\n\n").append(SearchProtocol.instructions(SearchProtocol.todayInKorea()))
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
        // Never negative: with fewer than ATTACHMENT_WINDOW_MESSAGES stored messages every
        // index must count as inside the window (was `size - 4`, i.e. -3 for a new thread).
        val attachmentWindowStart = (recent.size - ATTACHMENT_WINDOW_MESSAGES).coerceAtLeast(0)

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
                // 64 was too small for reasoning models: thinking ate the budget and the
                // title silently fell back to the message prefix (or came back empty).
                maxTokens = TITLE_MAX_TOKENS,
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

        /**
         * Attachments that can wait for one message. Every photo is uploaded with the message,
         * so this keeps a single request to a sensible size.
         */
        const val MAX_PENDING_ATTACHMENTS = 10

        /** Links read per message; more would mostly add tokens, not answers. */
        private const val MAX_LINKS = 3

        /** Only the latest messages resend their photos/files (about the last two turns). */
        private const val ATTACHMENT_WINDOW_MESSAGES = 4

        private const val SUMMARY_MAX_TOKENS = 2048
        private const val TITLE_MAX_TOKENS = 512
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
                    aiClient = aiClient,
                    linkReader = ServiceLocator.links(),
                    webSearch = ServiceLocator.webSearch(context)
                )
            }
        }
    }
}
