package com.dskapp.ui.voice

import com.dskapp.data.export.MarkdownParser
import com.dskapp.data.export.MdBlock

/**
 * Converts assistant markdown into plain sentences suitable for a TTS engine.
 *
 * Pure logic (no Android types) so it is unit-testable and cheap to call on every button tap.
 */
object SpeechText {

    private const val CODE_NOTE = "(코드는 생략합니다)"

    /** Plain sentences to speak, split into chunks of at most [maxChunk] chars. */
    fun chunks(markdown: String, maxChunk: Int = 3500): List<String> {
        val plain = toPlainText(markdown)
        if (plain.isBlank()) return emptyList()
        return splitSentences(plain, maxChunk)
    }

    /** Flattens every block kind into speakable lines, dropping code bodies and rules. */
    private fun toPlainText(markdown: String): String {
        val builder = StringBuilder()
        for (block in MarkdownParser.parse(markdown)) {
            when (block) {
                is MdBlock.Heading -> appendLine(builder, MarkdownParser.stripInline(block.text))
                is MdBlock.Paragraph -> appendLine(builder, MarkdownParser.stripInline(block.text))
                is MdBlock.Quote -> appendLine(builder, MarkdownParser.stripInline(block.text))
                is MdBlock.Bullet -> block.items.forEach { item ->
                    appendLine(builder, MarkdownParser.stripInline(item))
                }
                is MdBlock.Code -> appendLine(builder, CODE_NOTE)
                is MdBlock.Table -> block.rows.forEach { row ->
                    appendLine(builder, row.joinToString(", "))
                }
                is MdBlock.Rule -> Unit
            }
        }
        return builder.toString().trim()
    }

    private fun appendLine(builder: StringBuilder, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append(trimmed)
    }

    /**
     * Splits into sentences at `. ! ? 。` followed by whitespace and at newlines, then packs
     * greedily into chunks no longer than [maxChunk]; oversized sentences are hard-split.
     */
    private fun splitSentences(text: String, maxChunk: Int): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            current.append(ch)
            val isSentenceEnd = (ch == '.' || ch == '!' || ch == '?' || ch == '。') &&
                i + 1 < text.length && text[i + 1].isWhitespace()
            val isBreak = ch == '\n'
            if (isSentenceEnd || isBreak) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) sentences.add(sentence)
                current.clear()
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) sentences.add(tail)

        val chunks = mutableListOf<String>()
        val packer = StringBuilder()
        for (sentence in sentences) {
            for (piece in hardSplit(sentence, maxChunk)) {
                if (packer.isEmpty()) {
                    packer.append(piece)
                } else if (packer.length + 1 + piece.length <= maxChunk) {
                    packer.append(' ').append(piece)
                } else {
                    chunks.add(packer.toString())
                    packer.setLength(0)
                    packer.append(piece)
                }
            }
        }
        if (packer.isNotEmpty()) chunks.add(packer.toString())
        return chunks
    }

    private fun hardSplit(sentence: String, maxChunk: Int): List<String> {
        if (sentence.length <= maxChunk) return listOf(sentence)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < sentence.length) {
            val end = minOf(start + maxChunk, sentence.length)
            parts.add(sentence.substring(start, end))
            start = end
        }
        return parts
    }
}
