package com.example.controlfree.ui.todo.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodoScheduleView(
    todos: List<TodoItemUi>,
    modifier: Modifier = Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    onTodoClick: (TodoItemUi) -> Unit
) {
    var mode by rememberSaveable { mutableStateOf(TodoCalendarMode.DAY) }
    var selectedDateEpochDay by rememberSaveable { mutableStateOf(LocalDate.now(zoneId).toEpochDay()) }
    val selectedDate = LocalDate.ofEpochDay(selectedDateEpochDay)
    val dates = remember(mode, selectedDateEpochDay) {
        when (mode) {
            TodoCalendarMode.DAY -> listOf(selectedDate, selectedDate.plusDays(1), selectedDate.plusDays(2))
            TodoCalendarMode.WEEK -> weekDates(selectedDate)
            TodoCalendarMode.MONTH -> {
                val firstDay = selectedDate.withDayOfMonth(1)
                val length = selectedDate.lengthOfMonth()
                (0 until length).map { firstDay.plusDays(it.toLong()) }
            }
        }
    }
    val placements = remember(todos, dates, zoneId) {
        calendarPlacements(todos.filterNot(TodoItemUi::isCompleted), dates, zoneId)
    }
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()
    LaunchedEffect(mode) {
        verticalScroll.scrollTo((DEFAULT_VISIBLE_HOUR * HOUR_HEIGHT_DP).toInt())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 排程日期单独一行：标题不与视图切换共享横向空间，避免被挤压换行。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                selectedDateEpochDay -= when (mode) {
                    TodoCalendarMode.DAY -> 3
                    TodoCalendarMode.WEEK -> 7
                    TodoCalendarMode.MONTH -> selectedDate.lengthOfMonth().toLong()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一周期")
            }
            Text(
                text = scheduleTitle(mode, selectedDate),
                color = BrandColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                selectedDateEpochDay += when (mode) {
                    TodoCalendarMode.DAY -> 3
                    TodoCalendarMode.WEEK -> 7
                    TodoCalendarMode.MONTH -> selectedDate.lengthOfMonth().toLong()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "下一周期")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { selectedDateEpochDay = LocalDate.now(zoneId).toEpochDay() }) {
                Text("今天")
            }
            Spacer(Modifier.weight(1f))
            TodoCalendarMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    label = { Text(option.displayName) },
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }

        DueOnlyStrip(
            todos = todos,
            dates = dates,
            zoneId = zoneId,
            onTodoClick = onTodoClick
        )

        if (mode == TodoCalendarMode.MONTH) {
            MonthCalendarPane(
                selectedDate = selectedDate,
                placements = placements,
                zoneId = zoneId,
                onSelectDate = { selectedDateEpochDay = it.toEpochDay() },
                onTodoClick = onTodoClick
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // 周视图把 7 天等分到一屏内；日视图保持固定列宽横向滚动。
                val dayColumnWidth = if (mode == TodoCalendarMode.WEEK) {
                    ((maxWidth - TIME_LABEL_WIDTH) / dates.size).coerceAtLeast(36.dp)
                } else {
                    DAY_COLUMN_WIDTH
                }
                Row(modifier = Modifier.horizontalScroll(horizontalScroll)) {
                    Column {
                        ScheduleDayHeaders(dates, dayColumnWidth)
                        Row(modifier = Modifier.verticalScroll(verticalScroll)) {
                            HourLabels()
                            Box(
                                modifier = Modifier
                                    .width(dayColumnWidth * dates.size)
                                    .height(HOUR_HEIGHT * HOURS_PER_DAY)
                                    .background(BrandColors.Surface)
                            ) {
                                ScheduleGrid(dates.size, dayColumnWidth)
                                placements.forEach { placement ->
                                    CalendarEventBlock(
                                        placement = placement,
                                        dayIndex = dates.indexOf(placement.date),
                                        dayColumnWidth = dayColumnWidth,
                                        onClick = { onTodoClick(placement.todo) }
                                    )
                                }
                                val todayIndex = dates.indexOf(LocalDate.now(zoneId))
                                if (todayIndex >= 0) {
                                    CurrentTimeIndicator(todayIndex, zoneId, dayColumnWidth)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendarPane(
    selectedDate: LocalDate,
    placements: List<TodoCalendarPlacement>,
    zoneId: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
    onTodoClick: (TodoItemUi) -> Unit
) {
    val byDate = remember(placements) { placements.groupBy(TodoCalendarPlacement::date) }
    val firstOfMonth = selectedDate.withDayOfMonth(1)
    val leadingDays = (firstOfMonth.dayOfWeek.value + 6) % 7
    val gridStart = firstOfMonth.minusDays(leadingDays.toLong())
    val weekCount = (leadingDays + selectedDate.lengthOfMonth() + 6) / 7
    val today = LocalDate.now(zoneId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { name ->
                Text(
                    text = name,
                    color = BrandColors.TextTertiary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        repeat(weekCount) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { dayOfWeek ->
                    val date = gridStart.plusDays((week * 7 + dayOfWeek).toLong())
                    val inMonth = date.month == selectedDate.month
                    val dayPlacements = byDate[date].orEmpty()
                    val isSelected = date == selectedDate
                    val isToday = date == today
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isSelected -> BrandColors.PrimaryContainer
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onSelectDate(date) }
                            .padding(vertical = 5.dp)
                    ) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            fontSize = 13.sp,
                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isToday -> BrandColors.Primary
                                inMonth -> BrandColors.TextPrimary
                                else -> BrandColors.TextTertiary
                            }
                        )
                        Row(
                            modifier = Modifier.height(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            repeat(minOf(dayPlacements.size, 3)) { index ->
                                Spacer(
                                    Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (dayPlacements.getOrNull(index)?.todo?.isImportant == true) {
                                                BrandColors.Warning
                                            } else {
                                                BrandColors.Primary
                                            }
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = "${selectedDate.format(monthDayFormatter)} 排程",
            color = BrandColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp)
        )
        val dayItems = byDate[selectedDate].orEmpty().sortedBy(TodoCalendarPlacement::startMinute)
        if (dayItems.isEmpty()) {
            Text(
                text = "当日无排程",
                color = BrandColors.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )
        } else {
            dayItems.forEach { placement ->
                Surface(
                    onClick = { onTodoClick(placement.todo) },
                    color = if (placement.todo.isImportant) {
                        BrandColors.WarningContainer
                    } else {
                        BrandColors.PrimaryContainer
                    },
                    contentColor = BrandColors.TextPrimary,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "%02d:%02d - %02d:%02d".format(
                                placement.startMinute / 60,
                                placement.startMinute % 60,
                                placement.endMinuteExclusive / 60,
                                placement.endMinuteExclusive % 60
                            ),
                            color = BrandColors.TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = placement.todo.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ScheduleDayHeaders(dates: List<LocalDate>, dayColumnWidth: Dp) {
    Row(modifier = Modifier.padding(start = TIME_LABEL_WIDTH)) {
        dates.forEach { date ->
            val isToday = date == LocalDate.now()
            Column(
                modifier = Modifier.width(dayColumnWidth).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    dayOfWeekFormatter.format(date),
                    color = if (isToday) BrandColors.Primary else BrandColors.TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    date.dayOfMonth.toString(),
                    color = if (isToday) BrandColors.Primary else BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HourLabels() {
    Column(modifier = Modifier.width(TIME_LABEL_WIDTH)) {
        repeat(HOURS_PER_DAY) { hour ->
            Text(
                text = "%02d:00".format(hour),
                color = BrandColors.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.height(HOUR_HEIGHT).padding(top = 2.dp, end = 5.dp)
            )
        }
    }
}

@Composable
private fun ScheduleGrid(dayCount: Int, dayColumnWidth: Dp) {
    repeat(HOURS_PER_DAY + 1) { hour ->
        Spacer(
            modifier = Modifier
                .offset(y = HOUR_HEIGHT * hour)
                .width(dayColumnWidth * dayCount)
                .height(1.dp)
                .background(BrandColors.OutlineSoft)
        )
    }
    repeat(dayCount + 1) { day ->
        Spacer(
            modifier = Modifier
                .offset(x = dayColumnWidth * day)
                .width(1.dp)
                .fillMaxHeight()
                .background(BrandColors.OutlineSoft)
        )
    }
}

@Composable
private fun CalendarEventBlock(
    placement: TodoCalendarPlacement,
    dayIndex: Int,
    dayColumnWidth: Dp,
    onClick: () -> Unit
) {
    if (dayIndex < 0) return
    val laneWidth = (dayColumnWidth.value / placement.laneCount).dp
    val blockHeight = minutesToDp(
        placement.endMinuteExclusive - placement.startMinute
    ).coerceAtLeast(MIN_EVENT_HEIGHT)
    Surface(
        onClick = onClick,
        color = if (placement.todo.isImportant) {
            BrandColors.WarningContainer
        } else {
            BrandColors.PrimaryContainer
        },
        contentColor = BrandColors.TextPrimary,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .offset(
                x = dayColumnWidth * dayIndex + laneWidth * placement.lane,
                y = minutesToDp(placement.startMinute)
            )
            .width((laneWidth - 3.dp).coerceAtLeast(26.dp))
            .height(blockHeight)
            .padding(1.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
            Text(
                placement.todo.title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (blockHeight >= 48.dp) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (blockHeight >= 38.dp) {
                Text(
                    "%02d:%02d".format(
                        placement.startMinute / 60,
                        placement.startMinute % 60
                    ),
                    color = BrandColors.TextSecondary,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun DueOnlyStrip(
    todos: List<TodoItemUi>,
    dates: List<LocalDate>,
    zoneId: ZoneId,
    onTodoClick: (TodoItemUi) -> Unit
) {
    val dateSet = dates.toSet()
    val dueOnly = remember(todos, dates, zoneId) {
        todos.filter { todo ->
            !todo.isCompleted &&
                todo.scheduledStartEpochMillis == null &&
                todo.dueAtEpochMillis?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() in dateSet
                } == true
        }
    }
    if (dueOnly.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        dueOnly.forEach { todo ->
            Surface(
                onClick = { onTodoClick(todo) },
                color = BrandColors.SurfaceRaised,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.padding(end = 6.dp).border(
                    width = 1.dp,
                    color = BrandColors.OutlineSoft,
                    shape = RoundedCornerShape(4.dp)
                )
            ) {
                Text(
                    text = "截止 · ${todo.title}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    fontSize = 11.sp,
                    color = BrandColors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CurrentTimeIndicator(dayIndex: Int, zoneId: ZoneId, dayColumnWidth: Dp) {
    val now = java.time.ZonedDateTime.now(zoneId)
    val minute = now.hour * 60 + now.minute
    Row(
        modifier = Modifier.offset(
            x = dayColumnWidth * dayIndex,
            y = minutesToDp(minute)
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(BrandColors.Danger)
        )
        Spacer(
            Modifier
                .width((dayColumnWidth - 6.dp).coerceAtLeast(0.dp))
                .height(1.dp)
                .background(BrandColors.Danger)
        )
    }
}

private fun minutesToDp(minutes: Int): Dp =
    (minutes.coerceIn(0, HOURS_PER_DAY * 60) * HOUR_HEIGHT_DP / 60f).dp

private fun scheduleTitle(mode: TodoCalendarMode, selectedDate: LocalDate): String =
    when (mode) {
        TodoCalendarMode.DAY -> {
            val end = selectedDate.plusDays(2)
            "${selectedDate.format(shortDateFormatter)} - ${end.format(shortDateFormatter)}"
        }
        TodoCalendarMode.WEEK -> {
            val dates = weekDates(selectedDate)
            "${dates.first().format(shortDateFormatter)} - ${dates.last().format(shortDateFormatter)}"
        }
        TodoCalendarMode.MONTH -> {
            selectedDate.format(DateTimeFormatter.ofPattern("yyyy年M月"))
        }
    }

private val shortDateFormatter = DateTimeFormatter.ofPattern("M月d日")
private val monthDayFormatter = DateTimeFormatter.ofPattern("M月d日")
private val dayOfWeekFormatter = DateTimeFormatter.ofPattern("EEE")
private val HOUR_HEIGHT = 64.dp
private val DAY_COLUMN_WIDTH = 112.dp
private val TIME_LABEL_WIDTH = 46.dp
private val MIN_EVENT_HEIGHT = 24.dp
private const val HOUR_HEIGHT_DP = 64f
private const val HOURS_PER_DAY = 24
private const val DEFAULT_VISIBLE_HOUR = 7
