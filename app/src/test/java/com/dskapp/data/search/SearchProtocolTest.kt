package com.dskapp.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchProtocolTest {

    private fun hit(title: String, url: String, snippet: String = "") = SearchHit(title, url, snippet)

    @Test
    fun singleQueryParsed() {
        val queries = SearchProtocol.parseRequest("<search>갤럭시 s26</search>")
        assertEquals(listOf("갤럭시 s26"), queries)
    }

    @Test
    fun extraQueriesIgnored() {
        val answer = "<search>a</search>\n<search>b</search>\n<search>c</search>"
        val queries = SearchProtocol.parseRequest(answer)
        assertEquals(listOf("a", "b"), queries)
    }

    @Test
    fun whitespaceCollapsed() {
        val queries = SearchProtocol.parseRequest("<search>삼성   갤럭시\n출시일</search>")
        assertEquals(listOf("삼성 갤럭시 출시일"), queries)
    }

    @Test
    fun shortPreambleAccepted() {
        val queries = SearchProtocol.parseRequest("검색해 볼게요\n<search>a</search>")
        assertEquals(listOf("a"), queries)
    }

    @Test
    fun normalAnswerIsNotRequest() {
        assertNull(SearchProtocol.parseRequest("이것은 일반적인 답변입니다."))
    }

    @Test
    fun longAnswerWithTagIsNotRequest() {
        val answer = "가".repeat(300) + "<search>a</search>"
        assertNull(SearchProtocol.parseRequest(answer))
    }

    @Test
    fun emptyTagIsNotRequest() {
        assertNull(SearchProtocol.parseRequest("<search>   </search>"))
    }

    @Test
    fun mayBeRequestDetection() {
        assertTrue(SearchProtocol.mayBeRequest(""))
        assertTrue(SearchProtocol.mayBeRequest("  <se"))
        assertTrue(SearchProtocol.mayBeRequest("<SEARCH>x"))
        assertFalse(SearchProtocol.mayBeRequest("안녕"))
        assertFalse(SearchProtocol.mayBeRequest("<b>"))
    }

    @Test
    fun stripRequestsRemovesTags() {
        val stripped = SearchProtocol.stripRequests("잠시만요 <search>a</search>")
        assertEquals("잠시만요", stripped)
    }

    @Test
    fun resultsMessageIncludesHitsAndPages() {
        val report = SearchReport(
            queries = listOf("갤럭시"),
            hits = listOf(
                hit("첫 결과", "https://a.com/1", "요약 하나"),
                hit("둘째 결과", "https://b.com/2")
            ),
            pages = listOf(PageExcerpt("https://a.com/1", "페이지 제목", "페이지 본문 내용")),
            errors = emptyList()
        )

        val message = SearchProtocol.resultsMessage(report, finalRound = false)

        assertTrue(message.contains("https://a.com/1"))
        assertTrue(message.contains("https://b.com/2"))
        assertTrue(message.contains("페이지 본문 내용"))
        assertFalse(message.contains("더 이상 검색할 수 없으니"))
    }

    @Test
    fun finalRoundMentionsNoMoreSearch() {
        val report = SearchReport(
            queries = listOf("x"),
            hits = listOf(hit("결과", "https://a.com/1")),
            pages = emptyList(),
            errors = emptyList()
        )
        val message = SearchProtocol.resultsMessage(report, finalRound = true)
        assertTrue(message.contains("더 이상 검색할 수 없으니"))
    }

    @Test
    fun emptyHitsMentionsNoResultsAndError() {
        val report = SearchReport(
            queries = listOf("x"),
            hits = emptyList(),
            pages = emptyList(),
            errors = listOf("'x': 네트워크 오류")
        )
        val message = SearchProtocol.resultsMessage(report, finalRound = false)
        assertTrue(message.contains("검색 결과가 없습니다"))
        assertTrue(message.contains("'x': 네트워크 오류"))
    }
}
