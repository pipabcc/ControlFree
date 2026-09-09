package com.example.controlfree.ui.todo.timeblock

import java.time.LocalDate

enum class TimeBlockEditorType(val displayName: String) {
    TASK("待办 Task"),
    EVENT("日程 Event")
}

data class TimeBlockEditorDraft(
    val id: String? = null,
    val editorType: TimeBlockEditorType = TimeBlockEditorType.TASK,
    val title: String = "",
    val description: String = "",
    val date: LocalDate,
    val project: String = TIME_BLOCK_PROJECT_WORK,
    val highPriority: Boolean = false,
    val allDay: Boolean = true,
    val startMinute: Int = 9 * 60,
    val endMinuteExclusive: Int = 10 * 60
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY)
        require(endMinuteExclusive in 1..MINUTES_PER_DAY)
        require(editorType == TimeBlockEditorType.TASK || endMinuteExclusive > startMinute)
        require(project.isNotBlank())
    }
}

data class TimeBlockNaturalLanguageSuggestion(
    val title: String,
    val date: LocalDate,
    val startMinute: Int?,
    val endMinuteExclusive: Int?,
    val recognized: Boolean,
    val summary: String
)

data class TimeBlockSettings(
    val shadeWeekends: Boolean = true,
    val showHalfHourGrid: Boolean = true,
    val completionAnimation: Boolean = true
)

data class TimeBlockFocusRequest(
    val stableKey: String,
    val title: String,
    val durationMinutes: Int
)

const val TIME_BLOCK_PROJECT_WORK = "工作"
const val TIME_BLOCK_PROJECT_LIFE = "生活"
const val TIME_BLOCK_PROJECT_STUDY = "学习"

val primaryTimeBlockProjects = listOf(
    TIME_BLOCK_PROJECT_WORK,
    TIME_BLOCK_PROJECT_LIFE,
    TIME_BLOCK_PROJECT_STUDY
)
