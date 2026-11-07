package com.dskapp.agent

import android.util.Base64
import com.dskapp.data.ai.AiImage
import com.dskapp.data.ai.AiMessage
import com.dskapp.data.ai.AiResult
import com.dskapp.data.ai.AiRole
import com.dskapp.data.ai.userMessage
import com.dskapp.di.ServiceLocator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** How one agent run ended: [summary] for the user, [log] of every step for the chat record. */
data class AgentOutcome(val summary: String, val log: List<String>, val finished: Boolean)

/**
 * The read → decide → act loop. Every decision goes through [AgentProtocol]'s checks:
 * allowed level, well-formed action, blocked apps, and a user confirmation for anything
 * that sends, pays, deletes or cannot be undone. [unattended] runs (scheduled) never get a
 * confirmation dialog, so they stop at the first action that would need one.
 */
class PhoneAgentRunner(private val service: DskAccessibilityService) {

    suspend fun run(
        goal: String,
        level: AgentLevel,
        maxSteps: Int,
        model: String?,
        unattended: Boolean
    ): AgentOutcome {
        val settings = ServiceLocator.settings(service).state.value
        val ai = ServiceLocator.aiClient(service)
        val log = mutableListOf<String>()
        var badReplies = 0

        fun outcome(summary: String, finished: Boolean = false) = AgentOutcome(summary, log.toList(), finished)

        for (step in 1..maxSteps) {
            currentCoroutineContext().ensureActive()
            service.overlay.setStatus("$step/$maxSteps · 화면 읽는 중")
            // Let the previous action's animation settle before reading the screen.
            delay(if (step == 1) SETTLE_FIRST_MS else SETTLE_MS)

            // Null when DSK itself is in front (it never reads its own chat) or nothing is
            // readable: the model can still start with open_app / home.
            val screen = ScreenReader.capture(service)
            if (screen != null &&
                AgentProtocol.isBlockedPackage(screen.packageName, settings.agentBlocked, settings.agentAllowed)
            ) {
                return outcome("‘${screen.appLabel}’은(는) 보안상 조작하지 않는 앱이라 멈췄습니다. (설정 > 폰 조작에서 허용할 수 있어요)")
            }
            val screenText = screen?.render() ?: NO_SCREEN
            val elementCount = screen?.elements?.size ?: 0

            val images = if (screen != null && settings.agentScreenshots && elementCount < FEW_ELEMENTS) {
                service.screenshotJpeg()?.let { listOf(AiImage("image/jpeg", Base64.encodeToString(it, Base64.NO_WRAP))) }
            } else {
                null
            }
            val message = AgentProtocol.stepMessage(goal, level, step, maxSteps, log.takeLast(HISTORY_LINES), screenText)
            val reply = ai.complete(
                systemPrompt = AgentProtocol.SYSTEM_PROMPT,
                messages = listOf(AiMessage(AiRole.USER, message, images.orEmpty())),
                // Reasoning models spend part of this on thinking before the JSON action.
                maxTokens = REPLY_TOKENS,
                model = model
            )
            val text = when (reply) {
                is AiResult.Success -> reply.text
                is AiResult.Failure -> return outcome("AI 오류로 멈췄습니다: ${reply.error.userMessage}")
            }

            val action = AgentProtocol.parse(text).getOrElse {
                if (++badReplies >= MAX_BAD_REPLIES) return outcome("AI 답변 형식이 계속 잘못되어 멈췄습니다.")
                log += "$step. (AI 답변 형식 오류 - 다시 요청)"
                continue
            }
            AgentProtocol.validate(action, elementCount)?.let { problem ->
                log += "$step. 잘못된 행동: $problem"
                continue
            }
            if (!AgentProtocol.allowedAt(action, level)) {
                log += "$step. 허용되지 않은 행동 '${action.action}' (허용 수준: ${level.label})"
                continue
            }

            when (action.action) {
                "done" -> return outcome(action.text.orEmpty(), finished = true)
                "ask" -> return outcome("확인이 필요해 멈췄습니다: ${action.text.orEmpty()}")
            }

            val label = screen?.labelOf(action.target)
            val what = describe(action, label)
            if (AgentProtocol.needsConfirmation(action, label)) {
                if (unattended) return outcome("확인이 필요한 동작($what)이라 자동 실행에서는 멈췄습니다.")
                val question = listOfNotNull(action.reason, "$what — 실행할까요?").joinToString("\n")
                if (!service.overlay.confirm(question)) return outcome("사용자가 ‘$what’을(를) 취소해서 멈췄습니다.")
            }

            service.overlay.setStatus("$step/$maxSteps · ${action.reason ?: what}")
            val result = service.executor.execute(action, screen)
            log += "$step. " + result.getOrElse { "실패 - ${it.message}" }
        }
        return outcome("최대 ${maxSteps}단계에 도달해 멈췄습니다.")
    }

    private fun describe(action: AgentAction, label: String?): String = when (action.action) {
        "tap" -> "‘${label ?: "?"}’ 누르기"
        "long_press" -> "‘${label ?: "?"}’ 길게 누르기"
        "type" -> "‘${label ?: "?"}’에 ‘${action.text.orEmpty().take(40)}’ 입력"
        "open_app" -> "‘${action.text.orEmpty()}’ 앱 열기"
        "scroll" -> "스크롤 (${action.direction ?: "down"})"
        "swipe" -> "밀기 (${action.direction.orEmpty()})"
        "back" -> "뒤로 가기"
        "home" -> "홈 화면"
        "recents" -> "최근 앱"
        "notifications" -> "알림창 열기"
        "wait" -> "기다리기"
        else -> action.action
    }

    private companion object {
        const val SETTLE_FIRST_MS = 300L
        const val SETTLE_MS = 800L
        const val NO_SCREEN =
            "(지금은 DSK 스마트 화면이라 읽지 않습니다. open_app 으로 필요한 앱을 열거나 home 으로 시작하세요.)"
        const val MAX_BAD_REPLIES = 3
        const val HISTORY_LINES = 12
        const val REPLY_TOKENS = 1200
        /** Below this many readable elements the screen is probably a picture (map, game). */
        const val FEW_ELEMENTS = 5
    }
}
