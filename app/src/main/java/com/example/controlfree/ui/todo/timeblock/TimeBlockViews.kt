package com.example.controlfree.ui.todo.timeblock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.controlfree.ui.todo.components.SwipeToDeleteContainer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.theme.BrandColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

private const val GRID_START_MINUTE = 0 * 60
private const val GRID_END_MINUTE = 24 * 60
private val GRID_HOUR_HEIGHT = 48.dp
private val GRID_GUTTER_WIDTH = 46.dp
private val TIMED_ENTRY_RESIZE_HANDLE_HEIGHT = 9.dp

@Composable
internal fun ThreeDayTimeBlockView(
    entries: List<TimeBlockEntry>,
    dates: List<LocalDate>,
    today: LocalDate,
    nowEpochMillis: Long,
    settings: TimeBlockSettings,
    zoneId: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onEntryClick: (TimeBlockEntry) -> Unit,
    onToggle: (TimeBlockEntry) -> Unit,
    onMoveToDate: (TimeBlockEntry, LocalDate) -> Unit,
    onMoveToTime: (TimeBlockEntry, LocalDate, Int, Int) -> Unit,
    onMoveAllDayToTime: (TimeBlockEntry, LocalDate, Int) -> Unit,
    onResize: (TimeBlockEntry, Int) -> Unit,
    onInboxDrop: (String, LocalDate, Int?) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit
) {
    val allDayByDate = remember(entries, dates, zoneId) {
        dates.associateWith { date ->
            entriesOnDate(entries, date, zoneId)
                .filter { it.kind(zoneId) == TimeBlockEntryKind.ALL_DAY_TASK }
        }
    }
    val density = LocalDensity.current
    var allDayDropGeometry by remember(dates.size, density) {
        mutableStateOf<TimeBlockDayDropGeometry?>(null)
    }
    val allDayDropTarget = rememberTimeBlockInboxDropTarget { payload, positionInRoot ->
        val geometry = allDayDropGeometry ?: return@rememberTimeBlockInboxDropTarget
        val dayIndex = resolveTimeBlockDayDropIndex(
            dropXInRootPx = positionInRoot.x,
            payload = payload,
            geometry = geometry
        ) ?: return@rememberTimeBlockInboxDropTarget
        val targetDate = dates[dayIndex]
        when (payload.origin) {
            TimeBlockDragOrigin.INBOX -> onInboxDrop(payload.todoId, targetDate, null)
            TimeBlockDragOrigin.ALL_DAY -> entries.firstOrNull { entry ->
                entry.source == TimeBlockSource.TODO &&
                    entry.id == payload.todoId &&
                    entry.kind(zoneId) == TimeBlockEntryKind.ALL_DAY_TASK
            }?.let { entry ->
                if (entry.primaryDate(zoneId) != targetDate) onMoveToDate(entry, targetDate)
            }
        }
    }
    var gridDropGeometry by remember(dates.size, density) {
        mutableStateOf<TimeBlockGridDropGeometry?>(null)
    }
    val gridDropTarget = rememberTimeBlockInboxDropTarget { payload, positionInRoot ->
        val geometry = gridDropGeometry ?: return@rememberTimeBlockInboxDropTarget
        val hit = resolveTimeBlockInboxGridDrop(
            dropXInRootPx = positionInRoot.x,
            dropYInRootPx = positionInRoot.y,
            payload = payload,
            geometry = geometry
        ) ?: return@rememberTimeBlockInboxDropTarget
        val targetDate = dates[hit.dayIndex]
        when (payload.origin) {
            TimeBlockDragOrigin.INBOX -> onInboxDrop(payload.todoId, targetDate, hit.minute)
            TimeBlockDragOrigin.ALL_DAY -> entries.firstOrNull { entry ->
                entry.source == TimeBlockSource.TODO &&
                    entry.id == payload.todoId &&
                    entry.kind(zoneId) == TimeBlockEntryKind.ALL_DAY_TASK
            }?.let { entry -> onMoveAllDayToTime(entry, targetDate, hit.minute) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 3.dp)) {
            Spacer(Modifier.width(GRID_GUTTER_WIDTH))
            dates.forEach { date ->
                val isToday = date == today
                Row(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectDate(date) }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier.size(28.dp)
                            .background(if (isToday) TimeBlockColors.Current else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            color = if (isToday) Color.White else TimeBlockColors.Ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            when {
                                isToday -> "今天"
                                date == today.plusDays(1) -> "明天"
                                date == today.plusDays(2) -> "后天"
                                else -> date.weekdayLabel()
                            },
                            color = if (isToday) TimeBlockColors.Current else TimeBlockColors.InkSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${entriesOnDate(entries, date, zoneId).size} 项",
                            color = TimeBlockColors.InkTertiary,
                            fontFamily = TimeBlockMono,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(TimeBlockColors.AppBackgroundTop)
                .border(1.dp, TimeBlockColors.Line)
                .onGloballyPositioned { coordinates ->
                    allDayDropGeometry = TimeBlockDayDropGeometry(
                        leftInRootPx = coordinates.positionInRoot().x,
                        widthPx = coordinates.size.width.toFloat(),
                        gutterWidthPx = with(density) { GRID_GUTTER_WIDTH.toPx() },
                        dayCount = dates.size
                    )
                }
                .then(
                    if (allDayDropTarget.isActive) {
                        Modifier.background(TimeBlockColors.Work.copy(alpha = 0.1f))
                    } else {
                        Modifier
                    }
                )
                .dragAndDropTarget(
                    shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                    target = allDayDropTarget.target
                )
        ) {
            Box(Modifier.width(GRID_GUTTER_WIDTH).height(52.dp), contentAlignment = Alignment.Center) {
                Text("全天", color = TimeBlockColors.InkTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            dates.forEach { date ->
                Column(
                    Modifier.weight(1f).height(52.dp).border(0.5.dp, TimeBlockColors.Line)
                        .verticalScroll(rememberScrollState())
                        .padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    allDayByDate.getValue(date).forEach { entry ->
                        DraggableAllDayChip(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            onToggle = { onToggle(entry) }
                        )
                    }
                }
            }
        }
        val scrollState = rememberScrollState()
        val nowMinute = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalTime().toSecondOfDay() / 60
        LaunchedEffect(dates, today) {
            if (today in dates && nowMinute in GRID_START_MINUTE until GRID_END_MINUTE) {
                val target = (((nowMinute - GRID_START_MINUTE) / 60f) * 48f - 180f)
                    .roundToInt()
                    .coerceAtLeast(0)
                scrollState.scrollTo(target)
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .then(
                    if (gridDropTarget.isActive) Modifier.border(2.dp, TimeBlockColors.Work)
                    else Modifier
                )
                .dragAndDropTarget(
                    shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                    target = gridDropTarget.target
                )
        ) {
            Box(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                ThreeDayGrid(
                    entries = entries,
                    dates = dates,
                    today = today,
                    nowEpochMillis = nowEpochMillis,
                    settings = settings,
                    zoneId = zoneId,
                    onEntryClick = onEntryClick,
                    onToggle = onToggle,
                    onMoveToTime = onMoveToTime,
                    onResize = onResize,
                    onDropGeometryChanged = { gridDropGeometry = it },
                    onCreateAt = onCreateAt
                )
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DraggableAllDayChip(
    entry: TimeBlockEntry,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    var dragSourceSize by remember(entry.stableKey) { mutableStateOf(IntSize.Zero) }
    val color = projectColor(entry.project)
    val hapticFeedback = LocalHapticFeedback.current
    val viewConfiguration = LocalViewConfiguration.current
    val dragViewConfiguration = remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = 350L
        }
    }
    val dragSourceModifier = Modifier.dragAndDropSource(
        drawDragDecoration = {
            drawCircle(color.copy(alpha = 0.18f), radius = 12.dp.toPx(), center = center)
            drawCircle(color, radius = 3.5.dp.toPx(), center = center)
        },
        block = {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = {
                    val width = dragSourceSize.width
                    val height = dragSourceSize.height
                    if (width > 0 && height > 0) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        startTransfer(
                            timeBlockInboxTransferData(
                                TimeBlockInboxDragPayload(
                                    todoId = entry.id,
                                    shadowWidthPx = width.toFloat(),
                                    shadowHeightPx = height.toFloat(),
                                    anchorXInShadowPx = width / 2f,
                                    anchorYInShadowPx = height / 2f,
                                    origin = TimeBlockDragOrigin.ALL_DAY
                                )
                            )
                        )
                    }
                }
            )
        }
    )
    CompositionLocalProvider(LocalViewConfiguration provides dragViewConfiguration) {
        TimeBlockEntryChip(
            entry = entry,
            onClick = null,
            onToggle = onToggle,
            modifier = Modifier.fillMaxWidth()
                .onSizeChanged { dragSourceSize = it }
                .then(dragSourceModifier)
        )
    }
}

@Composable
private fun ThreeDayGrid(
    entries: List<TimeBlockEntry>,
    dates: List<LocalDate>,
    today: LocalDate,
    nowEpochMillis: Long,
    settings: TimeBlockSettings,
    zoneId: ZoneId,
    onEntryClick: (TimeBlockEntry) -> Unit,
    onToggle: (TimeBlockEntry) -> Unit,
    onMoveToTime: (TimeBlockEntry, LocalDate, Int, Int) -> Unit,
    onResize: (TimeBlockEntry, Int) -> Unit,
    onDropGeometryChanged: (TimeBlockGridDropGeometry) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit
) {
    val gridHeight = GRID_HOUR_HEIGHT * ((GRID_END_MINUTE - GRID_START_MINUTE) / 60)
    val placements = remember(entries, dates, zoneId) {
        timedPlacements(entries, dates, zoneId)
            .filter { it.endMinuteExclusive > GRID_START_MINUTE && it.startMinute < GRID_END_MINUTE }
    }
    val deadlines = remember(entries, dates, zoneId) {
        entries.filter { it.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK }
            .mapNotNull { entry ->
                val due = entry.dueAtEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId) }
                    ?: return@mapNotNull null
                val index = dates.indexOf(due.toLocalDate())
                if (index < 0) null else Triple(entry, index, due.toLocalTime().toSecondOfDay() / 60)
            }
    }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxWidth().requiredHeight(gridHeight)
            .onGloballyPositioned { coordinates ->
                val positionInRoot = coordinates.positionInRoot()
                onDropGeometryChanged(TimeBlockGridDropGeometry(
                    leftInRootPx = positionInRoot.x,
                    topInRootPx = positionInRoot.y,
                    widthPx = coordinates.size.width.toFloat(),
                    heightPx = coordinates.size.height.toFloat(),
                    gutterWidthPx = with(density) { GRID_GUTTER_WIDTH.toPx() },
                    hourHeightPx = with(density) { GRID_HOUR_HEIGHT.toPx() },
                    dayCount = dates.size,
                    startMinute = GRID_START_MINUTE,
                    endMinuteExclusive = GRID_END_MINUTE,
                    snapMinutes = TIME_SNAP_MINUTES
                ))
            }
    ) {
        val dayWidth = (maxWidth - GRID_GUTTER_WIDTH) / dates.size
        Box(
            Modifier.offset(x = GRID_GUTTER_WIDTH).width(maxWidth - GRID_GUTTER_WIDTH).height(gridHeight)
                .pointerInput(dates, dayWidth) {
                    detectTapGestures { offset ->
                        val dayWidthPx = with(density) { dayWidth.toPx() }
                        val dayIndex = (offset.x / dayWidthPx).toInt().coerceIn(dates.indices)
                        val minute = GRID_START_MINUTE +
                            (offset.y / with(density) { GRID_HOUR_HEIGHT.toPx() } * 60f).toInt()
                        onCreateAt(dates[dayIndex], snapMinute(minute))
                    }
                }
        )
        val lineColor = TimeBlockColors.Line
        val inkColor = TimeBlockColors.Ink
        Canvas(Modifier.fillMaxSize()) {
            val gutterPx = GRID_GUTTER_WIDTH.toPx()
            val dayWidthPx = (size.width - gutterPx) / dates.size
            dates.forEachIndexed { index, date ->
                val left = gutterPx + index * dayWidthPx
                if (date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && settings.shadeWeekends) {
                    drawRect(Color(0x0F94A3B8), topLeft = Offset(left, 0f), size = Size(dayWidthPx, size.height))
                }
                if (date == today) {
                    drawRect(TimeBlockColors.Work.copy(alpha = 0.045f), Offset(left, 0f), Size(dayWidthPx, size.height))
                }
                drawLine(lineColor, Offset(left, 0f), Offset(left, size.height), 1.dp.toPx())
            }
            for (hour in 0..(GRID_END_MINUTE - GRID_START_MINUTE) / 60) {
                val y = hour * GRID_HOUR_HEIGHT.toPx()
                drawLine(inkColor.copy(alpha = 0.085f), Offset(gutterPx, y), Offset(size.width, y), 1.dp.toPx())
                if (settings.showHalfHourGrid && hour < (GRID_END_MINUTE - GRID_START_MINUTE) / 60) {
                    val halfY = y + GRID_HOUR_HEIGHT.toPx() / 2f
                    drawLine(inkColor.copy(alpha = 0.04f), Offset(gutterPx, halfY), Offset(size.width, halfY), 1.dp.toPx())
                }
            }
        }
        for (hour in GRID_START_MINUTE / 60..GRID_END_MINUTE / 60) {
            Text(
                "%02d:00".format(hour),
                color = TimeBlockColors.InkTertiary,
                fontFamily = TimeBlockMono,
                fontSize = 9.sp,
                modifier = Modifier.offset(x = 7.dp, y = GRID_HOUR_HEIGHT * (hour - GRID_START_MINUTE / 60) - 6.dp)
            )
        }
        placements.forEach { placement ->
            val dayIndex = dates.indexOf(placement.date)
            val laneWidth = dayWidth / placement.laneCount
            val top = GRID_HOUR_HEIGHT *
                ((maxOf(placement.startMinute, GRID_START_MINUTE) - GRID_START_MINUTE) / 60f)
            val bottomMinute = minOf(placement.endMinuteExclusive, GRID_END_MINUTE)
            val height = (GRID_HOUR_HEIGHT * ((bottomMinute - maxOf(placement.startMinute, GRID_START_MINUTE)) / 60f))
                .coerceAtLeast(25.dp)
            TimedEntryCard(
                placement = placement,
                dates = dates,
                dayWidth = dayWidth,
                modifier = Modifier
                    .offset(
                        x = GRID_GUTTER_WIDTH + dayWidth * dayIndex + laneWidth * placement.lane + 3.dp,
                        y = top + 2.dp
                    )
                    .width(laneWidth - 6.dp)
                    .height(height - 4.dp),
                onClick = { onEntryClick(placement.entry) },
                onToggle = { onToggle(placement.entry) },
                onMoveToTime = onMoveToTime,
                onResize = onResize
            )
        }
        deadlines.forEach { (entry, dayIndex, minute) ->
            val top = GRID_HOUR_HEIGHT * ((minute - GRID_START_MINUTE) / 60f)
            if (minute in GRID_START_MINUTE until GRID_END_MINUTE) {
                DeadlineMarker(
                    entry = entry,
                    modifier = Modifier.offset(
                        x = GRID_GUTTER_WIDTH + dayWidth * dayIndex + 4.dp,
                        y = top - 11.dp
                    ).width(dayWidth - 8.dp).height(23.dp),
                    onClick = { onEntryClick(entry) },
                    onToggle = { onToggle(entry) }
                )
            }
        }
        val todayIndex = dates.indexOf(today)
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val nowMinute = now.toLocalTime().toSecondOfDay() / 60
        if (todayIndex >= 0 && nowMinute in GRID_START_MINUTE until GRID_END_MINUTE) {
            val y = GRID_HOUR_HEIGHT * ((nowMinute - GRID_START_MINUTE) / 60f)
            Row(
                Modifier.offset(x = GRID_GUTTER_WIDTH + dayWidth * todayIndex - 1.dp, y = y - 8.dp)
                    .width(dayWidth + 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(9.dp).background(TimeBlockColors.Current, CircleShape))
                Box(Modifier.height(2.dp).weight(1f).background(TimeBlockColors.Current))
            }
            Text(
                formatMinute(nowMinute),
                color = Color.White,
                fontFamily = TimeBlockMono,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(x = 0.dp, y = y - 9.dp)
                    .background(TimeBlockColors.Current, RoundedCornerShape(5.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun TimedEntryCard(
    placement: TimeBlockPlacement,
    dates: List<LocalDate>,
    dayWidth: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onMoveToTime: (TimeBlockEntry, LocalDate, Int, Int) -> Unit,
    onResize: (TimeBlockEntry, Int) -> Unit
) {
    val entry = placement.entry
    val color = projectColor(entry.project)
    var dragOffset by remember(entry.stableKey) { mutableStateOf(Offset.Zero) }
    var resizeDelta by remember(entry.stableKey) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val duration = placement.endMinuteExclusive - placement.startMinute
    Box(
        modifier = modifier
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .clip(RoundedCornerShape(9.dp))
            .background(if (entry.isCompleted) Color(0xFFEEF1F5) else color.softSurface())
            .border(
                width = if (entry.isHighPriority) 1.5.dp else 0.dp,
                color = if (entry.isHighPriority) TimeBlockColors.High else Color.Transparent,
                shape = RoundedCornerShape(9.dp)
            )
            .drawBehind {
                drawRect(color, size = Size(3.5.dp.toPx(), size.height))
            }
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = if (duration <= 30) 1.5.dp else 5.dp, bottom = if (duration <= 30) 1.5.dp else 5.dp)
    ) {
        // 移动区与底部缩放区是兄弟节点，避免同一次手势同时提交两种排程修改。
        Box(
            Modifier.fillMaxSize()
                .padding(bottom = TIMED_ENTRY_RESIZE_HANDLE_HEIGHT)
                .pointerInput(
                    entry.stableKey,
                    entry.updatedAtEpochMillis,
                    placement.date,
                    placement.startMinute,
                    placement.endMinuteExclusive,
                    dates,
                    dayWidth
                ) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        val dayShift = (dragOffset.x / with(density) { dayWidth.toPx() }).roundToInt()
                        val currentIndex = dates.indexOf(placement.date)
                        val targetDate = dates[(currentIndex + dayShift).coerceIn(dates.indices)]
                        val minuteShift = (dragOffset.y / with(density) { GRID_HOUR_HEIGHT.toPx() } * 60f).roundToInt()
                        onMoveToTime(entry, targetDate, placement.startMinute + minuteShift, duration)
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragOffset = Offset.Zero },
                    onDrag = { change, amount ->
                        change.consume()
                        dragOffset += amount
                    }
                )
            }
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(end = 17.dp),
            verticalArrangement = if (duration <= 30) Arrangement.Center else Arrangement.Top
        ) {
            Text(
                entry.title + if (entry.isHighPriority) "  急" else "",
                color = if (entry.isCompleted) TimeBlockColors.InkTertiary else TimeBlockColors.Ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (entry.isCompleted) TextDecoration.LineThrough else null
            )
            if (duration > 30) {
                Text(
                    "${formatMinute(placement.startMinute)} – ${formatMinute(placement.endMinuteExclusive)}",
                    color = TimeBlockColors.InkSecondary,
                    fontFamily = TimeBlockMono,
                    fontSize = 8.5.sp,
                    maxLines = 1
                )
            }
        }
        TimeBlockCompletionButton(
            completed = entry.isCompleted,
            color = color,
            onClick = onToggle,
            modifier = Modifier
                .align(if (duration <= 30) Alignment.CenterEnd else Alignment.TopEnd)
                .size(17.dp)
        )
        Box(
            Modifier.align(Alignment.BottomCenter).width(24.dp).height(TIMED_ENTRY_RESIZE_HANDLE_HEIGHT)
                .pointerInput(
                    entry.stableKey,
                    entry.updatedAtEpochMillis,
                    placement.startMinute,
                    placement.endMinuteExclusive
                ) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            resizeDelta += amount
                        },
                        onDragEnd = {
                            val minuteDelta = (resizeDelta / with(density) { GRID_HOUR_HEIGHT.toPx() } * 60f).roundToInt()
                            onResize(entry, placement.endMinuteExclusive + minuteDelta)
                            resizeDelta = 0f
                        },
                        onDragCancel = { resizeDelta = 0f }
                    )
                }
                .drawBehind {
                    drawRoundRect(
                        color = color.copy(alpha = 0.45f),
                        topLeft = Offset(0f, size.height - 3.dp.toPx()),
                        size = Size(size.width, 3.dp.toPx())
                    )
                }
        )
    }
}

@Composable
private fun DeadlineMarker(
    entry: TimeBlockEntry,
    modifier: Modifier,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        modifier.clip(RoundedCornerShape(7.dp)).background(Color.White)
            .drawBehind {
                drawRoundRect(
                    color = TimeBlockColors.High,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            entry.title,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TimeBlockColors.Ink,
            textDecoration = if (entry.isCompleted) TextDecoration.LineThrough else null
        )
        Text(
            formatEpochTime(requireNotNull(entry.dueAtEpochMillis), ZoneId.systemDefault()),
            color = TimeBlockColors.High,
            fontFamily = TimeBlockMono,
            fontSize = 7.5.sp
        )
        TimeBlockCompletionButton(
            completed = entry.isCompleted,
            color = TimeBlockColors.High,
            onClick = onToggle,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
internal fun WeekTimeBlockView(
    entries: List<TimeBlockEntry>,
    dates: List<LocalDate>,
    today: LocalDate,
    zoneId: ZoneId,
    onDayClick: (LocalDate) -> Unit,
    onEntryClick: (TimeBlockEntry) -> Unit,
    onToggle: (TimeBlockEntry) -> Unit,
    onInboxDrop: (String, LocalDate, Int?) -> Unit
) {
    val weekEntries = dates.flatMap { entriesOnDate(entries, it, zoneId) }.distinctBy(TimeBlockEntry::stableKey)
    val progress = dayProgress(weekEntries)
    val weekNumber = dates.first().get(WeekFields.of(Locale.SIMPLIFIED_CHINESE).weekOfWeekBasedYear())
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 90.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "week-summary") {
            Column(
                Modifier.fillMaxWidth().background(BrandColors.SurfaceCard, RoundedCornerShape(15.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第${weekNumber}周", fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = TimeBlockColors.Ink)
                            if (today in dates) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "本周",
                                    color = BrandColors.OnPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(BrandColors.Primary, RoundedCornerShape(5.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            "${dates.first().monthDayLabel()} – ${dates.last().monthDayLabel()} · ${progress.total} 项任务",
                            color = TimeBlockColors.InkSecondary,
                            fontFamily = TimeBlockMono,
                            fontSize = 10.sp
                        )
                    }
                    TimeBlockProgressRing(
                        progress.ratio,
                        "${progress.completed}/${progress.total}",
                        Modifier.size(46.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("${progress.completed}/${progress.total}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TimeBlockColors.Ink)
                        Text("本周完成", fontSize = 9.sp, color = TimeBlockColors.InkTertiary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    dates.forEach { date ->
                        val count = entriesOnDate(entries, date, zoneId).size
                        Box(
                            Modifier.weight(1f).height(7.dp)
                                .background(loadColor(count), RoundedCornerShape(4.dp))
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                    dates.forEach { date ->
                        Text(date.weekdayLabel().removePrefix("周"), color = TimeBlockColors.InkTertiary, fontSize = 9.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        items(dates, key = LocalDate::toString) { date ->
            val dayEntries = entriesOnDate(entries, date, zoneId)
            val dayProgress = dayProgress(dayEntries)
            val isToday = date == today
            val calendarLabel = timeBlockCalendarLabel(date)
            val dropTarget = rememberTimeBlockInboxDropTarget { payload, _ ->
                onInboxDrop(payload.todoId, date, null)
            }
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (dropTarget.isActive) TimeBlockColors.Work.copy(alpha = 0.1f)
                        else BrandColors.SurfaceCard,
                        RoundedCornerShape(15.dp)
                    )
                    .border(
                        if (isToday) 1.5.dp else 0.dp,
                        if (isToday) TimeBlockColors.Current else Color.Transparent,
                        RoundedCornerShape(15.dp)
                    )
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                        target = dropTarget.target
                    )
                    .clickable { onDayClick(date) }
                    .padding(11.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        date.weekdayLabel() + calendarLabel?.let { " · ${it.text}" }.orEmpty(),
                        color = if (calendarLabel?.isSolarTerm == true) Color(0xFF0B7A4E)
                        else TimeBlockColors.InkSecondary,
                        fontSize = if (calendarLabel == null) 10.sp else 8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        date.dayOfMonth.toString(),
                        color = if (isToday) TimeBlockColors.Current else TimeBlockColors.Ink,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                TimeBlockProgressRing(
                    dayProgress.ratio,
                    "${dayProgress.completed}/${dayProgress.total}",
                    Modifier.size(39.dp)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (dayEntries.isEmpty()) {
                        Text("暂无安排", color = TimeBlockColors.InkTertiary, fontSize = 11.sp, modifier = Modifier.padding(vertical = 5.dp))
                    } else {
                        dayEntries.take(3).forEach { entry ->
                            CompactEntryRow(entry, zoneId, { onEntryClick(entry) }, { onToggle(entry) })
                        }
                        if (dayEntries.size > 3) {
                            Text("＋${dayEntries.size - 3} 更多", color = TimeBlockColors.Work, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MonthTimeBlockView(
    entries: List<TimeBlockEntry>,
    dates: List<LocalDate>,
    anchorMonth: YearMonth,
    today: LocalDate,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    onDayClick: (LocalDate) -> Unit,
    onInboxDrop: (String, LocalDate, Int?) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, color = TimeBlockColors.InkSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 7.dp, end = 7.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            userScrollEnabled = true
        ) {
            items(dates, key = LocalDate::toString) { date ->
                val dayEntries = entriesOnDate(entries, date, zoneId)
                val progress = dayProgress(dayEntries)
                val expiredCount = dayEntries.count { isExpired(it, nowEpochMillis, zoneId) }
                val inMonth = YearMonth.from(date) == anchorMonth
                val calendarLabel = timeBlockCalendarLabel(date)
                val dropTarget = rememberTimeBlockInboxDropTarget { payload, _ ->
                    onInboxDrop(payload.todoId, date, null)
                }
                Column(
                    Modifier.height(82.dp)
                        .alpha(if (inMonth) 1f else 0.42f)
                        .background(
                            when {
                                dropTarget.isActive -> TimeBlockColors.Work.copy(alpha = 0.12f)
                                inMonth -> BrandColors.SurfaceCard
                                else -> Color.Transparent
                            },
                            RoundedCornerShape(9.dp)
                        )
                        .border(
                            if (date == today) 1.5.dp else 0.dp,
                            if (date == today) TimeBlockColors.Current else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                            target = dropTarget.target
                        )
                        .clickable { onDayClick(date) }
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(19.dp).background(if (date == today) TimeBlockColors.Current else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(date.dayOfMonth.toString(), color = if (date == today) Color.White else TimeBlockColors.Ink, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Spacer(Modifier.weight(1f))
                        calendarLabel?.let { label ->
                            Text(
                                label.text,
                                color = if (label.isSolarTerm) Color(0xFF0B7A4E) else Color(0xFFB0750A),
                                fontSize = 6.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier.widthIn(max = 29.dp)
                                    .background(
                                        if (label.isSolarTerm) Color(0xFF0B7A4E).copy(alpha = 0.12f) else Color(0xFFB0750A).copy(alpha = 0.12f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 2.dp, vertical = 1.dp)
                            )
                        }
                        if (dayEntries.isNotEmpty()) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = dayEntries.size.toString(),
                                color = TimeBlockColors.InkSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        dayEntries.take(3).forEach { entry ->
                            TimeBlockEntryChip(entry, onClick = { onDayClick(date) }, compact = true, modifier = Modifier.fillMaxWidth())
                        }
                        if (dayEntries.size > 3) {
                            Text("＋${dayEntries.size - 3} 更多", color = TimeBlockColors.InkSecondary, fontSize = 7.5.sp, maxLines = 1)
                        }
                    }
                    TimeBlockProgressBar(progress.ratio)
                }
            }
        }
    }
}

@Composable
internal fun TimelineTimeBlockView(
    entries: List<TimeBlockEntry>,
    date: LocalDate,
    today: LocalDate,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    onEntryClick: (TimeBlockEntry) -> Unit,
    onToggle: (TimeBlockEntry) -> Unit,
    onShiftOneDay: (TimeBlockEntry) -> Unit,
    onStartFocus: (TimeBlockEntry) -> Unit,
    onFillGaps: () -> Unit,
    onMoveExpired: () -> Unit,
    onOpenExpired: () -> Unit,
    onMoveToTime: (TimeBlockEntry, LocalDate, Int) -> Unit,
    onInboxDrop: (String, LocalDate, Int?) -> Unit,
    onCreateAt: (LocalDate, Int) -> Unit,
    onDelete: (TimeBlockEntry) -> Unit
) {
    val dayEntries = entriesOnDate(entries, date, zoneId)
    val progress = dayProgress(dayEntries)
    val allDay = dayEntries.filter { it.kind(zoneId) == TimeBlockEntryKind.ALL_DAY_TASK }
    val timed = dayEntries.filter {
        it.kind(zoneId) in setOf(
            TimeBlockEntryKind.EVENT,
            TimeBlockEntryKind.TIME_BLOCK_TASK,
            TimeBlockEntryKind.DEADLINE_TASK
        )
    }.sortedBy { it.scheduledStartEpochMillis ?: it.dueAtEpochMillis }
    val expired = if (date == today) {
        entries.filter { isExpired(it, nowEpochMillis, zoneId) }
    } else {
        emptyList()
    }
    val nowMinute = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalTime().toSecondOfDay() / 60
    val earlier = if (date == today) timed.filter { entryEndMinute(it, zoneId) <= nowMinute } else emptyList()
    val upcoming = timed.filterNot(earlier::contains)
    var earlierExpanded by remember(date) { mutableStateOf(true) }
    var expiredDismissed by remember(date) { mutableStateOf(false) }
    val calendarLabel = timeBlockCalendarLabel(date)
    val fallbackDropTarget = rememberTimeBlockInboxDropTarget { payload, _ ->
        onInboxDrop(payload.todoId, date, null)
    }
    LazyColumn(
        Modifier.fillMaxSize()
            .then(
                if (fallbackDropTarget.isActive) Modifier.background(TimeBlockColors.Work.copy(alpha = 0.045f))
                else Modifier
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                target = fallbackDropTarget.target
            ),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item("timeline-header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date.dayOfMonth.toString(),
                    color = if (date == today) TimeBlockColors.Current else TimeBlockColors.Ink,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${date.monthValue}月 · ${date.weekdayLabel()}" +
                            calendarLabel?.let { " · ${it.text}" }.orEmpty(),
                        color = if (calendarLabel?.isSolarTerm == true) Color(0xFF0B7A4E)
                        else TimeBlockColors.InkSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${date.year}年",
                        color = TimeBlockColors.Ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                TimeBlockProgressRing(progress.ratio, "${progress.completed}/${progress.total}", Modifier.size(46.dp))
            }
        }
        if (expired.isNotEmpty() && !expiredDismissed) {
            item("expired") {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFFFFF4F4), RoundedCornerShape(12.dp))
                        .border(1.dp, TimeBlockColors.High.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .noRippleClickable(onOpenExpired)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = TimeBlockColors.High,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("${expired.size} 项待办已过期", color = TimeBlockColors.High, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("全部顺延到今天", color = TimeBlockColors.High, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.noRippleClickable(onMoveExpired).padding(5.dp))
                    Text("稍后", color = TimeBlockColors.InkSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.noRippleClickable { expiredDismissed = true }.padding(5.dp))
                }
            }
        }
        if (date == today) {
            item("next") {
                val next = upcoming.firstOrNull {
                    it.scheduledStartEpochMillis != null && entryStartMinute(it, zoneId) > nowMinute
                }
                Row(
                    Modifier.background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).background(TimeBlockColors.Focus, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        next?.let {
                            val startMinute = entryStartMinute(it, zoneId)
                            "接下来 · ${it.title} ${formatMinute(startMinute)} · ${startMinute - nowMinute} 分钟后"
                        }
                            ?: "今天没有更多日程了",
                        color = BrandColors.Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item("timeline-stats") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("剩余 ${progress.total - progress.completed} 项 · 已完成 ${progress.completed}/${progress.total}", color = TimeBlockColors.InkTertiary, fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (date == today) {
                    Row(
                        Modifier.background(Color(0xFFFFF4D9), RoundedCornerShape(999.dp))
                            .noRippleClickable(onFillGaps).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, null, tint = TimeBlockColors.Amber, modifier = Modifier.size(14.dp))
                        Text("填充空档", color = TimeBlockColors.Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (allDay.isNotEmpty()) {
            item("all-day-title") { SectionLabel("全天 · ${allDay.size}") }
            item("all-day-items") {
                val dropTarget = rememberTimeBlockInboxDropTarget { payload, _ ->
                    onInboxDrop(payload.todoId, date, null)
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(
                            if (dropTarget.isActive) TimeBlockColors.Work.copy(alpha = 0.1f)
                            else Color.Transparent,
                            RoundedCornerShape(9.dp)
                        )
                        .dragAndDropTarget(
                            shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                            target = dropTarget.target
                        )
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    allDay.forEach { entry ->
                        TimeBlockEntryChip(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            modifier = Modifier.width(130.dp),
                            onToggle = { onToggle(entry) }
                        )
                    }
                }
            }
        }
        if (earlier.isNotEmpty()) {
            item("earlier-header") {
                Row(
                    Modifier.fillMaxWidth().clickable { earlierExpanded = !earlierExpanded }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (earlierExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = TimeBlockColors.InkSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text("今天早些时候 · ${earlier.size} 项", color = TimeBlockColors.InkSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (earlierExpanded) {
                items(earlier, key = { "earlier:${it.stableKey}" }) { entry ->
                    TimelineEntryRow(
                        entry = entry,
                        date = date,
                        zoneId = zoneId,
                        past = true,
                        onEntryClick = onEntryClick,
                        onToggle = onToggle,
                        onShiftOneDay = onShiftOneDay,
                        onStartFocus = onStartFocus,
                        onMoveToTime = onMoveToTime,
                        onDelete = onDelete
                    )
                }
            }
        }
        if (date == today) {
            item("now-line") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatMinute(nowMinute), color = Color.White, fontFamily = TimeBlockMono, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(TimeBlockColors.Current, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.size(9.dp).background(TimeBlockColors.Current, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text("现在", color = TimeBlockColors.Current, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(5.dp))
                    Box(Modifier.height(2.dp).weight(1f).background(TimeBlockColors.Current))
                }
            }
        }
        val timelineItems = if (date == today) upcoming else timed
        var lastEnd: Int? = if (date == today) snapMinute(nowMinute, roundUp = true) else null
        var lastBucket: String? = null
        timelineItems.forEach { entry ->
            val start = entryStartMinute(entry, zoneId)
            val bucket = when {
                start < 12 * 60 -> "上午"
                start < 18 * 60 -> "下午"
                else -> "晚上"
            }
            if (bucket != lastBucket) {
                item("bucket:$bucket:${entry.stableKey}") { SectionLabel(bucket) }
                lastBucket = bucket
            }
            if (entry.scheduledStartEpochMillis != null && entry.scheduledEndEpochMillis != null) {
                if (lastEnd != null && start - requireNotNull(lastEnd) >= TIME_SNAP_MINUTES) {
                    val gapStart = requireNotNull(lastEnd)
                    item("gap:${entry.stableKey}:$gapStart") {
                        TimelineGap(
                            date = date,
                            start = gapStart,
                            end = start,
                            onClick = { onCreateAt(date, gapStart) },
                            onInboxDrop = onInboxDrop
                        )
                    }
                }
                lastEnd = maxOf(lastEnd ?: start, entryEndMinute(entry, zoneId))
            }
            item("entry:${entry.stableKey}") {
                TimelineEntryRow(
                    entry = entry,
                    date = date,
                    zoneId = zoneId,
                    past = false,
                    onEntryClick = onEntryClick,
                    onToggle = onToggle,
                    onShiftOneDay = onShiftOneDay,
                    onStartFocus = onStartFocus,
                    onMoveToTime = onMoveToTime,
                    onDelete = onDelete
                )
            }
        }
        if (dayEntries.isEmpty()) {
            item("empty") {
                Text("这一天没有安排，享受留白 🌿", color = TimeBlockColors.InkTertiary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else if (date == today && timelineItems.isEmpty()) {
            item("no-more-today") {
                val hour = java.time.LocalTime.now().hour
                val emptyMessage = when {
                    hour in 5..8 -> "今天还没有安排日程，元气满满的清晨 ✨"
                    hour in 9..11 -> "上午暂时没有更多日程，专心做好眼前的事吧 🚀"
                    hour in 12..13 -> "中午休息时间，吃个好午饭，给身体充充电 🍱"
                    hour in 14..17 -> "下午暂时没有更多日程，喝杯咖啡，继续加油 ☕"
                    hour in 18..21 -> "今天的工作快完结啦，享受轻松的傍晚时光 🌆"
                    else -> "今天没有更多日程了，早点休息 🌙"
                }
                Text(emptyMessage, color = TimeBlockColors.InkTertiary, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun TimelineEntryRow(
    entry: TimeBlockEntry,
    date: LocalDate,
    zoneId: ZoneId,
    past: Boolean,
    onEntryClick: (TimeBlockEntry) -> Unit,
    onToggle: (TimeBlockEntry) -> Unit,
    onShiftOneDay: (TimeBlockEntry) -> Unit,
    onStartFocus: (TimeBlockEntry) -> Unit,
    onMoveToTime: (TimeBlockEntry, LocalDate, Int) -> Unit,
    onDelete: (TimeBlockEntry) -> Unit
) {
    var offsetY by remember(entry.stableKey) { mutableFloatStateOf(0f) }
    var isVerticalDragging by remember(entry.stableKey) { mutableStateOf(false) }
    val density = LocalDensity.current
    val color = if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) TimeBlockColors.High else projectColor(entry.project)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(54.dp)) {
            Text(formatMinute(entryStartMinute(entry, zoneId)), color = if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) TimeBlockColors.High else TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
            Text(if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) "截止" else formatMinute(entryEndMinute(entry, zoneId)), color = TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 8.sp)
        }
        Box(Modifier.width(16.dp).height(58.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(TimeBlockColors.Line))
            Box(Modifier.size(10.dp).background(color, CircleShape).border(2.dp, Color.White, CircleShape))
        }
        Box(Modifier.weight(1f).heightIn(min = 56.dp)) {
            SwipeToDeleteContainer(
                itemName = "日程",
                shape = RoundedCornerShape(10.dp),
                onDelete = { onDelete(entry) }
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .offset { IntOffset(0, offsetY.roundToInt()) }
                        .zIndex(if (isVerticalDragging) 3f else 0f)
                        .alpha(if (past || entry.isCompleted) 0.58f else 1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) BrandColors.SurfaceCard else color.softSurface())
                        .then(
                            if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) {
                                Modifier.dashedBorder(color = TimeBlockColors.High)
                            } else {
                                Modifier.drawBehind { drawRect(color, size = Size(3.5.dp.toPx(), size.height)) }
                            }
                        )
                        .pointerInput(entry.stableKey, date) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    isVerticalDragging = true
                                },
                                onDragEnd = {
                                    val halfHourDistance = with(density) { 35.dp.toPx() }
                                    val minuteDelta = (offsetY / halfHourDistance * TIME_SNAP_MINUTES)
                                        .roundToInt()
                                    if (kotlin.math.abs(minuteDelta) >= TIME_SNAP_MINUTES) {
                                        onMoveToTime(
                                            entry,
                                            date,
                                            snapMinute(entryStartMinute(entry, zoneId) + minuteDelta)
                                        )
                                    }
                                    offsetY = 0f
                                    isVerticalDragging = false
                                },
                                onDragCancel = {
                                    offsetY = 0f
                                    isVerticalDragging = false
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    offsetY += amount.y
                                }
                            )
                        }
                        .clickable { onEntryClick(entry) }
                        .padding(start = 10.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title + if (entry.isHighPriority) "  急" else "", color = if (entry.isCompleted) TimeBlockColors.InkTertiary else TimeBlockColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (entry.isCompleted) TextDecoration.LineThrough else null)
                        val metadata = if (entry.kind(zoneId) == TimeBlockEntryKind.DEADLINE_TASK) {
                            "Deadline · ${entry.primaryDate(zoneId)?.monthDayLabel().orEmpty()}"
                        } else {
                            "${formatDurationMinutes(entry.durationMinutes())} · ${entry.project}"
                        }
                        Text(metadata, color = TimeBlockColors.InkTertiary, fontSize = 9.sp, fontFamily = TimeBlockMono, maxLines = 1)
                    }
                    if (!entry.isCompleted) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).clickable { onStartFocus(entry) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, "开始专注", tint = color, modifier = Modifier.size(17.dp))
                        }
                    }
                    TimeBlockCompletionButton(entry.isCompleted, color, { onToggle(entry) }, Modifier.size(20.dp))
                }
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectHorizontalDragGesturesWithEnd(
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit
) {
    detectHorizontalDragGestures(
        onHorizontalDrag = { change, amount ->
            change.consume()
            onDrag(amount)
        },
        onDragEnd = onEnd,
        onDragCancel = onEnd
    )
}

@Composable
private fun TimelineGap(
    date: LocalDate,
    start: Int,
    end: Int,
    onClick: () -> Unit,
    onInboxDrop: (String, LocalDate, Int?) -> Unit
) {
    val dropTarget = rememberTimeBlockInboxDropTarget { payload, _ ->
        onInboxDrop(payload.todoId, date, start)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 70.dp).height(35.dp)
            .background(
                if (dropTarget.isActive) TimeBlockColors.Work.copy(alpha = 0.12f)
                else Color.Transparent,
                RoundedCornerShape(9.dp)
            )
            .dashedBorder(color = TimeBlockColors.Work.copy(alpha = 0.45f))
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                target = dropTarget.target
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${formatMinute(start)} – ${formatMinute(end)}", color = TimeBlockColors.InkSecondary, fontFamily = TimeBlockMono, fontSize = 9.sp)
        Spacer(Modifier.weight(1f))
        Text("空档 ${end - start}' · 点按或拖入", color = TimeBlockColors.InkTertiary, fontSize = 9.sp)
    }
}

@Composable
private fun CompactEntryRow(
    entry: TimeBlockEntry,
    zoneId: ZoneId,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(entry.timeLabel(zoneId).substringBefore(" "), color = TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 8.5.sp, modifier = Modifier.width(35.dp))
        ProjectDot(entry.project)
        Text(entry.title, color = if (entry.isCompleted) TimeBlockColors.InkTertiary else TimeBlockColors.Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (entry.isCompleted) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
        TimeBlockCompletionButton(entry.isCompleted, projectColor(entry.project), onToggle, Modifier.size(18.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TimeBlockColors.InkSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 3.dp))
}

private fun loadColor(count: Int): Color = when {
    count <= 0 -> Color(0xFFE6EAF1)
    count <= 2 -> TimeBlockColors.Life
    count <= 4 -> TimeBlockColors.Amber
    else -> TimeBlockColors.High
}

private fun entryStartMinute(entry: TimeBlockEntry, zoneId: ZoneId): Int =
    (entry.scheduledStartEpochMillis ?: entry.dueAtEpochMillis)?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalTime().toSecondOfDay() / 60
    } ?: 0

private fun entryEndMinute(entry: TimeBlockEntry, zoneId: ZoneId): Int =
    (entry.scheduledEndEpochMillis ?: entry.dueAtEpochMillis)?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalTime().toSecondOfDay() / 60
    } ?: entryStartMinute(entry, zoneId)
