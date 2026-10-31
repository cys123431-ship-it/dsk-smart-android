package com.dskapp.data.export

import com.dskapp.data.export.XmlEscape.text
import java.io.File

/**
 * Generates a valid .docx (WordprocessingML) from parsed markdown blocks.
 *
 * Headings become styled paragraphs, bullets become indented list paragraphs, code becomes
 * a shaded monospace block, and tables become real Word tables.
 */
object DocxWriter {

    fun write(target: File, title: String, blocks: List<MdBlock>) {
        OoxmlZip.write(
            target,
            mapOf(
                "[Content_Types].xml" to contentTypes(),
                "_rels/.rels" to rootRels(),
                "word/document.xml" to document(title, blocks),
                "word/styles.xml" to styles(),
                "word/_rels/document.xml.rels" to documentRels()
            )
        )
    }

    private fun contentTypes() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRels() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()

    private fun documentRels() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun document(title: String, blocks: List<MdBlock>): String {
        val body = StringBuilder()

        // Add the thread title as a top heading when the content does not start with one.
        if (blocks.firstOrNull() !is MdBlock.Heading) {
            body.append(headingParagraph(1, title))
        }

        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> body.append(headingParagraph(block.level, block.text))
                is MdBlock.Paragraph -> body.append(textParagraph(block.text))
                is MdBlock.Quote -> body.append(quoteParagraph(block.text))
                is MdBlock.Bullet -> block.items.forEachIndexed { i, item ->
                    body.append(listParagraph(item, block.ordered, i + 1))
                }
                is MdBlock.Code -> body.append(codeParagraph(block))
                is MdBlock.Table -> body.append(table(block))
                MdBlock.Rule -> body.append(ruleParagraph())
            }
        }

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                $body
                <w:sectPr>
                  <w:pgSz w:w="11906" w:h="16838"/>
                  <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/>
                </w:sectPr>
              </w:body>
            </w:document>
        """.trimIndent()
    }

    private fun headingParagraph(level: Int, raw: String): String {
        val style = "Heading${level.coerceIn(1, 4)}"
        return """<w:p><w:pPr><w:pStyle w:val="$style"/></w:pPr>""" +
            """<w:r><w:t xml:space="preserve">${text(MarkdownParser.stripInline(raw))}</w:t></w:r></w:p>"""
    }

    private fun textParagraph(raw: String): String =
        """<w:p><w:r><w:t xml:space="preserve">${text(MarkdownParser.stripInline(raw))}</w:t></w:r></w:p>"""

    private fun quoteParagraph(raw: String): String =
        """<w:p><w:pPr><w:ind w:left="567"/><w:pBdr>""" +
            """<w:left w:val="single" w:sz="12" w:space="8" w:color="CBD5E1"/></w:pBdr></w:pPr>""" +
            """<w:r><w:rPr><w:i/><w:color w:val="475569"/></w:rPr>""" +
            """<w:t xml:space="preserve">${text(MarkdownParser.stripInline(raw))}</w:t></w:r></w:p>"""

    private fun listParagraph(raw: String, ordered: Boolean, number: Int): String {
        val marker = if (ordered) "$number." else "•"
        return """<w:p><w:pPr><w:ind w:left="567" w:hanging="284"/></w:pPr>""" +
            """<w:r><w:t xml:space="preserve">$marker </w:t></w:r>""" +
            """<w:r><w:t xml:space="preserve">${text(MarkdownParser.stripInline(raw))}</w:t></w:r></w:p>"""
    }

    private fun codeParagraph(block: MdBlock.Code): String {
        val lines = block.code.split('\n')
        val runs = lines.mapIndexed { index, line ->
            val breakTag = if (index < lines.lastIndex) "<w:br/>" else ""
            """<w:r><w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>""" +
                """<w:sz w:val="18"/></w:rPr><w:t xml:space="preserve">${text(line)}</w:t>$breakTag</w:r>"""
        }.joinToString("")

        return """<w:p><w:pPr><w:shd w:val="clear" w:fill="F1F5F9"/></w:pPr>$runs</w:p>"""
    }

    private fun ruleParagraph(): String =
        """<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="CBD5E1"/>""" +
            """</w:pBdr></w:pPr></w:p>"""

    private fun table(block: MdBlock.Table): String {
        val builder = StringBuilder()
        builder.append("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/>""")
        builder.append("""<w:tblW w:w="5000" w:type="pct"/><w:tblBorders>""")
        builder.append("""<w:top w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""<w:left w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""<w:bottom w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""<w:right w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""<w:insideH w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""<w:insideV w:val="single" w:sz="4" w:color="CBD5E1"/>""")
        builder.append("""</w:tblBorders></w:tblPr>""")

        fun row(cells: List<String>, header: Boolean) {
            builder.append("<w:tr>")
            cells.forEach { cell ->
                val shading = if (header) """<w:shd w:val="clear" w:fill="E2E8F0"/>""" else ""
                val bold = if (header) "<w:b/>" else ""
                builder.append("<w:tc><w:tcPr>$shading</w:tcPr>")
                builder.append("<w:p><w:r><w:rPr>$bold</w:rPr>")
                builder.append("<w:t xml:space=\"preserve\">")
                builder.append(text(MarkdownParser.stripInline(cell)))
                builder.append("</w:t></w:r></w:p></w:tc>")
            }
            builder.append("</w:tr>")
        }

        row(block.header, header = true)
        block.rows.forEach { row(it, header = false) }
        builder.append("</w:tbl>")
        // A table must be followed by a paragraph or Word reports a repair prompt.
        builder.append("<w:p/>")
        return builder.toString()
    }

    /** Style definitions referenced by the body (headings, table grid). */
    private fun styles(): String {
        fun headingStyle(level: Int, size: Int, color: String) = buildString {
            append("""<w:style w:type="paragraph" w:styleId="Heading$level">""")
            append("""<w:name w:val="heading $level"/><w:basedOn w:val="Normal"/>""")
            append("""<w:pPr><w:keepNext/>""")
            append("""<w:spacing w:before="240" w:after="120"/>""")
            append("""<w:outlineLvl w:val="${level - 1}"/></w:pPr>""")
            append("""<w:rPr><w:b/><w:sz w:val="$size"/><w:color w:val="$color"/></w:rPr>""")
            append("</w:style>")
        }

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:docDefaults>
                <w:rPrDefault><w:rPr>
                  <w:rFonts w:ascii="Malgun Gothic" w:hAnsi="Malgun Gothic" w:eastAsia="Malgun Gothic"/>
                  <w:sz w:val="22"/>
                </w:rPr></w:rPrDefault>
                <w:pPrDefault><w:pPr>
                  <w:spacing w:after="120" w:line="300" w:lineRule="auto"/>
                </w:pPr></w:pPrDefault>
              </w:docDefaults>
              <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
              ${headingStyle(1, 36, "1E3A8A")}
              ${headingStyle(2, 30, "1E40AF")}
              ${headingStyle(3, 26, "1D4ED8")}
              ${headingStyle(4, 24, "1D4ED8")}
              <w:style w:type="table" w:styleId="TableGrid">
                <w:name w:val="Table Grid"/>
                <w:tblPr><w:tblCellMar>
                  <w:top w:w="60" w:type="dxa"/><w:left w:w="90" w:type="dxa"/>
                  <w:bottom w:w="60" w:type="dxa"/><w:right w:w="90" w:type="dxa"/>
                </w:tblCellMar></w:tblPr>
              </w:style>
            </w:styles>
        """.trimIndent()
    }
}


