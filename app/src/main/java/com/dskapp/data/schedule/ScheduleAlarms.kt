package com.dskapp.data.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Arms exact (or inexact when the OS forbids exact) alarms for scheduled tasks.
 * The alarm only wakes [ScheduleReceiver]; real work is handed to the JobScheduler.
 */
object ScheduleAlarms {

    const val ACTION_FIRE = "com.dskapp.schedule.FIRE"
    const val EXTRA_TASK_ID = "task_id"

    /** Cancels any existing alarm and arms the next one, if the task can still run. */
    fun reschedule(context: Context, task: ScheduledTask) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(appContext, task.id)

        alarmManager.cancel(pendingIntent)

        val next = ScheduleCalc.nextRun(task, java.time.ZonedDateTime.now(ScheduleCalc.ZONE))
            ?: return
        val millis = next.toInstant().toEpochMilli()

        val exact = canScheduleExact(appContext)
        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            }
        }.onFailure {
            // SecurityException can still surface between the check and the call.
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            }
        }
    }

    /** Re-arms every enabled task; also used after boot and time changes. */
    fun rescheduleAll(context: Context) {
        val repository = ScheduleRepository.get(context)
        repository.tasks.value.forEach { task ->
            if (task.enabled) reschedule(context, task) else cancel(context, task.id)
        }
    }

    fun cancel(context: Context, taskId: String) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(appContext, taskId))
    }

    /** Exact alarms need a user-granted permission from API 31 upwards. */
    fun canScheduleExact(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            true
        } else {
            val alarmManager = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        }
    }

    private fun requestCode(id: String): Int = id.hashCode()

    private fun pendingIntent(context: Context, taskId: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
