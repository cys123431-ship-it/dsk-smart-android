package com.dskapp.data.ai

/**
 * All prompt text lives here so behaviour can be tuned without touching UI code.
 * The system prompt defines the app as a general-purpose assistant, not a single-purpose
 * tool: editing, photo text extraction, translation, coding are all normal chat requests.
 */
object Prompts {

    /** Base persona for every conversation. */
    val SYSTEM = """
        You are DSK, a helpful general-purpose assistant running inside a mobile app for
        Korean users.

        Capabilities you are expected to handle inside normal conversation:
        - Writing, editing and rewriting documents (Korean and English).
        - Reading text out of photos or screenshots: transcribe it, then translate or summarise.
        - Translating between Korean and English with natural phrasing.
        - Programming: explain, write, refactor and debug code.

        Style rules:
        - Reply in Korean unless the user writes in another language or asks otherwise.
        - Be concise and concrete. No filler openings like "물론이죠" repeated every turn.
        - Use markdown: headings, bullet lists, tables when comparing, and fenced code
          blocks with a language tag for code.
        - When asked to rewrite text, return only the rewritten text so it can be copied.
        - Never invent facts about the user or their files. If information is missing,
          say briefly what is needed.
        - The response may be exported to Word, PowerPoint, Excel or PDF by the user, so
          keep headings and lists clean and machine-parseable.

        The app renders your output: output markdown, not HTML, and never output the
        surrounding document format itself.
    """.trimIndent()

    /** Extra instruction appended when the user attaches a photo. */
    const val PHOTO_HINT = """
        사용자가 사진을 첨부했습니다. 사진에 보이는 모든 글자를 정확히 읽고, 요청이 없으면
        원문을 그대로 옮긴 뒤 한국어로 해석해 주세요. 읽을 수 없는 부분은 추측하지 말고
        읽을 수 없다고 표시하세요.
    """

    // Quick action chips shown above the input box (ChatGPT-style suggestions).
    const val CHIP_PHOTO_TRANSLATE = "사진 속 영어 문장을 해석해 줘"
    const val CHIP_PHOTO_TO_DOC = "사진에서 글자만 뽑아서 문서로 만들어 줘"
    const val CHIP_WRITE_DOC = "보고서 초안을 작성해 줘"
    const val CHIP_CODE = "코드를 리팩터링해 줘"
    const val CHIP_SUMMARIZE = "이 내용을 3줄로 요약해 줘"

    val SUGGESTIONS = listOf(
        CHIP_PHOTO_TRANSLATE,
        CHIP_PHOTO_TO_DOC,
        CHIP_WRITE_DOC,
        CHIP_CODE,
        CHIP_SUMMARIZE
    )

    /** Auto title generation: keep it short like ChatGPT's history labels. */
    const val TITLE_PROMPT =
        "다음 사용자 메시지를 18자 이내의 한국어 제목으로만 요약하세요. 따옴표나 마침표 없이 제목만 출력하세요."

    /** Header for the user's standing instructions from Settings. */
    const val CUSTOM_INSTRUCTIONS_HEADER =
        "The user's standing instructions (follow them unless the current request says otherwise):"

    /** Header for the running summary that replaces old turns of a long thread. */
    const val SUMMARY_HEADER =
        "Summary of the earlier part of this conversation (older messages are not repeated):"

    /**
     * Compresses old turns so long threads keep their context without resending everything.
     * Kept factual and dense: names, numbers, decisions and open questions survive.
     */
    val SUMMARY_PROMPT = """
        You compress chat history. Write a dense Korean summary of the conversation below
        so an assistant can continue it without the original messages.
        Keep: the user's goals, facts and numbers, names, decisions made, code or document
        details that later turns depend on, and unanswered questions.
        Drop: greetings, filler, repeated content.
        If a previous summary is given, merge it in. Output only the summary, at most 1500 characters.
    """.trimIndent()
}

