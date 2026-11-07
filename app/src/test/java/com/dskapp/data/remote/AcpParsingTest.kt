package com.dskapp.data.remote

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the ACP payloads goose sends; no network involved. */
class AcpParsingTest {

    private fun obj(text: String): JsonObject = AcpJson.obj(text)!!

    // ---- server URL normalisation ----

    @Test
    fun bareHostBecomesWebSocketAcpUrl() {
        assertEquals("ws://100.64.0.5:3284/acp", AcpJson.normalizeServerUrl("100.64.0.5:3284"))
    }

    @Test
    fun httpSchemeIsConvertedToWebSocket() {
        assertEquals("ws://127.0.0.1:3284/acp", AcpJson.normalizeServerUrl("http://127.0.0.1:3284"))
        assertEquals("wss://pc.example.ts.net/acp", AcpJson.normalizeServerUrl("https://pc.example.ts.net"))
    }

    @Test
    fun existingPathIsKeptAndTrailingSlashTrimmed() {
        assertEquals("ws://h:3284/acp", AcpJson.normalizeServerUrl(" ws://h:3284/acp/ "))
        assertEquals("wss://h/custom", AcpJson.normalizeServerUrl("wss://h/custom"))
    }

    @Test
    fun blankUrlStaysBlank() {
        assertEquals("", AcpJson.normalizeServerUrl("   "))
    }

    // ---- session/update ----

    @Test
    fun messageChunkTextIsConcatenated() {
        val update = AcpUpdate.parse(
            obj(
                """{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk",
                   "content":[{"type":"text","text":"안녕"},{"type":"text","text":"하세요"}]}}"""
            )
        )
        assertNotNull(update)
        assertEquals("agent_message_chunk", update!!.kind)
        assertEquals("안녕하세요", update.text)
    }

    @Test
    fun thoughtChunkIsSeparateFromReply() {
        val update = AcpUpdate.parse(
            obj("""{"update":{"sessionUpdate":"agent_thought_chunk","content":[{"type":"text","text":"생각"}]}}""")
        )
        assertEquals("agent_thought_chunk", update!!.kind)
        assertEquals("생각", update.text)
    }

    @Test
    fun unknownUpdateKindIsStillSurfaced() {
        val update = AcpUpdate.parse(obj("""{"update":{"sessionUpdate":"available_commands_update"}}"""))
        assertEquals("available_commands_update", update!!.kind)
        assertEquals("", update.text)
    }

    @Test
    fun updateWithoutKindIsIgnored() {
        assertNull(AcpUpdate.parse(obj("""{"update":{"content":[]}}""")))
        assertNull(AcpUpdate.parse(null))
    }

    @Test
    fun toolCallCarriesIdStatusAndKind() {
        val update = AcpUpdate.parse(
            obj(
                """{"update":{"sessionUpdate":"tool_call","toolCallId":"t1","title":"파일 읽기",
                   "status":"in_progress","kind":"read"}}"""
            )
        )
        val tool = update!!.toolCall!!
        assertEquals("t1", tool.toolCallId)
        assertEquals("파일 읽기", tool.title)
        assertEquals("in_progress", tool.status)
        assertFalse(tool.isDone)
        assertEquals("read", tool.kind)
    }

    @Test
    fun toolCallUpdateMarksCompletion() {
        val update = AcpUpdate.parse(
            obj("""{"update":{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed"}}""")
        )
        val tool = update!!.toolCall!!
        assertTrue(tool.isDone)
        // Missing title falls back to a readable placeholder instead of an empty card.
        assertEquals("도구", tool.title)
    }

    @Test
    fun usageUpdateIsParsed() {
        val update = AcpUpdate.parse(
            obj("""{"update":{"sessionUpdate":"usage_update","usage":{"used":4976,"size":200000}}}""")
        )
        val usage = update!!.usage!!
        assertEquals(4976, usage.used)
        assertEquals(200000, usage.size)
        assertTrue(usage.fraction in 0f..1f)
    }

    @Test
    fun usageWithZeroSizeHasZeroFraction() {
        assertEquals(0f, AcpUsage(10, 0).fraction, 0f)
    }

    // ---- session/request_permission ----

    @Test
    fun permissionRequestParsesOptions() {
        val request = AcpPermissionRequest.parse(
            "7",
            obj(
                """{"sessionId":"s1","toolCall":{"toolCallId":"t1","title":"셸 실행"},
                   "options":[{"optionId":"allow","name":"허용","kind":"allow_once"},
                              {"optionId":"reject","name":"거부","kind":"reject_once"}]}"""
            )
        )
        assertNotNull(request)
        assertEquals("7", request!!.requestId)
        assertEquals("셸 실행", request.title)
        assertEquals(listOf("allow" to "허용", "reject" to "거부"), request.options)
    }

    @Test
    fun permissionRequestWithoutToolCallUsesFallbackTitle() {
        val request = AcpPermissionRequest.parse("1", obj("""{"sessionId":"s1"}"""))
        assertEquals("도구 실행 승인", request!!.title)
        assertTrue(request.options.isEmpty())
    }

    // ---- session/new / session/load ----

    @Test
    fun sessionResultReadsModes() {
        val session = AcpSession.from(
            obj(
                """{"sessionId":"20261107_5",
                   "modes":{"currentModeId":"approve","availableModes":[
                     {"id":"auto","name":"Auto","description":"자동"},
                     {"id":"approve","name":"Approve"}]}}"""
            )
        )
        assertEquals("20261107_5", session.sessionId)
        assertEquals("approve", session.currentMode)
        assertEquals(listOf("auto", "approve"), session.modes.map { it.id })
        assertEquals("Approve", session.modes[1].name)
        assertEquals("", session.modes[1].description)
    }

    @Test
    fun sessionResultWithoutModesIsValid() {
        val session = AcpSession.from(obj("""{"sessionId":"x"}"""))
        assertEquals("x", session.sessionId)
        assertTrue(session.modes.isEmpty())
        assertEquals("", session.currentMode)
    }

    @Test
    fun summarySubtitleUsesFolderAndTime() {
        val summary = AcpSessionSummary(
            sessionId = "1",
            title = "t",
            updatedAt = "2026-11-07T14:30:00+09:00",
            cwd = "C:\\Users\\me\\work\\tradingbot-1",
            messageCount = 12,
            providerId = "custom",
            modelId = "m"
        )
        assertEquals("tradingbot-1 · 2026-11-07 14:30", summary.subtitle)
    }
}
