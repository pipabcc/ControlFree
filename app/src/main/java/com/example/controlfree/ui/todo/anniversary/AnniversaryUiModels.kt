package com.example.controlfree.ui.todo.anniversary

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.toItemReminders
import com.example.controlfree.todo.toJsonString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class AnniversaryEditorDraft(
    val id: String? = null,
    val title: String = "",
    val type: AnniversaryType = AnniversaryType.COUNTDOWN,
    val calendarType: AnniversaryCalendarType = AnniversaryCalendarType.SOLAR,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
    val isLunarLeapMonth: Boolean = false,
    val repeatRule: AnniversaryRepeatRule = AnniversaryRepeatRule.NONE,
    val zoneId: String,
    val isPinned: Boolean = false,
    val showOnWidget: Boolean = false,
    val showOnLockScreen: Boolean = true,
    val reminders: List<com.example.controlfree.todo.ItemReminder> = emptyList()
) {
    val isValid: Boolean
        get() = title.trim().isNotEmpty() &&
            year in 1901..2099 && month in 1..12 &&
            day in 1..(if (calendarType == AnniversaryCalendarType.LUNAR) 30 else 31) &&
            hour in 0..23 && minute in 0..59 && second in 0..59 &&
            (type != AnniversaryType.COUNT_UP || repeatRule == AnniversaryRepeatRule.NONE) &&
            runCatching { ZoneId.of(zoneId) }.isSuccess

    fun toSpec(): AnniversarySpec = AnniversarySpec(
        type = type,
        calendarType = calendarType,
        dateTime = AnniversaryDateTimeParts(
            year = year,
            month = month,
            day = day,
            hour = hour,
            minute = minute,
            second = second,
            isLeapMonth = calendarType == AnniversaryCalendarType.LUNAR && isLunarLeapMonth
        ),
        repeatRule = if (type == AnniversaryType.COUNT_UP) {
            AnniversaryRepeatRule.NONE
        } else {
            repeatRule
        },
        zoneId = ZoneId.of(zoneId)
    )

    companion object {
        fun create(today: LocalDate, zoneId: ZoneId): AnniversaryEditorDraft =
            AnniversaryEditorDraft(
                year = today.year,
                month = today.monthValue,
                day = today.dayOfMonth,
                zoneId = zoneId.id
            )

        fun fromEntity(entity: AnniversaryItemEntity, fallbackZoneId: ZoneId): AnniversaryEditorDraft {
            val zone = runCatching { ZoneId.of(entity.zoneId) }.getOrDefault(fallbackZoneId)
            val fallback = Instant.ofEpochMilli(entity.targetDateEpochMillis).atZone(zone)
            return AnniversaryEditorDraft(
                id = entity.id,
                title = entity.title,
                type = AnniversaryType.fromStoredValue(entity.type),
                calendarType = if (entity.isLunar) AnniversaryCalendarType.LUNAR else AnniversaryCalendarType.SOLAR,
                year = entity.sourceYear.takeIf { it in 1901..2099 } ?: fallback.year,
                month = entity.sourceMonth.takeIf { it in 1..12 } ?: fallback.monthValue,
                day = entity.sourceDay.takeIf { it in 1..31 } ?: fallback.dayOfMonth,
                hour = entity.sourceHour.takeIf { it in 0..23 } ?: fallback.hour,
                minute = entity.sourceMinute.takeIf { it in 0..59 } ?: fallback.minute,
                second = entity.sourceSecond.takeIf { it in 0..59 } ?: fallback.second,
                isLunarLeapMonth = entity.isLunarLeapMonth,
                repeatRule = AnniversaryRepeatRule.fromStoredValue(entity.repeatRule),
                zoneId = zone.id,
                isPinned = entity.isPinnedTop,
                showOnWidget = entity.showOnWidget,
                showOnLockScreen = entity.showOnLockScreen,
                reminders = entity.remindersJson.toItemReminders()
            )
        }
    }
}

internal fun AnniversaryEditorDraft.toSaveableValues(): List<Any> = listOf(
    id != null,
    id.orEmpty(),
    title,
    type.name,
    calendarType.name,
    year,
    month,
    day,
    hour,
    minute,
    second,
    isLunarLeapMonth,
    repeatRule.name,
    zoneId,
    isPinned,
    showOnWidget,
    showOnLockScreen,
    reminders.toJsonString()
)

internal fun anniversaryEditorDraftFromSaveableValues(values: List<Any>): AnniversaryEditorDraft? {
    if (values.size != ANNIVERSARY_EDITOR_SAVED_VALUE_COUNT) return null
    return runCatching {
        val hasId = values[0] as Boolean
        val remindersJson = if (values.size > 17) values[17] as String else "[]"
        AnniversaryEditorDraft(
            id = (values[1] as String).takeIf { hasId },
            title = values[2] as String,
            type = AnniversaryType.valueOf(values[3] as String),
            calendarType = AnniversaryCalendarType.valueOf(values[4] as String),
            year = values[5] as Int,
            month = values[6] as Int,
            day = values[7] as Int,
            hour = values[8] as Int,
            minute = values[9] as Int,
            second = values[10] as Int,
            isLunarLeapMonth = values[11] as Boolean,
            repeatRule = AnniversaryRepeatRule.valueOf(values[12] as String),
            zoneId = values[13] as String,
            isPinned = values[14] as Boolean,
            showOnWidget = values[15] as Boolean,
            showOnLockScreen = values[16] as Boolean,
            reminders = remindersJson.toItemReminders()
        )
    }.getOrNull()
}

internal val AnniversaryEditorDraftSaver: Saver<AnniversaryEditorDraft, Any> = listSaver(
    save = { draft -> draft.toSaveableValues() },
    restore = ::anniversaryEditorDraftFromSaveableValues
)

internal val NullableAnniversaryEditorDraftSaver: Saver<AnniversaryEditorDraft?, Any> = listSaver(
    save = { draft ->
        if (draft == null) listOf(false) else listOf(true) + draft.toSaveableValues()
    },
    restore = { values ->
        when (values.firstOrNull()) {
            false -> null
            true -> anniversaryEditorDraftFromSaveableValues(values.drop(1))
            else -> null
        }
    }
)

private const val ANNIVERSARY_EDITOR_SAVED_VALUE_COUNT = 18

fun AnniversaryItemEntity.toSpec(fallbackZoneId: ZoneId): AnniversarySpec =
    AnniversaryEditorDraft.fromEntity(this, fallbackZoneId).toSpec()

fun LocalDate.toUtcDatePickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun datePickerMillisToLocalDate(value: Long): LocalDate =
    Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()
