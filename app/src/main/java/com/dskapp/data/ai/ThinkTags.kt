package com.dskapp.data.ai

/**
 * Some models (Qwen, GLM...) put their reasoning inline as `<think>…</think>` at the start
 * of the answer instead of a separate `reasoning_content` field. This splits it off so the
 * UI can fold it away and only the real answer is stored, exported and read aloud.
 */
object ThinkTags {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    /**
     * Returns (reasoning, answer). While the closing tag has not arrived yet (streaming),
     * everything after `<think>` is reasoning and the answer is empty.
     */
    fun split(text: String): Pair<String?, String> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(OPEN)) return null to text
        val body = trimmed.removePrefix(OPEN)
        val close = body.indexOf(CLOSE)
        return if (close < 0) {
            body.trim() to ""
        } else {
            body.substring(0, close).trim() to body.substring(close + CLOSE.length).trimStart()
        }
    }
}
