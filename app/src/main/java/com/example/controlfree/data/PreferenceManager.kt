package com.example.controlfree.data

import android.content.Context
import android.content.SharedPreferences
import com.example.controlfree.MonitorCycleSnapshot
import com.example.controlfree.MonitorPauseState
import com.example.controlfree.MonitorPhase
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.boot.BootRecoveryHintStore
import com.example.controlfree.supervision.runtime.ScheduledMonitorOwner
import com.example.controlfree.supervision.runtime.ScheduledOccurrenceSuppression
import com.example.controlfree.supervision.runtime.ScheduledOwnerReadResult
import java.security.MessageDigest

enum class MonitorProgressReadStatus {
    AVAILABLE,
    INACTIVE,
    MISSING,
    CORRUPTED,
    READ_FAILED
}

data class MonitorProgressReadResult(
    val status: MonitorProgressReadStatus,
    val snapshot: MonitorCycleSnapshot? = null,
    val pauseState: MonitorPauseState? = null
)

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val bootRecoveryHintStore = BootRecoveryHintStore(context)

    fun isBackgroundPopupManuallyConfirmed(): Boolean = try {
        prefs.getBoolean("key_background_popup_manually_confirmed", false)
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun setBackgroundPopupManuallyConfirmed(confirmed: Boolean): Boolean = try {
        prefs.edit().putBoolean("key_background_popup_manually_confirmed", confirmed).commit()
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun isBatteryUnrestrictedManuallyConfirmed(): Boolean = try {
        prefs.getBoolean("key_battery_unrestricted_manually_confirmed", false)
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun setBatteryUnrestrictedManuallyConfirmed(confirmed: Boolean): Boolean = try {
        prefs.edit().putBoolean("key_battery_unrestricted_manually_confirmed", confirmed).commit()
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun isDarkThemeEnabled(): Boolean = try {
        prefs.getBoolean(KEY_DARK_THEME_ENABLED, false)
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun setDarkThemeEnabled(enabled: Boolean): Boolean = try {
        prefs.edit().putBoolean(KEY_DARK_THEME_ENABLED, enabled).commit()
    } catch (_: java.lang.RuntimeException) {
        false
    }

    fun getUsageTime(): Int = try {
        prefs.getInt(KEY_USAGE_TIME, DEFAULT_USAGE_TIME_MINUTES)
    } catch (_: RuntimeException) {
        DEFAULT_USAGE_TIME_MINUTES
    }

    fun setUsageTime(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_USAGE_TIME, minutes).apply()
        } catch (_: RuntimeException) {
            // 启动监督时的权威快照写入会再次暴露持久化错误。
        }
    }

    fun getLockTime(): Int = try {
        prefs.getInt(KEY_LOCK_TIME, DEFAULT_LOCK_TIME_MINUTES)
    } catch (_: RuntimeException) {
        DEFAULT_LOCK_TIME_MINUTES
    }

    fun setLockTime(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_LOCK_TIME, minutes).apply()
        } catch (_: RuntimeException) {
            // 启动监督时的权威快照写入会再次暴露持久化错误。
        }
    }

    fun getFocusLockTime(): Int = try {
        prefs.getInt(KEY_FOCUS_LOCK_TIME, DEFAULT_FOCUS_LOCK_TIME_MINUTES)
    } catch (_: RuntimeException) {
        DEFAULT_FOCUS_LOCK_TIME_MINUTES
    }

    fun setFocusLockTime(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_FOCUS_LOCK_TIME, minutes).apply()
        } catch (_: RuntimeException) {
            // 启动专注时的权威快照写入会再次暴露持久化错误。
        }
    }

    fun getFocusPlayTime(): Int = try {
        prefs.getInt(KEY_FOCUS_PLAY_TIME, DEFAULT_FOCUS_PLAY_TIME_MINUTES)
    } catch (_: RuntimeException) {
        DEFAULT_FOCUS_PLAY_TIME_MINUTES
    }

    fun setFocusPlayTime(minutes: Int) {
        try {
            prefs.edit().putInt(KEY_FOCUS_PLAY_TIME, minutes).apply()
        } catch (_: RuntimeException) {
            // 启动专注时的权威快照写入会再次暴露持久化错误。
        }
    }

    fun getMonitorSessionMode(): MonitorSessionMode = try {
        MonitorSessionMode.fromStoredValue(prefs.getString(KEY_MONITOR_SESSION_MODE, null))
    } catch (_: RuntimeException) {
        MonitorSessionMode.SUPERVISION
    }

    fun isMonitorActive(): Boolean = try {
        prefs.getBoolean(KEY_MONITOR_ACTIVE, false)
    } catch (_: RuntimeException) {
        true
    }

    fun getMonitorSchemaVersion(): Int = try {
        prefs.getInt(KEY_MONITOR_SCHEMA, 0)
    } catch (_: RuntimeException) {
        Int.MAX_VALUE
    }

    fun getStoredMonitorPhase(): MonitorPhase? = try {
        prefs.getString(KEY_MONITOR_PHASE, null)
            ?.let { value -> MonitorPhase.entries.firstOrNull { it.storedValue == value } }
    } catch (_: RuntimeException) {
        null
    }

    fun getLegacyMonitorPhase(): MonitorPhase? = try {
        prefs.getString(KEY_LEGACY_MONITOR_STATE, null)
            ?.let { value -> MonitorPhase.entries.firstOrNull { it.storedValue == value } }
    } catch (_: RuntimeException) {
        null
    }

    /** 仅返回 schema 1 使用的墙钟截止时间，不在持久化层执行迁移。 */
    fun getLegacyPhaseEndsAtMillis(): Long = try {
        prefs.getLong(KEY_LEGACY_PHASE_ENDS_AT, 0L)
    } catch (_: RuntimeException) {
        0L
    }

    fun loadMonitorProgress(): MonitorCycleSnapshot? = inspectMonitorProgress().snapshot

    fun inspectMonitorProgress(): MonitorProgressReadResult = try {
        inspectMonitorProgressUnsafe()
    } catch (_: RuntimeException) {
        MonitorProgressReadResult(MonitorProgressReadStatus.READ_FAILED)
    }

    private fun inspectMonitorProgressUnsafe(): MonitorProgressReadResult {
        if (!prefs.getBoolean(KEY_MONITOR_ACTIVE, false)) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.INACTIVE)
        }
        val schemaVersion = prefs.getInt(KEY_MONITOR_SCHEMA, 0)
        if (schemaVersion == 0) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
        }
        if (schemaVersion !in SUPPORTED_MONITOR_SCHEMAS) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
        }

        val requiredKeys = arrayOf(
            KEY_MONITOR_PHASE,
            KEY_REMAINING_MILLIS,
            KEY_CHECKPOINT_ELAPSED_MILLIS,
            KEY_BOOT_COUNT,
            KEY_INTERACTIVE
        )
        if (requiredKeys.any { !prefs.contains(it) }) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
        }
        if (
            schemaVersion >= MONITOR_SCHEMA_VERSION &&
            !prefs.contains(KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS)
        ) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
        }

        val phase = getStoredMonitorPhase()
            ?: return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
        val remainingMillis = prefs.getLong(KEY_REMAINING_MILLIS, -1L)
        val checkpointElapsedMillis = prefs.getLong(KEY_CHECKPOINT_ELAPSED_MILLIS, -1L)
        val bootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val recoveryRemainderMillis = if (schemaVersion >= MONITOR_SCHEMA_VERSION) {
            prefs.getLong(KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS, -1L)
        } else {
            0L
        }
        if (
            remainingMillis < 0L ||
            checkpointElapsedMillis < 0L ||
            bootCount < UNKNOWN_BOOT_COUNT ||
            recoveryRemainderMillis !in 0L until SCREEN_OFF_RECOVERY_INTERVAL_MILLIS
        ) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
        }

        val snapshot = MonitorCycleSnapshot(
            phase = phase,
            remainingMillis = remainingMillis,
            checkpointElapsedMillis = checkpointElapsedMillis,
            bootCount = bootCount,
            isInteractive = prefs.getBoolean(KEY_INTERACTIVE, true),
            screenOffRecoveryRemainderMillis = recoveryRemainderMillis
        )
        val pauseState = if (schemaVersion >= PAUSE_MONITOR_SCHEMA_VERSION) {
            readPauseState() ?: return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
        } else {
            null
        }
        if (pauseState?.isActive == true && snapshot.phase != MonitorPhase.LOCK) {
            return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
        }
        if (schemaVersion == MONITOR_SCHEMA_VERSION) {
            if (!prefs.contains(KEY_SNAPSHOT_CHECKSUM)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
            }
            val storedChecksum = prefs.getString(KEY_SNAPSHOT_CHECKSUM, null)
                ?: return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            if (!MonitorSnapshotChecksum.matches(snapshot, pauseState, storedChecksum)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            }
        } else if (schemaVersion == CHECKSUM_MONITOR_SCHEMA_VERSION) {
            if (!prefs.contains(KEY_SNAPSHOT_CHECKSUM)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
            }
            val storedChecksum = prefs.getString(KEY_SNAPSHOT_CHECKSUM, null)
                ?: return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            if (!MonitorSnapshotChecksum.matchesSchema3(snapshot, storedChecksum)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            }
        } else if (schemaVersion == PAUSE_MONITOR_SCHEMA_VERSION) {
            if (!prefs.contains(KEY_SNAPSHOT_CHECKSUM)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.MISSING)
            }
            val storedChecksum = prefs.getString(KEY_SNAPSHOT_CHECKSUM, null)
                ?: return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            if (!MonitorSnapshotChecksum.matchesSchema4(snapshot, pauseState, storedChecksum)) {
                return MonitorProgressReadResult(MonitorProgressReadStatus.CORRUPTED)
            }
        }
        return MonitorProgressReadResult(
            status = MonitorProgressReadStatus.AVAILABLE,
            snapshot = snapshot,
            pauseState = pauseState
        )
    }

    private fun readPauseState(): MonitorPauseState? {
        val requiredKeys = arrayOf(KEY_PAUSE_ACCUMULATED_MILLIS, KEY_PAUSE_ACTIVE)
        if (requiredKeys.any { !prefs.contains(it) }) return null
        val isActive = prefs.getBoolean(KEY_PAUSE_ACTIVE, false)
        val accumulatedMillis = prefs.getLong(KEY_PAUSE_ACCUMULATED_MILLIS, -1L)
        val state = if (isActive) {
            val activeKeys = arrayOf(
                KEY_PAUSE_STARTED_AT_EPOCH_MILLIS,
                KEY_PAUSE_UNTIL_EPOCH_MILLIS,
                KEY_PAUSE_DEADLINE_ELAPSED_MILLIS,
                KEY_PAUSE_BOOT_COUNT
            )
            if (activeKeys.any { !prefs.contains(it) }) return null
            MonitorPauseState(
                accumulatedPauseMillis = accumulatedMillis,
                startedAtEpochMillis = prefs.getLong(KEY_PAUSE_STARTED_AT_EPOCH_MILLIS, -1L),
                untilEpochMillis = prefs.getLong(KEY_PAUSE_UNTIL_EPOCH_MILLIS, -1L),
                deadlineElapsedMillis = prefs.getLong(KEY_PAUSE_DEADLINE_ELAPSED_MILLIS, -1L),
                bootCount = prefs.getInt(KEY_PAUSE_BOOT_COUNT, Int.MIN_VALUE)
            )
        } else {
            MonitorPauseState(accumulatedPauseMillis = accumulatedMillis)
        }
        return state.takeIf(MonitorPauseState::isStructurallyValid)
    }

    /**
     * 一个同步事务内保存完整快照，避免进程退出时留下跨字段的不一致状态。
     */
    fun saveMonitorProgress(
        snapshot: MonitorCycleSnapshot,
        pauseState: MonitorPauseState? = null,
        appliedGrowthOrderIds: Set<String> = emptySet()
    ): Boolean = saveMonitorProgressInternal(
        snapshot,
        pauseState,
        session = null,
        appliedGrowthOrderIds = appliedGrowthOrderIds
    )

    /** 在同一个同步事务中保存快照、计时参数与定时任务来源，避免替换任务时跨字段撕裂。 */
    fun saveMonitorProgress(
        snapshot: MonitorCycleSnapshot,
        scheduledOwner: ScheduledMonitorOwner?,
        usageMinutes: Int,
        lockMinutes: Int,
        sessionMode: MonitorSessionMode = MonitorSessionMode.SUPERVISION,
        pauseState: MonitorPauseState? = null,
        appliedGrowthOrderIds: Set<String> = emptySet(),
        focusTaskTitle: String? = null,
        focusSourceTodoId: String? = null
    ): Boolean = saveMonitorProgressInternal(
        snapshot,
        pauseState,
        MonitorSessionMetadata(
            sessionMode,
            scheduledOwner,
            usageMinutes,
            lockMinutes,
            focusTaskTitle,
            focusSourceTodoId
        ),
        appliedGrowthOrderIds
    )

    @Synchronized
    private fun saveMonitorProgressInternal(
        snapshot: MonitorCycleSnapshot,
        pauseState: MonitorPauseState?,
        session: MonitorSessionMetadata?,
        appliedGrowthOrderIds: Set<String>
    ): Boolean {
        val persistedAppliedOrderIds = getAppliedGrowthOrderIds()
        val mergedAppliedOrderIds = persistedAppliedOrderIds + appliedGrowthOrderIds
        val isStructurallyValid =
            snapshot.remainingMillis >= 0L &&
                snapshot.checkpointElapsedMillis >= 0L &&
                snapshot.bootCount >= UNKNOWN_BOOT_COUNT &&
                snapshot.screenOffRecoveryRemainderMillis in
                    0L until SCREEN_OFF_RECOVERY_INTERVAL_MILLIS &&
                (pauseState == null || pauseState.isStructurallyValid) &&
                (pauseState?.isActive != true || snapshot.phase == MonitorPhase.LOCK) &&
                (session == null || session.isValid) &&
                mergedAppliedOrderIds.size <= MAX_APPLIED_GROWTH_ORDER_IDS &&
                mergedAppliedOrderIds.all(::isValidGrowthOrderId)
        if (!isStructurallyValid) return false
        // 先写失败安全提示。主快照提交失败时最多造成下次开机多做一次权威状态核对。
        bootRecoveryHintStore.setMonitorRecoveryRequired(true)
        return try {
            val editor = prefs.edit()
                .putBoolean(KEY_MONITOR_ACTIVE, true)
                .putInt(KEY_MONITOR_SCHEMA, MONITOR_SCHEMA_VERSION)
                .putString(KEY_MONITOR_PHASE, snapshot.phase.storedValue)
                .putLong(KEY_REMAINING_MILLIS, snapshot.remainingMillis)
                .putLong(KEY_CHECKPOINT_ELAPSED_MILLIS, snapshot.checkpointElapsedMillis)
                .putInt(KEY_BOOT_COUNT, snapshot.bootCount)
                .putBoolean(KEY_INTERACTIVE, snapshot.isInteractive)
                .putLong(
                    KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS,
                    snapshot.screenOffRecoveryRemainderMillis
                )
                .putString(
                    KEY_SNAPSHOT_CHECKSUM,
                    MonitorSnapshotChecksum.create(snapshot, pauseState)
                )
                .putLong(
                    KEY_PAUSE_ACCUMULATED_MILLIS,
                    pauseState?.accumulatedPauseMillis ?: 0L
                )
                .putBoolean(KEY_PAUSE_ACTIVE, pauseState?.isActive == true)
                .remove(KEY_LEGACY_MONITOR_STATE)
                .remove(KEY_LEGACY_PHASE_ENDS_AT)
            if (mergedAppliedOrderIds.isNotEmpty()) {
                editor.putStringSet(
                    KEY_APPLIED_GROWTH_ORDER_IDS,
                    mergedAppliedOrderIds.toSet()
                )
            }
            if (pauseState?.isActive == true) {
                editor
                    .putLong(KEY_PAUSE_STARTED_AT_EPOCH_MILLIS, pauseState.startedAtEpochMillis)
                    .putLong(KEY_PAUSE_UNTIL_EPOCH_MILLIS, pauseState.untilEpochMillis)
                    .putLong(KEY_PAUSE_DEADLINE_ELAPSED_MILLIS, pauseState.deadlineElapsedMillis)
                    .putInt(KEY_PAUSE_BOOT_COUNT, pauseState.bootCount)
            } else {
                editor
                    .remove(KEY_PAUSE_STARTED_AT_EPOCH_MILLIS)
                    .remove(KEY_PAUSE_UNTIL_EPOCH_MILLIS)
                    .remove(KEY_PAUSE_DEADLINE_ELAPSED_MILLIS)
                    .remove(KEY_PAUSE_BOOT_COUNT)
            }
            session?.let { metadata ->
                editor
                    .putString(KEY_MONITOR_SESSION_MODE, metadata.sessionMode.storedValue)
                when (metadata.sessionMode) {
                    MonitorSessionMode.SUPERVISION -> editor
                        .putInt(KEY_USAGE_TIME, metadata.usageMinutes)
                        .putInt(KEY_LOCK_TIME, metadata.lockMinutes)
                        .remove(KEY_ACTIVE_FOCUS_TASK_TITLE)
                        .remove(KEY_ACTIVE_FOCUS_SOURCE_TODO_ID)
                    MonitorSessionMode.FOCUS -> {
                        editor
                            .putInt(KEY_FOCUS_PLAY_TIME, metadata.usageMinutes)
                            .putInt(KEY_FOCUS_LOCK_TIME, metadata.lockMinutes)
                        if (metadata.focusTaskTitle == null) {
                            editor
                                .remove(KEY_ACTIVE_FOCUS_TASK_TITLE)
                                .remove(KEY_ACTIVE_FOCUS_SOURCE_TODO_ID)
                        } else {
                            editor.putString(KEY_ACTIVE_FOCUS_TASK_TITLE, metadata.focusTaskTitle)
                            if (metadata.focusSourceTodoId == null) {
                                editor.remove(KEY_ACTIVE_FOCUS_SOURCE_TODO_ID)
                            } else {
                                editor.putString(
                                    KEY_ACTIVE_FOCUS_SOURCE_TODO_ID,
                                    metadata.focusSourceTodoId
                                )
                            }
                        }
                    }
                }
                val owner = metadata.scheduledOwner
                if (owner == null) {
                    editor
                        .remove(KEY_MONITOR_ORIGIN)
                        .remove(KEY_SCHEDULED_PLAN_ID)
                        .remove(KEY_SCHEDULED_PLAN_UPDATED_AT)
                        .remove(KEY_SCHEDULED_ACTIVE_UNTIL)
                } else {
                    editor
                        .putString(KEY_MONITOR_ORIGIN, MONITOR_ORIGIN_SCHEDULED)
                        .putString(KEY_SCHEDULED_PLAN_ID, owner.planId)
                        .putLong(KEY_SCHEDULED_PLAN_UPDATED_AT, owner.planUpdatedAtEpochMillis)
                        .putLong(KEY_SCHEDULED_ACTIVE_UNTIL, owner.activeUntilEpochMillis)
                }
            }
            editor.commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 已经随监督快照原子落盘、但成长账本尚未确认提交的订单。
     * 该集合独立于监督快照生命周期，清除监督时也必须保留，供下次启动完成对账。
     */
    @Synchronized
    fun getAppliedGrowthOrderIds(): Set<String> = try {
        prefs.getStringSet(KEY_APPLIED_GROWTH_ORDER_IDS, emptySet())
            .orEmpty()
            .filterTo(linkedSetOf(), ::isValidGrowthOrderId)
            .take(MAX_APPLIED_GROWTH_ORDER_IDS)
            .toSet()
    } catch (_: RuntimeException) {
        emptySet()
    }

    @Synchronized
    fun clearAppliedGrowthOrderId(orderId: String): Boolean {
        if (!isValidGrowthOrderId(orderId)) return false
        return try {
            val remaining = getAppliedGrowthOrderIds() - orderId
            val editor = prefs.edit()
            if (remaining.isEmpty()) {
                editor.remove(KEY_APPLIED_GROWTH_ORDER_IDS)
            } else {
                editor.putStringSet(KEY_APPLIED_GROWTH_ORDER_IDS, remaining.toSet())
            }
            editor.commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun clearMonitorProgress(): Boolean {
        val cleared = try {
            prefs.edit()
                .putBoolean(KEY_MONITOR_ACTIVE, false)
                .remove(KEY_MONITOR_SCHEMA)
                .remove(KEY_MONITOR_PHASE)
                .remove(KEY_REMAINING_MILLIS)
                .remove(KEY_CHECKPOINT_ELAPSED_MILLIS)
                .remove(KEY_BOOT_COUNT)
                .remove(KEY_INTERACTIVE)
                .remove(KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS)
                .remove(KEY_SNAPSHOT_CHECKSUM)
                .remove(KEY_PAUSE_ACCUMULATED_MILLIS)
                .remove(KEY_PAUSE_ACTIVE)
                .remove(KEY_PAUSE_STARTED_AT_EPOCH_MILLIS)
                .remove(KEY_PAUSE_UNTIL_EPOCH_MILLIS)
                .remove(KEY_PAUSE_DEADLINE_ELAPSED_MILLIS)
                .remove(KEY_PAUSE_BOOT_COUNT)
                .remove(KEY_LEGACY_MONITOR_STATE)
                .remove(KEY_LEGACY_PHASE_ENDS_AT)
                .remove(KEY_MONITOR_ORIGIN)
                .remove(KEY_MONITOR_SESSION_MODE)
                .remove(KEY_SCHEDULED_PLAN_ID)
                .remove(KEY_SCHEDULED_PLAN_UPDATED_AT)
                .remove(KEY_SCHEDULED_ACTIVE_UNTIL)
                .remove(KEY_ACTIVE_FOCUS_TASK_TITLE)
                .remove(KEY_ACTIVE_FOCUS_SOURCE_TODO_ID)
                .commit()
        } catch (_: RuntimeException) {
            false
        }
        if (cleared) {
            // 权威状态清除成功后再清提示；中途退出只会留下安全的冗余恢复。
            bootRecoveryHintStore.setMonitorRecoveryRequired(false)
        }
        return cleared
    }

    fun inspectScheduledMonitorOwner(): ScheduledOwnerReadResult = try {
        val ownerKeys = arrayOf(
            KEY_MONITOR_ORIGIN,
            KEY_SCHEDULED_PLAN_ID,
            KEY_SCHEDULED_PLAN_UPDATED_AT,
            KEY_SCHEDULED_ACTIVE_UNTIL
        )
        val presentCount = ownerKeys.count(prefs::contains)
        if (presentCount == 0) return ScheduledOwnerReadResult.None
        if (presentCount != ownerKeys.size) return ScheduledOwnerReadResult.Corrupted
        if (prefs.getString(KEY_MONITOR_ORIGIN, null) != MONITOR_ORIGIN_SCHEDULED) {
            return ScheduledOwnerReadResult.Corrupted
        }
        val owner = ScheduledMonitorOwner(
            planId = prefs.getString(KEY_SCHEDULED_PLAN_ID, null).orEmpty(),
            planUpdatedAtEpochMillis = prefs.getLong(KEY_SCHEDULED_PLAN_UPDATED_AT, -1L),
            activeUntilEpochMillis = prefs.getLong(KEY_SCHEDULED_ACTIVE_UNTIL, -1L)
        )
        ScheduledOwnerReadResult.Available(owner)
    } catch (_: RuntimeException) {
        ScheduledOwnerReadResult.Corrupted
    }

    fun clearScheduledMonitorOwner(): Boolean = try {
        prefs.edit()
            .remove(KEY_MONITOR_ORIGIN)
            .remove(KEY_SCHEDULED_PLAN_ID)
            .remove(KEY_SCHEDULED_PLAN_UPDATED_AT)
            .remove(KEY_SCHEDULED_ACTIVE_UNTIL)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    fun getActiveFocusTaskTitle(): String? = readFocusMetadata(
        key = KEY_ACTIVE_FOCUS_TASK_TITLE,
        maxLength = 80
    )

    fun getActiveFocusSourceTodoId(): String? = readFocusMetadata(
        key = KEY_ACTIVE_FOCUS_SOURCE_TODO_ID,
        maxLength = 160
    )

    private fun readFocusMetadata(key: String, maxLength: Int): String? = try {
        prefs.getString(key, null)
            ?.takeIf { value -> value.isNotBlank() && value == value.trim() && value.length <= maxLength }
    } catch (_: RuntimeException) {
        null
    }

    fun suppressScheduledOccurrence(owner: ScheduledMonitorOwner): Boolean = try {
        prefs.edit()
            .putString(KEY_SUPPRESSED_PLAN_ID, owner.planId)
            .putLong(KEY_SUPPRESSED_PLAN_UPDATED_AT, owner.planUpdatedAtEpochMillis)
            .putLong(KEY_SUPPRESSED_UNTIL, owner.activeUntilEpochMillis)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    fun getScheduledOccurrenceSuppression(
        nowEpochMillis: Long
    ): ScheduledOccurrenceSuppression? = try {
        val keys = arrayOf(
            KEY_SUPPRESSED_PLAN_ID,
            KEY_SUPPRESSED_PLAN_UPDATED_AT,
            KEY_SUPPRESSED_UNTIL
        )
        if (keys.any { !prefs.contains(it) }) return null
        val suppression = ScheduledOccurrenceSuppression(
            planId = prefs.getString(KEY_SUPPRESSED_PLAN_ID, null).orEmpty(),
            planUpdatedAtEpochMillis = prefs.getLong(KEY_SUPPRESSED_PLAN_UPDATED_AT, -1L),
            suppressUntilEpochMillis = prefs.getLong(KEY_SUPPRESSED_UNTIL, -1L)
        )
        if (nowEpochMillis >= suppression.suppressUntilEpochMillis) {
            clearScheduledOccurrenceSuppression()
            null
        } else {
            suppression
        }
    } catch (_: RuntimeException) {
        null
    }

    fun clearScheduledOccurrenceSuppression(): Boolean = try {
        prefs.edit()
            .remove(KEY_SUPPRESSED_PLAN_ID)
            .remove(KEY_SUPPRESSED_PLAN_UPDATED_AT)
            .remove(KEY_SUPPRESSED_UNTIL)
            .commit()
    } catch (_: RuntimeException) {
        false
    }

    private data class MonitorSessionMetadata(
        val sessionMode: MonitorSessionMode,
        val scheduledOwner: ScheduledMonitorOwner?,
        val usageMinutes: Int,
        val lockMinutes: Int,
        val focusTaskTitle: String?,
        val focusSourceTodoId: String?
    ) {
        val isValid: Boolean
            get() = usageMinutes in 1..1_440 &&
                lockMinutes in 1..1_440 &&
                (focusTaskTitle == null || focusTaskTitle.isValidMonitorMetadata(80)) &&
                (focusSourceTodoId == null || focusSourceTodoId.isValidMonitorMetadata(160)) &&
                (sessionMode == MonitorSessionMode.FOCUS ||
                    (focusTaskTitle == null && focusSourceTodoId == null)) &&
                (focusSourceTodoId == null || focusTaskTitle != null)
    }

    fun getBackgroundType(): String = try {
        prefs.getString("KEY_BACKGROUND_TYPE", "pure") ?: "pure"
    } catch (_: RuntimeException) {
        "pure"
    }

    fun setBackgroundType(type: String) {
        try {
            prefs.edit().putString("KEY_BACKGROUND_TYPE", type).apply()
        } catch (_: RuntimeException) {
        }
    }

    fun getBackgroundColor(): Int = try {
        prefs.getInt("KEY_BACKGROUND_COLOR", 0)
    } catch (_: RuntimeException) {
        0
    }

    fun setBackgroundColor(color: Int) {
        try {
            prefs.edit().putInt("KEY_BACKGROUND_COLOR", color).apply()
        } catch (_: RuntimeException) {
        }
    }

    fun getBackgroundGradient(): String = try {
        prefs.getString("KEY_BACKGROUND_GRADIENT", "") ?: ""
    } catch (_: RuntimeException) {
        ""
    }

    fun setBackgroundGradient(gradient: String) {
        try {
            prefs.edit().putString("KEY_BACKGROUND_GRADIENT", gradient).apply()
        } catch (_: RuntimeException) {
        }
    }

    fun getBackgroundImageIndex(): Int = try {
        prefs.getInt("KEY_BACKGROUND_IMAGE_INDEX", -1)
    } catch (_: RuntimeException) {
        -1
    }

    fun setBackgroundImageIndex(index: Int) {
        try {
            prefs.edit().putInt("KEY_BACKGROUND_IMAGE_INDEX", index).apply()
        } catch (_: RuntimeException) {
        }
    }

    @Synchronized
    fun getInterceptCount(): Int = try {
        // 计数只对"当天"有效：日期不匹配时视为 0，跨天自动归零。
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString(KEY_INTERCEPT_COUNT_DATE, null) == today) {
            prefs.getInt(KEY_INTERCEPT_COUNT, 0)
        } else {
            0
        }
    } catch (_: RuntimeException) {
        0
    }

    @Synchronized
    fun incrementInterceptCount() {
        try {
            val today = java.time.LocalDate.now().toString()
            val storedDate = prefs.getString(KEY_INTERCEPT_COUNT_DATE, null)
            val current = if (storedDate == today) prefs.getInt(KEY_INTERCEPT_COUNT, 0) else 0
            prefs.edit()
                .putString(KEY_INTERCEPT_COUNT_DATE, today)
                .putInt(KEY_INTERCEPT_COUNT, current + 1)
                .apply()
        } catch (_: RuntimeException) {
        }
    }

    fun isDefaultPlansInitialized(): Boolean = try {
        prefs.getBoolean("key_default_plans_initialized_v2", false)
    } catch (_: RuntimeException) {
        false
    }

    fun setDefaultPlansInitialized(initialized: Boolean) {
        try {
            prefs.edit().putBoolean("key_default_plans_initialized_v2", initialized).apply()
        } catch (_: RuntimeException) {
        }
    }

    fun isDefaultFocusPlansInitialized(): Boolean = try {
        prefs.getBoolean("key_default_focus_plans_initialized", false)
    } catch (_: RuntimeException) {
        false
    }

    fun setDefaultFocusPlansInitialized(initialized: Boolean) {
        try {
            prefs.edit().putBoolean("key_default_focus_plans_initialized", initialized).apply()
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "control_free_prefs"

        const val KEY_DARK_THEME_ENABLED = "dark_theme_enabled"

        const val KEY_INTERCEPT_COUNT = "key_intercept_count_today"
        const val KEY_INTERCEPT_COUNT_DATE = "key_intercept_count_date"

        const val KEY_USAGE_TIME = "usage_time"
        const val KEY_LOCK_TIME = "lock_time"
        const val DEFAULT_USAGE_TIME_MINUTES = 30
        const val DEFAULT_LOCK_TIME_MINUTES = 5

        const val KEY_FOCUS_LOCK_TIME = "focus_lock_time"
        const val KEY_FOCUS_PLAY_TIME = "focus_play_time"
        const val DEFAULT_FOCUS_LOCK_TIME_MINUTES = 5
        const val DEFAULT_FOCUS_PLAY_TIME_MINUTES = 1
        const val KEY_ACTIVE_FOCUS_TASK_TITLE = "active_focus_task_title"
        const val KEY_ACTIVE_FOCUS_SOURCE_TODO_ID = "active_focus_source_todo_id"

        const val KEY_MONITOR_ACTIVE = "monitor_active"
        const val KEY_MONITOR_SESSION_MODE = "monitor_session_mode"
        const val KEY_MONITOR_SCHEMA = "monitor_schema"
        const val KEY_MONITOR_PHASE = "monitor_phase"
        const val KEY_REMAINING_MILLIS = "monitor_remaining_millis"
        const val KEY_CHECKPOINT_ELAPSED_MILLIS = "monitor_checkpoint_elapsed_millis"
        const val KEY_BOOT_COUNT = "monitor_boot_count"
        const val KEY_INTERACTIVE = "monitor_interactive"
        const val KEY_SCREEN_OFF_RECOVERY_REMAINDER_MILLIS =
            "monitor_screen_off_recovery_remainder_millis"
        const val KEY_SNAPSHOT_CHECKSUM = "monitor_snapshot_checksum"
        const val MONITOR_SCHEMA_VERSION = 5
        const val PAUSE_MONITOR_SCHEMA_VERSION = 4
        const val CHECKSUM_MONITOR_SCHEMA_VERSION = 3
        const val PREVIOUS_MONITOR_SCHEMA_VERSION = 2
        val SUPPORTED_MONITOR_SCHEMAS =
            PREVIOUS_MONITOR_SCHEMA_VERSION..MONITOR_SCHEMA_VERSION
        const val UNKNOWN_BOOT_COUNT = -1
        const val SCREEN_OFF_RECOVERY_INTERVAL_MILLIS = 5L * 60_000L

        const val KEY_PAUSE_ACCUMULATED_MILLIS = "monitor_pause_accumulated_millis"
        const val KEY_PAUSE_ACTIVE = "monitor_pause_active"
        const val KEY_PAUSE_STARTED_AT_EPOCH_MILLIS = "monitor_pause_started_at_epoch_millis"
        const val KEY_PAUSE_UNTIL_EPOCH_MILLIS = "monitor_pause_until_epoch_millis"
        const val KEY_PAUSE_DEADLINE_ELAPSED_MILLIS = "monitor_pause_deadline_elapsed_millis"
        const val KEY_PAUSE_BOOT_COUNT = "monitor_pause_boot_count"

        const val KEY_LEGACY_MONITOR_STATE = "monitor_state"
        const val KEY_LEGACY_PHASE_ENDS_AT = "phase_ends_at_millis"

        const val KEY_MONITOR_ORIGIN = "monitor_origin"
        const val MONITOR_ORIGIN_SCHEDULED = "scheduled"
        const val KEY_SCHEDULED_PLAN_ID = "scheduled_plan_id"
        const val KEY_SCHEDULED_PLAN_UPDATED_AT = "scheduled_plan_updated_at"
        const val KEY_SCHEDULED_ACTIVE_UNTIL = "scheduled_active_until"

        const val KEY_APPLIED_GROWTH_ORDER_IDS = "applied_growth_order_ids"
        const val MAX_APPLIED_GROWTH_ORDER_IDS = 64

        const val KEY_SUPPRESSED_PLAN_ID = "suppressed_plan_id"
        const val KEY_SUPPRESSED_PLAN_UPDATED_AT = "suppressed_plan_updated_at"
        const val KEY_SUPPRESSED_UNTIL = "suppressed_until"

        fun isValidGrowthOrderId(orderId: String): Boolean =
            orderId.length in 8..160 && orderId.all { character ->
                character.isLetterOrDigit() || character == '-' || character == '_'
            }
    }
}

private fun String.isValidMonitorMetadata(maxLength: Int): Boolean =
    isNotBlank() && this == trim() && length <= maxLength

/**
 * schema 5 的完整性摘要。摘要不承担密码学鉴权，只用于拒绝跨字段、截断或手工损坏的
 * 快照；SharedPreferences 的同步 commit 仍负责原子落盘。
 */
internal object MonitorSnapshotChecksum {
    fun create(snapshot: MonitorCycleSnapshot, pauseState: MonitorPauseState? = null): String {
        val payload = buildString {
            append(SCHEMA_VERSION)
            append('|')
            append(snapshot.phase.storedValue)
            append('|')
            append(snapshot.remainingMillis)
            append('|')
            append(snapshot.checkpointElapsedMillis)
            append('|')
            append(snapshot.bootCount)
            append('|')
            append(snapshot.isInteractive)
            append('|')
            append(snapshot.screenOffRecoveryRemainderMillis)
            append('|')
            append(pauseState?.accumulatedPauseMillis ?: 0L)
            append('|')
            append(pauseState?.isActive == true)
            append('|')
            append(pauseState?.startedAtEpochMillis ?: 0L)
            append('|')
            append(pauseState?.untilEpochMillis ?: 0L)
            append('|')
            append(pauseState?.deadlineElapsedMillis ?: 0L)
            append('|')
            append(pauseState?.bootCount ?: -1)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    fun matches(snapshot: MonitorCycleSnapshot, expected: String): Boolean =
        matches(snapshot, pauseState = null, expected = expected)

    fun matches(
        snapshot: MonitorCycleSnapshot,
        pauseState: MonitorPauseState?,
        expected: String
    ): Boolean {
        val actualBytes = create(snapshot, pauseState).encodeToByteArray()
        val expectedBytes = expected.lowercase().encodeToByteArray()
        return MessageDigest.isEqual(actualBytes, expectedBytes)
    }

    fun matchesSchema3(snapshot: MonitorCycleSnapshot, expected: String): Boolean {
        val payload = buildString {
            append(3)
            append('|')
            append(snapshot.phase.storedValue)
            append('|')
            append(snapshot.remainingMillis)
            append('|')
            append(snapshot.checkpointElapsedMillis)
            append('|')
            append(snapshot.bootCount)
            append('|')
            append(snapshot.isInteractive)
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(payload.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .encodeToByteArray()
        return MessageDigest.isEqual(actual, expected.lowercase().encodeToByteArray())
    }

    fun matchesSchema4(
        snapshot: MonitorCycleSnapshot,
        pauseState: MonitorPauseState?,
        expected: String
    ): Boolean {
        val payload = buildString {
            append(4)
            append('|')
            append(snapshot.phase.storedValue)
            append('|')
            append(snapshot.remainingMillis)
            append('|')
            append(snapshot.checkpointElapsedMillis)
            append('|')
            append(snapshot.bootCount)
            append('|')
            append(snapshot.isInteractive)
            append('|')
            append(pauseState?.accumulatedPauseMillis ?: 0L)
            append('|')
            append(pauseState?.isActive == true)
            append('|')
            append(pauseState?.startedAtEpochMillis ?: 0L)
            append('|')
            append(pauseState?.untilEpochMillis ?: 0L)
            append('|')
            append(pauseState?.deadlineElapsedMillis ?: 0L)
            append('|')
            append(pauseState?.bootCount ?: -1)
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(payload.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .encodeToByteArray()
        return MessageDigest.isEqual(actual, expected.lowercase().encodeToByteArray())
    }

    private const val SCHEMA_VERSION = 5
}
