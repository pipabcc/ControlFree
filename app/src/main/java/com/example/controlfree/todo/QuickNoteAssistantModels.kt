package com.example.controlfree.todo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class QuickNoteAssistantTodoDraft(
    val title: String,
    val dueAtEpochMillis: Long?,
    val scheduledStartEpochMillis: Long?,
    val durationMinutes: Int?,
    val priority: Int,
    val recurrenceType: TodoRecurrenceType,
    val recurrenceDaysMask: Int,
    val reminderMinutesBefore: List<Int>
)

data class QuickNoteAssistantHabitDraft(
    val name: String,
    val frequencyType: HabitFrequencyType,
    val weekdaysMask: Int,
    val reminderMinutesOfDay: List<Int>
)

data class QuickNoteAssistantFocusDraft(
    val title: String,
    val startAtEpochMillis: Long,
    val durationMinutes: Int
)

data class QuickNoteAssistantAnniversaryDraft(
    val title: String,
    val targetAtEpochMillis: Long,
    val type: AnniversaryType,
    val repeatRule: AnniversaryRepeatRule,
    val zoneId: String,
    val reminderMinutesBefore: List<Int>
)

internal fun QuickNoteAssistantAnniversaryDraft.normalizedForCurrentTime(
    nowEpochMillis: Long
): QuickNoteAssistantAnniversaryDraft {
    val normalizedType = if (targetAtEpochMillis <= nowEpochMillis) {
        AnniversaryType.COUNT_UP
    } else {
        AnniversaryType.COUNTDOWN
    }
    return copy(
        type = normalizedType,
        repeatRule = if (normalizedType == AnniversaryType.COUNT_UP) {
            AnniversaryRepeatRule.NONE
        } else {
            repeatRule
        }
    )
}

data class QuickNoteAssistantAnalysis(
    val fingerprint: String,
    val ledgerEntries: List<LedgerEntryDraft>,
    val todoItems: List<QuickNoteAssistantTodoDraft>,
    val habits: List<QuickNoteAssistantHabitDraft>,
    val focusSessions: List<QuickNoteAssistantFocusDraft>,
    val anniversaries: List<QuickNoteAssistantAnniversaryDraft>,
    val advice: List<String>,
    val warnings: List<String>
) {
    val actionableCount: Int
        get() = ledgerEntries.size + todoItems.size + habits.size +
            focusSessions.size + anniversaries.size
}

data class QuickNoteAssistantFocusSchedule(
    val todoId: String,
    val title: String,
    val startAtEpochMillis: Long,
    val endAtEpochMillis: Long
)

data class QuickNoteAssistantSaveResult(
    val wasCreated: Boolean,
    val ledgerEntryIds: List<String>,
    val todoIds: List<String>,
    val habitIds: List<String>,
    val anniversaryIds: List<String>,
    val focusSchedules: List<QuickNoteAssistantFocusSchedule>
) {
    val targetCount: Int
        get() = ledgerEntryIds.size + todoIds.size + habitIds.size +
            anniversaryIds.size + focusSchedules.size
}

object QuickNoteAssistantFingerprint {
    private val VALID_FINGERPRINT = Regex("[0-9a-f]{64}")

    fun fromContent(content: String): String {
        val normalized = content.trim()
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun isValid(value: String): Boolean = VALID_FINGERPRINT.matches(value)
}
