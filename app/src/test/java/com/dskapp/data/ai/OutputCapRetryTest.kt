package com.dskapp.data.ai

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real answers Superlinked gave for gemma-4-31B-it variants (2026-11-13), replayed by a
 * fake server: the app must retry within the model's output cap, and explain a model whose
 * backend is down instead of showing the raw 503.
 */
class OutputCapRetryTest {

    private val capRejection =
        """{"error":{"message":"max_completion_tokens (8192) exceeds model max_output_tokens (4096)"}}"""
    private val routeDown = """{"error":{"message":"Generation route is unavailable for this deployment"}}"""

    private class Fake(val replies: (maxTokens: Int) -> Pair<Int, String>) {
        val asked = mutableListOf<Int>()
        val unavailable = mutableListOf<String>()
        val worked = mutableListOf<String>()

        fun client(model: String = "google/gemma-4-31B-it:no-spec") = OpenAiCompatibleClient(
            client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val max = Regex(""""max_tokens":(\d+)""").find(buffer.readUtf8())!!.groupValues[1].toInt()
                asked += max
                val (code, body) = replies(max)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody(
                        (if (body.startsWith("data:")) "text/event-stream" else "application/json").toMediaType()
                    ))
                    .build()
            }.build(),
            configProvider = { ProviderConfig("https://api.example.com/v1", "test-key", model) },
            modelHealth = object : OpenAiCompatibleClient.ModelHealth {
                override fun unavailable(model: String) { unavailable += model }
                override fun worked(model: String) { worked += model }
            }
        )
    }

    private val okJson = """{"choices":[{"message":{"role":"assistant","content":"안녕하세요"}}]}"""
    private val okStream =
        "data: {\"choices\":[{\"delta\":{\"content\":\"안녕\"}}]}\n\ndata: [DONE]\n\n"

    @Test
    fun completeRetriesWithinTheModelsCapAndRemembersIt() = runBlocking {
        val fake = Fake { max -> if (max > 4096) 400 to capRejection else 200 to okJson }
        val client = fake.client()

        val first = client.complete("sys", listOf(AiMessage(AiRole.USER, "안녕")))
        assertTrue("was $first", first is AiResult.Success)
        assertEquals(listOf(8192, 4096), fake.asked)

        // The cap is remembered: the next request asks for 4096 straight away.
        client.complete("sys", listOf(AiMessage(AiRole.USER, "또")))
        assertEquals(listOf(8192, 4096, 4096), fake.asked)
        assertTrue(fake.worked.isNotEmpty())
    }

    @Test
    fun streamRetriesWithinTheCapWithoutShowingTheRejection() = runBlocking {
        val fake = Fake { max -> if (max > 4096) 400 to capRejection else 200 to okStream }
        val errors = mutableListOf<AiError>()
        val text = StringBuilder()
        fake.client().stream(
            systemPrompt = "sys",
            messages = listOf(AiMessage(AiRole.USER, "안녕")),
            onText = { text.append(it) },
            onError = { errors += it }
        ).collect()

        assertEquals(emptyList<AiError>(), errors)
        assertEquals("안녕", text.toString())
        assertEquals(listOf(8192, 4096), fake.asked)
        assertEquals(listOf("google/gemma-4-31B-it:no-spec"), fake.worked)
    }

    @Test
    fun routeUnavailableNamesTheModelAndFlagsIt() = runBlocking {
        val model = "google/gemma-4-31B-it:thinking"
        val fake = Fake { 503 to routeDown }
        val errors = mutableListOf<AiError>()
        fake.client(model).stream(
            systemPrompt = "sys",
            messages = listOf(AiMessage(AiRole.USER, "안녕")),
            onText = {},
            onError = { errors += it }
        ).collect()

        assertEquals(listOf<AiError>(AiError.ModelUnavailable(model, serverSide = true)), errors)
        assertTrue(errors.single().userMessage.contains(model))
        assertTrue(errors.single().userMessage.contains("다른 모델"))
        assertEquals(listOf(model), fake.unavailable)
        // A plain outage is not retried with a smaller budget.
        assertEquals(listOf(8192), fake.asked)
    }

    @Test
    fun unrelated400IsNotRetried() = runBlocking {
        val fake = Fake { 400 to """{"error":{"message":"messages must not be empty"}}""" }
        val result = fake.client().complete("sys", listOf(AiMessage(AiRole.USER, "x")))
        assertTrue(result is AiResult.Failure)
        assertEquals(listOf(8192), fake.asked)
    }
}
