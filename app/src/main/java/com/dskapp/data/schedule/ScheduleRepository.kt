package com.dskapp.data.schedule

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * JSON-in-SharedPreferences store for scheduled tasks.
 *
 * Mutations are synchronized and re-read prefs before writing because the JobService
 * may update `lastRunAt` while the UI writes another task.
 */
class ScheduleRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(ScheduledTask.serializer())

    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    fun get(id: String): ScheduledTask? = read().firstOrNull { it.id == id }

    @Synchronized
    fun upsert(task: ScheduledTask) {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == task.id }
        if (index >= 0) current[index] = task else current.add(task)
        write(current)
    }

    @Synchronized
    fun delete(id: String) {
        write(read().filterNot { it.id == id })
    }

    /** Records a run result; [conversationId] only overwrites when the run produced one. */
    @Synchronized
    fun markRun(id: String, at: Long, error: String?, conversationId: Long?) {
        val current = read().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = current[index]
        current[index] = old.copy(
            lastRunAt = at,
            lastError = error,
            conversationId = conversationId ?: old.conversationId
        )
        write(current)
    }

    private fun read(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    private fun write(tasks: List<ScheduledTask>) {
        prefs.edit().putString(KEY_TASKS, json.encodeToString(serializer, tasks)).apply()
        _tasks.value = tasks
    }

    private fun load(): List<ScheduledTask> = read()

    companion object {
        private const val PREFS_NAME = "dsk_schedule"
        private const val KEY_TASKS = "tasks"

        @Volatile
        private var instance: ScheduleRepository? = null

        fun get(context: Context): ScheduleRepository {
            return instance ?: synchronized(this) {
                instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
