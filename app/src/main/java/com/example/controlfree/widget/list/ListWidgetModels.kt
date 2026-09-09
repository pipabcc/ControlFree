package com.example.controlfree.widget.list

import com.example.controlfree.todo.QuickNoteEntity
import com.example.controlfree.todo.TodoItemEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class ListWidgetRow(
    val id: String,
    val marker: String,
    val title: String,
    val subtitle: String,
    val isMuted: Boolean,
    val isCompleted: Boolean
)

internal fun todoWidgetRows(
    items: List<TodoItemEntity>,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<ListWidgetRow> = items
    .sortedWith(
        compareBy<TodoItemEntity> { it.isCompleted }
            .thenByDescending { it.priority }
            .thenBy { it.dueDateEpochMillis ?: Long.MAX_VALUE }
            .thenByDescending { it.createdAtEpochMillis }
    )
    .take(MAX_WIDGET_ROWS)
    .map { item ->
        val dueText = item.dueDateEpochMillis?.let { millis ->
            "截止 ${Instant.ofEpochMilli(millis).atZone(zoneId).format(DATE_TIME_FORMATTER)}"
        }
        val subtitle = when {
            item.isCompleted -> "已完成"
            !item.description.isNullOrBlank() -> item.description.trim().take(MAX_SUBTITLE_LENGTH)
            dueText != null -> dueText
            item.category.isNotBlank() -> item.category
            else -> "待办"
        }
        ListWidgetRow(
            id = item.id,
            marker = if (item.isCompleted) "✓" else "○",
            title = item.title.take(MAX_TITLE_LENGTH),
            subtitle = subtitle,
            isMuted = item.isCompleted,
            isCompleted = item.isCompleted
        )
    }

internal fun quickNoteWidgetRows(
    notes: List<QuickNoteEntity>,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<ListWidgetRow> = notes
    .sortedByDescending(QuickNoteEntity::createdAtEpochMillis)
    .take(MAX_WIDGET_ROWS)
    .map { note ->
        val createdAt = Instant.ofEpochMilli(note.createdAtEpochMillis)
            .atZone(zoneId)
            .format(DATE_TIME_FORMATTER)
        ListWidgetRow(
            id = note.id,
            marker = "•",
            title = note.content.take(MAX_TITLE_LENGTH),
            subtitle = if (note.mediaUri == null) createdAt else "含图片 · $createdAt",
            isMuted = false,
            isCompleted = false
        )
    }

private const val MAX_WIDGET_ROWS = 50
private const val MAX_TITLE_LENGTH = 240
private const val MAX_SUBTITLE_LENGTH = 160
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
