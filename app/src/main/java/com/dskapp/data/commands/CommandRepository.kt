package com.dskapp.data.commands

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/** A saved request shown as a one-tap chip above the composer ("영어로 번역", "3줄 요약"...). */
@Serializable
data class PromptCommand(val id: String, val label: String, val prompt: String)

/**
 * The user's saved commands, kept as one small JSON value in SharedPreferences: a handful of
 * short strings does not justify a database table or a migration.
 */
class CommandRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _commands = MutableStateFlow(load())
    val commands: StateFlow<List<PromptCommand>> = _commands.asStateFlow()

    fun add(label: String, prompt: String) {
        if (label.isBlank() || prompt.isBlank()) return
        save(_commands.value + PromptCommand(UUID.randomUUID().toString(), label.trim(), prompt.trim()))
    }

    fun update(command: PromptCommand) {
        if (command.label.isBlank() || command.prompt.isBlank()) return
        save(_commands.value.map {
            if (it.id == command.id) command.copy(label = command.label.trim(), prompt = command.prompt.trim()) else it
        })
    }

    fun delete(id: String) = save(_commands.value.filterNot { it.id == id })

    /** Moves a command one place up or down; the chip row follows this order. */
    fun move(id: String, up: Boolean) {
        val list = _commands.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in list.indices) return
        list.add(target, list.removeAt(index))
        save(list)
    }

    fun resetDefaults() = save(CommandDefaults.list)

    private fun load(): List<PromptCommand> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return CommandDefaults.list
        return runCatching { json.decodeFromString<List<PromptCommand>>(raw) }
            .getOrDefault(CommandDefaults.list)
    }

    private fun save(list: List<PromptCommand>) {
        prefs.edit { putString(KEY_COMMANDS, json.encodeToString(list)) }
        _commands.value = list
    }

    private companion object {
        const val PREFS_FILE = "dsk_commands"
        const val KEY_COMMANDS = "commands"
    }
}

object CommandDefaults {
    val list = listOf(
        PromptCommand("default-en", "영어로 번역", "다음 내용을 자연스러운 영어로 번역해 줘. 번역문만 출력해."),
        PromptCommand("default-ko", "한국어로 번역", "다음 내용을 자연스러운 한국어로 번역해 줘. 번역문만 출력해."),
        PromptCommand("default-sum", "3줄 요약", "다음 내용을 핵심만 3줄로 요약해 줘."),
        PromptCommand("default-fix", "맞춤법 교정", "다음 글의 맞춤법과 띄어쓰기를 고쳐 줘. 고친 글만 출력하고, 바뀐 곳은 아래에 짧게 정리해."),
        PromptCommand("default-polite", "공손하게", "다음 글을 예의 바르고 부드러운 말투로 다듬어 줘. 다듬은 글만 출력해.")
    )
}

/**
 * Combines a command with what is already typed. `{입력}` in the command marks where the
 * typed text goes; otherwise the text is appended under the command.
 */
object CommandApplier {
    const val PLACEHOLDER = "{입력}"

    fun apply(prompt: String, input: String): String {
        val typed = input.trim()
        return when {
            prompt.contains(PLACEHOLDER) -> prompt.replace(PLACEHOLDER, typed)
            typed.isEmpty() -> prompt + "\n\n"
            else -> prompt + "\n\n" + typed
        }
    }
}
