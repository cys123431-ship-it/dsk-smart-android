package com.dskapp.data.attachment

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Extracts plain text from OOXML packages (docx/pptx) on a plain JVM.
 *
 * Android's XmlPullParser is unavailable in JVM unit tests and javax.xml.stream is missing on
 * Android, so we walk the XML with a tiny regex tag tokenizer instead. That is enough because we
 * only need element names and text runs, never attributes or namespaces.
 */
object OfficeTextExtractor {

    private val TAG_OR_TEXT = Regex("<[^>]+>|[^<]+")
    private val SLIDE_ENTRY = Regex("""ppt/slides/slide(\d+)\.xml""")

    /** Text of a .docx. Throws IllegalArgumentException("Word 문서를 읽을 수 없습니다.") if word/document.xml is missing. */
    fun docx(bytes: ByteArray): String {
        val xml = readEntry(bytes, "word/document.xml")
            ?: throw IllegalArgumentException("Word 문서를 읽을 수 없습니다.")
        val sb = StringBuilder()
        var inText = false
        var cellDepth = 0
        for (match in TAG_OR_TEXT.findAll(xml)) {
            val token = match.value
            if (token.startsWith("<")) {
                // Compare whole element names: a prefix test on "<w:t" would also hit
                // <w:tab/>, <w:tc>, <w:tbl>, <w:tr> and swallow tabs and table structure.
                val tag = Tag.of(token)
                when (tag.name) {
                    "w:t" -> inText = tag.isOpen
                    "w:tab" -> if (tag.isOpen) sb.append('\t')
                    "w:br", "w:cr" -> if (tag.isOpen) sb.append('\n')
                    "w:tc" -> if (tag.isOpen) {
                        cellDepth++
                    } else {
                        if (cellDepth > 0) cellDepth--
                        // Drop the space the cell's last paragraph left behind.
                        while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                        sb.append('\t')
                    }
                    "w:tr" -> if (!tag.isOpen) sb.append('\n')
                    "w:p" -> if (!tag.isOpen) sb.append(if (cellDepth > 0) ' ' else '\n')
                }
            } else if (inText) {
                sb.append(unescapeXml(token))
            }
        }
        return normalize(sb.toString())
    }

    /** Text of a .pptx. Throws IllegalArgumentException("PowerPoint 문서를 읽을 수 없습니다.") if there are no slides. */
    fun pptx(bytes: ByteArray): String {
        // Only slide entries are needed; collect them first because zip order is not guaranteed.
        val slides = linkedMapOf<Int, String>()
        forEachEntry(bytes, accept = { SLIDE_ENTRY.matches(it) }) { entryName, content ->
            val number = SLIDE_ENTRY.matchEntire(entryName)?.groupValues?.get(1)?.toIntOrNull()
            if (number != null) slides[number] = content.toString(Charsets.UTF_8)
        }
        if (slides.isEmpty()) throw IllegalArgumentException("PowerPoint 문서를 읽을 수 없습니다.")

        val blocks = slides.keys.sorted().map { number ->
            "## 슬라이드 $number\n" + slideText(slides.getValue(number))
        }
        return blocks.joinToString("\n\n").trim()
    }

    /** Builds the text of one slide: `</a:p>` separates paragraphs, `<a:br/>` is a hard newline. */
    private fun slideText(xml: String): String {
        val paragraphs = mutableListOf<String>()
        val sb = StringBuilder()
        var inText = false
        for (match in TAG_OR_TEXT.findAll(xml)) {
            val token = match.value
            if (token.startsWith("<")) {
                val tag = Tag.of(token)
                when (tag.name) {
                    "a:t" -> inText = tag.isOpen
                    "a:br" -> if (tag.isOpen) sb.append('\n')
                    "a:p" -> if (!tag.isOpen) {
                        val text = sb.toString().trim('\n')
                        if (text.isNotBlank()) paragraphs.add(text)
                        sb.setLength(0)
                    }
                }
            } else if (inText) {
                sb.append(unescapeXml(token))
            }
        }
        val trailing = sb.toString().trim('\n')
        if (trailing.isNotBlank()) paragraphs.add(trailing)
        return paragraphs.joinToString("\n")
    }

    /**
     * Element name of a tag token plus whether it opens content. A self-closing `<w:t/>`
     * counts as closed (it holds no text) while `<w:tab/>` / `<w:br/>` count as open, which is
     * what triggers their output; callers only consult [isOpen] where that matters.
     */
    private class Tag(val name: String, val isOpen: Boolean) {
        companion object {
            fun of(token: String): Tag {
                val closing = token.startsWith("</")
                val name = token.removePrefix("</").removePrefix("<")
                    .takeWhile { !it.isWhitespace() && it != '>' && it != '/' }
                val selfClosing = token.endsWith("/>")
                val isOpen = !closing && !(selfClosing && (name == "w:t" || name == "a:t"))
                return Tag(name, isOpen)
            }
        }
    }

    /** Decodes &amp; &lt; &gt; &quot; &apos; and numeric &#NNN; / &#xHH; references. */
    internal fun unescapeXml(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '&') {
                val end = text.indexOf(';', i + 1)
                if (end > i) {
                    val entity = text.substring(i + 1, end)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        out.append(decoded)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? {
        when (entity) {
            "amp" -> return "&"
            "lt" -> return "<"
            "gt" -> return ">"
            "quot" -> return "\""
            "apos" -> return "'"
        }
        if (entity.startsWith("#")) {
            val code = if (entity.startsWith("#x") || entity.startsWith("#X")) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull()
            }
            if (code != null && code in 1..0x10FFFF) return String(Character.toChars(code))
        }
        return null
    }

    /** Trims line ends and collapses 3+ newlines to 2 so loose OOXML whitespace does not leak. */
    private fun normalize(raw: String): String {
        val lines = raw.split('\n').map { it.trimEnd(' ', '\t', '\r') }
        val out = StringBuilder(raw.length)
        var newlineRun = 0
        for (line in lines) {
            if (line.isEmpty()) {
                newlineRun++
                if (newlineRun > 2) continue
            } else {
                newlineRun = 0
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString().trim()
    }

    private fun readEntry(bytes: ByteArray, wanted: String): String? {
        var found: String? = null
        forEachEntry(bytes, accept = { it == wanted }) { _, content ->
            if (found == null) found = content.toString(Charsets.UTF_8)
        }
        return found
    }

    /**
     * Streams the zip and hands entries whose name passes [accept] to [onEntry]. Other entries
     * (images, fonts, media) are skipped unread, so they never get buffered in memory.
     */
    private fun forEachEntry(
        bytes: ByteArray,
        accept: (String) -> Boolean,
        onEntry: (String, ByteArray) -> Unit
    ) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !accept(entry.name)) {
                    zip.closeEntry()
                    continue
                }
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val read = zip.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                onEntry(entry.name, buffer.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
