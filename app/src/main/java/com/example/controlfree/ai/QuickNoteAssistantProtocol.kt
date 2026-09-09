package com.example.controlfree.ai

import com.example.controlfree.knowledge.StrictKnowledgeJson
import com.example.controlfree.knowledge.strictArray
import com.example.controlfree.knowledge.strictInt
import com.example.controlfree.knowledge.strictObject
import com.example.controlfree.knowledge.strictString
import com.example.controlfree.productivity.quicknote.ParsedQuickNote
import com.example.controlfree.todo.ALL_WEEKDAYS_MASK
import com.example.controlfree.todo.AnniversaryRepeatRule
import com.example.controlfree.todo.AnniversaryType
import com.example.controlfree.todo.HabitFrequencyType
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerCategory
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.todo.LedgerEntryDraft
import com.example.controlfree.todo.QuickNoteAssistantAnalysis
import com.example.controlfree.todo.QuickNoteAssistantAnniversaryDraft
import com.example.controlfree.todo.QuickNoteAssistantFingerprint
import com.example.controlfree.todo.QuickNoteAssistantFocusDraft
import com.example.controlfree.todo.QuickNoteAssistantHabitDraft
import com.example.controlfree.todo.QuickNoteAssistantTodoDraft
import com.example.controlfree.todo.TodoRecurrenceType
import com.example.controlfree.todo.normalizedForCurrentTime
import java.time.Instant
import java.time.ZonedDateTime

internal const val QUICK_NOTE_ASSISTANT_SCHEMA = "controlfree.quick-note-assistant.v1"

internal data class AssistantTodoTiming(
    val scheduledStartEpochMillis: Long?,
    val dueAtEpochMillis: Long?,
    val durationMinutes: Int?,
    val durationWasEstimated: Boolean
)

/**
 * 本地解析器只知道“文本里出现了一个时间”，这里再按中文语义区分开始与截止。
 * “提前 10 分钟”是提醒偏移，不得被当成截止提示。
 */
internal fun resolveLocalAssistantTodoTiming(
    sourceText: String,
    parsed: ParsedQuickNote
): AssistantTodoTiming {
    val hasExplicitDueSignal = EXPLICIT_DUE_PATTERN.containsMatchIn(sourceText)
    val scheduledStart = parsed.startAtEpochMillis.takeUnless { hasExplicitDueSignal }
    val dueAt = when {
        hasExplicitDueSignal -> parsed.startAtEpochMillis ?: parsed.dueAtEpochMillis
        parsed.startAtEpochMillis == null -> parsed.dueAtEpochMillis
        else -> null
    }
    val estimatedDuration = if (scheduledStart != null && parsed.estimatedDurationMinutes == null) {
        estimateAssistantTodoDurationMinutes(parsed.title.ifBlank { sourceText })
    } else {
        null
    }
    return AssistantTodoTiming(
        scheduledStartEpochMillis = scheduledStart,
        dueAtEpochMillis = dueAt,
        durationMinutes = parsed.estimatedDurationMinutes ?: estimatedDuration,
        durationWasEstimated = estimatedDuration != null
    )
}

internal fun estimateAssistantTodoDurationMinutes(taskText: String): Int = when {
    listOf("电影", "演出", "音乐会").any(taskText::contains) -> 120
    listOf("吃饭", "午餐", "晚餐", "早餐", "用餐").any(taskText::contains) -> 45
    listOf("通勤", "出发", "接送", "取快递").any(taskText::contains) -> 30
    listOf("打球", "运动", "健身", "跑步", "游泳", "瑜伽").any(taskText::contains) -> 60
    listOf("会议", "开会", "复习", "学习", "写作", "报告", "购物", "买菜").any(taskText::contains) -> 60
    else -> DEFAULT_ASSISTANT_TODO_DURATION_MINUTES
}

private val EXPLICIT_DUE_PATTERN = Regex("截止|截至|最晚|到期|之前|(?<!提)前(?!往|去)")
private val MULTI_ACTION_SEPARATOR_PATTERN = Regex("[，,。！？!?；;\\n]|然后|同时|另外")
private const val DEFAULT_ASSISTANT_TODO_DURATION_MINUTES = 60

internal object QuickNoteAssistantResponseParser {
    private val ROOT_KEYS = setOf(
        "schema",
        "ledger_entries",
        "todo_items",
        "habits",
        "focus_sessions",
        "anniversaries",
        "advice",
        "warnings"
    )
    private val LEDGER_KEYS = setOf(
        "title",
        "amount_fen",
        "is_estimated",
        "direction",
        "category",
        "occurred_at",
        "confidence_percent",
        "emotion",
        "necessity",
        "note"
    )
    private val TODO_KEYS = setOf(
        "title",
        "due_at",
        "start_at",
        "duration_minutes",
        "priority",
        "recurrence",
        "reminder_minutes_before"
    )
    private val HABIT_KEYS = setOf("name", "recurrence", "weekdays", "reminder_times")
    private val FOCUS_KEYS = setOf("title", "start_at", "duration_minutes")
    private val ANNIVERSARY_KEYS = setOf(
        "title",
        "occurs_at",
        "type",
        "repeat_rule",
        "reminder_minutes_before"
    )
    private val VALID_EMOTIONS = setOf("冲动", "解压", "刚需", "社交", "自我投资")
    private val VALID_NECESSITIES = setOf("need", "want")

    fun parse(
        raw: String,
        sourceText: String,
        now: ZonedDateTime
    ): QuickNoteAssistantAnalysis? {
        val root = StrictKnowledgeJson.parseObject(raw, LIMITS) ?: return null
        if (root.keys != ROOT_KEYS || root.strictString("schema") != QUICK_NOTE_ASSISTANT_SCHEMA) {
            return null
        }
        val warnings = parseTextArray(root, "warnings", MAX_WARNING_CHARS) ?: return null
        val advice = parseTextArray(root, "advice", MAX_ADVICE_CHARS) ?: return null
        val ledgerValues = root.strictArray("ledger_entries") ?: return null
        val todoValues = root.strictArray("todo_items") ?: return null
        val habitValues = root.strictArray("habits") ?: return null
        val focusValues = root.strictArray("focus_sessions") ?: return null
        val anniversaryValues = root.strictArray("anniversaries") ?: return null
        val executableItemCount = ledgerValues.size + todoValues.size + habitValues.size +
            focusValues.size + anniversaryValues.size
        val ledgers = ledgerValues.map { value ->
            parseLedger(value, warnings, now) ?: return null
        }
        val todoSourceText = sourceText.takeIf {
            todoValues.size == 1 &&
                executableItemCount == 1 &&
                !MULTI_ACTION_SEPARATOR_PATTERN.containsMatchIn(sourceText)
        }
        val todos = todoValues.map { value ->
            parseTodo(value, todoSourceText, now) ?: return null
        }
        val habits = habitValues.map { value ->
            parseHabit(value, now) ?: return null
        }
        val focuses = focusValues.map { value ->
            parseFocus(value, now) ?: return null
        }
        val anniversaries = anniversaryValues.map { value ->
            parseAnniversary(value, now) ?: return null
        }
        if (ledgers.any(LedgerEntryDraft::isEstimated) && warnings.isEmpty()) return null

        return QuickNoteAssistantAnalysis(
            fingerprint = QuickNoteAssistantFingerprint.fromContent(sourceText),
            ledgerEntries = ledgers,
            todoItems = todos,
            habits = habits,
            focusSessions = focuses,
            anniversaries = anniversaries,
            advice = advice,
            warnings = warnings
        )
    }

    private fun parseLedger(
        value: StrictKnowledgeJson.Value,
        warnings: List<String>,
        now: ZonedDateTime
    ): LedgerEntryDraft? {
        val entry = value.strictObject() ?: return null
        if (entry.keys != LEDGER_KEYS) return null
        val title = entry.requiredText("title", MAX_LEDGER_TITLE_CHARS) ?: return null
        val amountFen = entry.strictLong("amount_fen")
            ?.takeIf(LedgerAmountCodec::isValidFen)
            ?: return null
        val estimated = entry.strictBoolean("is_estimated") ?: return null
        val direction = entry.strictString("direction")
            ?.let(LedgerDirection::fromStoredValue)
            ?: return null
        val category = entry.strictString("category")
            ?.let(LedgerCategory::fromStoredValue)
            ?: return null
        if (category.direction != direction) return null
        val occurredAt = entry.strictString("occurred_at")
            ?.let(::parseInstantMillis)
            ?.takeIf { it in 0L..now.plusMinutes(5).toInstant().toEpochMilli() }
            ?: return null
        val confidence = entry.strictInt("confidence_percent")
            ?.takeIf { it in 0..100 }
            ?: return null
        val emotion = entry.strictString("emotion")?.takeIf { it in VALID_EMOTIONS } ?: return null
        val necessity = entry.strictString("necessity")
            ?.takeIf { it in VALID_NECESSITIES }
            ?: return null
        val note = entry.strictString("note")?.trim()?.takeIf(String::isNotEmpty)
        if (entry.strictString("note") == null || (note?.length ?: 0) > MAX_LEDGER_NOTE_CHARS) return null
        return LedgerEntryDraft(
            title = title,
            amountFen = amountFen,
            direction = direction,
            category = category,
            occurredAtEpochMillis = occurredAt,
            isEstimated = estimated,
            aiConfidence = confidence / 100f,
            emotion = emotion,
            necessity = necessity,
            note = note,
            warnings = warnings
        )
    }

    private fun parseTodo(
        value: StrictKnowledgeJson.Value,
        sourceText: String?,
        now: ZonedDateTime
    ): QuickNoteAssistantTodoDraft? {
        val item = value.strictObject() ?: return null
        if (item.keys != TODO_KEYS) return null
        val title = item.requiredText("title", MAX_TITLE_CHARS) ?: return null
        val dueAt = item.optionalInstant("due_at") ?: return null
        val startAt = item.optionalInstant("start_at") ?: return null
        val duration = item.strictInt("duration_minutes")?.takeIf { it in 0..1_440 } ?: return null
        val priority = item.strictInt("priority")?.takeIf { it in 0..3 } ?: return null
        val recurrence = item.strictString("recurrence")
            ?.let(::parseTodoRecurrence)
            ?: return null
        val reminders = item.strictIntArray("reminder_minutes_before", 0..MAX_REMINDER_MINUTES)
            ?: return null
        val hasExplicitDueSignal = sourceText?.let(EXPLICIT_DUE_PATTERN::containsMatchIn)
        val resolvedDueAt = when (hasExplicitDueSignal) {
            true -> dueAt.value ?: startAt.value
            false -> null
            null -> dueAt.value
        }
        val resolvedStartAt = when (hasExplicitDueSignal) {
            true -> startAt.value?.takeUnless { it == resolvedDueAt }
            false -> startAt.value ?: dueAt.value
            null -> startAt.value
        }
        if (reminders.isNotEmpty() && resolvedStartAt == null && resolvedDueAt == null) return null
        val mask = if (recurrence == TodoRecurrenceType.WEEKLY_DAYS) {
            weekdayMask(resolvedStartAt ?: resolvedDueAt, now)
        } else {
            0
        }
        return QuickNoteAssistantTodoDraft(
            title = title,
            dueAtEpochMillis = resolvedDueAt,
            scheduledStartEpochMillis = resolvedStartAt,
            durationMinutes = duration.takeIf { it > 0 }
                ?: resolvedStartAt?.let { estimateAssistantTodoDurationMinutes(title) },
            priority = priority,
            recurrenceType = recurrence,
            recurrenceDaysMask = mask,
            reminderMinutesBefore = reminders
        )
    }

    private fun parseHabit(
        value: StrictKnowledgeJson.Value,
        now: ZonedDateTime
    ): QuickNoteAssistantHabitDraft? {
        val item = value.strictObject() ?: return null
        if (item.keys != HABIT_KEYS) return null
        val name = item.requiredText("name", MAX_HABIT_NAME_CHARS) ?: return null
        val recurrence = item.strictString("recurrence") ?: return null
        val frequency = when (recurrence) {
            "DAILY" -> HabitFrequencyType.DAILY
            "WEEKDAYS", "WEEKLY" -> HabitFrequencyType.SPECIFIC_WEEKDAYS
            else -> return null
        }
        val weekdays = item.strictIntArray("weekdays", 1..7) ?: return null
        if (recurrence == "WEEKLY" && weekdays.isEmpty()) return null
        val mask = when (recurrence) {
            "DAILY" -> ALL_WEEKDAYS_MASK
            "WEEKDAYS" -> WEEKDAYS_MASK
            else -> weekdays.fold(0) { result, day -> result or (1 shl (day - 1)) }
        }
        val reminderMinutes = item.strictStringArray("reminder_times")?.map { rawTime ->
            parseMinutesOfDay(rawTime) ?: return null
        }?.distinct()?.sorted() ?: return null
        return QuickNoteAssistantHabitDraft(
            name = name,
            frequencyType = frequency,
            weekdaysMask = mask,
            reminderMinutesOfDay = reminderMinutes
        )
    }

    private fun parseFocus(
        value: StrictKnowledgeJson.Value,
        now: ZonedDateTime
    ): QuickNoteAssistantFocusDraft? {
        val item = value.strictObject() ?: return null
        if (item.keys != FOCUS_KEYS) return null
        val title = item.requiredText("title", MAX_TITLE_CHARS) ?: return null
        val start = item.optionalInstant("start_at") ?: return null
        val duration = item.strictInt("duration_minutes")?.takeIf { it in 1..1_440 } ?: return null
        val resolvedStart = start.value ?: now.toInstant().toEpochMilli()
        val endAt = runCatching {
            Math.addExact(resolvedStart, Math.multiplyExact(duration.toLong(), 60_000L))
        }.getOrNull()?.takeIf { it > now.toInstant().toEpochMilli() } ?: return null
        return QuickNoteAssistantFocusDraft(
            title = title,
            startAtEpochMillis = resolvedStart,
            durationMinutes = duration
        )
    }

    private fun parseAnniversary(
        value: StrictKnowledgeJson.Value,
        now: ZonedDateTime
    ): QuickNoteAssistantAnniversaryDraft? {
        val item = value.strictObject() ?: return null
        if (item.keys != ANNIVERSARY_KEYS) return null
        val title = item.requiredText("title", MAX_TITLE_CHARS) ?: return null
        val target = item.strictString("occurs_at")
            ?.let(::parseInstantMillis)
            ?.takeIf { it >= 0L }
            ?: return null
        val type = item.strictString("type")
            ?.let { raw -> AnniversaryType.entries.firstOrNull { it.storedValue == raw } }
            ?: return null
        val repeat = item.strictString("repeat_rule")
            ?.let { raw -> AnniversaryRepeatRule.entries.firstOrNull { it.storedValue == raw } }
            ?: return null
        val reminders = item.strictIntArray("reminder_minutes_before", 0..MAX_REMINDER_MINUTES)
            ?: return null
        return QuickNoteAssistantAnniversaryDraft(
            title = title,
            targetAtEpochMillis = target,
            type = type,
            repeatRule = repeat,
            zoneId = now.zone.id,
            reminderMinutesBefore = reminders
        ).normalizedForCurrentTime(now.toInstant().toEpochMilli())
    }

    private fun parseTextArray(
        root: Map<String, StrictKnowledgeJson.Value>,
        name: String,
        maxChars: Int
    ): List<String>? = root.strictStringArray(name)?.map { value ->
        value.trim().takeIf { it.isNotEmpty() && it.length <= maxChars } ?: return null
    }

    private fun Map<String, StrictKnowledgeJson.Value>.requiredText(
        name: String,
        maxChars: Int
    ): String? = strictString(name)?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxChars }

    private fun Map<String, StrictKnowledgeJson.Value>.optionalInstant(name: String): OptionalInstant? {
        val raw = strictString(name)?.trim() ?: return null
        if (raw.isEmpty()) return OptionalInstant(null)
        return parseInstantMillis(raw)?.takeIf { it >= 0L }?.let(::OptionalInstant)
    }

    private fun Map<String, StrictKnowledgeJson.Value>.strictLong(name: String): Long? =
        (this[name] as? StrictKnowledgeJson.Value.NumberValue)?.raw?.toLongOrNull()

    private fun Map<String, StrictKnowledgeJson.Value>.strictBoolean(name: String): Boolean? =
        (this[name] as? StrictKnowledgeJson.Value.BooleanValue)?.value

    private fun Map<String, StrictKnowledgeJson.Value>.strictIntArray(
        name: String,
        range: IntRange
    ): List<Int>? = strictArray(name)?.map { value ->
        (value as? StrictKnowledgeJson.Value.NumberValue)?.raw?.toIntOrNull()
            ?.takeIf { it in range }
            ?: return null
    }?.distinct()?.sorted()

    private fun Map<String, StrictKnowledgeJson.Value>.strictStringArray(name: String): List<String>? =
        strictArray(name)?.map { value ->
            (value as? StrictKnowledgeJson.Value.StringValue)?.value ?: return null
        }

    private fun parseTodoRecurrence(raw: String): TodoRecurrenceType? = when (raw) {
        "NONE" -> TodoRecurrenceType.NONE
        "DAILY" -> TodoRecurrenceType.DAILY
        "WEEKDAYS" -> TodoRecurrenceType.WEEKDAYS
        "WEEKLY" -> TodoRecurrenceType.WEEKLY_DAYS
        else -> null
    }

    private fun parseMinutesOfDay(raw: String): Int? {
        val match = TIME_PATTERN.matchEntire(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun weekdayMask(epochMillis: Long?, now: ZonedDateTime): Int {
        val day = epochMillis?.let { Instant.ofEpochMilli(it).atZone(now.zone).dayOfWeek } ?: now.dayOfWeek
        return 1 shl (day.value - 1)
    }

    private fun parseInstantMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private data class OptionalInstant(val value: Long?)

    private const val MAX_TITLE_CHARS = 200
    private const val MAX_HABIT_NAME_CHARS = 100
    private const val MAX_LEDGER_TITLE_CHARS = 20
    private const val MAX_LEDGER_NOTE_CHARS = 200
    private const val MAX_ADVICE_CHARS = 500
    private const val MAX_WARNING_CHARS = 100
    private const val MAX_REMINDER_MINUTES = 525_600
    private const val WEEKDAYS_MASK = 0b0011111
    private val TIME_PATTERN = Regex("([01]\\d|2[0-3]):([0-5]\\d)")
    private val LIMITS = StrictKnowledgeJson.Limits(
        maxJsonChars = 24_000,
        maxStringChars = 1_000,
        maxArrayItems = 20,
        maxDepth = 6
    )
}
