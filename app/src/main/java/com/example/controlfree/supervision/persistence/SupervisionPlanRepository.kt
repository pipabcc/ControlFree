package com.example.controlfree.supervision.persistence

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.controlfree.productivity.schedule.SupervisionBusyIntervalFactory
import com.example.controlfree.supervision.DailyTimeRange
import com.example.controlfree.supervision.FocusCyclePolicy
import com.example.controlfree.supervision.OneTimeFocusWindow
import com.example.controlfree.supervision.ScheduleZoneMode
import com.example.controlfree.supervision.SupervisionPlan
import com.example.controlfree.supervision.SupervisionPlanConflict
import com.example.controlfree.supervision.SupervisionPlanConflictDetector
import com.example.controlfree.supervision.SupervisionPlanType
import com.example.controlfree.supervision.WeeklySchedule
import com.example.controlfree.supervision.isOneTimeFocus
import com.example.controlfree.todo.TodoItemEntity
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

sealed interface PlanLoadResult {
    data class Success(val plans: List<SupervisionPlan>) : PlanLoadResult
    data class CorruptData(val failures: List<PlanMappingResult.Failure>) : PlanLoadResult
    data object StorageFailure : PlanLoadResult
}

data class PlanSaveRequest(
    val plan: SupervisionPlan,
    val expectedUpdatedAtEpochMillis: Long?,
    val reservationMutation: PlanReservationMutation = PlanReservationMutation.Unchanged
)

sealed interface PlanReservationMutation {
    data object Unchanged : PlanReservationMutation
    data object Clear : PlanReservationMutation
    data class Set(val enableAtEpochMillis: Long) : PlanReservationMutation
}

sealed interface PlanWriteResult {
    data class Success(
        val affectedPlanIds: Set<String>,
        val autoDisabledPlanIds: Set<String> = emptySet()
    ) : PlanWriteResult
    data class DuplicateIds(val planIds: Set<String>) : PlanWriteResult
    data class Conflicts(val conflicts: List<SupervisionPlanConflict>) : PlanWriteResult
    data class StaleData(
        val planId: String,
        val expectedUpdatedAtEpochMillis: Long?,
        val actualUpdatedAtEpochMillis: Long?
    ) : PlanWriteResult
    data class NonIncreasingVersion(
        val planId: String,
        val actualUpdatedAtEpochMillis: Long,
        val proposedUpdatedAtEpochMillis: Long
    ) : PlanWriteResult
    data class CorruptData(val failures: List<PlanMappingResult.Failure>) : PlanWriteResult
    data class NotFound(val planId: String) : PlanWriteResult
    data class InvalidInput(val reason: String) : PlanWriteResult
    data object StorageFailure : PlanWriteResult
}

enum class ScheduledEnableFailureReason {
    NOT_FOUND,
    CORRUPT_DATA,
    ALREADY_ENABLED,
    EXPIRED,
    VERSION_EXHAUSTED,
    CONFLICT
}

data class ScheduledEnableFailure(
    val planId: String,
    val planName: String?,
    val reason: ScheduledEnableFailureReason,
    val conflicts: List<SupervisionPlanConflict> = emptyList()
)

sealed interface ScheduledEnableBatchResult {
    data class Completed(
        val activatedPlanIds: Set<String>,
        val autoDisabledPlanIds: Set<String>,
        val failures: List<ScheduledEnableFailure>
    ) : ScheduledEnableBatchResult

    data class InvalidInput(val reason: String) : ScheduledEnableBatchResult
    data object StorageFailure : ScheduledEnableBatchResult
}

sealed interface AppTriggerActivationResult {
    data class Activated(
        val planId: String,
        val autoDisabledPlanIds: Set<String>
    ) : AppTriggerActivationResult
    data object AlreadyEnabled : AppTriggerActivationResult
    data object NoLongerEligible : AppTriggerActivationResult
    data object NotFound : AppTriggerActivationResult
    data class Conflicts(val conflicts: List<SupervisionPlanConflict>) : AppTriggerActivationResult
    data object StorageFailure : AppTriggerActivationResult
}

sealed interface OneTimeFocusScheduleResult {
    data class Success(
        val planId: String,
        val wasCreated: Boolean
    ) : OneTimeFocusScheduleResult

    data class Conflicts(val conflicts: List<SupervisionPlanConflict>) : OneTimeFocusScheduleResult
    data class StaleTodo(
        val todoId: String,
        val expectedUpdatedAtEpochMillis: Long,
        val actualUpdatedAtEpochMillis: Long
    ) : OneTimeFocusScheduleResult
    data class InvalidInput(val reason: String) : OneTimeFocusScheduleResult
    data class TodoNotFound(val todoId: String) : OneTimeFocusScheduleResult
    data class CorruptData(val failures: List<PlanMappingResult.Failure>) : OneTimeFocusScheduleResult
    data object StorageFailure : OneTimeFocusScheduleResult
}

class SupervisionPlanRepository private constructor(
    private val database: ControlFreeDatabase
) {
    private val dao = database.supervisionPlanDao()
    private val todoDao = database.todoDao()

    fun observePlans(): Flow<PlanLoadResult> = dao.observeAll()
        .map(::mapAll)
        .catch { error ->
            if (error is CancellationException) throw error
            emit(PlanLoadResult.StorageFailure)
        }

    suspend fun loadPlans(): PlanLoadResult = try {
        mapAll(dao.getAll())
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        PlanLoadResult.StorageFailure
    }

    suspend fun save(
        plan: SupervisionPlan,
        expectedUpdatedAtEpochMillis: Long?
    ): PlanWriteResult = saveAll(listOf(PlanSaveRequest(plan, expectedUpdatedAtEpochMillis)))

    suspend fun saveWithReservation(
        plan: SupervisionPlan,
        expectedUpdatedAtEpochMillis: Long?,
        scheduledEnableAtEpochMillis: Long?
    ): PlanWriteResult = saveAll(
        listOf(
            PlanSaveRequest(
                plan = plan,
                expectedUpdatedAtEpochMillis = expectedUpdatedAtEpochMillis,
                reservationMutation = scheduledEnableAtEpochMillis?.let(
                    PlanReservationMutation::Set
                ) ?: PlanReservationMutation.Clear
            )
        )
    )

    suspend fun saveAll(requests: List<PlanSaveRequest>): PlanWriteResult {
        if (requests.isEmpty()) return PlanWriteResult.InvalidInput("至少提供一个监督计划")
        val duplicateIds = requests.groupBy { it.plan.id }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) return PlanWriteResult.DuplicateIds(duplicateIds)

        return safelyWrite {
            val storedResult = mapAll(dao.getAll())
            if (storedResult is PlanLoadResult.CorruptData) {
                return@safelyWrite PlanWriteResult.CorruptData(storedResult.failures)
            }
            val storedPlans = (storedResult as PlanLoadResult.Success).plans
            val storedById = storedPlans.associateBy(SupervisionPlan::id)
            requests.forEach { request ->
                val actual = storedById[request.plan.id]?.updatedAtEpochMillis
                if (actual != request.expectedUpdatedAtEpochMillis) {
                    return@safelyWrite PlanWriteResult.StaleData(
                        request.plan.id,
                        request.expectedUpdatedAtEpochMillis,
                        actual
                    )
                }
                if (actual != null && request.plan.updatedAtEpochMillis <= actual) {
                    return@safelyWrite PlanWriteResult.NonIncreasingVersion(
                        request.plan.id,
                        actual,
                        request.plan.updatedAtEpochMillis
                    )
                }
            }
            val transactionNowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
            requests.forEach { request ->
                when (val mutation = request.reservationMutation) {
                    PlanReservationMutation.Unchanged,
                    PlanReservationMutation.Clear -> Unit

                    is PlanReservationMutation.Set -> {
                        if (request.plan.enabled) {
                            return@safelyWrite PlanWriteResult.InvalidInput(
                                "已启用的任务不能同时预约开启"
                            )
                        }
                        if (mutation.enableAtEpochMillis <= transactionNowEpochMillis) {
                            return@safelyWrite PlanWriteResult.InvalidInput(
                                "预约开启时间必须晚于当前时间"
                            )
                        }
                        if (
                            request.plan.oneTimeFocusWindow?.startEpochMillis?.let {
                                it != mutation.enableAtEpochMillis
                            } == true
                        ) {
                            return@safelyWrite PlanWriteResult.InvalidInput(
                                "一次性专注只能在原定开始时间开启"
                            )
                        }
                    }
                }
                if (
                    request.plan.enabled && request.plan.isOneTimeFocus() &&
                    request.plan.oneTimeFocusWindow!!.endEpochMillis <= transactionNowEpochMillis
                ) {
                    return@safelyWrite PlanWriteResult.InvalidInput(
                        "一次性专注已结束，请从待办重新安排"
                    )
                }
                if (
                    request.expectedUpdatedAtEpochMillis != null &&
                    request.plan.enabled && request.plan.isOneTimeFocus() &&
                    todoDao.countFocusPlanAssociations(request.plan.id) == 0
                ) {
                    return@safelyWrite PlanWriteResult.InvalidInput(
                        "一次性专注已结束，请从待办重新安排"
                    )
                }
            }

            val candidateById = storedById.toMutableMap()
            requests.forEach { candidateById[it.plan.id] = it.plan }
            val requestIds = requests.mapTo(linkedSetOf()) { it.plan.id }
            val autoDisabledPlans = resolveAutoDisabledOppositePlans(
                storedById = storedById,
                candidateById = candidateById,
                requestIds = requestIds,
                nowEpochMillis = System.currentTimeMillis().coerceAtLeast(0L)
            )
            autoDisabledPlans.forEach { plan -> candidateById[plan.id] = plan }
            val conflicts = findConflicts(candidateById.values)
            if (conflicts.isNotEmpty()) return@safelyWrite PlanWriteResult.Conflicts(conflicts)

            requests.forEach { dao.replace(SupervisionPlanMapper.toRecord(it.plan)) }
            autoDisabledPlans.forEach { dao.replace(SupervisionPlanMapper.toRecord(it)) }
            requests.forEach { request ->
                when {
                    request.plan.enabled -> dao.deleteActivationReservation(request.plan.id)
                    request.reservationMutation is PlanReservationMutation.Set -> {
                        dao.upsertActivationReservation(
                            PlanActivationReservationEntity(
                                planId = request.plan.id,
                                enableAtEpochMillis =
                                    request.reservationMutation.enableAtEpochMillis
                            )
                        )
                    }
                    request.reservationMutation == PlanReservationMutation.Clear -> {
                        dao.deleteActivationReservation(request.plan.id)
                    }
                    else -> Unit
                }
            }
            // 一次性专注改成周期计划后，旧预约与待办排程已不再有归属；
            // 必须在同一事务中一并清理，避免旧预约到点激活新周期计划。
            requests.forEach { request ->
                val previous = storedById[request.plan.id]
                if (previous?.isOneTimeFocus() == true && !request.plan.isOneTimeFocus()) {
                    dao.deleteActivationReservation(request.plan.id)
                    todoDao.clearFocusPlanAssociation(
                        planId = request.plan.id,
                        updatedAt = request.plan.updatedAtEpochMillis
                    )
                }
            }
            requests.asSequence()
                .map(PlanSaveRequest::plan)
                .filter { plan ->
                    plan.isOneTimeFocus() &&
                        !plan.enabled &&
                        plan.scheduledEnableAtEpochMillis == null
                }
                .forEach { plan ->
                    todoDao.clearFocusPlanAssociation(
                        planId = plan.id,
                        updatedAt = plan.updatedAtEpochMillis
                    )
                }
            val autoDisabledIds = autoDisabledPlans.mapTo(linkedSetOf()) { it.id }
            PlanWriteResult.Success(
                affectedPlanIds = requestIds + autoDisabledIds,
                autoDisabledPlanIds = autoDisabledIds
            )
        }
    }

    /**
     * 为待办创建一次性专注锁，并在同一 Room 事务中写入待办关联和预约记录。
     * 计划编号由待办及时间窗确定，重复点击同一建议只会返回已有计划。
     */
    suspend fun scheduleOneTimeFocusForTodo(
        todoId: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis().coerceAtLeast(0L),
        expectedTodoUpdatedAtEpochMillis: Long? = null
    ): OneTimeFocusScheduleResult {
        if (todoId.isBlank()) return OneTimeFocusScheduleResult.InvalidInput("待办编号不能为空")
        if (nowEpochMillis < 0L) return OneTimeFocusScheduleResult.InvalidInput("当前时间无效")
        val window = try {
            OneTimeFocusWindow(startEpochMillis, endEpochMillis)
        } catch (error: IllegalArgumentException) {
            return OneTimeFocusScheduleResult.InvalidInput(error.message ?: "专注时间窗无效")
        }
        if (window.endEpochMillis <= nowEpochMillis) {
            return OneTimeFocusScheduleResult.InvalidInput("专注时间段已经结束")
        }
        val durationMillis = window.endEpochMillis - window.startEpochMillis
        val durationMinutes = (
            durationMillis / 60_000L +
                if (durationMillis % 60_000L == 0L) 0L else 1L
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (durationMinutes !in 1..1_440) {
            return OneTimeFocusScheduleResult.InvalidInput("专注时长必须是 1-1440 分钟")
        }

        return try {
            database.withTransaction {
                val todoDao = database.todoDao()
                val todo = todoDao.getById(todoId)
                    ?: return@withTransaction OneTimeFocusScheduleResult.TodoNotFound(todoId)

                val storedResult = mapAll(dao.getAll())
                if (storedResult is PlanLoadResult.CorruptData) {
                    return@withTransaction OneTimeFocusScheduleResult.CorruptData(
                        storedResult.failures
                    )
                }
                val storedPlans = (storedResult as PlanLoadResult.Success).plans
                val planId = oneTimeFocusPlanId(todo.id, window)
                val existing = storedPlans.firstOrNull { it.id == planId }
                val isAlreadyApplied = existing?.oneTimeFocusWindow == window &&
                    (existing.enabled || existing.scheduledEnableAtEpochMillis != null) &&
                    todo.associatedFocusPlanId == planId &&
                    todo.scheduledStartEpochMillis == window.startEpochMillis &&
                    todo.scheduledEndEpochMillis == window.endEpochMillis
                if (
                    expectedTodoUpdatedAtEpochMillis != null &&
                    todo.updatedAtEpochMillis != expectedTodoUpdatedAtEpochMillis
                ) {
                    if (isAlreadyApplied) {
                        return@withTransaction OneTimeFocusScheduleResult.Success(
                            planId = planId,
                            wasCreated = false
                        )
                    }
                    return@withTransaction OneTimeFocusScheduleResult.StaleTodo(
                        todoId = todoId,
                        expectedUpdatedAtEpochMillis = expectedTodoUpdatedAtEpochMillis,
                        actualUpdatedAtEpochMillis = todo.updatedAtEpochMillis
                    )
                }
                if (todo.isCompleted) {
                    return@withTransaction OneTimeFocusScheduleResult.InvalidInput("待办已经完成")
                }
                if (existing != null) {
                    if (existing.oneTimeFocusWindow != window) {
                        return@withTransaction OneTimeFocusScheduleResult.InvalidInput(
                            "已有同编号专注计划，无法覆盖不同时间窗"
                        )
                    }
                    if (existing.oneTimeFocusWindow.endEpochMillis <= nowEpochMillis) {
                        return@withTransaction OneTimeFocusScheduleResult.InvalidInput(
                            "该专注计划已经结束，请重新生成建议"
                        )
                    }
                    if (existing.enabled || existing.scheduledEnableAtEpochMillis != null) {
                        if (isAlreadyApplied) {
                            return@withTransaction OneTimeFocusScheduleResult.Success(
                                planId = planId,
                                wasCreated = false
                            )
                        }
                        val normalizedTodo = normalizeAssociatedTodo(
                            todo = todo,
                            planId = planId,
                            window = window,
                            durationMinutes = durationMinutes,
                            nowEpochMillis = nowEpochMillis
                        )
                        todoDao.upsertTodo(normalizedTodo)
                        return@withTransaction OneTimeFocusScheduleResult.Success(
                            planId = planId,
                            wasCreated = false
                        )
                    }
                }

                val oldAssociatedPlan = todo.associatedFocusPlanId
                    ?.let { id -> storedPlans.firstOrNull { it.id == id } }
                val oldOneTimePlanToReplace = oldAssociatedPlan
                    ?.takeIf { it.id != planId && it.isOneTimeFocus() }
                if (oldAssociatedPlan != null && oldAssociatedPlan.id != planId) {
                    if (oldAssociatedPlan.enabled && oldAssociatedPlan.oneTimeFocusWindow?.contains(nowEpochMillis) == true) {
                        return@withTransaction OneTimeFocusScheduleResult.InvalidInput(
                            "该待办已有正在执行的专注锁"
                        )
                    }
                }

                val candidate = buildOneTimeFocusPlan(
                    todo = todo,
                    planId = planId,
                    window = window,
                    durationMinutes = durationMinutes,
                    nowEpochMillis = nowEpochMillis
                )
                // 只把候选时间窗内实际占用设备锁通道的计划交给权威冲突检测。
                // 预约中的周期计划必须从预约生效时刻开始展开，不能把未来预约
                // 当成从当前立即启用，否则会拒绝本应可用的待办时段。
                val deviceZoneId = ZoneId.systemDefault()
                val conflictingPlans = storedPlans
                    .asSequence()
                    .filter { plan ->
                        plan.id != oldOneTimePlanToReplace?.id && plan.id != candidate.id
                    }
                    .filter { plan ->
                        SupervisionBusyIntervalFactory.create(
                            plans = listOf(plan),
                            startEpochMillis = window.startEpochMillis,
                            endExclusiveEpochMillis = window.endEpochMillis,
                            deviceZoneId = deviceZoneId
                        ).isNotEmpty()
                    }
                    .map { plan ->
                        plan.copy(enabled = true, scheduledEnableAtEpochMillis = null)
                    }
                    .toList()
                val logicalPlans = conflictingPlans + candidate.copy(
                    enabled = true,
                    scheduledEnableAtEpochMillis = null
                )
                val conflicts = SupervisionPlanConflictDetector
                    .findEnabledDeviceLockConflicts(logicalPlans, deviceZoneId)
                    .filter { it.firstPlanId == candidate.id || it.secondPlanId == candidate.id }
                if (conflicts.isNotEmpty()) {
                    return@withTransaction OneTimeFocusScheduleResult.Conflicts(conflicts)
                }

                // 冲突预检通过后才删除待办旧的一次性计划，避免失败返回留下半成品或
                // 在监督页暴露一个仍可手动开启的孤儿计划。
                if (oldOneTimePlanToReplace != null) {
                    dao.delete(oldOneTimePlanToReplace.id)
                    dao.deleteActivationReservation(oldOneTimePlanToReplace.id)
                    todoDao.clearFocusPlanAssociation(
                        planId = oldOneTimePlanToReplace.id,
                        updatedAt = nowEpochMillis
                    )
                }

                dao.replace(SupervisionPlanMapper.toRecord(candidate))
                dao.upsertActivationReservation(
                    PlanActivationReservationEntity(planId, window.startEpochMillis)
                )
                todoDao.upsertTodo(
                    normalizeAssociatedTodo(
                        todo = todo,
                        planId = planId,
                        window = window,
                        durationMinutes = durationMinutes,
                        nowEpochMillis = nowEpochMillis
                    )
                )
                OneTimeFocusScheduleResult.Success(planId = planId, wasCreated = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
            OneTimeFocusScheduleResult.StorageFailure
        }
    }

    /** 到期后把一次性计划置为停用，避免它在下一周再次触发。 */
    suspend fun disableExpiredOneTimeFocusPlans(
        nowEpochMillis: Long
    ): PlanWriteResult {
        if (nowEpochMillis < 0L) return PlanWriteResult.InvalidInput("停用时间无效")
        return safelyWrite {
            val stored = mapAll(dao.getAll())
            if (stored !is PlanLoadResult.Success) return@safelyWrite stored.toWriteFailure()
            val associatedPlanIds = todoDao.getAssociatedFocusPlanIds().toSet()
            val expired = stored.plans.filter { plan ->
                plan.isOneTimeFocus() &&
                    plan.oneTimeFocusWindow!!.endEpochMillis <= nowEpochMillis &&
                    (
                        plan.enabled ||
                            plan.scheduledEnableAtEpochMillis != null ||
                            plan.id in associatedPlanIds
                        )
            }
            val affected = linkedSetOf<String>()
            expired.forEach { plan ->
                val planChanged = plan.enabled || plan.scheduledEnableAtEpochMillis != null
                if (planChanged) {
                    val updated = plan.copy(
                        enabled = false,
                        scheduledEnableAtEpochMillis = null,
                        updatedAtEpochMillis = terminalVersion(
                            plan.updatedAtEpochMillis,
                            nowEpochMillis
                        )
                    )
                    dao.replace(SupervisionPlanMapper.toRecord(updated))
                }
                dao.deleteActivationReservation(plan.id)
                val unlinkedTodos = todoDao.unlinkFocusPlanAssociation(
                    planId = plan.id,
                    updatedAt = nowEpochMillis
                )
                if (planChanged || unlinkedTodos > 0) affected += plan.id
            }
            PlanWriteResult.Success(affected)
        }
    }

    suspend fun resetExpiredAppTriggerPlans(
        nowEpochMillis: Long,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): PlanWriteResult = try {
        database.withTransaction {
            val storedRecords = dao.getAll()
            val mappingResults = storedRecords.map(SupervisionPlanMapper::fromRecord)
            if (mappingResults.any { it is PlanMappingResult.Failure }) {
                return@withTransaction PlanWriteResult.StorageFailure
            }
            val affected = linkedSetOf<String>()
            val now = Instant.ofEpochMilli(nowEpochMillis)
            mappingResults
                .filterIsInstance<PlanMappingResult.Success>()
                .map { it.plan }
                .filter { plan ->
                    plan.type == SupervisionPlanType.GLOBAL &&
                        plan.triggerAppPackageNames.isNotEmpty() &&
                        plan.enabled &&
                        !plan.schedule.isActiveAt(now, deviceZoneId)
                }
                .forEach { plan ->
                    val nextVersion = nextVersion(plan.updatedAtEpochMillis, nowEpochMillis) ?: plan.updatedAtEpochMillis
                    val updated = plan.copy(
                        enabled = false,
                        updatedAtEpochMillis = nextVersion
                    )
                    dao.replace(SupervisionPlanMapper.toRecord(updated))
                    affected += plan.id
                }
            PlanWriteResult.Success(affected)
        }
    } catch (_: Exception) {
        PlanWriteResult.StorageFailure
    }

    suspend fun setEnabled(
        planId: String,
        enabled: Boolean,
        expectedUpdatedAtEpochMillis: Long,
        updatedAtEpochMillis: Long
    ): PlanWriteResult {
        val loaded = loadPlans()
        if (loaded !is PlanLoadResult.Success) return loaded.toWriteFailure()
        val current = loaded.plans.firstOrNull { it.id == planId }
            ?: return PlanWriteResult.NotFound(planId)
        val updated = try {
            current.copy(enabled = enabled, updatedAtEpochMillis = updatedAtEpochMillis)
        } catch (error: IllegalArgumentException) {
            return PlanWriteResult.InvalidInput(error.message ?: "监督计划参数无效")
        }
        return save(updated, expectedUpdatedAtEpochMillis)
    }

    /**
     * 由前台 App 进入边沿原子启用任务。所有触发条件在 Room 事务内重新校验，
     * 避免过期观察、重复回调或用户并发编辑造成误启用。
     */
    suspend fun activateFromAppTrigger(
        planId: String,
        expectedUpdatedAtEpochMillis: Long,
        triggerPackageName: String,
        nowEpochMillis: Long,
        deviceZoneId: ZoneId = ZoneId.systemDefault()
    ): AppTriggerActivationResult = try {
        database.withTransaction {
            val storedRecords = dao.getAll()
            val mappingResults = storedRecords.map(SupervisionPlanMapper::fromRecord)
            if (mappingResults.any { it is PlanMappingResult.Failure }) {
                return@withTransaction AppTriggerActivationResult.StorageFailure
            }
            val currentById = mappingResults
                .filterIsInstance<PlanMappingResult.Success>()
                .associate { it.plan.id to it.plan }
                .toMutableMap()
            val current = currentById[planId]
            Log.d("AppTriggerDebug", "activateFromAppTrigger planId=$planId, triggerPackageName=$triggerPackageName, now=${Instant.ofEpochMilli(nowEpochMillis)}")
            if (current == null) {
                Log.e("AppTriggerDebug", "activateFromAppTrigger PlanNotFound")
                return@withTransaction AppTriggerActivationResult.NotFound
            }
            if (current.enabled) {
                Log.d("AppTriggerDebug", "current.enabled is already true")
                return@withTransaction AppTriggerActivationResult.AlreadyEnabled
            }
            Log.d("AppTriggerDebug", "current.updatedAtEpochMillis=${current.updatedAtEpochMillis}, expected=$expectedUpdatedAtEpochMillis")
            Log.d("AppTriggerDebug", "current.type=${current.type}")
            Log.d("AppTriggerDebug", "current.triggerAppPackageNames=${current.triggerAppPackageNames}")
            val isActive = current.schedule.isActiveAt(Instant.ofEpochMilli(nowEpochMillis), deviceZoneId)
            Log.d("AppTriggerDebug", "current.schedule.isActiveAt=$isActive")

            if (
                current.updatedAtEpochMillis != expectedUpdatedAtEpochMillis ||
                current.type != SupervisionPlanType.GLOBAL ||
                triggerPackageName !in current.triggerAppPackageNames ||
                !isActive
            ) {
                Log.w("AppTriggerDebug", "activateFromAppTrigger NoLongerEligible! current.updatedAtEpochMillis=${current.updatedAtEpochMillis}, expected=$expectedUpdatedAtEpochMillis, type=${current.type}, isPackageMatch=${triggerPackageName in current.triggerAppPackageNames}, isActive=$isActive")
                return@withTransaction AppTriggerActivationResult.NoLongerEligible
            }
            val nextVersion = nextVersion(current.updatedAtEpochMillis, nowEpochMillis)
                ?: return@withTransaction AppTriggerActivationResult.NoLongerEligible
            val activated = current.copy(
                enabled = true,
                updatedAtEpochMillis = nextVersion,
                scheduledEnableAtEpochMillis = null
            )
            val storedSnapshot = currentById.toMap()
            val proposedById = currentById.toMutableMap().apply { put(current.id, activated) }
            val autoDisabledPlans = resolveAutoDisabledOppositePlans(
                storedById = storedSnapshot,
                candidateById = proposedById,
                requestIds = setOf(current.id),
                nowEpochMillis = nowEpochMillis
            )
            autoDisabledPlans.forEach { proposedById[it.id] = it }
            val conflicts = findConflicts(proposedById.values).filter { conflict ->
                conflict.firstPlanId == current.id || conflict.secondPlanId == current.id
            }
            if (conflicts.isNotEmpty()) {
                return@withTransaction AppTriggerActivationResult.Conflicts(conflicts)
            }
            autoDisabledPlans.forEach { dao.replace(SupervisionPlanMapper.toRecord(it)) }
            dao.replace(SupervisionPlanMapper.toRecord(activated))
            dao.deleteActivationReservation(activated.id)
            AppTriggerActivationResult.Activated(
                planId = activated.id,
                autoDisabledPlanIds = autoDisabledPlans.mapTo(linkedSetOf(), SupervisionPlan::id)
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        AppTriggerActivationResult.StorageFailure
    }

    /** 为当前停用的任务预约一次未来开启，并通过计划版本防止覆盖并发编辑。 */
    suspend fun scheduleEnable(
        planId: String,
        enableAtEpochMillis: Long,
        expectedUpdatedAtEpochMillis: Long,
        nowEpochMillis: Long
    ): PlanWriteResult {
        val normalizedPlanId = planId.trim()
        if (normalizedPlanId.isEmpty()) return PlanWriteResult.InvalidInput("计划编号不能为空")
        if (nowEpochMillis < 0L || enableAtEpochMillis <= nowEpochMillis) {
            return PlanWriteResult.InvalidInput("预约开启时间必须晚于当前时间")
        }

        return safelyWrite {
            val stored = dao.getById(normalizedPlanId)
                ?: return@safelyWrite PlanWriteResult.NotFound(normalizedPlanId)
            if (stored.plan.updatedAtEpochMillis != expectedUpdatedAtEpochMillis) {
                return@safelyWrite PlanWriteResult.StaleData(
                    normalizedPlanId,
                    expectedUpdatedAtEpochMillis,
                    stored.plan.updatedAtEpochMillis
                )
            }
            val mapped = SupervisionPlanMapper.fromRecord(stored)
            if (mapped is PlanMappingResult.Failure) {
                return@safelyWrite PlanWriteResult.CorruptData(listOf(mapped))
            }
            val current = (mapped as PlanMappingResult.Success).plan
            if (current.enabled) {
                return@safelyWrite PlanWriteResult.InvalidInput("只能为已停用的任务预约开启")
            }
            if (
                current.isOneTimeFocus() &&
                current.oneTimeFocusWindow!!.endEpochMillis <= nowEpochMillis
            ) {
                return@safelyWrite PlanWriteResult.InvalidInput(
                    "一次性专注已结束，请从待办重新安排"
                )
            }
            if (
                current.isOneTimeFocus() &&
                todoDao.countFocusPlanAssociations(current.id) == 0
            ) {
                return@safelyWrite PlanWriteResult.InvalidInput(
                    "一次性专注已结束，请从待办重新安排"
                )
            }
            if (
                current.oneTimeFocusWindow?.startEpochMillis?.let { it != enableAtEpochMillis } == true
            ) {
                return@safelyWrite PlanWriteResult.InvalidInput(
                    "一次性专注只能在原定开始时间开启"
                )
            }
            val nextVersion = nextVersion(current.updatedAtEpochMillis, nowEpochMillis)
                ?: return@safelyWrite PlanWriteResult.NonIncreasingVersion(
                    current.id,
                    current.updatedAtEpochMillis,
                    current.updatedAtEpochMillis
                )
            val updated = current.copy(
                updatedAtEpochMillis = nextVersion,
                scheduledEnableAtEpochMillis = enableAtEpochMillis
            )
            dao.replace(SupervisionPlanMapper.toRecord(updated))
            dao.upsertActivationReservation(
                PlanActivationReservationEntity(current.id, enableAtEpochMillis)
            )
            PlanWriteResult.Success(setOf(current.id))
        }
    }

    /** 取消尚未触发的预约；不存在预约时幂等成功且不递增版本。 */
    suspend fun cancelScheduledEnable(
        planId: String,
        expectedUpdatedAtEpochMillis: Long,
        nowEpochMillis: Long
    ): PlanWriteResult {
        val normalizedPlanId = planId.trim()
        if (normalizedPlanId.isEmpty()) return PlanWriteResult.InvalidInput("计划编号不能为空")
        if (nowEpochMillis < 0L) return PlanWriteResult.InvalidInput("取消预约时间无效")

        return safelyWrite {
            val stored = dao.getById(normalizedPlanId)
                ?: return@safelyWrite PlanWriteResult.NotFound(normalizedPlanId)
            if (stored.plan.updatedAtEpochMillis != expectedUpdatedAtEpochMillis) {
                return@safelyWrite PlanWriteResult.StaleData(
                    normalizedPlanId,
                    expectedUpdatedAtEpochMillis,
                    stored.plan.updatedAtEpochMillis
                )
            }
            val mapped = SupervisionPlanMapper.fromRecord(stored)
            if (mapped is PlanMappingResult.Failure) {
                return@safelyWrite PlanWriteResult.CorruptData(listOf(mapped))
            }
            val current = (mapped as PlanMappingResult.Success).plan
            if (current.scheduledEnableAtEpochMillis == null) {
                return@safelyWrite PlanWriteResult.Success(emptySet())
            }
            val nextVersion = nextVersion(current.updatedAtEpochMillis, nowEpochMillis)
                ?: return@safelyWrite PlanWriteResult.NonIncreasingVersion(
                    current.id,
                    current.updatedAtEpochMillis,
                    current.updatedAtEpochMillis
                )
            dao.replace(
                SupervisionPlanMapper.toRecord(
                    current.copy(
                        updatedAtEpochMillis = nextVersion,
                        scheduledEnableAtEpochMillis = null
                    )
                )
            )
            dao.deleteActivationReservation(current.id)
            if (current.isOneTimeFocus()) {
                todoDao.clearFocusPlanAssociation(current.id, nowEpochMillis)
            }
            PlanWriteResult.Success(setOf(current.id))
        }
    }

    /**
     * 原子处理所有已到期预约。
     *
     * 可自动解决的全局监督/专注冲突沿用手动启用的“停用相反类型”规则；其余冲突
     * 会清除预约并作为终态失败返回，避免协调器持续重试。任何存储异常都会回滚
     * 整批变更，因此预约仍可在下次调度时重试。
     */
    suspend fun activateDueScheduledEnables(
        nowEpochMillis: Long
    ): ScheduledEnableBatchResult {
        if (nowEpochMillis < 0L) {
            return ScheduledEnableBatchResult.InvalidInput("预约触发时间无效")
        }
        return safelyActivate {
            val dueReservations = dao.getDueActivationReservations(nowEpochMillis)
            if (dueReservations.isEmpty()) {
                return@safelyActivate ScheduledEnableBatchResult.Completed(
                    emptySet(),
                    emptySet(),
                    emptyList()
                )
            }

            val storedRecords = dao.getAll()
            val mappingResults = storedRecords.map(SupervisionPlanMapper::fromRecord)
            val corruptPlanIds = storedRecords.zip(mappingResults)
                .filter { (_, result) -> result is PlanMappingResult.Failure }
                .mapTo(mutableSetOf()) { (record, _) -> record.plan.planId }
            val namesById = storedRecords.associate { it.plan.planId to it.plan.name }

            val currentById = mappingResults
                .filterIsInstance<PlanMappingResult.Success>()
                .associate { it.plan.id to it.plan }
                .toMutableMap()
            val activatedPlanIds = linkedSetOf<String>()
            val autoDisabledPlanIds = linkedSetOf<String>()
            val failures = mutableListOf<ScheduledEnableFailure>()

            dueReservations.forEach { reservation ->
                if (reservation.planId in corruptPlanIds) {
                    dao.deleteActivationReservation(reservation.planId)
                    todoDao.unlinkFocusPlanAssociation(reservation.planId, nowEpochMillis)
                    failures += ScheduledEnableFailure(
                        planId = reservation.planId,
                        planName = namesById[reservation.planId],
                        reason = ScheduledEnableFailureReason.CORRUPT_DATA
                    )
                    return@forEach
                }
                val current = currentById[reservation.planId]
                if (current == null) {
                    dao.deleteActivationReservation(reservation.planId)
                    todoDao.unlinkFocusPlanAssociation(reservation.planId, nowEpochMillis)
                    failures += ScheduledEnableFailure(
                        reservation.planId,
                        null,
                        ScheduledEnableFailureReason.NOT_FOUND
                    )
                    return@forEach
                }
                if (current.oneTimeFocusWindow?.endEpochMillis?.let { it <= nowEpochMillis } == true) {
                    val expired = current.copy(
                        enabled = false,
                        scheduledEnableAtEpochMillis = null,
                        updatedAtEpochMillis = terminalVersion(
                            current.updatedAtEpochMillis,
                            nowEpochMillis
                        )
                    )
                    if (expired != current) {
                        dao.replace(SupervisionPlanMapper.toRecord(expired))
                    }
                    dao.deleteActivationReservation(current.id)
                    currentById[current.id] = expired
                    todoDao.unlinkFocusPlanAssociation(current.id, nowEpochMillis)
                    failures += ScheduledEnableFailure(
                        current.id,
                        current.name,
                        ScheduledEnableFailureReason.EXPIRED
                    )
                    return@forEach
                }
                if (current.enabled) {
                    clearTerminalReservation(current, nowEpochMillis, currentById)
                    failures += ScheduledEnableFailure(
                        current.id,
                        current.name,
                        ScheduledEnableFailureReason.ALREADY_ENABLED
                    )
                    return@forEach
                }
                val activationVersion = nextVersion(
                    current.updatedAtEpochMillis,
                    nowEpochMillis
                )
                if (activationVersion == null) {
                    dao.deleteActivationReservation(current.id)
                    currentById[current.id] = current.copy(scheduledEnableAtEpochMillis = null)
                    if (current.isOneTimeFocus()) {
                        todoDao.unlinkFocusPlanAssociation(current.id, nowEpochMillis)
                    }
                    failures += ScheduledEnableFailure(
                        current.id,
                        current.name,
                        ScheduledEnableFailureReason.VERSION_EXHAUSTED
                    )
                    return@forEach
                }

                val activated = current.copy(
                    enabled = true,
                    updatedAtEpochMillis = activationVersion,
                    scheduledEnableAtEpochMillis = null
                )
                val storedSnapshot = currentById.toMap()
                val proposedById = currentById.toMutableMap().apply { put(current.id, activated) }
                val autoDisabledPlans = resolveAutoDisabledOppositePlans(
                    storedById = storedSnapshot,
                    candidateById = proposedById,
                    requestIds = setOf(current.id),
                    nowEpochMillis = nowEpochMillis
                )
                autoDisabledPlans.forEach { proposedById[it.id] = it }
                val activationConflicts = findConflicts(proposedById.values).filter { conflict ->
                    conflict.firstPlanId == current.id || conflict.secondPlanId == current.id
                }
                if (activationConflicts.isNotEmpty()) {
                    clearTerminalReservation(current, nowEpochMillis, currentById)
                    if (current.isOneTimeFocus()) {
                        todoDao.unlinkFocusPlanAssociation(current.id, nowEpochMillis)
                    }
                    failures += ScheduledEnableFailure(
                        current.id,
                        current.name,
                        ScheduledEnableFailureReason.CONFLICT,
                        activationConflicts
                    )
                    return@forEach
                }

                autoDisabledPlans.forEach { plan ->
                    dao.replace(SupervisionPlanMapper.toRecord(plan))
                    currentById[plan.id] = plan
                    autoDisabledPlanIds += plan.id
                }
                dao.replace(SupervisionPlanMapper.toRecord(activated))
                dao.deleteActivationReservation(activated.id)
                currentById[activated.id] = activated
                activatedPlanIds += activated.id
            }

            val finallyActivatedPlanIds = activatedPlanIds.filterTo(linkedSetOf()) { planId ->
                currentById[planId]?.enabled == true
            }
            ScheduledEnableBatchResult.Completed(
                finallyActivatedPlanIds,
                autoDisabledPlanIds,
                failures
            )
        }
    }

    /**
     * 用户终止或应急解锁定时专注任务时，按计划 ID 停用数据库中的最新版本。
     *
     * 该操作不使用运行会话中的旧版本做乐观锁，避免计划在运行期间被编辑后无法关闭；
     * 已停用计划幂等成功且不重复递增版本。
     */
    suspend fun disableScheduledFocusPlan(
        planId: String,
        nowEpochMillis: Long
    ): PlanWriteResult {
        val normalizedPlanId = planId.trim()
        if (normalizedPlanId.isEmpty()) {
            return PlanWriteResult.InvalidInput("定时专注计划编号不能为空")
        }
        if (nowEpochMillis < 0L) {
            return PlanWriteResult.InvalidInput("停用时间无效")
        }

        return safelyWrite {
            val stored = dao.getById(normalizedPlanId)
                ?: return@safelyWrite PlanWriteResult.NotFound(normalizedPlanId)
            val mapped = SupervisionPlanMapper.fromRecord(stored)
            if (mapped is PlanMappingResult.Failure) {
                return@safelyWrite PlanWriteResult.CorruptData(listOf(mapped))
            }
            val current = (mapped as PlanMappingResult.Success).plan
            if (current.type != SupervisionPlanType.FOCUS) {
                return@safelyWrite PlanWriteResult.InvalidInput(
                    "计划“${current.name}”不是定时专注任务"
                )
            }
            if (!current.enabled) {
                return@safelyWrite PlanWriteResult.Success(emptySet())
            }
            if (current.updatedAtEpochMillis == Long.MAX_VALUE) {
                return@safelyWrite PlanWriteResult.NonIncreasingVersion(
                    planId = current.id,
                    actualUpdatedAtEpochMillis = current.updatedAtEpochMillis,
                    proposedUpdatedAtEpochMillis = current.updatedAtEpochMillis
                )
            }

            val updated = current.copy(
                enabled = false,
                updatedAtEpochMillis = maxOf(
                    nowEpochMillis,
                    current.updatedAtEpochMillis + 1L
                )
            )
            dao.replace(SupervisionPlanMapper.toRecord(updated))
            if (current.isOneTimeFocus()) {
                todoDao.unlinkFocusPlanAssociation(current.id, nowEpochMillis)
            }
            PlanWriteResult.Success(setOf(current.id))
        }
    }

    suspend fun delete(
        planId: String,
        expectedUpdatedAtEpochMillis: Long
    ): PlanWriteResult = safelyWrite {
        val current = dao.getById(planId) ?: return@safelyWrite PlanWriteResult.NotFound(planId)
        if (current.plan.updatedAtEpochMillis != expectedUpdatedAtEpochMillis) {
            return@safelyWrite PlanWriteResult.StaleData(
                planId,
                expectedUpdatedAtEpochMillis,
                current.plan.updatedAtEpochMillis
            )
        }
        dao.delete(planId)
        todoDao.clearFocusPlanAssociation(
            planId = planId,
            updatedAt = System.currentTimeMillis().coerceAtLeast(0L)
        )
        PlanWriteResult.Success(setOf(planId))
    }

    private suspend fun safelyWrite(
        block: suspend () -> PlanWriteResult
    ): PlanWriteResult = try {
        database.withTransaction { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        PlanWriteResult.StorageFailure
    }

    private fun buildOneTimeFocusPlan(
        todo: TodoItemEntity,
        planId: String,
        window: OneTimeFocusWindow,
        durationMinutes: Int,
        nowEpochMillis: Long
    ): SupervisionPlan {
        val zoneId = ZoneId.systemDefault()
        val startLocal = Instant.ofEpochMilli(window.startEpochMillis).atZone(zoneId)
        val endLocal = Instant.ofEpochMilli(window.endEpochMillis).atZone(zoneId)
        val startMinute = startLocal.hour * 60 + startLocal.minute
        val endMinute = endLocal.hour * 60 + endLocal.minute
        // 运行时只读取绝对时间窗；周时段仅用于兼容既有存储结构，因此把同一分钟
        // 内的秒级窗口扩成一个有效占位分钟即可。
        val placeholderRange = when {
            startMinute == endMinute && startLocal.toLocalDate() != endLocal.toLocalDate() ->
                DailyTimeRange(0, 1_440)
            startMinute == endMinute ->
                DailyTimeRange(startMinute, startMinute + 1)
            endMinute == 0 -> DailyTimeRange(startMinute, 1_440)
            else -> DailyTimeRange(startMinute, endMinute)
        }
        val createdAt = nowEpochMillis.coerceAtLeast(todo.createdAtEpochMillis)
        return SupervisionPlan(
            id = planId,
            name = "专注：${todo.title.trim().take(36)}".trimEnd('：'),
            type = SupervisionPlanType.FOCUS,
            enabled = false,
            schedule = WeeklySchedule(
                zoneId = zoneId,
                activeDays = setOf(startLocal.dayOfWeek),
                ranges = listOf(placeholderRange),
                zoneMode = ScheduleZoneMode.FIXED
            ),
            policy = FocusCyclePolicy(
                lockDuration = Duration.ofMinutes(durationMinutes.toLong()),
                playDuration = Duration.ofMinutes(1L)
            ),
            createdAtEpochMillis = createdAt,
            updatedAtEpochMillis = createdAt,
            scheduledEnableAtEpochMillis = window.startEpochMillis,
            oneTimeFocusWindow = window
        )
    }

    private fun normalizeAssociatedTodo(
        todo: TodoItemEntity,
        planId: String,
        window: OneTimeFocusWindow,
        durationMinutes: Int,
        nowEpochMillis: Long
    ): TodoItemEntity = todo.copy(
            associatedFocusPlanId = planId,
            scheduledStartEpochMillis = window.startEpochMillis,
            scheduledEndEpochMillis = window.endEpochMillis,
            estimatedFocusMinutes = durationMinutes,
            updatedAtEpochMillis = maxOf(todo.updatedAtEpochMillis, nowEpochMillis)
        )

    private fun terminalVersion(currentVersion: Long, nowEpochMillis: Long): Long =
        if (currentVersion == Long.MAX_VALUE) Long.MAX_VALUE
        else maxOf(nowEpochMillis, currentVersion + 1L)

    private suspend fun safelyActivate(
        block: suspend () -> ScheduledEnableBatchResult
    ): ScheduledEnableBatchResult = try {
        database.withTransaction { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: RuntimeException) {
        ScheduledEnableBatchResult.StorageFailure
    }

    private suspend fun clearTerminalReservation(
        current: SupervisionPlan,
        nowEpochMillis: Long,
        currentById: MutableMap<String, SupervisionPlan>
    ) {
        val cleared = nextVersion(current.updatedAtEpochMillis, nowEpochMillis)?.let { version ->
            current.copy(
                updatedAtEpochMillis = version,
                scheduledEnableAtEpochMillis = null
            )
        } ?: current.copy(scheduledEnableAtEpochMillis = null)
        if (cleared.updatedAtEpochMillis != current.updatedAtEpochMillis) {
            dao.replace(SupervisionPlanMapper.toRecord(cleared))
        }
        dao.deleteActivationReservation(current.id)
        currentById[current.id] = cleared
    }

    private fun nextVersion(currentVersion: Long, nowEpochMillis: Long): Long? =
        if (currentVersion == Long.MAX_VALUE) null
        else maxOf(nowEpochMillis, currentVersion + 1L)

    private fun mapAll(records: List<SupervisionPlanWithRanges>): PlanLoadResult {
        val results = records.map(SupervisionPlanMapper::fromRecord)
        val failures = results.filterIsInstance<PlanMappingResult.Failure>()
        if (failures.isNotEmpty()) return PlanLoadResult.CorruptData(failures)
        return PlanLoadResult.Success(
            results.filterIsInstance<PlanMappingResult.Success>().map { it.plan }
        )
    }

    private fun findConflicts(plans: Collection<SupervisionPlan>): List<SupervisionPlanConflict> {
        val conflictParticipants = plans.map { plan ->
            if (!plan.enabled && plan.isOneTimeFocus() && plan.scheduledEnableAtEpochMillis != null) {
                plan.copy(enabled = true, scheduledEnableAtEpochMillis = null)
            } else {
                plan
            }
        }
        return SupervisionPlanConflictDetector.findEnabledDeviceLockConflicts(
            conflictParticipants
        ) + SupervisionPlanConflictDetector.findEnabledAppConflicts(conflictParticipants)
    }

    private fun resolveAutoDisabledOppositePlans(
        storedById: Map<String, SupervisionPlan>,
        candidateById: Map<String, SupervisionPlan>,
        requestIds: Set<String>,
        nowEpochMillis: Long
    ): List<SupervisionPlan> {
        val storedIdsToDisable = SupervisionPlanConflictDetector
            .findEnabledDeviceLockConflicts(candidateById.values)
            .asSequence()
            .mapNotNull { conflict ->
                val firstRequested = conflict.firstPlanId in requestIds
                val secondRequested = conflict.secondPlanId in requestIds
                if (firstRequested == secondRequested) return@mapNotNull null
                val requestedId = if (firstRequested) conflict.firstPlanId else conflict.secondPlanId
                val storedId = if (firstRequested) conflict.secondPlanId else conflict.firstPlanId
                val requestedPlan = candidateById[requestedId] ?: return@mapNotNull null
                val storedPlan = storedById[storedId] ?: return@mapNotNull null
                // 一次性锁不能永久改变用户的周期计划；此类冲突交给调用方明确处理。
                if (requestedPlan.isOneTimeFocus() || storedPlan.isOneTimeFocus()) {
                    return@mapNotNull null
                }
                if (!requestedPlan.type.isOppositeDeviceLockType(storedPlan.type)) {
                    return@mapNotNull null
                }
                storedId
            }
            .toCollection(linkedSetOf())

        return storedIdsToDisable.mapNotNull { planId ->
            val storedPlan = storedById.getValue(planId)
            if (storedPlan.updatedAtEpochMillis == Long.MAX_VALUE) return@mapNotNull null
            storedPlan.copy(
                enabled = false,
                updatedAtEpochMillis = maxOf(
                    nowEpochMillis,
                    storedPlan.updatedAtEpochMillis + 1L
                )
            )
        }
    }

    private fun SupervisionPlanType.isOppositeDeviceLockType(
        other: SupervisionPlanType
    ): Boolean =
        (this == SupervisionPlanType.GLOBAL && other == SupervisionPlanType.FOCUS) ||
            (this == SupervisionPlanType.FOCUS && other == SupervisionPlanType.GLOBAL)

    private fun PlanLoadResult.toWriteFailure(): PlanWriteResult = when (this) {
        is PlanLoadResult.CorruptData -> PlanWriteResult.CorruptData(failures)
        PlanLoadResult.StorageFailure -> PlanWriteResult.StorageFailure
        is PlanLoadResult.Success -> error("成功结果不能转换为写入失败")
    }

    companion object {
        @Volatile
        private var instance: SupervisionPlanRepository? = null

        fun getInstance(context: Context): SupervisionPlanRepository =
            instance ?: synchronized(this) {
                instance ?: SupervisionPlanRepository(
                    ControlFreeDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }

        internal fun createForTest(database: ControlFreeDatabase): SupervisionPlanRepository =
            SupervisionPlanRepository(database)

        internal fun createForDatabase(database: ControlFreeDatabase): SupervisionPlanRepository =
            SupervisionPlanRepository(database)
    }
}

internal fun oneTimeFocusPlanId(todoId: String, window: OneTimeFocusWindow): String =
    "todo-focus-once:" + UUID.nameUUIDFromBytes(
        "$todoId:${window.startEpochMillis}:${window.endEpochMillis}"
            .toByteArray(StandardCharsets.UTF_8)
    ).toString()
