package com.dskapp.data.schedule

import android.content.Context
import com.dskapp.agent.AgentBridge
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.Prompts
import com.dskapp.data.ai.ThinkTags
import com.dskapp.data.ai.userMessage
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.chat.Attachment
import com.dskapp.data.chat.AttachmentCodec
import com.dskapp.data.link.UrlFinder
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
        val system = buildString {
            append(Prompts.SYSTEM)
            val custom = settings.state.value.customInstructions
            if (custom.isNotBlank()) append("\n\n").append(Prompts.CUSTOM_INSTRUCTIONS_HEADER).append('\n').append(custom)
            append("\n\n").append(Prompts.SCHEDULED_HINT)
        }

        val result = ServiceLocator.aiClient(appContext).complete(
            systemPrompt = system,
            messages = listOf(AiMessage(AiRole.USER, requestText)),
            maxTokens = MAX_TOKENS,
            model = task.model ?: existing?.model
        )
        when (result) {
            is AiResult.Success -> {
                val (reasoning, answer) = ThinkTags.split(result.text)
                chats.appendMessage(
                    conversationId = conversationId,
                    role = ChatViewModel.ROLE_ASSISTANT,
                    content = answer.trim().ifEmpty { result.text },
                    promptTokens = result.usage?.promptTokens,
                    completionTokens = result.usage?.completionTokens,
                    reasoning = reasoning
                )
                RunOutcome(conversationId, answer.trim().ifEmpty { result.text })
            }
            is AiResult.Failure -> {
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
