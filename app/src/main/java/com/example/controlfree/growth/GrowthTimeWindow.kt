package com.example.controlfree.growth

import java.time.Instant
import java.time.ZoneId

data class GrowthDayWindow(
    val startEpochMillis: Long,
    val endExclusiveEpochMillis: Long
) {
    init {
        require(startEpochMillis >= 0L)
        require(endExclusiveEpochMillis > startEpochMillis)
    }

    fun contains(epochMillis: Long): Boolean =
        epochMillis in startEpochMillis until endExclusiveEpochMillis

    companion object {
        fun containing(
            epochMillis: Long,
            zoneId: ZoneId = ZoneId.systemDefault()
        ): GrowthDayWindow {
            require(epochMillis >= 0L) { "时间不能为负数" }
            val localDate = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate()
            val start = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val end = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            return GrowthDayWindow(start, end)
        }
    }
}
