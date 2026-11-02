package com.dskapp.data.backup

import android.content.Context
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentCodec
import com.dskapp.data.chat.ChatRepository
import com.dskapp.data.chat.ConversationEntity
import com.dskapp.data.chat.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupResult(val conversations: Int, val messages: Int, val files: Int)

class BackupService(private val context: Context, private val repository: ChatRepository) {

    /** Writes `backup.json` (first entry) plus every referenced attachment into [output]. */
    suspend fun export(output: OutputStream): BackupResult = withContext(Dispatchers.IO) {
        val conversations = repository.allConversations()
        val modelConversations = ArrayList<BackupConversation>(conversations.size)
        // Distinct files, keyed by entry name: attachment names are already unique UUIDs,
        // so a shared file is written exactly once even if several messages reference it.
        val filesToWrite = LinkedHashMap<String, File>()
        var messageCount = 0

        for (conversation in conversations) {
            val messages = repository.messages(conversation.id)
            val backupMessages = ArrayList<BackupMessage>(messages.size)
            for (message in messages) {
                val backupAttachments = AttachmentCodec.decode(message.attachmentsJson).mapNotNull { attachment ->
                    val local = File(attachment.localPath)
                    // Missing files are skipped entirely so the JSON never lists a dangling entry.
                    if (!local.isFile) return@mapNotNull null
                    val entryName = "files/" + local.name
                    filesToWrite[entryName] = local
                    BackupAttachment(
                        kind = attachment.kind,
                        name = attachment.displayName,
                        mime = attachment.mimeType,
                        entry = entryName
                    )
                }
                backupMessages += BackupMessage(
                    role = message.role,
                    content = message.content,
                    createdAt = message.createdAt,
                    isError = message.isError,
                    promptTokens = message.promptTokens,
                    completionTokens = message.completionTokens,
                    attachments = backupAttachments,
                    reasoning = message.reasoning,
                    isBookmarked = message.isBookmarked
                )
            }
            messageCount += backupMessages.size
            modelConversations += BackupConversation(
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                isPinned = conversation.isPinned,
                model = conversation.model,
                messages = backupMessages
            )
        }

        val backupFile = BackupFile(
            exportedAt = System.currentTimeMillis(),
            conversations = modelConversations
        )

        val zip = ZipOutputStream(output)
        // backup.json goes first so a reader can stream the manifest before the payload.
        zip.putNextEntry(ZipEntry("backup.json"))
        zip.write(BackupCodec.encode(backupFile).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((entryName, file) in filesToWrite) {
            zip.putNextEntry(ZipEntry(entryName))
            // Stream in chunks; attachments may be large and must not be buffered in memory.
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        // finish() flushes the central directory but leaves the caller's stream open.
        zip.finish()

        BackupResult(
            conversations = modelConversations.size,
            messages = messageCount,
            files = filesToWrite.size
        )
    }

    /** Adds every thread in the backup as new threads; existing threads are untouched. */
    suspend fun import(input: InputStream): BackupResult = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var backupText: String? = null
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                // Only the manifest and sanitized file names are ever materialized: no path from
                // the archive is used verbatim, which rules out zip-slip writes outside staging.
                when {
                    name == "backup.json" -> backupText = readLimitedText(zip, MAX_MANIFEST_BYTES)
                    FILE_ENTRY.matches(name) -> {
                        val target = File(staging, name.substring("files/".length))
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    else -> Unit
                }
                zip.closeEntry()
            }
            val text = backupText
                ?: throw IllegalArgumentException("백업 파일에 backup.json이 없습니다.")

            val backup = BackupCodec.decode(text)
            var conversationCount = 0
            var messageCount = 0
            var restoredFiles = 0

            for (conversation in backup.conversations) {
                val messages = conversation.messages.map { message ->
                    val attachments = message.attachments.mapNotNull { attachment ->
                        val staged = File(staging, attachment.entry.substringAfterLast('/', ""))
                        if (!staged.isFile) return@mapNotNull null
                        val target = AttachmentStore.newAttachmentFile(
                            context,
                            staged.extension.ifBlank { "bin" }
                        )
                        staged.copyTo(target, overwrite = true)
                        restoredFiles++
                        Attachment(
                            kind = attachment.kind,
                            localPath = target.absolutePath,
                            displayName = attachment.name,
                            mimeType = attachment.mime,
                            sizeBytes = target.length()
                        )
                    }
                    MessageEntity(
                        id = 0L,
                        conversationId = 0L,
                        role = message.role,
                        content = message.content,
                        createdAt = message.createdAt,
                        attachmentsJson = AttachmentCodec.encode(attachments),
                        isError = message.isError,
                        promptTokens = message.promptTokens,
                        completionTokens = message.completionTokens,
                        reasoning = message.reasoning,
                        isBookmarked = message.isBookmarked
                    )
                }
                repository.insertRestored(
                    conversation = ConversationEntity(
                        id = 0L,
                        title = conversation.title,
                        createdAt = conversation.createdAt,
                        updatedAt = conversation.updatedAt,
                        isPinned = conversation.isPinned,
                        model = conversation.model
                    ),
                    messages = messages
                )
                conversationCount++
                messageCount += messages.size
            }

            BackupResult(
                conversations = conversationCount,
                messages = messageCount,
                files = restoredFiles
            )
        } finally {
            // Staged bytes are throwaway copies; remove them even when the import fails midway.
            staging.deleteRecursively()
        }
    }

    /** Reads at most [limit] bytes as UTF-8, rejecting oversized manifests instead of exhausting memory. */
    private fun readLimitedText(input: InputStream, limit: Int): String {
        val buffer = ByteArray(8 * 1024)
        val sink = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IllegalArgumentException("백업 파일 형식이 올바르지 않습니다.")
            sink.write(buffer, 0, read)
        }
        return sink.toString("UTF-8")
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 50 * 1024 * 1024
        val FILE_ENTRY = Regex("^files/[A-Za-z0-9._-]{1,128}$")
    }
}
