package com.dskapp.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Searches DuckDuckGo's HTML endpoint. No API key is needed, but the endpoint throttles bursts,
 * so calls are expected to be sequential and rare (see [WebSearchRunner]).
 */
class DuckDuckGoSearch(client: OkHttpClient) : WebSearchProvider {

    // Cloned so a short search call never inherits the long timeouts used for chat streaming.
    private val client: OkHttpClient = client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    override suspend fun search(query: String, limit: Int): Result<List<SearchHit>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = FormBody.Builder()
                    .add("q", query)
                    .add("kl", "kr-kr")
                    .build()
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Language", ACCEPT_LANGUAGE)
                    .header("Accept", "text/html")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    val code = response.code
                    if (code == 202 || code == 403 || code == 429 || DuckDuckGoParser.isBlocked(html)) {
                        throw IllegalStateException(BLOCKED_MESSAGE)
                    }
                    if (!response.isSuccessful) {
                        throw IllegalStateException("검색하지 못했습니다 (HTTP $code).")
                    }
                    DuckDuckGoParser.parse(html, limit)
                }
            }
        }

    private companion object {
        const val ENDPOINT = "https://html.duckduckgo.com/html/"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en;q=0.8"
        const val BLOCKED_MESSAGE = "검색이 잠시 막혔습니다 (DuckDuckGo). 잠시 후 다시 시도해 주세요."
    }
}
