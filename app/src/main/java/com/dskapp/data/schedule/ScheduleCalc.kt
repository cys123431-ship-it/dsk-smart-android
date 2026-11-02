package com.dskapp.data.schedule

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Pure scheduling math. Kept free of Android types so it can be unit tested on the JVM.
 */
object ScheduleCalc {

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private val DAY_NAMES = charArrayOf('월', '화', '수', '목', '금', '토', '일')

    /** Weekday numbers 1..5 (Mon..Fri), used for the "평일" description. */
    private val WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

    /** Weekend numbers 6 and 7, used for the "주말" description. */
    private val WEEKEND: Set<Int> = setOf(6, 7)

    /**
     * Returns the first trigger strictly after [now], or null when the task cannot run.
     *
     * Strict inequality matters: a trigger exactly equal to "now" has already fired.
     */
    fun nextRun(task: ScheduledTask, now: ZonedDateTime): ZonedDateTime? {
        if (!task.enabled || task.days.isEmpty()) return null

        val local = now.withZoneSameInstant(ZONE)
        var candidate = local.toLocalDate()
            .atTime(task.hour, task.minute)
            .atZone(ZONE)
        if (!candidate.isAfter(local)) {
            candidate = candidate.plusDays(1)
        }

        // A week always contains a match when days is non-empty, so 8 iterations is plenty.
        repeat(8) {
            if (candidate.dayOfWeek.value in task.days) return candidate
            candidate = candidate.plusDays(1)
        }
        return null
    }

    /** Human readable schedule, e.g. "매일 08:00", "평일 07:30" or "월·수·금 18:30". */
    fun describe(task: ScheduledTask): String {
        val time = String.format(Locale.KOREA, "%02d:%02d", task.hour, task.minute)
        val days = task.days
        val prefix = when {
            days.isEmpty() -> return "없음 $time"
            days == ScheduledTask.ALL_DAYS -> "매일"
            days == WEEKDAYS -> "평일"
            days == WEEKEND -> "주말"
            else -> days.sorted().joinToString("·") { DayOfWeek.of(it).let { d -> DAY_NAMES[d.value - 1].toString() } }
        }
        return "$prefix $time"
    }

    /** Display form such as "11월 3일 (월) 08:00". */
    fun formatWhen(time: ZonedDateTime): String {
        val local = time.withZoneSameInstant(ZONE)
        val day = DAY_NAMES[local.dayOfWeek.value - 1]
        return String.format(
            Locale.KOREA,
            "%d월 %d일 (%s) %02d:%02d",
            local.monthValue,
            local.dayOfMonth,
            day.toString(),
            local.hour,
            local.minute
        )
    }
}
