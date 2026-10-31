package com.dskapp.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun headingCarriesLevelAndText() {
        val blocks = MarkdownParser.parse("## 제목")
        assertEquals(1, blocks.size)
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("제목", heading.text)
    }

    @Test
    fun unorderedListIsOneBlock() {
        val blocks = MarkdownParser.parse("- 하나\n- 둘\n- 셋")
        assertEquals(1, blocks.size)
        val bullet = blocks[0] as MdBlock.Bullet
        assertEquals(listOf("하나", "둘", "셋"), bullet.items)
        assertEquals(false, bullet.ordered)
    }

    @Test
    fun orderedListIsMarkedOrdered() {
        val blocks = MarkdownParser.parse("1. 하나\n2. 둘")
        assertEquals(1, blocks.size)
        val bullet = blocks[0] as MdBlock.Bullet
        assertEquals(listOf("하나", "둘"), bullet.items)
        assertTrue(bullet.ordered)
    }

    @Test
    fun fencedCodeKeepsLanguageAndBody() {
        val blocks = MarkdownParser.parse("```kotlin\nval a = 1\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1", code.code)
    }

    @Test
    fun pipeTableHasHeaderAndRows() {
        val blocks = MarkdownParser.parse("| 이름 | 값 |\n| --- | --- |\n| 가 | 1 |\n| 나 | 2 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("이름", "값"), table.header)
        assertEquals(listOf(listOf("가", "1"), listOf("나", "2")), table.rows)
    }

    @Test
    fun horizontalRule() {
        val blocks = MarkdownParser.parse("---")
        assertEquals(listOf(MdBlock.Rule), blocks)
    }

    @Test
    fun blankInputHasNoBlocks() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun stripInlineRemovesBoldMarkers() {
        assertEquals("굵게", MarkdownParser.stripInline("**굵게**"))
    }
}
