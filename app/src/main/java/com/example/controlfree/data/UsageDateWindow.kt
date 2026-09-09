package com.example.controlfree.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class UsageDayRange(
    val date: LocalDate,
    val startMillis: Long,
    val endMillis: Long
) {
    init {
        require(endMillis >= startMillis) { "日期区间结束时间不能早于开始时间" }
    }

    val durationMillis: Long
        get() = endMillis - startMillis
}

internal data class UsageDateWindow(
    val queryStartMillis: Long,
    val queryEndMillis: Long,
    val days: List<UsageDayRange>
) {
    init {
        require(days.isNotEmpty()) { "统计日期不能为空" }
        require(queryEndMillis >= queryStartMillis) { "查询结束时间不能早于开始时间" }
        require(queryStartMillis <= days.first().startMillis) { "查询必须覆盖统计窗口起点" }
        require(queryEndMillis >= days.last().endMillis) { "查询必须覆盖统计窗口终点" }
        days.zipWithNext().forEach { (previous, next) ->
            require(previous.date < next.date) { "统计日期必须严格递增" }
            require(previous.endMillis <= next.startMillis) { "统计日期区间不能重叠" }
        }
    }
}

internal object UsageDateWindowFactory {
    private const val DAYS_TO_REPORT = 7L
    private const val LOOKBACK_DAYS = 1L

    fun create(
        nowMillis: Long,
        zoneId: ZoneId
    ): UsageDateWindow {
        require(nowMillis >= 0L) { "当前时间不能为负数" }
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val firstDate = today.minusDays(DAYS_TO_REPORT - 1L)
        val days = (0L until DAYS_TO_REPORT).map { offset ->
            val date = firstDate.plusDays(offset)
            val startMillis = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val naturalEndMillis = date.plusDays(1L)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            UsageDayRange(
                date = date,
                startMillis = startMillis,
                endMillis = if (date == today) nowMillis else naturalEndMillis
            )
        }
        val queryStartMillis = firstDate.minusDays(LOOKBACK_DAYS)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        return UsageDateWindow(
            queryStartMillis = queryStartMillis,
            queryEndMillis = nowMillis,
            days = days
        )
    }
}
