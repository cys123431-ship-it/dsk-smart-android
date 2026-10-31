package com.dskapp.data.chat

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** Repository over [ChatDao]; keeps ViewModels free of SQL details. */
class ChatRepository(private val dao: ChatDao) {

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeConversation(id: Long): Flow<ConversationEntity?> = dao.observeConversation(id)

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

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
        isError: Boolean = false
    ): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = role,
                content = content,
                createdAt = now,
                attachmentsJson = attachmentsJson,
                isError = isError
            )
        )
        dao.touchConversation(conversationId, now)
        return id
    }

    suspend fun updateMessageContent(messageId: Long, content: String, isError: Boolean = false) {
        dao.updateMessageContent(messageId, content, isError)
    }

    suspend fun renameConversation(id: Long, title: String) {
        dao.renameConversation(id, title, System.currentTimeMillis())
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun deleteConversation(id: Long) = dao.deleteConversation(id)

    suspend fun ensureWelcomeConversation() {
        if (dao.countConversations() == 0) {
            createConversation(WELCOME_TITLE)
        }
    }

    /** Live search over titles and message bodies. Blank queries return nothing. */
    fun search(query: String): Flow<List<ConversationEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            dao.searchConversations(trimmed)
        }
    }

    /** First matching message body, used to show why a thread matched. */
    suspend fun snippet(conversationId: Long, query: String): String? =
        dao.findSnippet(conversationId, query.trim())

    companion object Factory {
        const val NEW_CONVERSATION_TITLE = "새 대화"
        private const val WELCOME_TITLE = "DSK 스마트 시작하기"

        fun create(context: Context): ChatRepository {
            val db = androidx.room.Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                ChatDatabase.NAME
            ).build()
            return ChatRepository(db.chatDao())
        }
    }
}
