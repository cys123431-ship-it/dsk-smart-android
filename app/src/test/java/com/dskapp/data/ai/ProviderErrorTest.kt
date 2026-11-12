package com.dskapp.data.ai

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How provider failures are classified. The first case is the real answer Superlinked gave
 * when it dropped Qwen3.8-Max-VL-Thinking (shortened here; the real one listed 100+ models).
 */
class ProviderErrorTest {

    private val client = OpenAiCompatibleClient(
        client = OkHttpClient(),
        configProvider = { ProviderConfig(baseUrl = "https://api.example.com/v1", apiKey = "test-key", model = "m") }
    )

    @Test
    fun removedModelBecomesModelUnavailableWithItsName() {
        val payload = """{"error":{"message":"Model 'Qwen/Qwen3.8-Max-VL-Thinking' not found. Available models for this provider: BAAI/bge-m3, deepseek-ai/DeepSeek-V4.1-Flash-1017, google/gemma-4-31B-it"}}"""
        val error = client.parseError(404, payload)
        assertTrue("was $error", error is AiError.ModelUnavailable)
        assertEquals("Qwen/Qwen3.8-Max-VL-Thinking", (error as AiError.ModelUnavailable).model)
        // The message names the model and says what to do, without the provider's model list.
        assertTrue(error.userMessage.contains("Qwen/Qwen3.8-Max-VL-Thinking"))
        assertTrue(error.userMessage.contains("모델 목록"))
        assertTrue(!error.userMessage.contains("BAAI"))
    }

    @Test
    fun openAiStyleModelNotFoundIsRecognised() {
        val payload = """{"error":{"message":"The model `gpt-x` does not exist","type":"invalid_request_error","code":"model_not_found"}}"""
        val error = client.parseError(404, payload)
        assertEquals(AiError.ModelUnavailable("gpt-x"), error)
    }

    @Test
    fun plain404WithoutModelWordingStaysAProviderError() {
        // A wrong base URL also answers 404; that is not a missing model.
        val error = client.parseError(404, """{"error":{"message":"Not Found"}}""")
        assertTrue("was $error", error is AiError.Provider)
    }

    @Test
    fun serverErrorsAreNotMistakenForMissingModels() {
        val error = client.parseError(500, """{"error":{"message":"model worker not found in pool"}}""")
        assertTrue("was $error", error is AiError.Provider)
    }

    @Test
    fun longProviderMessagesAreCutInTheBanner() {
        val error = client.parseError(503, """{"error":{"message":"${"x".repeat(1000)}"}}""")
        assertTrue(error.userMessage.length < 260)
    }

    @Test
    fun authAndRateLimitKeepTheirOwnTypes() {
        assertTrue(client.parseError(401, "{}") is AiError.Unauthorized)
        assertTrue(client.parseError(429, "{}") is AiError.RateLimited)
    }
}
