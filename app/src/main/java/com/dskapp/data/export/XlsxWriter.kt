package com.dskapp.data.export

import com.dskapp.data.export.XmlEscape.text
import java.io.File

/**
 * Generates an .xlsx (SpreadsheetML) from parsed markdown.
 *
 * Every markdown table becomes its own worksheet; non-table content (headings, paragraphs,
 * bullets) goes into a "내용" sheet as an outline so nothing is lost when a reply mixes
 * prose and tables.
 */
object XlsxWriter {

    /** Excel caps sheet names at 31 chars and forbids : \ / ? * [ ] */
    private const val MAX_SHEET_NAME = 31
    private val invalidSheetChars = Regex("[:\\\\/?*\\[\\]]")

    fun write(target: File, title: String, blocks: List<MdBlock>) {
        val tables = blocks.filterIsInstance<MdBlock.Table>()
        val other = blocks.filterNot { it is MdBlock.Table }

        val sheets = buildList {
            add(Sheet("내용", other))
            tables.forEachIndexed { index, table ->
                val raw = table.header.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.replace(invalidSheetChars, "_")
                    ?.take(MAX_SHEET_NAME)
                    ?: "표 ${index + 1}"
                add(Sheet(uniqueName(raw, this), listOf(table)))
            }
        }

        OoxmlZip.write(
            target,
            mapOf(
                "[Content_Types].xml" to contentTypes(sheets.size),
                "_rels/.rels" to rootRels(),
                "xl/workbook.xml" to workbook(sheets),
                "xl/_rels/workbook.xml.rels" to workbookRels(sheets.size),
                "xl/styles.xml" to styles()
            ) + sheets.mapIndexed { index, sheet ->
                "xl/worksheets/sheet${index + 1}.xml" to worksheet(sheet, title)
            }
        )
    }

    private data class Sheet(val name: String, val blocks: List<MdBlock>)

    private fun uniqueName(candidate: String, existing: List<Sheet>): String {
        if (existing.none { it.name == candidate }) return candidate
        var suffix = 2
        while (true) {
            val next = candidate.take(MAX_SHEET_NAME - 2) + suffix
            if (existing.none { it.name == next }) return next
            suffix++
        }
    }

    /** Style 0 = normal wrapped cell, style 1 = bold header cell. */
    private fun styles() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="2"><font><sz val="11"/><name val="Malgun Gothic"/></font>""" +
            """<font><b/><sz val="11"/><name val="Malgun Gothic"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FFE2E8F0"/>""" +
            """<bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="2">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1">""" +
            """<alignment vertical="top" wrapText="1"/></xf>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" """ +
            """applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>""" +
            """</cellXfs><cellStyles count="1">""" +
            """<cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

    /** 0 -> A, 25 -> Z, 26 -> AA ... */
    private fun columnName(index: Int): String {
        var value = index
        val builder = StringBuilder()
        while (true) {
            builder.insert(0, ('A' + value % 26))
            value = value / 26 - 1
            if (value < 0) break
        }
        return builder.toString()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" """ +
            """ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        repeat(sheetCount) { index ->
            append("""<Override PartName="/xl/worksheets/sheet${index + 1}.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
            """Target="xl/workbook.xml"/></Relationships>"""

    private fun workbook(sheets: List<Sheet>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """)
        append("""xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            append("""<sheet name="${text(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        repeat(sheetCount) { index ->
            append("""<Relationship Id="rId${index + 1}" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                """Target="worksheets/sheet${index + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" """ +
            """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" """ +
            """Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun worksheet(sheet: Sheet, title: String): String {
        val rows = mutableListOf<List<Pair<String, Int>>>()
        val tables = sheet.blocks.filterIsInstance<MdBlock.Table>()
        val others = sheet.blocks.filterNot { it is MdBlock.Table }

        if (others.isNotEmpty() || tables.isEmpty()) {
            rows.add(listOf(title to 1))
            others.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> rows.add(listOf(block.text to 1))
                    is MdBlock.Paragraph -> rows.add(listOf(plain(block.text) to 0))
                    is MdBlock.Quote -> rows.add(listOf(plain(block.text) to 0))
                    is MdBlock.Bullet -> block.items.forEach { item ->
                        rows.add(listOf("• ${plain(item)}" to 0))
                    }
                    is MdBlock.Code -> block.code.split('\n').forEach { line ->
                        rows.add(listOf(line to 0))
                    }
                    MdBlock.Rule -> rows.add(listOf("" to 0))
                    is MdBlock.Table -> Unit
                }
            }
        }

        tables.forEach { table ->
            if (rows.isNotEmpty()) rows.add(listOf("" to 0))
            rows.add(table.header.map { plain(it) to 1 })
            table.rows.forEach { row -> rows.add(row.map { plain(it) to 0 }) }
        }

        val sheetData = rows.mapIndexed { rowIndex, cells ->
            val rowNumber = rowIndex + 1
            buildString {
                append("""<row r="$rowNumber">""")
                cells.forEachIndexed { columnIndex, cell ->
                    val ref = columnName(columnIndex) + rowNumber
                    append("""<c r="$ref" t="inlineStr" s="${cell.second}">""")
                    append("""<is><t xml:space="preserve">${text(cell.first)}</t></is></c>""")
                }
                append("</row>")
            }
        }.joinToString("")

        val columnCount = (rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val cols = (0 until columnCount).joinToString("") { index ->
            val width = if (index == 0) 42 else 26
            """<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>"""
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<sheetViews><sheetView workbookViewId="0"/></sheetViews>""" +
            """<sheetFormatPr defaultRowHeight="18"/>""" +
            """<cols>$cols</cols><sheetData>$sheetData</sheetData></worksheet>"""
    }

    private fun plain(raw: String): String = MarkdownParser.stripInline(raw)
}


