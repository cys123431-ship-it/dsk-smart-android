package com.dskapp.data.attachment

import android.content.Context
import android.net.Uri
import com.dskapp.data.chat.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Single entry point for any document the user picked or shared.
 *
 * Kept free of UI types so it can be reused from the chat composer and from share intents alike.
 */
object DocumentImporter {

    private const val MAX_OFFICE_BYTES = 25 * 1024 * 1024
    private const val CHUNK = 8 * 1024

    private const val MIME_DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MIME_PPTX =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private const val UNSUPPORTED =
        "이 형식은 지원하지 않습니다. docx, pptx, pdf, 텍스트 파일을 첨부해 주세요."

    /**
     * Imports a picked or shared document as attachments.
     * PDF -> PdfImporter, .docx/.pptx -> extracted text, anything else -> AttachmentStore.importTextFile.
     */
    suspend fun import(context: Context, uri: Uri): Result<List<Attachment>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = AttachmentStore.displayName(context, uri) ?: "첨부 파일"
                val mime = context.contentResolver.getType(uri).orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()

                when {
                    mime == "application/pdf" || ext == "pdf" ->
                        PdfImporter.import(context, uri, name).getOrThrow()

                    mime == MIME_DOCX || ext == "docx" -> {
                        val text = OfficeTextExtractor.docx(readCapped(context, uri))
                        requireText(text)
                        listOf(AttachmentStore.saveText(context, name, text))
                    }

                    mime == MIME_PPTX || ext == "pptx" -> {
                        val text = OfficeTextExtractor.pptx(readCapped(context, uri))
                        requireText(text)
                        listOf(AttachmentStore.saveText(context, name, text))
                    }

                    ext == "doc" || ext == "ppt" || ext == "hwp" ->
                        throw IllegalArgumentException(UNSUPPORTED)

                    else ->
                        listOf(AttachmentStore.importTextFile(context, uri).getOrThrow())
                }
            }
        }

    private fun requireText(text: String) {
        if (text.isBlank()) throw IllegalArgumentException("문서에서 글자를 찾지 못했습니다.")
    }

    /** Reads the whole stream but bails out as soon as it exceeds the cap, so a huge pick is cheap. */
    private fun readCapped(context: Context, uri: Uri): ByteArray {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("파일을 열 수 없습니다.")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > MAX_OFFICE_BYTES) {
                    throw IllegalArgumentException("파일이 너무 큽니다 (최대 25MB).")
                }
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }
}
