package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.todo.TodoItemEntity
import com.example.controlfree.todo.TodoDeletionSnapshot
import com.example.controlfree.todo.CommitmentPolicyInput
import com.example.controlfree.todo.CommitmentPolicyWithApps
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.TodoSubtaskEntity
import com.example.controlfree.todo.toJsonString
import com.example.controlfree.supervision.commitment.CommitmentScheduleReceiver
import com.example.controlfree.supervision.runtime.SupervisionScheduleReceiver
import com.example.controlfree.ui.todo.todo.TodoEditorDraft
import com.example.controlfree.ui.todo.todo.TodoUrgencyMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.ZoneId

class TodoListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val appAllowlistManager = AppAllowlistManager(application)

    private val _supervisableApps = MutableStateFlow<List<AllowedApp>>(emptyList())
    val supervisableApps = _supervisableApps.asStateFlow()
    private val _isLoadingSupervisableApps = MutableStateFlow(false)
    val isLoadingSupervisableApps = _isLoadingSupervisableApps.asStateFlow()
    private val _supervisableAppsLoadFailed = MutableStateFlow(false)
    val supervisableAppsLoadFailed = _supervisableAppsLoadFailed.asStateFlow()

    private val _isTodoDataLoaded = MutableStateFlow(false)
    val isTodoDataLoaded: StateFlow<Boolean> = _isTodoDataLoaded.asStateFlow()

    val todos: StateFlow<List<TodoItemEntity>> = repository.observeAllTodos()
        .onStart { _isTodoDataLoaded.value = false }
        .onEach { _isTodoDataLoaded.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val subtasks: StateFlow<List<TodoSubtaskEntity>> = repository.observeAllSubtasks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val commitmentPolicies: StateFlow<List<CommitmentPolicyWithApps>> =
        repository.observeAllCommitmentPolicies()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList()
            )

    init {
        loadSupervisableApps()
    }

    fun retrySupervisableApps() = loadSupervisableApps(force = true)

    private fun loadSupervisableApps(force: Boolean = false) {
        if (_isLoadingSupervisableApps.value || (!force && _supervisableApps.value.isNotEmpty())) return
        _isLoadingSupervisableApps.value = true
        _supervisableAppsLoadFailed.value = false
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching(appAllowlistManager::getSupervisableApps).getOrNull()
            }
            _supervisableApps.value = apps.orEmpty()
            _supervisableAppsLoadFailed.value = apps == null
            _isLoadingSupervisableApps.value = false
        }
    }

    fun addTodo(title: String, description: String?, dueDate: Long?, priority: Int, category: String) {
        viewModelScope.launch {
            val todo = TodoItemEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                description = description,
                dueDateEpochMillis = dueDate,
                priority = priority,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = category,
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = System.currentTimeMillis()
            )
            repository.saveTodo(todo)
        }
    }

    fun toggleCompletion(id: String, isCompleted: Boolean) {
        viewModelScope.launch {
            repository.toggleTodoCompletion(id, isCompleted)
            CommitmentScheduleReceiver.requestReconciliation(getApplication(), "todo_completion_changed")
            SupervisionScheduleReceiver.requestReconciliation(getApplication(), "todo_completion_changed")
        }
    }

    fun deleteTodo(id: String) {
        viewModelScope.launch {
            repository.deleteTodo(id)
            CommitmentScheduleReceiver.requestReconciliation(getApplication(), "todo_deleted")
            SupervisionScheduleReceiver.requestReconciliation(getApplication(), "todo_deleted")
        }
    }

    suspend fun deleteTodoForUndo(id: String): TodoDeletionSnapshot? = try {
        repository.deleteTodoForUndo(id)?.also {
            CommitmentScheduleReceiver.requestReconciliation(getApplication(), "todo_deleted")
            SupervisionScheduleReceiver.requestReconciliation(getApplication(), "todo_deleted")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun restoreDeletedTodo(snapshot: TodoDeletionSnapshot): Boolean = try {
        repository.restoreDeletedTodo(snapshot).also { restored ->
            if (restored) {
                CommitmentScheduleReceiver.requestReconciliation(getApplication(), "todo_delete_undone")
                SupervisionScheduleReceiver.requestReconciliation(getApplication(), "todo_delete_undone")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    fun saveTodo(draft: TodoEditorDraft) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            // 从数据库精确查询而不是内存列表：列表 Flow 初值为空，
            // 在首次发射前保存会把编辑误判为新建并生成新 id。
            val existing = draft.id?.let { id -> repository.getTodoById(id) }
            val id = draft.id ?: existing?.id ?: UUID.randomUUID().toString()
            val manuallyUrgent = draft.urgencyMode == TodoUrgencyMode.URGENT
            val legacyPriority = when {
                draft.isImportant && manuallyUrgent -> 3
                draft.isImportant -> 2
                manuallyUrgent -> 1
                else -> 0
            }
            val settings = draft.commitmentSettings
            repository.saveTodoWithCommitment(
                TodoItemEntity(
                    id = id,
                    title = draft.title,
                    description = draft.description.takeIf(String::isNotBlank),
                    dueDateEpochMillis = draft.dueAtEpochMillis,
                    priority = legacyPriority,
                    isCompleted = existing?.isCompleted ?: false,
                    completedAtEpochMillis = existing?.completedAtEpochMillis,
                    category = draft.project,
                    repeatRule = null,
                    associatedFocusPlanId = existing?.associatedFocusPlanId,
                    supervisionLockEnabled = settings.enabled,
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                    scheduledStartEpochMillis = draft.scheduledStartEpochMillis,
                    scheduledEndEpochMillis = draft.scheduledEndEpochMillis,
                    remindersJson = draft.reminders.toJsonString(),
                    estimatedFocusMinutes = draft.estimatedFocusMinutes,
                    isImportant = draft.isImportant,
                    urgencyMode = draft.urgencyMode.storedValue,
                    recurrenceType = draft.recurrence.type.storedValue,
                    recurrenceInterval = draft.recurrence.interval,
                    recurrenceDaysMask = draft.recurrence.weekdaysMask,
                    recurrenceDayOfMonth = draft.recurrence.dayOfMonth,
                    recurrenceSeriesId = existing?.recurrenceSeriesId,
                    recurrenceSequence = existing?.recurrenceSequence ?: 0,
                    updatedAtEpochMillis = now
                ),
                CommitmentPolicyInput(
                    enabled = settings.enabled,
                    localDeadlineMinute = null,
                    graceMinutes = settings.graceMinutes,
                    maxLockMinutes = settings.maxLockMinutes,
                    zoneId = ZoneId.systemDefault().id,
                    blockedPackages = settings.blockedPackages
                ),
                subtasks = draft.subtasks.mapIndexed { index, subtask ->
                    val createdAt = subtasks.value
                        .firstOrNull { it.id == subtask.id }
                        ?.createdAtEpochMillis
                        ?: now
                    TodoSubtaskEntity(
                        id = subtask.id,
                        todoId = id,
                        parentSubtaskId = subtask.parentSubtaskId,
                        title = subtask.title,
                        isCompleted = subtask.isCompleted,
                        completedAtEpochMillis = when {
                            !subtask.isCompleted -> null
                            else -> subtasks.value
                                .firstOrNull { it.id == subtask.id }
                                ?.completedAtEpochMillis
                                ?: now
                        },
                        sortOrder = index,
                        createdAtEpochMillis = createdAt,
                        updatedAtEpochMillis = now
                    )
                }
            )
            CommitmentScheduleReceiver.requestReconciliation(getApplication(), "todo_saved")
            SupervisionScheduleReceiver.requestReconciliation(getApplication(), "todo_saved")
        }
    }

    fun toggleSubtask(id: String, isCompleted: Boolean) {
        viewModelScope.launch { repository.toggleSubtaskCompletion(id, isCompleted) }
    }

    fun deleteSubtask(id: String) {
        viewModelScope.launch { repository.deleteSubtask(id) }
    }

    fun renameSubtask(id: String, newTitle: String) {
        viewModelScope.launch { repository.renameSubtask(id, newTitle) }
    }
}
