package com.example.controlfree.productivity.calendar

import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class CalendarEventType {
    PLANNED_TODO,
    COMPLETED_TODO,
    HABIT_DUE,
    HABIT_COMPLETED,
    ANNIVERSARY,
    FOCUS,
    SUPERVISION,
    LEDGER
}

data class LedgerCalendarEventDetails(
    val amountFen: Long,
    val direction: LedgerDirection,
    val category: LedgerCategory,
    val note: String?
) {
    init {
        require(LedgerAmountCodec.isValidFen(amountFen))
        require(category.direction == direction)
    }
}

data class SupervisionCalendarEventDetails(
    val kind: SupervisionSessionKind,
    val packageName: String?,
    val endReason: SupervisionSessionEndReason?
) {
    init {
        require(kind != SupervisionSessionKind.MANUAL_FOCUS)
        require(kind != SupervisionSessionKind.SCHEDULED_FOCUS)
    }
}

data class ProductivityCalendarEvent(
    val id: String,
    val title: String,
    val type: CalendarEventType,
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null,
    val ledgerDetails: LedgerCalendarEventDetails? = null,
    val supervisionDetails: SupervisionCalendarEventDetails? = null
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(startEpochMillis >= 0L)
        require(endEpochMillis == null || endEpochMillis >= startEpochMillis)
        require((type == CalendarEventType.LEDGER) == (ledgerDetails != null))
        require((type == CalendarEventType.SUPERVISION) == (supervisionDetails != null))
    }
}

data class CalendarDateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate
) {
    init {
        require(!endDateInclusive.isBefore(startDate))
    }

    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDateInclusive)

    fun startEpochMillis(zoneId: ZoneId): Long =
        startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

    fun endExclusiveEpochMillis(zoneId: ZoneId): Long =
        endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

    companion object {
        fun month(month: YearMonth): CalendarDateRange =
            CalendarDateRange(month.atDay(1), month.atEndOfMonth())

        fun monthGrid(
            month: YearMonth,
            firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
        ): CalendarDateRange {
            val firstCell = month.atDay(1)
                .with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val gridEnd = firstCell.plusDays(DEFAULT_MONTH_GRID_DAYS - 1L)
            return CalendarDateRange(
                startDate = firstCell,
                endDateInclusive = maxOf(gridEnd, month.atEndOfMonth().plusDays(2L))
            )
        }

        private const val DEFAULT_MONTH_GRID_DAYS = 42L
    }
}

data class CalendarDaySummary(
    val date: LocalDate,
    val events: List<ProductivityCalendarEvent>,
    val focusMillis: Long,
    val supervisionMillis: Long = 0L
) {
    val plannedTodos: Int get() = events.count { it.type == CalendarEventType.PLANNED_TODO }
    val completedTodos: Int get() = events.count { it.type == CalendarEventType.COMPLETED_TODO }
    val dueHabits: Int get() = events.count { it.type == CalendarEventType.HABIT_DUE }
    val completedHabits: Int get() = events.count { it.type == CalendarEventType.HABIT_COMPLETED }
    val anniversaryCount: Int get() = events.count { it.type == CalendarEventType.ANNIVERSARY }
    val focusCount: Int get() = events.count { it.type == CalendarEventType.FOCUS }
    val supervisionCount: Int get() = events.count { it.type == CalendarEventType.SUPERVISION }
    val ledgerCount: Int get() = events.count { it.type == CalendarEventType.LEDGER }
    val ledgerIncomeFen: Long
        get() = ledgerAmountFen(LedgerDirection.INCOME)
    val ledgerExpenseFen: Long
        get() = ledgerAmountFen(LedgerDirection.EXPENSE)

    private fun ledgerAmountFen(direction: LedgerDirection): Long = events.asSequence()
        .mapNotNull(ProductivityCalendarEvent::ledgerDetails)
        .filter { details -> details.direction == direction }
        .sumOf(LedgerCalendarEventDetails::amountFen)
}

data class CalendarRangeStatistics(
    val plannedTodos: Int,
    val completedTodos: Int,
    val dueHabits: Int,
    val completedHabits: Int,
    val anniversaryCount: Int,
    val focusCount: Int,
    val focusMillis: Long,
    val supervisionCount: Int,
    val supervisionMillis: Long,
    val ledgerCount: Int,
    val ledgerIncomeFen: Long,
    val ledgerExpenseFen: Long
) {
    val todoCount: Int get() = plannedTodos + completedTodos
}

object UnifiedCalendarAggregator {
    fun summarize(
        month: YearMonth,
        events: Collection<ProductivityCalendarEvent>,
        zoneId: ZoneId,
        activeSessionEndEpochMillis: Long
    ): Map<LocalDate, CalendarDaySummary> = summarize(
        range = CalendarDateRange.month(month),
        events = events,
        zoneId = zoneId,
        activeSessionEndEpochMillis = activeSessionEndEpochMillis
    )

    fun summarize(
        range: CalendarDateRange,
        events: Collection<ProductivityCalendarEvent>,
        zoneId: ZoneId,
        activeSessionEndEpochMillis: Long
    ): Map<LocalDate, CalendarDaySummary> {
        require(activeSessionEndEpochMillis >= 0L)
        val eventsByDay = linkedMapOf<LocalDate, MutableList<ProductivityCalendarEvent>>()
        val focusByDay = mutableMapOf<LocalDate, Long>()
        val supervisionByDay = mutableMapOf<LocalDate, Long>()

        events.forEach { event ->
            val start = Instant.ofEpochMilli(event.startEpochMillis).atZone(zoneId)
            val rawEndMillis = event.endEpochMillis ?: if (event.type.hasTrackedDuration) {
                activeSessionEndEpochMillis.coerceAtLeast(event.startEpochMillis)
            } else {
                event.startEpochMillis
            }
            val end = Instant.ofEpochMilli(rawEndMillis).atZone(zoneId)
            val eventDate = start.toLocalDate()
            if (eventDate in range) {
                eventsByDay.getOrPut(eventDate) { mutableListOf() } += event
            }

            if (event.type.hasTrackedDuration && end.isAfter(start)) {
                var date = start.toLocalDate()
                while (!date.isAfter(end.toLocalDate())) {
                    if (date in range) {
                        val dayStart = date.atStartOfDay(zoneId)
                        val dayEnd = date.plusDays(1).atStartOfDay(zoneId)
                        val clippedStart = if (start.isAfter(dayStart)) start else dayStart
                        val clippedEnd = if (end.isBefore(dayEnd)) end else dayEnd
                        val millis = (clippedEnd.toInstant().toEpochMilli() - clippedStart.toInstant().toEpochMilli())
                            .coerceAtLeast(0L)
                        val durationByDay = if (event.type == CalendarEventType.FOCUS) {
                            focusByDay
                        } else {
                            supervisionByDay
                        }
                        durationByDay[date] = (durationByDay[date] ?: 0L) + millis
                    }
                    date = date.plusDays(1)
                }
            }
        }

        return generateSequence(range.startDate) { date ->
            date.plusDays(1).takeUnless { it.isAfter(range.endDateInclusive) }
        }
            .associateWith { date ->
                CalendarDaySummary(
                    date = date,
                    events = eventsByDay[date].orEmpty().sortedWith(
                        compareBy(ProductivityCalendarEvent::startEpochMillis, ProductivityCalendarEvent::id)
                    ),
                    focusMillis = focusByDay[date] ?: 0L,
                    supervisionMillis = supervisionByDay[date] ?: 0L
                )
            }
    }

    fun statistics(
        summaries: Map<LocalDate, CalendarDaySummary>,
        range: CalendarDateRange
    ): CalendarRangeStatistics {
        val selectedDays = summaries.asSequence()
            .filter { (date, _) -> date in range }
            .map(Map.Entry<LocalDate, CalendarDaySummary>::value)
            .toList()
        return CalendarRangeStatistics(
            plannedTodos = selectedDays.sumOf(CalendarDaySummary::plannedTodos),
            completedTodos = selectedDays.sumOf(CalendarDaySummary::completedTodos),
            dueHabits = selectedDays.sumOf(CalendarDaySummary::dueHabits),
            completedHabits = selectedDays.sumOf(CalendarDaySummary::completedHabits),
            anniversaryCount = selectedDays.sumOf(CalendarDaySummary::anniversaryCount),
            focusCount = selectedDays.sumOf(CalendarDaySummary::focusCount),
            focusMillis = selectedDays.sumOf(CalendarDaySummary::focusMillis),
            supervisionCount = selectedDays.sumOf(CalendarDaySummary::supervisionCount),
            supervisionMillis = selectedDays.sumOf(CalendarDaySummary::supervisionMillis),
            ledgerCount = selectedDays.sumOf(CalendarDaySummary::ledgerCount),
            ledgerIncomeFen = selectedDays.sumOf(CalendarDaySummary::ledgerIncomeFen),
            ledgerExpenseFen = selectedDays.sumOf(CalendarDaySummary::ledgerExpenseFen)
        )
    }
}

private val CalendarEventType.hasTrackedDuration: Boolean
    get() = this == CalendarEventType.FOCUS || this == CalendarEventType.SUPERVISION
