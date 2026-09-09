package com.example.controlfree.ui.todo.timeblock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import com.example.controlfree.ui.todo.components.StableTimePickerPage
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.theme.BrandColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

internal sealed interface TimeBlockSheet {
    data class Add(val initialDraft: TimeBlockEditorDraft) : TimeBlockSheet
    data class Detail(val stableKey: String) : TimeBlockSheet
    data class Day(val date: LocalDate) : TimeBlockSheet
    data object Filter : TimeBlockSheet
    data object Settings : TimeBlockSheet
    data object MonthPicker : TimeBlockSheet
    data object Inbox : TimeBlockSheet
    data object Expired : TimeBlockSheet
    data object Search : TimeBlockSheet
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeBlockSheetHost(
    sheet: TimeBlockSheet,
    entries: List<TimeBlockEntry>,
    filter: TimeBlockFilter,
    settings: TimeBlockSettings,
    anchorDate: LocalDate,
    today: LocalDate,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onOpenSheet: (TimeBlockSheet) -> Unit,
    onStartFocus: (TimeBlockEntry) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrandColors.Surface,
        contentColor = BrandColors.TextPrimary,
        scrimColor = TimeBlockColors.Scrim,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                Modifier.padding(top = 9.dp, bottom = 5.dp).width(38.dp).height(4.dp)
                    .background(TimeBlockColors.Line, RoundedCornerShape(3.dp))
            )
        }
    ) {
        when (sheet) {
            is TimeBlockSheet.Add -> AddTimeBlockSheet(sheet.initialDraft, viewModel, onDismiss)
            is TimeBlockSheet.Detail -> {
                entries.firstOrNull { it.stableKey == sheet.stableKey }?.let { entry ->
                    DetailTimeBlockSheet(entry, zoneId, viewModel, onDismiss, onStartFocus)
                } ?: MissingEntrySheet(onDismiss)
            }
            is TimeBlockSheet.Day -> DayTimeBlockSheet(
                date = sheet.date,
                entries = entriesOnDate(entries, sheet.date, zoneId),
                zoneId = zoneId,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onOpenSheet = onOpenSheet
            )
            TimeBlockSheet.Filter -> FilterTimeBlockSheet(entries, filter, viewModel, onDismiss)
            TimeBlockSheet.Settings -> SettingsTimeBlockSheet(settings, viewModel, onDismiss)
            TimeBlockSheet.MonthPicker -> MonthPickerSheet(anchorDate, viewModel, onDismiss)
            TimeBlockSheet.Inbox -> InboxTimeBlockSheet(entries, today, zoneId, viewModel, onDismiss)
            TimeBlockSheet.Expired -> ExpiredTimeBlockSheet(
                entries = entries,
                today = today,
                zoneId = zoneId,
                viewModel = viewModel,
                onDismiss = onDismiss,
                onOpenSheet = onOpenSheet
            )
            TimeBlockSheet.Search -> SearchTimeBlockSheet(entries, zoneId, viewModel, onDismiss, onOpenSheet)
        }
    }
}

@Composable
internal fun TimeBlockInboxOverlay(
    entries: List<TimeBlockEntry>,
    today: LocalDate,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    isDragging: Boolean,
    onDragStarted: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        if (!isDragging) {
            Box(
                Modifier.fillMaxSize().background(TimeBlockColors.Scrim)
                    .noRippleClickable(onDismiss)
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().animateContentSize()
                .background(
                    if (isDragging) TimeBlockColors.Ink else BrandColors.Surface,
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
                .noRippleClickable {}
        ) {
            if (isDragging) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Inbox, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        "拖入日期列 / 时间线空档完成时间化",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("松手排入", color = Color.White.copy(alpha = 0.68f), fontSize = 9.sp)
                }
            } else {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp, bottom = 5.dp)
                        .width(38.dp).height(4.dp)
                        .background(TimeBlockColors.Line, RoundedCornerShape(3.dp))
                )
                InboxTimeBlockSheet(
                    entries = entries,
                    today = today,
                    zoneId = zoneId,
                    viewModel = viewModel,
                    onDismiss = onDismiss,
                    onDragStarted = onDragStarted
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = TimeBlockColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        subtitle?.let {
            Spacer(Modifier.width(7.dp))
            Text(it, color = TimeBlockColors.InkTertiary, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, "关闭", tint = TimeBlockColors.InkTertiary)
        }
    }
    HorizontalDivider(color = TimeBlockColors.Line)
}

@Composable
private fun AddTimeBlockSheet(
    initialDraft: TimeBlockEditorDraft,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }
    var suggestion by remember(initialDraft) {
        mutableStateOf(viewModel.parseNaturalLanguage(initialDraft.title))
    }
    var customTimePickerTarget by remember { mutableStateOf<CustomTimePickerTarget?>(null) }
    Column(Modifier.fillMaxWidth().imePadding()) {
        SheetHeader("快速新建", "支持自然语言", onDismiss)
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item("type") {
                TwoOptionSelector(
                    first = TimeBlockEditorType.TASK.displayName,
                    second = TimeBlockEditorType.EVENT.displayName,
                    firstSelected = draft.editorType == TimeBlockEditorType.TASK,
                    onFirst = { draft = draft.copy(editorType = TimeBlockEditorType.TASK) },
                    onSecond = {
                        val safeEnd = (draft.startMinute + TIME_SNAP_MINUTES).coerceAtMost(MINUTES_PER_DAY)
                        val safeStart = if (safeEnd == MINUTES_PER_DAY) MINUTES_PER_DAY - TIME_SNAP_MINUTES else draft.startMinute
                        draft = draft.copy(
                            editorType = TimeBlockEditorType.EVENT,
                            allDay = false,
                            startMinute = safeStart,
                            endMinuteExclusive = safeEnd
                        )
                    }
                )
            }
            item("title") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = draft.title,
                        onValueChange = { raw ->
                            suggestion = viewModel.parseNaturalLanguage(raw)
                            draft = draft.copy(
                                title = raw,
                                date = suggestion.date,
                                startMinute = suggestion.startMinute ?: draft.startMinute,
                                endMinuteExclusive = suggestion.endMinuteExclusive ?: draft.endMinuteExclusive,
                                editorType = if (suggestion.startMinute != null) {
                                    TimeBlockEditorType.EVENT
                                } else {
                                    draft.editorType
                                },
                                allDay = if (suggestion.startMinute != null) false else draft.allDay
                            )
                        },
                        label = { Text("任务名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (suggestion.recognized) {
                        Row(
                            modifier = Modifier
                                .background(TimeBlockColors.Work.softSurface(), RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Assistant,
                                contentDescription = null,
                                tint = TimeBlockColors.Work,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                "已识别 · ${suggestion.summary} · 标题已净化",
                                color = TimeBlockColors.Work,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            item("date-label") { SheetLabel("日期") }
            item("dates") {
                val base = listOf(
                    LocalDate.now(),
                    LocalDate.now().plusDays(1),
                    LocalDate.now().plusDays(2)
                ).let { dates -> if (draft.date in dates) dates else listOf(draft.date) + dates.take(2) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    base.distinct().take(3).forEachIndexed { index, date ->
                        DateChoice(
                            date = date,
                            label = when (date) {
                                LocalDate.now() -> "今天"
                                LocalDate.now().plusDays(1) -> "明天"
                                LocalDate.now().plusDays(2) -> "后天"
                                else -> date.monthDayLabel()
                            },
                            selected = draft.date == date,
                            onClick = { draft = draft.copy(date = date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            item("projects") {
                OutlinedTextField(
                    value = draft.project,
                    onValueChange = { draft = draft.copy(project = it.take(100)) },
                    label = { Text("项目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item("priority") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = draft.highPriority,
                        onCheckedChange = { draft = draft.copy(highPriority = it) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("高优 · 急", fontWeight = FontWeight.SemiBold, color = BrandColors.TextPrimary)
                }
            }
            if (draft.editorType == TimeBlockEditorType.TASK) {
                item("all-day") {
                    SettingRow(
                        label = "全天待办",
                        checked = draft.allDay,
                        onCheckedChange = { draft = draft.copy(allDay = it) }
                    )
                }
            }
            if (draft.editorType == TimeBlockEditorType.EVENT || !draft.allDay) {
                item("time-label") { SheetLabel(if (draft.editorType == TimeBlockEditorType.EVENT) "时间段（30 分钟步进）" else "截止时间") }
                item("time") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        val maxMinute = if (draft.editorType == TimeBlockEditorType.EVENT) {
                            draft.endMinuteExclusive - TIME_SNAP_MINUTES
                        } else {
                            MINUTES_PER_DAY - 1
                        }
                        TimeStepper(
                            value = draft.startMinute,
                            onTextClick = { customTimePickerTarget = CustomTimePickerTarget.START },
                            onChange = { minute ->
                                val safe = minute.coerceIn(0, maxMinute)
                                draft = draft.copy(startMinute = safe)
                            }
                        )
                        if (draft.editorType == TimeBlockEditorType.EVENT) {
                            Text("至", color = TimeBlockColors.InkTertiary, modifier = Modifier.padding(horizontal = 10.dp))
                            TimeStepper(
                                value = draft.endMinuteExclusive,
                                onTextClick = { customTimePickerTarget = CustomTimePickerTarget.END },
                                onChange = { minute ->
                                    draft = draft.copy(
                                        endMinuteExclusive = minute.coerceIn(
                                            draft.startMinute + TIME_SNAP_MINUTES,
                                            MINUTES_PER_DAY
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
            item("description") {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { draft = draft.copy(description = it.take(1000)) },
                    label = { Text("备注") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item("save") {
                Button(
                    onClick = {
                        val normalized = draft.copy(
                            title = suggestion.title.takeIf { suggestion.recognized && it.isNotBlank() }
                                ?: draft.title
                        )
                        viewModel.saveDraft(normalized)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandColors.Primary,
                        contentColor = BrandColors.OnPrimary
                    ),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("保存到日历", fontWeight = FontWeight.Bold)
                }
            }
        }

        val activePickerTarget = customTimePickerTarget
        if (activePickerTarget != null) {
            val initialMinute = if (activePickerTarget == CustomTimePickerTarget.START) draft.startMinute else draft.endMinuteExclusive
            val MINUTES_PER_HOUR = 60
            AlertDialog(
                onDismissRequest = { customTimePickerTarget = null },
                title = { Text(if (activePickerTarget == CustomTimePickerTarget.START) "设置开始时间" else "设置结束时间") },
                text = {
                    StableTimePickerPage(
                        initialHour = initialMinute / MINUTES_PER_HOUR,
                        initialMinute = initialMinute % MINUTES_PER_HOUR,
                        onCancel = { customTimePickerTarget = null },
                        onSelected = { hour, minute ->
                            val newMin = hour * MINUTES_PER_HOUR + minute
                            if (activePickerTarget == CustomTimePickerTarget.START) {
                                if (draft.editorType == TimeBlockEditorType.EVENT) {
                                    val safe = newMin.coerceIn(0, draft.endMinuteExclusive - TIME_SNAP_MINUTES)
                                    draft = draft.copy(startMinute = safe)
                                } else {
                                    draft = draft.copy(startMinute = newMin.coerceIn(0, MINUTES_PER_DAY - 1))
                                }
                            } else {
                                draft = draft.copy(
                                    endMinuteExclusive = newMin.coerceIn(
                                        draft.startMinute + TIME_SNAP_MINUTES,
                                        MINUTES_PER_DAY
                                    )
                                )
                            }
                            customTimePickerTarget = null
                        }
                    )
                },
                confirmButton = {}
            )
        }
    }
}

@Composable
private fun FilterTimeBlockSheet(
    entries: List<TimeBlockEntry>,
    filter: TimeBlockFilter,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit
) {
    val projects = (primaryTimeBlockProjects + entries.map(TimeBlockEntry::project)).distinct()
    Column(Modifier.fillMaxWidth()) {
        SheetHeader("筛选", "按项目 / 优先级", onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            projects.forEach { project ->
                val visible = filter.visibleProjects.isEmpty() || project in filter.visibleProjects
                ProjectSettingRow(
                    project = project,
                    count = entries.count { it.project == project },
                    checked = visible,
                    onCheckedChange = { viewModel.setProjectVisible(project, it) }
                )
            }
            SettingRow("仅看高优", filter.highPriorityOnly, viewModel::setHighPriorityOnly, TimeBlockColors.High)
            SettingRow("隐藏已完成", filter.hideCompleted, viewModel::setHideCompleted, TimeBlockColors.InkTertiary)
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsTimeBlockSheet(
    settings: TimeBlockSettings,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SheetHeader("设置", onClose = onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SettingRow("周末列底色", settings.shadeWeekends, { viewModel.updateSettings(settings.copy(shadeWeekends = it)) })
            SettingRow("30 分钟虚网格", settings.showHalfHourGrid, { viewModel.updateSettings(settings.copy(showHalfHourGrid = it)) })
            SettingRow("完成粒子动画", settings.completionAnimation, { viewModel.updateSettings(settings.copy(completionAnimation = it)) })
            Text("清单 · 日程", color = TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 10.sp, modifier = Modifier.padding(vertical = 18.dp))
        }
    }
}

@Composable
private fun MonthPickerSheet(
    anchorDate: LocalDate,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit
) {
    var year by remember(anchorDate) { mutableIntStateOf(anchorDate.year) }
    Column(Modifier.fillMaxWidth()) {
        SheetHeader("跳转到…", onClose = onDismiss)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "上一年", tint = TimeBlockColors.InkSecondary) }
            Text(year.toString(), color = TimeBlockColors.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 28.dp))
            IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "下一年", tint = TimeBlockColors.InkSecondary) }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            maxItemsInEachRow = 4,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(12) { index ->
                val month = index + 1
                val selected = year == anchorDate.year && month == anchorDate.monthValue
                Text(
                    "${month}月",
                    color = if (selected) BrandColors.OnPrimary else BrandColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                        .background(if (selected) BrandColors.Primary else BrandColors.SurfaceMuted, RoundedCornerShape(10.dp))
                        .clickable {
                            viewModel.selectMonth(YearMonth.of(year, month))
                            onDismiss()
                        }
                        .padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun InboxTimeBlockSheet(
    entries: List<TimeBlockEntry>,
    today: LocalDate,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onDragStarted: (() -> Unit)? = null
) {
    val inbox = entries.filter { it.kind(zoneId) == TimeBlockEntryKind.INBOX_TASK && !it.isCompleted }
    Column(Modifier.fillMaxWidth()) {
        SheetHeader("未排程收集箱", "Inbox", onDismiss)
        Text(
            "按住条目拖入日期列 / 时间线空档完成时间化。",
            color = TimeBlockColors.InkSecondary,
            fontSize = 11.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
        if (inbox.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Inbox, null, tint = TimeBlockColors.InkTertiary, modifier = Modifier.size(30.dp))
                Text("收集箱已清空 ✨", color = TimeBlockColors.InkTertiary, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(inbox, key = TimeBlockEntry::stableKey) { entry ->
                    var dragSourceSize by remember(entry.stableKey) {
                        mutableStateOf(IntSize.Zero)
                    }
                    val hapticFeedback = LocalHapticFeedback.current
                    val viewConfiguration = LocalViewConfiguration.current
                    val dragViewConfiguration = remember(viewConfiguration) {
                        object : ViewConfiguration by viewConfiguration {
                            override val longPressTimeoutMillis: Long = 350L
                        }
                    }
                    val dragSourceModifier = onDragStarted?.let { dragStarted ->
                        Modifier.dragAndDropSource(
                            drawDragDecoration = {
                                drawCircle(
                                    color = projectColor(entry.project).copy(alpha = 0.18f),
                                    radius = 12.dp.toPx(),
                                    center = center
                                )
                                drawCircle(
                                    color = projectColor(entry.project),
                                    radius = 3.5.dp.toPx(),
                                    center = center
                                )
                            },
                            transferData = {
                                val width = dragSourceSize.width
                                val height = dragSourceSize.height
                                if (width <= 0 || height <= 0) {
                                    null
                                } else {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragStarted()
                                    timeBlockInboxTransferData(
                                        TimeBlockInboxDragPayload(
                                            todoId = entry.id,
                                            shadowWidthPx = width.toFloat(),
                                            shadowHeightPx = height.toFloat(),
                                            anchorXInShadowPx = width / 2f,
                                            anchorYInShadowPx = height / 2f
                                        )
                                    )
                                }
                            }
                        )
                    } ?: Modifier
                    CompositionLocalProvider(LocalViewConfiguration provides dragViewConfiguration) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
                                .onSizeChanged { dragSourceSize = it }
                                .then(dragSourceModifier)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                ProjectDot(entry.project)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = entry.title,
                                color = BrandColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (entry.isHighPriority) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "急",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    modifier = Modifier
                                        .background(TimeBlockColors.High, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ExpiredTimeBlockSheet(
    entries: List<TimeBlockEntry>,
    today: LocalDate,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onOpenSheet: (TimeBlockSheet) -> Unit
) {
    val expired = remember(entries, today, zoneId) {
        entries
            .filter { entry ->
                entry.source == TimeBlockSource.TODO &&
                    !entry.isCompleted &&
                    entry.primaryDate(zoneId)?.let { it < today } == true
            }
            .sortedWith(
                compareBy<TimeBlockEntry>(
                    { requireNotNull(it.primaryDate(zoneId)) },
                    { it.scheduledStartEpochMillis ?: it.dueAtEpochMillis ?: Long.MIN_VALUE },
                    { it.stableKey }
                )
            )
    }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader("过期待办", "原日期早于今天 · ${expired.size} 项", onDismiss)
        if (expired.isEmpty()) {
            Text(
                "没有过期待办",
                color = TimeBlockColors.InkTertiary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 34.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(expired, key = TimeBlockEntry::stableKey) { entry ->
                    val date = requireNotNull(entry.primaryDate(zoneId))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
                            .clickable { onOpenSheet(TimeBlockSheet.Detail(entry.stableKey)) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProjectDot(entry.project)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                color = BrandColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${date.year}年${date.monthDayLabel()} · ${entry.timeLabel(zoneId)}",
                                color = TimeBlockColors.InkTertiary,
                                fontFamily = TimeBlockMono,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "项目 · ${entry.project}",
                                color = TimeBlockColors.InkSecondary,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.moveExpiredToToday()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandColors.Primary,
                    contentColor = BrandColors.OnPrimary
                ),
                shape = RoundedCornerShape(11.dp)
            ) {
                Text("全部顺延到今天", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DayTimeBlockSheet(
    date: LocalDate,
    entries: List<TimeBlockEntry>,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onOpenSheet: (TimeBlockSheet) -> Unit
) {
    val progress = dayProgress(entries)
    Column(Modifier.fillMaxWidth()) {
        SheetHeader(date.monthDayLabel(), "${date.weekdayLabel()} · ${progress.completed}/${progress.total} 已完成", onDismiss)
        LazyColumn(
            Modifier.heightIn(max = 520.dp),
            contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = TimeBlockEntry::stableKey) { entry ->
                Row(
                    Modifier.fillMaxWidth().background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
                        .clickable { onOpenSheet(TimeBlockSheet.Detail(entry.stableKey)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProjectDot(entry.project)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, color = BrandColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.timeLabel(zoneId), color = TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 9.sp)
                    }
                    TimeBlockCompletionButton(entry.isCompleted, projectColor(entry.project), { viewModel.toggleCompletion(entry) }, Modifier.size(21.dp))
                }
            }
            item("add") {
                OutlinedButton(
                    onClick = {
                        onOpenSheet(TimeBlockSheet.Add(TimeBlockEditorDraft(date = date)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, null)
                    Text("新建此日任务")
                }
            }
        }
    }
}

@Composable
private fun DetailTimeBlockSheet(
    entry: TimeBlockEntry,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onStartFocus: (TimeBlockEntry) -> Unit
) {
    var title by remember(entry.stableKey, entry.updatedAtEpochMillis) { mutableStateOf(entry.title) }
    var project by remember(entry.stableKey, entry.updatedAtEpochMillis) { mutableStateOf(entry.project) }
    var highPriority by remember(entry.stableKey, entry.updatedAtEpochMillis) { mutableStateOf(entry.isHighPriority) }
    var description by remember(entry.stableKey, entry.updatedAtEpochMillis) { mutableStateOf(entry.description.orEmpty()) }

    Column(Modifier.fillMaxWidth().imePadding()) {
        SheetHeader("任务详情", entry.timeLabel(zoneId), onDismiss)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(160) },
                    label = { Text("任务名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = project,
                    onValueChange = { project = it.take(100) },
                    label = { Text("项目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = highPriority,
                        onCheckedChange = { highPriority = it }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("高优 · 急", fontWeight = FontWeight.SemiBold, color = BrandColors.TextPrimary)
                }
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(1000) },
                    label = { Text("备注") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Button(
                    onClick = {
                        viewModel.updateDetails(entry, title, project, highPriority, description)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandColors.Primary, contentColor = BrandColors.OnPrimary),
                    shape = RoundedCornerShape(11.dp)
                ) {
                    Text("保存修改", fontWeight = FontWeight.Bold)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isCompleted) {
                        OutlinedButton(
                            onClick = { onStartFocus(entry); onDismiss() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Text("专注")
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.toggleCompletion(entry); onDismiss() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (entry.isCompleted) "取消完成" else "完成 √")
                    }
                    OutlinedButton(
                        onClick = { viewModel.delete(entry); onDismiss() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TimeBlockColors.High),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(15.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTimeBlockSheet(
    entries: List<TimeBlockEntry>,
    zoneId: ZoneId,
    viewModel: TimeBlockViewModel,
    onDismiss: () -> Unit,
    onOpenSheet: (TimeBlockSheet) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(entries, query) {
        val normalized = query.trim()
        if (normalized.isEmpty()) emptyList() else entries
            .filter { it.title.contains(normalized, ignoreCase = true) || it.description.orEmpty().contains(normalized, ignoreCase = true) }
            .filter { it.kind(zoneId) != TimeBlockEntryKind.INBOX_TASK }
            .sortedBy { it.primaryDate(zoneId) }
            .take(30)
    }
    Column(Modifier.fillMaxWidth().imePadding()) {
        SheetHeader("搜索", "跨全部日期", onDismiss)
        TextField(
            value = query,
            onValueChange = { query = it.take(160) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TimeBlockColors.InkTertiary) },
            placeholder = { Text("搜索任务 / 日程…") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TimeBlockColors.AppBackgroundTop,
                unfocusedContainerColor = TimeBlockColors.AppBackgroundTop,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = TimeBlockColors.Ink,
                unfocusedTextColor = TimeBlockColors.Ink
            ),
            shape = RoundedCornerShape(11.dp)
        )
        if (query.isBlank()) {
            Text("输入关键词，跨全部日期检索", color = TimeBlockColors.InkTertiary, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(28.dp))
        } else if (results.isEmpty()) {
            Text("没有匹配「$query」的条目", color = TimeBlockColors.InkTertiary, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(28.dp))
        } else {
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(results, key = TimeBlockEntry::stableKey) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            entry.primaryDate(zoneId)?.let { date ->
                                viewModel.selectDate(date)
                                onOpenSheet(TimeBlockSheet.Day(date))
                            }
                        }.padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(entry.primaryDate(zoneId)?.monthDayLabel().orEmpty(), color = TimeBlockColors.InkSecondary, fontFamily = TimeBlockMono, fontSize = 9.sp, modifier = Modifier.width(54.dp))
                        ProjectDot(entry.project)
                        Spacer(Modifier.width(8.dp))
                        Text(entry.title, color = TimeBlockColors.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.timeLabel(zoneId), color = TimeBlockColors.InkTertiary, fontFamily = TimeBlockMono, fontSize = 8.sp)
                    }
                    HorizontalDivider(color = TimeBlockColors.Line)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun MissingEntrySheet(onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetHeader("条目不存在", onClose = onDismiss)
        Text("该条目可能已被删除", color = TimeBlockColors.InkTertiary, modifier = Modifier.padding(30.dp))
    }
}

@Composable
private fun ProjectSelector(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        primaryTimeBlockProjects.forEach { project ->
            Row(
                Modifier.weight(1f)
                    .background(BrandColors.Surface, RoundedCornerShape(9.dp))
                    .border(if (selected == project) 1.5.dp else 1.dp, if (selected == project) projectColor(project) else TimeBlockColors.Line, RoundedCornerShape(9.dp))
                    .clickable { onSelect(project) }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProjectDot(project)
                Spacer(Modifier.width(5.dp))
                Text(project, color = BrandColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TwoOptionSelector(
    first: String,
    second: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
            .height(38.dp)
            .padding(3.dp)
    ) {
        val segmentWidth = maxWidth / 2
        val indicatorOffset by animateDpAsState(
            targetValue = if (firstSelected) 0.dp else segmentWidth,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "选项分段器弹性滑块"
        )
        Box(
            Modifier.offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segmentWidth).fillMaxHeight()
                .background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
        )
        Row(Modifier.fillMaxSize()) {
            listOf(first to onFirst, second to onSecond).forEachIndexed { index, (label, action) ->
                val selected = if (index == 0) firstSelected else !firstSelected
                Text(
                    label,
                    color = if (selected) BrandColors.Primary else BrandColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .noRippleClickable { action() }
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
    }
}

@Composable
private fun DateChoice(
    date: LocalDate,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.background(BrandColors.Surface, RoundedCornerShape(10.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) BrandColors.Primary else BrandColors.OutlineSoft, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = BrandColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("${date.monthValue}/${date.dayOfMonth} ${date.weekdayLabel()}", color = TimeBlockColors.InkTertiary, fontSize = 8.sp)
    }
}

@Composable
private fun TimeStepper(value: Int, onTextClick: () -> Unit, onChange: (Int) -> Unit) {
    Row(
        Modifier.background(BrandColors.SurfaceMuted, RoundedCornerShape(9.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("−", color = BrandColors.TextPrimary, fontSize = 18.sp, modifier = Modifier.clickable { onChange(value - TIME_SNAP_MINUTES) }.padding(horizontal = 12.dp, vertical = 8.dp))
        Text(
            text = formatMinute(value),
            color = BrandColors.TextPrimary,
            fontFamily = TimeBlockMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onTextClick).padding(horizontal = 12.dp, vertical = 8.dp)
        )
        Text("＋", color = BrandColors.TextPrimary, fontSize = 18.sp, modifier = Modifier.clickable { onChange(value + TIME_SNAP_MINUTES) }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun ProjectSettingRow(
    project: String,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        ProjectDot(project, Modifier.size(10.dp))
        Spacer(Modifier.width(9.dp))
        Text(project, color = TimeBlockColors.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("$count 项", color = TimeBlockColors.InkTertiary, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        PrototypeSwitch(checked, onCheckedChange)
    }
    HorizontalDivider(color = TimeBlockColors.Line)
}

@Composable
private fun SettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    dotColor: Color? = null
) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        dotColor?.let {
            Box(Modifier.size(10.dp).background(it, CircleShape))
            Spacer(Modifier.width(9.dp))
        }
        Text(label, color = TimeBlockColors.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        PrototypeSwitch(checked, onCheckedChange)
    }
    HorizontalDivider(color = TimeBlockColors.Line)
}

@Composable
private fun PrototypeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = TimeBlockColors.Life,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = TimeBlockColors.Line,
            uncheckedBorderColor = TimeBlockColors.Line
        )
    )
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = TimeBlockColors.InkTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
}

private enum class CustomTimePickerTarget {
    START,
    END
}
