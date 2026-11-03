package com.dskapp.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoParserTest {

    private fun anchor(href: String, title: String): String = """
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
              <h2 class="result__title">
                <a rel="nofollow" class="result__a" href="$href">$title</a>
              </h2>
        </div>
        </div>
    """.trimIndent()

    private fun withSnippet(href: String, title: String, snippet: String): String = """
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
              <h2 class="result__title">
                <a rel="nofollow" class="result__a" href="$href">$title</a>
              </h2>
              <div class="result__extras">
                <div class="result__extras__url">
                  <a class="result__url" href="$href">${href.removePrefix("https://")}</a>
                </div>
              </div>
              <a class="result__snippet" href="$href">$snippet</a>
            <div class="clear"></div>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun parsesTwoNormalResults() {
        val html = anchor("https://a.com/1", "첫 <b>결과</b> &amp; 더") +
            withSnippet("https://b.com/2", "둘째 결과 &amp; 끝", "요약 <b>본문</b> &amp; 더")

        val hits = DuckDuckGoParser.parse(html, 10)

        assertEquals(2, hits.size)
        assertEquals("첫 결과 & 더", hits[0].title)
        assertEquals("https://a.com/1", hits[0].url)
        assertEquals("", hits[0].snippet)
        assertEquals("둘째 결과 & 끝", hits[1].title)
        assertEquals("https://b.com/2", hits[1].url)
        assertEquals("요약 본문 & 더", hits[1].snippet)
    }

    @Test
    fun skipsAdLinks() {
        val html = anchor("https://duckduckgo.com/y.js?ad_domain=x.com&u3=abc", "광고") +
            anchor("https://real.com/page", "실제 결과")

        val hits = DuckDuckGoParser.parse(html, 10)

        assertEquals(1, hits.size)
        assertEquals("https://real.com/page", hits[0].url)
    }

    @Test
    fun resolvesRedirectLinks() {
        val html = anchor(
            "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&amp;rut=abc",
            "리다이렉트 결과"
        )

        val hits = DuckDuckGoParser.parse(html, 10)

        assertEquals(1, hits.size)
        assertEquals("https://example.com/a?b=1", hits[0].url)
    }

    @Test
    fun handlesAttributeOrderAndSingleQuotes() {
        val html = """
            <div class="result">
              <a class="result__a" rel="nofollow" href='https://x.com/1'>제목 하나</a>
              <a class="result__url" href="https://x.com/1">x.com/1</a>
            </div>
        """.trimIndent()

        val hits = DuckDuckGoParser.parse(html, 10)

        assertEquals(1, hits.size)
        assertEquals("https://x.com/1", hits[0].url)
        assertEquals("제목 하나", hits[0].title)
    }

    @Test
    fun resultWithoutSnippetHasEmptySnippet() {
        val html = anchor("https://only.com/1", "요약 없는 결과")

        val hits = DuckDuckGoParser.parse(html, 10)

        assertEquals(1, hits.size)
        assertEquals("", hits[0].snippet)
    }

    @Test
    fun deduplicatesUrlsAndRespectsLimit() {
        val html = anchor("https://dup.com/1", "중복 하나") +
            anchor("https://dup.com/1", "중복 둘") +
            anchor("https://other.com/2", "다른 결과")

        val hits = DuckDuckGoParser.parse(html, 10)
        assertEquals(2, hits.size)
        assertEquals("https://dup.com/1", hits[0].url)
        assertEquals("https://other.com/2", hits[1].url)

        val limited = DuckDuckGoParser.parse(html, 1)
        assertEquals(1, limited.size)
    }

    @Test
    fun detectsBotChallenge() {
        val blocked = """<div class="anomaly-modal__title">검증</div>"""
        val normal = anchor("https://fine.com/1", "정상 결과")

        assertTrue(DuckDuckGoParser.isBlocked(blocked))
        assertFalse(DuckDuckGoParser.isBlocked(normal))
    }
}
