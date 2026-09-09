package com.example.controlfree.ui.todo.todo

import com.example.controlfree.ui.todo.commitment.CommitmentEditorSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal const val DEFAULT_FOCUS_MINUTES = 25
internal const val MIN_FOCUS_MINUTES = 1
internal const val MAX_FOCUS_MINUTES = 180

enum class TodoViewMode(val displayName: String) {
    LIST("清单"),
    MATRIX("象限")
}

enum class TodoGroupingMode(val displayName: String) {
    TIME("时间"),
    PROJECT("项目")
}

enum class TodoCompletionFilter(val displayName: String) {
    ALL("全部"),
    ACTIVE("待完成"),
    COMPLETED("已完成")
}

enum class TodoDateFilter(val displayName: String) {
    ALL("全部"),
    OVERDUE("已过期"),
    TODAY("今天"),
    THIS_WEEK("本周"),
    NO_DATE("收集箱")
}

data class TodoFilterCriteria(
    val completion: TodoCompletionFilter = TodoCompletionFilter.ALL,
    val date: TodoDateFilter = TodoDateFilter.ALL,
    val project: String? = null,
    val importantOnly: Boolean = false,
    val urgentOnly: Boolean = false,
    val scheduledOnly: Boolean = false
) {
    val isDefault: Boolean
        get() = this == TodoFilterCriteria()
}

enum class TodoCalendarMode(val displayName: String) {
    DAY("日"),
    WEEK("周"),
    MONTH("月")
}

enum class TodoUrgencyMode(val storedValue: String, val displayName: String) {
    AUTO("AUTO", "自动"),
    URGENT("URGENT", "紧急"),
    NOT_URGENT("NOT_URGENT", "不紧急");

    companion object {
        fun fromStoredValue(value: String?): TodoUrgencyMode =
            entries.firstOrNull { it.storedValue == value } ?: NOT_URGENT
    }
}

enum class TodoRecurrenceType(val storedValue: String, val displayName: String) {
    NONE("NONE", "不重复"),
    DAILY("DAILY", "每天"),
    WEEKDAYS("WEEKDAYS", "工作日"),
    WEEKLY("WEEKLY_DAYS", "每周指定日"),
    MONTHLY("MONTHLY_DAY", "每月固定日"),
    AFTER_COMPLETION("COMPLETION_INTERVAL", "完成后间隔");

    companion object {
        fun fromStoredValue(value: String?): TodoRecurrenceType =
            entries.firstOrNull { it.storedValue == value } ?: NONE
    }
}

data class TodoRecurrenceUi(
    val type: TodoRecurrenceType = TodoRecurrenceType.NONE,
    val interval: Int = 1,
    val weekdaysMask: Int = 0,
    val dayOfMonth: Int? = null,
    val seriesId: String? = null,
    val sequence: Int = 0
) {
    init {
        require(interval >= 1)
        require(weekdaysMask in 0..0b1111111)
        require(dayOfMonth == null || dayOfMonth in 1..31)
        require(sequence >= 0)
    }

    fun includes(day: DayOfWeek): Boolean =
        weekdaysMask and (1 shl (day.value - 1)) != 0
}

data class TodoItemUi(
    val id: String,
    val title: String,
    val description: String? = null,
    val dueAtEpochMillis: Long? = null,
    val scheduledStartEpochMillis: Long? = null,
    val scheduledEndEpochMillis: Long? = null,
    val estimatedFocusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val isImportant: Boolean = false,
    val urgencyMode: TodoUrgencyMode = TodoUrgencyMode.AUTO,
    val isCompleted: Boolean = false,
    val completedAtEpochMillis: Long? = null,
    val project: String = DEFAULT_PROJECT,
    val recurrence: TodoRecurrenceUi = TodoRecurrenceUi(),
    val supervisionLockEnabled: Boolean = false,
    val commitmentSettings: CommitmentEditorSettings = CommitmentEditorSettings(),
    val reminders: List<com.example.controlfree.todo.ItemReminder> = emptyList(),
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = createdAtEpochMillis
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(estimatedFocusMinutes in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES)
        require(
            scheduledStartEpochMillis == null && scheduledEndEpochMillis == null ||
                scheduledStartEpochMillis != null &&
                scheduledEndEpochMillis != null &&
                scheduledEndEpochMillis > scheduledStartEpochMillis
        )
    }
}

data class TodoDayProgress(
    val completed: Int,
    val total: Int
) {
    init {
        require(completed in 0..total)
    }

    val fraction: Float
        get() = if (total == 0) 0f else completed.toFloat() / total

    val percentage: Int
        get() = (fraction * 100).toInt()
}

data class TodoSubtaskUi(
    val id: String,
    val todoId: String,
    val parentSubtaskId: String? = null,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0
)

data class TodoSubtaskDraft(
    val id: String,
    val parentSubtaskId: String? = null,
    val title: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0
) {
    init {
        require(id.isNotBlank())
        require(title.isNotBlank())
        require(parentSubtaskId != id)
        require(sortOrder >= 0)
    }
}

fun removeSubtaskBranch(
    subtasks: List<TodoSubtaskDraft>,
    rootId: String
): List<TodoSubtaskDraft> {
    val childrenByParent = subtasks.groupBy(TodoSubtaskDraft::parentSubtaskId)
    val removedIds = mutableSetOf<String>()
    val pendingIds = ArrayDeque<String>().apply { add(rootId) }
    while (pendingIds.isNotEmpty()) {
        val currentId = pendingIds.removeLast()
        if (removedIds.add(currentId)) {
            childrenByParent[currentId].orEmpty().forEach { pendingIds.add(it.id) }
        }
    }
    return subtasks.filterNot { it.id in removedIds }
}

data class TodoSubtaskNode(
    val item: TodoSubtaskUi,
    val children: List<TodoSubtaskNode>
)

data class TodoEditorDraft(
    val id: String? = null,
    val title: String = "",
    val description: String = "",
    val dueAtEpochMillis: Long? = null,
    val scheduledStartEpochMillis: Long? = null,
    val scheduledEndEpochMillis: Long? = null,
    val estimatedFocusMinutes: Int = DEFAULT_FOCUS_MINUTES,
    val isImportant: Boolean = false,
    val urgencyMode: TodoUrgencyMode = TodoUrgencyMode.AUTO,
    val project: String = DEFAULT_PROJECT,
    val recurrence: TodoRecurrenceUi = TodoRecurrenceUi(),
    val commitmentSettings: CommitmentEditorSettings = CommitmentEditorSettings(),
    val reminders: List<com.example.controlfree.todo.ItemReminder> = emptyList(),
    val subtasks: List<TodoSubtaskDraft> = emptyList()
)

data class TodoFocusRequest(
    val todoId: String,
    val taskTitle: String,
    val estimatedMinutes: Int
) {
    init {
        require(todoId.isNotBlank())
        require(taskTitle.isNotBlank() && taskTitle == taskTitle.trim())
        require(estimatedMinutes in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES)
    }
}

enum class TodoMatrixQuadrant(val displayName: String) {
    IMPORTANT_URGENT("重要紧急"),
    IMPORTANT_NOT_URGENT("重要不急"),
    NOT_IMPORTANT_URGENT("紧急次要"),
    NOT_IMPORTANT_NOT_URGENT("稍后处理")
}

enum class TodoTimeBucket(val displayName: String) {
    TODAY("今天"),
    OVERDUE("已过期"),
    NO_DATE("收集箱"),
    TOMORROW("明天"),
    THIS_WEEK("本周"),
    FUTURE("未来")
}

data class TodoCalendarPlacement(
    val todo: TodoItemUi,
    val date: LocalDate,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val lane: Int,
    val laneCount: Int
)

fun TodoItemUi.isUrgentAt(nowEpochMillis: Long): Boolean = when (urgencyMode) {
    TodoUrgencyMode.URGENT -> true
    TodoUrgencyMode.NOT_URGENT -> false
    TodoUrgencyMode.AUTO -> {
        val anchor = dueAtEpochMillis ?: scheduledStartEpochMillis
        anchor != null && !isCompleted && anchor <= nowEpochMillis + AUTO_URGENCY_WINDOW_MILLIS
    }
}

fun TodoItemUi.matrixQuadrant(nowEpochMillis: Long): TodoMatrixQuadrant {
    val urgent = isUrgentAt(nowEpochMillis)
    return when {
        isImportant && urgent -> TodoMatrixQuadrant.IMPORTANT_URGENT
        isImportant -> TodoMatrixQuadrant.IMPORTANT_NOT_URGENT
        urgent -> TodoMatrixQuadrant.NOT_IMPORTANT_URGENT
        else -> TodoMatrixQuadrant.NOT_IMPORTANT_NOT_URGENT
    }
}

fun groupTodosByTime(
    todos: List<TodoItemUi>,
    today: LocalDate,
    zoneId: ZoneId
): Map<TodoTimeBucket, List<TodoItemUi>> {
    return todos
        .groupBy { todo -> todo.timeBucket(today, zoneId) }
        .mapValues { (_, items) -> items.sortedWith(todoDisplayOrder) }
}

/**
 * 统计指定日期实际安排的待办：排程日期优先于截止日期，无日期待办不参与。
 *
 * 与 [groupTodosByTime] 共用同一日期锚点，保证进度卡和“今天”分组口径一致。
 */
fun calculateTodoDayProgress(
    todos: List<TodoItemUi>,
    date: LocalDate,
    zoneId: ZoneId
): TodoDayProgress {
    val dayTodos = todos.filter { it.plannedDate(zoneId) == date }
    return TodoDayProgress(
        completed = dayTodos.count(TodoItemUi::isCompleted),
        total = dayTodos.size
    )
}

fun filterTodos(
    todos: List<TodoItemUi>,
    criteria: TodoFilterCriteria,
    today: LocalDate,
    zoneId: ZoneId,
    nowEpochMillis: Long
): List<TodoItemUi> = todos.filter { todo ->
    criteria.completion.matches(todo) &&
        criteria.date.matches(todo, today, zoneId) &&
        criteria.project.matchesProject(todo) &&
        (!criteria.importantOnly || todo.isImportant) &&
        (!criteria.urgentOnly || todo.isUrgentAt(nowEpochMillis)) &&
        (!criteria.scheduledOnly || todo.scheduledStartEpochMillis != null)
}

private fun TodoItemUi.timeBucket(today: LocalDate, zoneId: ZoneId): TodoTimeBucket {
    val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val date = plannedDate(zoneId)
    return when {
        date == null -> TodoTimeBucket.NO_DATE
        date < today -> TodoTimeBucket.OVERDUE
        date == today -> TodoTimeBucket.TODAY
        date == today.plusDays(1) -> TodoTimeBucket.TOMORROW
        date <= endOfWeek -> TodoTimeBucket.THIS_WEEK
        else -> TodoTimeBucket.FUTURE
    }
}

private fun TodoItemUi.plannedDate(zoneId: ZoneId): LocalDate? =
    (scheduledStartEpochMillis ?: dueAtEpochMillis)
        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }

private fun TodoCompletionFilter.matches(todo: TodoItemUi): Boolean = when (this) {
    TodoCompletionFilter.ALL -> true
    TodoCompletionFilter.ACTIVE -> !todo.isCompleted
    TodoCompletionFilter.COMPLETED -> todo.isCompleted
}

private fun TodoDateFilter.matches(
    todo: TodoItemUi,
    today: LocalDate,
    zoneId: ZoneId
): Boolean {
    if (this == TodoDateFilter.ALL) return true
    val bucket = todo.timeBucket(today, zoneId)
    return when (this) {
        TodoDateFilter.ALL -> true
        TodoDateFilter.OVERDUE -> bucket == TodoTimeBucket.OVERDUE
        TodoDateFilter.TODAY -> bucket == TodoTimeBucket.TODAY
        TodoDateFilter.THIS_WEEK -> bucket == TodoTimeBucket.TODAY ||
            bucket == TodoTimeBucket.TOMORROW ||
            bucket == TodoTimeBucket.THIS_WEEK
        TodoDateFilter.NO_DATE -> bucket == TodoTimeBucket.NO_DATE
    }
}

private fun String?.matchesProject(todo: TodoItemUi): Boolean {
    val selectedProject = this?.trim()?.takeIf(String::isNotEmpty) ?: return true
    return todo.project.trim().ifEmpty { DEFAULT_PROJECT }
        .equals(selectedProject, ignoreCase = true)
}

fun groupTodosByProject(todos: List<TodoItemUi>): Map<String, List<TodoItemUi>> =
    todos.groupBy { it.project.trim().ifEmpty { DEFAULT_PROJECT } }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .mapValues { (_, items) -> items.sortedWith(todoDisplayOrder) }

fun buildSubtaskForest(subtasks: List<TodoSubtaskUi>): List<TodoSubtaskNode> {
    val uniqueItems = subtasks.distinctBy(TodoSubtaskUi::id)
    val byId = uniqueItems.associateBy(TodoSubtaskUi::id)
    val children = uniqueItems.groupBy { item ->
        item.parentSubtaskId?.takeIf { parentId -> parentId in byId && parentId != item.id }
    }
    val visiting = hashSetOf<String>()
    val visited = hashSetOf<String>()

    fun build(item: TodoSubtaskUi): TodoSubtaskNode {
        if (!visiting.add(item.id)) return TodoSubtaskNode(item, emptyList())
        val descendants = children[item.id].orEmpty()
            .filterNot { it.id in visiting }
            .sortedWith(compareBy(TodoSubtaskUi::sortOrder, TodoSubtaskUi::createdStableKey))
            .map(::build)
        visiting.remove(item.id)
        visited += item.id
        return TodoSubtaskNode(item, descendants)
    }

    val roots = children[null].orEmpty()
        .sortedWith(compareBy(TodoSubtaskUi::sortOrder, TodoSubtaskUi::createdStableKey))
        .map(::build)
        .toMutableList()
    uniqueItems.filterNot { it.id in visited }
        .sortedWith(compareBy(TodoSubtaskUi::sortOrder, TodoSubtaskUi::createdStableKey))
        .forEach { roots += build(it) }
    return roots
}

fun calendarPlacements(
    todos: List<TodoItemUi>,
    dates: List<LocalDate>,
    zoneId: ZoneId
): List<TodoCalendarPlacement> = dates.flatMap { date ->
    val dayStart = date.atStartOfDay(zoneId).toInstant()
    val dayEnd = date.plusDays(1).atStartOfDay(zoneId).toInstant()
    val segments = todos.mapNotNull { todo ->
        val startMillis = todo.scheduledStartEpochMillis ?: return@mapNotNull null
        val endMillis = todo.scheduledEndEpochMillis ?: return@mapNotNull null
        val start = Instant.ofEpochMilli(startMillis)
        val end = Instant.ofEpochMilli(endMillis)
        if (!start.isBefore(dayEnd) || !end.isAfter(dayStart)) return@mapNotNull null
        val clippedStart = if (start.isBefore(dayStart)) dayStart else start
        val clippedEnd = if (end.isAfter(dayEnd)) dayEnd else end
        val startMinute = if (clippedStart == dayStart) 0 else
            clippedStart.atZone(zoneId).toLocalTime().toSecondOfDay() / 60
        val endMinute = if (clippedEnd == dayEnd) MINUTES_PER_DAY else
            clippedEnd.atZone(zoneId).toLocalTime().toSecondOfDay() / 60
        CalendarSegment(todo, startMinute, endMinute.coerceAtLeast(startMinute + 1))
    }.sortedWith(compareBy(CalendarSegment::startMinute, CalendarSegment::endMinuteExclusive))
    assignCalendarLanes(date, segments)
}

fun weekDates(containing: LocalDate): List<LocalDate> {
    val monday = containing.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return (0L..6L).map(monday::plusDays)
}

fun TodoItemUi.toEditorDraft(
    subtasks: List<TodoSubtaskUi> = emptyList()
): TodoEditorDraft = TodoEditorDraft(
    id = id,
    title = title,
    description = description.orEmpty(),
    dueAtEpochMillis = dueAtEpochMillis,
    scheduledStartEpochMillis = scheduledStartEpochMillis,
    scheduledEndEpochMillis = scheduledEndEpochMillis,
    estimatedFocusMinutes = estimatedFocusMinutes,
    isImportant = isImportant,
    urgencyMode = urgencyMode,
    project = project,
    recurrence = recurrence,
    commitmentSettings = commitmentSettings.copy(enabled = supervisionLockEnabled),
    reminders = reminders,
    subtasks = subtasks.map { subtask ->
        TodoSubtaskDraft(
            id = subtask.id,
            parentSubtaskId = subtask.parentSubtaskId,
            title = subtask.title,
            isCompleted = subtask.isCompleted,
            sortOrder = subtask.sortOrder
        )
    }
)

fun TodoItemUi.toFocusRequest(): TodoFocusRequest = TodoFocusRequest(
    todoId = id,
    taskTitle = title.trim(),
    estimatedMinutes = estimatedFocusMinutes.coerceIn(MIN_FOCUS_MINUTES, MAX_FOCUS_MINUTES)
)

private fun assignCalendarLanes(
    date: LocalDate,
    segments: List<CalendarSegment>
): List<TodoCalendarPlacement> {
    if (segments.isEmpty()) return emptyList()
    val result = mutableListOf<TodoCalendarPlacement>()
    var clusterStart = 0
    while (clusterStart < segments.size) {
        var clusterEnd = clusterStart + 1
        var latestEnd = segments[clusterStart].endMinuteExclusive
        while (clusterEnd < segments.size && segments[clusterEnd].startMinute < latestEnd) {
            latestEnd = maxOf(latestEnd, segments[clusterEnd].endMinuteExclusive)
            clusterEnd++
        }
        val laneEnds = mutableListOf<Int>()
        val lanes = IntArray(clusterEnd - clusterStart)
        for (index in clusterStart until clusterEnd) {
            val segment = segments[index]
            val freeLane = laneEnds.indexOfFirst { endMinute -> endMinute <= segment.startMinute }
            val lane = if (freeLane >= 0) freeLane else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = segment.endMinuteExclusive
            lanes[index - clusterStart] = lane
        }
        val laneCount = laneEnds.size.coerceAtLeast(1)
        for (index in clusterStart until clusterEnd) {
            val segment = segments[index]
            result += TodoCalendarPlacement(
                todo = segment.todo,
                date = date,
                startMinute = segment.startMinute,
                endMinuteExclusive = segment.endMinuteExclusive,
                lane = lanes[index - clusterStart],
                laneCount = laneCount
            )
        }
        clusterStart = clusterEnd
    }
    return result
}

private data class CalendarSegment(
    val todo: TodoItemUi,
    val startMinute: Int,
    val endMinuteExclusive: Int
)

private val todoDisplayOrder = compareBy<TodoItemUi>(TodoItemUi::isCompleted)
    .thenBy { it.dueAtEpochMillis ?: it.scheduledStartEpochMillis ?: Long.MAX_VALUE }
    .thenByDescending(TodoItemUi::isImportant)
    .thenBy(TodoItemUi::createdAtEpochMillis)

private val TodoSubtaskUi.createdStableKey: String
    get() = id

const val DEFAULT_PROJECT = "默认"
private const val MINUTES_PER_DAY = 24 * 60
private const val AUTO_URGENCY_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
