package com.dskapp.data.export

import kotlinx.serialization.Serializable

/** Output formats offered in the "내보내기" sheet. */
@Serializable
enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
    MARKDOWN("Markdown (.md)", "md", "text/markdown"),
    TEXT("텍스트 (.txt)", "txt", "text/plain"),
    WORD("Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POWERPOINT("PowerPoint (.pptx)", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    PDF("PDF (.pdf)", "pdf", "application/pdf")
}

/** Result of a successful export: an on-disk file plus the share MIME type. */
data class ExportResult(
    val file: java.io.File,
    val mimeType: String,
    val format: ExportFormat
)

/**
 * Markdown subset produced by [MarkdownParser]. Deliberately small: the assistant is
 * instructed to emit headings, bullets, code fences and tables, which is everything the
 * Office writers need.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>, val ordered: Boolean) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}
