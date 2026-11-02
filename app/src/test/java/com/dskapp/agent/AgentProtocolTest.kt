package com.dskapp.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {

    // ---- parse ----

    @Test
    fun parsePlainJson() {
        val result = AgentProtocol.parse("""{"action":"tap","target":3}""")
        assertTrue(result.isSuccess)
        val action = result.getOrThrow()
        assertEquals("tap", action.action)
        assertEquals(3, action.target)
    }

    @Test
    fun parseJsonInsideFencesWithSurroundingText() {
        val raw = """
            Sure, here is the next step:
            ```json
            {"action":"scroll","direction":"up","reason":"화면을 올립니다."}
            ```
            That should do it.
        """.trimIndent()
        val action = AgentProtocol.parse(raw).getOrThrow()
        assertEquals("scroll", action.action)
        assertEquals("up", action.direction)
    }

    @Test
    fun parseBracesInsideStringValue() {
        val raw = """{"action":"type","target":1,"text":"a{b}c"}"""
        val action = AgentProtocol.parse(raw).getOrThrow()
        assertEquals("type", action.action)
        assertEquals("a{b}c", action.text)
    }

    @Test
    fun parseMapsSynonymClickToTap() {
        val action = AgentProtocol.parse("""{"action":"Click","target":2}""").getOrThrow()
        assertEquals("tap", action.action)
    }

    @Test
    fun parseGarbageFails() {
        val result = AgentProtocol.parse("행동을 찾을 수 없는 응답입니다")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    // ---- validate ----

    @Test
    fun validateUnknownAction() {
        val message = AgentProtocol.validate(AgentAction(action = "dance"), elementCount = 5)
        assertEquals("알 수 없는 행동: dance", message)
    }

    @Test
    fun validateTapWithoutTarget() {
        val message = AgentProtocol.validate(AgentAction(action = "tap"), elementCount = 5)
        assertEquals("대상 번호가 화면 목록에 없습니다.", message)
    }

    @Test
    fun validateTargetOutOfRange() {
        val message = AgentProtocol.validate(AgentAction(action = "tap", target = 7), elementCount = 5)
        assertEquals("대상 번호가 화면 목록에 없습니다.", message)
    }

    @Test
    fun validateTypeWithoutText() {
        val message = AgentProtocol.validate(AgentAction(action = "type", target = 1, text = "  "), elementCount = 5)
        assertEquals("입력할 글이 없습니다.", message)
    }

    @Test
    fun validateGoodTapReturnsNull() {
        assertNull(AgentProtocol.validate(AgentAction(action = "tap", target = 0), elementCount = 5))
    }

    // ---- allowedAt ----

    @Test
    fun readLevelBlocksNavigationAndControl() {
        assertFalse(AgentProtocol.allowedAt(AgentAction(action = "open_app"), AgentLevel.READ))
        assertFalse(AgentProtocol.allowedAt(AgentAction(action = "tap", target = 0), AgentLevel.READ))
        assertTrue(AgentProtocol.allowedAt(AgentAction(action = "done"), AgentLevel.READ))
    }

    @Test
    fun navigateLevelAllowsScrollButNotType() {
        assertTrue(AgentProtocol.allowedAt(AgentAction(action = "scroll"), AgentLevel.NAVIGATE))
        assertFalse(AgentProtocol.allowedAt(AgentAction(action = "type", target = 0, text = "안녕"), AgentLevel.NAVIGATE))
    }

    @Test
    fun controlLevelAllowsTap() {
        assertTrue(AgentProtocol.allowedAt(AgentAction(action = "tap", target = 0), AgentLevel.CONTROL))
    }

    // ---- needsConfirmation ----

    @Test
    fun confirmOnRiskyLabel() {
        assertTrue(AgentProtocol.needsConfirmation(AgentAction(action = "tap", target = 1), "전송"))
    }

    @Test
    fun noConfirmOnHarmlessLabel() {
        assertFalse(AgentProtocol.needsConfirmation(AgentAction(action = "tap", target = 1), "검색"))
    }

    @Test
    fun confirmWhenRiskyFlagSet() {
        assertTrue(AgentProtocol.needsConfirmation(AgentAction(action = "back", risky = true), null))
    }

    @Test
    fun confirmWhenTypingIntoPasswordField() {
        assertTrue(AgentProtocol.needsConfirmation(AgentAction(action = "type", target = 2, text = "abc"), "비밀번호"))
    }

    // ---- isBlockedPackage ----

    @Test
    fun blocksBankPackage() {
        assertTrue(AgentProtocol.isBlockedPackage("com.kbstar.kbbank", blocked = emptySet(), allowed = emptySet()))
    }

    @Test
    fun blocksTossPackage() {
        assertTrue(AgentProtocol.isBlockedPackage("viva.republica.toss", blocked = emptySet(), allowed = emptySet()))
    }

    @Test
    fun messengerIsNotBlocked() {
        assertFalse(AgentProtocol.isBlockedPackage("com.kakao.talk", blocked = emptySet(), allowed = emptySet()))
    }

    @Test
    fun allowListedBankIsNotBlocked() {
        assertFalse(
            AgentProtocol.isBlockedPackage(
                "com.kbstar.kbbank",
                blocked = emptySet(),
                allowed = setOf("com.kbstar.kbbank")
            )
        )
    }

    @Test
    fun explicitlyBlockedPackageIsBlocked() {
        assertTrue(
            AgentProtocol.isBlockedPackage(
                "com.example.x",
                blocked = setOf("com.example.x"),
                allowed = emptySet()
            )
        )
    }

    // ---- stepMessage ----

    @Test
    fun stepMessageContainsGoalStepAndEmptyHistoryMarker() {
        val message = AgentProtocol.stepMessage(
            goal = "카카오톡에서 엄마에게 안부 보내기",
            level = AgentLevel.CONTROL,
            step = 1,
            maxSteps = 25,
            history = emptyList(),
            screen = "0: 카카오톡\n1: 검색"
        )
        assertTrue(message.contains("카카오톡에서 엄마에게 안부 보내기"))
        assertTrue(message.contains("1/25"))
        assertTrue(message.contains("- 없음"))
    }
}
