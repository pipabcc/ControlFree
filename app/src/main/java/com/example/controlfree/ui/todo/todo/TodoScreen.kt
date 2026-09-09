package com.example.controlfree.ui.todo.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.example.controlfree.ui.todo.timeblock.noRippleClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.TodoDeletionSnapshot
import com.example.controlfree.ui.todo.components.TodoEmptyStatePanel
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.showWidgetPinResult
import com.example.controlfree.widget.ControlFreeWidgetPinning
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TodoScreenActions(
    val saveTodo: (TodoEditorDraft) -> Unit,
    val toggleTodo: (id: String, completed: Boolean) -> Unit,
    val deleteTodo: suspend (id: String) -> TodoDeletionSnapshot?,
    val restoreTodo: suspend (TodoDeletionSnapshot) -> Boolean,
    val toggleSubtask: (id: String, completed: Boolean) -> Unit,
    val retrySupervisableApps: () -> Unit = {},
    val deleteSubtask: (id: String) -> Unit = { _ -> },
    val renameSubtask: (id: String, newTitle: String) -> Unit = { _, _ -> }
)

@Composable
fun TodoScreen(
    todos: List<TodoItemUi>,
    subtasks: List<TodoSubtaskUi>,
    actions: TodoScreenActions,
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    isTodoDataLoaded: Boolean = true,
    supervisableApps: List<AllowedApp> = emptyList(),
    isLoadingSupervisableApps: Boolean = false,
    supervisableAppsLoadFailed: Boolean = false,
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    createRequestRevision: Long? = null,
    onCreateRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewPrefs = remember(context) { context.getSharedPreferences("todo_view_settings", android.content.Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var viewModeName by rememberSaveable {
        mutableStateOf(viewPrefs.getString("todo_view_mode", TodoViewMode.LIST.name) ?: TodoViewMode.LIST.name)
    }
    var groupingModeName by rememberSaveable { mutableStateOf(TodoGroupingMode.TIME.name) }
    var completionFilterName by rememberSaveable { mutableStateOf(TodoCompletionFilter.ALL.name) }
    var dateFilterName by rememberSaveable { mutableStateOf(TodoDateFilter.ALL.name) }
    var selectedProject by rememberSaveable { mutableStateOf<String?>(null) }
    var importantOnly by rememberSaveable { mutableStateOf(false) }
    var urgentOnly by rememberSaveable { mutableStateOf(false) }
    var scheduledOnly by rememberSaveable { mutableStateOf(false) }
    var editorDraft by remember { mutableStateOf<TodoEditorDraft?>(null) }
    var isCollapsed by rememberSaveable {
        mutableStateOf(viewPrefs.getBoolean("todo_collapsed", false))
    }
    val expandedTodos = remember { mutableStateMapOf<String, Boolean>() }
    val viewMode = TodoViewMode.entries.firstOrNull { it.name == viewModeName } ?: TodoViewMode.LIST
    val groupingMode = TodoGroupingMode.entries.firstOrNull { it.name == groupingModeName }
        ?: TodoGroupingMode.TIME
    val filterCriteria = TodoFilterCriteria(
        completion = TodoCompletionFilter.entries.firstOrNull { it.name == completionFilterName }
            ?: TodoCompletionFilter.ALL,
        date = TodoDateFilter.entries.firstOrNull { it.name == dateFilterName }
            ?: TodoDateFilter.ALL,
        project = selectedProject,
        importantOnly = importantOnly,
        urgentOnly = urgentOnly,
        scheduledOnly = scheduledOnly
    )
    val projects = remember(todos) {
        todos
            .map { it.project.trim().ifEmpty { DEFAULT_PROJECT } }
            .distinctBy(String::lowercase)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LaunchedEffect(projects, selectedProject) {
        if (selectedProject != null && projects.none { it.equals(selectedProject, ignoreCase = true) }) {
            selectedProject = null
        }
    }
    val today = remember(nowEpochMillis, zoneId) {
        Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    }
    val visibleTodos = remember(todos, filterCriteria, today, zoneId, nowEpochMillis) {
        filterTodos(todos, filterCriteria, today, zoneId, nowEpochMillis)
    }
    val applyFilterCriteria: (TodoFilterCriteria) -> Unit = { updated ->
        completionFilterName = updated.completion.name
        dateFilterName = updated.date.name
        selectedProject = updated.project
        importantOnly = updated.importantOnly
        urgentOnly = updated.urgentOnly
        scheduledOnly = updated.scheduledOnly
    }
    val resetFilters: () -> Unit = {
        groupingModeName = TodoGroupingMode.TIME.name
        applyFilterCriteria(TodoFilterCriteria())
    }
    val subtasksByTodo = remember(subtasks) { subtasks.groupBy(TodoSubtaskUi::todoId) }
    val deleteWithUndo: (TodoItemUi) -> Unit = { todo ->
        coroutineScope.launch {
            val snapshot = actions.deleteTodo(todo.id)
            if (snapshot == null) {
                snackbarHostState.showSnackbar("删除失败，待办可能已不存在")
                return@launch
            }
            val result = snackbarHostState.showSnackbar(
                message = "已删除「${todo.title}」",
                actionLabel = "撤销",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                val restored = withContext(NonCancellable) {
                    actions.restoreTodo(snapshot)
                }
                snackbarHostState.showSnackbar(
                    if (restored) "已撤销删除" else "撤销失败：同一待办编号已存在"
                )
            }
        }
    }

    LaunchedEffect(searchNavigationRequest?.revision, todos, isTodoDataLoaded) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        val target = todos.firstOrNull { it.id == request.entityId }
        when {
            target != null -> {
                editorDraft = target.toEditorDraft(subtasksByTodo[target.id].orEmpty())
                onSearchNavigationConsumed(request)
            }
            isTodoDataLoaded -> {
                snackbarHostState.showSnackbar("该待办已不存在")
                onSearchNavigationConsumed(request)
            }
        }
    }

    LaunchedEffect(createRequestRevision) {
        if (createRequestRevision == null) return@LaunchedEffect
        editorDraft = TodoEditorDraft()
        onCreateRequestConsumed()
    }

    Box(modifier = modifier.fillMaxSize().background(BrandColors.PageBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TodoProgressHeader(todos = todos, today = today, zoneId = zoneId)
            TodoToolbar(
                viewMode = viewMode,
                groupingMode = groupingMode,
                filterCriteria = filterCriteria,
                projects = projects,
                onViewModeChange = { 
                    viewModeName = it.name 
                    viewPrefs.edit().putString("todo_view_mode", it.name).apply()
                },
                onGroupingModeChange = { groupingModeName = it.name },
                onFilterCriteriaChange = applyFilterCriteria,
                onReset = resetFilters,
                onWidgetClick = {
                    context.showWidgetPinResult(ControlFreeWidgetPinning.requestTodo(context))
                },
                isCollapsed = isCollapsed,
                onCollapsedChange = { 
                    isCollapsed = it 
                    viewPrefs.edit().putBoolean("todo_collapsed", it).apply()
                }
            )
            if (visibleTodos.isEmpty() && todos.isNotEmpty() && !filterCriteria.isDefault) {
                FilteredTodoEmptyState(onReset = resetFilters)
            } else if (!isTodoDataLoaded && todos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandColors.Primary)
                }
            } else when (viewMode) {
                TodoViewMode.LIST -> TodoGroupedList(
                    todos = visibleTodos,
                    groupingMode = groupingMode,
                    today = today,
                    subtasksByTodo = subtasksByTodo,
                    expandedTodos = expandedTodos,
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId,
                    actions = actions,
                    onEdit = { todo ->
                        editorDraft = todo.toEditorDraft(subtasksByTodo[todo.id].orEmpty())
                    },
                    onAddTodo = { editorDraft = TodoEditorDraft() },
                    collapsed = isCollapsed
                )
                TodoViewMode.MATRIX -> TodoMatrixList(
                    todos = visibleTodos,
                    subtasksByTodo = subtasksByTodo,
                    expandedTodos = expandedTodos,
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId,
                    actions = actions,
                    onEdit = { todo ->
                        editorDraft = todo.toEditorDraft(subtasksByTodo[todo.id].orEmpty())
                    },
                    onAddTodo = { editorDraft = TodoEditorDraft() },
                    collapsed = isCollapsed
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(4f))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                FloatingActionButton(
                    onClick = { editorDraft = TodoEditorDraft() },
                    shape = CircleShape,
                    containerColor = BrandColors.Primary.copy(alpha = 0.12f),
                    contentColor = BrandColors.Primary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建待办",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    editorDraft?.let { draft ->
        TodoEditorDialog(
            initialDraft = draft,
            knownProjects = todos.map(TodoItemUi::project).distinct(),
            onDismiss = { editorDraft = null },
            onSave = { savedDraft ->
                actions.saveTodo(savedDraft)
                editorDraft = null
            },
            onDeleteRequest = {
                val todo = draft.id?.let { id -> todos.firstOrNull { it.id == id } }
                if (todo != null) {
                    editorDraft = null
                    deleteWithUndo(todo)
                }
            },
            supervisableApps = supervisableApps,
            isLoadingSupervisableApps = isLoadingSupervisableApps,
            supervisableAppsLoadFailed = supervisableAppsLoadFailed,
            onRetrySupervisableApps = actions.retrySupervisableApps
        )
    }
}

@Composable
private fun TodoProgressHeader(
    todos: List<TodoItemUi>,
    today: LocalDate,
    zoneId: ZoneId
) {
    val dayProgress = remember(todos, today, zoneId) {
        calculateTodoDayProgress(todos, today, zoneId)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = dayProgress.fraction,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "今日待办完成进度"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(
                elevation = 7.dp,
                shape = TODO_CARD_SHAPE,
                ambientColor = Color(0x180F5132),
                spotColor = Color(0x140F5132)
            ),
        shape = TODO_CARD_SHAPE,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            BrandColors.SurfaceCard,
                            BrandColors.SurfaceCard,
                            BrandColors.PrimaryContainer.copy(alpha = 0.46f)
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(BrandColors.Primary, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "执行进度 · 今日",
                        color = BrandColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = dayProgress.completed.toString(),
                        color = BrandColors.Primary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 36.sp
                    )
                    Text(
                        text = " / ",
                        color = BrandColors.TextTertiary,
                        fontWeight = FontWeight.Normal,
                        fontSize = 23.sp,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                    Text(
                        text = dayProgress.total.toString(),
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 30.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = "已完成",
                        color = BrandColors.TextSecondary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 6.dp, bottom = 5.dp)
                    )
                }
                Text(
                    text = progressEncouragement(dayProgress.percentage),
                    color = BrandColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            TodoProgressRing(
                progress = animatedProgress,
                percentage = dayProgress.percentage,
                modifier = Modifier.size(104.dp)
            )
        }
    }
}

@Composable
private fun TodoProgressRing(
    progress: Float,
    percentage: Int,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val trackColor = BrandColors.OutlineSoft.copy(alpha = 0.64f)
        val progressColor = BrandColors.Primary
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$percentage%",
                color = BrandColors.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "完成率",
                color = BrandColors.TextTertiary,
                fontSize = 10.sp,
                letterSpacing = 0.7.sp
            )
        }
    }
}

private fun progressEncouragement(percentage: Int): String = when {
    percentage == 0 -> "新的一天，从第一项开始 🌱"
    percentage < 40 -> "好的开始，继续保持 🚀"
    percentage < 60 -> "稳步推进中，状态在线 💪"
    percentage < 80 -> "过半了，节奏很不错 🔥"
    percentage < 100 -> "胜利在望，就差一点 ⚡"
    else -> "今日全部完成，太棒了 🎉"
}

@Composable
private fun TodoToolbar(
    viewMode: TodoViewMode,
    groupingMode: TodoGroupingMode,
    filterCriteria: TodoFilterCriteria,
    projects: List<String>,
    onViewModeChange: (TodoViewMode) -> Unit,
    onGroupingModeChange: (TodoGroupingMode) -> Unit,
    onFilterCriteriaChange: (TodoFilterCriteria) -> Unit,
    onReset: () -> Unit,
    onWidgetClick: () -> Unit,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrandColors.SurfaceCard, RoundedCornerShape(11.dp))
                .height(38.dp)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 1. 左侧：滑块分段器（清单、象限）
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val modes = TodoViewMode.entries
                val segmentWidth = maxWidth / modes.size
                val indicatorOffset by animateDpAsState(
                    targetValue = segmentWidth * modes.indexOf(viewMode),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "清单象限分段器弹性滑块"
                )
                Box(
                    Modifier
                        .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .background(BrandColors.Primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                )
                Row(Modifier.fillMaxSize()) {
                    modes.forEach { mode ->
                        val textLabel = when (mode) {
                            TodoViewMode.LIST -> "列表"
                            TodoViewMode.MATRIX -> "象限"
                        }
                        val icon = when (mode) {
                            TodoViewMode.LIST -> Icons.AutoMirrored.Filled.List
                            TodoViewMode.MATRIX -> Icons.Default.GridView
                        }
                        val selected = viewMode == mode
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .noRippleClickable { onViewModeChange(mode) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = textLabel,
                                tint = if (selected) BrandColors.Primary else BrandColors.TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = textLabel,
                                color = if (selected) BrandColors.Primary else BrandColors.TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 2. 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(BrandColors.OutlineSoft.copy(alpha = 0.45f))
            )

            // 3. 桌面组件 Widgets 按钮
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onWidgetClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Widgets,
                    contentDescription = "添加待办桌面组件",
                    tint = BrandColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 4. 筛选 Filter 按钮
            TodoFilterButton(
                groupingMode = groupingMode,
                criteria = filterCriteria,
                projects = projects,
                onGroupingModeChange = onGroupingModeChange,
                onCriteriaChange = onFilterCriteriaChange,
                onReset = onReset
            )

            // 5. 折叠/展开按钮 (在筛选图标右侧)
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onCollapsedChange(!isCollapsed) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                    contentDescription = if (isCollapsed) "展开卡片" else "折叠卡片",
                    tint = BrandColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private enum class TodoAttributeFilter(val displayName: String) {
    IMPORTANT("重要"),
    URGENT("紧急"),
    SCHEDULED("已排程")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodoFilterButton(
    groupingMode: TodoGroupingMode,
    criteria: TodoFilterCriteria,
    projects: List<String>,
    onGroupingModeChange: (TodoGroupingMode) -> Unit,
    onCriteriaChange: (TodoFilterCriteria) -> Unit,
    onReset: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasActiveSettings = groupingMode != TodoGroupingMode.TIME || !criteria.isDefault

    Box {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(8.dp),
            color = if (hasActiveSettings) {
                BrandColors.Primary.copy(alpha = 0.12f)
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
            contentColor = if (hasActiveSettings) BrandColors.Primary else BrandColors.TextSecondary
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "筛选待办",
                        modifier = Modifier.size(18.dp)
                    )
                    if (hasActiveSettings) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(6.dp)
                                .background(BrandColors.Primary, CircleShape)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(300.dp)
                .heightIn(max = 520.dp)
                .background(BrandColors.OverlaySurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "筛选与分组",
                        color = BrandColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (hasActiveSettings) {
                        TextButton(onClick = onReset) {
                            Text("重置", fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider(color = BrandColors.OutlineSoft)
                TodoFilterSection(title = "分组方式") {
                    TodoFilterOptions(
                        options = TodoGroupingMode.entries,
                        selected = { it == groupingMode },
                        label = TodoGroupingMode::displayName,
                        onSelect = onGroupingModeChange
                    )
                }
                TodoFilterSection(title = "状态") {
                    TodoFilterOptions(
                        options = TodoCompletionFilter.entries,
                        selected = { it == criteria.completion },
                        label = TodoCompletionFilter::displayName,
                        onSelect = { onCriteriaChange(criteria.copy(completion = it)) }
                    )
                }
                TodoFilterSection(title = "日期") {
                    TodoFilterOptions(
                        options = TodoDateFilter.entries,
                        selected = { it == criteria.date },
                        label = TodoDateFilter::displayName,
                        onSelect = { onCriteriaChange(criteria.copy(date = it)) }
                    )
                }
                TodoFilterSection(title = "项目") {
                    TodoFilterOptions(
                        options = listOf<String?>(null) + projects,
                        selected = { project ->
                            project == null && criteria.project == null ||
                                project != null && project.equals(criteria.project, ignoreCase = true)
                        },
                        label = { it ?: "全部" },
                        onSelect = { onCriteriaChange(criteria.copy(project = it)) }
                    )
                }
                TodoFilterSection(title = "属性") {
                    TodoFilterOptions(
                        options = TodoAttributeFilter.entries,
                        selected = { attribute ->
                            when (attribute) {
                                TodoAttributeFilter.IMPORTANT -> criteria.importantOnly
                                TodoAttributeFilter.URGENT -> criteria.urgentOnly
                                TodoAttributeFilter.SCHEDULED -> criteria.scheduledOnly
                            }
                        },
                        label = TodoAttributeFilter::displayName,
                        onSelect = { attribute ->
                            val updated = when (attribute) {
                                TodoAttributeFilter.IMPORTANT -> criteria.copy(
                                    importantOnly = !criteria.importantOnly
                                )
                                TodoAttributeFilter.URGENT -> criteria.copy(
                                    urgentOnly = !criteria.urgentOnly
                                )
                                TodoAttributeFilter.SCHEDULED -> criteria.copy(
                                    scheduledOnly = !criteria.scheduledOnly
                                )
                            }
                            onCriteriaChange(updated)
                        }
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun TodoFilterSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = BrandColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TodoFilterOptions(
    options: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected(option),
                onClick = { onSelect(option) },
                label = { Text(label(option), fontSize = 12.sp) },
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected(option),
                    borderColor = BrandColors.OutlineSoft,
                    selectedBorderColor = Color.Transparent,
                    borderWidth = 1.dp
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = BrandColors.SurfaceCard,
                    labelColor = BrandColors.TextSecondary,
                    selectedContainerColor = BrandColors.Primary.copy(alpha = 0.14f),
                    selectedLabelColor = BrandColors.Primary
                )
            )
        }
    }
}


@Composable
private fun TodoGroupedList(
    todos: List<TodoItemUi>,
    groupingMode: TodoGroupingMode,
    today: LocalDate,
    subtasksByTodo: Map<String, List<TodoSubtaskUi>>,
    expandedTodos: MutableMap<String, Boolean>,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    actions: TodoScreenActions,
    onEdit: (TodoItemUi) -> Unit,
    onAddTodo: () -> Unit,
    collapsed: Boolean
) {
    val groups: List<Pair<String, List<TodoItemUi>>> = when (groupingMode) {
        TodoGroupingMode.TIME -> groupTodosByTime(todos, today, zoneId)
            .let { grouped -> TodoTimeBucket.entries.mapNotNull { bucket ->
                grouped[bucket]?.takeIf(List<TodoItemUi>::isNotEmpty)
                    ?.let { bucket.displayName to it }
            } }
        TodoGroupingMode.PROJECT -> groupTodosByProject(todos).toList()
    }
    if (groups.isEmpty()) {
        EmptyTodoState(onAddTodo)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { (title, items) ->
            item(key = "group:$title") {
                GroupHeader(
                    title = title,
                    items = items,
                    today = today,
                    zoneId = zoneId
                )
            }
            items(items, key = TodoItemUi::id) { todo ->
                TodoItemSurface(
                    todo = todo,
                    subtasks = subtasksByTodo[todo.id].orEmpty(),
                    expanded = expandedTodos[todo.id] == true,
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId,
                    actions = actions,
                    onExpandChange = { expandedTodos[todo.id] = it },
                    onEdit = { onEdit(todo) },
                    collapsed = collapsed
                )
            }
        }
    }
}

@Composable
private fun TodoMatrixList(
    todos: List<TodoItemUi>,
    subtasksByTodo: Map<String, List<TodoSubtaskUi>>,
    expandedTodos: MutableMap<String, Boolean>,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    actions: TodoScreenActions,
    onEdit: (TodoItemUi) -> Unit,
    onAddTodo: () -> Unit,
    collapsed: Boolean
) {
    if (todos.isEmpty()) {
        EmptyTodoState(onAddTodo)
        return
    }
    val grouped = remember(todos, nowEpochMillis) {
        todos.groupBy { it.matrixQuadrant(nowEpochMillis) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TodoMatrixQuadrant.entries.forEach { quadrant ->
            item(key = "quadrant:${quadrant.name}") {
                MatrixHeader(quadrant, grouped[quadrant].orEmpty().size)
            }
            items(grouped[quadrant].orEmpty(), key = TodoItemUi::id) { todo ->
                TodoItemSurface(
                    todo = todo,
                    subtasks = subtasksByTodo[todo.id].orEmpty(),
                    expanded = expandedTodos[todo.id] == true,
                    nowEpochMillis = nowEpochMillis,
                    zoneId = zoneId,
                    actions = actions,
                    onExpandChange = { expandedTodos[todo.id] = it },
                    onEdit = { onEdit(todo) },
                    collapsed = collapsed
                )
            }
        }
    }
}

@Composable
private fun TodoItemSurface(
    todo: TodoItemUi,
    subtasks: List<TodoSubtaskUi>,
    expanded: Boolean,
    nowEpochMillis: Long,
    zoneId: ZoneId,
    actions: TodoScreenActions,
    onExpandChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    collapsed: Boolean = false
) {
    val forest = remember(subtasks) { buildSubtaskForest(subtasks) }
    val completedSubtasks = subtasks.count(TodoSubtaskUi::isCompleted)
    val isUrgent = todo.isUrgentAt(nowEpochMillis)
    val quadrant = todo.matrixQuadrant(nowEpochMillis)
    val accentColor = todoQuadrantAccentColor(quadrant)
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandColors.SurfaceCard),
        shape = TODO_CARD_SHAPE,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(TODO_CARD_SHAPE)
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(accentColor.copy(alpha = 0.14f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = if (collapsed) 56.dp else 112.dp)
                    .padding(
                        start = 14.dp,
                        top = if (collapsed) 12.dp else 14.dp,
                        end = 16.dp,
                        bottom = if (collapsed) 12.dp else 10.dp
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TodoCompletionButton(
                        completed = todo.isCompleted,
                        onClick = { actions.toggleTodo(todo.id, !todo.isCompleted) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = todo.title,
                        modifier = Modifier.weight(1f),
                        color = if (todo.isCompleted) BrandColors.TextTertiary else BrandColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!todo.isCompleted) {
                        TodoStatusBadge(todo = todo, isUrgent = isUrgent)
                    }
                }
                if (!collapsed) {
                    Row(
                        modifier = Modifier.padding(start = 40.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = todo.project,
                            color = BrandColors.TextTertiary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            Modifier
                                .size(3.dp)
                                .background(BrandColors.OutlineSoft, CircleShape)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = BrandColors.TextTertiary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${todo.estimatedFocusMinutes} 分钟",
                                color = BrandColors.TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                        todo.scheduledStartEpochMillis?.let { scheduledStart ->
                            TodoScheduleChip(
                                epochMillis = scheduledStart,
                                today = Instant.ofEpochMilli(nowEpochMillis)
                                    .atZone(zoneId)
                                    .toLocalDate(),
                                zoneId = zoneId
                            )
                        }
                    }
                    if (subtasks.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(start = 40.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { onExpandChange(!expanded) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    "$completedSubtasks/${subtasks.size}",
                                    color = BrandColors.TextTertiary,
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (expanded) "收起子任务" else "展开子任务",
                                    modifier = Modifier.size(17.dp),
                                    tint = BrandColors.TextTertiary
                                )
                            }
                        }
                    }
                    if (expanded) {
                        forest.forEach { node ->
                            SubtaskNodeRow(node = node, depth = 0, actions = actions)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoCompletionButton(completed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(28.dp)
            .semantics {
                role = Role.Checkbox
                contentDescription = if (completed) "标记未完成" else "标记完成"
                stateDescription = if (completed) "已完成" else "未完成"
            },
        shape = CircleShape,
        color = if (completed) BrandColors.Success else Color.Transparent,
        border = if (completed) null else BorderStroke(2.dp, BrandColors.Outline),
        contentColor = if (completed) Color.White else BrandColors.TextTertiary
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (completed) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "标记未完成",
                    modifier = Modifier.size(19.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun TodoStatusBadge(todo: TodoItemUi, isUrgent: Boolean) {
    val containerColor = when {
        isUrgent -> BrandColors.WarningContainer
        todo.isImportant -> BrandColors.PrimaryContainer
        else -> BrandColors.SurfaceRaised.copy(alpha = 0.45f)
    }
    val contentColor = when {
        isUrgent -> BrandColors.Warning
        todo.isImportant -> BrandColors.Primary
        else -> BrandColors.TextSecondary
    }
    val label = when {
        isUrgent -> "紧急"
        todo.isImportant -> "重要"
        else -> "普通"
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isUrgent && !todo.isCompleted) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TodoScheduleChip(epochMillis: Long, today: LocalDate, zoneId: ZoneId) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = BrandColors.PrimaryContainer.copy(alpha = 0.72f),
        contentColor = BrandColors.Primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = formatTodoSchedule(epochMillis, today, zoneId),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SubtaskNodeRow(
    node: TodoSubtaskNode,
    depth: Int,
    actions: TodoScreenActions
) {
    var expanded by rememberSaveable(node.item.id) { mutableStateOf(true) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(node.item.title) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 18).coerceAtMost(90).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (node.children.isNotEmpty()) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起下级" else "展开下级",
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(Modifier.size(30.dp))
        }
        Checkbox(
            checked = node.item.isCompleted,
            onCheckedChange = { actions.toggleSubtask(node.item.id, it) }
        )
        Text(
            node.item.title,
            modifier = Modifier.weight(1f),
            color = if (node.item.isCompleted) BrandColors.TextTertiary else BrandColors.TextPrimary,
            fontSize = 13.sp,
            textDecoration = if (node.item.isCompleted) TextDecoration.LineThrough else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = {
                renameText = node.item.title
                showRenameDialog = true
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑子任务",
                tint = BrandColors.TextTertiary,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { actions.deleteSubtask(node.item.id) },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除子任务",
                tint = BrandColors.Danger,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名子任务", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("子任务标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = renameText.trim()
                        if (t.isNotEmpty()) {
                            actions.renameSubtask(node.item.id, t)
                            showRenameDialog = false
                        }
                    }
                ) {
                    Text("保存", fontWeight = FontWeight.Bold, color = BrandColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消", color = BrandColors.TextSecondary)
                }
            }
        )
    }

    if (expanded) {
        node.children.forEach { child ->
            SubtaskNodeRow(child, depth + 1, actions)
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    items: List<TodoItemUi>,
    today: LocalDate,
    zoneId: ZoneId
) {
    val isToday = title == TodoTimeBucket.TODAY.displayName
    val pendingCount = items.count { !it.isCompleted }
    val dateLabel = if (isToday) {
        "${today.monthValue}月${today.dayOfMonth}日 · ${formatWeekday(today)}"
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = BrandColors.BackgroundTextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        dateLabel?.let {
            Text(
                text = it,
                color = BrandColors.BackgroundTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 9.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.padding(start = 8.dp),
            shape = RoundedCornerShape(99.dp),
            color = if (pendingCount > 0) {
                BrandColors.PrimaryContainer.copy(alpha = 0.72f)
            } else {
                BrandColors.SurfaceRaised.copy(alpha = 0.58f)
            }
        ) {
            Text(
                text = if (pendingCount > 0) "$pendingCount 待办" else "已完成 ✓",
                color = if (pendingCount > 0) BrandColors.Primary else BrandColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

private fun formatWeekday(date: LocalDate): String =
    when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

@Composable
private fun MatrixHeader(quadrant: TodoMatrixQuadrant, count: Int) {
    val color = todoQuadrantAccentColor(quadrant).copy(alpha = 0.14f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(quadrant.displayName, color = BrandColors.TextPrimary, fontWeight = FontWeight.Bold)
        Text("  $count", color = BrandColors.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyTodoState(onAddTodo: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TodoEmptyStatePanel(
            icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
            title = "还没有待办",
            description = "把下一件要做的事记录下来，完成后即可勾选归档",
            actionIcon = Icons.Default.Add,
            actionLabel = "新建待办",
            onAction = onAddTodo
        )
    }
}

@Composable
private fun FilteredTodoEmptyState(onReset: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TodoEmptyStatePanel(
            icon = Icons.Default.FilterAlt,
            title = "没有符合条件的待办",
            description = "调整筛选条件后再试试",
            actionLabel = "重置筛选",
            onAction = onReset
        )
    }
}

private fun formatTodoSchedule(epochMillis: Long, today: LocalDate, zoneId: ZoneId): String {
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    val dayLabel = when (dateTime.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> "${dateTime.monthValue}月${dateTime.dayOfMonth}日"
    }
    return "$dayLabel ${dateTime.hour.toString().padStart(2, '0')}:${dateTime.minute.toString().padStart(2, '0')}"
}

private val TODO_CARD_SHAPE = RoundedCornerShape(11.dp)

@Composable
private fun todoQuadrantAccentColor(quadrant: TodoMatrixQuadrant): Color = when (quadrant) {
    TodoMatrixQuadrant.IMPORTANT_URGENT -> BrandColors.Danger
    TodoMatrixQuadrant.IMPORTANT_NOT_URGENT -> BrandColors.Primary
    TodoMatrixQuadrant.NOT_IMPORTANT_URGENT -> BrandColors.Warning
    TodoMatrixQuadrant.NOT_IMPORTANT_NOT_URGENT -> BrandColors.Outline
}
