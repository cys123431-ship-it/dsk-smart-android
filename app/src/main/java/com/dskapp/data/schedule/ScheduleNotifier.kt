package com.dskapp.data.schedule

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dskapp.MainActivity
import com.dskapp.R

/**
 * Posts the outcome of a scheduled run. All entry points are defensive: a notification
 * is a nicety, a crash here would lose the stored answer.
 */
object ScheduleNotifier {

    const val CHANNEL_ID = "scheduled_results"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "예약 작업 결과",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (granted != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    fun notifyResult(context: Context, task: ScheduledTask, outcome: RunOutcome) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val text = outcome.answer
            .replace(Regex("[#*_>`]"), "")
            .trim()
            .take(120)
        val bigText = outcome.answer.take(1500)

        val contentIntent = conversationIntent(context, outcome.conversationId, task.id)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_chat)
            .setContentTitle("⏰ ${task.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setAutoCancel(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)

        post(context, task.id.hashCode(), builder)
    }

    fun notifyFailure(context: Context, task: ScheduledTask, message: String) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val target = task.conversationId
        val contentIntent = if (target != null) {
            conversationIntent(context, target, task.id)
        } else {
            val intent = Intent(context, MainActivity::class.java)
            PendingIntent.getActivity(
                context,
                task.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shortcut_chat)
            .setContentTitle("⏰ ${task.name} 실패")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        post(context, task.id.hashCode(), builder)
    }

    private fun conversationIntent(context: Context, conversationId: Long, taskId: String): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("com.dskapp.action.OPEN_CONVERSATION")
            .putExtra("conversation_id", conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        runCatching {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }
    }
}
