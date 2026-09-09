package com.example.controlfree.ui.todo.timeblock

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

enum class TimeBlockViewMode(val displayName: String) {
    TIMELINE("1日"),
    THREE_DAYS("3日"),
    WEEK("周"),
    MONTH("月")
}

enum class TimeBlockSource { TODO, EVENT }

enum class TimeBlockEntryKind {
    /** 独立 Event，拥有精确起止时间。 */
    EVENT,

    /** 已被排入时间段的待办。 */
    TIME_BLOCK_TASK,

    /** 只有截止时间的待办。 */
    DEADLINE_TASK,

    /** 截止时间位于当天最后一分钟的全天待办。 */
    ALL_DAY_TASK,

    /** 尚未排程且没有截止日期的待办。 */
    INBOX_TASK
}

data class TimeBlockEntry(
    val source: TimeBlockSource,
    val id: String,
    val title: String,
    val description: String? = null,
    val project: String,
    val priority: Int,
    val isCompleted: Boolean,
    val completedAtEpochMillis: Long? = null,
    val scheduledStartEpochMillis: Long? = null,
    val scheduledEndEpochMillis: Long? = null,
    val dueAtEpochMillis: Long? = null,
    val estimatedFocusMinutes: Int = 25,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(project.isNotBlank())
        require(priority in 0..3)
        require(
            scheduledStartEpochMillis == null && scheduledEndEpochMillis == null ||
                scheduledStartEpochMillis != null && scheduledEndEpochMillis != null &&
                scheduledEndEpochMillis > scheduledStartEpochMillis
        )
    }

    val stableKey: String
        get() = "${source.name.lowercase()}:$id"

    val isHighPriority: Boolean
        get() = priority >= 2

    fun kind(zoneId: ZoneId): TimeBlockEntryKind = when {
        source == TimeBlockSource.EVENT -> TimeBlockEntryKind.EVENT
        scheduledStartEpochMillis != null -> TimeBlockEntryKind.TIME_BLOCK_TASK
        dueAtEpochMillis == null -> TimeBlockEntryKind.INBOX_TASK
        dueAtEpochMillis.atZone(zoneId).toLocalTime().isEndOfDayDeadline() ->
            TimeBlockEntryKind.ALL_DAY_TASK
        else -> TimeBlockEntryKind.DEADLINE_TASK
    }

    fun primaryDate(zoneId: ZoneId): LocalDate? =
        (scheduledStartEpochMillis ?: dueAtEpochMillis)?.atZone(zoneId)?.toLocalDate()
}

data class TimeBlockFilter(
    val visibleProjects: Set<String> = emptySet(),
    val highPriorityOnly: Boolean = false,
    val hideCompleted: Boolean = false
) {
    fun accepts(entry: TimeBlockEntry): Boolean =
        (visibleProjects.isEmpty() || entry.project in visibleProjects) &&
            (!highPriorityOnly || entry.isHighPriority) &&
            (!hideCompleted || !entry.isCompleted)
}

data class TimeBlockPlacement(
    val entry: TimeBlockEntry,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val lane: Int,
    val laneCount: Int
)

data class TimeBlockDayProgress(
    val completed: Int,
    val total: Int
) {
    val ratio: Float
        get() = if (total == 0) 0f else completed.toFloat() / total
}

data class TimeBlockGap(
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY)
        require(endMinuteExclusive in 1..MINUTES_PER_DAY)
        require(endMinuteExclusive > startMinute)
    }

    val durationMinutes: Int
        get() = endMinuteExclusive - startMinute
}

data class TimeBlockCalendarLabel(
    val text: String,
    val isSolarTerm: Boolean
)

fun timeBlockCalendarLabel(date: LocalDate): TimeBlockCalendarLabel? {
    FIXED_CALENDAR_LABELS[MonthDay.from(date)]?.let {
        return TimeBlockCalendarLabel(it, isSolarTerm = false)
    }
    val firstTermIndex = (date.monthValue - 1) * 2
    for (termIndex in firstTermIndex..firstTermIndex + 1) {
        val termEpochMillis = SOLAR_TERM_BASE_EPOCH_MILLIS +
            (TROPICAL_YEAR_MILLIS * (date.year - SOLAR_TERM_BASE_YEAR)).roundToLong() +
            SOLAR_TERM_MINUTE_OFFSETS[termIndex] * MILLIS_PER_MINUTE
        val termDate = Instant.ofEpochMilli(termEpochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        if (termDate == date) {
            return TimeBlockCalendarLabel(SOLAR_TERM_NAMES[termIndex], isSolarTerm = true)
        }
    }
    return null
}

fun visibleDates(mode: TimeBlockViewMode, anchor: LocalDate): List<LocalDate> = when (mode) {
    TimeBlockViewMode.THREE_DAYS -> (0L..2L).map(anchor::plusDays)
    TimeBlockViewMode.WEEK -> {
        val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        (0L..6L).map(monday::plusDays)
    }
    TimeBlockViewMode.MONTH -> monthGridDates(YearMonth.from(anchor))
    TimeBlockViewMode.TIMELINE -> listOf(anchor)
}

fun navigateAnchor(
    anchor: LocalDate,
    mode: TimeBlockViewMode,
    direction: Int
): LocalDate {
    require(direction == -1 || direction == 1)
    return when (mode) {
        TimeBlockViewMode.THREE_DAYS -> anchor.plusDays(direction * 3L)
        TimeBlockViewMode.WEEK -> anchor.plusWeeks(direction.toLong())
        TimeBlockViewMode.MONTH -> anchor.shiftMonthClamped(direction.toLong())
        TimeBlockViewMode.TIMELINE -> anchor.plusDays(direction.toLong())
    }
}

fun monthGridDates(month: YearMonth): List<LocalDate> {
    val firstCell = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0L until MONTH_GRID_CELL_COUNT).map(firstCell::plusDays)
}

fun entriesOnDate(
    entries: List<TimeBlockEntry>,
    date: LocalDate,
    zoneId: ZoneId,
    filter: TimeBlockFilter = TimeBlockFilter()
): List<TimeBlockEntry> = entries.asSequence()
    .filter(filter::accepts)
    .filter { entry ->
        val start = entry.scheduledStartEpochMillis?.let(Instant::ofEpochMilli)
        val end = entry.scheduledEndEpochMillis?.let(Instant::ofEpochMilli)
        if (start != null && end != null) {
            val dayStart = date.atStartOfDay(zoneId).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
            start < dayEnd && end > dayStart
        } else {
            entry.dueAtEpochMillis?.atZone(zoneId)?.toLocalDate() == date
        }
    }
    .sortedWith(
        compareBy<TimeBlockEntry> { it.isCompleted }
            .thenBy { it.scheduledStartEpochMillis ?: it.dueAtEpochMillis ?: Long.MAX_VALUE }
            .thenByDescending(TimeBlockEntry::priority)
            .thenBy(TimeBlockEntry::stableKey)
    )
    .toList()

fun dayProgress(entries: List<TimeBlockEntry>): TimeBlockDayProgress =
    TimeBlockDayProgress(
        completed = entries.count(TimeBlockEntry::isCompleted),
        total = entries.size
    )

fun timedPlacements(
    entries: List<TimeBlockEntry>,
    dates: List<LocalDate>,
    zoneId: ZoneId
): List<TimeBlockPlacement> = dates.flatMap { date ->
    val dayStart = date.atStartOfDay(zoneId).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val segments = entries.mapNotNull { entry ->
        val rawStart = entry.scheduledStartEpochMillis?.let(Instant::ofEpochMilli)
            ?: return@mapNotNull null
        val rawEnd = entry.scheduledEndEpochMillis?.let(Instant::ofEpochMilli)
            ?: return@mapNotNull null
        if (rawStart >= dayEnd || rawEnd <= dayStart) return@mapNotNull null
        val clippedStart = maxOf(rawStart, dayStart)
        val clippedEnd = minOf(rawEnd, dayEnd)
        PlacementSegment(
            entry = entry,
            startMinute = clippedStart.minuteOfDay(zoneId, dayStart, 0),
            endMinuteExclusive = clippedEnd.minuteOfDay(zoneId, dayEnd, MINUTES_PER_DAY)
        )
    }.sortedWith(
        compareBy<PlacementSegment>(PlacementSegment::startMinute)
            .thenBy(PlacementSegment::endMinuteExclusive)
            .thenBy { it.entry.stableKey }
    )
    allocateLanes(date, segments)
}

fun freeGaps(
    date: LocalDate,
    entries: List<TimeBlockEntry>,
    zoneId: ZoneId,
    windowStartMinute: Int = 8 * 60,
    windowEndMinute: Int = 22 * 60,
    minimumGapMinutes: Int = 30,
    nowEpochMillis: Long? = null
): List<TimeBlockGap> {
    require(windowStartMinute in 0 until windowEndMinute)
    require(windowEndMinute <= MINUTES_PER_DAY)
    require(minimumGapMinutes > 0)
    val today = nowEpochMillis?.atZone(zoneId)?.toLocalDate()
    val nowMinute = nowEpochMillis?.atZone(zoneId)?.toLocalTime()?.toSecondOfDay()?.div(60)
    val effectiveStart = if (date == today && nowMinute != null) {
        maxOf(windowStartMinute, snapMinute(nowMinute, roundUp = true))
    } else {
        windowStartMinute
    }
    val occupied = timedPlacements(entries, listOf(date), zoneId)
        .map { maxOf(effectiveStart, it.startMinute)..<minOf(windowEndMinute, it.endMinuteExclusive) }
        .filterNot(IntRange::isEmpty)
        .sortedBy(IntRange::first)
    val merged = mutableListOf<IntRange>()
    occupied.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.first > previous.last + 1) {
            merged += range
        } else {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        }
    }
    val result = mutableListOf<TimeBlockGap>()
    var cursor = effectiveStart
    merged.forEach { range ->
        if (range.first - cursor >= minimumGapMinutes) {
            result += TimeBlockGap(date, cursor, range.first)
        }
        cursor = maxOf(cursor, range.last + 1)
    }
    if (windowEndMinute - cursor >= minimumGapMinutes) {
        result += TimeBlockGap(date, cursor, windowEndMinute)
    }
    return result
}

fun snapMinute(minute: Int, roundUp: Boolean = false): Int {
    val safe = minute.coerceIn(0, MINUTES_PER_DAY)
    val remainder = safe % TIME_SNAP_MINUTES
    if (remainder == 0) return safe
    return if (roundUp) {
        (safe + TIME_SNAP_MINUTES - remainder).coerceAtMost(MINUTES_PER_DAY)
    } else {
        safe - remainder
    }
}

fun isExpired(
    entry: TimeBlockEntry,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Boolean {
    if (entry.source != TimeBlockSource.TODO || entry.isCompleted) return false
    val entryDate = entry.primaryDate(zoneId) ?: return false
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    return entryDate < today
}

private fun allocateLanes(
    date: LocalDate,
    segments: List<PlacementSegment>
): List<TimeBlockPlacement> {
    if (segments.isEmpty()) return emptyList()
    val result = mutableListOf<TimeBlockPlacement>()
    var clusterStart = 0
    while (clusterStart < segments.size) {
        var clusterEnd = clusterStart + 1
        var latestEnd = segments[clusterStart].endMinuteExclusive
        while (clusterEnd < segments.size && segments[clusterEnd].startMinute < latestEnd) {
            latestEnd = maxOf(latestEnd, segments[clusterEnd].endMinuteExclusive)
            clusterEnd++
        }
        val laneEnds = mutableListOf<Int>()
        val assigned = IntArray(clusterEnd - clusterStart)
        for (index in clusterStart until clusterEnd) {
            val segment = segments[index]
            val reusable = laneEnds.indexOfFirst { it <= segment.startMinute }
            val lane = if (reusable >= 0) reusable else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = segment.endMinuteExclusive
            assigned[index - clusterStart] = lane
        }
        val laneCount = laneEnds.size
        for (index in clusterStart until clusterEnd) {
            val segment = segments[index]
            result += TimeBlockPlacement(
                entry = segment.entry,
                date = date,
                startMinute = segment.startMinute,
                endMinuteExclusive = segment.endMinuteExclusive,
                lane = assigned[index - clusterStart],
                laneCount = laneCount
            )
        }
        clusterStart = clusterEnd
    }
    return result
}

private data class PlacementSegment(
    val entry: TimeBlockEntry,
    val startMinute: Int,
    val endMinuteExclusive: Int
)

private fun Long.atZone(zoneId: ZoneId) = Instant.ofEpochMilli(this).atZone(zoneId)

private fun Instant.minuteOfDay(zoneId: ZoneId, boundary: Instant, boundaryValue: Int): Int =
    if (this == boundary) boundaryValue else atZone(zoneId).toLocalTime().toSecondOfDay() / 60

private fun LocalTime.isEndOfDayDeadline(): Boolean = hour == 23 && minute >= 59

private fun LocalDate.shiftMonthClamped(months: Long): LocalDate {
    val target = YearMonth.from(this).plusMonths(months)
    return target.atDay(dayOfMonth.coerceAtMost(target.lengthOfMonth()))
}

fun epochMillis(date: LocalDate, minuteOfDay: Int, zoneId: ZoneId): Long {
    require(minuteOfDay in 0..MINUTES_PER_DAY)
    return if (minuteOfDay == MINUTES_PER_DAY) {
        date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    } else {
        LocalDateTime.of(date, LocalTime.ofSecondOfDay(minuteOfDay * 60L))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}

const val TIME_SNAP_MINUTES = 30
const val MINUTES_PER_DAY = 24 * 60
private const val MONTH_GRID_CELL_COUNT = 42L
private const val SOLAR_TERM_BASE_YEAR = 1900
private const val SOLAR_TERM_BASE_EPOCH_MILLIS = -2_208_549_300_000L
private const val TROPICAL_YEAR_MILLIS = 31_556_925_974.7
private const val MILLIS_PER_MINUTE = 60_000L

private val FIXED_CALENDAR_LABELS = mapOf(
    MonthDay.of(1, 1) to "元旦",
    MonthDay.of(5, 1) to "劳动节",
    MonthDay.of(7, 1) to "建党节",
    MonthDay.of(8, 1) to "建军节",
    MonthDay.of(10, 1) to "国庆节"
)

private val SOLAR_TERM_NAMES = listOf(
    "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
    "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
    "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
    "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
)

private val SOLAR_TERM_MINUTE_OFFSETS = listOf(
    0L, 21_208L, 42_467L, 63_836L, 85_337L, 107_014L,
    128_867L, 150_921L, 173_149L, 195_551L, 218_072L, 240_693L,
    263_343L, 285_989L, 308_563L, 331_033L, 353_350L, 375_494L,
    397_447L, 419_210L, 440_795L, 462_224L, 483_532L, 504_758L
)
