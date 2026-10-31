package com.dskapp.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An attachment saved on disk. Stored as JSON in [MessageEntity.attachmentsJson] so the
 * chat history can render thumbnails after a restart without keeping base64 in the DB.
 */
@Serializable
data class Attachment(
    val kind: AttachmentKind,
    @SerialName("path") val localPath: String,
    @SerialName("name") val displayName: String,
    @SerialName("mime") val mimeType: String,
    val sizeBytes: Long = 0L
)

@Serializable
enum class AttachmentKind { IMAGE, TEXT_FILE }

object AttachmentCodec {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(items: List<Attachment>): String =
        if (items.isEmpty()) "[]" else json.encodeToString(items)

    fun decode(raw: String): List<Attachment> =
        runCatching { json.decodeFromString<List<Attachment>>(raw) }.getOrDefault(emptyList())
}
