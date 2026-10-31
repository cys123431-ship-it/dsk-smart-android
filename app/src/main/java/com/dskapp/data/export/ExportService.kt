package com.dskapp.data.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns an assistant reply (markdown) into a real document file and shares it.
 *
 * All formats are generated on-device: Office files are OOXML ZIP packages we write
 * ourselves, PDF uses the platform PdfDocument API. No network, no extra libraries.
 */
class ExportService(private val context: Context) {

    suspend fun export(
        markdown: String,
        title: String,
        format: ExportFormat
    ): Result<ExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val safeTitle = sanitize(title)
            val fileName = "${safeTitle}_${timestamp()}.${format.extension}"
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val target = File(dir, fileName)
            val blocks = MarkdownParser.parse(markdown)

            when (format) {
                ExportFormat.MARKDOWN -> target.writeText(markdown)
                ExportFormat.TEXT -> target.writeText(toPlainText(title, blocks))
                ExportFormat.WORD -> DocxWriter.write(target, title, blocks)
                ExportFormat.EXCEL -> XlsxWriter.write(target, title, blocks)
                ExportFormat.POWERPOINT -> PptxWriter.write(target, title, blocks)
                ExportFormat.PDF -> PdfWriter.write(target, title, blocks)
            }

            require(target.exists() && target.length() > 0) { "파일을 만들지 못했습니다." }
            ExportResult(target, format.mimeType, format)
        }
    }

    /** Opens the system share sheet for a previously exported file. */
    fun share(result: ExportResult) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            result.file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = result.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, result.file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "내보내기").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Opens the file in the app the user picks (Office, Docs, PDF viewer, ...). */
    fun open(result: ExportResult) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            result.file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, result.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun toPlainText(title: String, blocks: List<MdBlock>): String = buildString {
        appendLine(title)
        appendLine("=".repeat(title.length.coerceAtMost(60)))
        appendLine()
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    appendLine()
                    appendLine(MarkdownParser.stripInline(block.text).uppercase())
                    appendLine("-".repeat(40))
                }
                is MdBlock.Paragraph -> {
                    appendLine(MarkdownParser.stripInline(block.text))
                    appendLine()
                }
                is MdBlock.Quote -> {
                    appendLine("  “${MarkdownParser.stripInline(block.text)}”")
                    appendLine()
                }
                is MdBlock.Bullet -> {
                    block.items.forEachIndexed { index, item ->
                        val marker = if (block.ordered) "${index + 1}." else "-"
                        appendLine("$marker ${MarkdownParser.stripInline(item)}")
                    }
                    appendLine()
                }
                is MdBlock.Code -> {
                    appendLine(block.code)
                    appendLine()
                }
                is MdBlock.Table -> {
                    appendLine(block.header.joinToString(" | "))
                    appendLine("-".repeat(40))
                    block.rows.forEach { appendLine(it.joinToString(" | ")) }
                    appendLine()
                }
                MdBlock.Rule -> appendLine("-".repeat(40))
            }
        }
    }

    private fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().trim('.')
        return cleaned.ifBlank { "DSK_문서" }.take(40)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
}
