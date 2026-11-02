package com.dskapp.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlFinderTest {

    @Test
    fun stripsTrailingPunctuationAndBrackets() {
        val text = "참고: https://example.com/page. 그리고 (https://example.com/b) 끝"
        val urls = UrlFinder.find(text)
        assertTrue(urls.contains("https://example.com/page"))
        assertTrue(urls.contains("https://example.com/b"))
    }

    @Test
    fun stripsKoreanBrackets() {
        val text = "링크 https://example.com/a」 그리고 https://example.com/c）"
        val urls = UrlFinder.find(text)
        assertTrue(urls.contains("https://example.com/a"))
        assertTrue(urls.contains("https://example.com/c"))
    }

    @Test
    fun keepsBalancedWikiParentheses() {
        val url = "https://ko.wikipedia.org/wiki/대한민국_(국가)"
        val urls = UrlFinder.find(url)
        assertEquals(listOf(url), urls)
    }

    @Test
    fun removesDuplicates() {
        val text = "https://example.com/x https://example.com/x https://example.com/y"
        val urls = UrlFinder.find(text)
        assertEquals(listOf("https://example.com/x", "https://example.com/y"), urls)
    }

    @Test
    fun respectsMax() {
        val text = "https://example.com/1 https://example.com/2 https://example.com/3 https://example.com/4"
        assertEquals(2, UrlFinder.find(text, max = 2).size)
    }

    @Test
    fun ignoresTextWithoutUrl() {
        assertTrue(UrlFinder.find("링크가 없는 그냥 문장입니다.").isEmpty())
    }

    @Test
    fun ignoresTooShortUrl() {
        assertTrue(UrlFinder.find("http://ab").isEmpty())
    }
}
