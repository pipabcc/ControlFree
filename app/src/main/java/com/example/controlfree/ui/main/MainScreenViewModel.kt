package com.example.controlfree.ui.main

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.example.controlfree.MonitorCycle
import com.example.controlfree.MonitorPhase
import com.example.controlfree.MonitorProgressRestorer
import com.example.controlfree.MonitorSessionMode
import com.example.controlfree.MonitorService
import com.example.controlfree.data.MonitorRecoveryGuard
import com.example.controlfree.data.PreferenceManager

internal data class PendingMonitorStart(
    val attemptId: Long,
    val sessionMode: MonitorSessionMode,
    val focusTaskTitle: String? = null,
    val sourceTodoId: String? = null
)

class MainScreenViewModel : ViewModel() {
    val usageTimeState = mutableStateOf(30)
    val lockTimeState = mutableStateOf(5)
    val focusLockTimeState = mutableStateOf(5)
    val focusPlayTimeState = mutableStateOf(1)
    val focusTaskTitleState = mutableStateOf("")
    val isRunningState = mutableStateOf(false)
    val remainingSecondsState = mutableStateOf(0)
    val monitorStateState = mutableStateOf(MonitorService.STATE_USAGE)
    val isInitializedState = mutableStateOf(false)
    val isStartPendingState = mutableStateOf(false)
    val isStartingState = mutableStateOf(false)
    val startAttemptIdState = mutableStateOf<Long?>(null)
    val activeSessionModeState = mutableStateOf(MonitorSessionMode.SUPERVISION)
    val pendingSessionModeState = mutableStateOf<MonitorSessionMode?>(null)

    private var prefs: PreferenceManager? = null
    private var applicationContext: Context? = null
    private var recoveryGuard: MonitorRecoveryGuard? = null
    private var nextStartAttemptId = 0L
    private var pendingFocusTaskTitle: String? = null
    private var pendingSourceTodoId: String? = null

    fun initPreferences(context: Context) {
        if (prefs == null) {
            applicationContext = context.applicationContext
            prefs = PreferenceManager(requireNotNull(applicationContext))
            recoveryGuard = MonitorRecoveryGuard(requireNotNull(applicationContext))
            usageTimeState.value = prefs!!.getUsageTime()
            lockTimeState.value = prefs!!.getLockTime()
            focusLockTimeState.value = prefs!!.getFocusLockTime()
            focusPlayTimeState.value = prefs!!.getFocusPlayTime()
        }
        restoreMonitorProgress()
        isInitializedState.value = true
    }

    fun saveSettings(usage: Int, lock: Int) {
        usageTimeState.value = usage
        lockTimeState.value = lock
        prefs?.setUsageTime(usage)
        prefs?.setLockTime(lock)
    }

    fun requestMonitorStart(usage: Int, lock: Int): Boolean {
        return requestStart(MonitorSessionMode.SUPERVISION) { saveSettings(usage, lock) }
    }

    fun saveFocusSettings(lock: Int, play: Int) {
        focusLockTimeState.value = lock
        focusPlayTimeState.value = play
        prefs?.setFocusLockTime(lock)
        prefs?.setFocusPlayTime(play)
    }

    fun prepareTodoFocus(todoId: String, taskTitle: String, estimatedMinutes: Int): Boolean {
        val normalizedId = todoId.trim()
        val normalizedTitle = taskTitle.trim()
        if (
            normalizedId.isEmpty() ||
            normalizedTitle.isEmpty() ||
            normalizedTitle.length > MAX_FOCUS_TASK_TITLE_LENGTH ||
            estimatedMinutes !in 1..180
        ) {
            return false
        }
        if (isStartingState.value || isStartPendingState.value || isRunningState.value) return false
        focusTaskTitleState.value = normalizedTitle
        focusLockTimeState.value = estimatedMinutes
        pendingSourceTodoId = normalizedId
        return true
    }

    fun updateFocusTaskTitle(value: String) {
        focusTaskTitleState.value = value.take(MAX_FOCUS_TASK_TITLE_LENGTH)
        if (focusTaskTitleState.value.isBlank()) pendingSourceTodoId = null
    }

    fun requestFocusStart(lock: Int, play: Int): Boolean {
        val normalizedTitle = focusTaskTitleState.value.trim().takeIf(String::isNotEmpty)
        val sourceTodoId = pendingSourceTodoId?.takeIf { normalizedTitle != null }
        return requestStart(
            sessionMode = MonitorSessionMode.FOCUS,
            focusTaskTitle = normalizedTitle,
            sourceTodoId = sourceTodoId
        ) { saveFocusSettings(lock, play) }
    }

    private fun requestStart(
        sessionMode: MonitorSessionMode,
        focusTaskTitle: String? = null,
        sourceTodoId: String? = null,
        persistConfiguration: () -> Unit
    ): Boolean {
        if (
            isStartingState.value ||
            isStartPendingState.value ||
            isRunningState.value ||
            MonitorService.isRunning
        ) {
            return false
        }
        persistConfiguration()
        nextStartAttemptId++
        startAttemptIdState.value = nextStartAttemptId
        pendingSessionModeState.value = sessionMode
        pendingFocusTaskTitle = focusTaskTitle
        pendingSourceTodoId = sourceTodoId
        isStartPendingState.value = true
        isStartingState.value = true
        return true
    }

    /** 所需权限齐备后只允许派发一次服务启动命令。 */
    internal fun consumePendingStart(): PendingMonitorStart? {
        if (!isStartPendingState.value) return null
        val attemptId = startAttemptIdState.value ?: run {
            cancelMonitorStart()
            return null
        }
        val sessionMode = pendingSessionModeState.value ?: run {
            cancelMonitorStart()
            return null
        }
        isStartPendingState.value = false
        return PendingMonitorStart(
            attemptId = attemptId,
            sessionMode = sessionMode,
            focusTaskTitle = pendingFocusTaskTitle,
            sourceTodoId = pendingSourceTodoId
        ).also {
            pendingFocusTaskTitle = null
            pendingSourceTodoId = null
        }
    }

    fun cancelMonitorStart() {
        isStartPendingState.value = false
        isStartingState.value = false
        startAttemptIdState.value = null
        pendingSessionModeState.value = null
        pendingFocusTaskTitle = null
        pendingSourceTodoId = null
    }

    fun confirmMonitorStarted(
        attemptId: Long,
        state: String,
        remainingSeconds: Int,
        sessionMode: MonitorSessionMode
    ): Boolean {
        val currentAttemptId = startAttemptIdState.value
        if (
            (currentAttemptId != null && currentAttemptId != attemptId) ||
            (currentAttemptId == null && attemptId != MonitorService.NO_START_ATTEMPT)
        ) {
            return false
        }
        isStartPendingState.value = false
        isStartingState.value = false
        startAttemptIdState.value = null
        pendingSessionModeState.value = null
        isRunningState.value = true
        activeSessionModeState.value = sessionMode
        monitorStateState.value = state
        remainingSecondsState.value = remainingSeconds.coerceAtLeast(0)
        return true
    }

    fun acceptMonitorStartFailure(attemptId: Long): Boolean {
        val currentAttemptId = startAttemptIdState.value
        if (
            (currentAttemptId != null && currentAttemptId != attemptId) ||
            (currentAttemptId == null && attemptId != MonitorService.NO_START_ATTEMPT)
        ) {
            return false
        }
        cancelMonitorStart()
        return true
    }

    fun updateMonitorTick(
        state: String,
        remainingSeconds: Int,
        sessionMode: MonitorSessionMode
    ) {
        if (isStartingState.value) return
        isRunningState.value = true
        activeSessionModeState.value = sessionMode
        monitorStateState.value = state
        remainingSecondsState.value = remainingSeconds.coerceAtLeast(0)
    }

    fun confirmMonitorStopped() {
        isStartPendingState.value = false
        isStartingState.value = false
        startAttemptIdState.value = null
        pendingSessionModeState.value = null
        isRunningState.value = false
        activeSessionModeState.value = MonitorSessionMode.SUPERVISION
        monitorStateState.value = MonitorService.STATE_USAGE
        remainingSecondsState.value = 0
    }

    fun updateRunningState() {
        restoreMonitorProgress()
    }

    private fun restoreMonitorProgress() {
        val preferenceManager = prefs ?: return
        isRunningState.value =
            preferenceManager.isMonitorActive() || recoveryGuard?.requiresLock() == true
        activeSessionModeState.value = preferenceManager.getMonitorSessionMode()
        if (!isRunningState.value) {
            remainingSecondsState.value = 0
            monitorStateState.value = MonitorService.STATE_USAGE
            return
        }

        val savedSnapshot = preferenceManager.loadMonitorProgress()
        if (savedSnapshot != null) {
            val context = applicationContext ?: return
            val cycle = MonitorCycle(
                usageDurationMillis = activeUsageMinutes(preferenceManager) * 60_000L,
                lockDurationMillis = activeLockMinutes(preferenceManager) * 60_000L,
                initialPhase = activeSessionModeState.value.initialPhase,
                lockCountsDownWhileInteractive =
                    activeSessionModeState.value == MonitorSessionMode.FOCUS
            )
            val nowElapsed = SystemClock.elapsedRealtime()
            val currentBootCount = readBootCount(context)
            val currentInteractive =
                context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
            val canPreviewLiveProgress =
                MonitorService.isRunning &&
                    savedSnapshot.isInteractive == currentInteractive &&
                    (savedSnapshot.bootCount == currentBootCount || currentBootCount < 0)
            val preview = if (canPreviewLiveProgress) {
                cycle.advance(savedSnapshot, nowElapsed)
            } else {
                MonitorProgressRestorer(cycle).restore(
                    saved = savedSnapshot,
                    nowElapsedMillis = nowElapsed,
                    currentBootCount = currentBootCount,
                    currentInteractive = currentInteractive
                )
            }
            monitorStateState.value = preview.phase.storedValue
            remainingSecondsState.value = cycle.remainingSeconds(preview)
            return
        }

        // 损坏的新快照采用锁定态兜底；旧 schema 只预览，权威迁移由服务完成。
        val legacyPhase = if (
            preferenceManager.getMonitorSchemaVersion() >= 2 ||
            preferenceManager.getLegacyPhaseEndsAtMillis() <= 0L
        ) {
            MonitorPhase.LOCK
        } else {
            preferenceManager.getLegacyMonitorPhase() ?: MonitorPhase.LOCK
        }
        monitorStateState.value = legacyPhase.storedValue
        remainingSecondsState.value = if (legacyPhase == MonitorPhase.LOCK) {
            activeLockMinutes(preferenceManager) * 60
        } else {
            activeUsageMinutes(preferenceManager) * 60
        }
    }

    private fun activeUsageMinutes(preferenceManager: PreferenceManager): Int =
        when (activeSessionModeState.value) {
            MonitorSessionMode.SUPERVISION -> preferenceManager.getUsageTime()
            MonitorSessionMode.FOCUS -> preferenceManager.getFocusPlayTime()
        }.coerceAtLeast(1)

    private fun activeLockMinutes(preferenceManager: PreferenceManager): Int =
        when (activeSessionModeState.value) {
            MonitorSessionMode.SUPERVISION -> preferenceManager.getLockTime()
            MonitorSessionMode.FOCUS -> preferenceManager.getFocusLockTime()
        }.coerceAtLeast(1)

    private fun readBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: RuntimeException) {
        -1
    }

    private companion object {
        const val MAX_FOCUS_TASK_TITLE_LENGTH = 80
    }
}
