package com.example.controlfree.ui.todo.timeblock

import com.example.controlfree.ui.todo.todo.TodoEditorDraft
import com.example.controlfree.ui.todo.todo.TodoUrgencyMode
import java.time.LocalDate
import java.time.ZoneId

internal data class TimeBlockTodoEditorSession(
    val initialDraft: TodoEditorDraft,
    val initialEntry: TimeBlockEntry? = null
)

internal fun TimeBlockEntry.baseTodoId(): String =
    if (source == TimeBlockSource.TODO) id.substringBefore("_recur_") else id

internal fun TimeBlockEntry.focusTimerStableKey(): String =
    if (source == TimeBlockSource.TODO) "todo:${baseTodoId()}" else stableKey

internal fun todoDraftForDeadlineDate(
    date: LocalDate,
    zoneId: ZoneId
): TodoEditorDraft = TodoEditorDraft(
    dueAtEpochMillis = deadlineAtEndOfDay(date, zoneId)
)

internal fun todoDraftForSchedule(
    date: LocalDate,
    startMinute: Int,
    endMinuteExclusive: Int,
    zoneId: ZoneId
): TodoEditorDraft {
    require(startMinute in 0 until MINUTES_PER_DAY)
    require(endMinuteExclusive in (startMinute + 1)..MINUTES_PER_DAY)
    return TodoEditorDraft(
        scheduledStartEpochMillis = epochMillis(date, startMinute, zoneId),
        scheduledEndEpochMillis = epochMillis(date, endMinuteExclusive, zoneId),
        estimatedFocusMinutes = (endMinuteExclusive - startMinute).coerceIn(1, 180)
    )
}

internal fun TimeBlockEditorDraft.toTodoEditorDraft(zoneId: ZoneId): TodoEditorDraft {
    val schedule = editorType == TimeBlockEditorType.EVENT
    return TodoEditorDraft(
        id = id,
        title = title,
        description = description,
        dueAtEpochMillis = if (schedule) {
            null
        } else if (allDay) {
            deadlineAtEndOfDay(date, zoneId)
        } else {
            epochMillis(date, startMinute, zoneId)
        },
        scheduledStartEpochMillis = if (schedule) epochMillis(date, startMinute, zoneId) else null,
        scheduledEndEpochMillis = if (schedule) epochMillis(date, endMinuteExclusive, zoneId) else null,
        estimatedFocusMinutes = if (schedule) {
            (endMinuteExclusive - startMinute).coerceIn(1, 180)
        } else {
            com.example.controlfree.ui.todo.todo.DEFAULT_FOCUS_MINUTES
        },
        isImportant = highPriority,
        urgencyMode = if (highPriority) TodoUrgencyMode.URGENT else TodoUrgencyMode.AUTO,
        project = project
    )
}

private fun deadlineAtEndOfDay(date: LocalDate, zoneId: ZoneId): Long =
    date.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
