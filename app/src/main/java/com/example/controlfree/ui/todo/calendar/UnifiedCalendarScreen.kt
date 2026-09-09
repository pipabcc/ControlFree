package com.example.controlfree.ui.todo.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.productivity.calendar.CalendarDaySummary
import com.example.controlfree.productivity.calendar.CalendarDateRange
import com.example.controlfree.productivity.calendar.CalendarEventType
import com.example.controlfree.productivity.calendar.CalendarRangeStatistics
import com.example.controlfree.productivity.calendar.ProductivityCalendarEvent
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.LedgerAmountCodec
import com.example.controlfree.todo.LedgerDirection
import com.example.controlfree.ui.todo.viewmodel.SmartScheduleSuggestionUi
import com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarViewModel
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun UnifiedCalendarScreen(
    viewModel: UnifiedCalendarViewModel,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val suggestion by viewModel.scheduleSuggestion.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(searchNavigationRequest?.revision) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        request.targetDate?.let(viewModel::selectDate)
        onSearchNavigationConsumed(request)
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    var viewMode by remember { mutableStateOf(CalendarViewMode.MONTH) }
    var expandedEventGroupKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val today = state.today
    val visibleDates = visibleDatesFor(
        viewMode = viewMode,
        today = today
    )
    val visibleStatistics = state.statistics(statisticsRangeFor(visibleDates))

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BrandColors.PageBackground,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 视图切换 Toggle
            item {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
                        .height(38.dp)
                        .padding(3.dp)
                ) {
                    val modes = listOf(
                        CalendarViewMode.DAY to "三日视图",
                        CalendarViewMode.WEEK to "周视图 (7天)",
                        CalendarViewMode.MONTH to "月视图"
                    )
                    val segmentWidth = maxWidth / modes.size
                    val activeIndex = modes.indexOfFirst { it.first == viewMode }
                    val indicatorOffset by animateDpAsState(
                        targetValue = segmentWidth * activeIndex,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "日程统计视图分段器弹性滑块"
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    )
                    Row(Modifier.fillMaxSize()) {
                        modes.forEach { (mode, label) ->
                            val selected = viewMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    ) { viewMode = mode },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) BrandColors.Primary else BrandColors.TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            when (viewMode) {
                CalendarViewMode.MONTH -> {
                    item {
                        RecentMonthGrid(
                            dates = visibleDates,
                            state = state,
                            today = today,
                            viewModel = viewModel
                        )
                    }
                    item {
                        CalendarStatisticsCard(
                            rangeLabel = "近30日统计",
                            statistics = visibleStatistics
                        )
                    }
                    item { HorizontalDivider(color = BrandColors.BackgroundDivider) }
                    item {
                        CalendarEventsAggregationCard(
                            date = state.selectedDate,
                            events = state.selectedDay?.events.orEmpty(),
                            nowEpochMillis = state.nowEpochMillis,
                            expandedEventGroupKeys = expandedEventGroupKeys,
                            onToggleEventGroup = { key ->
                                expandedEventGroupKeys = expandedEventGroupKeys.toggled(key)
                            }
                        )
                    }
                }
                CalendarViewMode.WEEK -> {
                    item {
                        WeekViewGrid(
                            dates = visibleDates,
                            state = state,
                            today = today,
                            viewModel = viewModel
                        )
                    }
                    item {
                        CalendarStatisticsCard(
                            rangeLabel = "近7日统计",
                            statistics = visibleStatistics
                        )
                    }
                    item { HorizontalDivider(color = BrandColors.BackgroundDivider) }
                    visibleDates.forEach { date ->
                        val daySummary = state.days[date]
                        val events = daySummary?.events.orEmpty()
                        val isToday = date == today
                        item {
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = BrandColors.SurfaceCard
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${date.monthValue}月${date.dayOfMonth}日 · " +
                                                date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.SIMPLIFIED_CHINESE),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = if (isToday) BrandColors.Primary else BrandColors.TextPrimary
                                        )
                                        if (isToday) {
                                            Text("今天", color = BrandColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    if (events.isEmpty()) {
                                        Text("暂无排程", color = BrandColors.TextTertiary, fontSize = 11.sp)
                                    } else {
                                        CalendarDayEventRows(
                                            date = date,
                                            events = events,
                                            nowEpochMillis = state.nowEpochMillis,
                                            expandedEventGroupKeys = expandedEventGroupKeys,
                                            onToggleEventGroup = { key ->
                                                expandedEventGroupKeys = expandedEventGroupKeys.toggled(key)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                CalendarViewMode.DAY -> {
                    item {
                        CalendarStatisticsCard(
                            rangeLabel = "三日统计",
                            statistics = visibleStatistics
                        )
                    }
                    item {
                        ThreeDayLayout(
                            state = state,
                            dates = visibleDates,
                            expandedEventGroupKeys = expandedEventGroupKeys,
                            onToggleEventGroup = { key ->
                                expandedEventGroupKeys = expandedEventGroupKeys.toggled(key)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentMonthGrid(
    dates: List<LocalDate>,
    state: com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarUiState,
    today: LocalDate,
    viewModel: UnifiedCalendarViewModel
) {
    val cells = alignedDateGridCells(dates)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 11.sp,
                    color = BrandColors.BackgroundTextTertiary
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(0.65f))
                    } else {
                        CalendarDayCell(
                            date = date,
                            summary = state.days[date],
                            selected = date == state.selectedDate,
                            today = date == today,
                            onClick = { viewModel.selectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    summary: CalendarDaySummary?,
    selected: Boolean,
    today: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(7.dp)
    val background = when {
        selected -> BrandColors.Primary.copy(alpha = 0.12f)
        else -> BrandColors.SurfaceCard
    }
    val border = if (today) BorderStroke(1.dp, BrandColors.Primary) else null
    Surface(
        onClick = onClick,
        modifier = modifier
            .aspectRatio(0.65f),
        shape = shape,
        color = background,
        border = border,
        tonalElevation = if (selected) 1.dp else 0.dp
    ) {
        Column(modifier = Modifier.padding(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "${date.monthValue}/${date.dayOfMonth}",
                fontSize = 11.sp,
                fontWeight = if (today) FontWeight.Bold else FontWeight.Normal,
                color = BrandColors.TextPrimary
            )
            val events = summary?.events.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                events.take(MAX_MONTH_CELL_EVENTS).forEach { event ->
                    Text(
                        text = event.title,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = eventColor(event.type),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(eventColor(event.type).copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 2.dp, vertical = 0.5.dp)
                    )
                }
            }
            val hasAnniversary = events.any { it.type == CalendarEventType.ANNIVERSARY }
            if (events.size > MAX_MONTH_CELL_EVENTS && !hasAnniversary) {
                Text(
                    "+${events.size - MAX_MONTH_CELL_EVENTS}",
                    fontSize = 9.sp,
                    color = Color.Red,
                    maxLines = 1
                )
            } else if (summary != null && summary.focusMillis > 0L) {
                Text(
                    formatFocusMinutes(summary.focusMillis),
                    fontSize = 9.sp,
                    color = BrandColors.Primary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CalendarStatisticsCard(
    rangeLabel: String,
    statistics: CalendarRangeStatistics
) {
    val topMetrics = listOf(
        CalendarMetric(
            "待办", statistics.todoCount.toString(),
            icon = Icons.Filled.CheckCircle, iconTint = BrandColors.Primary
        ),
        CalendarMetric(
            "习惯", "${statistics.completedHabits}/${statistics.dueHabits}",
            icon = Icons.Filled.DirectionsRun, iconTint = BrandColors.Success
        ),
        CalendarMetric(
            "时刻", statistics.anniversaryCount.toString(),
            icon = Icons.Filled.Favorite, iconTint = BrandColors.Danger
        )
    )
    val bottomMetrics = listOf(
        CalendarMetric(
            "专注", formatFocusMinutes(statistics.focusMillis),
            icon = Icons.Filled.PlayCircle, iconTint = BrandColors.AppAccent
        ),
        CalendarMetric(
            label = "监督",
            value = "${statistics.supervisionCount}次",
            supporting = formatDurationSupporting(statistics.supervisionMillis),
            icon = Icons.Filled.Visibility, iconTint = BrandColors.Warning
        ),
        CalendarMetric(
            label = "账本",
            value = "${statistics.ledgerCount}笔",
            supporting = ledgerMetricSupporting(
                statistics.ledgerIncomeFen,
                statistics.ledgerExpenseFen
            ),
            icon = Icons.Filled.AccountBalanceWallet, iconTint = BrandColors.Secondary
        )
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = BrandColors.Surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 标题区域
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            BrandColors.PrimaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CalendarMonth,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = BrandColors.Primary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = rangeLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary
                )
            }
            // 第一行指标
            StatisticsGridRow(metrics = topMetrics)
            Spacer(Modifier.height(8.dp))
            // 第二行指标
            StatisticsGridRow(metrics = bottomMetrics)
        }
    }
}

@Composable
private fun StatisticsGridRow(metrics: List<CalendarMetric>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        metrics.forEach { metric ->
            MetricCell(
                metric = metric,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricCell(metric: CalendarMetric, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 图标圆形背景
            metric.icon?.let { icon ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            metric.iconTint.copy(alpha = 0.12f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = metric.iconTint
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // 数值 + 辅助文字（水平排列，避免撑高卡片）
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = metric.value,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                metric.supporting?.let { supporting ->
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = supporting,
                        fontSize = 9.sp,
                        color = BrandColors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 标签
            Text(
                text = metric.label,
                fontSize = 11.sp,
                color = BrandColors.TextSecondary
            )
        }
    }
}

@Composable
private fun CalendarEventRow(event: ProductivityCalendarEvent, nowEpochMillis: Long) {
    val icon = when (event.type) {
        CalendarEventType.PLANNED_TODO -> Icons.Default.Flag
        CalendarEventType.COMPLETED_TODO,
        CalendarEventType.HABIT_COMPLETED -> Icons.Default.CheckCircle
        CalendarEventType.HABIT_DUE -> Icons.Default.Today
        CalendarEventType.ANNIVERSARY -> Icons.Default.Event
        CalendarEventType.FOCUS -> Icons.Default.PlayCircle
        CalendarEventType.SUPERVISION -> Icons.Default.Visibility
        CalendarEventType.LEDGER -> Icons.Default.AccountBalanceWallet
    }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = eventColor(event.type), modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(event.title, color = BrandColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(eventSupportingLabel(event), fontSize = 11.sp, color = BrandColors.TextSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = eventTrailingText(event),
                    fontSize = 11.sp,
                    color = eventTrailingColor(event)
                )
                eventSecondaryTrailingText(event, nowEpochMillis)?.let { secondary ->
                    Text(
                        text = secondary,
                        fontSize = 10.sp,
                        color = BrandColors.TextTertiary
                    )
                }
            }
        }
    }
}

private fun eventColor(type: CalendarEventType): Color = when (type) {
    CalendarEventType.PLANNED_TODO -> Color(0xFF2D6CDF)
    CalendarEventType.COMPLETED_TODO -> Color(0xFF3A9D5D)
    CalendarEventType.HABIT_DUE -> Color(0xFF8B6FD1)
    CalendarEventType.HABIT_COMPLETED -> Color(0xFF2E9B73)
    CalendarEventType.ANNIVERSARY -> Color(0xFFD6812D)
    CalendarEventType.FOCUS -> Color(0xFF1B8A9B)
    CalendarEventType.SUPERVISION -> Color(0xFF5267A8)
    CalendarEventType.LEDGER -> Color(0xFF9A6540)
}

private fun eventTypeLabel(type: CalendarEventType): String = when (type) {
    CalendarEventType.PLANNED_TODO -> "待办排程"
    CalendarEventType.COMPLETED_TODO -> "已完成待办"
    CalendarEventType.HABIT_DUE -> "习惯目标"
    CalendarEventType.HABIT_COMPLETED -> "习惯达标"
    CalendarEventType.ANNIVERSARY -> "时刻"
    CalendarEventType.FOCUS -> "专注记录"
    CalendarEventType.SUPERVISION -> "监督记录"
    CalendarEventType.LEDGER -> "账本记录"
}

private fun eventSupportingLabel(event: ProductivityCalendarEvent): String = when (event.type) {
    CalendarEventType.SUPERVISION -> {
        val details = event.supervisionDetails
        listOfNotNull(
            details?.kind?.let(::supervisionKindLabel),
            details?.endReason?.let(::supervisionEndReasonLabel)
        ).joinToString(" · ").ifEmpty { eventTypeLabel(event.type) }
    }
    CalendarEventType.LEDGER -> {
        val details = event.ledgerDetails
        listOfNotNull(details?.category?.displayName, eventTypeLabel(event.type))
            .joinToString(" · ")
    }
    else -> eventTypeLabel(event.type)
}

private fun eventTrailingText(event: ProductivityCalendarEvent): String = when (event.type) {
    CalendarEventType.LEDGER -> event.ledgerDetails?.let { details ->
        val sign = if (details.direction == LedgerDirection.EXPENSE) "-" else "+"
        "$sign¥${LedgerAmountCodec.formatFen(details.amountFen)}"
    } ?: formatTime(event.startEpochMillis)
    else -> formatTime(event.startEpochMillis)
}

private fun eventSecondaryTrailingText(
    event: ProductivityCalendarEvent,
    nowEpochMillis: Long
): String? = when (event.type) {
    CalendarEventType.LEDGER -> formatTime(event.startEpochMillis)
    CalendarEventType.FOCUS,
    CalendarEventType.SUPERVISION -> {
        val isRunning = event.endEpochMillis == null && event.startEpochMillis <= nowEpochMillis
        val endEpochMillis = event.endEpochMillis
            ?: nowEpochMillis.coerceAtLeast(event.startEpochMillis)
        val duration = formatFocusMinutes(
            (endEpochMillis - event.startEpochMillis).coerceAtLeast(0L)
        )
        if (isRunning) "进行中 · $duration" else duration
    }
    else -> null
}

@Composable
private fun eventTrailingColor(event: ProductivityCalendarEvent): Color = when {
    event.type != CalendarEventType.LEDGER -> BrandColors.TextSecondary
    event.ledgerDetails?.direction == LedgerDirection.INCOME -> BrandColors.Success
    else -> BrandColors.Danger
}

private fun supervisionKindLabel(kind: SupervisionSessionKind): String = when (kind) {
    SupervisionSessionKind.MANUAL_GLOBAL -> "即时监督"
    SupervisionSessionKind.SCHEDULED_GLOBAL -> "计划监督"
    SupervisionSessionKind.APP -> "App 独立监督"
    SupervisionSessionKind.MANUAL_FOCUS,
    SupervisionSessionKind.SCHEDULED_FOCUS -> "专注"
}

private fun supervisionEndReasonLabel(reason: SupervisionSessionEndReason): String = when (reason) {
    SupervisionSessionEndReason.COMPLETED -> "已完成"
    SupervisionSessionEndReason.CANCELLED -> "已停止"
    SupervisionSessionEndReason.REPLACED -> "已切换"
}

private fun formatFocusMinutes(millis: Long): String {
    val minutes = (millis / 60_000L).coerceAtLeast(0L)
    return if (minutes >= 60L) "${minutes / 60}h${minutes % 60}m" else "${minutes}m"
}

private fun formatDateTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M/d HH:mm", Locale.SIMPLIFIED_CHINESE))

private fun formatTime(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE))

internal fun visibleDatesFor(
    viewMode: CalendarViewMode,
    today: LocalDate
): List<LocalDate> = when (viewMode) {
    CalendarViewMode.DAY -> (0 until THREE_DAY_COUNT).map { offset ->
        today.minusDays(offset.toLong())
    }
    CalendarViewMode.WEEK -> ((RECENT_WEEK_DAY_COUNT - 1) downTo 0).map { offset ->
        today.minusDays(offset.toLong())
    }
    CalendarViewMode.MONTH -> ((RECENT_MONTH_DAY_COUNT - 1) downTo 0).map { offset ->
        today.minusDays(offset.toLong())
    }
}

internal fun statisticsRangeFor(dates: List<LocalDate>): CalendarDateRange {
    require(dates.isNotEmpty())
    return CalendarDateRange(
        startDate = requireNotNull(dates.minOrNull()),
        endDateInclusive = requireNotNull(dates.maxOrNull())
    )
}

internal fun alignedDateGridCells(dates: List<LocalDate>): List<LocalDate?> {
    require(dates.isNotEmpty())
    require(dates.zipWithNext().all { (first, second) -> second == first.plusDays(1L) })
    return buildList {
        repeat(dates.first().dayOfWeek.value - 1) { add(null) }
        addAll(dates)
        repeat((7 - size % 7) % 7) { add(null) }
    }
}

private fun formatDurationSupporting(millis: Long): String? =
    millis.takeIf { it > 0L }?.let(::formatFocusMinutes)

private fun ledgerMetricSupporting(incomeFen: Long, expenseFen: Long): String? =
    if (incomeFen <= 0L && expenseFen <= 0L) {
        null
    } else {
        "收 ¥${LedgerAmountCodec.formatFen(incomeFen)}\n支 ¥${LedgerAmountCodec.formatFen(expenseFen)}"
    }

private fun ledgerTotalsSupporting(incomeFen: Long, expenseFen: Long): String? =
    if (incomeFen <= 0L && expenseFen <= 0L) {
        null
    } else {
        "收 ¥${LedgerAmountCodec.formatFen(incomeFen)} · 支 ¥${LedgerAmountCodec.formatFen(expenseFen)}"
    }

private data class CalendarMetric(
    val label: String,
    val value: String,
    val supporting: String? = null,
    val icon: ImageVector? = null,
    val iconTint: Color = Color.Unspecified
)

private const val MAX_MONTH_CELL_EVENTS = 2
internal const val THREE_DAY_COUNT = 3
internal const val RECENT_WEEK_DAY_COUNT = 7
internal const val RECENT_MONTH_DAY_COUNT = 30

enum class CalendarViewMode { DAY, WEEK, MONTH }

@Composable
private fun WeekViewGrid(
    dates: List<LocalDate>,
    state: com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarUiState,
    today: LocalDate,
    viewModel: UnifiedCalendarViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        dates.forEach { date ->
            val isSelected = date == state.selectedDate
            val isToday = date == today
            val summary = state.days[date]
            Surface(
                onClick = { viewModel.selectDate(date) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) BrandColors.Primary.copy(alpha = 0.12f) else BrandColors.SurfaceCard
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE),
                        fontSize = 10.sp,
                        color = BrandColors.TextTertiary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = date.dayOfMonth.toString(),
                        fontSize = 13.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected || isToday) BrandColors.Primary else BrandColors.TextPrimary
                    )
                    val eventsCount = summary?.events.orEmpty().size
                    if (eventsCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).background(BrandColors.Primary, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreeDayLayout(
    state: com.example.controlfree.ui.todo.viewmodel.UnifiedCalendarUiState,
    dates: List<LocalDate>,
    expandedEventGroupKeys: List<String>,
    onToggleEventGroup: (String) -> Unit
) {
    val today = state.today
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        dates.forEach { date ->
            val daySummary = state.days[date]
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = when (date) {
                            today -> "今天 (${date.format(DateTimeFormatter.ofPattern("M/d"))})"
                            today.minusDays(1) -> "昨天 (${date.format(DateTimeFormatter.ofPattern("M/d"))})"
                            today.minusDays(2) -> "前天 (${date.format(DateTimeFormatter.ofPattern("M/d"))})"
                            else -> date.format(
                                DateTimeFormatter.ofPattern("M月d日 E", Locale.SIMPLIFIED_CHINESE)
                            )
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = BrandColors.Primary
                    )
                    Spacer(Modifier.height(6.dp))
                    val events = daySummary?.events.orEmpty()
                    if (events.isEmpty()) {
                        Text("当天没有排程记录", color = BrandColors.TextTertiary, fontSize = 12.sp)
                    } else {
                        CalendarDayEventRows(
                            date = date,
                            events = events,
                            nowEpochMillis = state.nowEpochMillis,
                            expandedEventGroupKeys = expandedEventGroupKeys,
                            onToggleEventGroup = onToggleEventGroup
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayEventRows(
    date: LocalDate,
    events: List<ProductivityCalendarEvent>,
    nowEpochMillis: Long,
    expandedEventGroupKeys: List<String>,
    onToggleEventGroup: (String) -> Unit
) {
    val sessionEvents = events
        .filter { it.type == CalendarEventType.FOCUS || it.type == CalendarEventType.SUPERVISION }
        .groupBy(ProductivityCalendarEvent::type)
    val renderedSessionTypes = mutableSetOf<CalendarEventType>()
    events.forEach { event ->
        if (event.type == CalendarEventType.FOCUS || event.type == CalendarEventType.SUPERVISION) {
            if (renderedSessionTypes.add(event.type)) {
                val groupEvents = sessionEvents[event.type].orEmpty()
                val groupKey = calendarEventGroupKey(date, event.type)
                CollapsibleCalendarEventRows(
                    type = event.type,
                    events = groupEvents,
                    nowEpochMillis = nowEpochMillis,
                    expanded = groupKey in expandedEventGroupKeys,
                    onToggle = { onToggleEventGroup(groupKey) }
                )
            }
        } else {
            CalendarEventRow(event, nowEpochMillis)
        }
    }
}

@Composable
private fun CollapsibleCalendarEventRows(
    type: CalendarEventType,
    events: List<ProductivityCalendarEvent>,
    nowEpochMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    if (events.size > COLLAPSED_EVENT_LIMIT) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${eventTypeLabel(type)} · ${events.size}",
                modifier = Modifier.weight(1f),
                color = BrandColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onToggle, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠${eventTypeLabel(type)}" else "展开${eventTypeLabel(type)}",
                    tint = BrandColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
    collapsedCalendarEvents(events, expanded).forEach { event ->
        CalendarEventRow(event, nowEpochMillis)
    }
}

@Composable
private fun CalendarEventsAggregationCard(
    date: LocalDate,
    events: List<ProductivityCalendarEvent>,
    nowEpochMillis: Long,
    expandedEventGroupKeys: List<String>,
    onToggleEventGroup: (String) -> Unit
) {
    val focusEvents = events.filter { it.type == CalendarEventType.FOCUS }
    val supervisionEvents = events.filter { it.type == CalendarEventType.SUPERVISION }
    val todoEvents = events.filter { it.type == CalendarEventType.COMPLETED_TODO || it.type == CalendarEventType.PLANNED_TODO }
    val habitEvents = events.filter { it.type == CalendarEventType.HABIT_COMPLETED || it.type == CalendarEventType.HABIT_DUE }
    val anniversaryEvents = events.filter { it.type == CalendarEventType.ANNIVERSARY }
    val ledgerEvents = events.filter { it.type == CalendarEventType.LEDGER }
    val ledgerIncomeFen = ledgerEvents.asSequence()
        .mapNotNull(ProductivityCalendarEvent::ledgerDetails)
        .filter { it.direction == LedgerDirection.INCOME }
        .sumOf { it.amountFen }
    val ledgerExpenseFen = ledgerEvents.asSequence()
        .mapNotNull(ProductivityCalendarEvent::ledgerDetails)
        .filter { it.direction == LedgerDirection.EXPENSE }
        .sumOf { it.amountFen }
    val focusGroupKey = calendarEventGroupKey(date, CalendarEventType.FOCUS)
    val supervisionGroupKey = calendarEventGroupKey(date, CalendarEventType.SUPERVISION)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column {
            AggregationSection(
                title = "专注记录",
                events = focusEvents,
                emptyText = "本日无专注时间",
                icon = Icons.Default.PlayCircle,
                iconTint = Color(0xFFFFA726),
                iconBg = Color(0xFFFFF3E0),
                nowEpochMillis = nowEpochMillis,
                expanded = focusGroupKey in expandedEventGroupKeys,
                onToggle = { onToggleEventGroup(focusGroupKey) }
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)

            AggregationSection(
                title = "监督记录",
                events = supervisionEvents,
                emptyText = "本日无监督记录",
                icon = Icons.Default.Visibility,
                iconTint = Color(0xFF5267A8),
                iconBg = Color(0xFFE8EAF4),
                nowEpochMillis = nowEpochMillis,
                expanded = supervisionGroupKey in expandedEventGroupKeys,
                onToggle = { onToggleEventGroup(supervisionGroupKey) }
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)

            AggregationSection(
                title = "待办日程与完成",
                events = todoEvents,
                emptyText = "本日无待办记录",
                icon = Icons.Default.CheckCircle,
                iconTint = Color(0xFF29B6F6),
                iconBg = Color(0xFFE1F5FE),
                nowEpochMillis = nowEpochMillis
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)

            AggregationSection(
                title = "习惯计划与打卡",
                events = habitEvents,
                emptyText = "本日无习惯记录",
                icon = Icons.Filled.DirectionsRun,
                iconTint = Color(0xFF66BB6A),
                iconBg = Color(0xFFE8F5E9),
                nowEpochMillis = nowEpochMillis
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)

            AggregationSection(
                title = "时刻",
                events = anniversaryEvents,
                emptyText = "本日无时刻记录",
                icon = Icons.Default.Favorite,
                iconTint = Color(0xFFEC407A),
                iconBg = Color(0xFFFCE4EC),
                nowEpochMillis = nowEpochMillis
            )
            HorizontalDivider(color = BrandColors.OutlineSoft)

            AggregationSection(
                title = "账本记录",
                events = ledgerEvents,
                emptyText = "本日无账本记录",
                icon = Icons.Default.AccountBalanceWallet,
                iconTint = Color(0xFF9A6540),
                iconBg = Color(0xFFF3ECE7),
                summary = ledgerTotalsSupporting(ledgerIncomeFen, ledgerExpenseFen),
                nowEpochMillis = nowEpochMillis
            )
        }
    }
}

@Composable
private fun AggregationSection(
    title: String,
    events: List<ProductivityCalendarEvent>,
    emptyText: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    summary: String? = null,
    nowEpochMillis: Long,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = BrandColors.TextPrimary
                )
                if (events.size > COLLAPSED_EVENT_LIMIT && onToggle != null) {
                    IconButton(onClick = onToggle, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "折叠$title" else "展开$title",
                            tint = BrandColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            summary?.takeIf { events.isNotEmpty() }?.let { value ->
                Text(value, fontSize = 11.sp, color = BrandColors.TextTertiary)
            }
            Spacer(Modifier.height(4.dp))
            if (events.isEmpty()) {
                Text(emptyText, fontSize = 12.sp, color = BrandColors.TextSecondary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    collapsedCalendarEvents(events, expanded || onToggle == null).forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "• ${event.title}",
                                    fontSize = 12.sp,
                                    color = BrandColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (event.type == CalendarEventType.SUPERVISION || event.type == CalendarEventType.LEDGER) {
                                    Text(
                                        text = eventSupportingLabel(event),
                                        fontSize = 10.sp,
                                        color = BrandColors.TextTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = eventTrailingText(event),
                                    fontSize = 11.sp,
                                    color = eventTrailingColor(event)
                                )
                                eventSecondaryTrailingText(event, nowEpochMillis)?.let { secondary ->
                                    Text(
                                        text = secondary,
                                        fontSize = 10.sp,
                                        color = BrandColors.TextTertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun collapsedCalendarEvents(
    events: List<ProductivityCalendarEvent>,
    expanded: Boolean
): List<ProductivityCalendarEvent> = if (expanded || events.size <= COLLAPSED_EVENT_LIMIT) {
    events
} else {
    events.take(COLLAPSED_EVENT_LIMIT)
}

internal fun calendarEventGroupKey(date: LocalDate, type: CalendarEventType): String =
    "$date:${type.name}"

private fun List<String>.toggled(key: String): List<String> =
    if (key in this) filterNot { it == key } else this + key

private const val COLLAPSED_EVENT_LIMIT = 2
