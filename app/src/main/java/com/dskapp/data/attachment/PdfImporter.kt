package com.dskapp.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.dskapp.data.chat.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns a PDF into either a text attachment (digital PDFs) or a handful of page images (scans).
 *
 * Rendering is capped because chat only needs a readable preview of the first pages.
 */
internal object PdfImporter {

    private const val MAX_TEXT_PAGES = 60
    private const val MAX_IMAGE_PAGES = 4
    private const val TARGET_LONG_EDGE = 1568f
    private const val JPEG_QUALITY = 82
    private const val MIN_TEXT_CHARS = 20

    suspend fun import(context: Context, uri: Uri, displayName: String): Result<List<Attachment>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalArgumentException("PDF 파일을 열 수 없습니다.")
                descriptor.use { pfd ->
                    val renderer = try {
                        PdfRenderer(pfd)
                    } catch (e: SecurityException) {
                        throw IllegalArgumentException("암호가 걸린 PDF는 열 수 없습니다.")
                    }
                    try {
                        val pageCount = try {
                            renderer.pageCount
                        } catch (e: SecurityException) {
                            throw IllegalArgumentException("암호가 걸린 PDF는 열 수 없습니다.")
                        }
                        if (pageCount == 0) throw IllegalArgumentException("빈 PDF입니다.")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            val text = extractText(renderer, pageCount)
                            if (text.count { !it.isWhitespace() } >= MIN_TEXT_CHARS) {
                                return@runCatching listOf(
                                    AttachmentStore.saveText(context, displayName, text),
                                )
                            }
                        }

                        val images = mutableListOf<Attachment>()
                        val pages = minOf(pageCount, MAX_IMAGE_PAGES)
                        for (i in 0 until pages) {
                            val bytes = renderPage(renderer, i)
                            images.add(
                                AttachmentStore.importImage(
                                    context,
                                    bytes,
                                    "$displayName ${i + 1}쪽",
                                ),
                            )
                        }
                        images
                    } finally {
                        renderer.close()
                    }
                }
            }
        }

    /** Android 15 exposes the embedded text layer; older versions cannot read text from a PDF. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun extractText(renderer: PdfRenderer, pageCount: Int): String {
        val blocks = mutableListOf<String>()
        val pages = minOf(pageCount, MAX_TEXT_PAGES)
        for (i in 0 until pages) {
            val page = renderer.openPage(i)
            val pageText = try {
                page.textContents.joinToString("\n") { it.text }
            } finally {
                page.close()
            }
            if (pageText.isNotBlank()) {
                blocks.add("--- ${i + 1}쪽 ---\n$pageText")
            }
        }
        return blocks.joinToString("\n\n")
    }

    /** Renders one page to JPEG bytes; the bitmap is recycled as soon as it is compressed. */
    private fun renderPage(renderer: PdfRenderer, index: Int): ByteArray {
        val page = renderer.openPage(index)
        try {
            val scale = TARGET_LONG_EDGE / maxOf(page.width, page.height).toFloat()
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PDF renders on a transparent canvas, so fill white first.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                return out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }
}
