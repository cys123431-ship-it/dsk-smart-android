package com.dskapp.data.schedule

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalcTest {

    private val mondayMorning = ZonedDateTime.of(2026, 11, 2, 7, 0, 0, 0, ScheduleCalc.ZONE)

    private fun task(
        hour: Int,
        minute: Int,
        days: Set<Int> = ScheduledTask.ALL_DAYS,
        enabled: Boolean = true
    ) = ScheduledTask(
        id = "t1",
        name = "테스트",
        prompt = "안녕",
        hour = hour,
        minute = minute,
        days = days,
        enabled = enabled
    )

    @Test
    fun dailyLaterToday() {
        val next = ScheduleCalc.nextRun(task(8, 0), mondayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, ScheduleCalc.ZONE), next)
    }

    @Test
    fun dailyEarlierGoesToTomorrow() {
        val next = ScheduleCalc.nextRun(task(6, 0), mondayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 3, 6, 0, 0, 0, ScheduleCalc.ZONE), next)
    }

    @Test
    fun exactlyNowIsStrictlyAfter() {
        val next = ScheduleCalc.nextRun(task(7, 0), mondayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 3, 7, 0, 0, 0, ScheduleCalc.ZONE), next)
    }

    @Test
    fun weekdaysSkipWeekend() {
        val friday = ZonedDateTime.of(2026, 11, 6, 7, 30, 0, 0, ScheduleCalc.ZONE)
        val next = ScheduleCalc.nextRun(task(7, 0, days = setOf(1, 2, 3, 4, 5)), friday)
        assertEquals(ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, ScheduleCalc.ZONE), next)
    }

    @Test
    fun sundayOnly() {
        val next = ScheduleCalc.nextRun(task(8, 0, days = setOf(7)), mondayMorning)
        assertEquals(ZonedDateTime.of(2026, 11, 8, 8, 0, 0, 0, ScheduleCalc.ZONE), next)
    }

    @Test
    fun disabledReturnsNull() {
        assertNull(ScheduleCalc.nextRun(task(8, 0, enabled = false), mondayMorning))
    }

    @Test
    fun emptyDaysReturnsNull() {
        assertNull(ScheduleCalc.nextRun(task(8, 0, days = emptySet()), mondayMorning))
    }

    @Test
    fun describeAllDays() {
        assertEquals("매일 08:00", ScheduleCalc.describe(task(8, 0)))
    }

    @Test
    fun describeWeekdays() {
        assertEquals("평일 07:30", ScheduleCalc.describe(task(7, 30, days = setOf(1, 2, 3, 4, 5))))
    }

    @Test
    fun describeWeekend() {
        assertEquals("주말 10:00", ScheduleCalc.describe(task(10, 0, days = setOf(6, 7))))
    }

    @Test
    fun describeCustomDays() {
        assertEquals("월·수·금 18:30", ScheduleCalc.describe(task(18, 30, days = setOf(1, 3, 5))))
    }

    @Test
    fun formatWhenMonday() {
        val whenTime = ZonedDateTime.of(2026, 11, 2, 8, 0, 0, 0, ScheduleCalc.ZONE)
        assertEquals("11월 2일 (월) 08:00", ScheduleCalc.formatWhen(whenTime))
    }

    @Test
    fun utcNowConvertedToKorea() {
        val utc = ZonedDateTime.of(2026, 11, 1, 23, 0, 0, 0, ZoneOffset.UTC)
        val next = ScheduleCalc.nextRun(task(8, 0), utc)
        // 2026-11-01T23:00Z is exactly 2026-11-02 08:00 KST, so the next run is tomorrow.
        assertEquals(ZonedDateTime.of(2026, 11, 3, 8, 0, 0, 0, ScheduleCalc.ZONE), next)
    }
}
