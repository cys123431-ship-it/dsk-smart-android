package com.dskapp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRequestMessageTest {

    private fun encode(message: ChatRequestMessage) =
        ChatJson.encodeToString(ChatRequestMessage.serializer(), message)

    @Test
    fun textOnlyMessageIsPlainString() {
        val message = chatRequestMessage("assistant", "안녕하세요")!!
        assertEquals("""{"role":"assistant","content":"안녕하세요"}""", encode(message))
    }

    @Test
    fun imagesUsePartList() {
        val message = chatRequestMessage("user", "이거 뭐야?", listOf("data:image/jpeg;base64,AAA"))!!
        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"이거 뭐야?"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAA"}}]}""",
            encode(message)
        )
    }

    @Test
    fun imageWithoutTextHasNoEmptyTextPart() {
        val message = chatRequestMessage("user", " ", listOf("data:image/jpeg;base64,AAA"))!!
        assertEquals(
            """{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAA"}}]}""",
            encode(message)
        )
    }

    @Test
    fun emptyMessageIsDropped() {
        assertNull(chatRequestMessage("system", "  "))
    }
}
