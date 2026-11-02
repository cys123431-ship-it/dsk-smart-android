package com.dskapp.data.link

/**
 * Turns a raw HTML document into readable plain text.
 *
 * Kept free of android.* so it can run on the JVM and be unit tested without Robolectric.
 * The heuristics are intentionally coarse: we only need enough structure to feed a summary,
 * not a spec-compliant parser.
 */
object HtmlTextExtractor {

    data class Page(val title: String?, val description: String?, val text: String)

    private const val ELLIPSIS = "\n…(이하 생략)"

    private val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DESC_REGEX = Regex(
        "<meta[^>]*name\\s*=\\s*[\"']description[\"'][^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val OG_DESC_REGEX = Regex(
        "<meta[^>]*property\\s*=\\s*[\"']og:description[\"'][^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val CONTENT_ATTR_REGEX = Regex(
        "content\\s*=\\s*[\"']([^\"']*)[\"']",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val COMMENT_REGEX = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    private val ARTICLE_REGEX = Regex("<article\\b[^>]*>(.*?)</article>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val MAIN_REGEX = Regex("<main\\b[^>]*>(.*?)</main>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BODY_REGEX = Regex("<body\\b[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val LI_REGEX = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BLOCK_REGEX = Regex(
        "</?(?:p|div|section|article|h[1-6]|tr|blockquote|pre|table|ul|ol|dd|dt|figcaption)\\b[^>]*>",
        RegexOption.IGNORE_CASE
    )
    private val CELL_REGEX = Regex("</t[dh]>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)
    private val SPACES_REGEX = Regex("[ \\t]+")
    private val NEWLINES_REGEX = Regex("\n{3,}")

    fun extract(html: String, maxChars: Int = 20_000): Page {
        // Comments can hide fake tags, so drop them before any element removal.
        val cleaned = COMMENT_REGEX.replace(html, " ")

        val title = parseTitle(cleaned)
        val description = parseDescription(cleaned)

        val withoutHead = removeElements(cleaned, "script", "style", "noscript", "svg", "template", "iframe", "head")

        val region = chooseRegion(withoutHead)
        val trimmed = removeElements(region, "nav", "header", "footer", "aside", "form", "button", "select")

        val withBreaks = trimmed
            .replace(BR_REGEX, "\n")
            .replace(LI_REGEX, "\n- ")
            .replace(BLOCK_REGEX, "\n")
            .replace(CELL_REGEX, "\t")

        val text = normalize(unescape(TAG_REGEX.replace(withBreaks, " ")), maxChars)

        return Page(title, description, text)
    }

    private fun parseTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val value = unescape(raw).trim()
        return value.ifBlank { null }
    }

    private fun parseDescription(html: String): String? {
        val tag = DESC_REGEX.find(html)?.value ?: OG_DESC_REGEX.find(html)?.value ?: return null
        val raw = CONTENT_ATTR_REGEX.find(tag)?.groupValues?.get(1) ?: return null
        val value = unescape(raw).trim()
        return value.ifBlank { null }
    }

    private fun removeElements(html: String, vararg tags: String): String {
        var result = html
        for (tag in tags) {
            val regex = Regex(
                "<$tag\\b[^>]*>.*?</$tag\\s*>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            result = regex.replace(result, " ")
        }
        return result
    }

    private fun chooseRegion(html: String): String {
        val article = ARTICLE_REGEX.find(html)?.groupValues?.get(1)
        if (article != null && roughLength(article) >= 500) return article

        val main = MAIN_REGEX.find(html)?.groupValues?.get(1)
        if (main != null) return main

        val body = BODY_REGEX.find(html)?.groupValues?.get(1)
        if (body != null) return body

        return html
    }

    /** Cheap plain-text length used only for the article-vs-body decision. */
    private fun roughLength(html: String): Int = TAG_REGEX.replace(html, "").trim().length

    private fun normalize(text: String, maxChars: Int): String {
        val perLine = text
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { SPACES_REGEX.replace(it, " ").trim() }

        var result = NEWLINES_REGEX.replace(perLine, "\n\n").trim()

        if (result.length > maxChars) {
            result = result.substring(0, maxChars) + ELLIPSIS
        }
        return result
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "middot" to "\u00B7",
        "hellip" to "\u2026",
        "ndash" to "\u2013",
        "mdash" to "\u2014",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "ldquo" to "\u201C",
        "rdquo" to "\u201D",
        "copy" to "\u00A9",
        "reg" to "\u00AE",
        "trade" to "\u2122",
        "laquo" to "\u00AB",
        "raquo" to "\u00BB"
    )

    private val NAMED_ENTITY_REGEX = Regex("&([a-zA-Z]+);")
    private val DEC_ENTITY_REGEX = Regex("&#(\\d+);")
    private val HEX_ENTITY_REGEX = Regex("&#[xX]([0-9a-fA-F]+);")

    internal fun unescape(text: String): String {
        var result = text
        result = DEC_ENTITY_REGEX.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        result = HEX_ENTITY_REGEX.replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }
        result = NAMED_ENTITY_REGEX.replace(result) { match ->
            NAMED_ENTITIES[match.groupValues[1]] ?: match.value
        }
        return result
    }

    private val META_CHARSET_REGEX = Regex(
        "<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)",
        RegexOption.IGNORE_CASE
    )

    fun sniffCharset(head: ByteArray): String? {
        // ISO-8859-1 maps every byte 1:1 to a char, so ASCII patterns stay intact.
        val ascii = String(head, Charsets.ISO_8859_1)
        val charset = META_CHARSET_REGEX.find(ascii)?.groupValues?.get(1) ?: return null
        return charset.lowercase()
    }
}
