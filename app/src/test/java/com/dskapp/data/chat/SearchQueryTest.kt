package com.dskapp.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {

    @Test
    fun plainTextIsUnchanged() {
        assertEquals("안녕 DSK", escapeLikePattern("안녕 DSK"))
    }

    @Test
    fun percentIsEscaped() {
        assertEquals("50\\%", escapeLikePattern("50%"))
    }

    @Test
    fun underscoreIsEscaped() {
        assertEquals("a\\_b", escapeLikePattern("a_b"))
    }

    @Test
    fun backslashIsDoubled() {
        assertEquals("a\\\\b", escapeLikePattern("a\\b"))
    }

    @Test
    fun mixedWildcardsAndBackslash() {
        assertEquals("\\\\\\%\\_", escapeLikePattern("\\%_"))
    }
}
