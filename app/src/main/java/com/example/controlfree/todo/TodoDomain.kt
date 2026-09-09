package com.example.controlfree.todo

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object TodoClassifier {
    fun timeGroup(
        todo: TodoItemEntity,
        now: Instant,
        zoneId: ZoneId
    ): TodoTimeGroup {
        val epochMillis = todo.scheduledStartEpochMillis ?: todo.dueDateEpochMillis
            ?: return TodoTimeGroup.NO_DATE
        val instant = Instant.ofEpochMilli(epochMillis)
        if (instant.isBefore(now)) return TodoTimeGroup.OVERDUE
        val date = instant.atZone(zoneId).toLocalDate()
        val today = now.atZone(zoneId).toLocalDate()
        return when {
            date == today -> TodoTimeGroup.TODAY
            date == today.plusDays(1) -> TodoTimeGroup.TOMORROW
            !date.isAfter(today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))) ->
                TodoTimeGroup.THIS_WEEK
            else -> TodoTimeGroup.FUTURE
        }
    }

    fun matrixQuadrant(
        todo: TodoItemEntity,
        now: Instant,
        autoUrgentWithinHours: Long = 24L
    ): TodoMatrixQuadrant {
        require(autoUrgentWithinHours >= 0L)
        val important = todo.isImportant || todo.priority >= 2
        val urgent = when (TodoUrgencyMode.fromStoredValue(todo.urgencyMode)) {
            TodoUrgencyMode.URGENT -> true
            TodoUrgencyMode.NOT_URGENT -> false
            TodoUrgencyMode.AUTO -> todo.dueDateEpochMillis?.let { due ->
                due <= now.plusSeconds(autoUrgentWithinHours * 3_600L).toEpochMilli()
            } ?: false
        }
        return when {
            important && urgent -> TodoMatrixQuadrant.IMPORTANT_URGENT
            important -> TodoMatrixQuadrant.IMPORTANT_NOT_URGENT
            urgent -> TodoMatrixQuadrant.NOT_IMPORTANT_URGENT
            else -> TodoMatrixQuadrant.NOT_IMPORTANT_NOT_URGENT
        }
    }
}

object TodoRecurrenceCalculator {
    fun nextOccurrence(
        todo: TodoItemEntity,
        completedAt: Instant,
        zoneId: ZoneId,
        nextId: String,
        createdAtEpochMillis: Long = completedAt.toEpochMilli()
    ): TodoItemEntity? {
        val recurrence = TodoRecurrenceType.fromStoredValue(todo.recurrenceType)
        if (recurrence == TodoRecurrenceType.NONE) return null
        val interval = todo.recurrenceInterval.coerceIn(1, MAX_RECURRENCE_INTERVAL)
        val sourceEpochMillis = todo.scheduledStartEpochMillis
            ?: todo.dueDateEpochMillis
            ?: completedAt.toEpochMilli()
        val source = Instant.ofEpochMilli(sourceEpochMillis).atZone(zoneId)
        val targetDate = nextDate(
            recurrence = recurrence,
            sourceDate = source.toLocalDate(),
            completedDate = completedAt.atZone(zoneId).toLocalDate(),
            interval = interval,
            daysMask = todo.recurrenceDaysMask,
            requestedDayOfMonth = todo.recurrenceDayOfMonth
        )
        val dayShift = targetDate.toEpochDay() - source.toLocalDate().toEpochDay()
        val seriesId = todo.recurrenceSeriesId ?: todo.id
        return todo.copy(
            id = nextId,
            dueDateEpochMillis = todo.dueDateEpochMillis.shiftDays(dayShift, zoneId),
            scheduledStartEpochMillis = todo.scheduledStartEpochMillis.shiftDays(dayShift, zoneId)
                ?: if (todo.scheduledStartEpochMillis == null && todo.dueDateEpochMillis == null) {
                    targetDate.atTime(source.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli()
                } else {
                    null
                },
            scheduledEndEpochMillis = todo.scheduledEndEpochMillis.shiftDays(dayShift, zoneId),
            isCompleted = false,
            completedAtEpochMillis = null,
            associatedFocusPlanId = null,
            recurrenceSeriesId = seriesId,
            recurrenceSequence = todo.recurrenceSequence + 1,
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis
        )
    }

    private fun nextDate(
        recurrence: TodoRecurrenceType,
        sourceDate: LocalDate,
        completedDate: LocalDate,
        interval: Int,
        daysMask: Int,
        requestedDayOfMonth: Int?
    ): LocalDate = when (recurrence) {
        TodoRecurrenceType.NONE -> sourceDate
        TodoRecurrenceType.DAILY -> sourceDate.plusDays(interval.toLong())
        TodoRecurrenceType.WEEKDAYS -> addWeekdays(sourceDate, interval)
        TodoRecurrenceType.WEEKLY_DAYS -> nextSelectedWeekday(sourceDate, daysMask, interval)
        TodoRecurrenceType.MONTHLY_DAY -> {
            val targetMonth = sourceDate.plusMonths(interval.toLong()).withDayOfMonth(1)
            targetMonth.withDayOfMonth(
                (requestedDayOfMonth ?: sourceDate.dayOfMonth)
                    .coerceIn(1, targetMonth.lengthOfMonth())
            )
        }
        TodoRecurrenceType.COMPLETION_INTERVAL -> completedDate.plusDays(interval.toLong())
    }

    private fun addWeekdays(date: LocalDate, count: Int): LocalDate {
        var remaining = count
        var candidate = date
        while (remaining > 0) {
            candidate = candidate.plusDays(1)
            if (candidate.dayOfWeek !in WEEKEND) remaining--
        }
        return candidate
    }

    private fun nextSelectedWeekday(date: LocalDate, mask: Int, intervalWeeks: Int): LocalDate {
        val effectiveMask = mask.takeIf { it and ALL_WEEKDAYS_MASK != 0 }
            ?: weekdayBit(date.dayOfWeek)
        val sourceWeekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        for (offset in 1..MAX_WEEKLY_SEARCH_DAYS) {
            val candidate = date.plusDays(offset.toLong())
            if (effectiveMask and weekdayBit(candidate.dayOfWeek) == 0) continue
            val candidateWeekStart = candidate.with(
                TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
            )
            val weekDelta = (candidateWeekStart.toEpochDay() - sourceWeekStart.toEpochDay()) / 7L
            if (weekDelta == 0L || weekDelta % intervalWeeks == 0L) return candidate
        }
        return date.plusWeeks(intervalWeeks.toLong())
    }

    private fun weekdayBit(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private const val MAX_RECURRENCE_INTERVAL = 366
    private const val MAX_WEEKLY_SEARCH_DAYS = 10 * 366
}

object TodoSubtaskTreeBuilder {
    fun build(subtasks: List<TodoSubtaskEntity>): List<TodoSubtaskNode> {
        if (subtasks.isEmpty()) return emptyList()
        val ordered = subtasks.distinctBy(TodoSubtaskEntity::id)
            .sortedWith(compareBy(TodoSubtaskEntity::sortOrder, TodoSubtaskEntity::createdAtEpochMillis, TodoSubtaskEntity::id))
        val byId = ordered.associateBy(TodoSubtaskEntity::id)
        val childrenByParent = ordered.groupBy(TodoSubtaskEntity::parentSubtaskId)
        val emitted = mutableSetOf<String>()

        fun node(item: TodoSubtaskEntity, path: Set<String>): TodoSubtaskNode {
            if (!emitted.add(item.id)) return TodoSubtaskNode(item, emptyList())
            val nextPath = path + item.id
            val children = childrenByParent[item.id].orEmpty()
                .filter { child -> child.id !in nextPath }
                .map { child -> node(child, nextPath) }
            return TodoSubtaskNode(item, children)
        }

        val roots = ordered.filter { item ->
            item.parentSubtaskId == null || item.parentSubtaskId !in byId
        }.map { node(it, emptySet()) }.toMutableList()

        // 损坏数据中的纯环没有自然根；提升为根并截断回边，保证所有项仍可操作。
        ordered.filter { it.id !in emitted }.forEach { roots += node(it, emptySet()) }
        return roots
    }

    fun descendantIds(subtasks: List<TodoSubtaskEntity>, rootId: String): Set<String> {
        val childrenByParent = subtasks.groupBy(TodoSubtaskEntity::parentSubtaskId)
        val result = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += rootId
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!result.add(current)) continue
            childrenByParent[current].orEmpty().forEach { pending += it.id }
        }
        return result
    }

    fun cloneForTodo(
        subtasks: List<TodoSubtaskEntity>,
        newTodoId: String,
        newIdFor: (TodoSubtaskEntity) -> String,
        nowEpochMillis: Long
    ): List<TodoSubtaskEntity> {
        val ids = subtasks.associate { it.id to newIdFor(it) }
        return subtasks.map { subtask ->
            subtask.copy(
                id = checkNotNull(ids[subtask.id]),
                todoId = newTodoId,
                parentSubtaskId = subtask.parentSubtaskId?.let(ids::get),
                isCompleted = false,
                completedAtEpochMillis = null,
                createdAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis
            )
        }
    }
}

private fun Long?.shiftDays(days: Long, zoneId: ZoneId): Long? = this?.let { value ->
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(value), zoneId)
        .plusDays(days)
        .toInstant()
        .toEpochMilli()
}
