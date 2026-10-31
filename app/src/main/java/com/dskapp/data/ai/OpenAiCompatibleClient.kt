package com.dskapp.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint.
 *
 * Verified against the River AI docs:
 *  - base URL  https://api.river.ai/v1
 *  - auth      Authorization: Bearer rv_...
 *  - streaming SSE terminated by `data: [DONE]`
 *  - images    content part {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
 *
 * [configProvider] is read on every call, so editing the base URL / key / model in Settings
 * takes effect immediately without rebuilding anything.
 */
class OpenAiCompatibleClient(
    private val client: OkHttpClient,
    private val configProvider: () -> ProviderConfig
) : AiClient {

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int
    ): AiResult = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (!config.isComplete) return@withContext AiResult.Failure(AiError.NotConfigured)

        val body = ChatJson.encodeToString(
            ChatRequest.serializer(),
            buildRequest(config, systemPrompt, messages, maxTokens, stream = false)
        )

        val request = Request.Builder()
            .url(config.endpoint("chat/completions"))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext AiResult.Failure(parseError(response.code, payload))
                }
                val text = ChatJson.decodeFromString(ChatResponse.serializer(), payload).text()
                if (text.isBlank()) {
                    AiResult.Failure(AiError.Unexpected("응답이 비어 있습니다."))
                } else {
                    AiResult.Success(text)
                }
            }
        } catch (e: IOException) {
            AiResult.Failure(AiError.Network(e.message ?: "연결 실패"))
        } catch (e: Exception) {
            AiResult.Failure(AiError.Unexpected(e.message ?: e::class.simpleName ?: "알 수 없는 오류"))
        }
    }

    override suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configProvider()
            require(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) { "설정이 필요합니다." }

            val request = Request.Builder()
                .url(config.endpoint("models"))
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                require(response.isSuccessful) {
                    "모델 목록을 가져오지 못했습니다 (${response.code})"
                }
                ChatJson.decodeFromString(ModelsResponse.serializer(), payload)
                    .data.map { it.id }
                    .sorted()
            }
        }
    }

    override fun stream(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit
    ): Flow<Unit> = flow<Unit> {
        val config = configProvider()
        if (!config.isComplete) {
            onError(AiError.NotConfigured)
            return@flow
        }

        val body = ChatJson.encodeToString(
            ChatRequest.serializer(),
            buildRequest(config, systemPrompt, messages, maxTokens, stream = true)
        )

        val request = Request.Builder()
            .url(config.endpoint("chat/completions"))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // The read loop below blocks on the socket, which coroutine cancellation cannot
        // interrupt. A sibling guard coroutine wakes up on cancellation and cancels the OkHttp
        // call, closing the socket, so "stop" really stops the download instead of silently
        // reading (and paying for) the rest of the answer.
        val call = client.newCall(request)
        coroutineScope {
            val guard = launch {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                readStream(call, onText, onError)
            } finally {
                guard.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun readStream(
        call: Call,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit
    ) {
        try {
            call.execute().use { okResponse ->
                if (!okResponse.isSuccessful) {
                    onError(parseError(okResponse.code, okResponse.body?.string().orEmpty()))
                    return
                }

                val source = okResponse.body?.source() ?: return
                while (!source.exhausted()) {
                    // No further deltas once the user pressed stop.
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == DONE_SENTINEL) break

                    val chunk = runCatching {
                        ChatJson.decodeFromString(ChatStreamChunk.serializer(), payload)
                    }.getOrNull() ?: continue

                    chunk.choices.firstOrNull()?.delta?.content?.let { delta ->
                        if (delta.isNotEmpty()) onText(delta)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A cancelled call fails with "Canceled"/"Socket closed": that is the user's stop,
            // not a network error worth reporting.
            if (!call.isCanceled()) onError(AiError.Network(e.message ?: "연결 실패"))
        } catch (e: Exception) {
            onError(AiError.Unexpected(e.message ?: "알 수 없는 오류"))
        }
    }

    private fun buildRequest(
        config: ProviderConfig,
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int,
        stream: Boolean
    ) = ChatRequest(
        model = config.model,
        messages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(ChatRequestMessage("system", listOf(ChatContentPart.text(systemPrompt))))
            }
            messages.forEach { message ->
                add(
                    ChatRequestMessage(
                        role = when (message.role) {
                            AiRole.USER -> "user"
                            AiRole.ASSISTANT -> "assistant"
                        },
                        content = buildList {
                            if (message.text.isNotBlank()) add(ChatContentPart.text(message.text))
                            message.images.forEach { add(ChatContentPart.image(it.toDataUri())) }
                        }
                    )
                )
            }
        }.filter { it.content.isNotEmpty() },
        maxTokens = maxTokens,
        stream = stream
    )

    /** Maps an HTTP failure to a typed [AiError], preferring the provider's own message. */
    private fun parseError(status: Int, payload: String): AiError {
        val providerMessage = runCatching {
            val envelope = ChatJson.decodeFromString(ChatErrorEnvelope.serializer(), payload)
            envelope.error?.message ?: envelope.message
        }.getOrNull()

        return when (status) {
            401, 403 -> AiError.Unauthorized(providerMessage ?: "인증 실패")
            429 -> AiError.RateLimited(providerMessage ?: "요청 한도 초과")
            in 500..599 -> AiError.Provider(status, providerMessage ?: "서버 오류")
            else -> AiError.Provider(status, providerMessage ?: "요청 실패")
        }
    }

    companion object {
        /** River AI default; the user can change this in Settings. */
        const val DEFAULT_BASE_URL = "https://api.river.ai/v1"
        const val DEFAULT_MODEL = "deepseek-ai/DeepSeek-V4.1-Flash"

        /** Suggested models from the River docs; Settings also loads the live list. */
        val SUGGESTED_MODELS = listOf(
            "deepseek-ai/DeepSeek-V4.1-Flash",
            "deepseek-ai/DeepSeek-V4.1-Flash-1017",
            "moonshotai/Kimi-K3",
            "zai-org/GLM-5.3-Flash",
            "GLM-5.3",
            "Qwen/Qwen3.8-27B-FP8",
            "nvidia/NVIDIA-Nemotron-3.5-Super-VL-120B-A12B-NVFP4"
        )

        private const val DONE_SENTINEL = "[DONE]"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Long reads are normal for documents and images, so timeouts are generous. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

