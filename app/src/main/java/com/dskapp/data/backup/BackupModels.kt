package com.dskapp.data.backup

import com.dskapp.data.chat.AttachmentKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Top-level shape of the single `backup.json` entry. [version] lets future releases
 * reject/upgrade payloads; unknown keys are tolerated so newer minors stay readable.
 */
@Serializable
data class BackupFile(
    val version: Int = 1,
    val exportedAt: Long,
    val conversations: List<BackupConversation>
)

/** A thread without ids or summaries: restored threads always get fresh ids and rebuilt summaries. */
@Serializable
data class BackupConversation(
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isPinned: Boolean = false,
    val model: String? = null,
    val messages: List<BackupMessage> = emptyList()
)

/** One message; attachments point at zip entries rather than absolute on-device paths. */
@Serializable
data class BackupMessage(
    val role: String,
    val content: String,
    val createdAt: Long,
    val isError: Boolean = false,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val attachments: List<BackupAttachment> = emptyList()
)

/** [entry] is the file's name inside the zip's `files/` folder (e.g. `files/a1b2.png`). */
@Serializable
data class BackupAttachment(
    val kind: AttachmentKind,
    val name: String,
    val mime: String,
    val entry: String
)

object BackupCodec {
    // encodeDefaults keeps optional fields explicit so older readers see stable keys.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Throws [IllegalArgumentException] when the payload is not valid JSON or from a newer version. */
    fun decode(text: String): BackupFile {
        val parsed = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            // Any parse failure (missing fields, bad enum, malformed syntax) is the same user-facing error.
            throw IllegalArgumentException("백업 파일 형식이 올바르지 않습니다.", e)
        }
        if (parsed.version > 1) {
            throw IllegalArgumentException("백업 파일 형식이 올바르지 않습니다.")
        }
        return parsed
    }
}
