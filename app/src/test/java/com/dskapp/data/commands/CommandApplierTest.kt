package com.dskapp.data.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandApplierTest {

    @Test
    fun appendsTypedTextUnderCommand() {
        assertEquals("3줄 요약\n\n긴 글", CommandApplier.apply("3줄 요약", "  긴 글 "))
    }

    @Test
    fun emptyInputLeavesRoomToType() {
        assertEquals("3줄 요약\n\n", CommandApplier.apply("3줄 요약", ""))
    }

    @Test
    fun placeholderIsReplaced() {
        assertEquals("「안녕」을 영어로", CommandApplier.apply("「{입력}」을 영어로", "안녕"))
    }
}
