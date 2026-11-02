package com.dskapp.data.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point for alarms and system broadcasts. Only routes work to durable schedulers;
 * the receiver itself must return quickly.
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ScheduleAlarms.ACTION_FIRE -> {
                val taskId = intent.getStringExtra(ScheduleAlarms.EXTRA_TASK_ID) ?: return
                val task = ScheduleRepository.get(appContext).get(taskId) ?: return
                if (!task.enabled) return
                // Arm the next day before running, so a crash in the job does not drop the series.
                ScheduleAlarms.reschedule(appContext, task)
                ScheduledTaskJob.enqueue(appContext, taskId)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                ScheduleAlarms.rescheduleAll(appContext)
            }
        }
    }
}
