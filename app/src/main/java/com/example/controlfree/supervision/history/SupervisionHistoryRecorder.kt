package com.example.controlfree.supervision.history

import android.content.Context
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.supervision.app.AppSupervisionRule
import com.example.controlfree.supervision.persistence.SupervisionHistoryRepository
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.runBlocking

/**
 * 将服务主线程上的历史事件串行写入 Room。历史采集失败只影响统计，不得阻断监督状态机。
 * 单例执行器跨 Service 重建保持写入顺序，避免“旧会话结束”晚于“新会话开始”。
 */
class SupervisionHistoryRecorder private constructor(context: Context) {
    private val repository = SupervisionHistoryRepository.getInstance(context.applicationContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ControlFree-SupervisionHistory").apply { isDaemon = true }
    }

    fun startManual(
        usageMinutes: Int,
        lockMinutes: Int,
        sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION,
        displayName: String? = null
    ) {
        val descriptor = manualDescriptor(
            identityKey = "manual:${UUID.randomUUID()}",
            usageMinutes = usageMinutes,
            lockMinutes = lockMinutes,
            sessionMode = sessionMode,
            displayName = displayName
        )
        submit { repository.startOrReplaceGlobal(descriptor, System.currentTimeMillis()) }
    }

    fun restoreManual(
        usageMinutes: Int,
        lockMinutes: Int,
        sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION,
        displayName: String? = null
    ) {
        val fallback = manualDescriptor(
            identityKey = "manual-recovered:${UUID.randomUUID()}",
            usageMinutes = usageMinutes,
            lockMinutes = lockMinutes,
            sessionMode = sessionMode,
            displayName = displayName
        )
        submit { repository.ensureGlobalPresent(fallback, System.currentTimeMillis()) }
    }

    fun startOrRestoreScheduled(
        planId: String,
        planUpdatedAtEpochMillis: Long,
        activeUntilEpochMillis: Long,
        planName: String,
        usageMinutes: Int,
        lockMinutes: Int,
        sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION
    ) {
        val descriptor = scheduledDescriptor(
            planId = planId,
            planUpdatedAtEpochMillis = planUpdatedAtEpochMillis,
            activeUntilEpochMillis = activeUntilEpochMillis,
            planName = planName,
            usageMinutes = usageMinutes,
            lockMinutes = lockMinutes,
            sessionMode = sessionMode
        )
        submit { repository.startOrReplaceGlobal(descriptor, System.currentTimeMillis()) }
    }

    fun closeGlobal(reason: SupervisionSessionEndReason) {
        submit { repository.closeGlobal(reason, System.currentTimeMillis()) }
    }

    fun reconcileApps(
        rules: Collection<AppSupervisionRule>,
        removedReason: SupervisionSessionEndReason = SupervisionSessionEndReason.COMPLETED
    ) {
        val descriptors = rules.map(::appDescriptor)
        submit {
            repository.reconcileApps(
                descriptors = descriptors,
                nowEpochMillis = System.currentTimeMillis(),
                removedReason = removedReason
            )
        }
    }

    private fun submit(operation: suspend () -> Unit) {
        try {
            executor.execute {
                runBlocking {
                    try {
                        operation()
                    } catch (_: RuntimeException) {
                        // 历史记录是旁路能力，数据库异常不能改变监督或解锁结果。
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // 进程退出阶段允许丢弃尚未开始的统计写入。
        }
    }

    companion object {
        @Volatile
        private var instance: SupervisionHistoryRecorder? = null

        fun getInstance(context: Context): SupervisionHistoryRecorder =
            instance ?: synchronized(this) {
                instance ?: SupervisionHistoryRecorder(context.applicationContext)
                    .also { instance = it }
            }
    }
}

internal fun manualDescriptor(
    identityKey: String,
    usageMinutes: Int,
    lockMinutes: Int,
    sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION,
    displayName: String? = null
) = SupervisionSessionDescriptor(
    identityKey = identityKey,
    runtimeSlot = GLOBAL_RUNTIME_SLOT,
    kind = when (sessionMode) {
        MonitorSessionMode.SUPERVISION -> SupervisionSessionKind.MANUAL_GLOBAL
        MonitorSessionMode.FOCUS -> SupervisionSessionKind.MANUAL_FOCUS
    },
    displayName = displayName
        ?.trim()
        ?.takeIf { value ->
            sessionMode == MonitorSessionMode.FOCUS &&
                value.isNotEmpty() &&
                value.length <= MAX_MANUAL_DISPLAY_NAME_LENGTH
        }
        ?: when (sessionMode) {
            MonitorSessionMode.SUPERVISION -> "即时监督"
            MonitorSessionMode.FOCUS -> "即时专注"
        },
    planId = null,
    packageName = null,
    usageMinutes = usageMinutes,
    lockMinutes = lockMinutes
)

private const val MAX_MANUAL_DISPLAY_NAME_LENGTH = 80

internal fun scheduledDescriptor(
    planId: String,
    planUpdatedAtEpochMillis: Long,
    activeUntilEpochMillis: Long,
    planName: String,
    usageMinutes: Int,
    lockMinutes: Int,
    sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION
) = SupervisionSessionDescriptor(
    identityKey = stableIdentity(
        if (sessionMode == MonitorSessionMode.FOCUS) "scheduled-focus" else "scheduled",
        planId,
        planUpdatedAtEpochMillis.toString(),
        activeUntilEpochMillis.toString()
    ),
    runtimeSlot = GLOBAL_RUNTIME_SLOT,
    kind = when (sessionMode) {
        MonitorSessionMode.SUPERVISION -> SupervisionSessionKind.SCHEDULED_GLOBAL
        MonitorSessionMode.FOCUS -> SupervisionSessionKind.SCHEDULED_FOCUS
    },
    displayName = planName.trim().ifEmpty { planId },
    planId = planId,
    packageName = null,
    usageMinutes = usageMinutes,
    lockMinutes = lockMinutes
)

internal fun appDescriptor(rule: AppSupervisionRule) = SupervisionSessionDescriptor(
    identityKey = stableIdentity(
        "app",
        rule.planId,
        rule.planUpdatedAtEpochMillis.toString(),
        rule.occurrenceEndEpochMillis.toString()
    ),
    runtimeSlot = appRuntimeSlot(rule.planId),
    kind = SupervisionSessionKind.APP,
    displayName = rule.planName,
    planId = rule.planId,
    packageName = rule.packageName,
    usageMinutes = (rule.usageAllowanceMillis / 60_000L).toInt(),
    lockMinutes = (rule.restDurationMillis / 60_000L).toInt()
)

private fun stableIdentity(prefix: String, vararg parts: String): String {
    val digestInput = buildString {
        parts.forEach { part -> append(part.length).append(':').append(part).append('|') }
    }.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(digestInput)
        .joinToString("") { byte -> "%02x".format(byte) }
    return "$prefix:$digest"
}
