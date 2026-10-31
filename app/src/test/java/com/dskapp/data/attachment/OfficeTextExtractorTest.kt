package com.dskapp.data.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeTextExtractorTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun documentXml(body: String): String =
        """<w:document xmlns:w="x"><w:body>$body</w:body></w:document>"""

    @Test
    fun docx_twoParagraphs() {
        val body = "<w:p><w:r><w:t>첫 문단</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>둘째 문단</w:t></w:r></w:p>"
        val bytes = zip("word/document.xml" to documentXml(body))
        assertEquals("첫 문단\n둘째 문단", OfficeTextExtractor.docx(bytes))
    }

    @Test
    fun docx_splitRunsConcatenate() {
        val body = "<w:p><w:r><w:t>안녕</w:t></w:r><w:r><w:t>하세요</w:t></w:r></w:p>"
        val bytes = zip("word/document.xml" to documentXml(body))
        assertEquals("안녕하세요", OfficeTextExtractor.docx(bytes))
    }

    @Test
    fun docx_tabAndBreak() {
        val body = "<w:p><w:r><w:t>가</w:t><w:tab/><w:t>나</w:t><w:br/><w:t>다</w:t></w:r></w:p>"
        val bytes = zip("word/document.xml" to documentXml(body))
        assertEquals("가\t나\n다", OfficeTextExtractor.docx(bytes))
    }

    @Test
    fun docx_entities() {
        val body = "<w:p><w:r><w:t>a&amp;&lt;b</w:t></w:r></w:p>"
        val bytes = zip("word/document.xml" to documentXml(body))
        assertEquals("a&<b", OfficeTextExtractor.docx(bytes))
    }

    @Test
    fun docx_tableCellsAndRows() {
        val body = "<w:tbl>" +
            "<w:tr>" +
            "<w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>" +
            "</w:tr>" +
            "<w:tr>" +
            "<w:tc><w:p><w:r><w:t>C</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>D</w:t></w:r></w:p></w:tc>" +
            "</w:tr>" +
            "</w:tbl>"
        val text = OfficeTextExtractor.docx(zip("word/document.xml" to documentXml(body)))
        val lines = text.lines()
        assertTrue("A\\tB not in $lines", lines.any { it.contains("A\tB") })
        assertTrue("C\\tD not in $lines", lines.any { it.contains("C\tD") })
    }

    @Test
    fun docx_missingDocumentXmlThrows() {
        val bytes = zip("[Content_Types].xml" to "<Types/>")
        try {
            OfficeTextExtractor.docx(bytes)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Word 문서를 읽을 수 없습니다.", e.message)
        }
    }

    private fun slideXml(vararg paragraphs: String): String =
        """<p:sld xmlns:p="p" xmlns:a="a"><p:cSld><p:spTree><p:sp><p:txBody>""" +
            paragraphs.joinToString("") +
            "</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"

    @Test
    fun pptx_slidesSortedNumerically() {
        val bytes = zip(
            "ppt/slides/slide2.xml" to slideXml("<a:p><a:r><a:t>둘째</a:t></a:r></a:p>"),
            "ppt/slides/slide10.xml" to slideXml("<a:p><a:r><a:t>열째</a:t></a:r></a:p>"),
            "ppt/slides/slide1.xml" to slideXml("<a:p><a:r><a:t>첫째</a:t></a:r></a:p>"),
        )
        val text = OfficeTextExtractor.pptx(bytes)
        val one = text.indexOf("## 슬라이드 1")
        val two = text.indexOf("## 슬라이드 2")
        val ten = text.indexOf("## 슬라이드 10")
        assertTrue(one in 0 until two)
        assertTrue(two in 0 until ten)
        assertTrue(text.contains("첫째"))
        assertTrue(text.contains("둘째"))
        assertTrue(text.contains("열째"))
    }

    @Test
    fun pptx_breakBecomesNewline() {
        val bytes = zip(
            "ppt/slides/slide1.xml" to slideXml(
                "<a:p><a:r><a:t>위</a:t></a:r><a:br/><a:r><a:t>아래</a:t></a:r></a:p>",
            ),
        )
        val text = OfficeTextExtractor.pptx(bytes)
        assertTrue("위\\n아래 not in $text", text.contains("위\n아래"))
    }

    @Test
    fun pptx_noSlidesThrows() {
        val bytes = zip("[Content_Types].xml" to "<Types/>")
        try {
            OfficeTextExtractor.pptx(bytes)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("PowerPoint 문서를 읽을 수 없습니다.", e.message)
        }
    }

    @Test
    fun unescapeXml_decodesNumericReferences() {
        assertEquals("가", OfficeTextExtractor.unescapeXml("&#44032;"))
        assertEquals("가", OfficeTextExtractor.unescapeXml("&#xAC00;"))
    }
}
