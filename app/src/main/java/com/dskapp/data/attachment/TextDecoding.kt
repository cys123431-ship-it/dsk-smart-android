package com.dskapp.data.attachment

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decodes raw bytes from a user-picked text file.
 *
 * Files reach us without a guarantee about their encoding: the system picker happily hands
 * over UTF-16, BOM-marked UTF-8 or legacy Korean (EUC-KR / MS949) exports. We try the cheap
 * and unambiguous signals (BOM, null bytes, strict UTF-8) before falling back to the legacy
 * Korean charset, so we never silently mangle Hangul text.
 */
object TextDecoding {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Tried in order; the first supported legacy Korean charset wins. */
    private val LEGACY_KOREAN_NAMES = listOf("x-windows-949", "windows-949", "MS949", "EUC-KR")

    private const val NOT_A_TEXT_FILE = "텍스트 파일이 아닙니다."

    /**
     * Decodes bytes read from a user-picked text file.
     * [truncated] = true when the file was longer than the bytes we read, so the last
     * multi-byte character may be cut in half.
     * Throws IllegalArgumentException("텍스트 파일이 아닙니다.") for binary data.
     */
    fun decode(bytes: ByteArray, truncated: Boolean): String {
        // 1. BOM detection is unambiguous, so it wins over every heuristic below.
        if (startsWith(bytes, UTF8_BOM)) {
            return decodeUtf8Strict(bytes, UTF8_BOM.size, truncated)
                ?: decodeWithReplace(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, StandardCharsets.UTF_8)
        }
        if (startsWith(bytes, UTF16LE_BOM)) {
            return decodeUtf16(bytes, UTF16LE_BOM.size, StandardCharsets.UTF_16LE, truncated)
        }
        if (startsWith(bytes, UTF16BE_BOM)) {
            return decodeUtf16(bytes, UTF16BE_BOM.size, StandardCharsets.UTF_16BE, truncated)
        }

        // 2. A 0x00 byte outside UTF-16 is the strongest signal for a binary file.
        if (bytes.any { it == 0.toByte() }) throw IllegalArgumentException(NOT_A_TEXT_FILE)

        // 3. Strict UTF-8; a truncated tail is allowed to be an incomplete sequence.
        decodeUtf8Strict(bytes, 0, truncated)?.let { return it }

        // 4. Legacy Korean fallback, then plain UTF-8 with replacement as the last resort.
        val legacy = legacyKoreanCharset()
        return if (legacy != null) {
            decodeWithReplace(bytes, 0, bytes.size, legacy)
        } else {
            decodeWithReplace(bytes, 0, bytes.size, StandardCharsets.UTF_8)
        }
    }

    /**
     * Tries the array with 0..3 trailing bytes dropped (only when truncated), because a
     * cut 3-byte Hangul sequence is exactly the case that must not produce U+FFFD.
     */
    private fun decodeUtf8Strict(bytes: ByteArray, offset: Int, truncated: Boolean): String? {
        val maxDrop = if (truncated) 3 else 0
        val available = bytes.size - offset
        for (drop in 0..maxDrop) {
            val length = available - drop
            if (length < 0) continue
            try {
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString()
            } catch (_: CharacterCodingException) {
                // Not valid UTF-8 with this many dropped bytes: try the next one.
            }
        }
        return null
    }

    private fun decodeUtf16(bytes: ByteArray, offset: Int, charset: Charset, truncated: Boolean): String {
        var length = bytes.size - offset
        // A trailing lone byte would become a replacement char; drop it instead.
        if (truncated && length % 2 != 0) length -= 1
        if (length <= 0) return ""
        return decodeWithReplace(bytes, offset, length, charset)
    }

    private fun decodeWithReplace(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()

    private fun legacyKoreanCharset(): Charset? =
        LEGACY_KOREAN_NAMES.firstNotNullOfOrNull { name ->
            runCatching { if (Charset.isSupported(name)) Charset.forName(name) else null }.getOrNull()
        }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) return false
        }
        return true
    }
}
