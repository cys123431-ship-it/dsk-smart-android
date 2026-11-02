package com.dskapp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkTagsTest {

    @Test
    fun plainAnswerHasNoReasoning() {
        val (reasoning, answer) = ThinkTags.split("안녕하세요")
        assertNull(reasoning)
        assertEquals("안녕하세요", answer)
    }

    @Test
    fun completeThinkBlockIsSplitOff() {
        val (reasoning, answer) = ThinkTags.split("<think>\n먼저 생각\n</think>\n\n답변입니다")
        assertEquals("먼저 생각", reasoning)
        assertEquals("답변입니다", answer)
    }

    @Test
    fun unfinishedThinkBlockWhileStreaming() {
        val (reasoning, answer) = ThinkTags.split("  <think>아직 생각 중")
        assertEquals("아직 생각 중", reasoning)
        assertEquals("", answer)
    }

    @Test
    fun thinkTagLaterInTextIsLeftAlone() {
        val text = "코드 예시: <think> 태그"
        assertEquals(null to text, ThinkTags.split(text))
    }
}
