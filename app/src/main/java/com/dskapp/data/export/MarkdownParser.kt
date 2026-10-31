package com.dskapp.data.export

/**
 * Parses the markdown the assistant emits into [MdBlock]s.
 *
 * This is intentionally not a full CommonMark implementation: it handles headings, bullets,
 * numbered lists, fenced code, pipe tables, quotes and horizontal rules, which is what the
 * system prompt asks the model to produce. Anything else becomes a paragraph.
 */
object MarkdownParser {

    private val headingRegex = Regex("^(#{1,6})\\s+(.*)$")
    private val bulletRegex = Regex("^\\s*[-*+]\\s+(.*)$")
    private val orderedRegex = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    // Three or more of the same marker, optionally spaced ("---", "* * *"). A back-reference
    // cannot appear inside a character class, so the repetition is spelled out as a group.
    private val ruleRegex = Regex("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$")
    private val tableSeparatorRegex = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var index = 0

        while (index < lines.size) {
            val line = lines[index]

            when {
                line.isBlank() -> index++

                line.trimStart().startsWith("```") -> {
                    val language = line.trim().removePrefix("```").trim()
                    val code = StringBuilder()
                    index++
                    while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                        code.appendLine(lines[index])
                        index++
                    }
                    index++ // skip the closing fence
                    blocks += MdBlock.Code(language, code.toString().trimEnd())
                }

                headingRegex.matches(line) -> {
                    val match = headingRegex.find(line)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        text = match.groupValues[2].trim()
                    )
                    index++
                }

                ruleRegex.matches(line) -> {
                    blocks += MdBlock.Rule
                    index++
                }

                line.trimStart().startsWith(">") -> {
                    val quote = StringBuilder()
                    while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                        if (quote.isNotEmpty()) quote.append(' ')
                        quote.append(lines[index].trimStart().removePrefix(">").trim())
                        index++
                    }
                    blocks += MdBlock.Quote(quote.toString())
                }

                isTableStart(lines, index) -> {
                    val header = splitRow(lines[index])
                    index += 2
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size && lines[index].contains('|') && lines[index].isNotBlank()) {
                        rows += splitRow(lines[index])
                        index++
                    }
                    blocks += MdBlock.Table(header, rows)
                }

                bulletRegex.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && bulletRegex.matches(lines[index])) {
                        items += bulletRegex.find(lines[index])!!.groupValues[1].trim()
                        index++
                    }
                    blocks += MdBlock.Bullet(items, ordered = false)
                }

                orderedRegex.matches(line) -> {
                    val items = mutableListOf<String>()
                    while (index < lines.size && orderedRegex.matches(lines[index])) {
                        items += orderedRegex.find(lines[index])!!.groupValues[1].trim()
                        index++
                    }
                    blocks += MdBlock.Bullet(items, ordered = true)
                }

                else -> {
                    val paragraph = StringBuilder()
                    while (
                        index < lines.size &&
                        lines[index].isNotBlank() &&
                        !headingRegex.matches(lines[index]) &&
                        !bulletRegex.matches(lines[index]) &&
                        !orderedRegex.matches(lines[index]) &&
                        !lines[index].trimStart().startsWith("```") &&
                        !lines[index].trimStart().startsWith(">")
                    ) {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(lines[index].trim())
                        index++
                    }
                    blocks += MdBlock.Paragraph(paragraph.toString())
                }
            }
        }

        return blocks
    }

    /** Strips inline markdown (bold/italic/code markers) for plain-text output targets. */
    fun stripInline(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1")
        .trim()

    private fun isTableStart(lines: List<String>, index: Int): Boolean =
        index + 1 < lines.size &&
            lines[index].contains('|') &&
            tableSeparatorRegex.matches(lines[index + 1])

    private fun splitRow(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }
}
