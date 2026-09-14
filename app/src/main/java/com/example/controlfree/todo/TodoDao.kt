package com.example.controlfree.todo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {
    @Query("SELECT * FROM todo_items ORDER BY created_at_epoch_millis DESC")
    fun observeAll(): Flow<List<TodoItemEntity>>

    @Query(
        "SELECT * FROM todo_items WHERE " +
            "(scheduled_start_epoch_millis >= :startEpochMillis AND scheduled_start_epoch_millis < :endExclusiveEpochMillis) " +
            "OR (due_date_epoch_millis >= :startEpochMillis AND due_date_epoch_millis < :endExclusiveEpochMillis) " +
            "OR (completed_at_epoch_millis >= :startEpochMillis AND completed_at_epoch_millis < :endExclusiveEpochMillis) " +
            "ORDER BY COALESCE(scheduled_start_epoch_millis, due_date_epoch_millis, created_at_epoch_millis)"
    )
    fun observeInRange(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_items WHERE id = :id")
    suspend fun getById(id: String): TodoItemEntity?

    @Query("SELECT * FROM todo_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<TodoItemEntity>

    @Upsert
    suspend fun upsertTodo(todo: TodoItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTodoIfAbsent(todo: TodoItemEntity): Long

    @Query("DELETE FROM todo_items WHERE id = :id")
    suspend fun delete(id: String): Int

    /**
     * 清理监督计划删除后遗留的待办关联字段。
     *
     * 计划与待办没有数据库外键关系，因此必须在同一事务中显式解除关联，
     * 否则待办会继续携带已经不存在的计划编号和旧时间窗。
     */
    @Query(
        "UPDATE todo_items SET associated_focus_plan_id = NULL, " +
            "scheduled_start_epoch_millis = NULL, " +
            "scheduled_end_epoch_millis = NULL, " +
            "estimated_focus_minutes = NULL, " +
            "updated_at_epoch_millis = CASE " +
            "WHEN updated_at_epoch_millis < :updatedAt THEN :updatedAt " +
            "ELSE updated_at_epoch_millis END " +
            "WHERE associated_focus_plan_id = :planId"
    )
    suspend fun clearFocusPlanAssociation(planId: String, updatedAt: Long): Int

    /** 计划自然结束或调度失败时仅解除监督关联，保留待办的日历排程历史。 */
    @Query(
        "UPDATE todo_items SET associated_focus_plan_id = NULL, " +
            "updated_at_epoch_millis = CASE " +
            "WHEN updated_at_epoch_millis < :updatedAt THEN :updatedAt " +
            "ELSE updated_at_epoch_millis END " +
            "WHERE associated_focus_plan_id = :planId"
    )
    suspend fun unlinkFocusPlanAssociation(planId: String, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM todo_items WHERE associated_focus_plan_id = :planId")
    suspend fun countFocusPlanAssociations(planId: String): Int

    @Query(
        "SELECT DISTINCT associated_focus_plan_id FROM todo_items " +
            "WHERE associated_focus_plan_id IS NOT NULL"
    )
    suspend fun getAssociatedFocusPlanIds(): List<String>

    @Query(
        "UPDATE todo_items SET is_completed = :isCompleted, " +
            "completed_at_epoch_millis = :completedAt, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateCompletionStatus(
        id: String,
        isCompleted: Boolean,
        completedAt: Long?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM todo_subtasks ORDER BY todo_id, sort_order, created_at_epoch_millis, id")
    fun observeAllSubtasks(): Flow<List<TodoSubtaskEntity>>

    @Query(
        "SELECT * FROM todo_subtasks WHERE todo_id = :todoId " +
            "ORDER BY sort_order, created_at_epoch_millis, id"
    )
    fun observeSubtasks(todoId: String): Flow<List<TodoSubtaskEntity>>

    @Query(
        "SELECT * FROM todo_subtasks WHERE todo_id = :todoId " +
            "ORDER BY sort_order, created_at_epoch_millis, id"
    )
    suspend fun getSubtasks(todoId: String): List<TodoSubtaskEntity>

    @Query("SELECT * FROM todo_subtasks WHERE id = :id")
    suspend fun getSubtaskById(id: String): TodoSubtaskEntity?

    @Upsert
    suspend fun upsertSubtask(subtask: TodoSubtaskEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubtasksIfAbsent(subtasks: List<TodoSubtaskEntity>): List<Long>

    @Query(
        "UPDATE todo_subtasks SET title = :title, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun renameSubtask(id: String, title: String, updatedAt: Long): Int

    @Query(
        "UPDATE todo_subtasks SET is_completed = :isCompleted, " +
            "completed_at_epoch_millis = :completedAt, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateSubtaskCompletion(
        id: String,
        isCompleted: Boolean,
        completedAt: Long?,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM todo_subtasks WHERE id = :id")
    suspend fun deleteSubtask(id: String): Int

    @Query("DELETE FROM todo_subtasks WHERE todo_id = :todoId")
    suspend fun deleteSubtasksForTodo(todoId: String): Int
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit_items WHERE is_archived = 0 ORDER BY created_at_epoch_millis DESC")
    fun observeActiveHabits(): Flow<List<HabitItemEntity>>

    @Query("SELECT * FROM habit_items ORDER BY created_at_epoch_millis DESC")
    fun observeAllHabits(): Flow<List<HabitItemEntity>>

    @Query("SELECT * FROM habit_items ORDER BY created_at_epoch_millis DESC")
    suspend fun getAllHabits(): List<HabitItemEntity>

    @Query("SELECT * FROM habit_items WHERE id = :id")
    suspend fun getById(id: String): HabitItemEntity?

    @Upsert
    suspend fun upsertHabit(habit: HabitItemEntity)

    @Query(
        "UPDATE habit_items SET current_streak = :currentStreak, best_streak = :bestStreak, " +
            "updated_at_epoch_millis = :updatedAt WHERE id = :id"
    )
    suspend fun updateStreak(
        id: String,
        currentStreak: Int,
        bestStreak: Int,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE habit_items SET is_archived = :isArchived, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun setArchived(id: String, isArchived: Boolean, updatedAt: Long): Int

    @Upsert
    suspend fun upsertRecord(record: HabitRecordEntity)

    @Query("SELECT * FROM habit_records ORDER BY completed_date DESC, updated_at_epoch_millis DESC")
    fun observeAllRecords(): Flow<List<HabitRecordEntity>>

    @Query(
        "SELECT * FROM habit_records WHERE completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date, habit_id"
    )
    fun observeRecordsInRange(startDate: String, endDate: String): Flow<List<HabitRecordEntity>>

    @Query("SELECT * FROM habit_records WHERE habit_id = :habitId ORDER BY completed_date DESC")
    fun observeRecords(habitId: String): Flow<List<HabitRecordEntity>>

    @Query("SELECT * FROM habit_records WHERE habit_id = :habitId ORDER BY completed_date DESC")
    suspend fun getRecordsList(habitId: String): List<HabitRecordEntity>

    @Query("SELECT * FROM habit_records WHERE habit_id = :habitId AND completed_date = :completedDate LIMIT 1")
    suspend fun getRecordByDate(habitId: String, completedDate: String): HabitRecordEntity?

    @Query(
        "SELECT * FROM habit_records WHERE habit_id = :habitId AND completed_date >= :startDate AND completed_date <= :endDate " +
            "ORDER BY completed_date"
    )
    suspend fun getRecordsBetween(
        habitId: String,
        startDate: String,
        endDate: String
    ): List<HabitRecordEntity>

    @Query("DELETE FROM habit_records WHERE habit_id = :habitId AND completed_date = :completedDate")
    suspend fun deleteRecord(habitId: String, completedDate: String): Int

    @Query("SELECT * FROM habit_records WHERE completed_date = :completedDate")
    suspend fun getRecordsOnDate(completedDate: String): List<HabitRecordEntity>

    @Query("SELECT * FROM habit_records WHERE completed_date = :completedDate")
    fun observeRecordsOnDate(completedDate: String): Flow<List<HabitRecordEntity>>

    @Query(
        "UPDATE habit_records SET note = :note, updated_at_epoch_millis = :updatedAt " +
            "WHERE habit_id = :habitId AND completed_date = :completedDate"
    )
    suspend fun updateRecordNote(
        habitId: String,
        completedDate: String,
        note: String?,
        updatedAt: Long
    ): Int
}

@Dao
interface AnniversaryDao {
    @Query("SELECT * FROM anniversary_items ORDER BY is_pinned_top DESC, target_date_epoch_millis ASC")
    fun observeAll(): Flow<List<AnniversaryItemEntity>>

    @Query(
        "SELECT * FROM anniversary_items WHERE show_on_widget = 1 OR show_on_lock_screen = 1 " +
            "ORDER BY is_pinned_top DESC, target_date_epoch_millis ASC"
    )
    fun observeEnabled(): Flow<List<AnniversaryItemEntity>>

    @Query("SELECT * FROM anniversary_items ORDER BY is_pinned_top DESC, target_date_epoch_millis ASC")
    suspend fun getAll(): List<AnniversaryItemEntity>

    @Query("SELECT * FROM anniversary_items WHERE id = :id")
    suspend fun getById(id: String): AnniversaryItemEntity?

    @Upsert
    suspend fun upsertAnniversary(anniversary: AnniversaryItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnniversaryIfAbsent(anniversary: AnniversaryItemEntity): Long

    @Query("DELETE FROM anniversary_items WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query(
        "UPDATE anniversary_items SET is_pinned_top = :isPinned, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun setPinned(id: String, isPinned: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE anniversary_items SET show_on_widget = :enabled, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun setWidgetEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Query(
        "UPDATE anniversary_items SET show_on_lock_screen = :enabled, updated_at_epoch_millis = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun setLockScreenEnabled(id: String, enabled: Boolean, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrenceIfAbsent(occurrence: AnniversaryOccurrenceEntity): Long

    @Query(
        "SELECT * FROM anniversary_occurrences WHERE occurs_at_epoch_millis >= :startEpochMillis " +
            "AND occurs_at_epoch_millis < :endExclusiveEpochMillis ORDER BY occurs_at_epoch_millis"
    )
    fun observeOccurrencesInRange(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<AnniversaryOccurrenceEntity>>

    @Query("SELECT * FROM anniversary_occurrences WHERE anniversary_id = :anniversaryId ORDER BY occurs_at_epoch_millis DESC")
    fun observeOccurrences(anniversaryId: String): Flow<List<AnniversaryOccurrenceEntity>>
}

@Dao
interface QuickNoteDao {
    @Query("SELECT * FROM quick_notes WHERE status != 'ARCHIVED' ORDER BY created_at_epoch_millis DESC")
    fun observeInboxNotes(): Flow<List<QuickNoteEntity>>

    @Query("SELECT * FROM quick_notes ORDER BY created_at_epoch_millis DESC")
    fun observeAll(): Flow<List<QuickNoteEntity>>

    @Query("SELECT * FROM quick_notes WHERE id = :id")
    suspend fun getById(id: String): QuickNoteEntity?

    @Upsert
    suspend fun upsertNote(note: QuickNoteEntity)

    @Update
    suspend fun updateNote(note: QuickNoteEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNoteIfAbsent(note: QuickNoteEntity): Long

    @Query("UPDATE quick_notes SET status = :status, updated_at_epoch_millis = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long): Int

    @Query("DELETE FROM quick_notes WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM quick_note_conversions WHERE note_id = :noteId")
    suspend fun deleteConversions(noteId: String): Int

    @Query("DELETE FROM quick_note_conversions WHERE note_id = :noteId AND target_type = :targetType")
    suspend fun deleteConversion(noteId: String, targetType: String): Int

    @Query("SELECT COUNT(*) FROM quick_notes WHERE media_uri = :mediaUri")
    suspend fun countNotesUsingMediaUri(mediaUri: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversionIfAbsent(conversion: QuickNoteConversionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversionsIfAbsent(
        conversions: List<QuickNoteConversionEntity>
    ): List<Long>

    @Query("SELECT * FROM quick_note_conversions WHERE note_id = :noteId ORDER BY target_type")
    suspend fun getConversions(noteId: String): List<QuickNoteConversionEntity>

    @Query("SELECT * FROM quick_note_conversions WHERE note_id = :noteId AND target_type = :targetType LIMIT 1")
    suspend fun getConversion(noteId: String, targetType: String): QuickNoteConversionEntity?
}

@Dao
interface ProductivityEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAchievementIfAbsent(achievement: AchievementUnlockEntity): Long

    @Query("SELECT * FROM achievement_unlocks ORDER BY unlocked_at_epoch_millis DESC")
    fun observeAchievements(): Flow<List<AchievementUnlockEntity>>

    @Query(
        "SELECT * FROM achievement_unlocks WHERE achievement_type = 'HABIT_STREAK' " +
            "AND subject_id = :habitId ORDER BY tier"
    )
    fun observeHabitAchievements(habitId: String): Flow<List<AchievementUnlockEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardEventIfAbsent(event: ProductivityRewardEventEntity): Long

    @Query(
        "SELECT COUNT(*) FROM productivity_reward_outbox " +
            "WHERE reward_type = :rewardType AND local_date = :localDate"
    )
    suspend fun countRewardEvents(rewardType: String, localDate: String): Int

    @Query(
        "SELECT * FROM productivity_reward_outbox WHERE processed_at_epoch_millis IS NULL " +
            "ORDER BY created_at_epoch_millis, id"
    )
    fun observePendingRewardEvents(): Flow<List<ProductivityRewardEventEntity>>

    @Query(
        "SELECT * FROM productivity_reward_outbox WHERE processed_at_epoch_millis IS NULL " +
            "ORDER BY created_at_epoch_millis, id LIMIT :limit"
    )
    suspend fun getPendingRewardEvents(limit: Int): List<ProductivityRewardEventEntity>

    @Query(
        "UPDATE productivity_reward_outbox SET processed_at_epoch_millis = :processedAt " +
            "WHERE id = :id AND processed_at_epoch_millis IS NULL"
    )
    suspend fun markRewardEventProcessed(id: String, processedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCelebrationIfAbsent(event: CelebrationEventEntity): Long

    @Query(
        "SELECT * FROM celebration_events WHERE consumed_at_epoch_millis IS NULL " +
            "ORDER BY occurred_at_epoch_millis, id"
    )
    fun observePendingCelebrations(): Flow<List<CelebrationEventEntity>>

    @Query(
        "UPDATE celebration_events SET consumed_at_epoch_millis = :consumedAt " +
            "WHERE id = :id AND consumed_at_epoch_millis IS NULL"
    )
    suspend fun consumeCelebration(id: String, consumedAt: Long): Int
}

@Dao
interface CommitmentDao {
    @Transaction
    @Query("SELECT * FROM commitment_policies ORDER BY id")
    fun observeAllPoliciesWithApps(): Flow<List<CommitmentPolicyWithApps>>

    @Transaction
    @Query(
        "SELECT * FROM commitment_policies WHERE source_type = :sourceType " +
            "AND source_id = :sourceId LIMIT 1"
    )
    suspend fun getPolicyWithAppsBySource(
        sourceType: String,
        sourceId: String
    ): CommitmentPolicyWithApps?

    @Transaction
    @Query("SELECT * FROM commitment_policies WHERE enabled = 1 ORDER BY id")
    fun observeEnabledPoliciesWithApps(): Flow<List<CommitmentPolicyWithApps>>

    @Transaction
    @Query("SELECT * FROM commitment_policies WHERE enabled = 1 ORDER BY id")
    suspend fun getEnabledPoliciesWithApps(): List<CommitmentPolicyWithApps>

    @Query("SELECT * FROM commitment_policies WHERE source_type = :sourceType AND source_id = :sourceId LIMIT 1")
    suspend fun getPolicyBySource(sourceType: String, sourceId: String): CommitmentPolicyEntity?

    @Query("SELECT * FROM commitment_policies WHERE id = :id LIMIT 1")
    suspend fun getPolicyById(id: String): CommitmentPolicyEntity?

    @Upsert
    suspend fun upsertPolicy(policy: CommitmentPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPolicyIfAbsent(policy: CommitmentPolicyEntity): Long

    @Query("DELETE FROM commitment_policies WHERE id = :id")
    suspend fun deletePolicy(id: String): Int

    @Query("DELETE FROM commitment_blocked_apps WHERE policy_id = :policyId")
    suspend fun deleteBlockedApps(policyId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockedApps(apps: List<CommitmentBlockedAppEntity>): List<Long>

    @Query(
        "SELECT * FROM commitment_occurrences WHERE satisfied_at_epoch_millis IS NULL " +
            "ORDER BY deadline_epoch_millis, id"
    )
    fun observeOpenOccurrences(): Flow<List<CommitmentOccurrenceEntity>>

    @Query(
        "SELECT * FROM commitment_occurrences WHERE satisfied_at_epoch_millis IS NULL " +
            "ORDER BY deadline_epoch_millis, id"
    )
    suspend fun getOpenOccurrences(): List<CommitmentOccurrenceEntity>

    @Query("SELECT * FROM commitment_occurrences WHERE policy_id = :policyId AND occurrence_key = :occurrenceKey LIMIT 1")
    suspend fun getOccurrence(policyId: String, occurrenceKey: String): CommitmentOccurrenceEntity?

    @Query(
        "SELECT * FROM commitment_occurrences WHERE policy_id = :policyId " +
            "AND satisfied_at_epoch_millis IS NULL ORDER BY deadline_epoch_millis, id"
    )
    suspend fun getOpenOccurrencesForPolicy(policyId: String): List<CommitmentOccurrenceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrenceIfAbsent(occurrence: CommitmentOccurrenceEntity): Long

    @Upsert
    suspend fun upsertOccurrence(occurrence: CommitmentOccurrenceEntity)

    @Query("DELETE FROM commitment_occurrences WHERE id = :id")
    suspend fun deleteOccurrence(id: String): Int

    /** 仅回滚本次待办删除写入的“已满足”状态，避免覆盖删除后的其他变化。 */
    @Query(
        "UPDATE commitment_occurrences SET satisfied_at_epoch_millis = NULL, " +
            "updated_at_epoch_millis = :originalUpdatedAt " +
            "WHERE id = :id AND policy_id = :policyId AND occurrence_key = :occurrenceKey " +
            "AND deadline_epoch_millis = :deadlineAt AND expires_at_epoch_millis = :expiresAt " +
            "AND activated_at_epoch_millis IS :activatedAt " +
            "AND satisfied_at_epoch_millis = :deletedAt " +
            "AND created_at_epoch_millis = :createdAt " +
            "AND updated_at_epoch_millis = :deletedAt"
    )
    suspend fun restoreOpenOccurrenceIfUnchanged(
        id: String,
        policyId: String,
        occurrenceKey: String,
        deadlineAt: Long,
        expiresAt: Long,
        activatedAt: Long?,
        createdAt: Long,
        originalUpdatedAt: Long,
        deletedAt: Long
    ): Int

    @Query(
        "UPDATE commitment_occurrences SET activated_at_epoch_millis = COALESCE(activated_at_epoch_millis, :activatedAt), " +
            "updated_at_epoch_millis = :activatedAt WHERE id = :id AND satisfied_at_epoch_millis IS NULL"
    )
    suspend fun markOccurrenceActivated(id: String, activatedAt: Long): Int

    @Query(
        "UPDATE commitment_occurrences SET satisfied_at_epoch_millis = :satisfiedAt, " +
            "updated_at_epoch_millis = :satisfiedAt WHERE id = :id AND satisfied_at_epoch_millis IS NULL"
    )
    suspend fun satisfyOccurrence(id: String, satisfiedAt: Long): Int

    @Query(
        "UPDATE commitment_occurrences SET satisfied_at_epoch_millis = NULL, " +
            "activated_at_epoch_millis = NULL, updated_at_epoch_millis = :updatedAt " +
            "WHERE policy_id = :policyId AND occurrence_key = :occurrenceKey"
    )
    suspend fun reopenOccurrence(
        policyId: String,
        occurrenceKey: String,
        updatedAt: Long
    ): Int

    @Query(
        "UPDATE commitment_occurrences SET satisfied_at_epoch_millis = :satisfiedAt, " +
            "updated_at_epoch_millis = :satisfiedAt WHERE policy_id = :policyId " +
            "AND satisfied_at_epoch_millis IS NULL"
    )
    suspend fun satisfyOpenOccurrencesForPolicy(policyId: String, satisfiedAt: Long): Int
}

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntries(entries: List<LedgerEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgerEntriesIfAbsent(entries: List<LedgerEntryEntity>): List<Long>

    @Query("SELECT * FROM ledger_entries ORDER BY occurred_at_epoch_millis DESC, id DESC")
    fun observeLedgerEntries(): Flow<List<LedgerEntryEntity>>

    @Query("""
        SELECT * FROM ledger_entries 
        WHERE occurred_at_epoch_millis >= :startMillis
          AND occurred_at_epoch_millis < :endExclusiveMillis
        ORDER BY occurred_at_epoch_millis DESC, id DESC
    """)
    fun observeLedgerEntriesBetween(startMillis: Long, endExclusiveMillis: Long): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1")
    suspend fun getLedgerEntryById(id: String): LedgerEntryEntity?

    @Query("SELECT COUNT(*) FROM ledger_entries WHERE source_note_id = :sourceNoteId")
    suspend fun countBySourceNoteId(sourceNoteId: String): Int

    @Query("SELECT COUNT(*) FROM ledger_entries WHERE source_batch_id = :sourceBatchId")
    suspend fun countBySourceBatchId(sourceBatchId: String): Int

    @Query("SELECT * FROM ledger_entries WHERE source_batch_id = :sourceBatchId ORDER BY id")
    suspend fun getLedgerEntriesBySourceBatchId(sourceBatchId: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries WHERE id IN (:ids)")
    suspend fun getLedgerEntriesByIds(ids: List<String>): List<LedgerEntryEntity>

    @Query("""
        UPDATE ledger_entries
        SET title = :title,
            amount = :amount,
            direction = :direction,
            category = :category,
            isEstimated = 0,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id
    """)
    suspend fun updateLedgerEntryUserFields(
        id: String,
        title: String,
        amount: Long,
        direction: String,
        category: String,
        updatedAtEpochMillis: Long
    ): Int

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteLedgerEntry(id: String): Int

    @Query("""
        SELECT direction, SUM(amount) as total 
        FROM ledger_entries 
        WHERE occurred_at_epoch_millis >= :startMillis
          AND occurred_at_epoch_millis < :endExclusiveMillis
          AND amount BETWEEN 1 AND 100000000000
          AND (
              (direction = 'EXPENSE' AND category IN (
                  'FOOD', 'TRANSPORT', 'SHOPPING', 'ENTERTAINMENT', 'HOUSING',
                  'MEDICAL', 'EDUCATION', 'SOCIAL', 'EXPENSE_OTHER'
              ))
              OR
              (direction = 'INCOME' AND category IN (
                  'SALARY', 'PART_TIME', 'INVESTMENT', 'RED_PACKET', 'INCOME_OTHER'
              ))
          )
        GROUP BY direction
    """)
    fun observeMonthlyDirectionTotals(startMillis: Long, endExclusiveMillis: Long): Flow<List<DirectionTotal>>

    @Query("""
        SELECT category, SUM(amount) as total 
        FROM ledger_entries 
        WHERE direction = 'EXPENSE' 
        AND occurred_at_epoch_millis >= :startMillis
        AND occurred_at_epoch_millis < :endExclusiveMillis
        AND amount BETWEEN 1 AND 100000000000
        AND category IN (
            'FOOD', 'TRANSPORT', 'SHOPPING', 'ENTERTAINMENT', 'HOUSING',
            'MEDICAL', 'EDUCATION', 'SOCIAL', 'EXPENSE_OTHER'
        )
        GROUP BY category 
        ORDER BY total DESC, category ASC
    """)
    fun observeMonthlyCategoryTotals(startMillis: Long, endExclusiveMillis: Long): Flow<List<CategoryTotal>>
}
