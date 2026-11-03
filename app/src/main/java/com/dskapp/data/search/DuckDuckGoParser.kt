package com.dskapp.data.search

import com.dskapp.data.link.HtmlTextExtractor

/**
 * Parses the HTML result page of html.duckduckgo.com. Kept free of Android and network code so
 * the fragile part (markup) can be unit-tested against a captured page.
 */
object DuckDuckGoParser {

    /** Every `<a ...>` start tag; the class attribute is looked up inside the captured tag. */
    private val ANCHOR_TAG = Regex("<a\\b([^>]*)>", RegexOption.IGNORE_CASE)

    /** `<a>`/`<div>`/`<td>` start tags, used to locate the snippet container. */
    private val ELEMENT_TAG = Regex("<(a|div|td)\\b([^>]*)>", RegexOption.IGNORE_CASE)

    /** href / class attribute values in double or single quotes, in either order. */
    private val ATTR = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")

    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")

    private val BLOCKED_MODAL = Regex("anomaly-modal", RegexOption.IGNORE_CASE)
    private val BLOCKED_BOTS = Regex("bots use DuckDuckGo", RegexOption.IGNORE_CASE)
    private const val BLOCKED_CHALLENGE = "id=\"challenge-form\""

    fun parse(html: String, limit: Int): List<SearchHit> {
        if (limit <= 0) return emptyList()
        val hits = ArrayList<SearchHit>()
        val seen = HashSet<String>()

        // Collect every candidate title anchor; each anchors the snippet window that follows it.
        val titles = ANCHOR_TAG.findAll(html)
            .filter { hasClass(it.groupValues[1], "result__a") }
            .toList()

        for ((index, match) in titles.withIndex()) {
            val href = attrValue(match.groupValues[1], "href") ?: continue
            val titleEnd = html.indexOf("</a>", match.range.last + 1)
            if (titleEnd < 0) continue
            val title = clean(html.substring(match.range.last + 1, titleEnd))
            if (title.isBlank()) continue

            val nextStart = titles.getOrNull(index + 1)?.range?.first ?: html.length
            val snippet = snippetBetween(html, titleEnd + 1, minOf(nextStart, html.length))

            val url = normalizeUrl(href) ?: continue
            if (!seen.add(url)) continue
            hits.add(SearchHit(title, url, snippet))
            if (hits.size >= limit) break
        }
        return hits
    }

    /** True when the page is DuckDuckGo's bot challenge instead of results. */
    fun isBlocked(html: String): Boolean =
        BLOCKED_MODAL.containsMatchIn(html) ||
            BLOCKED_BOTS.containsMatchIn(html) ||
            html.contains(BLOCKED_CHALLENGE)

    /** First snippet element between [from] and [to], cleaned; blank when none. */
    private fun snippetBetween(html: String, from: Int, to: Int): String {
        var match = ELEMENT_TAG.find(html, from)
        while (match != null && match.range.first < to) {
            val current = match
            match = current.next()
            val attrs = current.groupValues[2]
            if (!hasClass(attrs, "result__snippet")) continue
            val name = current.groupValues[1].lowercase()
            val close = "</$name>"
            val end = html.indexOf(close, current.range.last + 1, ignoreCase = true)
            if (end < 0) continue
            return clean(html.substring(current.range.last + 1, end))
        }
        return ""
    }

    /** Value of attribute [name] in a start tag's [attrs], or null. */
    private fun attrValue(attrs: String, name: String): String? {
        for (m in ATTR.findAll(attrs)) {
            if (!m.groupValues[1].equals(name, ignoreCase = true)) continue
            val dq = m.groups[3]
            return if (dq != null) dq.value else m.groups[4]?.value ?: ""
        }
        return null
    }

    /** Exact class-token match, so `result__url` is never taken for `result__a`. */
    private fun hasClass(attrs: String, token: String): Boolean =
        attrValue(attrs, "class")?.split(SPACES)?.contains(token) == true

    /** Strip tags, decode entities, collapse whitespace. */
    private fun clean(raw: String): String =
        HtmlTextExtractor.unescape(raw.replace(TAGS, " ")).replace(SPACES, " ").trim()

    /** Resolve redirects/ads; null means "skip this hit". */
    private fun normalizeUrl(href: String): String? {
        val unescaped = href.replace("&amp;", "&").trim()
        if (unescaped.contains("duckduckgo.com/y.js")) return null

        val absolute = when {
            unescaped.startsWith("//") -> "https:$unescaped"
            else -> unescaped
        }
        val resolved = if (isDdgRedirect(absolute)) decodeUddg(absolute) else absolute
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://")) return null
        return resolved
    }

    private fun isDdgRedirect(url: String): Boolean =
        url.startsWith("https://duckduckgo.com/l/") || url.startsWith("http://duckduckgo.com/l/")

    /** Extracts the real target from the `uddg` query parameter. */
    private fun decodeUddg(url: String): String {
        val query = url.substringAfter('?', "")
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=', "")
            if (key != "uddg") continue
            val value = pair.substringAfter('=', "")
            return try {
                java.net.URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                value
            }
        }
        return url
    }
}
