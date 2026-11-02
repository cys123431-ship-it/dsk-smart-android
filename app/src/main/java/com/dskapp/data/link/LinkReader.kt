package com.dskapp.data.link

import com.dskapp.data.attachment.TextDecoding
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Fetches a web page and returns its readable text.
 *
 * Timeouts are tighter than the shared client: a chat message must not be blocked for long
 * just because a user pasted a slow link.
 */
class LinkReader(client: OkHttpClient) {

    data class LinkContent(val url: String, val title: String?, val text: String)

    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun read(url: String): Result<LinkContent> = withContext(Dispatchers.IO) {
        runCatching { fetch(url) }
    }

    private fun fetch(url: String): LinkContent {
        val scheme = url.substringBefore(':', "").lowercase()
        require(scheme == "http" || scheme == "https") { "지원하지 않는 링크입니다." }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()

        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("링크를 열 수 없습니다 (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IllegalStateException("페이지에서 본문을 찾지 못했습니다.")

            val contentType = body.contentType()
            val mime = contentType?.type?.lowercase()?.let { type ->
                contentType.subtype?.lowercase()?.let { subtype -> "$type/$subtype" }
            }
            val isHtml = mime == "text/html" || mime == "application/xhtml+xml"
            val isPlain = mime == "text/plain"
            if (!isHtml && !isPlain) {
                throw IllegalArgumentException("웹페이지가 아닌 링크입니다 (${mime ?: "알 수 없음"}).")
            }

            val (bytes, truncated) = readLimited(body.source())

            val charset = resolveCharset(contentType?.charset(), bytes)

            val finalUrl = response.request.url.toString()

            if (isHtml) {
                val html = if (charset != null) {
                    String(bytes, charset)
                } else {
                    TextDecoding.decode(bytes, truncated)
                }
                val page = HtmlTextExtractor.extract(html)
                val text = mergeDescription(page)
                if (text.isBlank()) throw IllegalStateException("페이지에서 본문을 찾지 못했습니다.")
                LinkContent(finalUrl, page.title, text)
            } else {
                val raw = if (charset != null) {
                    String(bytes, charset)
                } else {
                    TextDecoding.decode(bytes, truncated)
                }
                val text = raw.take(MAX_CHARS)
                if (text.isBlank()) throw IllegalStateException("페이지에서 본문을 찾지 못했습니다.")
                LinkContent(finalUrl, null, text)
            }
        }
    }

    private fun mergeDescription(page: HtmlTextExtractor.Page): String {
        val description = page.description ?: return page.text
        if (page.text.contains(description)) return page.text
        return if (page.text.isBlank()) description else "$description\n\n${page.text}"
    }

    private fun resolveCharset(declared: Charset?, bytes: ByteArray): Charset? {
        if (declared != null) return declared
        val head = bytes.copyOf(minOf(bytes.size, CHARSET_SNIFF_BYTES))
        val name = HtmlTextExtractor.sniffCharset(head) ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    private fun readLimited(source: okio.BufferedSource): Pair<ByteArray, Boolean> {
        val sink = Buffer()
        var remaining = MAX_BYTES.toLong()
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            sink.write(buffer, 0, read)
            remaining -= read
        }
        val truncated = if (remaining <= 0) {
            runCatching { !source.exhausted() }.getOrDefault(true)
        } else {
            false
        }
        return sink.readByteArray() to truncated
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_CHARS = 20_000
        const val CHARSET_SNIFF_BYTES = 4096

        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) DSK-Smart"
        const val ACCEPT = "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5"
        const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en;q=0.8"
    }
}
