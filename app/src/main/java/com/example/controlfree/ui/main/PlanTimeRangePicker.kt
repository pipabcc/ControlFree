package com.example.controlfree.ui.main

import androidx.compose.runtime.Composable
import com.example.controlfree.ui.todo.components.StableTimePickerPage

internal enum class TimeRangeBoundary(val pickerTitle: String) {
    START("选择开始时间"),
    END("选择结束时间")
}

internal data class TimeRangePickerRequest(
    val rangeId: Long,
    val initialMinute: Int,
    val boundary: TimeRangeBoundary
)

/**
 * 监督类任务共用的时间选择内容页。
 *
 * 复用习惯编辑器的 Compose 时间表盘，并在此集中处理结束时间 00:00 与 24:00
 * 之间的显示/存储映射，避免三类任务分别实现同一套边界规则。
 */
@Composable
internal fun PlanTimeRangePickerPage(
    request: TimeRangePickerRequest,
    onCancel: () -> Unit,
    onSelected: (request: TimeRangePickerRequest, minute: Int) -> Unit
) {
    val initialMinute = normalizePlanTimePickerMinute(request.initialMinute)
    StableTimePickerPage(
        initialHour = initialMinute / MINUTES_PER_HOUR,
        initialMinute = initialMinute % MINUTES_PER_HOUR,
        onCancel = onCancel,
        onSelected = { hour, minute ->
            onSelected(
                request,
                planMinuteFromTimePicker(request.boundary, hour, minute)
            )
        }
    )
}

internal fun normalizePlanTimePickerMinute(initialMinute: Int): Int {
    require(initialMinute in 0..MINUTES_PER_DAY) { "时段分钟必须位于 0..1440" }
    return if (initialMinute == MINUTES_PER_DAY) 0 else initialMinute
}

internal fun planMinuteFromTimePicker(
    boundary: TimeRangeBoundary,
    hour: Int,
    minute: Int
): Int {
    require(hour in 0..23) { "小时必须位于 0..23" }
    require(minute in 0..59) { "分钟必须位于 0..59" }
    val selectedMinute = hour * MINUTES_PER_HOUR + minute
    return if (boundary == TimeRangeBoundary.END && selectedMinute == 0) {
        MINUTES_PER_DAY
    } else {
        selectedMinute
    }
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
