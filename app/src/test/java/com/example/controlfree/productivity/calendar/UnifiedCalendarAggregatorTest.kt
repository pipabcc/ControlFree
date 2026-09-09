package com.example.controlfree.productivity.calendar

import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.persistence.SupervisionSessionEntity
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryEntity
import com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarEventFactory
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedCalendarAggregatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `跨午夜专注按每天边界裁剪`() {
        val start = ZonedDateTime.of(2026, 7, 22, 23, 30, 0, 0, zone)
        val end = start.plusHours(2)
        val summaries = UnifiedCalendarAggregator.summarize(
            month = YearMonth.of(2026, 7),
            events = listOf(
                ProductivityCalendarEvent(
                    id = "focus-1",
                    title = "夜间专注",
                    type = CalendarEventType.FOCUS,
                    startEpochMillis = start.toInstant().toEpochMilli(),
                    endEpochMillis = end.toInstant().toEpochMilli()
                )
            ),
            zoneId = zone,
            activeSessionEndEpochMillis = end.toInstant().toEpochMilli()
        )

        assertEquals(30L * 60_000L, summaries.getValue(LocalDate.of(2026, 7, 22)).focusMillis)
        assertEquals(90L * 60_000L, summaries.getValue(LocalDate.of(2026, 7, 23)).focusMillis)
    }

    @Test
    fun `计划与完成待办分别计数`() {
        val time = ZonedDateTime.of(2026, 7, 22, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val summaries = UnifiedCalendarAggregator.summarize(
            month = YearMonth.of(2026, 7),
            events = listOf(
                ProductivityCalendarEvent("todo-plan", "报告", CalendarEventType.PLANNED_TODO, time),
                ProductivityCalendarEvent("todo-done", "阅读", CalendarEventType.COMPLETED_TODO, time)
            ),
            zoneId = zone,
            activeSessionEndEpochMillis = time
        )
        val day = summaries.getValue(LocalDate.of(2026, 7, 22))

        assertEquals(1, day.plannedTodos)
        assertEquals(1, day.completedTodos)
    }

    @Test
    fun `监督历史与专注历史按会话种类正确分类`() {
        val start = ZonedDateTime.of(2026, 7, 22, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sessions = listOf(
            session("manual-global", SupervisionSessionKind.MANUAL_GLOBAL, start, 120),
            session("scheduled-global", SupervisionSessionKind.SCHEDULED_GLOBAL, start, 120),
            session("app-supervision", SupervisionSessionKind.APP, start, 120),
            session("manual-focus", SupervisionSessionKind.MANUAL_FOCUS, start, 30),
            session("scheduled-focus", SupervisionSessionKind.SCHEDULED_FOCUS, start, 30)
        )

        val events = UnifiedCalendarEventFactory.create(
            month = YearMonth.of(2026, 7),
            todos = emptyList(),
            habits = emptyList(),
            habitRecords = emptyList(),
            anniversaries = emptyList(),
            sessions = sessions,
            zoneId = zone
        )
        val summaries = UnifiedCalendarAggregator.summarize(
            month = YearMonth.of(2026, 7),
            events = events,
            zoneId = zone,
            activeSessionEndEpochMillis = start + 2 * 60 * 60_000L
        )

        assertEquals(
            setOf(
                "supervision:manual-global",
                "supervision:scheduled-global",
                "supervision:app-supervision",
                "focus:manual-focus",
                "focus:scheduled-focus"
            ),
            events.mapTo(linkedSetOf()) { it.id }
        )
        assertEquals(2, events.count { it.type == CalendarEventType.FOCUS })
        assertEquals(3, events.count { it.type == CalendarEventType.SUPERVISION })
        assertEquals(
            SupervisionSessionKind.APP,
            events.single { it.id == "supervision:app-supervision" }.supervisionDetails?.kind
        )
        val day = summaries.getValue(LocalDate.of(2026, 7, 22))
        assertEquals(2, day.focusCount)
        assertEquals(3, day.supervisionCount)
        assertEquals(60L * 60_000L, day.focusMillis)
        assertEquals(360L * 60_000L, day.supervisionMillis)
    }

    @Test
    fun `跨午夜监督按每天边界裁剪且跨月网格保留相邻月数据`() {
        val start = ZonedDateTime.of(2026, 7, 31, 23, 30, 0, 0, zone)
        val end = start.plusHours(2)
        val range = CalendarDateRange.monthGrid(YearMonth.of(2026, 8))
        val events = UnifiedCalendarEventFactory.create(
            range = range,
            todos = emptyList(),
            habits = emptyList(),
            habitRecords = emptyList(),
            anniversaries = emptyList(),
            sessions = listOf(
                session(
                    id = "cross-month-supervision",
                    kind = SupervisionSessionKind.MANUAL_GLOBAL,
                    startedAt = start.toInstant().toEpochMilli(),
                    durationMinutes = 120
                )
            ),
            ledgerEntries = emptyList(),
            zoneId = zone
        )

        val summaries = UnifiedCalendarAggregator.summarize(
            range = range,
            events = events,
            zoneId = zone,
            activeSessionEndEpochMillis = end.toInstant().toEpochMilli()
        )

        assertEquals(LocalDate.of(2026, 7, 27), range.startDate)
        assertEquals(LocalDate.of(2026, 9, 6), range.endDateInclusive)
        assertEquals(42, summaries.size)
        assertEquals(30L * 60_000L, summaries.getValue(LocalDate.of(2026, 7, 31)).supervisionMillis)
        assertEquals(90L * 60_000L, summaries.getValue(LocalDate.of(2026, 8, 1)).supervisionMillis)
        assertEquals(1, summaries.getValue(LocalDate.of(2026, 7, 31)).supervisionCount)
        assertEquals(0, summaries.getValue(LocalDate.of(2026, 8, 1)).supervisionCount)
    }

    @Test
    fun `账本事件保留日期金额收支和分类且跳过损坏记录`() {
        val occurredAt = ZonedDateTime.of(2026, 8, 1, 0, 15, 0, 0, zone)
            .toInstant().toEpochMilli()
        val events = UnifiedCalendarEventFactory.create(
            month = YearMonth.of(2026, 8),
            todos = emptyList(),
            habits = emptyList(),
            habitRecords = emptyList(),
            anniversaries = emptyList(),
            sessions = emptyList(),
            zoneId = zone,
            ledgerEntries = listOf(
                ledgerEntry("valid", occurredAt, 1_234L, "EXPENSE", "FOOD"),
                ledgerEntry("bad-direction", occurredAt, 2_000L, "BROKEN", "FOOD"),
                ledgerEntry("mismatch", occurredAt, 3_000L, "INCOME", "FOOD"),
                ledgerEntry("bad-amount", occurredAt, 0L, "EXPENSE", "FOOD")
            )
        )

        val event = events.single()
        val details = requireNotNull(event.ledgerDetails)
        assertEquals("ledger:valid", event.id)
        assertEquals(CalendarEventType.LEDGER, event.type)
        assertEquals(1_234L, details.amountFen)
        assertEquals(LedgerDirection.EXPENSE, details.direction)
        assertEquals(LedgerCategory.FOOD, details.category)

        val summaries = UnifiedCalendarAggregator.summarize(
            month = YearMonth.of(2026, 8),
            events = events,
            zoneId = zone,
            activeSessionEndEpochMillis = occurredAt
        )
        val day = summaries.getValue(LocalDate.of(2026, 8, 1))
        assertEquals(1, day.ledgerCount)
        assertEquals(1_234L, day.ledgerExpenseFen)
        assertEquals(0L, day.ledgerIncomeFen)
    }

    @Test
    fun `范围统计汇总六类事件并排除范围外日期`() {
        val firstDayAt = ZonedDateTime.of(2026, 8, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val secondDayAt = ZonedDateTime.of(2026, 8, 4, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val events = listOf(
            ProductivityCalendarEvent("todo-plan", "计划", CalendarEventType.PLANNED_TODO, firstDayAt),
            ProductivityCalendarEvent("habit-done", "习惯", CalendarEventType.HABIT_COMPLETED, firstDayAt),
            ProductivityCalendarEvent("moment", "时刻", CalendarEventType.ANNIVERSARY, firstDayAt),
            ProductivityCalendarEvent(
                "focus",
                "专注",
                CalendarEventType.FOCUS,
                firstDayAt,
                firstDayAt + 30L * 60_000L
            ),
            ProductivityCalendarEvent(
                id = "supervision",
                title = "监督",
                type = CalendarEventType.SUPERVISION,
                startEpochMillis = firstDayAt,
                endEpochMillis = firstDayAt + 60L * 60_000L,
                supervisionDetails = SupervisionCalendarEventDetails(
                    kind = SupervisionSessionKind.MANUAL_GLOBAL,
                    packageName = null,
                    endReason = null
                )
            ),
            ProductivityCalendarEvent(
                id = "ledger-income",
                title = "工资",
                type = CalendarEventType.LEDGER,
                startEpochMillis = firstDayAt,
                ledgerDetails = LedgerCalendarEventDetails(
                    amountFen = 500_000L,
                    direction = LedgerDirection.INCOME,
                    category = LedgerCategory.SALARY,
                    note = null
                )
            ),
            ProductivityCalendarEvent("outside", "范围外待办", CalendarEventType.PLANNED_TODO, secondDayAt)
        )
        val summaries = UnifiedCalendarAggregator.summarize(
            range = CalendarDateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 4)),
            events = events,
            zoneId = zone,
            activeSessionEndEpochMillis = secondDayAt
        )

        val statistics = UnifiedCalendarAggregator.statistics(
            summaries = summaries,
            range = CalendarDateRange(LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 3))
        )

        assertEquals(1, statistics.todoCount)
        assertEquals(1, statistics.completedHabits)
        assertEquals(1, statistics.anniversaryCount)
        assertEquals(1, statistics.focusCount)
        assertEquals(30L * 60_000L, statistics.focusMillis)
        assertEquals(1, statistics.supervisionCount)
        assertEquals(60L * 60_000L, statistics.supervisionMillis)
        assertEquals(1, statistics.ledgerCount)
        assertEquals(500_000L, statistics.ledgerIncomeFen)
        assertEquals(0L, statistics.ledgerExpenseFen)
    }

    private fun session(
        id: String,
        kind: SupervisionSessionKind,
        startedAt: Long,
        durationMinutes: Int
    ) = SupervisionSessionEntity(
        sessionId = id,
        identityKey = "identity:$id",
        runtimeSlot = null,
        sessionKind = kind.storedValue,
        displayName = id,
        planId = null,
        packageName = null,
        usageMinutes = null,
        lockMinutes = null,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = startedAt + durationMinutes * 60_000L,
        endReason = "completed",
        updatedAtEpochMillis = startedAt
    )

    private fun ledgerEntry(
        id: String,
        occurredAt: Long,
        amount: Long,
        direction: String,
        category: String
    ) = LedgerEntryEntity(
        id = id,
        amount = amount,
        direction = direction,
        category = category,
        title = "午餐",
        note = "工作餐",
        occurredAtEpochMillis = occurredAt,
        sourceNoteId = null,
        isEstimated = false,
        emotion = null,
        necessity = null,
        createdAtEpochMillis = occurredAt,
        updatedAtEpochMillis = occurredAt
    )
}
