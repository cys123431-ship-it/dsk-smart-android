package com.dskapp.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCatalogTest {

    private fun meta(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    @Test
    fun nameDecisions() {
        val cases = listOf(
            "Qwen3-VL-Reranker-2B" to false,
            "gemma-4-31B-it:thinking" to true,
            "DeepSeek-V4.1-Flash-1017" to true,
            "GLM-5.3-Flash" to true,
            "Hy-MT2-1.8B" to true,
            "GTE-ModernColBERT-v1" to false,
            "GLM-OCR" to false,
            "Qwen3Guard-Gen-4B" to false,
            "granite-guardian-3.0-2b" to false,
            "snowflake-arctic-embed-l-v2.0" to false,
            "opensearch-neural-sparse-encoding-doc-v3-gte" to false,
            "whisper-large-v3-turbo" to false,
            "Qwen3.8-27B-FP8:h100-256k-thinking" to true,
            "nvidia/NVIDIA-Nemotron-3.5-Super-VL-120B-A12B-NVFP4" to true,
            "sam3" to false,
            "LightOnOCR-3-4B" to false,
            "madlad400-3b-mt" to false,
            "gliner_multi-v2.1" to false,
        )
        for ((id, expected) in cases) {
            assertEquals("isChatModel(\"$id\")", expected, ModelCatalog.isChatModel(id))
        }
    }

    @Test
    fun metadataChatOverridesEmbedName() {
        val entry = ModelEntry("foo-embed", meta("type" to "chat"))
        assertTrue(ModelCatalog.isChatModel(entry.id, entry.meta))
    }

    @Test
    fun metadataEmbeddingTaskIsNotChat() {
        val entry = ModelEntry("plainname", meta("task" to "embedding"))
        assertFalse(ModelCatalog.isChatModel(entry.id, entry.meta))
    }

    @Test
    fun capabilitiesObjectDecides() {
        val m = buildJsonObject {
            put("capabilities", buildJsonObject {
                put("chat", false)
                put("embeddings", true)
            })
        }
        assertFalse(ModelCatalog.isChatModel("x", m))
    }

    @Test
    fun capabilitiesTrueChat() {
        val m = buildJsonObject {
            put("capabilities", buildJsonObject { put("chat", true) })
        }
        assertTrue(ModelCatalog.isChatModel("some-embed-model", m))
    }

    @Test
    fun parseOpenAiShape() {
        val payload = """{"data":[{"id":"a","object":"model"},{"id":"b"}]}"""
        val entries = ModelCatalog.parse(payload)
        assertEquals(listOf("a", "b"), entries.map { it.id })
        assertEquals("model", (entries[0].meta?.get("object") as? JsonPrimitive)?.content)
        assertNull(entries[1].meta?.get("object"))
    }

    @Test
    fun parseBareArray() {
        val entries = ModelCatalog.parse("""[{"id":"x"},{"id":"y"}]""")
        assertEquals(listOf("x", "y"), entries.map { it.id })
    }

    @Test
    fun parseStringElements() {
        val entries = ModelCatalog.parse("""{"data":["a","b"]}""")
        assertEquals(listOf("a", "b"), entries.map { it.id })
        assertTrue(entries.all { it.meta == null })
    }

    @Test
    fun parseSkipsBlankAndMissingIds() {
        val entries = ModelCatalog.parse("""{"data":[{"id":""},{"id":"  "},{},{"id":"ok"}]}""")
        assertEquals(listOf("ok"), entries.map { it.id })
    }

    @Test
    fun parseInvalidJsonThrows() {
        try {
            ModelCatalog.parse("not json")
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("모델 목록 형식을 해석하지 못했습니다.", e.message)
        }
    }

    @Test
    fun listingSortsDistinctsAndCountsHidden() {
        val entries = listOf(
            ModelEntry("Zeta"),
            ModelEntry("alpha"),
            ModelEntry("Zeta"),
            ModelEntry("bge-embed"),
            ModelEntry("gemma-4-31B-it:thinking"),
            ModelEntry("Beta"),
        )
        val listing = ModelCatalog.listing(entries)
        assertEquals(listOf("alpha", "Beta", "bge-embed", "gemma-4-31B-it:thinking", "Zeta"), listing.all)
        assertEquals(listOf("alpha", "Beta", "gemma-4-31B-it:thinking", "Zeta"), listing.chat)
        assertEquals(1, listing.hiddenCount)
    }

    @Test
    fun outputLimitFromExamples() {
        val cases = listOf(
            "max_completion_tokens (8192) exceeds model max_output_tokens (4096)" to 4096,
            "max_tokens is too large: 8192. This model supports at most 4096 completion tokens, whereas you provided 8192." to 4096,
            "max_tokens: 8192 > 2048, which is the maximum allowed number of output tokens for x" to 2048,
            "This model's maximum context length is 32768 tokens. However, you requested 40000 tokens (31808 in the messages, 8192 in the completion)." to null,
            "max_tokens must be at least 1" to null,
            "Model 'x' not found" to null,
        )
        for ((message, expected) in cases) {
            assertEquals("outputLimitFrom(\"$message\")", expected, ModelCatalog.outputLimitFrom(message, 8192))
        }
    }

    @Test
    fun outputLimitPicksLargestUnderRequested() {
        val msg = "max_tokens 8192 exceeds max_output_tokens 4096 and 2048"
        assertEquals(4096, ModelCatalog.outputLimitFrom(msg, 8192))
    }

    @Test
    fun outputLimitIgnoresSmallNumbers() {
        val msg = "max_tokens: 100 > 50, max_output_tokens allows at most 1024"
        assertEquals(1024, ModelCatalog.outputLimitFrom(msg, 8192))
    }

    @Test
    fun outputLimitIgnoresDecimals() {
        val msg = "model version 3.5 max_tokens exceeds max_output_tokens 2048"
        assertEquals(2048, ModelCatalog.outputLimitFrom(msg, 8192))
    }
}
