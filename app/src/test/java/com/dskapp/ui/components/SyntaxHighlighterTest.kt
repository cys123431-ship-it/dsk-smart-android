package com.dskapp.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests only the pure tokenizer so they run on the plain JVM without Compose.
 */
class SyntaxHighlighterTest {

    private fun types(code: String, lang: String): List<Pair<String, TokenType>> =
        SyntaxHighlighter.tokenize(code, lang).map { code.substring(it.start, it.end) to it.type }

    @Test
    fun kotlinKeywordsStringsAndComments() {
        val code = "val x = \"if\" // fun"
        val result = types(code, "kotlin")
        assertEquals(TokenType.KEYWORD, result.first { it.first == "val" }.second)
        assertTrue(result.any { it.first == "\"if\"" && it.second == TokenType.STRING })
        assertTrue(result.any { it.first == "// fun" && it.second == TokenType.COMMENT })
        // The keyword inside the string must not be tokenized.
        assertTrue(result.none { it.first == "if" })
    }

    @Test
    fun pythonDefReturnHexAndComment() {
        val code = "def f(): return 0x1F  # done"
        val result = types(code, "python")
        assertEquals(TokenType.KEYWORD, result.first { it.first == "def" }.second)
        assertEquals(TokenType.KEYWORD, result.first { it.first == "return" }.second)
        assertTrue(result.any { it.first == "0x1F" && it.second == TokenType.NUMBER })
        assertTrue(result.any { it.first == "# done" && it.second == TokenType.COMMENT })
    }

    @Test
    fun sqlKeywordsAreCaseInsensitive() {
        val upper = types("SELECT name FROM users -- all", "sql")
        assertEquals(TokenType.KEYWORD, upper.first { it.first == "SELECT" }.second)
        assertEquals(TokenType.KEYWORD, upper.first { it.first == "FROM" }.second)
        assertTrue(upper.any { it.first == "-- all" && it.second == TokenType.COMMENT })

        val lower = types("select name from users", "sql")
        assertEquals(TokenType.KEYWORD, lower.first { it.first == "select" }.second)
        assertEquals(TokenType.KEYWORD, lower.first { it.first == "from" }.second)
    }

    @Test
    fun jsonKeysValuesAndLiterals() {
        val code = "{\"a\": \"b\", \"n\": 1.5e3, \"t\": true}"
        val result = types(code, "json")
        assertTrue(result.any { it.first == "\"a\"" && it.second == TokenType.KEYWORD })
        assertTrue(result.any { it.first == "\"b\"" && it.second == TokenType.STRING })
        assertTrue(result.any { it.first == "1.5e3" && it.second == TokenType.NUMBER })
        assertTrue(result.any { it.first == "true" && it.second == TokenType.KEYWORD })
    }

    @Test
    fun aliasResolvesAndUnknownLanguageIsGeneric() {
        val alias = types("val x = 1", "kt")
        assertEquals(TokenType.KEYWORD, alias.first { it.first == "val" }.second)

        val unknown = types("val x = 1 // note \"s\"", "zzz")
        assertTrue(unknown.none { it.first == "val" && it.second == TokenType.KEYWORD })
        assertTrue(unknown.any { it.first == "1" && it.second == TokenType.NUMBER })
        assertTrue(unknown.any { it.first == "// note \"s\"" && it.second == TokenType.COMMENT })
    }

    @Test
    fun tokensAreSortedAndNonOverlapping() {
        val sample = buildString {
            repeat(300) { idx ->
                append("val x$idx = \"s$idx\" + 42 // line $idx\n")
            }
        }
        val tokens = SyntaxHighlighter.tokenize(sample, "kotlin")
        assertTrue(tokens.isNotEmpty())
        var prev = -1
        for (t in tokens) {
            assertTrue(t.start >= prev)
            assertTrue(t.end > t.start)
            prev = t.end
        }
    }

    @Test
    fun blockCommentAcrossLinesIsSingleToken() {
        val code = "val a = 1\n/* a\n b */\nval b = 2"
        val result = types(code, "kotlin")
        val comment = result.filter { it.second == TokenType.COMMENT }
        assertEquals(1, comment.size)
        assertEquals("/* a\n b */", comment[0].first)
    }
}
