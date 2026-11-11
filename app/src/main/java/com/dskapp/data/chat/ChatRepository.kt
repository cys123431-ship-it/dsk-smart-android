package com.dskapp.data.chat

import android.content.Context
import com.dskapp.data.attachment.AttachmentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Repository over [ChatDao]; keeps ViewModels free of SQL details. */
class ChatRepository(private val dao: ChatDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeConversation(id: Long): Flow<ConversationEntity?> = dao.observeConversation(id)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    suspend fun conversation(id: Long): ConversationEntity? = dao.getConversation(id)

    suspend fun messages(conversationId: Long): List<MessageEntity> =
        dao.getMessages(conversationId)

    /** Creates an empty thread; the title is replaced by the first user message. */
    suspend fun createConversation(title: String = NEW_CONVERSATION_TITLE): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(
            ConversationEntity(title = title, createdAt = now, updatedAt = now)
        )
    }

    suspend fun appendMessage(
        conversationId: Long,
        role: String,
        content: String,
        attachmentsJson: String = "[]",
        isError: Boolean = false,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        reasoning: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = now,
                attachmentsJson = attachmentsJson,
                isError = isError,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                reasoning = reasoning?.takeIf { it.isNotBlank() }
            )
        )
        dao.touchConversation(conversationId, now)
        return id
    }

    /**
     * Removes [fromMessageId] and every later message of the thread (edit / regenerate).
     * Attachment files go too, except paths in [keepPaths] (an edited message re-sends its
     * own photos). A summary that covered removed messages is dropped so it is rebuilt.
     */
    suspend fun truncateFrom(
        conversationId: Long,
        fromMessageId: Long,
        keepPaths: Set<String> = emptySet()
    ) {
        // Rows first, atomically; files only after the database agrees they are gone.
        val removed = dao.truncateThread(conversationId, fromMessageId)
        AttachmentStore.deleteAll(
            removed.flatMap { AttachmentCodec.decode(it.attachmentsJson) }
                .filterNot { it.localPath in keepPaths }
        )
    }

    suspend fun renameConversation(id: Long, title: String) {
        dao.renameConversation(id, title, System.currentTimeMillis())
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun setBookmarked(messageId: Long, bookmarked: Boolean) = dao.setBookmarked(messageId, bookmarked)

    fun observeBookmarks(): Flow<List<BookmarkItem>> = dao.observeBookmarks()

    suspend fun setModel(id: Long, model: String?) = dao.setModel(id, model?.takeIf { it.isNotBlank() })

    suspend fun setSummary(id: Long, summary: String, upToId: Long) = dao.setSummary(id, summary, upToId)

    /**
     * Deletes the thread (messages cascade) and the attachment files its messages point to,
     * which would otherwise stay in app storage forever. If that was the last thread, a fresh
     * one is created so the chat screen always has a valid conversation to write into.
     */
    suspend fun deleteConversation(id: Long) {
        val attachments = dao.getMessages(id).flatMap { AttachmentCodec.decode(it.attachmentsJson) }
        dao.deleteConversation(id)
        AttachmentStore.deleteAll(attachments)
        if (dao.countConversations() == 0) createConversation()
    }

    suspend fun ensureWelcomeConversation() {
        if (dao.countConversations() == 0) {
            createConversation(WELCOME_TITLE)
        }
    }

    /** Live search over titles and message bodies. Blank queries return nothing. */
    fun search(query: String): Flow<List<ConversationEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            flowOf(emptyList())
        } else {
            dao.searchConversations(escapeLikePattern(trimmed))
        }
    }

    /** First matching message body, used to show why a thread matched. */
    suspend fun snippet(conversationId: Long, query: String): String? =
        dao.findSnippet(conversationId, escapeLikePattern(query.trim()))

    // ---------------------------------------------------------------- backup support

    suspend fun allConversations(): List<ConversationEntity> = dao.getAllConversations()

    /** Inserts a restored thread with fresh ids; returns the new conversation id. */
    suspend fun insertRestored(conversation: ConversationEntity, messages: List<MessageEntity>): Long {
        return dao.insertThread(
            conversation.copy(id = 0L, summary = null, summaryUpToId = null),
            messages
        )
    }

    companion object Factory {
        const val NEW_CONVERSATION_TITLE = "새 대화"
        private const val WELCOME_TITLE = "DSK 스마트 시작하기"

        fun create(context: Context): ChatRepository {
            val db = androidx.room.Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                ChatDatabase.NAME
            ).addMigrations(ChatDatabase.MIGRATION_1_2, ChatDatabase.MIGRATION_2_3).build()
            return ChatRepository(db.chatDao())
        }
    }
}
