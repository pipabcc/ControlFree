package com.example.controlfree.ui.todo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.controlfree.data.AllowedApp
import com.example.controlfree.data.AppAllowlistManager
import com.example.controlfree.supervision.commitment.CommitmentScheduleReceiver
import com.example.controlfree.todo.AchievementUnlockEntity
import com.example.controlfree.todo.AchievementType
import com.example.controlfree.todo.CommitmentPolicyInput
import com.example.controlfree.todo.CommitmentPolicyWithApps
import com.example.controlfree.todo.CommitmentSourceType
import com.example.controlfree.todo.HabitItemEntity
import com.example.controlfree.todo.HabitRecordEntity
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.toJsonString
import com.example.controlfree.todo.habit.runtime.HabitReminderPreferences
import com.example.controlfree.todo.habit.runtime.HabitReminderReceiver
import com.example.controlfree.todo.habit.runtime.HabitReminderSettings
import com.example.controlfree.ui.todo.habit.HabitEditorDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TodoRepository.getInstance(application)
    private val appAllowlistManager = AppAllowlistManager(application)
    private val reminderPreferences = HabitReminderPreferences(application)
    val clock: Clock = Clock.systemDefaultZone()

    private val _reminderSettings = MutableStateFlow(reminderPreferences.read())
    val reminderSettings: StateFlow<HabitReminderSettings> = _reminderSettings.asStateFlow()

    val habits: StateFlow<List<HabitItemEntity>> = repository.observeActiveHabits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allRecords: StateFlow<List<HabitRecordEntity>> = repository.observeAllHabitRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allAchievements: StateFlow<List<AchievementUnlockEntity>> = repository.observeAllAchievements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unlockedHabitBadgeTiers: StateFlow<Map<String, Set<Int>>> = allAchievements
        .map { achievements ->
            achievements.asSequence()
                .filter { achievement ->
                    achievement.achievementType == AchievementType.HABIT_STREAK.storedValue &&
                        achievement.subjectId != null
                }
                .groupBy(
                    keySelector = { achievement -> requireNotNull(achievement.subjectId) },
                    valueTransform = AchievementUnlockEntity::tier
                )
                .mapValues { (_, tiers) -> tiers.toSet() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val commitmentPolicies: StateFlow<List<CommitmentPolicyWithApps>> =
        repository.observeAllCommitmentPolicies()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _supervisableApps = MutableStateFlow<List<AllowedApp>>(emptyList())
    val supervisableApps = _supervisableApps.asStateFlow()
    private val _isLoadingSupervisableApps = MutableStateFlow(false)
    val isLoadingSupervisableApps = _isLoadingSupervisableApps.asStateFlow()
    private val _supervisableAppsLoadFailed = MutableStateFlow(false)
    val supervisableAppsLoadFailed = _supervisableAppsLoadFailed.asStateFlow()

    private val selectedHabitIdForRecords = MutableStateFlow<String?>(null)
    val currentHabitRecords: StateFlow<List<HabitRecordEntity>> = selectedHabitIdForRecords
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.observeHabitRecords(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        loadSupervisableApps()
    }

    fun retrySupervisableApps() = loadSupervisableApps(force = true)

    fun selectHabitForRecords(habitId: String?) {
        selectedHabitIdForRecords.value = habitId
    }

    fun saveHabit(draft: HabitEditorDraft) {
        viewModelScope.launch {
            runCatching {
                val now = clock.millis()
                val existing = draft.id?.let { repository.getHabitById(it) }
                val settings = draft.commitmentSettings
                repository.saveHabitWithCommitment(
                    HabitItemEntity(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = draft.name.trim(),
                        iconRes = draft.iconKey,
                        colorHex = draft.colorHex,
                        frequencyType = draft.frequency.storedValue,
                        targetCountPerDay = draft.targetCountPerDay,
                        currentStreak = existing?.currentStreak ?: 0,
                        bestStreak = existing?.bestStreak ?: 0,
                        isArchived = existing?.isArchived ?: false,
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                        weekdaysMask = draft.weekdaysMask,
                        weeklyTargetDays = draft.weeklyTargetDays,
                        intervalDays = draft.intervalDays,
                        startDate = draft.startDate.toString(),
                        remindersJson = draft.reminderConfig.toJsonString(),
                        updatedAtEpochMillis = now
                    ),
                    CommitmentPolicyInput(
                        enabled = settings.enabled,
                        localDeadlineMinute = settings.localDeadlineMinute,
                        graceMinutes = settings.graceMinutes,
                        maxLockMinutes = settings.maxLockMinutes,
                        zoneId = clock.zone.id,
                        blockedPackages = settings.blockedPackages
                    )
                )
                CommitmentScheduleReceiver.requestReconciliation(getApplication(), "habit_saved")
                requestReminderReconciliation("habit_saved")
            }.onFailure { report(it, "保存习惯失败") }
        }
    }

    fun checkIn(
        habitId: String,
        date: LocalDate,
        note: String? = null,
        isBackfill: Boolean = date != LocalDate.now(clock)
    ) {
        viewModelScope.launch {
            runCatching {
                repository.checkInHabit(habitId, date.toString(), note, isBackfill)
            }.onSuccess { result ->
                CommitmentScheduleReceiver.requestReconciliation(getApplication(), "habit_check_in")
                requestReminderReconciliation("habit_check_in")
                if (result == null) _messages.tryEmit("习惯不存在")
                else if (result.unlockedBadgeTiers.isNotEmpty()) {
                    _messages.tryEmit("解锁里程碑：${result.unlockedBadgeTiers.sorted().joinToString(" / ")}")
                }
            }.onFailure { report(it, "打卡失败") }
        }
    }

    fun decrementCheckIn(habitId: String, date: LocalDate) {
        viewModelScope.launch {
            runCatching {
                repository.decrementHabitCheckIn(habitId, date.toString())
                CommitmentScheduleReceiver.requestReconciliation(getApplication(), "habit_check_in_reverted")
                requestReminderReconciliation("habit_check_in_reverted")
            }
                .onFailure { report(it, "撤销打卡失败") }
        }
    }

    fun saveRecordNote(habitId: String, date: LocalDate, note: String?) {
        viewModelScope.launch {
            runCatching { repository.saveHabitRecordNote(habitId, date.toString(), note) }
                .onSuccess { saved -> if (!saved) _messages.tryEmit("请先完成至少一次打卡") }
                .onFailure { report(it, "保存心得失败") }
        }
    }

    fun archiveHabit(habitId: String) {
        viewModelScope.launch {
            runCatching {
                repository.getCommitmentPolicy(CommitmentSourceType.HABIT, habitId)?.let { relation ->
                    repository.deleteCommitmentPolicy(relation.policy.id)
                }
                repository.setHabitArchived(habitId, true)
                CommitmentScheduleReceiver.requestReconciliation(getApplication(), "habit_archived")
                requestReminderReconciliation("habit_archived")
            }
                .onFailure { report(it, "归档习惯失败") }
        }
    }

    // 兼容旧清单页面，待页面切换到 HabitScreen 后可删除。
    fun addHabit(name: String, colorHex: String, iconRes: String, frequencyType: String) {
        saveHabit(
            HabitEditorDraft(
                name = name,
                colorHex = colorHex,
                iconKey = iconRes,
                frequency = com.example.controlfree.ui.todo.habit.HabitFrequencyType.fromStoredValue(frequencyType),
                startDate = LocalDate.now(clock)
            )
        )
    }

    fun checkIn(habitId: String, dateStr: String, note: String? = null) {
        val date = runCatching { LocalDate.parse(dateStr) }.getOrElse {
            _messages.tryEmit("打卡日期无效")
            return
        }
        checkIn(habitId, date, note)
    }

    fun undoCheckIn(habitId: String, dateStr: String) {
        runCatching { LocalDate.parse(dateStr) }
            .onSuccess { decrementCheckIn(habitId, it) }
            .onFailure { _messages.tryEmit("打卡日期无效") }
    }

    fun setReminderEnabled(enabled: Boolean) {
        _reminderSettings.value = reminderPreferences.setEnabled(enabled)
        requestReminderReconciliation("settings_enabled_changed")
    }

    fun setReminderMinute(reminderMinute: Int) {
        _reminderSettings.value = reminderPreferences.setReminderMinute(reminderMinute)
        requestReminderReconciliation("settings_time_changed")
    }

    fun refreshReminderSchedule() {
        requestReminderReconciliation("notification_access_changed")
    }

    private fun report(error: Throwable, fallback: String) {
        _messages.tryEmit(error.message?.takeIf(String::isNotBlank) ?: fallback)
    }

    private fun requestReminderReconciliation(reason: String) {
        HabitReminderReceiver.requestReconciliation(getApplication(), reason)
    }

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
}
