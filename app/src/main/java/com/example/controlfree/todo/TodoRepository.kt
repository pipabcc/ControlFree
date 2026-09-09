package com.example.controlfree.todo

import android.content.Context
import androidx.room.withTransaction
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import com.example.controlfree.supervision.persistence.SupervisionPlanWithRanges
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class QuickNoteConversionResult(
    val targetId: String,
    val wasCreated: Boolean
)

data class TodoDeletionSnapshot(
    val todo: TodoItemEntity,
    val subtasks: List<TodoSubtaskEntity>,
    val commitmentPolicy: CommitmentPolicyWithApps?,
    val openCommitmentOccurrences: List<CommitmentOccurrenceEntity>,
    val oneTimeFocusPlan: SupervisionPlanWithRanges?,
    val deletedAtEpochMillis: Long
)

data class QuickNoteDeletionSnapshot(
    val note: QuickNoteEntity,
    val conversions: List<QuickNoteConversionEntity>,
    val mediaUriToRelease: String?
)

data class AnniversaryDeletionSnapshot(
    val anniversary: AnniversaryItemEntity
)

private data class QuickNoteAssistantCommit(
    val result: QuickNoteAssistantSaveResult,
    val todosToSchedule: List<TodoItemEntity>,
    val habitsToSchedule: List<HabitItemEntity>,
    val anniversariesToSchedule: List<AnniversaryItemEntity>
)

class TodoRepository private constructor(
    private val database: ControlFreeDatabase,
    private val clock: Clock,
    private val context: Context?
) {
    private val todoDao = database.todoDao()
    private val habitDao = database.habitDao()
    private val anniversaryDao = database.anniversaryDao()
    private val quickNoteDao = database.quickNoteDao()
    private val eventDao = database.productivityEventDao()
    private val commitmentDao = database.commitmentDao()
    private val supervisionPlanDao = database.supervisionPlanDao()
    private val ledgerDao = database.ledgerDao()
    private val supervisionPlanRepository = SupervisionPlanRepository.createForDatabase(database)

    private val reminderScheduler = context?.let { UnifiedReminderAlarmScheduler(it.applicationContext) }

    fun observeLedgerEntries(): Flow<List<LedgerEntryEntity>> = ledgerDao.observeLedgerEntries()

    fun observeLedgerEntriesBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<LedgerEntryEntity>> {
        requireValidRange(startEpochMillis, endExclusiveEpochMillis)
        return ledgerDao.observeLedgerEntriesBetween(startEpochMillis, endExclusiveEpochMillis)
    }

    fun observeCurrentMonthLedger(): Flow<List<LedgerEntryEntity>> {
        val now = LocalDate.now(clock)
        val startOfMonth = now.withDayOfMonth(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val nextMonth = now.withDayOfMonth(1).plusMonths(1)
            .atStartOfDay(clock.zone).toInstant().toEpochMilli()
        return ledgerDao.observeLedgerEntriesBetween(startOfMonth, nextMonth)
    }

    suspend fun saveLedgerEntries(entries: List<LedgerEntryEntity>): Int = database.withTransaction {
        require(entries.isNotEmpty()) { "至少需要一笔账目" }
        entries.forEach(::validateLedgerEntry)
        ledgerDao.insertLedgerEntries(entries)
        entries.size
    }

    /**
     * 编辑已落库账目时只覆盖界面允许修改的字段。迁移保留的历史行可能包含旧版的
     * note/source/time/id 值，不能因为这些非编辑字段不符合新协议而阻断用户修正账目。
     */
    suspend fun updateLedgerEntryUserFields(
        id: String,
        title: String,
        amountFen: Long,
        direction: LedgerDirection,
        category: LedgerCategory
    ): LedgerEntryEntity = database.withTransaction {
        val existing = requireNotNull(ledgerDao.getLedgerEntryById(id)) { "账目不存在" }
        val normalizedTitle = normalizeRequiredText(title, MAX_LEDGER_TITLE_LENGTH, "账目标题")
        require(LedgerAmountCodec.isValidFen(amountFen)) { "账目金额无效" }
        require(category.direction == direction) { "账目分类与收支方向不一致" }
        val updatedAt = maxOf(existing.updatedAtEpochMillis, nowEpochMillis())
        check(
            ledgerDao.updateLedgerEntryUserFields(
                id = id,
                title = normalizedTitle,
                amount = amountFen,
                direction = direction.storedValue,
                category = category.storedValue,
                updatedAtEpochMillis = updatedAt
            ) == 1
        ) { "账目更新失败" }
        requireNotNull(ledgerDao.getLedgerEntryById(id)) { "账目更新后不存在" }
    }

    suspend fun deleteLedgerEntry(id: String) = database.withTransaction {
        ledgerDao.deleteLedgerEntry(id)
    }

    /**
     * 删除撤销必须原样恢复已落库实体，不能套用新账目的严格协议校验。
     * 冲突时保留现有记录，避免撤销覆盖删除后刚写入的同 ID 数据。
     */
    internal suspend fun restoreDeletedLedgerEntry(entry: LedgerEntryEntity): Boolean =
        database.withTransaction {
            ledgerDao.insertLedgerEntriesIfAbsent(listOf(entry)).single() != -1L
        }

    fun observeMonthSummary(year: Int, month: Int): Flow<LedgerMonthSummary> {
        val localDate = LocalDate.of(year, month, 1)
        val startOfMonth = localDate.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val endOfMonth = localDate.plusMonths(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()

        return observeLedgerSummaryBetween(startOfMonth, endOfMonth)
    }

    fun observeLedgerSummaryBetween(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<LedgerMonthSummary> {
        requireValidRange(startEpochMillis, endExclusiveEpochMillis)

        val directionTotalsFlow = ledgerDao.observeMonthlyDirectionTotals(
            startEpochMillis,
            endExclusiveEpochMillis
        )
        val categoryTotalsFlow = ledgerDao.observeMonthlyCategoryTotals(
            startEpochMillis,
            endExclusiveEpochMillis
        )

        return combine(directionTotalsFlow, categoryTotalsFlow) { dirTotals, catTotals ->
            val expense = dirTotals.firstOrNull { it.direction == LedgerDirection.EXPENSE.storedValue }?.total ?: 0L
            val income = dirTotals.firstOrNull { it.direction == LedgerDirection.INCOME.storedValue }?.total ?: 0L
            LedgerMonthSummary(
                totalExpense = expense,
                totalIncome = income,
                balance = income - expense,
                categoryTotals = catTotals
            )
        }
    }

    /**
     * 将一轮 AI 三位一体结果在单个 Room 事务中写入。batchId 是幂等键的一部分，
     * 重复点击只会命中相同的主键，不会生成第二批流水或待办。
     */
    suspend fun saveLedgerRouting(
        batchId: String,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>
    ): LedgerRoutingSaveResult = database.withTransaction {
        require(ledgerEntries.isNotEmpty() || todoItems.isNotEmpty()) { "没有可保存的内容" }
        persistLedgerRouting(
            batchId = normalizeRequiredText(batchId, MAX_ID_LENGTH, "账本批次编号"),
            sourceNoteId = null,
            ledgerEntries = ledgerEntries,
            todoItems = todoItems
        )
    }

    /**
     * 闪念转账的唯一入口：账目、待办、转化审计和闪念归档必须一起提交。
     * 闪念只归档，不删除，因此附件 URI 仍由闪念记录持有，媒体权限不会被提前清理。
     */
    suspend fun convertNoteToLedger(
        noteId: String,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>,
        batchId: String = deterministicId("quick-note:$noteId:ledger")
    ): QuickNoteConversionResult = database.withTransaction {
        val normalizedBatchId = normalizeRequiredText(batchId, MAX_ID_LENGTH, "账本批次编号")
        existingConversionTarget(noteId, QuickNoteConversionTarget.LEDGER)?.let { existingBatchId ->
            if (normalizedBatchId == existingBatchId) {
                // 已有审计时只能核验，不能因用户后来删除目标而重新生成同批数据。
                persistLedgerRouting(
                    batchId = existingBatchId,
                    sourceNoteId = normalizeRequiredText(noteId, MAX_ID_LENGTH, "闪念编号"),
                    ledgerEntries = ledgerEntries,
                    todoItems = todoItems,
                    returnExistingWhenTargetsMissing = true
                )
            }
            // 兼容旧版本中“已存在记账审计但闪念未归档”的异常状态；重复调用应收敛
            // 到完整的幂等结果，而不只是返回旧批次编号。
            quickNoteDao.updateStatus(
                noteId,
                QuickNoteStatus.ARCHIVED.storedValue,
                nowEpochMillis()
            )
            return@withTransaction QuickNoteConversionResult(existingBatchId, wasCreated = false)
        }
        requireNotNull(quickNoteDao.getById(noteId)) { "闪念不存在" }
        require(ledgerEntries.isNotEmpty() || todoItems.isNotEmpty()) { "没有可转换的内容" }
        persistLedgerRouting(
            batchId = normalizedBatchId,
            sourceNoteId = normalizeRequiredText(noteId, MAX_ID_LENGTH, "闪念编号"),
            ledgerEntries = ledgerEntries,
            todoItems = todoItems
        )
        val now = nowEpochMillis()
        val conversionCreated = insertQuickNoteConversion(
            noteId = noteId,
            target = QuickNoteConversionTarget.LEDGER,
            targetId = normalizedBatchId,
            nowEpochMillis = now
        )
        quickNoteDao.updateStatus(noteId, QuickNoteStatus.ARCHIVED.storedValue, now)
        QuickNoteConversionResult(normalizedBatchId, wasCreated = conversionCreated)
    }

    fun observeAllTodos(): Flow<List<TodoItemEntity>> = todoDao.observeAll()

    fun observeTodosInRange(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<TodoItemEntity>> {
        requireValidRange(startEpochMillis, endExclusiveEpochMillis)
        return todoDao.observeInRange(startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun getTodoById(id: String): TodoItemEntity? = todoDao.getById(id)

    suspend fun saveTodo(todo: TodoItemEntity): TodoItemEntity = database.withTransaction {
        val now = nowEpochMillis()
        val existing = todoDao.getById(todo.id)
        val reconciled = reconcileOneTimeFocusAssociation(
            existing = existing,
            candidate = normalizeTodo(todo, now),
            nowEpochMillis = now
        )
        val normalized = ensureTodoVersion(existing, reconciled, now)
        todoDao.upsertTodo(normalized)
        reminderScheduler?.scheduleTodoReminders(normalized)
        normalized
    }

    suspend fun saveTodoWithCommitment(
        todo: TodoItemEntity,
        commitment: CommitmentPolicyInput,
        subtasks: List<TodoSubtaskEntity>? = null
    ): TodoItemEntity = database.withTransaction {
        require(!commitment.enabled || todo.dueDateEpochMillis != null) {
            "启用防拖延监督前必须设置待办截止时间"
        }
        require(commitment.localDeadlineMinute == null) { "待办策略不得设置每日截止分钟" }
        val now = nowEpochMillis()
        val existing = todoDao.getById(todo.id)
        val reconciled = reconcileOneTimeFocusAssociation(
            existing = existing,
            candidate = normalizeTodo(
                todo.copy(supervisionLockEnabled = commitment.enabled),
                now
            ),
            nowEpochMillis = now
        )
        val normalized = ensureTodoVersion(existing, reconciled, now)
        todoDao.upsertTodo(normalized)
        replaceCommitmentPolicy(
            sourceType = CommitmentSourceType.TODO,
            sourceId = normalized.id,
            input = commitment,
            nowEpochMillis = now
        )?.let { policy ->
            CommitmentOccurrenceFactory.forTodo(policy, normalized, now)?.let {
                upsertCommitmentOccurrence(it)
            }
        }
        reminderScheduler?.scheduleTodoReminders(normalized)
        subtasks?.let { desiredSubtasks ->
            replaceTodoSubtasks(
                todoId = normalized.id,
                desiredSubtasks = desiredSubtasks,
                nowEpochMillis = now
            )
        }
        normalized
    }

    private suspend fun replaceTodoSubtasks(
        todoId: String,
        desiredSubtasks: List<TodoSubtaskEntity>,
        nowEpochMillis: Long
    ) {
        val normalized = desiredSubtasks
            .also { subtasks ->
                require(subtasks.distinctBy(TodoSubtaskEntity::id).size == subtasks.size) {
                    "子任务编号不能重复"
                }
            }
            .sortedWith(compareBy(TodoSubtaskEntity::sortOrder, TodoSubtaskEntity::id))
            .mapIndexed { index, subtask ->
                require(subtask.todoId == todoId) { "子任务不属于当前待办" }
                subtask.copy(
                    title = normalizeRequiredText(
                        subtask.title,
                        MAX_SUBTASK_TITLE_LENGTH,
                        "子任务标题"
                    ),
                    sortOrder = index,
                    updatedAtEpochMillis = nowEpochMillis
                )
            }
        val ids = normalized.mapTo(linkedSetOf(), TodoSubtaskEntity::id)
        require(normalized.none { it.parentSubtaskId != null && it.parentSubtaskId !in ids }) {
            "子任务父节点不存在"
        }
        require(normalized.none { it.parentSubtaskId == it.id }) { "子任务不能以自身为父节点" }
        val parentById = normalized.associate { it.id to it.parentSubtaskId }
        require(normalized.none { subtask ->
            val visited = mutableSetOf<String>()
            var currentId: String? = subtask.id
            while (currentId != null && visited.add(currentId)) {
                currentId = parentById[currentId]
            }
            currentId != null
        }) { "子任务层级不能形成循环" }
        todoDao.deleteSubtasksForTodo(todoId)
        if (normalized.isNotEmpty()) {
            val inserted = todoDao.insertSubtasksIfAbsent(normalized)
            check(inserted.all { it != -1L }) { "子任务保存失败" }
        }
    }

    suspend fun deleteTodo(id: String) = database.withTransaction {
        todoDao.getById(id)?.let { todo ->
            deleteAssociatedOneTimeFocusPlan(todo, nowEpochMillis())
            reminderScheduler?.cancelTodoReminders(todo.id, todo.remindersJson.toItemReminders().map { it.minutesBefore })
        }
        todoDao.deleteSubtasksForTodo(id)
        commitmentDao.getPolicyBySource(CommitmentSourceType.TODO.storedValue, id)?.let { policy ->
            commitmentDao.satisfyOpenOccurrencesForPolicy(policy.id, nowEpochMillis())
            commitmentDao.deleteBlockedApps(policy.id)
            commitmentDao.deletePolicy(policy.id)
        }
        todoDao.delete(id)
    }

    suspend fun deleteTodoForUndo(id: String): TodoDeletionSnapshot? =
        database.withTransaction {
            val todo = todoDao.getById(id) ?: return@withTransaction null
            val subtasks = todoDao.getSubtasks(id)
            val commitmentPolicy = commitmentDao.getPolicyWithAppsBySource(
                CommitmentSourceType.TODO.storedValue,
                id
            )
            val openOccurrences = commitmentPolicy?.policy?.id
                ?.let { commitmentDao.getOpenOccurrencesForPolicy(it) }
                .orEmpty()
            val oneTimeFocusPlan = todo.associatedFocusPlanId
                ?.let { supervisionPlanDao.getById(it) }
                ?.takeIf { it.isOneTimeFocusRecord() }
            val deletedAt = nowEpochMillis()
            val snapshot = TodoDeletionSnapshot(
                todo = todo,
                subtasks = subtasks,
                commitmentPolicy = commitmentPolicy,
                openCommitmentOccurrences = openOccurrences,
                oneTimeFocusPlan = oneTimeFocusPlan,
                deletedAtEpochMillis = deletedAt
            )

            reminderScheduler?.cancelTodoReminders(
                todo.id,
                todo.remindersJson.toItemReminders().map { it.minutesBefore }
            )
            oneTimeFocusPlan?.let {
                deleteOneTimeFocusPlanAndClearAssociations(it.plan.planId, deletedAt)
            }
            todoDao.deleteSubtasksForTodo(id)
            commitmentPolicy?.policy?.let { policy ->
                if (openOccurrences.isNotEmpty()) {
                    commitmentDao.satisfyOpenOccurrencesForPolicy(policy.id, deletedAt)
                }
                commitmentDao.deleteBlockedApps(policy.id)
                commitmentDao.deletePolicy(policy.id)
            }
            check(todoDao.delete(id) == 1) { "待办删除失败" }
            snapshot
        }

    suspend fun restoreDeletedTodo(snapshot: TodoDeletionSnapshot): Boolean {
        val restored = restoreWithoutOverwrite {
            validateTodoDeletionSnapshot(snapshot)
            ensureInserted(todoDao.insertTodoIfAbsent(snapshot.todo))
            ensureInserted(todoDao.insertSubtasksIfAbsent(snapshot.subtasks))
            snapshot.commitmentPolicy?.let { relation ->
                ensureInserted(commitmentDao.insertPolicyIfAbsent(relation.policy))
                ensureInserted(commitmentDao.insertBlockedApps(relation.blockedApps))
                snapshot.openCommitmentOccurrences.forEach { occurrence ->
                    restoreOpenOccurrence(snapshot, occurrence)
                }
            }
            snapshot.oneTimeFocusPlan?.let { plan ->
                ensureInserted(supervisionPlanDao.insertPlanIfAbsent(plan.plan))
                plan.globalPolicy?.let {
                    ensureInserted(supervisionPlanDao.insertGlobalPolicyIfAbsent(it))
                }
                plan.appPolicy?.let {
                    ensureInserted(supervisionPlanDao.insertAppPolicyIfAbsent(it))
                }
                ensureInserted(supervisionPlanDao.insertTimeRangesIfAbsent(plan.ranges))
                plan.activationReservation?.let {
                    ensureInserted(supervisionPlanDao.insertActivationReservationIfAbsent(it))
                }
            }
        }
        if (restored) reminderScheduler?.scheduleTodoReminders(snapshot.todo)
        return restored
    }

    suspend fun toggleTodoCompletion(
        id: String,
        isCompleted: Boolean,
        completedAtEpochMillis: Long = nowEpochMillis()
    ): TodoCompletionResult? = database.withTransaction {
        val current = todoDao.getById(id) ?: return@withTransaction null
        if (current.isCompleted == isCompleted) {
            val next = recurringSuccessor(current)
            return@withTransaction TodoCompletionResult(current, next)
        }
        val recurrence = TodoRecurrenceType.fromStoredValue(current.recurrenceType)
        val updated = ensureTodoVersion(
            existing = current,
            candidate = current.copy(
                isCompleted = isCompleted,
                completedAtEpochMillis = completedAtEpochMillis.takeIf { isCompleted },
                associatedFocusPlanId = current.associatedFocusPlanId.takeUnless { isCompleted },
                recurrenceSeriesId = if (recurrence == TodoRecurrenceType.NONE) {
                    current.recurrenceSeriesId
                } else {
                    current.recurrenceSeriesId ?: current.id
                },
                updatedAtEpochMillis = completedAtEpochMillis
            ),
            nowEpochMillis = nowEpochMillis()
        )
        val currentCommitment = commitmentDao.getPolicyWithAppsBySource(
            CommitmentSourceType.TODO.storedValue,
            updated.id
        )
        todoDao.upsertTodo(updated)
        if (isCompleted) {
            reminderScheduler?.cancelTodoReminders(updated.id, updated.remindersJson.toItemReminders().map { it.minutesBefore })
        } else {
            reminderScheduler?.scheduleTodoReminders(updated)
        }
        if (!isCompleted) {
            commitmentDao.getPolicyBySource(
                CommitmentSourceType.TODO.storedValue,
                updated.id
            )?.let { policy ->
                CommitmentOccurrenceFactory.forTodo(policy, updated, completedAtEpochMillis)?.let { candidate ->
                    commitmentDao.reopenOccurrence(
                        policy.id,
                        candidate.occurrenceKey,
                        completedAtEpochMillis
                    )
                    upsertCommitmentOccurrence(candidate)
                }
            }
            return@withTransaction TodoCompletionResult(updated, null)
        }

        // 待办完成后不应继续保留其一次性专注锁；计划删除与待办更新处于同一事务。
        deleteAssociatedOneTimeFocusPlan(
            todo = current,
            updatedAtEpochMillis = updated.updatedAtEpochMillis
        )

        recordReward(
            sourceKey = "todo:complete:${updated.id}",
            rewardType = ProductivityRewardType.TODO_COMPLETED,
            subjectId = updated.id,
            points = ProductivityRewardPolicy.TODO_COMPLETION_POINTS,
            occurredAtEpochMillis = completedAtEpochMillis
        )
        currentCommitment?.policy?.let { policy ->
            commitmentDao.satisfyOpenOccurrencesForPolicy(policy.id, completedAtEpochMillis)
        }

        val nextId = deterministicId(
            "todo-recurrence:${updated.recurrenceSeriesId}:${updated.recurrenceSequence + 1}"
        )
        val candidate = TodoRecurrenceCalculator.nextOccurrence(
            todo = updated,
            completedAt = Instant.ofEpochMilli(completedAtEpochMillis),
            zoneId = clock.zone,
            nextId = nextId,
            createdAtEpochMillis = completedAtEpochMillis
        )
        if (candidate == null) return@withTransaction TodoCompletionResult(updated, null)
        val inserted = todoDao.insertTodoIfAbsent(candidate) != -1L
        if (inserted) {
            val clonedSubtasks = TodoSubtaskTreeBuilder.cloneForTodo(
                subtasks = todoDao.getSubtasks(updated.id),
                newTodoId = candidate.id,
                newIdFor = { original -> deterministicId("${candidate.id}:subtask:${original.id}") },
                nowEpochMillis = completedAtEpochMillis
            )
            if (clonedSubtasks.isNotEmpty()) todoDao.insertSubtasksIfAbsent(clonedSubtasks)
            currentCommitment?.takeIf { it.policy.enabled }?.let { relation ->
                val clonedPolicy = replaceCommitmentPolicy(
                    sourceType = CommitmentSourceType.TODO,
                    sourceId = candidate.id,
                    input = CommitmentPolicyInput(
                        enabled = true,
                        localDeadlineMinute = null,
                        graceMinutes = relation.policy.graceMinutes,
                        maxLockMinutes = relation.policy.maxLockMinutes,
                        zoneId = relation.policy.zoneId,
                        blockedPackages = relation.blockedApps.mapTo(linkedSetOf()) { it.packageName }
                    ),
                    nowEpochMillis = completedAtEpochMillis
                )
                CommitmentOccurrenceFactory.forTodo(
                    requireNotNull(clonedPolicy),
                    candidate,
                    completedAtEpochMillis
                )?.let { occurrence -> upsertCommitmentOccurrence(occurrence) }
            }
        }
        TodoCompletionResult(updated, todoDao.getById(candidate.id))
    }

    fun observeAllSubtasks(): Flow<List<TodoSubtaskEntity>> = todoDao.observeAllSubtasks()

    fun observeSubtasks(todoId: String): Flow<List<TodoSubtaskEntity>> =
        todoDao.observeSubtasks(todoId)

    suspend fun saveSubtask(
        todoId: String,
        parentSubtaskId: String?,
        title: String,
        sortOrder: Int,
        id: String = UUID.randomUUID().toString(),
        nowEpochMillis: Long = nowEpochMillis()
    ): TodoSubtaskEntity = database.withTransaction {
        requireNotNull(todoDao.getById(todoId)) { "待办不存在" }
        val normalizedTitle = normalizeRequiredText(title, MAX_SUBTASK_TITLE_LENGTH, "子任务标题")
        val parent = parentSubtaskId?.let { requireNotNull(todoDao.getSubtaskById(it)) { "父子任务不存在" } }
        require(parent == null || parent.todoId == todoId) { "父子任务不属于同一待办" }
        require(parentSubtaskId != id) { "子任务不能以自身为父节点" }
        TodoSubtaskEntity(
            id = id,
            todoId = todoId,
            parentSubtaskId = parentSubtaskId,
            title = normalizedTitle,
            isCompleted = false,
            completedAtEpochMillis = null,
            sortOrder = sortOrder.coerceAtLeast(0),
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis
        ).also { todoDao.upsertSubtask(it) }
    }

    suspend fun renameSubtask(
        id: String,
        title: String,
        nowEpochMillis: Long = nowEpochMillis()
    ): Boolean = todoDao.renameSubtask(
        id,
        normalizeRequiredText(title, MAX_SUBTASK_TITLE_LENGTH, "子任务标题"),
        nowEpochMillis
    ) > 0

    suspend fun toggleSubtaskCompletion(
        id: String,
        isCompleted: Boolean,
        nowEpochMillis: Long = nowEpochMillis()
    ): Boolean = todoDao.updateSubtaskCompletion(
        id = id,
        isCompleted = isCompleted,
        completedAt = nowEpochMillis.takeIf { isCompleted },
        updatedAt = nowEpochMillis
    ) > 0

    suspend fun deleteSubtask(id: String): Int = database.withTransaction {
        val root = todoDao.getSubtaskById(id) ?: return@withTransaction 0
        val descendantIds = TodoSubtaskTreeBuilder.descendantIds(
            todoDao.getSubtasks(root.todoId),
            root.id
        )
        descendantIds.sumOf { todoDao.deleteSubtask(it) }
    }

    fun observeActiveHabits(): Flow<List<HabitItemEntity>> = habitDao.observeActiveHabits()

    fun observeAllHabits(): Flow<List<HabitItemEntity>> = habitDao.observeAllHabits()

    suspend fun getHabitById(id: String): HabitItemEntity? = habitDao.getById(id)

    suspend fun saveHabit(habit: HabitItemEntity): HabitItemEntity {
        val normalized = normalizeHabit(habit, nowEpochMillis(), clock.zone)
        habitDao.upsertHabit(normalized)
        reminderScheduler?.scheduleHabitReminders(normalized)
        return normalized
    }

    suspend fun saveHabitWithCommitment(
        habit: HabitItemEntity,
        commitment: CommitmentPolicyInput
    ): HabitItemEntity = database.withTransaction {
        require(!commitment.enabled || commitment.localDeadlineMinute != null) {
            "启用防拖延监督前必须设置习惯截止时间"
        }
        val now = nowEpochMillis()
        val normalized = normalizeHabit(habit, now, clock.zone)
        habitDao.upsertHabit(normalized)
        replaceCommitmentPolicy(
            sourceType = CommitmentSourceType.HABIT,
            sourceId = normalized.id,
            input = commitment,
            nowEpochMillis = now
        )?.let { policy ->
            val today = Instant.ofEpochMilli(now).atZone(ZoneId.of(policy.zoneId)).toLocalDate()
            val record = habitDao.getRecordByDate(normalized.id, today.toString())
            CommitmentOccurrenceFactory.forHabit(policy, normalized, today, record, now)?.let {
                upsertCommitmentOccurrence(it)
            }
        }
        reminderScheduler?.scheduleHabitReminders(normalized)
        normalized
    }

    suspend fun updateHabit(habit: HabitItemEntity): HabitItemEntity = saveHabit(habit)

    suspend fun setHabitArchived(
        id: String,
        isArchived: Boolean,
        nowEpochMillis: Long = nowEpochMillis()
    ): Boolean = database.withTransaction {
        val success = habitDao.setArchived(id, isArchived, nowEpochMillis) > 0
        if (success) {
            habitDao.getById(id)?.let { habit ->
                if (isArchived) {
                    reminderScheduler?.cancelAllHabitAlarms(habit)
                } else {
                    reminderScheduler?.scheduleHabitReminders(habit)
                }
            }
        }
        success
    }

    fun observeHabitRecords(habitId: String): Flow<List<HabitRecordEntity>> =
        habitDao.observeRecords(habitId)

    fun observeHabitRecords(
        startDate: String,
        endDate: String
    ): Flow<List<HabitRecordEntity>> {
        val start = requireDate(startDate)
        val end = requireDate(endDate)
        require(!end.isBefore(start)) { "习惯记录日期范围无效" }
        return habitDao.observeRecordsInRange(start.toString(), end.toString())
    }

    fun observeAllHabitRecords(): Flow<List<HabitRecordEntity>> = habitDao.observeAllRecords()

    fun observeHabitRecordsOnDate(dateStr: String): Flow<List<HabitRecordEntity>> =
        habitDao.observeRecordsOnDate(requireDate(dateStr).toString())

    suspend fun getHabitRecordsOnDate(dateStr: String): List<HabitRecordEntity> =
        habitDao.getRecordsOnDate(requireDate(dateStr).toString())

    suspend fun checkInHabit(
        habitId: String,
        dateStr: String,
        note: String? = null,
        isBackfill: Boolean = false,
        nowEpochMillis: Long = nowEpochMillis()
    ): HabitCheckInResult? = database.withTransaction {
        val habit = habitDao.getById(habitId) ?: return@withTransaction null
        val date = requireDate(dateStr)
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(clock.zone).toLocalDate()
        require(!date.isAfter(today)) { "不能为未来日期打卡" }
        require(HabitScheduleCalculator.isScheduled(habit, date)) { "该日期不在习惯计划中" }
        val target = habit.targetCountPerDay.coerceAtLeast(1)
        val existing = habitDao.getRecordByDate(habitId, date.toString())
        val previousCount = existing?.completionCount ?: 0
        val newCount = (previousCount + 1).coerceAtMost(target)
        val targetReachedNow = previousCount < target && newCount >= target
        val normalizedNote = normalizeOptionalText(note, MAX_HABIT_NOTE_LENGTH)
        val record = (existing ?: HabitRecordEntity(
            id = UUID.randomUUID().toString(),
            habitId = habitId,
            completedDate = date.toString(),
            note = normalizedNote,
            rewardPoints = 0,
            createdAtEpochMillis = nowEpochMillis,
            completionCount = 0,
            isBackfill = isBackfill || date.isBefore(today),
            updatedAtEpochMillis = nowEpochMillis
        )).copy(
            note = normalizedNote ?: existing?.note,
            rewardPoints = if (newCount >= target) ProductivityRewardPolicy.HABIT_TARGET_POINTS else 0,
            completionCount = newCount,
            isBackfill = existing?.isBackfill == true || isBackfill || date.isBefore(today),
            updatedAtEpochMillis = nowEpochMillis
        )
        habitDao.upsertRecord(record)
        if (targetReachedNow) {
            recordReward(
                sourceKey = "habit:target:$habitId:$date",
                rewardType = ProductivityRewardType.HABIT_TARGET_REACHED,
                subjectId = habitId,
                points = ProductivityRewardPolicy.HABIT_TARGET_POINTS,
                occurredAtEpochMillis = nowEpochMillis
            )
        }
        val unlockedTiers = recalculateHabitProgressAndAchievements(habit, today, nowEpochMillis)
        if (targetReachedNow) {
            commitmentDao.getPolicyBySource(
                CommitmentSourceType.HABIT.storedValue,
                habitId
            )?.let { policy ->
                commitmentDao.getOccurrence(policy.id, date.toString())?.let { occurrence ->
                    commitmentDao.satisfyOccurrence(occurrence.id, nowEpochMillis)
                }
            }
        }
        val completed = newCount >= target
        reminderScheduler?.scheduleHabitReminders(habit, hasCompletedToday = completed)
        HabitCheckInResult(record, targetReachedNow, completed, unlockedTiers)
    }

    suspend fun decrementHabitCheckIn(
        habitId: String,
        dateStr: String,
        nowEpochMillis: Long = nowEpochMillis()
    ): HabitRecordEntity? = database.withTransaction {
        val habit = habitDao.getById(habitId) ?: return@withTransaction null
        val date = requireDate(dateStr)
        val existing = habitDao.getRecordByDate(habitId, date.toString())
            ?: return@withTransaction null
        val target = habit.targetCountPerDay.coerceAtLeast(1)
        val wasTargetReached = existing.completionCount >= target
        val updated = if (existing.completionCount <= 1) {
            habitDao.deleteRecord(habitId, date.toString())
            null
        } else {
            existing.copy(
                completionCount = existing.completionCount - 1,
                rewardPoints = if (existing.completionCount - 1 >= habit.targetCountPerDay) {
                    ProductivityRewardPolicy.HABIT_TARGET_POINTS
                } else {
                    0
                },
                updatedAtEpochMillis = nowEpochMillis
            ).also { habitDao.upsertRecord(it) }
        }
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(clock.zone).toLocalDate()
        recalculateHabitProgressAndAchievements(habit, today, nowEpochMillis)
        val isTargetReached = (updated?.completionCount ?: 0) >= target
        if (wasTargetReached && !isTargetReached) {
            commitmentDao.getPolicyBySource(
                CommitmentSourceType.HABIT.storedValue,
                habitId
            )?.let { policy ->
                CommitmentOccurrenceFactory.forHabit(
                    policy,
                    habit,
                    date,
                    updated,
                    nowEpochMillis
                )?.let { candidate ->
                    commitmentDao.reopenOccurrence(policy.id, candidate.occurrenceKey, nowEpochMillis)
                    upsertCommitmentOccurrence(candidate)
                }
            }
        }
        if (!isTargetReached) {
            reminderScheduler?.scheduleHabitReminders(habit, hasCompletedToday = false)
        }
        updated
    }

    suspend fun undoCheckInHabit(habitId: String, dateStr: String) {
        decrementHabitCheckIn(habitId, dateStr)
    }

    suspend fun saveHabitRecordNote(
        habitId: String,
        dateStr: String,
        note: String?,
        nowEpochMillis: Long = nowEpochMillis()
    ): Boolean = habitDao.updateRecordNote(
        habitId = habitId,
        completedDate = requireDate(dateStr).toString(),
        note = normalizeOptionalText(note, MAX_HABIT_NOTE_LENGTH),
        updatedAt = nowEpochMillis
    ) > 0

    fun observeHabitBadges(habitId: String): Flow<List<AchievementUnlockEntity>> =
        eventDao.observeHabitAchievements(habitId)

    fun observeAllAchievements(): Flow<List<AchievementUnlockEntity>> =
        eventDao.observeAchievements()

    fun observeAllAnniversaries(): Flow<List<AnniversaryItemEntity>> = anniversaryDao.observeAll()

    fun observeEnabledAnniversaries(): Flow<List<AnniversaryItemEntity>> =
        anniversaryDao.observeEnabled()

    suspend fun getAnniversaryById(id: String): AnniversaryItemEntity? = anniversaryDao.getById(id)

    suspend fun saveAnniversary(anniversary: AnniversaryItemEntity): AnniversaryItemEntity {
        val normalized = normalizeAnniversary(anniversary, nowEpochMillis())
        anniversaryDao.upsertAnniversary(normalized)
        reminderScheduler?.scheduleAnniversaryReminders(normalized)
        return normalized
    }

    suspend fun deleteAnniversary(id: String): Boolean = deleteAnniversaryForUndo(id) != null

    suspend fun deleteAnniversaryForUndo(id: String): AnniversaryDeletionSnapshot? =
        database.withTransaction {
            val anniversary = anniversaryDao.getById(id) ?: return@withTransaction null
            reminderScheduler?.cancelAnniversaryReminders(
                anniversary.id,
                anniversary.remindersJson.toItemReminders().map { it.minutesBefore }
            )
            check(anniversaryDao.delete(id) == 1) { "时刻删除失败" }
            AnniversaryDeletionSnapshot(anniversary)
        }

    suspend fun restoreDeletedAnniversary(snapshot: AnniversaryDeletionSnapshot): Boolean {
        val restored = restoreWithoutOverwrite {
            ensureInserted(anniversaryDao.insertAnniversaryIfAbsent(snapshot.anniversary))
        }
        if (restored) reminderScheduler?.scheduleAnniversaryReminders(snapshot.anniversary)
        return restored
    }

    suspend fun setAnniversaryPinned(id: String, isPinned: Boolean): Boolean =
        anniversaryDao.setPinned(id, isPinned, nowEpochMillis()) > 0

    suspend fun setAnniversaryWidgetEnabled(id: String, enabled: Boolean): Boolean =
        anniversaryDao.setWidgetEnabled(id, enabled, nowEpochMillis()) > 0

    suspend fun setAnniversaryLockScreenEnabled(id: String, enabled: Boolean): Boolean =
        anniversaryDao.setLockScreenEnabled(id, enabled, nowEpochMillis()) > 0

    fun observeAnniversaryOccurrences(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<AnniversaryOccurrenceEntity>> {
        requireValidRange(startEpochMillis, endExclusiveEpochMillis)
        return anniversaryDao.observeOccurrencesInRange(startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun recordAnniversaryOccurrence(
        anniversaryId: String,
        occurrence: AnniversaryOccurrence,
        recordedAtEpochMillis: Long = nowEpochMillis()
    ): Boolean = database.withTransaction {
        val anniversary = anniversaryDao.getById(anniversaryId) ?: return@withTransaction false
        val entity = AnniversaryOccurrenceEntity(
            id = deterministicId("anniversary-occurrence:$anniversaryId:${occurrence.key}"),
            anniversaryId = anniversaryId,
            occurrenceKey = occurrence.key,
            occursAtEpochMillis = occurrence.occursAtEpochMillis,
            recordedAtEpochMillis = recordedAtEpochMillis
        )
        if (anniversaryDao.insertOccurrenceIfAbsent(entity) == -1L) return@withTransaction false
        val celebrationSource = "anniversary:$anniversaryId:${occurrence.key}"
        eventDao.insertCelebrationIfAbsent(
            CelebrationEventEntity(
                id = deterministicId("celebration:$celebrationSource"),
                sourceKey = celebrationSource,
                celebrationType = CelebrationType.ANNIVERSARY_REACHED.storedValue,
                subjectId = anniversaryId,
                title = anniversary.title,
                message = "${anniversary.title} 已到达，和小芽一起庆祝吧",
                occurredAtEpochMillis = occurrence.occursAtEpochMillis,
                consumedAtEpochMillis = null
            )
        )
        eventDao.insertAchievementIfAbsent(
            AchievementUnlockEntity(
                id = "anniversary:$anniversaryId:reached",
                achievementType = AchievementType.ANNIVERSARY_REACHED.storedValue,
                subjectId = anniversaryId,
                tier = 1,
                title = "重要时刻达成",
                description = "如期到达重要时刻：${anniversary.title}",
                unlockedAtEpochMillis = occurrence.occursAtEpochMillis
            )
        )
        true
    }

    suspend fun reconcileAnniversaryOccurrences(
        startInclusiveEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        recordedAtEpochMillis: Long = nowEpochMillis()
    ): Int {
        requireValidRange(startInclusiveEpochMillis, endExclusiveEpochMillis)
        val start = Instant.ofEpochMilli(startInclusiveEpochMillis)
        val end = Instant.ofEpochMilli(endExclusiveEpochMillis)
        var inserted = 0
        anniversaryDao.getAll().forEach { anniversary ->
            AnniversaryOccurrenceCalculator.occurrencesBetween(anniversary, start, end)
                .forEach { occurrence ->
                    if (recordAnniversaryOccurrence(anniversary.id, occurrence, recordedAtEpochMillis)) {
                        inserted++
                    }
                }
        }
        return inserted
    }

    suspend fun reconcileDueAnniversaryOccurrences(
        nowEpochMillis: Long = nowEpochMillis()
    ): Int {
        val now = Instant.ofEpochMilli(nowEpochMillis)
        var inserted = 0
        anniversaryDao.getAll().forEach { anniversary ->
            AnniversaryCatchUpCalculator.dueOccurrences(anniversary, now)
                .forEach { occurrence ->
                    if (recordAnniversaryOccurrence(anniversary.id, occurrence, nowEpochMillis)) {
                        inserted++
                    }
                }
        }
        return inserted
    }

    fun observeQuickNoteInbox(): Flow<List<QuickNoteEntity>> = quickNoteDao.observeInboxNotes()

    fun observeAllQuickNotes(): Flow<List<QuickNoteEntity>> = quickNoteDao.observeAll()

    suspend fun addQuickNote(
        content: String,
        mediaUri: String? = null,
        mediaMimeType: String? = null,
        mediaDisplayName: String? = null,
        mediaSizeBytes: Long? = null,
        captureSource: QuickNoteCaptureSource = QuickNoteCaptureSource.TEXT,
        nowEpochMillis: Long = nowEpochMillis()
    ): QuickNoteEntity {
        val note = QuickNoteEntity(
            id = UUID.randomUUID().toString(),
            content = content,
            mediaUri = mediaUri,
            status = QuickNoteStatus.RAW.storedValue,
            createdAtEpochMillis = nowEpochMillis,
            mediaMimeType = mediaMimeType,
            mediaDisplayName = mediaDisplayName,
            mediaSizeBytes = mediaSizeBytes,
            captureSource = captureSource.storedValue,
            updatedAtEpochMillis = nowEpochMillis
        )
        saveQuickNote(note)
        return note
    }

    suspend fun saveQuickNote(note: QuickNoteEntity): QuickNoteEntity = database.withTransaction {
        val normalized = normalizeQuickNote(note, nowEpochMillis())
        val isNew = quickNoteDao.getById(normalized.id) == null
        quickNoteDao.upsertNote(normalized)
        if (isNew && normalized.status == QuickNoteStatus.RAW.storedValue) {
            val localDate = Instant.ofEpochMilli(normalized.createdAtEpochMillis)
                .atZone(clock.zone)
                .toLocalDate()
                .toString()
            val rewardedCount = eventDao.countRewardEvents(
                ProductivityRewardType.QUICK_NOTE_CAPTURED.storedValue,
                localDate
            )
            if (rewardedCount < ProductivityRewardPolicy.MAX_REWARDED_QUICK_NOTES_PER_DAY) {
                recordReward(
                    sourceKey = "quick-note:capture:${normalized.id}",
                    rewardType = ProductivityRewardType.QUICK_NOTE_CAPTURED,
                    subjectId = normalized.id,
                    points = ProductivityRewardPolicy.QUICK_NOTE_POINTS,
                    occurredAtEpochMillis = normalized.createdAtEpochMillis
                )
            }
        }
        normalized
    }

    suspend fun updateQuickNote(note: QuickNoteEntity): QuickNoteEntity? = database.withTransaction {
        val existing = quickNoteDao.getById(note.id) ?: return@withTransaction null
        val contentChanged = existing.content.trim() != note.content.trim()
        val normalized = normalizeQuickNote(
            if (contentChanged) {
                note.copy(aiAdvice = null, aiAssistantFingerprint = null)
            } else {
                note
            },
            nowEpochMillis()
        )
        normalized.takeIf { quickNoteDao.updateNote(it) == 1 }
    }

    suspend fun deleteQuickNote(id: String): String? = database.withTransaction {
        val mediaUri = quickNoteDao.getById(id)?.mediaUri
        quickNoteDao.deleteConversions(id)
        quickNoteDao.delete(id)
        mediaUri?.takeIf { quickNoteDao.countNotesUsingMediaUri(it) == 0 }
    }

    suspend fun deleteQuickNoteForUndo(id: String): QuickNoteDeletionSnapshot? =
        database.withTransaction {
            val note = quickNoteDao.getById(id) ?: return@withTransaction null
            val conversions = quickNoteDao.getConversions(id)
            quickNoteDao.deleteConversions(id)
            check(quickNoteDao.delete(id) == 1) { "闪念删除失败" }
            QuickNoteDeletionSnapshot(
                note = note,
                conversions = conversions,
                mediaUriToRelease = note.mediaUri?.takeIf {
                    quickNoteDao.countNotesUsingMediaUri(it) == 0
                }
            )
        }

    suspend fun restoreDeletedQuickNote(snapshot: QuickNoteDeletionSnapshot): Boolean =
        restoreWithoutOverwrite {
            if (snapshot.conversions.any { it.noteId != snapshot.note.id }) {
                throw RestoreConflictException()
            }
            ensureInserted(quickNoteDao.insertNoteIfAbsent(snapshot.note))
            ensureInserted(quickNoteDao.insertConversionsIfAbsent(snapshot.conversions))
        }

    suspend fun countQuickNotesUsingMediaUri(mediaUri: String): Int =
        quickNoteDao.countNotesUsingMediaUri(mediaUri)

    suspend fun convertNoteToTodo(
        noteId: String,
        todoTitle: String,
        priority: Int,
        dueDate: Long?,
        scheduledStartEpochMillis: Long? = null,
        scheduledEndEpochMillis: Long? = null,
        estimatedFocusMinutes: Int? = null,
        recurrenceType: String = TodoRecurrenceType.NONE.storedValue,
        recurrenceDaysMask: Int = 0,
        reminderMinutesBefore: List<Int> = emptyList()
    ): QuickNoteConversionResult = database.withTransaction {
        existingConversionTarget(noteId, QuickNoteConversionTarget.TODO)?.let {
            return@withTransaction QuickNoteConversionResult(it, wasCreated = false)
        }
        val sourceNote = requireNotNull(quickNoteDao.getById(noteId)) { "闪念不存在" }
        val now = nowEpochMillis()
        val targetId = deterministicId("quick-note:$noteId:todo")
        require(reminderMinutesBefore.all { it in 0..MAX_ITEM_REMINDER_MINUTES }) {
            "待办提醒提前时间无效"
        }
        require(
            reminderMinutesBefore.isEmpty() ||
                scheduledStartEpochMillis != null || dueDate != null
        ) { "设置待办提醒前必须指定开始或截止时间" }
        val todo = normalizeTodo(
            TodoItemEntity(
                id = targetId,
                title = todoTitle,
                description = "来自闪念转化",
                dueDateEpochMillis = dueDate,
                priority = priority.coerceIn(0, 3),
                isCompleted = false,
                completedAtEpochMillis = null,
                category = "闪念转化",
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = now,
                scheduledStartEpochMillis = scheduledStartEpochMillis,
                scheduledEndEpochMillis = scheduledEndEpochMillis,
                estimatedFocusMinutes = estimatedFocusMinutes,
                recurrenceType = recurrenceType,
                recurrenceDaysMask = recurrenceDaysMask,
                remindersJson = reminderMinutesBefore.distinct().sorted()
                    .map { minutes -> ItemReminder(minutes) }
                    .toJsonString()
            ),
            now
        )
        val wasCreated = todoDao.insertTodoIfAbsent(todo) != -1L
        insertQuickNoteConversion(noteId, QuickNoteConversionTarget.TODO, targetId, now)
        updateQuickNoteStatusUnlessArchived(
            sourceNote = sourceNote,
            status = QuickNoteStatus.CONVERTED_TO_TODO,
            updatedAt = now
        )
        QuickNoteConversionResult(targetId, wasCreated)
    }

    suspend fun convertNoteToHabit(
        noteId: String,
        habitName: String,
        colorHex: String,
        iconRes: String,
        frequencyType: String = HabitFrequencyType.DAILY.storedValue,
        weekdaysMask: Int = ALL_WEEKDAYS_MASK
    ): QuickNoteConversionResult = database.withTransaction {
        existingConversionTarget(noteId, QuickNoteConversionTarget.HABIT)?.let {
            return@withTransaction QuickNoteConversionResult(it, wasCreated = false)
        }
        val sourceNote = requireNotNull(quickNoteDao.getById(noteId)) { "闪念不存在" }
        val now = nowEpochMillis()
        val targetId = deterministicId("quick-note:$noteId:habit")
        val existingTarget = habitDao.getById(targetId)
        val wasCreated = existingTarget == null
        if (existingTarget == null) {
            val habit = normalizeHabit(
                HabitItemEntity(
                    id = targetId,
                    name = habitName,
                    iconRes = iconRes,
                    colorHex = colorHex,
                    frequencyType = frequencyType,
                    targetCountPerDay = 1,
                    currentStreak = 0,
                    bestStreak = 0,
                    isArchived = false,
                    createdAtEpochMillis = now,
                    weekdaysMask = weekdaysMask
                ),
                now,
                clock.zone
            )
            habitDao.upsertHabit(habit)
        } else if (existingTarget.isArchived) {
            habitDao.setArchived(existingTarget.id, false, now)
        }
        insertQuickNoteConversion(noteId, QuickNoteConversionTarget.HABIT, targetId, now)
        updateQuickNoteStatusUnlessArchived(
            sourceNote = sourceNote,
            status = QuickNoteStatus.CONVERTED_TO_HABIT,
            updatedAt = now
        )
        QuickNoteConversionResult(targetId, wasCreated)
    }

    suspend fun convertNoteToFocus(
        noteId: String,
        focusRequestId: String = deterministicId("quick-note:$noteId:focus")
    ): QuickNoteConversionResult = database.withTransaction {
        existingConversionTarget(noteId, QuickNoteConversionTarget.FOCUS)?.let {
            return@withTransaction QuickNoteConversionResult(it, wasCreated = false)
        }
        val sourceNote = requireNotNull(quickNoteDao.getById(noteId)) { "闪念不存在" }
        val now = nowEpochMillis()
        val targetId = normalizeRequiredText(focusRequestId, MAX_ID_LENGTH, "专注请求编号")
        insertQuickNoteConversion(noteId, QuickNoteConversionTarget.FOCUS, targetId, now)
        updateQuickNoteStatusUnlessArchived(
            sourceNote = sourceNote,
            status = QuickNoteStatus.CONVERTED_TO_FOCUS,
            updatedAt = now
        )
        QuickNoteConversionResult(targetId, wasCreated = true)
    }

    /**
     * AI 助理确认的唯一写入口。分析阶段只构造 [QuickNoteAssistantAnalysis]；用户确认后，
     * 这里才在一个 Room 事务中写入业务目标、审计记录、建议和正文指纹。
     *
     * 专注承载待办和一次性计划也在同一个 Room 事务中完成；任何冲突或存储失败都会
     * 回滚本次确认，保留确认界面供用户调整或重试。
     */
    suspend fun confirmQuickNoteAssistant(
        noteId: String,
        analysis: QuickNoteAssistantAnalysis
    ): QuickNoteAssistantSaveResult {
        val commit = database.withTransaction {
            require(QuickNoteAssistantFingerprint.isValid(analysis.fingerprint)) {
                "AI 助理正文指纹无效"
            }
            require(analysis.actionableCount > 0 || analysis.advice.isNotEmpty()) {
                "没有可确认的内容"
            }
            require(analysis.actionableCount <= MAX_ASSISTANT_ACTIONS) {
                "AI 助理生成的目标数量超出上限"
            }
            val sourceNote = requireNotNull(quickNoteDao.getById(noteId)) { "闪记不存在" }
            require(
                QuickNoteAssistantFingerprint.fromContent(sourceNote.content) == analysis.fingerprint
            ) { "闪记正文已变化，请重新分析" }
            val batchId = assistantBatchId(noteId, analysis.fingerprint)
            val expectedResult = assistantSaveResult(
                analysis = analysis,
                batchId = batchId,
                wasCreated = false
            )
            if (sourceNote.aiAssistantFingerprint == analysis.fingerprint) {
                return@withTransaction QuickNoteAssistantCommit(
                    result = expectedResult,
                    todosToSchedule = emptyList(),
                    habitsToSchedule = emptyList(),
                    anniversariesToSchedule = emptyList()
                )
            }

            val now = nowEpochMillis()
            val normalizedAdvice = analysis.advice.map { advice ->
                normalizeRequiredText(advice, MAX_ASSISTANT_ADVICE_ITEM_LENGTH, "AI 建议")
            }.distinct()
            require(analysis.warnings.size <= MAX_LEDGER_WARNING_COUNT) { "AI 警告数量超出上限" }

            val ledgerEntryIds = if (analysis.ledgerEntries.isEmpty()) {
                emptyList()
            } else {
                persistLedgerRouting(
                    batchId = batchId,
                    sourceNoteId = sourceNote.id,
                    ledgerEntries = analysis.ledgerEntries,
                    todoItems = emptyList()
                ).ledgerEntryIds
            }

            val todos = analysis.todoItems.mapIndexed { index, draft ->
                assistantTodoEntity(batchId, index, draft, now)
            }
            todos.forEach { todo ->
                check(todoDao.insertTodoIfAbsent(todo) != -1L) { "AI 待办写入冲突" }
            }

            val habits = analysis.habits.mapIndexed { index, draft ->
                assistantHabitEntity(batchId, index, draft, now)
            }
            habits.forEach { habit ->
                check(habitDao.getById(habit.id) == null) { "AI 习惯写入冲突" }
                habitDao.upsertHabit(habit)
            }

            val focusTodos = analysis.focusSessions.mapIndexed { index, draft ->
                assistantFocusTodoEntity(batchId, index, draft, now)
            }
            focusTodos.forEach { todo ->
                check(todoDao.insertTodoIfAbsent(todo) != -1L) { "AI 专注写入冲突" }
            }
            focusTodos.forEach { todo ->
                val startAt = requireNotNull(todo.scheduledStartEpochMillis) {
                    "AI 专注开始时间缺失"
                }
                val endAt = requireNotNull(todo.scheduledEndEpochMillis) {
                    "AI 专注结束时间缺失"
                }
                requireAssistantFocusScheduled(
                    supervisionPlanRepository.scheduleOneTimeFocusForTodo(
                        todoId = todo.id,
                        startEpochMillis = startAt,
                        endEpochMillis = endAt,
                        nowEpochMillis = now,
                        expectedTodoUpdatedAtEpochMillis = null
                    )
                )
            }

            val anniversaries = analysis.anniversaries.mapIndexed { index, draft ->
                assistantAnniversaryEntity(batchId, index, draft, now)
            }
            anniversaries.forEach { anniversary ->
                check(anniversaryDao.insertAnniversaryIfAbsent(anniversary) != -1L) {
                    "AI 时刻写入冲突"
                }
            }

            val targetType = "$ASSISTANT_CONVERSION_PREFIX${analysis.fingerprint}"
            check(
                quickNoteDao.insertConversionIfAbsent(
                    QuickNoteConversionEntity(
                        noteId = sourceNote.id,
                        targetType = targetType,
                        targetId = batchId,
                        createdAtEpochMillis = now
                    )
                ) != -1L
            ) { "AI 助理确认审计写入冲突" }
            val adviceText = normalizedAdvice.joinToString("\n").takeIf(String::isNotEmpty)
            val updatedNote = normalizeQuickNote(
                sourceNote.copy(
                    aiAdvice = adviceText,
                    aiAssistantFingerprint = analysis.fingerprint,
                    updatedAtEpochMillis = now
                ),
                now
            )
            check(quickNoteDao.updateNote(updatedNote) == 1) { "闪记 AI 建议保存失败" }

            QuickNoteAssistantCommit(
                result = assistantSaveResult(
                    analysis = analysis,
                    batchId = batchId,
                    ledgerEntryIds = ledgerEntryIds,
                    wasCreated = true
                ),
                todosToSchedule = todos,
                habitsToSchedule = habits,
                anniversariesToSchedule = anniversaries
            )
        }
        commit.todosToSchedule.forEach { reminderScheduler?.scheduleTodoReminders(it) }
        commit.habitsToSchedule.forEach { reminderScheduler?.scheduleHabitReminders(it) }
        commit.anniversariesToSchedule.forEach {
            reminderScheduler?.scheduleAnniversaryReminders(it)
        }
        return commit.result
    }

    fun observePendingRewardEvents(): Flow<List<ProductivityRewardEventEntity>> =
        eventDao.observePendingRewardEvents()

    suspend fun getPendingRewardEvents(limit: Int = 100): List<ProductivityRewardEventEntity> =
        eventDao.getPendingRewardEvents(limit.coerceIn(1, 1_000))

    suspend fun markRewardEventProcessed(
        id: String,
        processedAtEpochMillis: Long = nowEpochMillis()
    ): Boolean = eventDao.markRewardEventProcessed(id, processedAtEpochMillis) > 0

    fun observePendingCelebrations(): Flow<List<CelebrationEventEntity>> =
        eventDao.observePendingCelebrations()

    suspend fun consumeCelebration(
        id: String,
        consumedAtEpochMillis: Long = nowEpochMillis()
    ): Boolean = eventDao.consumeCelebration(id, consumedAtEpochMillis) > 0

    fun observeCommitmentRuntime(): Flow<CommitmentRuntimeSnapshot> = combine(
        commitmentDao.observeEnabledPoliciesWithApps(),
        commitmentDao.observeOpenOccurrences()
    ) { policies, occurrences -> CommitmentRuntimeSnapshot(policies, occurrences) }

    suspend fun getCommitmentRuntimeSnapshot(): CommitmentRuntimeSnapshot =
        CommitmentRuntimeSnapshot(
            policies = commitmentDao.getEnabledPoliciesWithApps(),
            openOccurrences = commitmentDao.getOpenOccurrences()
        )

    fun observeAllCommitmentPolicies(): Flow<List<CommitmentPolicyWithApps>> =
        commitmentDao.observeAllPoliciesWithApps()

    suspend fun getCommitmentPolicy(
        sourceType: CommitmentSourceType,
        sourceId: String
    ): CommitmentPolicyWithApps? = commitmentDao.getPolicyWithAppsBySource(
        sourceType.storedValue,
        sourceId
    )

    suspend fun saveCommitmentPolicy(
        policy: CommitmentPolicyEntity,
        blockedPackages: Collection<String>
    ): CommitmentPolicyEntity = database.withTransaction {
        val normalizedPackages = blockedPackages.map(String::trim).filter(String::isNotEmpty).distinct()
        require(!policy.enabled || normalizedPackages.isNotEmpty()) {
            "启用防拖延策略前请至少选择一个 App"
        }
        CommitmentPolicyValidator.requireValid(policy, normalizedPackages)
        commitmentDao.upsertPolicy(policy)
        commitmentDao.deleteBlockedApps(policy.id)
        if (normalizedPackages.isNotEmpty()) {
            commitmentDao.insertBlockedApps(normalizedPackages.map { packageName ->
                CommitmentBlockedAppEntity(policy.id, packageName)
            })
        }
        policy
    }

    suspend fun deleteCommitmentPolicy(id: String): Boolean = database.withTransaction {
        commitmentDao.satisfyOpenOccurrencesForPolicy(id, nowEpochMillis())
        commitmentDao.deleteBlockedApps(id)
        commitmentDao.deletePolicy(id) > 0
    }

    suspend fun reconcileCommitmentOccurrences(
        nowEpochMillis: Long = nowEpochMillis()
    ): Int = database.withTransaction {
        require(nowEpochMillis >= 0L) { "当前墙钟无效" }
        val todayByZone = mutableMapOf<ZoneId, LocalDate>()
        val habitFutureScheduleByPolicy = mutableMapOf<String, Pair<LocalDate, String?>>()
        var changed = 0
        val enabledRelations = commitmentDao.getEnabledPoliciesWithApps()
        enabledRelations.forEach { relation ->
            val policy = relation.policy
            when (CommitmentSourceType.entries.firstOrNull { it.storedValue == policy.sourceType }) {
                CommitmentSourceType.TODO -> {
                    val todo = todoDao.getById(policy.sourceId) ?: return@forEach
                    val candidate = CommitmentOccurrenceFactory.forTodo(policy, todo, nowEpochMillis)
                        ?: return@forEach
                    if (upsertCommitmentOccurrence(candidate)) changed++
                }
                CommitmentSourceType.HABIT -> {
                    val habit = habitDao.getById(policy.sourceId) ?: return@forEach
                    val zone = ZoneId.of(policy.zoneId)
                    val today = todayByZone.getOrPut(zone) {
                        Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
                    }
                    val policyStart = Instant.ofEpochMilli(policy.createdAtEpochMillis)
                        .atZone(zone)
                        .toLocalDate()
                    val habitStart = parseDate(habit.startDate) ?: policyStart
                    val firstDate = maxOf(policyStart, habitStart)
                    val lookbackDays = ChronoUnit.DAYS.between(firstDate, today)
                        .coerceIn(0L, MAX_COMMITMENT_RECONCILIATION_DAYS)
                    for (offset in lookbackDays downTo 0L) {
                        val date = today.minusDays(offset)
                        val record = habitDao.getRecordByDate(habit.id, date.toString())
                        val candidate = CommitmentOccurrenceFactory.forHabit(
                            policy,
                            habit,
                            date,
                            record,
                            nowEpochMillis
                        ) ?: continue
                        if (upsertCommitmentOccurrence(candidate)) changed++
                    }
                    val nextDate = HabitScheduleCalculator.nextScheduledDate(habit, today)
                    habitFutureScheduleByPolicy[policy.id] = today to nextDate?.toString()
                    if (nextDate != null) {
                        val record = habitDao.getRecordByDate(habit.id, nextDate.toString())
                        CommitmentOccurrenceFactory.forHabit(
                            policy,
                            habit,
                            nextDate,
                            record,
                            nowEpochMillis
                        )?.let { candidate ->
                            if (upsertCommitmentOccurrence(candidate)) changed++
                        }
                    }
                }
                null -> Unit
            }
        }

        var openOccurrences = commitmentDao.getOpenOccurrences()
        openOccurrences.forEach { occurrence ->
            val (today, expectedFutureKey) = habitFutureScheduleByPolicy[occurrence.policyId]
                ?: return@forEach
            val occurrenceDate = parseDate(occurrence.occurrenceKey) ?: return@forEach
            val isStaleFuture = occurrence.activatedAtEpochMillis == null &&
                occurrenceDate.isAfter(today) &&
                occurrence.occurrenceKey != expectedFutureKey
            if (isStaleFuture && commitmentDao.deleteOccurrence(occurrence.id) > 0) changed++
        }

        if (changed > 0) openOccurrences = commitmentDao.getOpenOccurrences()
        val policiesById = enabledRelations.associateBy { relation -> relation.policy.id }
        openOccurrences.forEach { occurrence ->
            if (occurrence.activatedAtEpochMillis != null) return@forEach
            val policy = policiesById[occurrence.policyId]?.policy ?: return@forEach
            if (
                CommitmentActivationPolicy.isActive(policy, occurrence, nowEpochMillis) &&
                commitmentDao.markOccurrenceActivated(occurrence.id, nowEpochMillis) > 0
            ) {
                changed++
            }
        }
        changed
    }

    suspend fun markCommitmentOccurrenceActivated(
        occurrenceId: String,
        activatedAtEpochMillis: Long = nowEpochMillis()
    ): Boolean = commitmentDao.markOccurrenceActivated(occurrenceId, activatedAtEpochMillis) > 0

    suspend fun satisfyCommitmentOccurrence(
        occurrenceId: String,
        satisfiedAtEpochMillis: Long = nowEpochMillis()
    ): Boolean = commitmentDao.satisfyOccurrence(occurrenceId, satisfiedAtEpochMillis) > 0

    private suspend fun replaceCommitmentPolicy(
        sourceType: CommitmentSourceType,
        sourceId: String,
        input: CommitmentPolicyInput,
        nowEpochMillis: Long
    ): CommitmentPolicyEntity? {
        val existing = commitmentDao.getPolicyBySource(sourceType.storedValue, sourceId)
        if (!input.enabled) {
            existing?.let { policy ->
                commitmentDao.satisfyOpenOccurrencesForPolicy(policy.id, nowEpochMillis)
                commitmentDao.deleteBlockedApps(policy.id)
                commitmentDao.deletePolicy(policy.id)
            }
            return null
        }
        val normalizedPackages = input.blockedPackages
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val policy = CommitmentPolicyEntity(
            id = existing?.id ?: deterministicId("commitment:${sourceType.storedValue}:$sourceId"),
            sourceType = sourceType.storedValue,
            sourceId = sourceId,
            enabled = true,
            localDeadlineMinute = input.localDeadlineMinute,
            graceMinutes = input.graceMinutes,
            maxLockMinutes = input.maxLockMinutes,
            zoneId = input.zoneId,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis
        )
        CommitmentPolicyValidator.requireValid(policy, normalizedPackages)
        commitmentDao.upsertPolicy(policy)
        commitmentDao.deleteBlockedApps(policy.id)
        commitmentDao.insertBlockedApps(normalizedPackages.map { packageName ->
            CommitmentBlockedAppEntity(policy.id, packageName)
        })
        return policy
    }

    private suspend fun upsertCommitmentOccurrence(
        candidate: CommitmentOccurrenceEntity
    ): Boolean {
        val existing = commitmentDao.getOccurrence(candidate.policyId, candidate.occurrenceKey)
        if (existing == null) return commitmentDao.insertOccurrenceIfAbsent(candidate) != -1L
        val merged = candidate.copy(
            id = existing.id,
            activatedAtEpochMillis = existing.activatedAtEpochMillis,
            satisfiedAtEpochMillis = candidate.satisfiedAtEpochMillis ?: existing.satisfiedAtEpochMillis,
            createdAtEpochMillis = existing.createdAtEpochMillis,
            updatedAtEpochMillis = existing.updatedAtEpochMillis
        )
        if (merged == existing) return false
        commitmentDao.upsertOccurrence(
            merged.copy(updatedAtEpochMillis = candidate.updatedAtEpochMillis)
        )
        return true
    }

    private suspend fun recalculateHabitProgressAndAchievements(
        originalHabit: HabitItemEntity,
        today: LocalDate,
        nowEpochMillis: Long
    ): Set<Int> {
        val records = habitDao.getRecordsList(originalHabit.id)
        val start = parseDate(originalHabit.startDate)
            ?: records.mapNotNull { parseDate(it.completedDate) }.minOrNull()
            ?: today
        val stats = HabitProgressCalculator.calculate(
            habit = originalHabit,
            records = records,
            rangeStart = minOf(start, today),
            rangeEnd = today,
            today = today
        )
        val best = maxOf(originalHabit.bestStreak, stats.bestStreak)
        habitDao.updateStreak(originalHabit.id, stats.currentStreak, best, nowEpochMillis)
        val unlocked = linkedSetOf<Int>()
        ProductivityRewardPolicy.HABIT_STREAK_BADGE_TIERS.sorted().forEach { tier ->
            if (best < tier) return@forEach
            val achievementId = "habit:${originalHabit.id}:streak:$tier"
            val inserted = eventDao.insertAchievementIfAbsent(
                AchievementUnlockEntity(
                    id = achievementId,
                    achievementType = AchievementType.HABIT_STREAK.storedValue,
                    subjectId = originalHabit.id,
                    tier = tier,
                    title = "连续坚持 $tier 次",
                    description = "${originalHabit.name} 已连续完成 $tier 个计划周期",
                    unlockedAtEpochMillis = nowEpochMillis
                )
            ) != -1L
            if (inserted) {
                unlocked += tier
                eventDao.insertCelebrationIfAbsent(
                    CelebrationEventEntity(
                        id = deterministicId("celebration:$achievementId"),
                        sourceKey = achievementId,
                        celebrationType = CelebrationType.HABIT_STREAK_BADGE.storedValue,
                        subjectId = originalHabit.id,
                        title = "新徽章已解锁",
                        message = "${originalHabit.name} 连续坚持 $tier 次",
                        occurredAtEpochMillis = nowEpochMillis,
                        consumedAtEpochMillis = null
                    )
                )
            }
        }
        return unlocked
    }

    private fun assistantSaveResult(
        analysis: QuickNoteAssistantAnalysis,
        batchId: String,
        wasCreated: Boolean,
        ledgerEntryIds: List<String> = analysis.ledgerEntries.indices.map { index ->
            deterministicId("ledger:$batchId:entry:$index")
        }
    ): QuickNoteAssistantSaveResult = QuickNoteAssistantSaveResult(
        wasCreated = wasCreated,
        ledgerEntryIds = ledgerEntryIds,
        todoIds = analysis.todoItems.indices.map { index ->
            assistantTargetId(batchId, "todo", index)
        },
        habitIds = analysis.habits.indices.map { index ->
            assistantTargetId(batchId, "habit", index)
        },
        anniversaryIds = analysis.anniversaries.indices.map { index ->
            assistantTargetId(batchId, "anniversary", index)
        },
        focusSchedules = analysis.focusSessions.mapIndexed { index, draft ->
            QuickNoteAssistantFocusSchedule(
                todoId = assistantTargetId(batchId, "focus", index),
                title = draft.title,
                startAtEpochMillis = draft.startAtEpochMillis,
                endAtEpochMillis = addMinutesExact(
                    draft.startAtEpochMillis,
                    draft.durationMinutes,
                    "专注时间范围无效"
                )
            )
        }
    )

    private fun requireAssistantFocusScheduled(result: OneTimeFocusScheduleResult) {
        val failureMessage = when (result) {
            is OneTimeFocusScheduleResult.Success -> return
            is OneTimeFocusScheduleResult.Conflicts ->
                "专注时间与 ${result.conflicts.size} 个计划冲突，请调整后重新分析"
            is OneTimeFocusScheduleResult.InvalidInput -> result.reason
            is OneTimeFocusScheduleResult.StaleTodo -> "专注关联的待办已变化，请重试"
            is OneTimeFocusScheduleResult.TodoNotFound -> "专注关联的待办不存在，请重试"
            is OneTimeFocusScheduleResult.CorruptData -> "监督计划数据异常，请先修复"
            OneTimeFocusScheduleResult.StorageFailure -> "专注计划保存失败，请稍后重试"
        }
        throw IllegalStateException(failureMessage)
    }

    private fun assistantTodoEntity(
        batchId: String,
        index: Int,
        draft: QuickNoteAssistantTodoDraft,
        nowEpochMillis: Long
    ): TodoItemEntity {
        require(draft.dueAtEpochMillis == null || draft.dueAtEpochMillis >= 0L) {
            "待办截止时间无效"
        }
        require(draft.scheduledStartEpochMillis == null || draft.scheduledStartEpochMillis >= 0L) {
            "待办开始时间无效"
        }
        require(draft.durationMinutes == null || draft.durationMinutes in 1..1_440) {
            "待办时长无效"
        }
        validateAssistantReminders(
            draft.reminderMinutesBefore,
            hasReferenceTime = draft.scheduledStartEpochMillis != null || draft.dueAtEpochMillis != null
        )
        val scheduledEnd = draft.scheduledStartEpochMillis?.let { start ->
            draft.durationMinutes?.let { minutes ->
                addMinutesExact(start, minutes, "待办时间范围无效")
            }
        }
        return normalizeTodo(
            TodoItemEntity(
                id = assistantTargetId(batchId, "todo", index),
                title = draft.title,
                description = "由闪记 AI 助理确认创建",
                dueDateEpochMillis = draft.dueAtEpochMillis,
                priority = draft.priority,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = ASSISTANT_CATEGORY,
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = nowEpochMillis,
                scheduledStartEpochMillis = draft.scheduledStartEpochMillis,
                scheduledEndEpochMillis = scheduledEnd,
                estimatedFocusMinutes = draft.durationMinutes,
                recurrenceType = draft.recurrenceType.storedValue,
                recurrenceDaysMask = draft.recurrenceDaysMask,
                remindersJson = draft.reminderMinutesBefore.distinct().sorted()
                    .map { minutes -> ItemReminder(minutes) }
                    .toJsonString()
            ),
            nowEpochMillis
        )
    }

    private fun assistantHabitEntity(
        batchId: String,
        index: Int,
        draft: QuickNoteAssistantHabitDraft,
        nowEpochMillis: Long
    ): HabitItemEntity {
        require(draft.reminderMinutesOfDay.all { it in 0 until MINUTES_PER_DAY }) {
            "习惯提醒时间无效"
        }
        val reminderConfig = HabitReminderConfig(
            timedReminders = draft.reminderMinutesOfDay.distinct().sorted().map { minutes ->
                HabitTimeReminder(hour = minutes / 60, minute = minutes % 60)
            }
        )
        return normalizeHabit(
            HabitItemEntity(
                id = assistantTargetId(batchId, "habit", index),
                name = draft.name,
                iconRes = DEFAULT_ASSISTANT_HABIT_ICON,
                colorHex = DEFAULT_ASSISTANT_HABIT_COLOR,
                frequencyType = draft.frequencyType.storedValue,
                targetCountPerDay = 1,
                currentStreak = 0,
                bestStreak = 0,
                isArchived = false,
                createdAtEpochMillis = nowEpochMillis,
                weekdaysMask = draft.weekdaysMask,
                remindersJson = reminderConfig.toJsonString()
            ),
            nowEpochMillis,
            clock.zone
        )
    }

    private fun assistantFocusTodoEntity(
        batchId: String,
        index: Int,
        draft: QuickNoteAssistantFocusDraft,
        nowEpochMillis: Long
    ): TodoItemEntity {
        require(draft.startAtEpochMillis >= 0L) { "专注开始时间无效" }
        require(draft.durationMinutes in 1..1_440) { "专注时长无效" }
        val endAt = addMinutesExact(
            draft.startAtEpochMillis,
            draft.durationMinutes,
            "专注时间范围无效"
        )
        require(endAt > nowEpochMillis) { "专注时间段已经结束，请重新分析" }
        return normalizeTodo(
            TodoItemEntity(
                id = assistantTargetId(batchId, "focus", index),
                title = draft.title,
                description = "由闪记 AI 助理确认创建的一次性专注",
                dueDateEpochMillis = endAt,
                priority = 1,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = ASSISTANT_FOCUS_CATEGORY,
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = nowEpochMillis,
                scheduledStartEpochMillis = draft.startAtEpochMillis,
                scheduledEndEpochMillis = endAt,
                estimatedFocusMinutes = draft.durationMinutes,
                remindersJson = "[]"
            ),
            nowEpochMillis
        )
    }

    private fun assistantAnniversaryEntity(
        batchId: String,
        index: Int,
        draft: QuickNoteAssistantAnniversaryDraft,
        nowEpochMillis: Long
    ): AnniversaryItemEntity {
        require(draft.targetAtEpochMillis >= 0L) { "时刻时间无效" }
        validateAssistantReminders(draft.reminderMinutesBefore, hasReferenceTime = true)
        val normalizedDraft = draft.normalizedForCurrentTime(nowEpochMillis)
        val sourceZoneId = runCatching { ZoneId.of(normalizedDraft.zoneId) }
            .getOrElse { clock.zone }
        val sourceDateTime = Instant.ofEpochMilli(normalizedDraft.targetAtEpochMillis)
            .atZone(sourceZoneId)
        return normalizeAnniversary(
            AnniversaryItemEntity(
                id = assistantTargetId(batchId, "anniversary", index),
                title = normalizedDraft.title,
                targetDateEpochMillis = normalizedDraft.targetAtEpochMillis,
                isLunar = false,
                type = normalizedDraft.type.storedValue,
                repeatRule = normalizedDraft.repeatRule.storedValue,
                isPinnedTop = false,
                showOnWidget = false,
                createdAtEpochMillis = nowEpochMillis,
                sourceYear = sourceDateTime.year,
                sourceMonth = sourceDateTime.monthValue,
                sourceDay = sourceDateTime.dayOfMonth,
                sourceHour = sourceDateTime.hour,
                sourceMinute = sourceDateTime.minute,
                sourceSecond = sourceDateTime.second,
                zoneId = sourceZoneId.id,
                showOnLockScreen = false,
                remindersJson = normalizedDraft.reminderMinutesBefore.distinct().sorted()
                    .map { minutes -> ItemReminder(minutes) }
                    .toJsonString()
            ),
            nowEpochMillis
        )
    }

    private fun validateAssistantReminders(
        reminders: List<Int>,
        hasReferenceTime: Boolean
    ) {
        require(reminders.all { it in 0..MAX_ITEM_REMINDER_MINUTES }) { "提醒提前时间无效" }
        require(reminders.isEmpty() || hasReferenceTime) { "设置提醒前必须指定时间" }
    }

    private suspend fun recordReward(
        sourceKey: String,
        rewardType: ProductivityRewardType,
        subjectId: String,
        points: Int,
        occurredAtEpochMillis: Long
    ) {
        val localDate = Instant.ofEpochMilli(occurredAtEpochMillis)
            .atZone(clock.zone)
            .toLocalDate()
            .toString()
        eventDao.insertRewardEventIfAbsent(
            ProductivityRewardEventEntity(
                id = deterministicId("reward:$sourceKey"),
                sourceKey = sourceKey,
                rewardType = rewardType.storedValue,
                subjectId = subjectId,
                balancePoints = points,
                experiencePoints = points,
                localDate = localDate,
                createdAtEpochMillis = occurredAtEpochMillis,
                processedAtEpochMillis = null
            )
        )
    }

    private suspend fun recurringSuccessor(todo: TodoItemEntity): TodoItemEntity? {
        val seriesId = todo.recurrenceSeriesId ?: return null
        return todoDao.getById(
            deterministicId("todo-recurrence:$seriesId:${todo.recurrenceSequence + 1}")
        )
    }

    private suspend fun persistLedgerRouting(
        batchId: String,
        sourceNoteId: String?,
        ledgerEntries: List<LedgerEntryDraft>,
        todoItems: List<LedgerTodoDraft>,
        returnExistingWhenTargetsMissing: Boolean = false
    ): LedgerRoutingSaveResult {
        require(ledgerEntries.isNotEmpty() || todoItems.isNotEmpty()) { "没有可保存的账目或待办" }
        require(ledgerEntries.size <= MAX_LEDGER_ROUTING_ITEMS) { "账目数量超出协议上限" }
        require(todoItems.size <= MAX_LEDGER_ROUTING_ITEMS) { "待办数量超出协议上限" }
        val now = nowEpochMillis()
        val normalizedSourceNoteId = sourceNoteId?.let {
            normalizeRequiredText(it, MAX_ID_LENGTH, "闪念编号")
        }
        val normalizedEntries = ledgerEntries.mapIndexed { index, draft ->
            validateLedgerDraft(draft)
            val warningsJson = LedgerWarningCodec.encode(draft.warnings)
            require(warningsJson == null || warningsJson.length <= MAX_LEDGER_WARNINGS_JSON_LENGTH) {
                "账目警告过长"
            }
            LedgerEntryEntity(
                id = deterministicId("ledger:$batchId:entry:$index"),
                amount = draft.amountFen,
                direction = draft.direction.storedValue,
                category = draft.category.storedValue,
                title = normalizeRequiredText(draft.title, MAX_LEDGER_TITLE_LENGTH, "账目标题"),
                note = normalizeOptionalText(draft.note, MAX_LEDGER_NOTE_LENGTH),
                occurredAtEpochMillis = draft.occurredAtEpochMillis,
                sourceNoteId = normalizedSourceNoteId,
                isEstimated = draft.isEstimated,
                emotion = draft.emotion,
                necessity = draft.necessity,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
                aiConfidence = draft.aiConfidence,
                sourceType = LedgerEntrySourceType.AI.storedValue,
                aiWarningsJson = warningsJson,
                sourceBatchId = batchId
            )
        }
        val normalizedTodos = todoItems.mapIndexed { index, draft ->
            validateLedgerTodoDraft(draft)
            normalizeTodo(
                TodoItemEntity(
                    id = deterministicId("ledger:$batchId:todo:$index"),
                    title = draft.content,
                    description = "由 AI 三位一体路由生成",
                    dueDateEpochMillis = draft.dueDateEpochMillis,
                    priority = 1,
                    isCompleted = false,
                    completedAtEpochMillis = null,
                    category = "财务联动",
                    repeatRule = null,
                    associatedFocusPlanId = null,
                    supervisionLockEnabled = false,
                    createdAtEpochMillis = now,
                    scheduledStartEpochMillis = null,
                    scheduledEndEpochMillis = null,
                    estimatedFocusMinutes = null,
                    isImportant = false,
                    urgencyMode = TodoUrgencyMode.AUTO.storedValue,
                    recurrenceType = TodoRecurrenceType.NONE.storedValue,
                    recurrenceInterval = 1,
                    recurrenceDaysMask = 0,
                    recurrenceDayOfMonth = null,
                    recurrenceSeriesId = null,
                    recurrenceSequence = 0,
                    updatedAtEpochMillis = now
                ),
                now
            )
        }

        val existingEntriesForBatch = ledgerDao.getLedgerEntriesBySourceBatchId(batchId)
        val existingEntriesForExpectedIds = if (normalizedEntries.isEmpty()) {
            emptyList()
        } else {
            ledgerDao.getLedgerEntriesByIds(normalizedEntries.map(LedgerEntryEntity::id))
        }
        val possibleTodoIds = (0 until MAX_LEDGER_ROUTING_ITEMS).map { index ->
            deterministicId("ledger:$batchId:todo:$index")
        }
        val existingTodosForBatch = todoDao.getByIds(possibleTodoIds)
        val existingEntryIds = existingEntriesForExpectedIds.mapTo(hashSetOf(), LedgerEntryEntity::id)
        val existingTodoIds = existingTodosForBatch.mapTo(hashSetOf(), TodoItemEntity::id)
        val expectedTargetMissing = normalizedEntries.any { it.id !in existingEntryIds } ||
            normalizedTodos.any { it.id !in existingTodoIds }
        if (returnExistingWhenTargetsMissing && expectedTargetMissing) {
            return ledgerRoutingSaveResult(batchId, normalizedEntries, normalizedTodos, wasCreated = false)
        }
        if (
            existingEntriesForBatch.isNotEmpty() ||
            existingEntriesForExpectedIds.isNotEmpty() ||
            existingTodosForBatch.isNotEmpty()
        ) {
            requireLedgerRoutingPayloadMatches(
                expectedEntries = normalizedEntries,
                expectedTodos = normalizedTodos,
                existingEntriesForBatch = existingEntriesForBatch,
                existingEntriesForExpectedIds = existingEntriesForExpectedIds,
                existingTodosForBatch = existingTodosForBatch
            )
            return ledgerRoutingSaveResult(batchId, normalizedEntries, normalizedTodos, wasCreated = false)
        }

        if (returnExistingWhenTargetsMissing) {
            return ledgerRoutingSaveResult(batchId, normalizedEntries, normalizedTodos, wasCreated = false)
        }

        val ledgerInsertResults = if (normalizedEntries.isEmpty()) {
            emptyList()
        } else {
            ledgerDao.insertLedgerEntriesIfAbsent(normalizedEntries)
        }
        val todoInsertResults = normalizedTodos.map { todo ->
            todoDao.insertTodoIfAbsent(todo)
        }
        check(ledgerInsertResults.all { it != -1L } && todoInsertResults.all { it != -1L }) {
            "账本批次写入冲突"
        }
        return ledgerRoutingSaveResult(
            batchId,
            normalizedEntries,
            normalizedTodos,
            wasCreated = ledgerInsertResults.any { it != -1L } ||
                todoInsertResults.any { it != -1L }
        )
    }

    private fun ledgerRoutingSaveResult(
        batchId: String,
        entries: List<LedgerEntryEntity>,
        todos: List<TodoItemEntity>,
        wasCreated: Boolean
    ): LedgerRoutingSaveResult = LedgerRoutingSaveResult(
        batchId = batchId,
        ledgerEntryIds = entries.map(LedgerEntryEntity::id),
        todoIds = todos.map(TodoItemEntity::id),
        wasCreated = wasCreated
    )

    /**
     * 批次 ID 是写入幂等键的一部分。不能只依赖 INSERT IGNORE：同一 ID 的新载荷会被
     * 静默丢弃，调用方却以为已经完成。时间戳是写入元数据，重试时允许不同，其余字段必须一致。
     */
    private fun requireLedgerRoutingPayloadMatches(
        expectedEntries: List<LedgerEntryEntity>,
        expectedTodos: List<TodoItemEntity>,
        existingEntriesForBatch: List<LedgerEntryEntity>,
        existingEntriesForExpectedIds: List<LedgerEntryEntity>,
        existingTodosForBatch: List<TodoItemEntity>
    ) {
        require(existingEntriesForBatch.size == expectedEntries.size) {
            "账本批次载荷冲突：账目数量不一致"
        }
        require(existingEntriesForExpectedIds.size == expectedEntries.size) {
            "账本批次载荷冲突：账目编号已被其他记录占用"
        }
        require(existingTodosForBatch.size == expectedTodos.size) {
            "账本批次载荷冲突：待办数量不一致"
        }

        val entriesById = existingEntriesForExpectedIds.associateBy(LedgerEntryEntity::id)
        expectedEntries.forEach { expected ->
            val existing = entriesById[expected.id]
            require(existing != null && existing.matchesLedgerRoutingPayload(expected)) {
                "账本批次载荷冲突：账目内容不一致"
            }
        }
        val todosById = existingTodosForBatch.associateBy(TodoItemEntity::id)
        expectedTodos.forEach { expected ->
            val existing = todosById[expected.id]
            require(existing != null && existing.matchesLedgerRoutingPayload(expected)) {
                "账本批次载荷冲突：待办内容不一致"
            }
        }
    }

    private fun LedgerEntryEntity.matchesLedgerRoutingPayload(expected: LedgerEntryEntity): Boolean =
        this == expected.copy(
            createdAtEpochMillis = this.createdAtEpochMillis,
            updatedAtEpochMillis = this.updatedAtEpochMillis
        )

    private fun TodoItemEntity.matchesLedgerRoutingPayload(expected: TodoItemEntity): Boolean =
        this == expected.copy(
            createdAtEpochMillis = this.createdAtEpochMillis,
            updatedAtEpochMillis = this.updatedAtEpochMillis
        )

    private fun validateLedgerDraft(draft: LedgerEntryDraft) {
        require(LedgerAmountCodec.isValidFen(draft.amountFen)) { "账目金额无效" }
        require(draft.category.direction == draft.direction) { "账目分类与收支方向不一致" }
        normalizeRequiredText(draft.title, MAX_LEDGER_TITLE_LENGTH, "账目标题")
        require(draft.occurredAtEpochMillis >= 0L) { "账目发生时间无效" }
        require(draft.aiConfidence.isFinite() && draft.aiConfidence in 0f..1f) {
            "AI 置信度无效"
        }
        require(draft.emotion in VALID_LEDGER_EMOTIONS) {
            "消费情绪枚举无效"
        }
        require(draft.necessity in VALID_LEDGER_NECESSITIES) {
            "必要性枚举无效"
        }
        require(draft.warnings.size <= MAX_LEDGER_WARNING_COUNT) { "账目警告过多" }
        draft.warnings.forEach { warning ->
            require(warning.trim().isNotEmpty() && warning.length <= MAX_LEDGER_WARNING_LENGTH) {
                "账目警告无效"
            }
        }
        require(!draft.isEstimated || draft.warnings.any { it.trim().isNotEmpty() }) {
            "估算金额必须附带警告"
        }
    }

    private fun validateLedgerTodoDraft(draft: LedgerTodoDraft) {
        normalizeRequiredText(draft.content, MAX_TODO_TITLE_LENGTH, "财务待办内容")
        require(draft.dueDateEpochMillis == null || draft.dueDateEpochMillis >= 0L) {
            "待办截止时间无效"
        }
    }

    private fun validateLedgerEntry(entry: LedgerEntryEntity) {
        require(entry.id.isNotBlank() && entry.id.length <= MAX_ID_LENGTH) { "账目编号无效" }
        require(LedgerAmountCodec.isValidFen(entry.amount)) { "账目金额无效" }
        val direction = LedgerDirection.fromStoredValue(entry.direction)
            ?: error("账目收支方向无效")
        val category = LedgerCategory.fromStoredValue(entry.category)
            ?: error("账目分类无效")
        require(category.direction == direction) { "账目分类与收支方向不一致" }
        normalizeRequiredText(entry.title, MAX_LEDGER_TITLE_LENGTH, "账目标题")
        require(entry.occurredAtEpochMillis >= 0L) { "账目发生时间无效" }
        require(entry.createdAtEpochMillis >= 0L && entry.updatedAtEpochMillis >= 0L) {
            "账目时间戳无效"
        }
        normalizeOptionalText(entry.note, MAX_LEDGER_NOTE_LENGTH)
        require(entry.aiConfidence.isFinite() && entry.aiConfidence in 0f..1f) {
            "AI 置信度无效"
        }
        require(LedgerEntrySourceType.fromStoredValue(entry.sourceType) != null) {
            "账目来源无效"
        }
        entry.emotion?.let { require(it in VALID_LEDGER_EMOTIONS) { "消费情绪枚举无效" } }
        entry.necessity?.let { require(it in VALID_LEDGER_NECESSITIES) { "必要性枚举无效" } }
        val decodedWarnings = entry.aiWarningsJson?.let { encoded ->
            require(encoded.length <= MAX_LEDGER_WARNINGS_JSON_LENGTH) { "账目警告过长" }
            requireNotNull(LedgerWarningCodec.decode(encoded)) { "账目警告格式无效" }
        }
        require(!entry.isEstimated || !decodedWarnings.isNullOrEmpty()) {
            "估算金额必须附带警告"
        }
        entry.sourceNoteId?.let { normalizeRequiredText(it, MAX_ID_LENGTH, "闪念编号") }
        entry.sourceBatchId?.let { normalizeRequiredText(it, MAX_ID_LENGTH, "账本批次编号") }
    }

    private suspend fun updateQuickNoteStatusUnlessArchived(
        sourceNote: QuickNoteEntity,
        status: QuickNoteStatus,
        updatedAt: Long
    ) {
        if (sourceNote.status == QuickNoteStatus.ARCHIVED.storedValue) return
        quickNoteDao.updateStatus(sourceNote.id, status.storedValue, updatedAt)
    }

    private suspend fun existingConversionTarget(
        noteId: String,
        target: QuickNoteConversionTarget
    ): String? {
        val conversion = quickNoteDao.getConversion(noteId, target.storedValue) ?: return null
        val targetStillExists = when (target) {
            QuickNoteConversionTarget.TODO -> todoDao.getById(conversion.targetId) != null
            QuickNoteConversionTarget.HABIT -> habitDao.getById(conversion.targetId)?.let { habit ->
                if (habit.isArchived) {
                    habitDao.setArchived(habit.id, false, nowEpochMillis())
                }
                true
            } == true
            QuickNoteConversionTarget.FOCUS -> true
            // 转账记录是审计事实。即使用户后来删除某笔流水，也不能再次把同一闪念记账。
            QuickNoteConversionTarget.LEDGER -> true
        }
        if (targetStillExists) return conversion.targetId
        quickNoteDao.deleteConversion(noteId, target.storedValue)
        return null
    }

    private suspend fun insertQuickNoteConversion(
        noteId: String,
        target: QuickNoteConversionTarget,
        targetId: String,
        nowEpochMillis: Long
    ): Boolean {
        val inserted = quickNoteDao.insertConversionIfAbsent(
            QuickNoteConversionEntity(noteId, target.storedValue, targetId, nowEpochMillis)
        )
        check(inserted != -1L || quickNoteDao.getConversion(noteId, target.storedValue)?.targetId == targetId) {
            "闪念转化目标冲突"
        }
        return inserted != -1L
    }

    private suspend fun restoreWithoutOverwrite(block: suspend () -> Unit): Boolean = try {
        database.withTransaction { block() }
        true
    } catch (_: RestoreConflictException) {
        false
    }

    private fun ensureInserted(rowId: Long) {
        if (rowId == -1L) throw RestoreConflictException()
    }

    private fun ensureInserted(rowIds: List<Long>) {
        if (rowIds.any { it == -1L }) throw RestoreConflictException()
    }

    private suspend fun restoreOpenOccurrence(
        snapshot: TodoDeletionSnapshot,
        occurrence: CommitmentOccurrenceEntity
    ) {
        val restored = commitmentDao.restoreOpenOccurrenceIfUnchanged(
            id = occurrence.id,
            policyId = occurrence.policyId,
            occurrenceKey = occurrence.occurrenceKey,
            deadlineAt = occurrence.deadlineEpochMillis,
            expiresAt = occurrence.expiresAtEpochMillis,
            activatedAt = occurrence.activatedAtEpochMillis,
            createdAt = occurrence.createdAtEpochMillis,
            originalUpdatedAt = occurrence.updatedAtEpochMillis,
            deletedAt = snapshot.deletedAtEpochMillis
        )
        if (restored == 0) {
            ensureInserted(commitmentDao.insertOccurrenceIfAbsent(occurrence))
        }
    }

    private fun validateTodoDeletionSnapshot(snapshot: TodoDeletionSnapshot) {
        val todoId = snapshot.todo.id
        if (
            snapshot.deletedAtEpochMillis < 0L ||
            snapshot.subtasks.any { it.todoId != todoId }
        ) {
            throw RestoreConflictException()
        }

        val relation = snapshot.commitmentPolicy
        if (relation == null) {
            if (snapshot.openCommitmentOccurrences.isNotEmpty()) throw RestoreConflictException()
        } else {
            val policy = relation.policy
            if (
                policy.sourceType != CommitmentSourceType.TODO.storedValue ||
                policy.sourceId != todoId ||
                relation.blockedApps.any { it.policyId != policy.id } ||
                snapshot.openCommitmentOccurrences.any {
                    it.policyId != policy.id || it.satisfiedAtEpochMillis != null
                }
            ) {
                throw RestoreConflictException()
            }
        }

        snapshot.oneTimeFocusPlan?.let { plan ->
            val planId = plan.plan.planId
            if (
                snapshot.todo.associatedFocusPlanId != planId ||
                !plan.isOneTimeFocusRecord() ||
                plan.globalPolicy?.planId?.let { it != planId } == true ||
                plan.appPolicy?.planId?.let { it != planId } == true ||
                plan.ranges.any { it.planId != planId } ||
                plan.activationReservation?.planId?.let { it != planId } == true
            ) {
                throw RestoreConflictException()
            }
        }
    }

    /**
     * 待办编辑可能保留旧计划编号，却修改或清空了时间窗。此时旧的一次性计划必须和
     * 待办写入处于同一事务中删除，避免后台仍按旧时间锁定设备。
     */
    private suspend fun reconcileOneTimeFocusAssociation(
        existing: TodoItemEntity?,
        candidate: TodoItemEntity,
        nowEpochMillis: Long
    ): TodoItemEntity {
        val associatedPlanIds = linkedSetOf<String>()
        existing?.associatedFocusPlanId?.let(associatedPlanIds::add)
        candidate.associatedFocusPlanId?.let(associatedPlanIds::add)
        var reconciled = candidate

        associatedPlanIds.forEach { planId ->
            val stored = supervisionPlanDao.getById(planId)
            if (stored == null) {
                if (reconciled.associatedFocusPlanId == planId) {
                    reconciled = reconciled.copy(
                        associatedFocusPlanId = null,
                        updatedAtEpochMillis = maxOf(reconciled.updatedAtEpochMillis, nowEpochMillis)
                    )
                }
                return@forEach
            }
            if (!stored.isOneTimeFocusRecord()) return@forEach

            val row = stored.plan
            val stillMatches = reconciled.associatedFocusPlanId == planId &&
                reconciled.scheduledStartEpochMillis == row.oneTimeStartEpochMillis &&
                reconciled.scheduledEndEpochMillis == row.oneTimeEndEpochMillis
            if (stillMatches) return@forEach

            deleteOneTimeFocusPlanAndClearAssociations(planId, nowEpochMillis)
            if (reconciled.associatedFocusPlanId == planId) {
                reconciled = reconciled.copy(
                    associatedFocusPlanId = null,
                    updatedAtEpochMillis = maxOf(reconciled.updatedAtEpochMillis, nowEpochMillis)
                )
            }
        }
        return reconciled
    }

    private suspend fun deleteAssociatedOneTimeFocusPlan(
        todo: TodoItemEntity,
        updatedAtEpochMillis: Long
    ) {
        val planId = todo.associatedFocusPlanId ?: return
        val stored = supervisionPlanDao.getById(planId) ?: return
        if (stored.isOneTimeFocusRecord()) {
            deleteOneTimeFocusPlanAndClearAssociations(planId, updatedAtEpochMillis)
        }
    }

    private suspend fun deleteOneTimeFocusPlanAndClearAssociations(
        planId: String,
        updatedAtEpochMillis: Long
    ) {
        supervisionPlanDao.delete(planId)
        supervisionPlanDao.deleteActivationReservation(planId)
        todoDao.clearFocusPlanAssociation(planId, updatedAtEpochMillis.coerceAtLeast(0L))
    }

    private fun SupervisionPlanWithRanges.isOneTimeFocusRecord(): Boolean =
        plan.planType == SupervisionPlanType.FOCUS.name &&
            plan.oneTimeStartEpochMillis != null &&
            plan.oneTimeEndEpochMillis != null

    private class RestoreConflictException : RuntimeException()

    private fun normalizeTodo(todo: TodoItemEntity, nowEpochMillis: Long): TodoItemEntity {
        require(todo.id.isNotBlank() && todo.id.length <= MAX_ID_LENGTH) { "待办编号无效" }
        val title = normalizeRequiredText(todo.title, MAX_TODO_TITLE_LENGTH, "待办标题")
        val description = normalizeOptionalText(todo.description, MAX_TODO_DESCRIPTION_LENGTH)
        val category = normalizeRequiredText(todo.category, MAX_CATEGORY_LENGTH, "待办项目")
        require(todo.priority in 0..3) { "待办优先级无效" }
        require(todo.estimatedFocusMinutes == null || todo.estimatedFocusMinutes in 1..1_440) {
            "预计专注时长无效"
        }
        require(
            todo.scheduledStartEpochMillis == null && todo.scheduledEndEpochMillis == null ||
                todo.scheduledStartEpochMillis != null &&
                todo.scheduledEndEpochMillis != null &&
                todo.scheduledEndEpochMillis > todo.scheduledStartEpochMillis
        ) { "待办计划起止时间必须同时为空，或结束时间晚于开始时间" }
        val recurrenceType = TodoRecurrenceType.fromStoredValue(todo.recurrenceType)
        require(todo.recurrenceInterval in 1..366) { "待办重复间隔无效" }
        require(todo.recurrenceDaysMask and ALL_WEEKDAYS_MASK == todo.recurrenceDaysMask) {
            "待办重复星期掩码无效"
        }
        require(todo.recurrenceDayOfMonth == null || todo.recurrenceDayOfMonth in 1..31) {
            "待办每月重复日期无效"
        }
        if (recurrenceType == TodoRecurrenceType.WEEKLY_DAYS) {
            require(todo.recurrenceDaysMask != 0) { "每周重复至少选择一天" }
        }
        val seriesId = if (recurrenceType == TodoRecurrenceType.NONE) {
            todo.recurrenceSeriesId
        } else {
            todo.recurrenceSeriesId ?: todo.id
        }
        return todo.copy(
            title = title,
            description = description,
            category = category,
            urgencyMode = TodoUrgencyMode.fromStoredValue(todo.urgencyMode).storedValue,
            recurrenceType = recurrenceType.storedValue,
            recurrenceSeriesId = seriesId,
            updatedAtEpochMillis = maxOf(todo.updatedAtEpochMillis, nowEpochMillis)
        )
    }

    /**
     * 版本号既要反映用户在同一毫秒内的连续编辑，也不能因设备时钟回拨而倒退。
     * 内容未变化时保留调用方提供的更高版本，避免无意义地制造版本冲突。
     */
    private fun ensureTodoVersion(
        existing: TodoItemEntity?,
        candidate: TodoItemEntity,
        nowEpochMillis: Long
    ): TodoItemEntity {
        if (existing == null) return candidate
        val candidateWithoutVersion = candidate.copy(
            updatedAtEpochMillis = existing.updatedAtEpochMillis
        )
        val contentChanged = candidateWithoutVersion != existing
        if (!contentChanged) {
            return candidate.copy(
                updatedAtEpochMillis = maxOf(
                    candidate.updatedAtEpochMillis,
                    existing.updatedAtEpochMillis
                )
            )
        }
        val nextVersion = if (existing.updatedAtEpochMillis == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            maxOf(
                candidate.updatedAtEpochMillis,
                nowEpochMillis,
                existing.updatedAtEpochMillis + 1L
            )
        }
        return candidate.copy(updatedAtEpochMillis = nextVersion)
    }

    private fun normalizeHabit(
        habit: HabitItemEntity,
        nowEpochMillis: Long,
        zoneId: ZoneId
    ): HabitItemEntity {
        require(habit.id.isNotBlank() && habit.id.length <= MAX_ID_LENGTH) { "习惯编号无效" }
        val frequency = HabitFrequencyType.fromStoredValue(habit.frequencyType)
        require(habit.targetCountPerDay in 1..100) { "每日目标次数无效" }
        require(habit.weekdaysMask and ALL_WEEKDAYS_MASK == habit.weekdaysMask) { "习惯星期掩码无效" }
        require(habit.weeklyTargetDays in 1..7) { "每周目标天数无效" }
        require(habit.intervalDays in 1..366) { "习惯间隔天数无效" }
        if (frequency == HabitFrequencyType.SPECIFIC_WEEKDAYS) {
            require(habit.weekdaysMask != 0) { "每周习惯至少选择一天" }
        }
        val startDate = parseDate(habit.startDate)?.takeUnless {
            habit.startDate == EPOCH_LOCAL_DATE && habit.createdAtEpochMillis > 0L
        } ?: Instant.ofEpochMilli(habit.createdAtEpochMillis.coerceAtLeast(0L))
            .atZone(zoneId)
            .toLocalDate()
        return habit.copy(
            name = normalizeRequiredText(habit.name, MAX_HABIT_NAME_LENGTH, "习惯名称"),
            iconRes = normalizeRequiredText(habit.iconRes, MAX_ICON_KEY_LENGTH, "习惯图标"),
            colorHex = normalizeColor(habit.colorHex),
            frequencyType = frequency.storedValue,
            currentStreak = habit.currentStreak.coerceAtLeast(0),
            bestStreak = maxOf(habit.bestStreak, habit.currentStreak, 0),
            startDate = startDate.toString(),
            updatedAtEpochMillis = maxOf(habit.updatedAtEpochMillis, nowEpochMillis)
        )
    }

    private fun normalizeAnniversary(
        anniversary: AnniversaryItemEntity,
        nowEpochMillis: Long
    ): AnniversaryItemEntity {
        require(anniversary.id.isNotBlank() && anniversary.id.length <= MAX_ID_LENGTH) {
            "时刻编号无效"
        }
        require(anniversary.targetDateEpochMillis >= 0L) { "目标时刻无效" }
        val zoneId = try {
            ZoneId.of(anniversary.zoneId)
        } catch (_: RuntimeException) {
            clock.zone
        }
        val fallbackDateTime = Instant.ofEpochMilli(anniversary.targetDateEpochMillis).atZone(zoneId)
        val source = if (anniversary.isLunar) {
            ChineseLunarCalendar.solarToLunar(fallbackDateTime.toLocalDate())
        } else {
            null
        }
        val hasValidSource = anniversary.sourceMonth in 1..12 && anniversary.sourceDay in 1..31
        return anniversary.copy(
            title = normalizeRequiredText(anniversary.title, MAX_ANNIVERSARY_TITLE_LENGTH, "时刻标题"),
            type = AnniversaryType.fromStoredValue(anniversary.type).storedValue,
            repeatRule = AnniversaryRepeatRule.fromStoredValue(anniversary.repeatRule).storedValue,
            sourceYear = if (hasValidSource) anniversary.sourceYear else source?.year ?: fallbackDateTime.year,
            sourceMonth = if (hasValidSource) anniversary.sourceMonth else source?.month ?: fallbackDateTime.monthValue,
            sourceDay = if (hasValidSource) anniversary.sourceDay else source?.day ?: fallbackDateTime.dayOfMonth,
            sourceHour = anniversary.sourceHour.takeIf { it in 0..23 } ?: fallbackDateTime.hour,
            sourceMinute = anniversary.sourceMinute.takeIf { it in 0..59 } ?: fallbackDateTime.minute,
            sourceSecond = anniversary.sourceSecond.takeIf { it in 0..59 } ?: fallbackDateTime.second,
            isLunarLeapMonth = if (hasValidSource) anniversary.isLunarLeapMonth else source?.isLeapMonth == true,
            zoneId = zoneId.id,
            updatedAtEpochMillis = maxOf(anniversary.updatedAtEpochMillis, nowEpochMillis)
        )
    }

    private fun normalizeQuickNote(note: QuickNoteEntity, nowEpochMillis: Long): QuickNoteEntity {
        require(note.id.isNotBlank() && note.id.length <= MAX_ID_LENGTH) { "闪念编号无效" }
        val content = note.content.trim().take(MAX_QUICK_NOTE_LENGTH)
        val mediaUri = normalizeOptionalText(note.mediaUri, MAX_MEDIA_URI_LENGTH)
        require(content.isNotEmpty() || mediaUri != null) { "闪念文字和图片不能同时为空" }
        require(note.mediaSizeBytes == null || note.mediaSizeBytes in 0..MAX_MEDIA_BYTES) {
            "闪念图片大小无效"
        }
        val captureSource = normalizeOptionalText(note.captureSource, MAX_CAPTURE_SOURCE_LENGTH)
            ?: QuickNoteCaptureSource.TEXT.storedValue
        val advice = normalizeOptionalText(note.aiAdvice, MAX_AI_ADVICE_LENGTH)
        val fingerprint = note.aiAssistantFingerprint?.trim()?.takeIf(String::isNotEmpty)
        require(fingerprint == null || QuickNoteAssistantFingerprint.isValid(fingerprint)) {
            "闪记 AI 助理正文指纹无效"
        }
        require(advice == null || fingerprint != null) { "AI 建议缺少对应的正文指纹" }
        return note.copy(
            content = content,
            mediaUri = mediaUri,
            mediaMimeType = normalizeOptionalText(note.mediaMimeType, MAX_MIME_LENGTH),
            mediaDisplayName = normalizeOptionalText(note.mediaDisplayName, MAX_MEDIA_NAME_LENGTH),
            captureSource = captureSource,
            aiAdvice = advice,
            aiAssistantFingerprint = fingerprint,
            updatedAtEpochMillis = maxOf(note.updatedAtEpochMillis, nowEpochMillis)
        )
    }

    private fun normalizeColor(value: String): String {
        val normalized = value.trim().uppercase()
        require(COLOR_PATTERN.matches(normalized)) { "习惯颜色无效" }
        return normalized
    }

    private fun requireDate(value: String): LocalDate =
        requireNotNull(parseDate(value)) { "日期格式必须为 yyyy-MM-dd" }

    private fun nowEpochMillis(): Long = clock.millis().coerceAtLeast(0L)

    companion object {
        @Volatile
        private var instance: TodoRepository? = null

        fun getInstance(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(
                    ControlFreeDatabase.getInstance(context.applicationContext),
                    Clock.systemDefaultZone(),
                    context.applicationContext
                ).also { instance = it }
            }

        internal fun createForTest(database: ControlFreeDatabase, clock: Clock): TodoRepository =
            TodoRepository(database, clock, null)

        private val COLOR_PATTERN = Regex("#[0-9A-F]{6}([0-9A-F]{2})?")
        private const val MAX_ID_LENGTH = 200
        private const val MAX_TODO_TITLE_LENGTH = 200
        private const val MAX_TODO_DESCRIPTION_LENGTH = 4_000
        private const val MAX_CATEGORY_LENGTH = 100
        private const val MAX_SUBTASK_TITLE_LENGTH = 200
        private const val MAX_HABIT_NAME_LENGTH = 100
        private const val MAX_HABIT_NOTE_LENGTH = 2_000
        private const val MAX_ICON_KEY_LENGTH = 100
        private const val MAX_ANNIVERSARY_TITLE_LENGTH = 200
        private const val MAX_QUICK_NOTE_LENGTH = 4_000
        private const val MAX_MEDIA_URI_LENGTH = 4_096
        private const val MAX_MEDIA_NAME_LENGTH = 255
        private const val MAX_MIME_LENGTH = 100
        private const val MAX_CAPTURE_SOURCE_LENGTH = 100
        private const val MAX_AI_ADVICE_LENGTH = 4_000
        private const val MAX_ITEM_REMINDER_MINUTES = 525_600
        private const val MAX_ASSISTANT_ACTIONS = 100
        private const val MAX_ASSISTANT_ADVICE_ITEM_LENGTH = 500
        private const val MINUTES_PER_DAY = 1_440
        private const val ASSISTANT_CONVERSION_PREFIX = "ASSISTANT:"
        private const val ASSISTANT_CATEGORY = "AI 助理"
        private const val ASSISTANT_FOCUS_CATEGORY = "AI 助理专注"
        private const val DEFAULT_ASSISTANT_HABIT_ICON = "auto_awesome"
        private const val DEFAULT_ASSISTANT_HABIT_COLOR = "#21C76A"
        private const val MAX_MEDIA_BYTES = 25L * 1024L * 1024L
        private const val MAX_LEDGER_TITLE_LENGTH = 20
        private const val MAX_LEDGER_NOTE_LENGTH = 200
        private const val MAX_LEDGER_WARNING_COUNT = 20
        private const val MAX_LEDGER_WARNING_LENGTH = 100
        private const val MAX_LEDGER_WARNINGS_JSON_LENGTH = 4_096
        private const val MAX_LEDGER_ROUTING_ITEMS = 20
        private val VALID_LEDGER_EMOTIONS = setOf("冲动", "解压", "刚需", "社交", "自我投资")
        private val VALID_LEDGER_NECESSITIES = setOf("need", "want")
        private const val MAX_COMMITMENT_RECONCILIATION_DAYS = 3_650L
    }
}

private fun assistantBatchId(noteId: String, fingerprint: String): String =
    deterministicId("quick-note:$noteId:assistant:$fingerprint")

private fun assistantTargetId(batchId: String, targetType: String, index: Int): String =
    deterministicId("quick-note-assistant:$batchId:$targetType:$index")

private fun addMinutesExact(startEpochMillis: Long, minutes: Int, message: String): Long =
    runCatching {
        Math.addExact(startEpochMillis, Math.multiplyExact(minutes.toLong(), 60_000L))
    }.getOrElse { throw IllegalArgumentException(message) }

private fun deterministicId(value: String): String = UUID.nameUUIDFromBytes(
    value.toByteArray(StandardCharsets.UTF_8)
).toString()

private fun normalizeRequiredText(value: String, maxLength: Int, fieldName: String): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty()) { "$fieldName 不能为空" }
    require(normalized.length <= maxLength) { "$fieldName 过长" }
    return normalized
}

private fun normalizeOptionalText(value: String?, maxLength: Int): String? = value
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.also { require(it.length <= maxLength) { "文本过长" } }

private fun requireValidRange(startEpochMillis: Long, endExclusiveEpochMillis: Long) {
    require(startEpochMillis >= 0L && endExclusiveEpochMillis > startEpochMillis) { "时间范围无效" }
}
