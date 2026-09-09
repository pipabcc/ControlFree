package com.example.controlfree.ui.todo.timeblock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.CommitmentSourceType
import com.example.controlfree.ui.todo.toTodoItemUi
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.todo.search.SuperSearchSource
import com.example.controlfree.ui.todo.todo.TodoEditorDialog
import com.example.controlfree.ui.todo.todo.TodoEditorDraft
import com.example.controlfree.ui.todo.todo.TodoItemUi
import com.example.controlfree.ui.todo.todo.TodoSubtaskUi
import com.example.controlfree.ui.todo.todo.toEditorDraft
import com.example.controlfree.ui.todo.viewmodel.TodoListViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun TimeBlockScreen(
    viewModel: TimeBlockViewModel,
    todoListViewModel: TodoListViewModel,
    modifier: Modifier = Modifier,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {}
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val anchorDate by viewModel.anchorDate.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nowEpochMillis by viewModel.nowEpochMillis.collectAsStateWithLifecycle()
    val todoEntities by todoListViewModel.todos.collectAsStateWithLifecycle()
    val commitmentPolicies by todoListViewModel.commitmentPolicies.collectAsStateWithLifecycle()
    val supervisableApps by todoListViewModel.supervisableApps.collectAsStateWithLifecycle()
    val isLoadingSupervisableApps by todoListViewModel.isLoadingSupervisableApps.collectAsStateWithLifecycle()
    val supervisableAppsLoadFailed by todoListViewModel.supervisableAppsLoadFailed.collectAsStateWithLifecycle()
    val isTodoDataLoaded by todoListViewModel.isTodoDataLoaded.collectAsStateWithLifecycle()
    val zoneId = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val filteredEntries = remember(entries, filter) { entries.filter(filter::accepts) }
    val commitmentsByTodo = remember(commitmentPolicies) {
        commitmentPolicies
            .filter { it.policy.sourceType == CommitmentSourceType.TODO.storedValue }
            .associateBy { it.policy.sourceId }
    }
    val todoItems = remember(todoEntities, commitmentsByTodo) {
        todoEntities.map { todo -> todo.toTodoItemUi(commitmentsByTodo[todo.id]) }
    }
    val subtasks by todoListViewModel.subtasks.collectAsStateWithLifecycle()
    val subtasksByTodo = remember(subtasks) { subtasks.groupBy { it.todoId } }
    val dates = remember(viewMode, anchorDate) { visibleDates(viewMode, anchorDate) }
    val snackbarHostState = remember { SnackbarHostState() }
    var activeSheet by remember { mutableStateOf<TimeBlockSheet?>(null) }
    var todoEditorSession by remember { mutableStateOf<TimeBlockTodoEditorSession?>(null) }
    var celebrationNonce by remember { mutableIntStateOf(0) }
    val focusTimerState by FocusTimerManager.timerState.collectAsStateWithLifecycle()
    val focusTimer = focusTimerState
    var isInboxDragging by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val inboxDragObserver = rememberTimeBlockInboxDragObserver {
        isInboxDragging = false
    }

    fun openTodoEditor(draft: TodoEditorDraft, entry: TimeBlockEntry? = null) {
        activeSheet = null
        todoEditorSession = TimeBlockTodoEditorSession(
            initialDraft = draft,
            initialEntry = entry
        )
    }

    fun openEntry(entry: TimeBlockEntry) {
        if (entry.source == TimeBlockSource.EVENT) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("独立日程已统一迁移为待办，请刷新后重试")
            }
            return
        }
        val todoId = entry.baseTodoId()
        val todo = todoItems.firstOrNull { it.id == todoId }
        if (todo == null) {
            coroutineScope.launch { snackbarHostState.showSnackbar("待办信息尚未加载，请稍后重试") }
            return
        }
        openTodoEditor(todo.toEditorDraft(subtasksByTodo[todo.id].orEmpty().map { subtask ->
            TodoSubtaskUi(
                id = subtask.id,
                todoId = subtask.todoId,
                parentSubtaskId = subtask.parentSubtaskId,
                title = subtask.title,
                isCompleted = subtask.isCompleted,
                sortOrder = subtask.sortOrder
            )
        }), entry)
    }

    fun openSheet(sheet: TimeBlockSheet) {
        when (sheet) {
            is TimeBlockSheet.Add -> openTodoEditor(sheet.initialDraft.toTodoEditorDraft(zoneId))
            is TimeBlockSheet.Detail -> {
                val entry = entries.firstOrNull { it.stableKey == sheet.stableKey }
                if (entry == null) {
                    activeSheet = sheet
                } else {
                    openEntry(entry)
                }
            }
            else -> activeSheet = sheet
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest(snackbarHostState::showSnackbar)
    }

    LaunchedEffect(searchNavigationRequest?.revision, entries, todoItems, isTodoDataLoaded) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        if (request.source != SuperSearchSource.CALENDAR) return@LaunchedEffect
        val entry = entries.firstOrNull { it.id == request.entityId }
            ?: return@LaunchedEffect
        val targetDate = request.targetDate ?: entry.primaryDate(zoneId)
        targetDate?.let(viewModel::selectDate)
        viewModel.selectViewMode(TimeBlockViewMode.TIMELINE)
        if (entry.source == TimeBlockSource.TODO &&
            todoItems.none { it.id == entry.baseTodoId() }
        ) {
            if (isTodoDataLoaded) {
                snackbarHostState.showSnackbar("该待办已不存在")
                onSearchNavigationConsumed(request)
            }
            return@LaunchedEffect
        }
        openEntry(entry)
        onSearchNavigationConsumed(request)
    }

    // 计时循环已移入 MainScreen 全局处理，本地不再需要 LaunchedEffect 维持局部状态更新
    
    fun startFocus(entry: TimeBlockEntry) {
        val minutes = entry.durationMinutes().coerceIn(1, 180)
        FocusTimerManager.startTimer(
            stableKey = entry.focusTimerStableKey(),
            title = entry.title,
            totalSeconds = minutes * 60
        )
        activeSheet = null
        todoEditorSession = null
    }

    fun toggleEntry(entry: TimeBlockEntry) {
        if (!entry.isCompleted && settings.completionAnimation) celebrationNonce++
        viewModel.toggleCompletion(entry)
    }

    fun scheduleInbox(todoId: String, date: LocalDate, startMinute: Int?) {
        val entry = entries.firstOrNull {
            it.source == TimeBlockSource.TODO && it.id == todoId &&
                it.kind(zoneId) == TimeBlockEntryKind.INBOX_TASK
        } ?: return
        if (startMinute == null) {
            viewModel.scheduleInboxOnDate(todoId, date)
        } else {
            viewModel.moveToTime(entry, date, startMinute, TIME_SNAP_MINUTES)
        }
        activeSheet = null
    }

    Box(
        modifier = modifier.fillMaxSize().background(BrandColors.PageBackground)
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::isTimeBlockInboxDrag,
                target = inboxDragObserver
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            TimeBlockToolbar(
                anchorDate = anchorDate,
                filterActive = filter != TimeBlockFilter(),
                onToday = viewModel::selectToday,
                onPrevious = { viewModel.navigate(-1) },
                onNext = { viewModel.navigate(1) },
                onMonth = { activeSheet = TimeBlockSheet.MonthPicker },
                onFilter = { activeSheet = TimeBlockSheet.Filter },
                onSettings = { activeSheet = TimeBlockSheet.Settings }
            )
            TimeBlockSegmentedControl(viewMode, viewModel::selectViewMode)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (viewMode) {
                    TimeBlockViewMode.THREE_DAYS -> ThreeDayTimeBlockView(
                        entries = filteredEntries,
                        dates = dates,
                        today = today,
                        nowEpochMillis = nowEpochMillis,
                        settings = settings,
                        zoneId = zoneId,
                        onSelectDate = { date ->
                            viewModel.selectDate(date)
                            activeSheet = TimeBlockSheet.Day(date)
                        },
                        onEntryClick = ::openEntry,
                        onToggle = ::toggleEntry,
                        onMoveToDate = viewModel::moveToDate,
                        onMoveToTime = viewModel::moveToTime,
                        onMoveAllDayToTime = viewModel::moveAllDayToTime,
                        onResize = viewModel::resize,
                        onInboxDrop = ::scheduleInbox,
                        onCreateAt = { date, minute ->
                            val startMinute = minute.coerceAtMost(MINUTES_PER_DAY - 60)
                            openTodoEditor(
                                todoDraftForSchedule(
                                    date = date,
                                    startMinute = startMinute,
                                    endMinuteExclusive = startMinute + 60,
                                    zoneId = zoneId
                                )
                            )
                        }
                    )
                    TimeBlockViewMode.WEEK -> WeekTimeBlockView(
                        entries = filteredEntries,
                        dates = dates,
                        today = today,
                        zoneId = zoneId,
                        onDayClick = { activeSheet = TimeBlockSheet.Day(it) },
                        onEntryClick = ::openEntry,
                        onToggle = ::toggleEntry,
                        onInboxDrop = ::scheduleInbox
                    )
                    TimeBlockViewMode.MONTH -> MonthTimeBlockView(
                        entries = filteredEntries,
                        dates = dates,
                        anchorMonth = YearMonth.from(anchorDate),
                        today = today,
                        nowEpochMillis = nowEpochMillis,
                        zoneId = zoneId,
                        onDayClick = { activeSheet = TimeBlockSheet.Day(it) },
                        onInboxDrop = ::scheduleInbox
                    )
                    TimeBlockViewMode.TIMELINE -> TimelineTimeBlockView(
                        entries = filteredEntries,
                        date = anchorDate,
                        today = today,
                        nowEpochMillis = nowEpochMillis,
                        zoneId = zoneId,
                        onEntryClick = ::openEntry,
                        onToggle = ::toggleEntry,
                        onShiftOneDay = viewModel::shiftOneDay,
                        onStartFocus = ::startFocus,
                        onFillGaps = viewModel::fillTodayGaps,
                        onMoveExpired = viewModel::moveExpiredToToday,
                        onOpenExpired = { activeSheet = TimeBlockSheet.Expired },
                        onMoveToTime = { entry, date, startMinute ->
                            viewModel.moveToTime(entry, date, startMinute, entry.durationMinutes())
                        },
                        onInboxDrop = ::scheduleInbox,
                        onCreateAt = { date, minute ->
                            val startMinute = minute.coerceAtMost(MINUTES_PER_DAY - TIME_SNAP_MINUTES)
                            openTodoEditor(
                                todoDraftForSchedule(
                                    date = date,
                                    startMinute = startMinute,
                                    endMinuteExclusive = startMinute + TIME_SNAP_MINUTES,
                                    zoneId = zoneId
                                )
                            )
                        },
                        onDelete = { entry -> viewModel.deleteWithUndo(entry, snackbarHostState) }
                    )
                }
            }
        }

        val inboxCount = entries.count {
            it.kind(zoneId) == TimeBlockEntryKind.INBOX_TASK && !it.isCompleted
        }
        if (focusTimer == null && inboxCount > 0) {
            InboxPill(
                count = inboxCount,
                onClick = { activeSheet = TimeBlockSheet.Inbox },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
            )
        }
        // 计时药丸已移至 MainScreen 全局层级显示，此处无需再绘制局部药丸
        SnackbarHost(
            snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (focusTimer != null) 72.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(4f))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = {
                        openTodoEditor(todoDraftForDeadlineDate(anchorDate, zoneId))
                    },
                    shape = CircleShape,
                    containerColor = BrandColors.Primary.copy(alpha = 0.12f),
                    contentColor = BrandColors.Primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "快捷新建",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        if (celebrationNonce > 0) {
            CompletionBurst(celebrationNonce, Modifier.fillMaxSize())
        }
        if (activeSheet == TimeBlockSheet.Inbox) {
            TimeBlockInboxOverlay(
                entries = entries,
                today = today,
                zoneId = zoneId,
                viewModel = viewModel,
                isDragging = isInboxDragging,
                onDragStarted = { isInboxDragging = true },
                onDismiss = {
                    isInboxDragging = false
                    activeSheet = null
                }
            )
        }
    }

    activeSheet?.takeUnless { it == TimeBlockSheet.Inbox }?.let { sheet ->
        TimeBlockSheetHost(
            sheet = sheet,
            entries = entries,
            filter = filter,
            settings = settings,
            anchorDate = anchorDate,
            today = today,
            zoneId = zoneId,
            viewModel = viewModel,
            onDismiss = { activeSheet = null },
            onOpenSheet = ::openSheet,
            onStartFocus = ::startFocus
        )
    }

    todoEditorSession?.let { session ->
        val entry = session.initialEntry
        val todoId = entry?.baseTodoId() ?: session.initialDraft.id
        val currentTodo = todoId?.let { id -> todoItems.firstOrNull { it.id == id } }
        TodoEditorDialog(
            initialDraft = session.initialDraft,
            knownProjects = todoItems.map(TodoItemUi::project).distinct(),
            onDismiss = { todoEditorSession = null },
            onSave = { draft ->
                todoListViewModel.saveTodo(draft)
                todoEditorSession = null
            },
            onDeleteRequest = {
                if (todoId != null) {
                    todoListViewModel.deleteTodo(todoId)
                    todoEditorSession = null
                }
            },
            supervisableApps = supervisableApps,
            isLoadingSupervisableApps = isLoadingSupervisableApps,
            supervisableAppsLoadFailed = supervisableAppsLoadFailed,
            onRetrySupervisableApps = todoListViewModel::retrySupervisableApps,
            bottomActions = {
                if (entry != null && todoId != null) {
                    val isCompleted = currentTodo?.isCompleted ?: entry.isCompleted
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!isCompleted) {
                            OutlinedButton(
                                onClick = { startFocus(entry) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                Text("专注")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                todoListViewModel.toggleCompletion(todoId, !isCompleted)
                                todoEditorSession = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(if (isCompleted) "取消完成" else "完成 √")
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun TimeBlockToolbar(
    anchorDate: LocalDate,
    filterActive: Boolean,
    onToday: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMonth: () -> Unit,
    onFilter: () -> Unit,
    onSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(BrandColors.PageBackground)
            .padding(horizontal = 7.dp)
    ) {
        // 1. 左侧对齐的一组按钮 (<- 今天 ->)
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallToolbarButton(Icons.Default.ArrowBackIosNew, "上一周期", onPrevious)
            Text(
                text = "今天",
                color = BrandColors.TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .noRippleClickable(onToday)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
            SmallToolbarButton(Icons.AutoMirrored.Filled.ArrowForwardIos, "下一周期", onNext)
        }

        // 2. 年月正中间对齐
        Text(
            text = anchorDate.format(DateTimeFormatter.ofPattern("yyyy年M月")),
            color = BrandColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .noRippleClickable(onMonth)
        )

        // 3. 右侧对齐的一组按钮 (筛选, 设置)
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                SmallToolbarButton(Icons.Default.FilterAlt, "筛选日程", onFilter)
                if (filterActive) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = 5.dp)
                            .size(7.dp).background(TimeBlockColors.High, CircleShape)
                    )
                }
            }
            SmallToolbarButton(Icons.Default.Settings, "日程设置", onSettings)
        }
    }
}

@Composable
private fun SmallToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = TimeBlockColors.InkSecondary, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun TimeBlockSegmentedControl(
    selected: TimeBlockViewMode,
    onSelected: (TimeBlockViewMode) -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(BrandColors.PageBackground)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
            .height(37.dp)
            .padding(3.dp)
    ) {
        val modes = TimeBlockViewMode.entries
        val segmentWidth = maxWidth / modes.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * modes.indexOf(selected),
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "视图分段器弹性滑块"
        )
        Box(
            Modifier.offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                .width(segmentWidth).fillMaxHeight()
                .background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                Text(
                    mode.displayName,
                    color = if (mode == selected) BrandColors.Primary else BrandColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .noRippleClickable { onSelected(mode) }
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
    }
}

@Composable
private fun InboxPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .noRippleClickable(onClick).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(Icons.Default.Inbox, null, tint = BrandColors.Primary, modifier = Modifier.size(15.dp))
        Text("未排程", color = BrandColors.Primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(TimeBlockColors.High, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun FocusTimerPill(
    state: FocusTimerState,
    onPauseToggle: () -> Unit,
    onComplete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = state.remainingSeconds.toFloat() / state.totalSeconds.coerceAtLeast(1)
    Row(
        modifier.fillMaxWidth().background(TimeBlockColors.Ink, RoundedCornerShape(17.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(Color.White.copy(alpha = 0.18f), -90f, 360f, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawArc(TimeBlockColors.Focus, -90f, 360f * progress, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("专注 · ${state.title}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatFocusSeconds(state.remainingSeconds), color = Color.White.copy(alpha = 0.75f), fontFamily = TimeBlockMono, fontSize = 9.sp)
        }
        IconButton(onClick = onPauseToggle, modifier = Modifier.size(34.dp)) {
            Icon(if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (state.paused) "继续" else "暂停", tint = Color.White, modifier = Modifier.size(17.dp))
        }
        IconButton(onClick = onComplete, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Check, "完成专注", tint = TimeBlockColors.Focus, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Default.Close, "结束专注", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(15.dp))
        }
    }
}

private enum class ParticleShape {
    CONFETTI_RECT,
    STAR,
    CIRCLE
}

private data class CelebrationParticle(
    val startX: Float,
    val startY: Float,
    val angle: Double,
    val speed: Float,
    val gravity: Float,
    val drag: Float,
    val swaySpeed: Float,
    val swayAmount: Float,
    val rotationSpeed: Float,
    val size: Float,
    val color: Color,
    val shape: ParticleShape,
    val delay: Float
)

@Composable
private fun CompletionBurst(nonce: Int, modifier: Modifier = Modifier) {
    val progress = remember(nonce) { Animatable(0f) }
    LaunchedEffect(nonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
    }
    
    val particles = remember(nonce) {
        val list = mutableListOf<CelebrationParticle>()
        val colors = listOf(
            Color(0xFFFF2F68),
            Color(0xFF0A84FF),
            Color(0xFFFFD60A),
            Color(0xFF30D158),
            Color(0xFFBF5AF2),
            Color(0xFFFF9F0A),
            Color(0xFF0FA06B),
            Color(0xFFE5484D)
        )
        val random = kotlin.random.Random(nonce)
        
        // 35 particles from bottom-left
        repeat(35) { index ->
            val angle = (-45.0 + random.nextDouble() * 40.0 - 20.0) * Math.PI / 180.0
            val speed = 300f + random.nextFloat() * 250f
            val size = 6f + random.nextFloat() * 7f
            val shape = when (random.nextInt(3)) {
                0 -> ParticleShape.CONFETTI_RECT
                1 -> ParticleShape.STAR
                else -> ParticleShape.CIRCLE
            }
            list.add(
                CelebrationParticle(
                    startX = 0.05f,
                    startY = 0.85f,
                    angle = angle,
                    speed = speed,
                    gravity = 280f + random.nextFloat() * 120f,
                    drag = 1.8f + random.nextFloat() * 1.2f,
                    swaySpeed = 5f + random.nextFloat() * 7f,
                    swayAmount = 12f + random.nextFloat() * 12f,
                    rotationSpeed = 180f + random.nextFloat() * 360f,
                    size = size,
                    color = colors[random.nextInt(colors.size)],
                    shape = shape,
                    delay = random.nextFloat() * 0.12f
                )
            )
        }
        
        // 35 particles from bottom-right
        repeat(35) { index ->
            val angle = (-135.0 + random.nextDouble() * 40.0 - 20.0) * Math.PI / 180.0
            val speed = 300f + random.nextFloat() * 250f
            val size = 6f + random.nextFloat() * 7f
            val shape = when (random.nextInt(3)) {
                0 -> ParticleShape.CONFETTI_RECT
                1 -> ParticleShape.STAR
                else -> ParticleShape.CIRCLE
            }
            list.add(
                CelebrationParticle(
                    startX = 0.95f,
                    startY = 0.85f,
                    angle = angle,
                    speed = speed,
                    gravity = 280f + random.nextFloat() * 120f,
                    drag = 1.8f + random.nextFloat() * 1.2f,
                    swaySpeed = 5f + random.nextFloat() * 7f,
                    swayAmount = 12f + random.nextFloat() * 12f,
                    rotationSpeed = 180f + random.nextFloat() * 360f,
                    size = size,
                    color = colors[random.nextInt(colors.size)],
                    shape = shape,
                    delay = random.nextFloat() * 0.12f
                )
            )
        }
        list
    }
    
    if (progress.value < 1f) {
        Canvas(modifier) {
            val t = progress.value
            particles.forEach { particle ->
                val activeTime = (t - particle.delay) / (1f - particle.delay)
                if (activeTime in 0f..1f) {
                    val durationSeconds = 1.3f
                    val timeSec = activeTime * durationSeconds
                    
                    val dragFactor = (1f - kotlin.math.exp(-particle.drag * timeSec)) / particle.drag
                    val dist = particle.speed * dragFactor
                    
                    val initX = size.width * particle.startX
                    val initY = size.height * particle.startY
                    
                    val sway = kotlin.math.sin(timeSec * particle.swaySpeed).toFloat() * particle.swayAmount.dp.toPx()
                    
                    val posX = initX + kotlin.math.cos(particle.angle).toFloat() * dist.dp.toPx() + sway
                    val posY = initY + kotlin.math.sin(particle.angle).toFloat() * dist.dp.toPx() + (0.5f * particle.gravity * timeSec * timeSec).dp.toPx()
                    
                    val alpha = (1f - activeTime).coerceIn(0f, 1f)
                    
                    val scale = if (activeTime > 0.7f) {
                        ((1f - activeTime) / 0.3f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    
                    val particleSizePx = particle.size.dp.toPx() * scale
                    val rotation = particle.rotationSpeed * timeSec
                    
                    when (particle.shape) {
                        ParticleShape.CONFETTI_RECT -> {
                            val rectWidth = particleSizePx * 1.6f
                            val rectHeight = particleSizePx * 0.8f
                            withTransform({
                                translate(posX, posY)
                                rotate(rotation)
                            }) {
                                drawRect(
                                    color = particle.color,
                                    topLeft = Offset(-rectWidth / 2, -rectHeight / 2),
                                    size = Size(rectWidth, rectHeight),
                                    alpha = alpha
                                )
                            }
                        }
                        ParticleShape.STAR -> {
                            val starPath = Path().apply {
                                val half = particleSizePx * 1.2f
                                val quarter = half * 0.25f
                                moveTo(posX, posY - half)
                                cubicTo(posX, posY - quarter, posX + quarter, posY, posX + half, posY)
                                cubicTo(posX + quarter, posY, posX, posY + quarter, posX, posY + half)
                                cubicTo(posX, posY + quarter, posX - quarter, posY, posX - half, posY)
                                cubicTo(posX - quarter, posY, posX, posY - quarter, posX, posY - half)
                                close()
                            }
                            withTransform({
                                rotate(rotation, Offset(posX, posY))
                            }) {
                                drawPath(
                                    path = starPath,
                                    color = particle.color,
                                    alpha = alpha
                                )
                            }
                        }
                        ParticleShape.CIRCLE -> {
                            drawCircle(
                                color = particle.color,
                                radius = particleSizePx * 0.8f,
                                center = Offset(posX, posY),
                                alpha = alpha * 0.25f
                            )
                            drawCircle(
                                color = Color.White,
                                radius = particleSizePx * 0.4f,
                                center = Offset(posX, posY),
                                alpha = alpha
                            )
                            drawCircle(
                                color = particle.color,
                                radius = particleSizePx * 0.3f,
                                center = Offset(posX, posY),
                                alpha = alpha
                            )
                        }
                    }
                }
            }
        }
    }
}



private fun formatFocusSeconds(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
