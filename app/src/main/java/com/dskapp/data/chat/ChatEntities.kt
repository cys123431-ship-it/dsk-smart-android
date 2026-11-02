package com.dskapp.data.chat

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One chat thread shown in the drawer / tablet list, exactly like ChatGPT's history. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    /** Model for this thread only; null means the default model from Settings. */
    val model: String? = null,
    /** Running summary of the messages up to [summaryUpToId], sent instead of them. */
    val summary: String? = null,
    val summaryUpToId: Long? = null
)

/**
 * A single message. [attachmentsJson] holds the attachments as a JSON array of
 * {kind, path, name, mime} so thumbnails survive app restarts without bloating the row with base64.
 * Token counts are filled for assistant answers when the server reports usage.
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val role: String,          // "user" | "assistant"
    val content: String,
    val createdAt: Long,
    val attachmentsJson: String = "[]",
    val isError: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    /** The model's thinking, kept apart from the answer and never sent back to the model. */
    val reasoning: String? = null,
    @ColumnInfo(defaultValue = "0") val isBookmarked: Boolean = false
)

/** A starred answer with the title of its thread, for the 즐겨찾기 screen. */
data class BookmarkItem(
    val messageId: Long,
    val conversationId: Long,
    val conversationTitle: String,
    val content: String,
    val createdAt: Long
)

@Dao
interface ChatDao {

    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, updatedAt DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeConversation(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY id ASC")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    /** Ids grow with time inside a thread, so "from this id on" is "this message and later". */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun getMessagesFrom(conversationId: Long, fromId: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long)

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameConversation(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET model = :model WHERE id = :id")
    suspend fun setModel(id: Long, model: String?)

    @Query("UPDATE conversations SET summary = :summary, summaryUpToId = :upToId WHERE id = :id")
    suspend fun setSummary(id: Long, summary: String?, upToId: Long?)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    @Query("UPDATE messages SET isBookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(id: Long, bookmarked: Boolean)

    @Query(
        """
        SELECT m.id AS messageId, m.conversationId AS conversationId, c.title AS conversationTitle,
               m.content AS content, m.createdAt AS createdAt
        FROM messages m JOIN conversations c ON c.id = m.conversationId
        WHERE m.isBookmarked = 1
        ORDER BY m.createdAt DESC
        """
    )
    fun observeBookmarks(): Flow<List<BookmarkItem>>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun countConversations(): Int

    /**
     * Full history search over both the thread title and every message body.
     * A LIKE scan is fine at this data size (personal chat history) and needs no schema
     * migration, unlike FTS which would require a new virtual table plus a rebuild.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR id IN (
               SELECT conversationId FROM messages
               WHERE content LIKE '%' || :query || '%' ESCAPE '\'
           )
        ORDER BY isPinned DESC, updatedAt DESC
        """
    )
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    /** Returns the first matching message body per conversation, for the result snippet. */
    @Query(
        """
        SELECT content FROM messages
        WHERE conversationId = :conversationId AND content LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt ASC LIMIT 1
        """
    )
    suspend fun findSnippet(conversationId: Long, query: String): String?
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 3,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        const val NAME = "dsk_chat.db"

        /** v2: per-thread model, running summary, token usage per answer. All nullable. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN model TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryUpToId INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER")
            }
        }

        /** v3: reasoning text and the bookmark flag on messages. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN isBookmarked INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
