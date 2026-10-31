package com.dskapp.data.backup

import com.dskapp.data.chat.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupCodecTest {

    private fun sample(): BackupFile = BackupFile(
        exportedAt = 1_700_000_000_000L,
        conversations = listOf(
            BackupConversation(
                title = "여행 계획 회의",
                createdAt = 1L,
                updatedAt = 2L,
                isPinned = true,
                model = null,
                messages = listOf(
                    BackupMessage(
                        role = "user",
                        content = "안녕하세요. 사진을 첨부합니다.",
                        createdAt = 3L,
                        isError = false,
                        promptTokens = 12,
                        completionTokens = 34,
                        attachments = listOf(
                            BackupAttachment(
                                kind = AttachmentKind.IMAGE,
                                name = "바다 사진.png",
                                mime = "image/png",
                                entry = "files/abc123.png"
                            )
                        )
                    )
                )
            )
        )
    )

    @Test
    fun roundTripPreservesKoreanNullModelAndAttachment() {
        val original = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals("여행 계획 회의", decoded.conversations[0].title)
        assertNull(decoded.conversations[0].model)
        assertEquals("바다 사진.png", decoded.conversations[0].messages[0].attachments[0].name)
    }

    @Test
    fun decodeIgnoresUnknownKeys() {
        val text = """
            {
              "version": 1,
              "exportedAt": 10,
              "unknownTop": "ignored",
              "conversations": [
                {
                  "title": "제목",
                  "createdAt": 1,
                  "updatedAt": 2,
                  "extra": [1, 2, 3],
                  "messages": []
                }
              ]
            }
        """.trimIndent()

        val decoded = BackupCodec.decode(text)

        assertEquals(1, decoded.conversations.size)
        assertEquals("제목", decoded.conversations[0].title)
    }

    @Test
    fun decodeRejectsNonJson() {
        try {
            BackupCodec.decode("not json")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("백업 파일 형식이 올바르지 않습니다.", expected.message)
        }
    }

    @Test
    fun decodeRejectsNewerVersion() {
        val text = """{"version":2,"exportedAt":1,"conversations":[]}"""
        try {
            BackupCodec.decode(text)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals("백업 파일 형식이 올바르지 않습니다.", expected.message)
        }
    }
}
