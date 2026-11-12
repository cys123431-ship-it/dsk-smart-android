package com.dskapp.ui.schedule

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dskapp.data.schedule.ScheduleAlarms
import com.dskapp.data.schedule.ScheduleCalc
import com.dskapp.data.schedule.ScheduleRepository
import com.dskapp.data.schedule.ScheduledTask
import com.dskapp.data.schedule.ScheduledTaskJob
import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Holds scheduled-task list state and mediates persistence plus alarm re-scheduling.
 *
 * The activity-scoped app context is kept only for [ScheduleAlarms] which needs a Context
 * but must not leak an Activity reference, hence the explicit [appContext] parameter.
 */
class ScheduleViewModel(
    context: Context,
    private val repository: ScheduleRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val appContext: Context = context.applicationContext

    val tasks: StateFlow<List<ScheduledTask>> = repository.tasks

    val knownModels: StateFlow<List<String>> = settings.state
        .map { it.knownModels }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unavailableModels: StateFlow<Set<String>> = settings.state
        .map { it.unavailableModels }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Model shown as the "기본 모델" entry; trimmed to the last path segment in the UI. */
    val defaultModel: String = settings.state.value.model

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Assigns a fresh id when the dialog passes "" for a new task, then persists and
     * re-arms the exact alarm so the change takes effect immediately.
     */
    fun save(task: ScheduledTask) {
        val resolved = if (task.id.isBlank()) task.copy(id = UUID.randomUUID().toString()) else task
        repository.upsert(resolved)
        ScheduleAlarms.reschedule(appContext, resolved)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = repository.get(id) ?: return
        val updated = current.copy(enabled = enabled)
        repository.upsert(updated)
        if (enabled) ScheduleAlarms.reschedule(appContext, updated) else ScheduleAlarms.cancel(appContext, id)
    }

    fun delete(id: String) {
        ScheduleAlarms.cancel(appContext, id)
        repository.delete(id)
    }

    fun runNow(id: String) {
        ScheduledTaskJob.enqueue(appContext, id)
        _message.value = "지금 실행합니다. 결과는 알림으로 알려 드려요."
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun nextRunText(task: ScheduledTask): String =
        ScheduleCalc.nextRun(task, ZonedDateTime.now(ScheduleCalc.ZONE))
            ?.let { "다음 실행 " + ScheduleCalc.formatWhen(it) }
            ?: "꺼짐"

    /** True when exact alarms are not permitted, hinting the user toward system settings. */
    val needsExactAlarm: Boolean
        get() = !ScheduleAlarms.canScheduleExact(appContext)

    companion object {
        /** The dialog uses provided repository/settings instances so tests can inject fakes. */
        fun factory(
            context: Context,
            repository: ScheduleRepository,
            settings: SettingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ScheduleViewModel(context.applicationContext, repository, settings) as T
        }
    }
}
