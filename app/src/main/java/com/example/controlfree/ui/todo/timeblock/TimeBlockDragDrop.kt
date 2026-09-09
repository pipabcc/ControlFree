package com.example.controlfree.ui.todo.timeblock

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent

private const val TIME_BLOCK_INBOX_DRAG_LABEL = "controlfree-timeblock-inbox"

internal enum class TimeBlockDragOrigin {
    INBOX,
    ALL_DAY
}

internal data class TimeBlockInboxDragPayload(
    val todoId: String,
    val shadowWidthPx: Float,
    val shadowHeightPx: Float,
    val anchorXInShadowPx: Float,
    val anchorYInShadowPx: Float,
    val origin: TimeBlockDragOrigin = TimeBlockDragOrigin.INBOX
)

internal data class TimeBlockGridDropGeometry(
    val leftInRootPx: Float,
    val topInRootPx: Float,
    val widthPx: Float,
    val heightPx: Float,
    val gutterWidthPx: Float,
    val hourHeightPx: Float,
    val dayCount: Int,
    val startMinute: Int,
    val endMinuteExclusive: Int,
    val snapMinutes: Int
)

internal data class TimeBlockGridDropHit(
    val dayIndex: Int,
    val minute: Int
)

internal data class TimeBlockDayDropGeometry(
    val leftInRootPx: Float,
    val widthPx: Float,
    val gutterWidthPx: Float,
    val dayCount: Int
)

internal data class TimeBlockDropWindow(
    val startMinute: Int,
    val endMinuteExclusive: Int
)

internal data class TimeBlockInboxDropTargetBinding(
    val target: DragAndDropTarget,
    val isActive: Boolean
)

internal fun timeBlockInboxTransferData(payload: TimeBlockInboxDragPayload): DragAndDropTransferData =
    DragAndDropTransferData(
        clipData = ClipData.newPlainText(TIME_BLOCK_INBOX_DRAG_LABEL, payload.todoId),
        localState = payload
    )

/**
 * Android 使用拖影中心作为默认热点。将释放点还原为拖影内锚点在根布局中的位置。
 */
internal fun resolveTimeBlockInboxGridDrop(
    dropXInRootPx: Float,
    dropYInRootPx: Float,
    payload: TimeBlockInboxDragPayload,
    geometry: TimeBlockGridDropGeometry
): TimeBlockGridDropHit? {
    if (!payload.hasValidGeometry() || !geometry.hasValidGeometry()) return null
    if (!dropXInRootPx.isFinite() || !dropYInRootPx.isFinite()) return null

    val anchorXInRootPx = dropXInRootPx - payload.shadowWidthPx / 2f +
        payload.anchorXInShadowPx
    val anchorYInRootPx = dropYInRootPx - payload.shadowHeightPx / 2f +
        payload.anchorYInShadowPx
    val dayIndex = timeBlockDayIndexAt(
        anchorXInGridPx = anchorXInRootPx - geometry.leftInRootPx,
        gridWidthPx = geometry.widthPx,
        gutterWidthPx = geometry.gutterWidthPx,
        dayCount = geometry.dayCount
    ) ?: return null
    val minute = timeBlockMinuteAt(
        anchorYInGridPx = anchorYInRootPx - geometry.topInRootPx,
        gridHeightPx = geometry.heightPx,
        hourHeightPx = geometry.hourHeightPx,
        startMinute = geometry.startMinute,
        endMinuteExclusive = geometry.endMinuteExclusive,
        snapMinutes = geometry.snapMinutes
    ) ?: return null
    return TimeBlockGridDropHit(dayIndex, minute)
}

internal fun resolveTimeBlockDayDropIndex(
    dropXInRootPx: Float,
    payload: TimeBlockInboxDragPayload,
    geometry: TimeBlockDayDropGeometry
): Int? {
    if (!payload.hasValidGeometry() || !dropXInRootPx.isFinite()) return null
    val anchorXInRootPx = dropXInRootPx - payload.shadowWidthPx / 2f +
        payload.anchorXInShadowPx
    return timeBlockDayIndexAt(
        anchorXInGridPx = anchorXInRootPx - geometry.leftInRootPx,
        gridWidthPx = geometry.widthPx,
        gutterWidthPx = geometry.gutterWidthPx,
        dayCount = geometry.dayCount
    )
}

internal fun timeBlockDayIndexAt(
    anchorXInGridPx: Float,
    gridWidthPx: Float,
    gutterWidthPx: Float,
    dayCount: Int
): Int? {
    if (
        !anchorXInGridPx.isFinite() || !gridWidthPx.isFinite() ||
        !gutterWidthPx.isFinite() || dayCount <= 0
    ) {
        return null
    }
    val contentWidthPx = gridWidthPx - gutterWidthPx
    val relativeX = anchorXInGridPx - gutterWidthPx
    if (contentWidthPx <= 0f || relativeX < 0f || relativeX >= contentWidthPx) return null

    val dayWidthPx = contentWidthPx / dayCount
    return (relativeX / dayWidthPx).toInt().takeIf { it in 0 until dayCount }
}

internal fun timeBlockMinuteAt(
    anchorYInGridPx: Float,
    gridHeightPx: Float,
    hourHeightPx: Float,
    startMinute: Int,
    endMinuteExclusive: Int,
    snapMinutes: Int
): Int? {
    if (
        !anchorYInGridPx.isFinite() || !gridHeightPx.isFinite() ||
        !hourHeightPx.isFinite() || gridHeightPx <= 0f || hourHeightPx <= 0f ||
        startMinute < 0 || endMinuteExclusive <= startMinute || snapMinutes <= 0
    ) {
        return null
    }
    val timelineHeightPx = (endMinuteExclusive - startMinute) / 60f * hourHeightPx
    val hitHeightPx = minOf(gridHeightPx, timelineHeightPx)
    if (anchorYInGridPx < 0f || anchorYInGridPx >= hitHeightPx) return null

    val rawMinute = startMinute + (anchorYInGridPx / hourHeightPx * 60f).toInt()
    if (rawMinute !in startMinute until endMinuteExclusive) return null
    return rawMinute - rawMinute % snapMinutes
}

internal fun timeBlockDropWindow(
    dropMinute: Int,
    estimatedDurationMinutes: Int,
    snapMinutes: Int = TIME_SNAP_MINUTES,
    endMinuteExclusive: Int = MINUTES_PER_DAY
): TimeBlockDropWindow {
    require(snapMinutes > 0)
    require(endMinuteExclusive > 0)

    val maxDuration = minOf(180, endMinuteExclusive)
    val boundedEstimate = estimatedDurationMinutes.coerceIn(1, maxDuration)
    val alignedDuration = (
        (boundedEstimate + snapMinutes - 1) / snapMinutes * snapMinutes
        ).coerceAtMost(maxDuration)
    val boundedDropMinute = dropMinute.coerceIn(0, endMinuteExclusive - 1)
    val alignedStart = boundedDropMinute - boundedDropMinute % snapMinutes
    return TimeBlockDropWindow(
        startMinute = alignedStart,
        endMinuteExclusive = minOf(alignedStart + alignedDuration, endMinuteExclusive)
    )
}

internal fun timeBlockResizeEndMinute(
    currentStartMinute: Int,
    requestedEndMinuteExclusive: Int,
    snapMinutes: Int = TIME_SNAP_MINUTES,
    dayEndMinuteExclusive: Int = MINUTES_PER_DAY
): Int {
    require(snapMinutes > 0)
    require(dayEndMinuteExclusive > 0)
    require(currentStartMinute in 0 until dayEndMinuteExclusive)

    val boundedEnd = requestedEndMinuteExclusive.coerceIn(0, dayEndMinuteExclusive)
    val alignedEnd = if (boundedEnd == dayEndMinuteExclusive) {
        dayEndMinuteExclusive
    } else {
        boundedEnd - boundedEnd % snapMinutes
    }
    val minimumEnd = minOf(currentStartMinute + snapMinutes, dayEndMinuteExclusive)
    return alignedEnd.coerceIn(minimumEnd, dayEndMinuteExclusive)
}

private fun TimeBlockInboxDragPayload.hasValidGeometry(): Boolean =
    todoId.isNotBlank() &&
        shadowWidthPx.isFinite() && shadowWidthPx > 0f &&
        shadowHeightPx.isFinite() && shadowHeightPx > 0f &&
        anchorXInShadowPx.isFinite() && anchorXInShadowPx in 0f..shadowWidthPx &&
        anchorYInShadowPx.isFinite() && anchorYInShadowPx in 0f..shadowHeightPx

private fun TimeBlockGridDropGeometry.hasValidGeometry(): Boolean =
    leftInRootPx.isFinite() && topInRootPx.isFinite() &&
        widthPx.isFinite() && widthPx > 0f &&
        heightPx.isFinite() && heightPx > 0f

internal fun isTimeBlockInboxDrag(event: DragAndDropEvent): Boolean {
    val description = event.toAndroidDragEvent().clipDescription ?: return false
    return description.label?.toString() == TIME_BLOCK_INBOX_DRAG_LABEL &&
        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
}

@Composable
internal fun rememberTimeBlockInboxDropTarget(
    onDragEnded: () -> Unit = {},
    onDrop: (payload: TimeBlockInboxDragPayload, positionInRoot: Offset) -> Unit
): TimeBlockInboxDropTargetBinding {
    var isActive by remember { mutableStateOf(false) }
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnDragEnded by rememberUpdatedState(onDragEnded)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isActive = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isActive = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                val androidEvent = event.toAndroidDragEvent()
                val clipData = androidEvent.clipData ?: return false
                if (clipData.itemCount == 0) return false
                val todoId = clipData.getItemAt(0).text?.toString().orEmpty()
                if (todoId.isBlank()) return false
                val payload = androidEvent.localState as? TimeBlockInboxDragPayload ?: return false
                if (payload.todoId != todoId) return false
                currentOnDrop(payload, Offset(androidEvent.x, androidEvent.y))
                isActive = false
                return true
            }

            override fun onEnded(event: DragAndDropEvent) {
                isActive = false
                currentOnDragEnded()
            }
        }
    }
    return TimeBlockInboxDropTargetBinding(target, isActive)
}

@Composable
internal fun rememberTimeBlockInboxDragObserver(
    onDragEnded: () -> Unit
): DragAndDropTarget {
    val currentOnDragEnded by rememberUpdatedState(onDragEnded)
    return remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = false

            override fun onEnded(event: DragAndDropEvent) {
                currentOnDragEnded()
            }
        }
    }
}
