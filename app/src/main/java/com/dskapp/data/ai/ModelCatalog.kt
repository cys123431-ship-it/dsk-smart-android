package com.dskapp.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One entry of GET /models: the id plus whatever extra fields the provider sent. */
data class ModelEntry(val id: String, val meta: JsonObject? = null)

/** What the app keeps from GET /models. */
data class ModelListing(
    val chat: List<String>, // chat-capable ids, sorted case-insensitively, distinct
    val all: List<String>,  // every id the server listed, sorted, distinct
) {
    val hiddenCount: Int get() = all.size - chat.size
}

/** Parses `/models` payloads and decides which models can chat. */
object ModelCatalog {

    private val CHAT_WORDS = listOf(
        "chat", "generate", "generation", "completion", "text-generation", "llm",
        "instruct", "conversational",
    )

    private val NON_CHAT_WORDS = listOf(
        "embed", "encode", "rerank", "score", "scoring", "classif", "extract", "ocr",
        "transcri", "speech", "audio", "asr", "tts", "detection", "segment",
        "moderation", "guard", "vision-encoder", "sparse",
    )

    private val META_KEYS = listOf(
        "type", "task", "tasks", "model_type", "category", "mode", "modality", "modalities",
        "capabilities", "pipeline_tag", "endpoint", "endpoints", "supported_endpoints", "kind",
    )

    private val NAME_DENY = listOf(
        "embed", "rerank", "bge-", "bge_", "colbert", "colqwen", "colpali", "splade", "siglip",
        "clip-", "ocr", "whisper", "parakeet", "gliner", "nuner", "gliclass", "deberta", "guard",
        "docling", "grounding-dino", "owlv", "sam2", "sam3", "neural-sparse", "sparse-encoding",
        "pii-redact", "madlad", "zeroshot", "moderation", "-tts", "tts-",
    )

    private val NAME_DENY_PREFIX = listOf("e5-", "gte-", "bge")

    private val OUTPUT_CAP_MARKERS = listOf(
        "max_tokens", "max_completion_tokens", "max_output_tokens",
        "output tokens", "completion tokens", "max_new_tokens",
    )

    private val CONTEXT_MARKERS = listOf(
        "context length", "context window", "prompt is too long",
    )

    private val INT_REGEX = Regex("(?<![.\\d])(\\d+)(?!\\.\\d)")

    /** Parses the OpenAI shape, a bare array, or an array of plain strings. */
    fun parse(payload: String): List<ModelEntry> {
        val root = try {
            ChatJson.parseToJsonElement(payload)
        } catch (e: Exception) {
            throw IllegalArgumentException("모델 목록 형식을 해석하지 못했습니다.", e)
        }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray
                ?: throw IllegalArgumentException("모델 목록 형식을 해석하지 못했습니다.")
            else -> throw IllegalArgumentException("모델 목록 형식을 해석하지 못했습니다.")
        }
        return array.mapNotNull(::toEntry)
    }

    private fun toEntry(element: JsonElement): ModelEntry? = when (element) {
        is JsonPrimitive -> {
            val id = element.content
            if (id.isBlank()) null else ModelEntry(id)
        }
        is JsonObject -> {
            val id = (element["id"] as? JsonPrimitive)?.content
            if (id.isNullOrBlank()) null else ModelEntry(id, element)
        }
        else -> null
    }

    /** Builds a [ModelListing] from parsed entries. */
    fun listing(entries: List<ModelEntry>): ModelListing {
        val all = entries.map { it.id }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val chat = entries
            .filter { isChatModel(it.id, it.meta) }
            .map { it.id }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ModelListing(chat = chat, all = all)
    }

    /** Whether [id] (with optional provider [meta]) can be used for chat. */
    fun isChatModel(id: String, meta: JsonObject? = null): Boolean {
        val words = metadataWords(meta)
        if (words.any { w -> CHAT_WORDS.any { w.contains(it) } }) return true
        if (words.any { w -> NON_CHAT_WORDS.any { w.contains(it) } || isNerTag(w) }) return false

        val name = id.substringAfterLast('/').lowercase().substringBefore(':')
        if (NAME_DENY.any { name.contains(it) }) return false
        if (NAME_DENY_PREFIX.any { name.startsWith(it) }) return false
        return true
    }

    /** "ner" only as a whole tag: as a substring it would match "general", "inner"... */
    private fun isNerTag(word: String): Boolean =
        word.split(Regex("[^a-z0-9]+")).any { it == "ner" }

    private fun metadataWords(meta: JsonObject?): List<String> {
        if (meta == null) return emptyList()
        val out = mutableListOf<String>()
        for (key in META_KEYS) {
            val value = meta[key] ?: continue
            collectWords(value, out)
        }
        return out
    }

    private fun collectWords(value: JsonElement, out: MutableList<String>) {
        when (value) {
            is JsonPrimitive -> {
                if (value.isString) out += value.content.lowercase()
            }
            is JsonArray -> value.forEach { collectWords(it, out) }
            is JsonObject -> value.forEach { (k, v) ->
                val flag = (v as? JsonPrimitive)?.booleanOrNull
                if (flag == true) out += k.lowercase()
            }
        }
    }

    /** Reads the provider's output-token cap out of a 400/422 rejection. */
    fun outputLimitFrom(message: String, requested: Int): Int? {
        val lower = message.lowercase()
        if (CONTEXT_MARKERS.any { lower.contains(it) }) return null
        if (OUTPUT_CAP_MARKERS.none { lower.contains(it) }) return null

        return INT_REGEX.findAll(message)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 256 until requested }
            .maxOrNull()
    }
}
