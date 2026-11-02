package com.dskapp.data.link

/**
 * Extracts web links from free-form text (user message, note, clipboard).
 *
 * Only http(s) is considered a link; other schemes are ignored on purpose so a pasted
 * `intent://` or `mailto:` never reaches the network layer.
 */
object UrlFinder {

    private val URL_REGEX = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)

    private const val TRAILING = ".,;:!?)]}'\u0022\u300D\u300F\u300B\u3009\uFF09"

    fun find(text: String, max: Int = 3): List<String> {
        if (max <= 0) return emptyList()

        val result = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(text)) {
            val url = trimTrailing(match.value)
            if (url.length < 10) continue
            result.add(url)
            if (result.size >= max) break
        }
        return result.toList()
    }

    private fun trimTrailing(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            if (last !in TRAILING) break
            // Keep a closing paren that balances an opening one (Wikipedia style URLs).
            if (last == ')' && url.count { it == '(' } >= url.count { it == ')' }) break
            url = url.dropLast(1)
        }
        return url
    }
}
