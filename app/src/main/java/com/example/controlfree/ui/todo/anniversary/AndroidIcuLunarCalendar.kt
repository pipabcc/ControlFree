package com.example.controlfree.ui.todo.anniversary

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone as IcuTimeZone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class AndroidIcuLunarCalendar : LunarCalendarConverter {
    override fun toInstant(parts: AnniversaryDateTimeParts, zoneId: ZoneId): Instant {
        val calendar = newCalendar(zoneId).apply {
            isLenient = false
            clear()
            set(Calendar.EXTENDED_YEAR, extendedYear(parts.year, zoneId))
            set(Calendar.MONTH, parts.month - 1)
            set(Calendar.IS_LEAP_MONTH, if (parts.isLeapMonth) 1 else 0)
            set(Calendar.DAY_OF_MONTH, parts.day)
            set(Calendar.HOUR_OF_DAY, parts.hour)
            set(Calendar.MINUTE, parts.minute)
            set(Calendar.SECOND, parts.second)
            set(Calendar.MILLISECOND, 0)
        }
        val instant = Instant.ofEpochMilli(calendar.timeInMillis)
        val roundTrip = fromInstant(instant, zoneId)
        require(roundTrip == parts) { "无效或不存在的农历日期" }
        return instant
    }

    override fun fromInstant(instant: Instant, zoneId: ZoneId): AnniversaryDateTimeParts {
        val calendar = newCalendar(zoneId).apply { timeInMillis = instant.toEpochMilli() }
        val gregorianYear = instant.atZone(zoneId).year
        val nominalYear = gregorianYear + (
            calendar.get(Calendar.EXTENDED_YEAR) - extendedYear(gregorianYear, zoneId)
            )
        return AnniversaryDateTimeParts(
            year = nominalYear,
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH),
            hour = calendar.get(Calendar.HOUR_OF_DAY),
            minute = calendar.get(Calendar.MINUTE),
            second = calendar.get(Calendar.SECOND),
            isLeapMonth = calendar.get(Calendar.IS_LEAP_MONTH) == 1
        )
    }

    private fun extendedYear(nominalYear: Int, zoneId: ZoneId): Int {
        val reference = LocalDate.of(nominalYear, 7, 1)
            .atTime(LocalTime.NOON)
            .atZone(zoneId)
            .toInstant()
        return newCalendar(zoneId).apply { timeInMillis = reference.toEpochMilli() }
            .get(Calendar.EXTENDED_YEAR)
    }

    private fun newCalendar(zoneId: ZoneId): ChineseCalendar =
        ChineseCalendar(IcuTimeZone.getTimeZone(zoneId.id))
}
