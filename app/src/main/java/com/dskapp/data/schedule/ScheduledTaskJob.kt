package com.dskapp.data.schedule

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.dskapp.data.log.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one scheduled task. JobScheduler is used instead of WorkManager so no new
 * dependency is needed while still surviving Doze via expedited / deadline hints.
 */
class ScheduledTaskJob : JobService() {

    private var scope: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val taskId = params.extras.getString(EXTRA_TASK_ID)
        if (taskId.isNullOrEmpty()) {
            jobFinished(params, false)
            return false
        }
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = jobScope
        jobScope.launch {
            val repository = ScheduleRepository.get(applicationContext)
            val task = repository.get(taskId)
            if (task == null) {
                jobFinished(params, false)
                return@launch
            }
            ScheduledTaskRunner.run(applicationContext, taskId)
                .onSuccess { outcome ->
                    repository.markRun(taskId, System.currentTimeMillis(), null, outcome.conversationId)
                    ScheduleNotifier.notifyResult(applicationContext, task.copy(conversationId = outcome.conversationId), outcome)
                }
                .onFailure { error ->
                    val message = error.message ?: error.javaClass.simpleName
                    repository.markRun(taskId, System.currentTimeMillis(), message, null)
                    ErrorLog.record("schedule", "task ${task.name} failed", error)
                    ScheduleNotifier.notifyFailure(applicationContext, task, message)
                }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope?.cancel()
        scope = null
        return false
    }

    companion object {
        private const val JOB_ID_BASE = 40_000
        private const val EXTRA_TASK_ID = "task_id"

        fun enqueue(context: Context, taskId: String) {
            val appContext = context.applicationContext
            val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            val jobId = JOB_ID_BASE + (taskId.hashCode() and 0x7FFF)
            val extras = PersistableBundle().apply { putString(EXTRA_TASK_ID, taskId) }
            val builder = JobInfo.Builder(jobId, ComponentName(appContext, ScheduledTaskJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setExtras(extras)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(true)
            } else {
                builder.setOverrideDeadline(0)
            }

            val result = runCatching { scheduler.schedule(builder.build()) }.getOrNull()
            if (result != JobScheduler.RESULT_SUCCESS) {
                // Expedited quota may be exhausted; a plain job is better than none.
                val fallback = JobInfo.Builder(jobId, ComponentName(appContext, ScheduledTaskJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExtras(extras)
                    .build()
                runCatching { scheduler.schedule(fallback) }
            }
        }
    }
}
