package com.dskapp.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTranscriptTest {

    private class Ids {
        private var next = 1L
        fun take(): Long = next++
    }

    @Test
    fun appendToEmptyListCreatesOneOpenAssistantBubble() {
        val ids = Ids()
        val result = RemoteTranscript.append(emptyList(), "Hi", thought = false, newId = ids::take)
        assertEquals(1, result.size)
        val message = result[0]
        assertFalse(message.fromUser)
        assertFalse(message.isThought)
        assertTrue(message.streaming)
        assertEquals("Hi", message.text)
    }

    @Test
    fun twoAppendsOfSameKindGrowTheSameBubble() {
        val ids = Ids()
        var list = RemoteTranscript.append(emptyList(), "Hel", thought = false, newId = ids::take)
        val firstId = list[0].id
        list = RemoteTranscript.append(list, "lo", thought = false, newId = ids::take)
        assertEquals(1, list.size)
        assertEquals("Hello", list[0].text)
        assertEquals(firstId, list[0].id)
        assertTrue(list[0].streaming)
    }

    @Test
    fun leadingBlankLinesAreDropped() {
        val ids = Ids()
        var list = RemoteTranscript.append(emptyList(), "\n\n", thought = false, newId = ids::take)
        assertEquals(0, list.size)
        list = RemoteTranscript.append(list, "pong", thought = false, newId = ids::take)
        assertEquals(1, list.size)
        assertEquals("pong", list[0].text)

        val ids2 = Ids()
        val lone = RemoteTranscript.append(emptyList(), "\n\n", thought = false, newId = ids2::take)
        assertEquals(0, lone.size)
    }

    @Test
    fun whitespaceInsideAnAnswerIsKept() {
        val ids = Ids()
        var list = RemoteTranscript.append(emptyList(), "a", thought = false, newId = ids::take)
        list = RemoteTranscript.append(list, "\n\nb", thought = false, newId = ids::take)
        assertEquals(1, list.size)
        assertEquals("a\n\nb", list[0].text)
    }

    @Test
    fun thoughtThenAnswerMakesTwoBubbles() {
        val ids = Ids()
        var list = RemoteTranscript.append(emptyList(), "thinking...", thought = true, newId = ids::take)
        list = RemoteTranscript.append(list, "answer", thought = false, newId = ids::take)
        assertEquals(2, list.size)
        assertEquals("thinking...", list[0].text)
        assertTrue(list[0].isThought)
        assertFalse(list[0].streaming)
        assertEquals("answer", list[1].text)
        assertFalse(list[1].isThought)
        assertTrue(list[1].streaming)
    }

    @Test
    fun answerThenThoughtAlsoSplits() {
        val ids = Ids()
        var list = RemoteTranscript.append(emptyList(), "answer", thought = false, newId = ids::take)
        list = RemoteTranscript.append(list, "hmm", thought = true, newId = ids::take)
        assertEquals(2, list.size)
        assertFalse(list[0].isThought)
        assertFalse(list[0].streaming)
        assertTrue(list[1].isThought)
        assertTrue(list[1].streaming)
        assertEquals("hmm", list[1].text)
    }

    @Test
    fun placeholderTakesKindOfFirstChunk() {
        val ids = Ids()
        var list = RemoteTranscript.userTurn(emptyList(), "hi", placeholder = true, newId = ids::take)
        assertEquals(2, list.size)
        val placeholderId = list[1].id
        list = RemoteTranscript.append(list, "hmm", thought = true, newId = ids::take)
        assertEquals(2, list.size)
        assertEquals(placeholderId, list[1].id)
        assertEquals("hmm", list[1].text)
        assertTrue(list[1].isThought)
        assertTrue(list[1].streaming)
    }

    @Test
    fun appendWithEmptyTextReturnsSameList() {
        val ids = Ids()
        val list = RemoteTranscript.append(emptyList(), "x", thought = false, newId = ids::take)
        val result = RemoteTranscript.append(list, "", thought = false, newId = ids::take)
        assertSame(list, result)
    }

    @Test
    fun userTurnWithPlaceholderAddsAssistantBubble() {
        val ids = Ids()
        val withPlaceholder = RemoteTranscript.userTurn(emptyList(), "hello", placeholder = true, newId = ids::take)
        assertEquals(2, withPlaceholder.size)
        assertTrue(withPlaceholder[0].fromUser)
        assertEquals("hello", withPlaceholder[0].text)
        assertFalse(withPlaceholder[1].fromUser)
        assertEquals("", withPlaceholder[1].text)
        assertTrue(withPlaceholder[1].streaming)

        val ids2 = Ids()
        val withoutPlaceholder = RemoteTranscript.userTurn(emptyList(), "hello", placeholder = false, newId = ids2::take)
        assertEquals(1, withoutPlaceholder.size)
        assertTrue(withoutPlaceholder[0].fromUser)
        assertEquals("hello", withoutPlaceholder[0].text)
    }

    @Test
    fun userTurnClosesWhatWasOpen() {
        val ids = Ids()
        val open = listOf(
            RemoteMessage(id = 1L, fromUser = false, text = "old answer  \n", isThought = false, streaming = true)
        )
        val closedList = RemoteTranscript.userTurn(open, "next", placeholder = false, newId = ids::take)
        assertEquals(2, closedList.size)
        assertFalse(closedList[0].streaming)
        assertEquals("old answer", closedList[0].text)

        val ids2 = Ids()
        val emptyOpen = listOf(
            RemoteMessage(id = 1L, fromUser = false, text = "", isThought = false, streaming = true)
        )
        val removedList = RemoteTranscript.userTurn(emptyOpen, "next", placeholder = false, newId = ids2::take)
        assertEquals(1, removedList.size)
        assertTrue(removedList[0].fromUser)
        assertEquals("next", removedList[0].text)
    }

    @Test
    fun finishTrimsAndClosesAssistantBubblesAndLeavesUsersAlone() {
        val list = listOf(
            RemoteMessage(id = 1L, fromUser = true, text = "  ", isThought = false, streaming = false),
            RemoteMessage(id = 2L, fromUser = false, text = "  answer  ", isThought = false, streaming = true),
            RemoteMessage(id = 3L, fromUser = false, text = "   ", isThought = false, streaming = true),
            RemoteMessage(id = 4L, fromUser = false, text = " \n ", isThought = true, streaming = false)
        )
        val result = RemoteTranscript.finish(list)
        assertEquals(2, result.size)
        assertTrue(result[0].fromUser)
        assertEquals("  ", result[0].text)
        assertFalse(result[1].streaming)
        assertEquals("answer", result[1].text)
    }

    @Test
    fun replayedHistoryProducesFourMessagesInOrder() {
        val ids = Ids()
        var list = RemoteTranscript.userTurn(emptyList(), "q1", placeholder = false, newId = ids::take)
        list = RemoteTranscript.append(list, "a1", thought = false, newId = ids::take)
        list = RemoteTranscript.userTurn(list, "q2", placeholder = false, newId = ids::take)
        list = RemoteTranscript.append(list, "\n\n", thought = false, newId = ids::take)
        list = RemoteTranscript.append(list, "a2", thought = false, newId = ids::take)
        val result = RemoteTranscript.finish(list)

        assertEquals(4, result.size)
        assertTrue(result[0].fromUser)
        assertEquals("q1", result[0].text)
        assertFalse(result[1].fromUser)
        assertEquals("a1", result[1].text)
        assertTrue(result[2].fromUser)
        assertEquals("q2", result[2].text)
        assertFalse(result[3].fromUser)
        assertEquals("a2", result[3].text)
        result.forEach { assertFalse(it.streaming) }
    }

    @Test
    fun chunksGoToLastOpenBubbleOnly() {
        val ids = Ids()
        var list = RemoteTranscript.userTurn(emptyList(), "q", placeholder = true, newId = ids::take)
        list = RemoteTranscript.append(list, "x", thought = false, newId = ids::take)
        assertEquals(2, list.size)
        assertTrue(list[0].fromUser)
        assertEquals("q", list[0].text)
        assertEquals("x", list[1].text)
    }

    @Test
    fun idsAreUniqueAcrossReplayedHistory() {
        val ids = Ids()
        var list = RemoteTranscript.userTurn(emptyList(), "q1", placeholder = false, newId = ids::take)
        list = RemoteTranscript.append(list, "a1", thought = false, newId = ids::take)
        list = RemoteTranscript.userTurn(list, "q2", placeholder = false, newId = ids::take)
        list = RemoteTranscript.append(list, "\n\n", thought = false, newId = ids::take)
        list = RemoteTranscript.append(list, "a2", thought = false, newId = ids::take)
        val result = RemoteTranscript.finish(list)
        assertEquals(result.size, result.map { it.id }.toSet().size)
    }
}
