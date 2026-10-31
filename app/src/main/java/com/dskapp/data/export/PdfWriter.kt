package com.dskapp.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/**
 * Renders markdown to PDF with the platform [PdfDocument] API.
 *
 * StaticLayout does the line-breaking and wrapping, which handles Korean correctly
 * (unlike manual glyph measurement). Long documents flow across pages automatically.
 */
object PdfWriter {

    private const val PAGE_WIDTH = 595   // A4 at 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    fun write(target: File, title: String, blocks: List<MdBlock>) {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
        )
        var canvas = page.canvas
        var y = MARGIN

        fun noteY(delta: Float) {
            y += delta
        }

        /** Starts a new page when the next block does not fit. */
        fun ensureSpace(height: Float) {
            if (y + height <= PAGE_HEIGHT - MARGIN) return
            document.finishPage(page)
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            )
            canvas = page.canvas
            y = MARGIN
        }

        fun drawBlock(text: String, paint: TextPaint, spacingAfter: Float, leftIndent: Float = 0f) {
            if (text.isBlank()) return
            val width = (CONTENT_WIDTH - leftIndent).toInt()
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width)
                .setLineSpacing(0f, 1.15f)
                .build()

            ensureSpace(layout.height.toFloat())
            canvas.save()
            canvas.translate(MARGIN + leftIndent, y)
            layout.draw(canvas)
            canvas.restore()
            noteY(layout.height + spacingAfter)
        }

        // Document title.
        drawBlock(title, paint(size = 20f, bold = true, color = 0xFF1E3A8A.toInt()), spacingAfter = 18f)

        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> drawBlock(
                    text = block.text,
                    paint = paint(
                        size = when (block.level) {
                            1 -> 17f
                            2 -> 15f
                            else -> 13.5f
                        },
                        bold = true,
                        color = if (block.level == 1) 0xFF1E3A8A.toInt() else 0xFF1D4ED8.toInt()
                    ),
                    spacingAfter = 10f
                )

                is MdBlock.Paragraph ->
                    drawBlock(block.text, paint(11.5f, color = 0xFF1E293B.toInt()), 10f)

                is MdBlock.Quote ->
                    drawBlock("“${block.text}”", paint(11f, italic = true, color = 0xFF475569.toInt()), 10f, 16f)

                is MdBlock.Bullet -> block.items.forEachIndexed { index, item ->
                    val marker = if (block.ordered) "${index + 1}." else "•"
                    drawBlock("$marker  $item", paint(11.5f, color = 0xFF1E293B.toInt()), 6f, 14f)
                }

                is MdBlock.Code -> {
                    val codePaint = paint(9.5f, mono = true, color = 0xFF0F172A.toInt())
                    drawBlock(block.code, codePaint, 12f, 8f)
                }

                is MdBlock.Table -> {
                    drawBlock(
                        block.header.joinToString("   |   "),
                        paint(10.5f, bold = true, color = 0xFF1E3A8A.toInt()),
                        6f
                    )
                    block.rows.forEach { row ->
                        drawBlock(row.joinToString("   |   "), paint(10.5f, color = 0xFF1E293B.toInt()), 4f)
                    }
                    noteY(8f)
                }

                MdBlock.Rule -> {
                    ensureSpace(16f)
                    val linePaint = Paint().apply {
                        color = 0xFFCBD5E1.toInt()
                        strokeWidth = 0.8f
                    }
                    canvas.drawLine(MARGIN, y + 6f, PAGE_WIDTH - MARGIN, y + 6f, linePaint)
                    noteY(14f)
                }
            }
        }

        document.finishPage(page)
        target.parentFile?.mkdirs()
        target.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun paint(
        size: Float,
        bold: Boolean = false,
        italic: Boolean = false,
        mono: Boolean = false,
        color: Int = Color.BLACK
    ): TextPaint = TextPaint().apply {
        this.color = color
        textSize = size
        isAntiAlias = true
        typeface = Typeface.create(
            if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
            when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
        )
    }
}
