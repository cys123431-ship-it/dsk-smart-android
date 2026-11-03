package com.dskapp.data.schedule

import android.content.Context
import com.dskapp.agent.AgentBridge
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.Prompts
import com.dskapp.data.ai.ThinkTags
import com.dskapp.data.ai.TokenUsage
import com.dskapp.data.ai.userMessage
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentCodec
import com.dskapp.data.link.UrlFinder
import com.dskapp.data.search.SearchProtocol
import com.dskapp.di.ServiceLocator
import com.dskapp.ui.chat.ChatViewModel

data class RunOutcome(val conversationId: Long, val answer: String)

/**
 * Runs one scheduled task without any UI: the request and the answer are stored in the
 * task's own "⏰" thread, so the result is there even when notifications are off.
 */
object ScheduledTaskRunner {

    private const val MAX_TOKENS = 4096
    private const val MAX_LINKS = 3

    suspend fun run(context: Context, taskId: String): Result<RunOutcome> = runCatching {
        val appContext = context.applicationContext
        val schedule = ScheduleRepository.get(appContext)
        val task = schedule.get(taskId) ?: error("예약 작업을 찾을 수 없습니다.")
        val chats = ServiceLocator.chats(appContext)
        val settings = ServiceLocator.settings(appContext)
        check(settings.state.value.isConfigured) { "설정에서 서버 주소와 API 키를 먼저 입력해 주세요." }

        // Reuse the task's thread; recreate it if the user deleted it.
        val existing = task.conversationId?.let { chats.conversation(it) }
        val conversationId = existing?.id ?: chats.createConversation("⏰ ${task.name}")
        if (existing == null) schedule.markRun(task.id, task.lastRunAt ?: 0L, task.lastError, conversationId)

        if (task.usePhoneAgent) {
            chats.appendMessage(conversationId, ChatViewModel.ROLE_USER, "📱 ${task.prompt}")
            val record = AgentBridge.runUnattended(appContext, task.prompt, task.model ?: existing?.model)
                .getOrElse { error ->
                    chats.appendMessage(
                        conversationId = conversationId,
                        role = ChatViewModel.ROLE_ASSISTANT,
                        content = "예약 실행 실패: ${error.message}",
                        isError = true
                    )
                    throw error
                }
            chats.appendMessage(conversationId, ChatViewModel.ROLE_ASSISTANT, record)
            return@runCatching RunOutcome(conversationId, record)
        }

        val links = readLinks(appContext, task.prompt)
        chats.appendMessage(
            conversationId = conversationId,
            role = ChatViewModel.ROLE_USER,
            content = task.prompt,
            attachmentsJson = AttachmentCodec.encode(links)
        )

        val requestText = buildString {
            append(task.prompt)
            links.forEach { link ->
                append("\n\n### 첨부 파일: ").append(link.displayName).append("\n```\n")
                append(AttachmentStore.readText(link))
                append("\n```")
            }
        }
        val searchEnabled = settings.state.value.webSearch
        val system = buildString {
            append(Prompts.SYSTEM)
            val custom = settings.state.value.customInstructions
            if (custom.isNotBlank()) append("\n\n").append(Prompts.CUSTOM_INSTRUCTIONS_HEADER).append('\n').append(custom)
            if (searchEnabled) append("\n\n").append(SearchProtocol.instructions(SearchProtocol.todayInKorea()))
            append("\n\n").append(Prompts.SCHEDULED_HINT)
        }

        // Same search loop as the chat screen ("매일 아침 뉴스 요약" needs it most).
        var messages = listOf(AiMessage(AiRole.USER, requestText))
        var usage: TokenUsage? = null
        val searchFiles = mutableListOf<Attachment>()
        var round = 0
        var result: AiResult
        while (true) {
            result = ServiceLocator.aiClient(appContext).complete(
                systemPrompt = system,
                messages = messages,
                maxTokens = MAX_TOKENS,
                model = task.model ?: existing?.model
            )
            if (result !is AiResult.Success) break
            result.usage?.let { usage = usage?.plus(it) ?: it }
            if (!searchEnabled || round >= SearchProtocol.MAX_ROUNDS) break
            val queries = SearchProtocol.parseRequest(ThinkTags.split(result.text).second) ?: break
            round++
            val report = ServiceLocator.webSearch(appContext).run(queries)
            val results = SearchProtocol.resultsMessage(report, finalRound = round >= SearchProtocol.MAX_ROUNDS)
            if (report.hits.isNotEmpty()) {
                searchFiles += AttachmentStore.saveText(appContext, "🔍 ${queries.first()}".take(60), results)
            }
            messages = messages + AiMessage(AiRole.ASSISTANT, result.text.trim()) + AiMessage(AiRole.USER, results)
        }
        when (result) {
            is AiResult.Success -> {
                val (reasoning, raw) = ThinkTags.split(result.text)
                val answer = (if (searchEnabled) SearchProtocol.stripRequests(raw) else raw.trim())
                    .ifEmpty { "응답이 비어 있습니다." }
                chats.appendMessage(
                    conversationId = conversationId,
                    role = ChatViewModel.ROLE_ASSISTANT,
                    content = answer,
                    attachmentsJson = AttachmentCodec.encode(searchFiles),
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens,
                    reasoning = reasoning
                )
                RunOutcome(conversationId, answer)
            }
            is AiResult.Failure -> {
                AttachmentStore.deleteAll(searchFiles)
                chats.appendMessage(
                    conversationId = conversationId,
                    role = ChatViewModel.ROLE_ASSISTANT,
                    content = "예약 실행 실패: ${result.error.userMessage}",
                    isError = true
                )
                error(result.error.userMessage)
            }
        }
    }

    private suspend fun readLinks(context: Context, prompt: String): List<Attachment> =
        UrlFinder.find(prompt, MAX_LINKS).mapNotNull { url ->
            ServiceLocator.links().read(url).getOrNull()?.let { page ->
                val name = "🔗 " + (page.title ?: page.url.substringAfter("://").substringBefore('/'))
                AttachmentStore.saveText(context, name.take(60), "출처: ${page.url}\n\n${page.text}")
            }
        }
}
