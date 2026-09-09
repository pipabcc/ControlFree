package com.example.controlfree.productivity.quicknote

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

enum class QuickNoteIntent {
    NOTE,
    TODO,
    HABIT,
    FOCUS
}

enum class QuickNoteRecurrence {
    NONE,
    DAILY,
    WEEKDAYS,
    WEEKLY
}

data class ParsedQuickNote(
    val originalText: String,
    val title: String,
    val intent: QuickNoteIntent,
    val startAtEpochMillis: Long?,
    val dueAtEpochMillis: Long?,
    val estimatedDurationMinutes: Int?,
    val recurrence: QuickNoteRecurrence,
    val confidence: Float,
    val warnings: List<String> = emptyList()
)

/**
 * 解析常见中文日期、时段和时长。规则完整时无需联网；有歧义的结果交给用户或 AI 预览。
 */
class LocalChineseQuickNoteParser {
    fun parse(rawInput: String, now: ZonedDateTime): ParsedQuickNote {
        val input = rawInput.trim().take(MAX_INPUT_CHARS)
        if (input.isBlank()) {
            return ParsedQuickNote("", "", QuickNoteIntent.NOTE, null, null, null, QuickNoteRecurrence.NONE, 0f)
        }

        val warnings = mutableListOf<String>()
        val parsedDate = parseDate(input, now.toLocalDate())
        val parsedTime = parseTime(input)
        val durationMinutes = parseDurationMinutes(input)
        val recurrence = parseRecurrence(input)
        var resolvedDate = parsedDate.date

        if (parsedTime != null) {
            resolvedDate = (resolvedDate ?: now.toLocalDate()).plusDays(parsedTime.dayOffset)
            if (parsedDate.date == null && parsedTime.dayOffset == 0L &&
                !LocalDateTime.of(resolvedDate, parsedTime.time).atZone(now.zone).isAfter(now)
            ) {
                resolvedDate = resolvedDate.plusDays(1)
                warnings += "未写日期且该时间今天已过，已按明天解析"
            }
        }

        val startAt = if (resolvedDate != null && parsedTime != null) {
            LocalDateTime.of(resolvedDate, parsedTime.time).atZone(now.zone)
        } else {
            null
        }
        val dueAt = when {
            startAt != null && durationMinutes != null -> startAt.plusMinutes(durationMinutes.toLong())
            parsedDate.date != null && parsedTime == null -> parsedDate.date.atTime(LocalTime.of(23, 59)).atZone(now.zone)
            else -> null
        }

        val intent = inferIntent(input, parsedDate.date != null || parsedTime != null, recurrence)
        val title = cleanTitle(input).ifBlank { input }
        val confidence = confidenceFor(parsedDate.explicit, parsedTime != null, durationMinutes != null, intent)
        return ParsedQuickNote(
            originalText = input,
            title = title,
            intent = intent,
            startAtEpochMillis = startAt?.toInstant()?.toEpochMilli(),
            dueAtEpochMillis = dueAt?.toInstant()?.toEpochMilli(),
            estimatedDurationMinutes = durationMinutes,
            recurrence = recurrence,
            confidence = confidence,
            warnings = warnings
        )
    }

    private fun parseDate(input: String, today: LocalDate): ParsedDate {
        when {
            "大后天" in input -> return ParsedDate(today.plusDays(3), true)
            "后天" in input -> return ParsedDate(today.plusDays(2), true)
            "明天" in input -> return ParsedDate(today.plusDays(1), true)
            "今天" in input || "今日" in input -> return ParsedDate(today, true)
        }

        DATE_PATTERN.find(input)?.let { match ->
            val year = match.groups[1]?.value?.toIntOrNull() ?: today.year
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            if (month != null && day != null) {
                runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return ParsedDate(it, true) }
            }
        }

        WEEKDAY_PATTERN.find(input)?.let { match ->
            val requestedDay = WEEKDAY_NAMES[match.groupValues[2]] ?: return@let
            val weekPrefix = match.groupValues[1]
            val currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val date = when (weekPrefix) {
                "本周", "这周" -> currentWeekStart
                    .plusDays((requestedDay.value - DayOfWeek.MONDAY.value).toLong())

                "下周" -> currentWeekStart
                    .plusWeeks(1)
                    .plusDays((requestedDay.value - DayOfWeek.MONDAY.value).toLong())

                else -> today.with(TemporalAdjusters.nextOrSame(requestedDay))
            }
            return ParsedDate(date, true)
        }
        return ParsedDate(null, false)
    }

    private fun parseTime(input: String): ParsedTime? {
        COLON_TIME_PATTERN.find(input)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            val period = periodBefore(input, match.range.first)
            return parseTimeValue(rawHour, minute, period)
        }
        CHINESE_TIME_PATTERN.find(input)?.let { match ->
            val rawHour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = when {
                match.groupValues[2].isNotBlank() -> 30
                match.groupValues[3].isNotBlank() -> match.groupValues[3].toIntOrNull() ?: 0
                else -> 0
            }
            val period = periodBefore(input, match.range.first)
            return parseTimeValue(rawHour, minute, period)
        }
        return null
    }

    private fun parseTimeValue(rawHour: Int, minute: Int, period: String): ParsedTime? = runCatching {
        ParsedTime(
            time = LocalTime.of(normalizeHour(rawHour, period), minute),
            dayOffset = if (period == "晚上" && rawHour == 12) 1L else 0L
        )
    }.getOrNull()

    private fun periodBefore(input: String, timeStartIndex: Int): String = PERIOD_PATTERN
        .findAll(input.substring(0, timeStartIndex))
        .lastOrNull()
        ?.value
        .orEmpty()

    private fun normalizeHour(hour: Int, period: String): Int = when {
        period == "晚上" && hour == 12 -> 0
        period in AFTERNOON_PERIODS && hour < 12 -> hour + 12
        period == "凌晨" && hour == 12 -> 0
        else -> hour
    }

    private fun parseDurationMinutes(input: String): Int? {
        val match = DURATION_PATTERN.find(input) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val minutes = if (match.groupValues[2] in HOUR_UNITS) amount * 60 else amount
        return minutes.toInt().coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
    }

    private fun parseRecurrence(input: String): QuickNoteRecurrence = when {
        "工作日" in input -> QuickNoteRecurrence.WEEKDAYS
        "每天" in input || "每日" in input -> QuickNoteRecurrence.DAILY
        "每周" in input || "每星期" in input -> QuickNoteRecurrence.WEEKLY
        else -> QuickNoteRecurrence.NONE
    }

    private fun inferIntent(
        input: String,
        hasTimeSignal: Boolean,
        recurrence: QuickNoteRecurrence
    ): QuickNoteIntent = when {
        FOCUS_KEYWORDS.any(input::contains) -> QuickNoteIntent.FOCUS
        recurrence != QuickNoteRecurrence.NONE || HABIT_KEYWORDS.any(input::contains) -> QuickNoteIntent.HABIT
        hasTimeSignal || TODO_KEYWORDS.any(input::contains) -> QuickNoteIntent.TODO
        else -> QuickNoteIntent.NOTE
    }

    private fun cleanTitle(input: String): String = input
        .replace(DATE_PATTERN, " ")
        .replace(WEEKDAY_PATTERN, " ")
        .replace(COLON_TIME_PATTERN, " ")
        .replace(CHINESE_TIME_PATTERN, " ")
        .replace(DURATION_PATTERN, " ")
        .replace(RELATIVE_DATE_PATTERN, " ")
        .replace(PERIOD_PATTERN, " ")
        .replace(RECURRENCE_PATTERN, " ")
        .replace(LEADING_COMMAND_PATTERN, " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '，', ',', '。')

    private fun confidenceFor(
        explicitDate: Boolean,
        hasTime: Boolean,
        hasDuration: Boolean,
        intent: QuickNoteIntent
    ): Float {
        var score = if (intent == QuickNoteIntent.NOTE) 0.45f else 0.58f
        if (explicitDate) score += 0.15f
        if (hasTime) score += 0.15f
        if (hasDuration) score += 0.08f
        return score.coerceAtMost(0.96f)
    }

    private data class ParsedDate(val date: LocalDate?, val explicit: Boolean)

    private data class ParsedTime(val time: LocalTime, val dayOffset: Long)

    private companion object {
        const val MAX_INPUT_CHARS = 1_000
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 480
        val HOUR_UNITS = setOf("小时", "钟头", "h", "H")
        val DATE_PATTERN = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?")
        val WEEKDAY_PATTERN = Regex("(下周|本周|这周|星期|周)([一二三四五六日天])")
        val COLON_TIME_PATTERN = Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)")
        val CHINESE_TIME_PATTERN = Regex("(?<!\\d)([0-2]?\\d)点(?:(半)|([0-5]?\\d)分?)?")
        val DURATION_PATTERN = Regex("(\\d+(?:\\.\\d+)?)\\s*(小时|钟头|分钟|min|MIN|h|H)")
        val RELATIVE_DATE_PATTERN = Regex("大后天|后天|明天|今天|今日")
        val PERIOD_PATTERN = Regex("凌晨|早上|上午|中午|下午|傍晚|晚上")
        val AFTERNOON_PERIODS = setOf("中午", "下午", "傍晚", "晚上")
        val RECURRENCE_PATTERN = Regex("每天|每日|工作日|每周|每星期")
        val LEADING_COMMAND_PATTERN = Regex("^(提醒我|记得|帮我|我要|计划|安排)\\s*")
        val FOCUS_KEYWORDS = listOf("专注", "番茄钟", "进入心流")
        val HABIT_KEYWORDS = listOf("打卡", "养成习惯", "坚持")
        val TODO_KEYWORDS = listOf("完成", "提交", "交付", "办理", "购买", "开会", "复习", "考试")
        val WEEKDAY_NAMES = mapOf(
            "一" to DayOfWeek.MONDAY,
            "二" to DayOfWeek.TUESDAY,
            "三" to DayOfWeek.WEDNESDAY,
            "四" to DayOfWeek.THURSDAY,
            "五" to DayOfWeek.FRIDAY,
            "六" to DayOfWeek.SATURDAY,
            "日" to DayOfWeek.SUNDAY,
            "天" to DayOfWeek.SUNDAY
        )
    }
}
