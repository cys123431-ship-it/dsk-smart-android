package com.dskapp.data.attachment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Copies picked content into app-private storage so it stays available after the
 * temporary content:// permission is revoked, and so the AI request can read it later.
 */
object AttachmentStore {

    private const val DIR = "attachments"

    /** Max characters of a text file we hand to the model (roughly 40k tokens). */
    const val MAX_TEXT_CHARS = 120_000

    /** UTF-8 needs up to 3 bytes per Korean character; reading more than this buys nothing. */
    private const val MAX_TEXT_BYTES = MAX_TEXT_CHARS * 3

    private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L

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
                val (bytes, truncated) = context.contentResolver.openInputStream(source)?.use { stream ->
                    // Read at most MAX_TEXT_BYTES: readText() on a huge file would OOM.
                    stream.readAtMost(MAX_TEXT_BYTES) to (stream.read() != -1)
                } ?: error("파일을 읽을 수 없습니다.")

                val text = TextDecoding.decode(bytes, truncated).take(MAX_TEXT_CHARS)

                val file = newFile(context, "txt")
                file.writeText(text)

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

    suspend fun deleteAll(attachments: List<Attachment>) = withContext(Dispatchers.IO) {
        attachments.forEach(::delete)
    }

    /**
     * Startup housekeeping. Exports are only needed while the share sheet hands them over,
     * and camera captures are full-size originals already re-encoded into [DIR], so neither
     * should keep using storage across launches. The one-hour grace period protects a share
     * that may still be reading a file.
     */
    suspend fun cleanCaches(context: Context) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        listOf("exports", "camera").forEach { name ->
            File(context.cacheDir, name).listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) runCatching { file.delete() }
            }
        }
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

    /** Reads up to [limit] bytes; memory grows with the file, not with the limit. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
