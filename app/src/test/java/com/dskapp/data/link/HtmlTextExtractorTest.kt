package com.dskapp.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextExtractorTest {

    @Test
    fun extractsTitleAndDescription_nameFirst() {
        val html = """
            <html><head>
            <title>  제목 &amp; 부제  </title>
            <meta name="description" content="설명 문구">
            </head><body><p>본문</p></body></html>
        """.trimIndent()

        val page = HtmlTextExtractor.extract(html)
        assertEquals("제목 & 부제", page.title)
        assertEquals("설명 문구", page.description)
        assertEquals("본문", page.text)
    }

    @Test
    fun extractsTitleAndDescription_contentFirst() {
        val html = """
            <html><head>
            <title>제목</title>
            <meta content="설명" name="description">
            </head><body><p>본문</p></body></html>
        """.trimIndent()

        val page = HtmlTextExtractor.extract(html)
        assertEquals("제목", page.title)
        assertEquals("설명", page.description)
    }

    @Test
    fun removesNoiseElements_keepsArticleText() {
        val html = """
            <html><body>
            <nav>메뉴</nav>
            <article>
              <h1>머리글</h1>
              <p>남아야 하는 본문입니다.</p>
              <script>var x = 1;</script>
              <style>.a{color:red}</style>
            </article>
            <footer>바닥글</footer>
            </body></html>
        """.trimIndent()

        val page = HtmlTextExtractor.extract(html)
        assertTrue(page.text.contains("남아야 하는 본문입니다."))
        assertFalse(page.text.contains("메뉴"))
        assertFalse(page.text.contains("바닥글"))
        assertFalse(page.text.contains("var x"))
        assertFalse(page.text.contains("color:red"))
    }

    @Test
    fun prefersLongArticleOverBody() {
        val long = "가".repeat(600)
        val html = "<html><body><p>몸통</p><article><p>$long</p></article></body></html>"

        val page = HtmlTextExtractor.extract(html)
        assertTrue(page.text.contains(long))
        assertFalse(page.text.contains("몸통"))
    }

    @Test
    fun shortArticleFallsBackToBody() {
        val html = "<html><body><p>몸통 본문</p><article><p>짧음</p></article></body></html>"

        val page = HtmlTextExtractor.extract(html)
        assertTrue(page.text.contains("몸통 본문"))
        assertTrue(page.text.contains("짧음"))
    }

    @Test
    fun formatsBrAndLi() {
        val html = "<html><body><p>첫줄<br>둘째줄</p><ul><li>항목</li></ul></body></html>"

        val page = HtmlTextExtractor.extract(html)
        assertTrue(page.text.contains("첫줄\n둘째줄"))
        assertTrue(page.text.contains("- 항목"))
    }

    @Test
    fun unescapesEntities() {
        val html = "<html><body><p>&amp;&nbsp;&#44032;&quot;</p></body></html>"

        val page = HtmlTextExtractor.extract(html)
        assertEquals("& 가\"", page.text)
    }

    @Test
    fun appliesMaxCharsWithEllipsis() {
        val body = "나".repeat(100)
        val html = "<html><body><p>$body</p></body></html>"

        val page = HtmlTextExtractor.extract(html, maxChars = 10)
        assertEquals("나".repeat(10) + "\n…(이하 생략)", page.text)
    }

    @Test
    fun sniffsCharsetFromHttpEquiv() {
        val head = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=EUC-KR\">".toByteArray(Charsets.ISO_8859_1)
        assertEquals("euc-kr", HtmlTextExtractor.sniffCharset(head))
    }

    @Test
    fun sniffsCharsetFromMetaTag() {
        val head = "<meta charset=\"utf-8\">".toByteArray(Charsets.ISO_8859_1)
        assertEquals("utf-8", HtmlTextExtractor.sniffCharset(head))
    }

    @Test
    fun blankTitleBecomesNull() {
        val html = "<html><head><title>   </title></head><body><p>본문</p></body></html>"
        assertNull(HtmlTextExtractor.extract(html).title)
    }
}
