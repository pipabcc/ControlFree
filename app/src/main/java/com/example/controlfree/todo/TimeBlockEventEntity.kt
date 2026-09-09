package com.example.controlfree.todo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 新日程工作台中的独立 Event。
 *
 * Event 与待办具有不同生命周期，因此不写入 todo_items，也不会进入旧日程统计、
 * 待办组件、监督承诺或成长奖励链路。
 */
@Entity(
    tableName = "time_block_events",
    indices = [
        Index(value = ["start_at_epoch_millis"]),
        Index(value = ["end_at_epoch_millis"]),
        Index(value = ["is_completed"]),
        Index(value = ["project"])
    ]
)
data class TimeBlockEventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "start_at_epoch_millis") val startAtEpochMillis: Long,
    @ColumnInfo(name = "end_at_epoch_millis") val endAtEpochMillis: Long,
    @ColumnInfo(name = "project") val project: String,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean,
    @ColumnInfo(name = "completed_at_epoch_millis") val completedAtEpochMillis: Long?,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis") val updatedAtEpochMillis: Long
) {
    init {
        require(id.isNotBlank()) { "日程编号不能为空" }
        require(title.isNotBlank()) { "日程标题不能为空" }
        require(startAtEpochMillis >= 0L) { "日程开始时间不能为负数" }
        require(endAtEpochMillis > startAtEpochMillis) { "日程结束时间必须晚于开始时间" }
        require(priority in 0..3) { "日程优先级必须位于 0..3" }
        require(project.isNotBlank()) { "日程项目不能为空" }
        require(isCompleted == (completedAtEpochMillis != null)) {
            "完成状态与完成时间必须一致"
        }
    }
}
