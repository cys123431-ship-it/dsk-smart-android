package com.dskapp.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Copies picked content into app-private storage so it stays available after the
 * temporary content:// permission is revoked, and so the AI request can read it later.
 */
object AttachmentStore {

    private const val DIR = "attachments"

    /** Max characters of a text file we hand to the model (roughly 40k tokens). */
    const val MAX_TEXT_CHARS = 120_000

    suspend fun importImage(context: Context, processedJpeg: ByteArray): Attachment =
        withContext(Dispatchers.IO) {
            val file = newFile(context, "jpg")
            file.writeBytes(processedJpeg)
            Attachment(
                kind = AttachmentKind.IMAGE,
                localPath = file.absolutePath,
                displayName = "사진 ${file.nameWithoutExtension.takeLast(4)}",
                mimeType = "image/jpeg",
                sizeBytes = file.length()
            )
        }

    suspend fun importTextFile(context: Context, source: Uri): Result<Attachment> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = queryDisplayName(context, source) ?: "첨부 파일.txt"
                val text = context.contentResolver.openInputStream(source)?.use { stream ->
                    stream.bufferedReader().readText()
                } ?: error("파일을 읽을 수 없습니다.")

                val file = newFile(context, "txt")
                file.writeText(text.take(MAX_TEXT_CHARS))

                Attachment(
                    kind = AttachmentKind.TEXT_FILE,
                    localPath = file.absolutePath,
                    displayName = name,
                    mimeType = "text/plain",
                    sizeBytes = file.length()
                )
            }
        }

    suspend fun readText(attachment: Attachment): String = withContext(Dispatchers.IO) {
        runCatching { File(attachment.localPath).readText() }.getOrDefault("")
    }

    suspend fun readBytes(attachment: Attachment): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(attachment.localPath).readBytes() }.getOrNull()
    }

    fun delete(attachment: Attachment) {
        runCatching { File(attachment.localPath).delete() }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
    }

    private fun newFile(context: Context, extension: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "${UUID.randomUUID()}.$extension")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
}
