package com.example.controlfree.ui.todo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.controlfree.todo.toItemReminders
import com.example.controlfree.theme.BrandColors
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.TodoSubtaskEntity
import com.example.controlfree.todo.CommitmentPolicyWithApps
import com.example.controlfree.todo.CommitmentSourceType
import com.example.controlfree.ui.todo.anniversary.AnniversaryScreen
import com.example.controlfree.ui.todo.commitment.CommitmentEditorSettings
import com.example.controlfree.ui.todo.habit.HabitScreen
import com.example.controlfree.ui.todo.quicknote.QuickNoteScreen
import com.example.controlfree.ui.todo.ledger.LedgerScreen
import com.example.controlfree.ui.todo.todo.DEFAULT_FOCUS_MINUTES
import com.example.controlfree.ui.todo.todo.TodoItemUi
import com.example.controlfree.ui.todo.todo.TodoRecurrenceType as TodoUiRecurrenceType
import com.example.controlfree.ui.todo.todo.TodoRecurrenceUi
import com.example.controlfree.ui.todo.todo.TodoScreen
import com.example.controlfree.ui.todo.todo.TodoScreenActions
import com.example.controlfree.ui.todo.todo.TodoSubtaskUi
import com.example.controlfree.ui.todo.search.SearchNavigationRequest
import com.example.controlfree.ui.todo.search.SuperSearchSource
import com.example.controlfree.ui.todo.timeblock.TimeBlockScreen
import com.example.controlfree.ui.todo.timeblock.TimeBlockViewModel
import com.example.controlfree.ui.todo.todo.TodoUrgencyMode as TodoUiUrgencyMode
import com.example.controlfree.ui.todo.viewmodel.AnniversaryViewModel
import com.example.controlfree.ui.todo.viewmodel.HabitViewModel
import com.example.controlfree.ui.todo.viewmodel.QuickNoteViewModel
import com.example.controlfree.ui.todo.viewmodel.LedgerViewModel
import com.example.controlfree.ui.todo.viewmodel.TodoListViewModel
import com.example.controlfree.widget.WidgetCreateTarget
import com.example.controlfree.widget.WidgetTodoNavigationRequest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

enum class TodoSubTab(
    val displayName: String,
    val isVisibleInChecklist: Boolean = true
) {
    TODO("待办"),
    CALENDAR("日程"),
    HABIT("习惯"),
    LEDGER("账本"),
    ANNIVERSARY("时刻"),
    QUICK_NOTE("闪记");

    companion object {
        const val EXTRA_NAVIGATION_TARGET = "com.example.controlfree.extra.TODO_SUB_TAB"

        fun fromNavigationValue(value: String?): TodoSubTab? =
            entries.firstOrNull { tab -> tab.name == value }
    }
}

internal val checklistSubTabs = TodoSubTab.entries.filter(TodoSubTab::isVisibleInChecklist)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoMainScreen(
    modifier: Modifier = Modifier,
    initialSubTab: TodoSubTab = TodoSubTab.TODO,
    onSubTabSelected: (TodoSubTab) -> Unit = {},
    searchNavigationRequest: SearchNavigationRequest? = null,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit = {},
    widgetNavigationRequest: WidgetTodoNavigationRequest? = null,
    onWidgetNavigationConsumed: (WidgetTodoNavigationRequest) -> Unit = {},
    todoListViewModel: TodoListViewModel = viewModel(),
    habitViewModel: HabitViewModel = viewModel(),
    anniversaryViewModel: AnniversaryViewModel = viewModel(),
    quickNoteViewModel: QuickNoteViewModel = viewModel(),
    ledgerViewModel: LedgerViewModel = viewModel(),
    timeBlockViewModel: TimeBlockViewModel = viewModel()
) {
    val initialPage = checklistSubTabs.indexOf(initialSubTab).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { checklistSubTabs.size }
    )
    val coroutineScope = rememberCoroutineScope()
    val currentOnSubTabSelected by rememberUpdatedState(onSubTabSelected)

    LaunchedEffect(initialSubTab) {
        val requestedPage = checklistSubTabs.indexOf(initialSubTab)
        if (requestedPage >= 0 && pagerState.currentPage != requestedPage) {
            pagerState.scrollToPage(requestedPage)
        }
    }

    LaunchedEffect(searchNavigationRequest?.revision) {
        val request = searchNavigationRequest ?: return@LaunchedEffect
        val requestedPage = checklistSubTabs.indexOf(request.tab)
        if (requestedPage >= 0 && pagerState.currentPage != requestedPage) {
            pagerState.scrollToPage(requestedPage)
        }
    }

    LaunchedEffect(widgetNavigationRequest?.revision) {
        val request = widgetNavigationRequest ?: return@LaunchedEffect
        val requestedPage = checklistSubTabs.indexOf(request.subTab)
        if (requestedPage >= 0 && pagerState.currentPage != requestedPage) {
            pagerState.scrollToPage(requestedPage)
        }
    }

    LaunchedEffect(pagerState) {
        // currentPage 会在拖动越过阈值时提前变化；若立刻回写外部状态，
        // 用户取消手势后仍可能被外部状态强制切页，因此只发布最终稳定页。
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { settledPage ->
                currentOnSubTabSelected(checklistSubTabs[settledPage])
            }
    }

    Column(modifier = modifier.fillMaxSize().background(BrandColors.PageBackground)) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = BrandColors.BackgroundChrome,
            contentColor = BrandColors.BackgroundAccent,
            indicator = {},
            divider = {}
        ) {
            checklistSubTabs.forEachIndexed { index, tab ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) {
                            coroutineScope.launch { pagerState.scrollToPage(index) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val isSelected = pagerState.currentPage == index
                    val isImageBg = BrandColors.BackgroundType == "image"
                    val capsuleColor = if (isImageBg) {
                        if (BrandColors.UsesDarkForeground) {
                            if (isSelected) Color.White.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.48f)
                        } else {
                            if (isSelected) Color.Black.copy(alpha = 0.72f) else Color.Black.copy(alpha = 0.38f)
                        }
                    } else {
                        if (isSelected) {
                            BrandColors.Primary.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        }
                    }
                    Box(
                        modifier = Modifier
                            .background(
                                color = capsuleColor,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            tab.displayName,
                            fontSize = if (pagerState.currentPage == index) 15.sp else 13.sp,
                            fontWeight = if (pagerState.currentPage == index) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1,
                            softWrap = false,
                            color = if (pagerState.currentPage == index) {
                                BrandColors.BackgroundAccent
                            } else {
                                BrandColors.BackgroundTextSecondary
                            }
                        )
                    }
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = false
        ) { page ->
            when (checklistSubTabs[page]) {
                TodoSubTab.TODO -> TodoRoute(
                    todoListViewModel,
                    searchNavigationRequest?.takeIf { it.source == SuperSearchSource.TODO },
                    onSearchNavigationConsumed,
                    widgetNavigationRequest
                        ?.takeIf { it.createTarget == WidgetCreateTarget.TODO }
                        ?.revision,
                    widgetNavigationRequest?.let { request ->
                        { onWidgetNavigationConsumed(request) }
                    } ?: {}
                )
                TodoSubTab.HABIT -> HabitScreen(
                    habitViewModel,
                    searchNavigationRequest?.takeIf { it.source == SuperSearchSource.HABIT },
                    onSearchNavigationConsumed
                )
                TodoSubTab.ANNIVERSARY -> AnniversaryScreen(
                    anniversaryViewModel,
                    searchNavigationRequest?.takeIf { it.source == SuperSearchSource.ANNIVERSARY },
                    onSearchNavigationConsumed
                )
                TodoSubTab.QUICK_NOTE -> QuickNoteScreen(
                    viewModel = quickNoteViewModel,
                    searchNavigationRequest = searchNavigationRequest
                        ?.takeIf { it.source == SuperSearchSource.QUICK_NOTE },
                    onSearchNavigationConsumed = onSearchNavigationConsumed,
                    createRequestRevision = widgetNavigationRequest
                        ?.takeIf { it.createTarget == WidgetCreateTarget.QUICK_NOTE }
                        ?.revision,
                    onCreateRequestConsumed = widgetNavigationRequest?.let { request ->
                        { onWidgetNavigationConsumed(request) }
                    } ?: {}
                )
                TodoSubTab.LEDGER -> LedgerScreen(
                    viewModel = ledgerViewModel,
                    searchNavigationRequest = searchNavigationRequest
                        ?.takeIf { it.source == SuperSearchSource.LEDGER },
                    onSearchNavigationConsumed = onSearchNavigationConsumed
                )
                TodoSubTab.CALENDAR -> TimeBlockScreen(
                    viewModel = timeBlockViewModel,
                    todoListViewModel = todoListViewModel,
                    searchNavigationRequest = searchNavigationRequest
                        ?.takeIf { it.source == SuperSearchSource.CALENDAR },
                    onSearchNavigationConsumed = onSearchNavigationConsumed
                )
            }
        }
    }
}

@Composable
private fun TodoRoute(
    viewModel: TodoListViewModel,
    searchNavigationRequest: SearchNavigationRequest?,
    onSearchNavigationConsumed: (SearchNavigationRequest) -> Unit,
    createRequestRevision: Long?,
    onCreateRequestConsumed: () -> Unit
) {
    val todos by viewModel.todos.collectAsStateWithLifecycle()
    val isTodoDataLoaded by viewModel.isTodoDataLoaded.collectAsStateWithLifecycle()
    val subtasks by viewModel.subtasks.collectAsStateWithLifecycle()
    val commitmentPolicies by viewModel.commitmentPolicies.collectAsStateWithLifecycle()
    val supervisableApps by viewModel.supervisableApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingSupervisableApps.collectAsStateWithLifecycle()
    val appsLoadFailed by viewModel.supervisableAppsLoadFailed.collectAsStateWithLifecycle()
    val actions = remember(viewModel) {
        TodoScreenActions(
            saveTodo = viewModel::saveTodo,
            toggleTodo = viewModel::toggleCompletion,
            deleteTodo = viewModel::deleteTodoForUndo,
            restoreTodo = viewModel::restoreDeletedTodo,
            toggleSubtask = viewModel::toggleSubtask,
            retrySupervisableApps = viewModel::retrySupervisableApps,
            deleteSubtask = viewModel::deleteSubtask,
            renameSubtask = viewModel::renameSubtask
        )
    }
    val commitmentsByTodo = remember(commitmentPolicies) {
        commitmentPolicies
            .filter { it.policy.sourceType == CommitmentSourceType.TODO.storedValue }
            .associateBy { it.policy.sourceId }
    }
    TodoScreen(
        todos = todos.map { todo -> todo.toTodoItemUi(commitmentsByTodo[todo.id]) },
        subtasks = subtasks.map(TodoSubtaskEntity::toTodoSubtaskUi),
        actions = actions,
        isTodoDataLoaded = isTodoDataLoaded,
        supervisableApps = supervisableApps,
        isLoadingSupervisableApps = isLoadingApps,
        supervisableAppsLoadFailed = appsLoadFailed,
        searchNavigationRequest = searchNavigationRequest,
        onSearchNavigationConsumed = onSearchNavigationConsumed,
        createRequestRevision = createRequestRevision,
        onCreateRequestConsumed = onCreateRequestConsumed
    )
}

internal fun TodoItemEntity.toTodoItemUi(commitment: CommitmentPolicyWithApps?): TodoItemUi {
    val validScheduleEnd = scheduledEndEpochMillis
        ?.takeIf { end -> scheduledStartEpochMillis?.let { start -> end > start } == true }
    val validScheduleStart = scheduledStartEpochMillis.takeIf { validScheduleEnd != null }
    return TodoItemUi(
        id = id,
        title = title,
        description = description,
        dueAtEpochMillis = dueDateEpochMillis,
        scheduledStartEpochMillis = validScheduleStart,
        scheduledEndEpochMillis = validScheduleEnd,
        estimatedFocusMinutes = estimatedFocusMinutes?.coerceIn(1, 180) ?: DEFAULT_FOCUS_MINUTES,
        isImportant = isImportant || priority >= 2,
        urgencyMode = TodoUiUrgencyMode.fromStoredValue(urgencyMode),
        isCompleted = isCompleted,
        completedAtEpochMillis = completedAtEpochMillis,
        project = category,
        recurrence = TodoRecurrenceUi(
            type = TodoUiRecurrenceType.fromStoredValue(recurrenceType),
            interval = recurrenceInterval.coerceAtLeast(1),
            weekdaysMask = recurrenceDaysMask and 0b1111111,
            dayOfMonth = recurrenceDayOfMonth?.coerceIn(1, 31),
            seriesId = recurrenceSeriesId,
            sequence = recurrenceSequence.coerceAtLeast(0)
        ),
        supervisionLockEnabled = supervisionLockEnabled,
        commitmentSettings = commitment?.let { relation ->
            CommitmentEditorSettings(
                enabled = relation.policy.enabled,
                localDeadlineMinute = relation.policy.localDeadlineMinute,
                graceMinutes = relation.policy.graceMinutes,
                maxLockMinutes = relation.policy.maxLockMinutes,
                blockedPackages = relation.blockedApps.mapTo(linkedSetOf()) { it.packageName }
            )
        } ?: CommitmentEditorSettings(enabled = supervisionLockEnabled),
        reminders = remindersJson.toItemReminders(),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis
    )
}

private fun TodoSubtaskEntity.toTodoSubtaskUi(): TodoSubtaskUi = TodoSubtaskUi(
    id = id,
    todoId = todoId,
    parentSubtaskId = parentSubtaskId,
    title = title,
    isCompleted = isCompleted,
    sortOrder = sortOrder
)
