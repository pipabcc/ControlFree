package com.example.controlfree.ui.todo.todo

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoUiModelsTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 7, 22)
    private val todayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

    @Test
    fun timeGroupingKeepsEveryBucket() {
        val todos = listOf(
            todo("overdue", due = todayStart - 1),
            todo("today", due = todayStart + 12 * HOUR),
            todo("tomorrow", due = todayStart + DAY + HOUR),
            todo("week", due = todayStart + 3 * DAY),
            todo("future", due = todayStart + 10 * DAY),
            todo("none")
        )

        val grouped = groupTodosByTime(todos, today, zoneId)

        assertEquals("overdue", grouped.getValue(TodoTimeBucket.OVERDUE).single().id)
        assertEquals("today", grouped.getValue(TodoTimeBucket.TODAY).single().id)
        assertEquals("tomorrow", grouped.getValue(TodoTimeBucket.TOMORROW).single().id)
        assertEquals("week", grouped.getValue(TodoTimeBucket.THIS_WEEK).single().id)
        assertEquals("future", grouped.getValue(TodoTimeBucket.FUTURE).single().id)
        assertEquals("none", grouped.getValue(TodoTimeBucket.NO_DATE).single().id)
    }

    @Test
    fun scheduledStartTakesPriorityOverLaterDeadlineWhenGrouping() {
        val scheduledToday = todo(
            id = "scheduled-today",
            due = todayStart + 10 * DAY,
            start = todayStart + 9 * HOUR,
            end = todayStart + 10 * HOUR
        )

        val grouped = groupTodosByTime(listOf(scheduledToday), today, zoneId)

        assertEquals("scheduled-today", grouped.getValue(TodoTimeBucket.TODAY).single().id)
    }

    @Test
    fun dayProgressUsesScheduleBeforeDeadlineAndExcludesOtherDates() {
        val tomorrowStart = todayStart + DAY
        val todos = listOf(
            todo(
                id = "scheduled-today",
                due = todayStart + 10 * DAY,
                start = todayStart + 9 * HOUR,
                end = todayStart + 10 * HOUR
            ),
            todo("due-today", due = todayStart + 12 * HOUR),
            todo(
                id = "scheduled-tomorrow",
                due = todayStart + 13 * HOUR,
                start = tomorrowStart + 9 * HOUR,
                end = tomorrowStart + 10 * HOUR
            ),
            todo("completed-today", due = todayStart + 14 * HOUR, completed = true),
            todo("undated")
        )

        val progress = calculateTodoDayProgress(todos, today, zoneId)

        assertEquals(3, progress.total)
        assertEquals(1, progress.completed)
        assertEquals(33, progress.percentage)
        assertEquals(1f / 3f, progress.fraction, 0.0001f)
    }

    @Test
    fun emptyDayProgressIsZero() {
        val progress = calculateTodoDayProgress(
            todos = listOf(todo("tomorrow", due = todayStart + DAY)),
            date = today,
            zoneId = zoneId
        )

        assertEquals(0, progress.total)
        assertEquals(0, progress.completed)
        assertEquals(0, progress.percentage)
        assertEquals(0f, progress.fraction, 0f)
    }

    @Test
    fun automaticUrgencyAndImportanceProduceExactQuadrants() {
        val now = todayStart + 9 * HOUR
        val importantSoon = todo(
            id = "important-soon",
            due = now + HOUR,
            important = true,
            urgency = TodoUrgencyMode.AUTO
        )
        val normalLater = todo(
            id = "normal-later",
            due = now + 3 * DAY,
            urgency = TodoUrgencyMode.AUTO
        )

        assertEquals(
            TodoMatrixQuadrant.IMPORTANT_URGENT,
            importantSoon.matrixQuadrant(now)
        )
        assertEquals(
            TodoMatrixQuadrant.NOT_IMPORTANT_NOT_URGENT,
            normalLater.matrixQuadrant(now)
        )
    }

    @Test
    fun defaultFilterKeepsAllTodosInSourceOrder() {
        val todos = listOf(
            todo("active"),
            todo("completed", completed = true)
        )

        val filtered = filterTodos(
            todos = todos,
            criteria = TodoFilterCriteria(),
            today = today,
            zoneId = zoneId,
            nowEpochMillis = todayStart + 9 * HOUR
        )

        assertEquals(listOf("active", "completed"), filtered.map(TodoItemUi::id))
    }

    @Test
    fun completionProjectAndImportanceFiltersUseIntersection() {
        val todos = listOf(
            todo("match", project = "工作", important = true),
            todo("completed", project = "工作", important = true, completed = true),
            todo("other-project", project = "生活", important = true),
            todo("not-important", project = "工作")
        )

        val filtered = filterTodos(
            todos = todos,
            criteria = TodoFilterCriteria(
                completion = TodoCompletionFilter.ACTIVE,
                project = "工作",
                importantOnly = true
            ),
            today = today,
            zoneId = zoneId,
            nowEpochMillis = todayStart + 9 * HOUR
        )

        assertEquals(listOf("match"), filtered.map(TodoItemUi::id))
    }

    @Test
    fun dateFiltersReuseTimeGroupingBoundaries() {
        val todos = listOf(
            todo("overdue", due = todayStart - HOUR),
            todo("today", due = todayStart + 12 * HOUR),
            todo("this-week", due = todayStart + 3 * DAY),
            todo("future", due = todayStart + 10 * DAY),
            todo("no-date")
        )

        fun ids(date: TodoDateFilter) = filterTodos(
            todos = todos,
            criteria = TodoFilterCriteria(date = date),
            today = today,
            zoneId = zoneId,
            nowEpochMillis = todayStart + 9 * HOUR
        ).map(TodoItemUi::id)

        assertEquals(listOf("overdue"), ids(TodoDateFilter.OVERDUE))
        assertEquals(listOf("today"), ids(TodoDateFilter.TODAY))
        assertEquals(listOf("today", "this-week"), ids(TodoDateFilter.THIS_WEEK))
        assertEquals(listOf("no-date"), ids(TodoDateFilter.NO_DATE))
    }

    @Test
    fun attributeFiltersCanBeCombined() {
        val scheduledStart = todayStart + 10 * HOUR
        val todos = listOf(
            todo(
                id = "match",
                start = scheduledStart,
                end = scheduledStart + HOUR,
                important = true,
                urgency = TodoUrgencyMode.URGENT
            ),
            todo("not-scheduled", important = true, urgency = TodoUrgencyMode.URGENT),
            todo(
                id = "not-important",
                start = scheduledStart,
                end = scheduledStart + HOUR,
                urgency = TodoUrgencyMode.URGENT
            )
        )

        val filtered = filterTodos(
            todos = todos,
            criteria = TodoFilterCriteria(
                importantOnly = true,
                urgentOnly = true,
                scheduledOnly = true
            ),
            today = today,
            zoneId = zoneId,
            nowEpochMillis = todayStart + 9 * HOUR
        )

        assertEquals(listOf("match"), filtered.map(TodoItemUi::id))
    }

    @Test
    fun subtaskTreeSupportsDepthAndCutsCycles() {
        val subtasks = listOf(
            subtask("root", parent = null, order = 1),
            subtask("child-b", parent = "root", order = 2),
            subtask("child-a", parent = "root", order = 1),
            subtask("grandchild", parent = "child-a", order = 0),
            subtask("cycle-a", parent = "cycle-b", order = 0),
            subtask("cycle-b", parent = "cycle-a", order = 0)
        )

        val forest = buildSubtaskForest(subtasks)
        val root = forest.first { it.item.id == "root" }

        assertEquals(listOf("child-a", "child-b"), root.children.map { it.item.id })
        assertEquals("grandchild", root.children.first().children.single().item.id)
        assertTrue(forest.any { it.item.id == "cycle-a" || it.item.id == "cycle-b" })
        assertEquals(subtasks.size, forest.flattenNodes().map { it.item.id }.distinct().size)
    }

    @Test
    fun editorDraftCopiesSubtasksWithoutLosingHierarchyOrCompletion() {
        val draft = todo("with-subtasks").toEditorDraft(
            listOf(
                TodoSubtaskUi(
                    id = "root",
                    todoId = "with-subtasks",
                    title = "根任务",
                    isCompleted = true,
                    sortOrder = 1
                ),
                TodoSubtaskUi(
                    id = "child",
                    todoId = "with-subtasks",
                    parentSubtaskId = "root",
                    title = "下级任务",
                    sortOrder = 2
                )
            )
        )

        assertEquals(listOf("root", "child"), draft.subtasks.map(TodoSubtaskDraft::id))
        assertTrue(draft.subtasks.first().isCompleted)
        assertEquals("root", draft.subtasks.last().parentSubtaskId)
        assertEquals(2, draft.subtasks.last().sortOrder)
    }

    @Test
    fun deletingSubtaskRemovesEveryDescendantOnly() {
        val subtasks = listOf(
            TodoSubtaskDraft(id = "root", title = "根任务"),
            TodoSubtaskDraft(id = "child", parentSubtaskId = "root", title = "子任务"),
            TodoSubtaskDraft(id = "grandchild", parentSubtaskId = "child", title = "孙任务"),
            TodoSubtaskDraft(id = "sibling", title = "同级任务")
        )

        val remaining = removeSubtaskBranch(subtasks, "root")

        assertEquals(listOf("sibling"), remaining.map(TodoSubtaskDraft::id))
    }

    @Test
    fun calendarSplitsCrossMidnightAndAllocatesOverlapLanes() {
        val dayOneStart = today.atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
        val dayTwoEnd = today.plusDays(1).atTime(1, 0).atZone(zoneId).toInstant().toEpochMilli()
        val overlapStart = today.atTime(23, 30).atZone(zoneId).toInstant().toEpochMilli()
        val overlapEnd = today.plusDays(1).atTime(0, 30).atZone(zoneId).toInstant().toEpochMilli()
        val placements = calendarPlacements(
            todos = listOf(
                todo("cross", start = dayOneStart, end = dayTwoEnd),
                todo("overlap", start = overlapStart, end = overlapEnd)
            ),
            dates = listOf(today, today.plusDays(1)),
            zoneId = zoneId
        )

        val firstDay = placements.filter { it.date == today }
        val secondDay = placements.filter { it.date == today.plusDays(1) }
        assertEquals(2, firstDay.size)
        assertEquals(2, firstDay.map { it.lane }.distinct().size)
        assertTrue(firstDay.all { it.laneCount == 2 })
        assertEquals(2, secondDay.size)
        assertTrue(secondDay.all { it.startMinute == 0 })
    }

    @Test
    fun focusRequestUsesTitleAndEstimate() {
        val request = todo("focus", estimate = 45).toFocusRequest()

        assertEquals("focus", request.todoId)
        assertEquals("任务 focus", request.taskTitle)
        assertEquals(45, request.estimatedMinutes)
        assertFalse(request.taskTitle.isBlank())
    }

    private fun todo(
        id: String,
        due: Long? = null,
        start: Long? = null,
        end: Long? = null,
        estimate: Int = DEFAULT_FOCUS_MINUTES,
        important: Boolean = false,
        urgency: TodoUrgencyMode = TodoUrgencyMode.NOT_URGENT,
        completed: Boolean = false,
        project: String = DEFAULT_PROJECT
    ) = TodoItemUi(
        id = id,
        title = "任务 $id",
        dueAtEpochMillis = due,
        scheduledStartEpochMillis = start,
        scheduledEndEpochMillis = end,
        estimatedFocusMinutes = estimate,
        isImportant = important,
        urgencyMode = urgency,
        isCompleted = completed,
        project = project
    )

    private fun subtask(id: String, parent: String?, order: Int) = TodoSubtaskUi(
        id = id,
        todoId = "todo",
        parentSubtaskId = parent,
        title = id,
        sortOrder = order
    )

    private fun List<TodoSubtaskNode>.flattenNodes(): List<TodoSubtaskNode> =
        flatMap { node -> listOf(node) + node.children.flattenNodes() }

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24L * HOUR
    }
}
