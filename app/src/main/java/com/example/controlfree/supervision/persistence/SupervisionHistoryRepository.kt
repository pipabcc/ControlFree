package com.example.controlfree.supervision.persistence

import android.content.Context
import androidx.room.withTransaction
import com.example.controlfree.supervision.history.GLOBAL_RUNTIME_SLOT
import com.example.controlfree.supervision.history.SupervisionSessionDescriptor
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.history.SupervisionSessionRecord
import com.example.controlfree.supervision.history.SupervisionHistoryEventRecord
import com.example.controlfree.supervision.history.SupervisionHistoryEventType
import com.example.controlfree.supervision.history.SupervisionRecoveryStatus
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SupervisionHistoryRepository private constructor(
    private val database: ControlFreeDatabase
) {
    private val dao = database.supervisionHistoryDao()

    /** 每个进程生命周期内至多执行一次的保留期清理开关。 */
    private val prunePending = AtomicBoolean(true)

    suspend fun startOrReplaceGlobal(
        descriptor: SupervisionSessionDescriptor,
        nowEpochMillis: Long
    ): SupervisionSessionRecord {
        require(descriptor.runtimeSlot == GLOBAL_RUNTIME_SLOT)
        return database.withTransaction {
            val current = dao.getActiveBySlot(GLOBAL_RUNTIME_SLOT)
            if (current != null && current.identityKey == descriptor.identityKey) {
                return@withTransaction current.toDomain()
            }
            current?.let { entity -> close(entity, nowEpochMillis, SupervisionSessionEndReason.REPLACED) }
            insert(descriptor, nowEpochMillis).toDomain()
        }
    }

    /** 恢复普通监督时优先沿用现有活动记录，防止进程重启切割成多个会话。 */
    suspend fun ensureGlobalPresent(
        fallbackDescriptor: SupervisionSessionDescriptor,
        nowEpochMillis: Long
    ): SupervisionSessionRecord {
        require(fallbackDescriptor.runtimeSlot == GLOBAL_RUNTIME_SLOT)
        return database.withTransaction {
            val current = dao.getActiveBySlot(GLOBAL_RUNTIME_SLOT)
            if (current != null && current.sessionKind == fallbackDescriptor.kind.storedValue) {
                return@withTransaction current.toDomain()
            }
            current?.let { entity ->
                close(entity, nowEpochMillis, SupervisionSessionEndReason.REPLACED)
            }
            insert(fallbackDescriptor, nowEpochMillis).toDomain()
        }
    }

    suspend fun closeGlobal(
        reason: SupervisionSessionEndReason,
        nowEpochMillis: Long
    ): Boolean = database.withTransaction {
        val current = dao.getActiveBySlot(GLOBAL_RUNTIME_SLOT) ?: return@withTransaction false
        close(current, nowEpochMillis, reason)
        true
    }

    suspend fun reconcileApps(
        descriptors: Collection<SupervisionSessionDescriptor>,
        nowEpochMillis: Long,
        removedReason: SupervisionSessionEndReason = SupervisionSessionEndReason.COMPLETED
    ): List<SupervisionSessionRecord> {
        require(descriptors.all { descriptor -> descriptor.kind == SupervisionSessionKind.APP })
        val desiredBySlot = descriptors.associateBy(SupervisionSessionDescriptor::runtimeSlot)
        require(desiredBySlot.size == descriptors.size) { "App 历史运行槽位重复" }
        return database.withTransaction {
            val currentBySlot = dao.getActiveApps().associateBy(SupervisionSessionEntity::runtimeSlot)
            currentBySlot.forEach { (slot, current) ->
                val desired = desiredBySlot[slot]
                when {
                    desired == null -> close(current, nowEpochMillis, removedReason)
                    current.identityKey != desired.identityKey ->
                        close(current, nowEpochMillis, SupervisionSessionEndReason.REPLACED)
                }
            }
            desiredBySlot.values.map { descriptor ->
                val current = dao.getActiveBySlot(descriptor.runtimeSlot)
                if (current != null && current.identityKey == descriptor.identityKey) {
                    current.toDomain()
                } else {
                    insert(descriptor, nowEpochMillis).toDomain()
                }
            }
        }
    }

    fun observeRecent(limit: Int = 100): Flow<List<SupervisionSessionRecord>> {
        require(limit in 1..1_000) { "历史读取数量无效" }
        return dao.observeRecent(limit).map { entities -> entities.map(SupervisionSessionEntity::toDomain) }
    }

    suspend fun getAll(): List<SupervisionSessionRecord> =
        dao.getAll().map(SupervisionSessionEntity::toDomain)

    fun observeRecentEvents(limit: Int = 100): Flow<List<SupervisionHistoryEventRecord>> {
        require(limit in 1..1_000) { "历史事件读取数量无效" }
        return dao.observeRecentEvents(limit).map { entities ->
            entities.map(SupervisionHistoryEventEntity::toDomain)
        }
    }

    suspend fun getAllEvents(): List<SupervisionHistoryEventRecord> =
        dao.getAllEvents().map(SupervisionHistoryEventEntity::toDomain)

    suspend fun recordDeviceBoot(
        bootCount: Int,
        occurredAtEpochMillis: Long,
        receivedAtEpochMillis: Long,
        recoveryExpected: Boolean
    ): SupervisionHistoryEventRecord {
        // 开机时顺带做一次保留期清理（每进程至多一次）。
        pruneExpired(receivedAtEpochMillis)
        return database.withTransaction {
        val safeReceived = receivedAtEpochMillis.coerceAtLeast(0L)
        val safeOccurred = occurredAtEpochMillis.coerceIn(0L, safeReceived)
        val bootInstanceKey = deviceBootInstanceKey(bootCount, safeOccurred)
        val existing = dao.getEventByBootInstance(bootInstanceKey)
        if (existing != null) {
            if (
                recoveryExpected &&
                existing.recoveryStatus ==
                SupervisionRecoveryStatus.NO_ACTIVE_SUPERVISION.storedValue
            ) {
                dao.updateBootRecoveryStatus(
                    bootInstanceKey = bootInstanceKey,
                    recoveryStatus = SupervisionRecoveryStatus.RECOVERY_REQUESTED.storedValue,
                    updatedAtEpochMillis = safeReceived.coerceAtLeast(
                        existing.receivedAtEpochMillis
                    )
                )
            }
            return@withTransaction requireNotNull(
                dao.getEventByBootInstance(bootInstanceKey)
            ).toDomain()
        }

        val active = dao.getActiveBySlot(GLOBAL_RUNTIME_SLOT)
            ?: dao.getActiveApps().firstOrNull()
        val status = if (recoveryExpected || active != null) {
            SupervisionRecoveryStatus.RECOVERY_REQUESTED
        } else {
            SupervisionRecoveryStatus.NO_ACTIVE_SUPERVISION
        }
        val entity = SupervisionHistoryEventEntity(
            eventId = UUID.randomUUID().toString(),
            eventType = SupervisionHistoryEventType.DEVICE_BOOT.storedValue,
            bootInstanceKey = bootInstanceKey,
            bootCount = bootCount.takeIf { it >= 0 },
            occurredAtEpochMillis = safeOccurred,
            receivedAtEpochMillis = safeReceived,
            sessionId = active?.sessionId,
            runtimeSlot = active?.runtimeSlot,
            recoveryStatus = status.storedValue,
            updatedAtEpochMillis = safeReceived
        )
        dao.insertEvent(entity)
        requireNotNull(dao.getEventByBootInstance(bootInstanceKey)).toDomain()
        }
    }

    suspend fun markDeviceBootRecoveryStatus(
        bootCount: Int,
        status: SupervisionRecoveryStatus,
        nowEpochMillis: Long
    ): Boolean {
        if (bootCount < 0 || status == SupervisionRecoveryStatus.NO_ACTIVE_SUPERVISION) {
            return false
        }
        return dao.updateBootRecoveryStatus(
            bootInstanceKey = deviceBootInstanceKey(bootCount, 0L),
            recoveryStatus = status.storedValue,
            updatedAtEpochMillis = nowEpochMillis.coerceAtLeast(0L)
        ) > 0
    }

    suspend fun getOverlapping(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long,
        activeEndEpochMillis: Long = System.currentTimeMillis()
    ): List<SupervisionSessionRecord> {
        require(startEpochMillis >= 0L && endExclusiveEpochMillis > startEpochMillis) {
            "历史查询范围无效"
        }
        require(activeEndEpochMillis >= 0L) { "活动历史截止时间无效" }
        return dao.getOverlapping(
            startEpochMillis,
            endExclusiveEpochMillis,
            activeEndEpochMillis.coerceAtMost(endExclusiveEpochMillis)
        ).map(SupervisionSessionEntity::toDomain)
    }

    /** 保留期清理：删除过期会话与事件；每个进程生命周期内至多执行一次。 */
    suspend fun pruneExpired(nowEpochMillis: Long) {
        if (!prunePending.getAndSet(false)) return
        val cutoff = nowEpochMillis.coerceAtLeast(0L) - HISTORY_RETENTION_MILLIS
        database.withTransaction {
            dao.deleteSessionsBefore(cutoff)
            dao.deleteEventsBefore(cutoff)
        }
    }

    private suspend fun insert(
        descriptor: SupervisionSessionDescriptor,
        nowEpochMillis: Long
    ): SupervisionSessionEntity {
        val safeNow = nowEpochMillis.coerceAtLeast(0L)
        return descriptor.toEntity(
            sessionId = UUID.randomUUID().toString(),
            startedAtEpochMillis = safeNow
        ).also { dao.insert(it) }
    }

    private suspend fun close(
        entity: SupervisionSessionEntity,
        nowEpochMillis: Long,
        reason: SupervisionSessionEndReason
    ) {
        val safeEnd = nowEpochMillis.coerceAtLeast(entity.startedAtEpochMillis)
        check(dao.closeActive(entity.sessionId, safeEnd, reason.storedValue) == 1) {
            "活动历史会话已发生并发变化"
        }
    }

    companion object {
        /** 历史保留 90 天，防止监督会话/事件表无限增长。 */
        private const val HISTORY_RETENTION_MILLIS = 90L * 24L * 60L * 60L * 1_000L

        @Volatile
        private var instance: SupervisionHistoryRepository? = null

        fun getInstance(context: Context): SupervisionHistoryRepository =
            instance ?: synchronized(this) {
                instance ?: SupervisionHistoryRepository(
                    ControlFreeDatabase.getInstance(context.applicationContext)
                ).also { instance = it }
            }

        internal fun createForTest(database: ControlFreeDatabase) =
            SupervisionHistoryRepository(database)
    }
}

private fun deviceBootInstanceKey(bootCount: Int, occurredAtEpochMillis: Long): String =
    if (bootCount >= 0) {
        "device-boot:$bootCount"
    } else {
        "device-boot:estimated:${occurredAtEpochMillis.coerceAtLeast(0L) / 60_000L}"
    }

private fun SupervisionSessionDescriptor.toEntity(
    sessionId: String,
    startedAtEpochMillis: Long
) = SupervisionSessionEntity(
    sessionId = sessionId,
    identityKey = identityKey,
    runtimeSlot = runtimeSlot,
    sessionKind = kind.storedValue,
    displayName = displayName,
    planId = planId,
    packageName = packageName,
    usageMinutes = usageMinutes,
    lockMinutes = lockMinutes,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = null,
    endReason = null,
    updatedAtEpochMillis = startedAtEpochMillis
)

private fun SupervisionSessionEntity.toDomain() = SupervisionSessionRecord(
    sessionId = sessionId,
    identityKey = identityKey,
    runtimeSlot = runtimeSlot,
    kind = requireNotNull(SupervisionSessionKind.fromStoredValue(sessionKind)) {
        "历史会话类型无效"
    },
    displayName = displayName,
    planId = planId,
    packageName = packageName,
    usageMinutes = usageMinutes,
    lockMinutes = lockMinutes,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    endReason = endReason?.let { stored ->
        requireNotNull(SupervisionSessionEndReason.fromStoredValue(stored)) {
            "历史结束原因无效"
        }
    },
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun SupervisionHistoryEventEntity.toDomain() = SupervisionHistoryEventRecord(
    eventId = eventId,
    eventType = requireNotNull(SupervisionHistoryEventType.fromStoredValue(eventType)) {
        "历史事件类型无效"
    },
    bootInstanceKey = bootInstanceKey,
    bootCount = bootCount,
    occurredAtEpochMillis = occurredAtEpochMillis,
    receivedAtEpochMillis = receivedAtEpochMillis,
    sessionId = sessionId,
    runtimeSlot = runtimeSlot,
    recoveryStatus = requireNotNull(
        SupervisionRecoveryStatus.fromStoredValue(recoveryStatus)
    ) {
        "监督恢复状态无效"
    },
    updatedAtEpochMillis = updatedAtEpochMillis
)
