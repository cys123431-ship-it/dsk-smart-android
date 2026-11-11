package com.dskapp.data.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of the ACP payloads goose sends; no network involved. */
class AcpParsingTest {

    private fun obj(text: String): JsonObject = AcpJson.obj(text)!!

    private fun element(text: String): JsonElement =
        kotlinx.serialization.json.Json.parseToJsonElement(text)

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
    fun mixedCaseSchemeIsConvertedToWebSocket() {
        assertEquals("wss://pc.example.ts.net/acp", AcpJson.normalizeServerUrl("Https://pc.example.ts.net"))
        assertEquals("ws://127.0.0.1:3284/acp", AcpJson.normalizeServerUrl("HTTP://127.0.0.1:3284"))
        assertEquals("ws://100.64.0.5:3284/acp", AcpJson.normalizeServerUrl("WS://100.64.0.5:3284"))
        assertEquals("wss://pc.example.ts.net/acp", AcpJson.normalizeServerUrl("WSS://pc.example.ts.net"))
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
    fun realMessageChunkHasSingleObjectContent() {
        val update = AcpUpdate.parse(
            obj(
                """{"sessionId":"20261110_1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"\n\npong"},"messageId":"chatcmpl-01a1","_meta":{"goose":{"created":1794275854}}}}"""
            )
        )
        assertEquals("agent_message_chunk", update!!.kind)
        assertEquals("\n\npong", update.text)
        assertEquals("chatcmpl-01a1", update.messageId)
    }

    @Test
    fun realUserMessageChunkIsParsed() {
        val update = AcpUpdate.parse(
            obj(
                """{"sessionId":"20261110_2","update":{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"Use your shell tool"},"messageId":"msg_7a45"}}"""
            )
        )
        assertEquals("user_message_chunk", update!!.kind)
        assertEquals("Use your shell tool", update.text)
        assertEquals("msg_7a45", update.messageId)
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
    fun realToolCallFromGoose() {
        val update = AcpUpdate.parse(
            obj(
                """{"sessionId":"20261110_2","update":{"sessionUpdate":"tool_call","toolCallId":"call_b118","title":"shell · echo dsk-probe","rawInput":{"command":"echo dsk-probe"},"_meta":{"goose":{"toolCall":{"toolName":"shell","extensionName":"developer"}}}}}"""
            )
        )
        val tool = update!!.toolCall!!
        assertEquals("call_b118", tool.toolCallId)
        assertEquals("shell · echo dsk-probe", tool.title)
        assertEquals("pending", tool.status)
        assertNull(tool.kind)
        assertEquals("echo dsk-probe", tool.detail)
        assertFalse(tool.isDone)
    }

    @Test
    fun realToolCallUpdateFromGoose() {
        val update = AcpUpdate.parse(
            obj(
                """{"sessionId":"20261110_2","update":{"sessionUpdate":"tool_call_update","toolCallId":"call_b118","status":"failed","content":[{"type":"content","content":{"type":"text","text":"The user has declined to run this tool."}}]}}"""
            )
        )
        val tool = update!!.toolCall!!
        assertEquals("call_b118", tool.toolCallId)
        assertEquals("", tool.title)
        assertEquals("도구", tool.displayTitle)
        assertEquals("failed", tool.status)
        assertTrue(tool.isDone)
        assertTrue(tool.detail.startsWith("The user has declined"))
    }

    @Test
    fun mergedWithKeepsTitleAndTakesNewStatus() {
        val first = AcpUpdate.parse(
            obj(
                """{"update":{"sessionUpdate":"tool_call","toolCallId":"call_b118","title":"shell · echo dsk-probe","rawInput":{"command":"echo dsk-probe"}}}"""
            )
        )!!.toolCall!!
        val second = AcpUpdate.parse(
            obj(
                """{"update":{"sessionUpdate":"tool_call_update","toolCallId":"call_b118","status":"failed","content":[{"type":"content","content":{"type":"text","text":"The user has declined to run this tool."}}]}}"""
            )
        )!!.toolCall!!
        val merged = first.mergedWith(second)
        assertEquals("shell · echo dsk-probe", merged.title)
        assertEquals("failed", merged.status)
        // A later non-blank detail (here the result text) wins, like every other field.
        assertEquals("The user has declined to run this tool.", merged.detail)

        // A progress frame without content keeps the earlier detail.
        val progress = first.mergedWith(AcpToolCall("call_b118", "", "in_progress", null, ""))
        assertEquals("echo dsk-probe", progress.detail)
        assertEquals("in_progress", progress.status)

        val retitled = first.mergedWith(
            AcpToolCall("call_b118", "다시 실행", "", null, "")
        )
        assertEquals("다시 실행", retitled.title)
        assertEquals("pending", retitled.status)
    }

    @Test
    fun toolCallUpdateMarksCompletion() {
        val update = AcpUpdate.parse(
            obj("""{"update":{"sessionUpdate":"tool_call_update","toolCallId":"t1","status":"completed"}}""")
        )
        val tool = update!!.toolCall!!
        assertTrue(tool.isDone)
        // Missing title stays empty on the model; the UI falls back via displayTitle.
        assertEquals("", tool.title)
        assertEquals("도구", tool.displayTitle)
    }

    @Test
    fun nestedUsageUpdateIsParsed() {
        val update = AcpUpdate.parse(
            obj("""{"update":{"sessionUpdate":"usage_update","usage":{"used":4976,"size":200000}}}""")
        )
        val usage = update!!.usage!!
        assertEquals(4976, usage.used)
        assertEquals(200000, usage.size)
        assertTrue(usage.fraction in 0f..1f)
    }

    @Test
    fun realTopLevelUsageUpdateIsParsed() {
        val update = AcpUpdate.parse(
            obj("""{"sessionId":"20261110_1","update":{"sessionUpdate":"usage_update","used":4989,"size":1048576}}""")
        )
        val usage = update!!.usage!!
        assertEquals(4989, usage.used)
        assertEquals(1048576, usage.size)
        assertTrue(usage.fraction in 0f..1f)
    }

    @Test
    fun usageWithZeroSizeHasZeroFraction() {
        assertEquals(0f, AcpUsage(10, 0).fraction, 0f)
    }

    @Test
    fun currentModeUpdateIsParsed() {
        val update = AcpUpdate.parse(
            obj("""{"sessionId":"20261110_2","update":{"sessionUpdate":"current_mode_update","currentModeId":"approve"}}""")
        )
        assertEquals("current_mode_update", update!!.kind)
        assertEquals("approve", update.text)
    }

    @Test
    fun sessionInfoUpdateWithAndWithoutTitle() {
        val titled = AcpUpdate.parse(
            obj(
                """{"sessionId":"20261110_1","update":{"sessionUpdate":"session_info_update","title":"Pong reply","updatedAt":"2026-11-10T01:57:34+00:00","_meta":{"messageCount":1,"userSetName":false}}}"""
            )
        )!!
        assertEquals("session_info_update", titled.kind)
        assertEquals("Pong reply", titled.text)

        val untitled = AcpUpdate.parse(
            obj("""{"sessionId":"20261110_1","update":{"sessionUpdate":"session_info_update","_meta":{"goose":{"activeRunId":null}}}}""")
        )!!
        assertEquals("", untitled.text)
    }

    // ---- session/request_permission ----

    @Test
    fun permissionRequestParsesOptions() {
        val request = AcpPermissionRequest.parse(
            JsonPrimitive("7"),
            obj(
                """{"sessionId":"s1","toolCall":{"toolCallId":"t1","title":"셸 실행"},
                   "options":[{"optionId":"allow","name":"허용","kind":"allow_once"},
                              {"optionId":"reject","name":"거부","kind":"reject_once"}]}"""
            )
        )
        assertNotNull(request)
        assertEquals("7", request!!.requestId.content)
        assertEquals("셸 실행", request.title)
        assertEquals(listOf("allow", "reject"), request.options.map { it.id })
        assertEquals(listOf("허용", "거부"), request.options.map { it.name })
        assertEquals("allow", request.allowOnceId)
        assertEquals("reject", request.rejectOnceId)
    }

    @Test
    fun realPermissionRequestFromGoose() {
        val request = AcpPermissionRequest.parse(
            JsonPrimitive("bcaa2b70-e63d-4781-b80c-82b8fcc40aaa"),
            obj(
                """{"sessionId":"20261110_2","toolCall":{"toolCallId":"call_b118","kind":"other","status":"pending","title":"shell · echo dsk-probe","rawInput":{"command":"echo dsk-probe"}},"options":[{"optionId":"allow_always","name":"allow_always","kind":"allow_always"},{"optionId":"allow_once","name":"allow_once","kind":"allow_once"},{"optionId":"reject_once","name":"reject_once","kind":"reject_once"},{"optionId":"reject_always","name":"reject_always","kind":"reject_always"}]}"""
            )
        )
        assertNotNull(request)
        assertEquals(4, request!!.options.size)
        assertEquals("allow_once", request.allowOnceId)
        assertEquals("reject_once", request.rejectOnceId)
        assertEquals("echo dsk-probe", request.detail)
        assertEquals("shell · echo dsk-probe", request.title)
        assertEquals("20261110_2", request.sessionId)
    }

    @Test
    fun permissionRequestWithoutToolCallUsesFallbackTitle() {
        val request = AcpPermissionRequest.parse(JsonPrimitive("1"), obj("""{"sessionId":"s1"}"""))
        assertEquals("도구 실행 승인", request!!.title)
        assertTrue(request.options.isEmpty())
        assertNull(request.allowOnceId)
        assertNull(request.rejectOnceId)
    }

    @Test
    fun permissionRequestOnlyAlwaysAllowHasNoAllowOnce() {
        val request = AcpPermissionRequest.parse(
            JsonPrimitive("9"),
            obj(
                """{"sessionId":"s1","toolCall":{"toolCallId":"t1","title":"셸 실행"},
                   "options":[{"optionId":"allow_always","name":"allow_always","kind":"allow_always"},
                              {"optionId":"reject_always","name":"reject_always","kind":"reject_always"}]}"""
            )
        )
        assertNotNull(request)
        assertNull(request!!.allowOnceId)
        assertNull(request.rejectOnceId)
    }

    @Test
    fun numericPermissionRequestIdIsKeptAsNumber() {
        val request = AcpPermissionRequest.parse(
            JsonPrimitive(7),
            obj("""{"sessionId":"s1","toolCall":{"title":"셸 실행"},"options":[]}""")
        )
        assertNotNull(request)
        assertFalse(request!!.requestId.isString)
        assertEquals("7", request.requestId.content)
    }

    @Test
    fun permissionOutcomeMatchesGooseShape() {
        assertEquals(
            """{"outcome":{"outcome":"selected","optionId":"allow_once"}}""",
            AcpPermissionOutcome.selected("allow_once").toString()
        )
        assertEquals(
            """{"outcome":{"outcome":"cancelled"}}""",
            AcpPermissionOutcome.cancelled().toString()
        )
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
    fun sessionLoadResultUsesFallbackSessionId() {
        val session = AcpSession.from(
            obj(
                """{"modes":{"currentModeId":"approve","availableModes":[{"id":"auto","name":"auto","description":"Automatically approve tool calls"},{"id":"approve","name":"approve","description":"Ask before every tool call"}]},"configOptions":[]}"""
            ),
            fallbackSessionId = "20261110_2"
        )
        assertEquals("20261110_2", session.sessionId)
        assertEquals("approve", session.currentMode)
    }

    @Test
    fun sessionNewResultKeepsItsOwnIdOverFallback() {
        val session = AcpSession.from(
            obj(
                """{"sessionId":"20261110_1","modes":{"currentModeId":"auto","availableModes":[{"id":"auto","name":"auto","description":"Automatically approve tool calls"},{"id":"approve","name":"approve","description":"Ask before every tool call"},{"id":"smart_approve","name":"smart_approve","description":"Ask only for sensitive tool calls"},{"id":"chat","name":"chat","description":"Chat only, no tool calls"}]},"configOptions":[]}"""
            ),
            fallbackSessionId = "ignored"
        )
        assertEquals("20261110_1", session.sessionId)
        assertEquals("auto", session.currentMode)
        assertEquals(4, session.modes.size)
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

    // ---- content blocks ----

    @Test
    fun blocksParseSingleObjectArrayAndNull() {
        val single = AcpBlocks.parse(element("""{"type":"text","text":"hi"}"""))
        assertEquals(listOf("hi"), single.map { it.text })
        assertFalse(single[0].isThought)

        val array = AcpBlocks.parse(
            element("""[{"type":"text","text":"a"},{"type":"text","text":"b"}]""")
        )
        assertEquals(listOf("a", "b"), array.map { it.text })

        assertTrue(AcpBlocks.parse(null).isEmpty())
        assertTrue(AcpBlocks.text(null).isEmpty())
    }

    @Test
    fun blocksParseToolResultWrapper() {
        val blocks = AcpBlocks.parse(
            element("""{"type":"content","content":{"type":"text","text":"inside"}}""")
        )
        assertEquals(listOf("inside"), blocks.map { it.text })

        // A wrapper may hold several blocks; none of them may be dropped.
        val several = AcpBlocks.parse(
            element("""[{"type":"content","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]},{"type":"text","text":"c"}]""")
        )
        assertEquals(listOf("a", "b", "c"), several.map { it.text })
    }

    @Test
    fun thinkingBlockIsMarked() {
        val blocks = AcpBlocks.parse(element("""{"type":"thinking","thinking":"deep"}"""))
        assertEquals(1, blocks.size)
        assertTrue(blocks[0].isThought)
        assertEquals("deep", blocks[0].text)
    }

    @Test
    fun unknownBlockTypeIsSkipped() {
        val blocks = AcpBlocks.parse(
            element("""{"type":"mystery","text":"x"}""")
        )
        assertTrue(blocks.isEmpty())
    }
}
