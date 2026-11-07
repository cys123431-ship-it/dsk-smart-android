package com.dskapp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "연결 테스트 fails while chat works" fix: a reasoning model that answers with
 * thinking only, or with no content at all, must be reported clearly instead of producing a
 * blank success/failure, and the token-limit flag must be readable.
 */
class ChatResponseParsingTest {

    private fun decode(json: String) =
        ChatJson.decodeFromString(ChatResponse.serializer(), json)

    @Test
    fun contentWinsOverThinking() {
        val response = decode(
            """{"choices":[{"message":{"role":"assistant","content":"연결 성공",""" +
                """"reasoning_content":"생각 중..."},"finish_reason":"stop"}]}"""
        )
        assertEquals("연결 성공", response.text())
        assertEquals("생각 중...", response.thinkingText())
        assertFalse(response.hitTokenLimit())
    }

    @Test
    fun reasoningOnlyReplyIsDetected() {
        val response = decode(
            """{"choices":[{"message":{"role":"assistant","content":"",""" +
                """"reasoning":"thinking only"},"finish_reason":"length"}]}"""
        )
        assertEquals("", response.text())
        assertEquals("thinking only", response.thinkingText())
        assertTrue(response.hitTokenLimit())
    }

    @Test
    fun completelyEmptyChoiceHasNoThinking() {
        val response = decode("""{"choices":[{"message":{"role":"assistant"}}]}""")
        assertEquals("", response.text())
        assertEquals("", response.thinkingText())
        assertFalse(response.hitTokenLimit())
    }

    @Test
    fun finishReasonLengthIsCaseInsensitive() {
        val response = decode("""{"choices":[{"message":{"content":"par"},"finish_reason":"LENGTH"}]}""")
        assertTrue(response.hitTokenLimit())
    }
}
