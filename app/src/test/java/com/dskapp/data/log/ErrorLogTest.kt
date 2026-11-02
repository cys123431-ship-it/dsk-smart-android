package com.dskapp.data.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises only the pure helpers so the test runs on the JVM without Android. */
class ErrorLogTest {

    @Test
    fun redactMasksReplicateKey() {
        assertEquals("***", ErrorLog.redact("rv_abcDEF123456"))
        assertEquals("key=*** end", ErrorLog.redact("key=rv_abcDEF123456 end"))
    }

    @Test
    fun redactMasksBearerToken() {
        assertEquals("Authorization: Bearer ***", ErrorLog.redact("Authorization: Bearer abc.def-123"))
    }

    @Test
    fun redactMasksQueryKey() {
        assertEquals(
            "https://x.com/?api_key=***&a=1",
            ErrorLog.redact("https://x.com/?api_key=SECRET&a=1")
        )
    }

    @Test
    fun redactLeavesNormalTextUnchanged() {
        val text = "음성 읽기를 사용할 수 없습니다."
        assertEquals(text, ErrorLog.redact(text))
    }

    @Test
    fun trimKeepsShortTextWhole() {
        val text = "=== 2026-01-01 00:00:00 KST [test]\nhello\n"
        assertEquals(text, ErrorLog.trimToLimit(text, 1000))
    }

    @Test
    fun trimStartsAtHeaderAndFitsLimit() {
        val builder = StringBuilder()
        repeat(50) { i ->
            builder.append("=== 2026-01-01 00:00:00 KST [t$i]\nmessage $i\n")
        }
        val trimmed = ErrorLog.trimToLimit(builder.toString(), 200)
        assertTrue(trimmed.startsWith("=== "))
        assertTrue(trimmed.length <= 200)
    }

    @Test
    fun formatEntryWithoutStack() {
        val entry = ErrorLog.formatEntry("2026-01-01 00:00:00 KST", "net", "실패", null)
        assertEquals("=== 2026-01-01 00:00:00 KST [net]\n실패\n", entry)
    }

    @Test
    fun formatEntryWithStack() {
        val entry = ErrorLog.formatEntry("2026-01-01 00:00:00 KST", "crash", "죽음", "line1\nline2\n")
        assertEquals("=== 2026-01-01 00:00:00 KST [crash]\n죽음\nline1\nline2\n", entry)
    }
}
