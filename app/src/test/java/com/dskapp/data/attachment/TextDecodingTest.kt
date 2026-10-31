package com.dskapp.data.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class TextDecodingTest {

    private val korean = "안녕하세요 DSK"

    @Test
    fun plainAsciiIsUnchanged() {
        val text = "hello world"
        assertEquals(text, TextDecoding.decode(text.toByteArray(StandardCharsets.US_ASCII), truncated = false))
    }

    @Test
    fun koreanUtf8RoundTrips() {
        val bytes = korean.toByteArray(StandardCharsets.UTF_8)
        assertEquals(korean, TextDecoding.decode(bytes, truncated = false))
    }

    @Test
    fun utf8BomIsRemoved() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + korean.toByteArray(StandardCharsets.UTF_8)
        assertEquals(korean, TextDecoding.decode(bytes, truncated = false))
    }

    @Test
    fun utf16LeWithBomDecodes() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + korean.toByteArray(StandardCharsets.UTF_16LE)
        assertEquals(korean, TextDecoding.decode(bytes, truncated = false))
    }

    @Test
    fun legacyKoreanEncodingDecodes() {
        val ms949 = Charset.forName("MS949")
        val bytes = korean.toByteArray(ms949)
        assertEquals(korean, TextDecoding.decode(bytes, truncated = false))
    }

    @Test
    fun truncatedUtf8DropsOnlyTheBrokenCharacter() {
        // "안" is three UTF-8 bytes; keep two of them to simulate a cut tail.
        val prefix = "안녕".toByteArray(StandardCharsets.UTF_8)
        val cut = "하".toByteArray(StandardCharsets.UTF_8).copyOf(2)
        val bytes = prefix + cut

        val decoded = TextDecoding.decode(bytes, truncated = true)

        assertEquals("안녕", decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun nullByteMeansNotATextFile() {
        val bytes = byteArrayOf(0x61, 0x00, 0x62)
        try {
            TextDecoding.decode(bytes, truncated = false)
            fail("binary data must be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("텍스트 파일이 아닙니다.", e.message)
        }
    }

    @Test
    fun emptyArrayDecodesToEmptyString() {
        assertEquals("", TextDecoding.decode(ByteArray(0), truncated = false))
    }
}
