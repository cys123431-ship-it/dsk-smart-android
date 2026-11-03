package com.dskapp.data.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the queries of one search round: sequential searches (the engine throttles bursts),
 * merged deduplicated hits, then a few result pages read in parallel.
 */
class WebSearchRunner(
    private val provider: () -> WebSearchProvider,
    private val pageReader: suspend (String) -> Result<PageExcerpt>
) {

    suspend fun run(queries: List<String>): SearchReport {
        if (queries.isEmpty()) return SearchReport(queries, emptyList(), emptyList(), emptyList())

        // Resolved once per run so a settings change mid-run does not split the report.
        val engine = provider()

        val errors = ArrayList<String>()
        val hits = ArrayList<SearchHit>()
        val seen = HashSet<String>()

        for (query in queries) {
            val result = engine.search(query, HITS_PER_QUERY)
            result.getOrElse { e ->
                if (e is CancellationException) throw e
                errors.add("'$query': ${e.message ?: "알 수 없는 오류"}")
                return@getOrElse emptyList<SearchHit>()
            }.forEach { hit ->
                if (hits.size >= MAX_HITS) return@forEach
                if (seen.add(dedupeKey(hit.url))) hits.add(hit)
            }
        }

        val pages = readPages(hits.take(PAGES_TO_READ))
        return SearchReport(queries, hits, pages, errors)
    }

    /** Reads the first hits in parallel; failures and timeouts are silently dropped. */
    private suspend fun readPages(targets: List<SearchHit>): List<PageExcerpt> = coroutineScope {
        val deferred = targets.map { hit ->
            async {
                val excerpt = try {
                    withTimeoutOrNull(PAGE_TIMEOUT_MS) { pageReader(hit.url) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                excerpt?.getOrElse { e ->
                    if (e is CancellationException) throw e
                    null
                }?.let { toExcerpt(hit, it) }
            }
        }
        deferred.mapNotNull { it.await() }
    }

    private fun toExcerpt(hit: SearchHit, page: PageExcerpt): PageExcerpt? {
        val text = cut(page.text)
        if (text.isBlank()) return null
        return PageExcerpt(page.url, page.title ?: hit.title, text)
    }

    /** Cuts [text] to [PAGE_CHARS], preferring a newline or sentence boundary after 70%. */
    private fun cut(text: String): String {
        if (text.length <= PAGE_CHARS) return text
        val minIndex = (PAGE_CHARS * 7) / 10
        val newline = text.lastIndexOf('\n', PAGE_CHARS - 1)
        val sentence = text.lastIndexOf(". ", PAGE_CHARS - 1)
        val boundary = maxOf(newline, if (sentence >= 0) sentence + 1 else -1)
        return if (boundary >= minIndex) {
            text.substring(0, boundary).trimEnd() + "…"
        } else {
            text.substring(0, PAGE_CHARS) + "…"
        }
    }

    /** URL identity for duplicate detection: trailing slash and #fragment removed. */
    private fun dedupeKey(url: String): String =
        url.substringBefore('#').removeSuffix("/")

    companion object {
        const val HITS_PER_QUERY = 5
        const val MAX_HITS = 8
        const val PAGES_TO_READ = 3
        const val PAGE_CHARS = 3000
        const val PAGE_TIMEOUT_MS = 12_000L
    }
}
