package com.dskapp.data.search

/** One search result. [snippet] is plain text (tags stripped, entities decoded), may be empty. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/** Readable text of one result page, already cut to size. */
data class PageExcerpt(val url: String, val title: String?, val text: String)

/** Everything one search round produced; [errors] are short Korean reasons for failed queries. */
data class SearchReport(
    val queries: List<String>,
    val hits: List<SearchHit>,
    val pages: List<PageExcerpt>,
    val errors: List<String>
)

/** A search engine. Implementations must not throw; failures come back as Result.failure with a Korean message. */
interface WebSearchProvider {
    suspend fun search(query: String, limit: Int): Result<List<SearchHit>>
}

/**
 * Engines the user can pick. Only DuckDuckGo exists today (no key needed). To add Brave or
 * Tavily: write a WebSearchProvider, add an entry here with needsKey = true, and store its
 * key with KeystoreCipher like the AI key.
 */
enum class SearchProviderType(val label: String, val needsKey: Boolean) {
    DUCKDUCKGO("DuckDuckGo", needsKey = false);

    companion object {
        /** Unknown or missing stored names fall back to DuckDuckGo. */
        fun fromName(name: String?): SearchProviderType =
            entries.firstOrNull { it.name == name } ?: DUCKDUCKGO
    }
}
