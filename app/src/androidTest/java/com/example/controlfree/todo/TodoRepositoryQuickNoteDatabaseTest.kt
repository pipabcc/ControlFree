package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import com.example.controlfree.supervision.persistence.OneTimeFocusScheduleResult
import com.example.controlfree.supervision.persistence.PlanLoadResult
import com.example.controlfree.supervision.persistence.PlanWriteResult
import com.example.controlfree.supervision.persistence.SupervisionPlanRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryQuickNoteDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(
            database,
            Clock.fixed(
                Instant.parse("2026-07-22T04:00:00Z"),
                ZoneId.of("Asia/Shanghai")
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 已转换闪念仍保留在收件箱并支持其他转换() = runBlocking {
        val note = repository.addQuickNote("明天下午三点跑步三十分钟")

        repository.convertNoteToTodo(note.id, "跑步", priority = 1, dueDate = null)
        assertEquals(note.id, repository.observeQuickNoteInbox().first().single().id)

        repository.convertNoteToHabit(note.id, "跑步", "#21C76A", "run")
        repository.convertNoteToFocus(note.id)

        assertEquals(note.id, repository.observeQuickNoteInbox().first().single().id)
    }

    @Test
    fun 转换目标删除或归档后再次转换会重建目标() = runBlocking {
        val note = repository.addQuickNote("跑步")

        val todoResult = repository.convertNoteToTodo(
            note.id,
            "跑步",
            priority = 1,
            dueDate = null,
            scheduledStartEpochMillis = 1_000L,
            scheduledEndEpochMillis = 2_000L,
            estimatedFocusMinutes = 30,
            recurrenceType = TodoRecurrenceType.DAILY.storedValue
        )
        assertTrue(todoResult.wasCreated)
        repository.deleteTodo(todoResult.targetId)
        val rebuiltTodo = repository.convertNoteToTodo(note.id, "跑步", priority = 1, dueDate = null)
        assertTrue(rebuiltTodo.wasCreated)
        assertEquals(todoResult.targetId, rebuiltTodo.targetId)
        assertNotNull(repository.getTodoById(rebuiltTodo.targetId))

        val habitResult = repository.convertNoteToHabit(note.id, "跑步", "#21C76A", "run")
        assertTrue(habitResult.wasCreated)
        val customizedHabit = requireNotNull(repository.getHabitById(habitResult.targetId)).copy(
            name = "自定义跑步习惯",
            targetCountPerDay = 2,
            frequencyType = HabitFrequencyType.INTERVAL.storedValue,
            intervalDays = 3
        )
        repository.updateHabit(customizedHabit)
        repository.setHabitArchived(habitResult.targetId, true)
        val reusedHabit = repository.convertNoteToHabit(
            note.id,
            "跑步",
            "#21C76A",
            "run",
            frequencyType = HabitFrequencyType.DAILY.storedValue,
            weekdaysMask = 0
        )
        assertFalse(reusedHabit.wasCreated)
        assertEquals(habitResult.targetId, reusedHabit.targetId)
        val restoredHabit = requireNotNull(repository.getHabitById(reusedHabit.targetId))
        assertFalse(restoredHabit.isArchived)
        assertEquals(customizedHabit.name, restoredHabit.name)
        assertEquals(customizedHabit.targetCountPerDay, restoredHabit.targetCountPerDay)
        assertEquals(customizedHabit.frequencyType, restoredHabit.frequencyType)
        assertEquals(customizedHabit.intervalDays, restoredHabit.intervalDays)
    }

    @Test
    fun 重复转换不会覆盖已编辑待办() = runBlocking {
        val note = repository.addQuickNote("明天下午三点写报告")
        val first = repository.convertNoteToTodo(
            note.id,
            "写报告",
            priority = 1,
            dueDate = 2_000L,
            scheduledStartEpochMillis = 1_000L,
            scheduledEndEpochMillis = 2_000L,
            estimatedFocusMinutes = 30,
            recurrenceType = TodoRecurrenceType.DAILY.storedValue
        )
        val customized = requireNotNull(repository.getTodoById(first.targetId)).copy(
            scheduledStartEpochMillis = 8_000L,
            scheduledEndEpochMillis = 12_000L,
            estimatedFocusMinutes = 60,
            recurrenceType = TodoRecurrenceType.MONTHLY_DAY.storedValue,
            recurrenceDayOfMonth = 22
        )
        repository.saveTodo(customized)

        val reused = repository.convertNoteToTodo(
            note.id,
            "被忽略的新标题",
            priority = 0,
            dueDate = null,
            scheduledStartEpochMillis = 20_000L,
            scheduledEndEpochMillis = 21_000L,
            estimatedFocusMinutes = 10,
            recurrenceType = TodoRecurrenceType.NONE.storedValue
        )

        assertFalse(reused.wasCreated)
        assertEquals(first.targetId, reused.targetId)
        assertEquals(customized, repository.getTodoById(first.targetId))
    }

    @Test
    fun 删除最后一个图片引用后才释放媒体授权() = runBlocking {
        val mediaUri = "content://example.controlfree.test/shared-image"
        val first = repository.addQuickNote("第一条", mediaUri = mediaUri)
        val second = repository.addQuickNote("第二条", mediaUri = mediaUri)

        assertNull(repository.deleteQuickNote(first.id))
        assertEquals(mediaUri, repository.deleteQuickNote(second.id))
    }

    @Test
    fun 删除并撤销闪念会恢复原记录和全部转化审计() = runBlocking {
        val mediaUri = "content://example.controlfree.test/undo-image"
        val note = repository.addQuickNote("需要撤销", mediaUri = mediaUri)
        repository.convertNoteToFocus(note.id, "focus-request")

        val snapshot = requireNotNull(repository.deleteQuickNoteForUndo(note.id))
        assertEquals(mediaUri, snapshot.mediaUriToRelease)
        assertEquals(1, snapshot.conversions.size)
        assertTrue(repository.observeQuickNoteInbox().first().isEmpty())

        assertTrue(repository.restoreDeletedQuickNote(snapshot))
        assertEquals(note, repository.observeQuickNoteInbox().first().single())
        val reused = repository.convertNoteToFocus(note.id, "ignored-request")
        assertFalse(reused.wasCreated)
        assertEquals("focus-request", reused.targetId)
    }

    @Test
    fun 恢复闪念遇到转化唯一键冲突会回滚主记录且不覆盖新转化() = runBlocking {
        val note = repository.addQuickNote("测试原子恢复")
        repository.convertNoteToFocus(note.id, "old-focus-request")
        val snapshot = requireNotNull(repository.deleteQuickNoteForUndo(note.id))
        val conflicting = snapshot.conversions.single().copy(targetId = "new-focus-request")
        database.quickNoteDao().insertConversionIfAbsent(conflicting)

        assertFalse(repository.restoreDeletedQuickNote(snapshot))
        assertNull(database.quickNoteDao().getById(note.id))
        assertEquals(
            "new-focus-request",
            database.quickNoteDao().getConversions(note.id).single().targetId
        )
    }

    @Test
    fun 编辑已删除闪记不会通过更新操作重新创建() = runBlocking {
        val note = repository.addQuickNote("即将删除")
        repository.convertNoteToFocus(note.id, "focus-request")
        requireNotNull(repository.deleteQuickNoteForUndo(note.id))

        val updated = repository.updateQuickNote(note.copy(content = "不应复活"))

        assertNull(updated)
        assertNull(database.quickNoteDao().getById(note.id))
        assertTrue(database.quickNoteDao().getConversions(note.id).isEmpty())
    }

    @Test
    fun 编辑闪记会保留可扩展的原始采集来源() = runBlocking {
        val note = repository.addQuickNote("图片来源")

        val updated = requireNotNull(
            repository.updateQuickNote(note.copy(captureSource = "IMAGE_IMPORT"))
        )

        assertEquals("IMAGE_IMPORT", updated.captureSource)
        assertEquals("IMAGE_IMPORT", database.quickNoteDao().getById(note.id)?.captureSource)
    }

    @Test
    fun AI助理确认原子写入全部目标并保持闪记可见() = runBlocking {
        val note = repository.addQuickNote("午餐28元；明天提交报告；专注20分钟；记录纪念日")
        val fingerprint = QuickNoteAssistantFingerprint.fromContent(note.content)
        val analysis = assistantAnalysis(fingerprint)

        val result = repository.confirmQuickNoteAssistant(note.id, analysis)

        assertTrue(result.wasCreated)
        assertEquals(1, result.ledgerEntryIds.size)
        assertEquals(1, result.todoIds.size)
        assertEquals(1, result.habitIds.size)
        assertEquals(1, result.anniversaryIds.size)
        assertEquals(1, result.focusSchedules.size)
        assertEquals(1, database.ledgerDao().observeLedgerEntries().first().size)
        val todos = repository.observeAllTodos().first()
        assertEquals(2, todos.size)
        val routedTodo = requireNotNull(todos.firstOrNull { it.id in result.todoIds })
        assertEquals(listOf(10), routedTodo.remindersJson.toItemReminders().map { it.minutesBefore })
        val focusTodo = requireNotNull(todos.firstOrNull { it.id == result.focusSchedules.single().todoId })
        assertNotNull(focusTodo.associatedFocusPlanId)
        assertEquals(
            focusTodo.associatedFocusPlanId,
            (SupervisionPlanRepository.createForTest(database).loadPlans() as PlanLoadResult.Success)
                .plans.single().id
        )
        assertEquals(1, repository.observeAllHabits().first().size)
        assertEquals(1, repository.observeAllAnniversaries().first().size)

        val savedNote = repository.observeQuickNoteInbox().first().single()
        assertEquals(QuickNoteStatus.RAW.storedValue, savedNote.status)
        assertEquals("建议先提交报告。", savedNote.aiAdvice)
        assertEquals(fingerprint, savedNote.aiAssistantFingerprint)
        assertEquals(1, database.quickNoteDao().getConversions(note.id).size)
    }

    @Test
    fun AI时刻在最终写入时按当前时间复核类型和循环规则() = runBlocking {
        val note = repository.addQuickNote("记录已经发生的重要时刻")
        val fingerprint = QuickNoteAssistantFingerprint.fromContent(note.content)
        val analysis = QuickNoteAssistantAnalysis(
            fingerprint = fingerprint,
            ledgerEntries = emptyList(),
            todoItems = emptyList(),
            habits = emptyList(),
            focusSessions = emptyList(),
            anniversaries = listOf(
                QuickNoteAssistantAnniversaryDraft(
                    title = "重要时刻",
                    targetAtEpochMillis = Instant.parse("2026-07-21T07:15:30Z").toEpochMilli(),
                    type = AnniversaryType.COUNTDOWN,
                    repeatRule = AnniversaryRepeatRule.YEARLY,
                    zoneId = "Asia/Shanghai",
                    reminderMinutesBefore = emptyList()
                )
            ),
            advice = emptyList(),
            warnings = emptyList()
        )

        repository.confirmQuickNoteAssistant(note.id, analysis)

        val saved = repository.observeAllAnniversaries().first().single()
        assertEquals(AnniversaryType.COUNT_UP.storedValue, saved.type)
        assertEquals(AnniversaryRepeatRule.NONE.storedValue, saved.repeatRule)
        assertEquals(15, saved.sourceHour)
        assertEquals(15, saved.sourceMinute)
        assertEquals(30, saved.sourceSecond)
    }

    @Test
    fun AI待办把开始和预计结束写入排程但不伪造截止时间() = runBlocking {
        val note = repository.addQuickNote("下午3点去打球")
        val startAt = Instant.parse("2026-07-22T07:00:00Z").toEpochMilli()
        val analysis = QuickNoteAssistantAnalysis(
            fingerprint = QuickNoteAssistantFingerprint.fromContent(note.content),
            ledgerEntries = emptyList(),
            todoItems = listOf(
                QuickNoteAssistantTodoDraft(
                    title = "去打球",
                    dueAtEpochMillis = null,
                    scheduledStartEpochMillis = startAt,
                    durationMinutes = 60,
                    priority = 1,
                    recurrenceType = TodoRecurrenceType.NONE,
                    recurrenceDaysMask = 0,
                    reminderMinutesBefore = listOf(10)
                )
            ),
            habits = emptyList(),
            focusSessions = emptyList(),
            anniversaries = emptyList(),
            advice = emptyList(),
            warnings = emptyList()
        )

        val result = repository.confirmQuickNoteAssistant(note.id, analysis)

        val saved = requireNotNull(repository.getTodoById(result.todoIds.single()))
        assertEquals(startAt, saved.scheduledStartEpochMillis)
        assertEquals(startAt + 60 * 60_000L, saved.scheduledEndEpochMillis)
        assertNull(saved.dueDateEpochMillis)
        assertEquals(60, saved.estimatedFocusMinutes)
        assertEquals(listOf(10), saved.remindersJson.toItemReminders().map { it.minutesBefore })
    }

    @Test
    fun AI专注冲突会回滚整批确认并可在冲突解除后重试() = runBlocking {
        val planRepository = SupervisionPlanRepository.createForTest(database)
        val blockingTodo = repository.saveTodo(
            TodoItemEntity(
                id = "blocking-focus",
                title = "已有专注",
                description = null,
                dueDateEpochMillis = null,
                priority = 1,
                isCompleted = false,
                completedAtEpochMillis = null,
                category = "测试",
                repeatRule = null,
                associatedFocusPlanId = null,
                supervisionLockEnabled = false,
                createdAtEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli()
            )
        )
        val focusStart = Instant.parse("2026-07-22T05:00:00Z").toEpochMilli()
        val blockingResult = planRepository.scheduleOneTimeFocusForTodo(
            todoId = blockingTodo.id,
            startEpochMillis = focusStart,
            endEpochMillis = focusStart + 20 * 60_000L,
            nowEpochMillis = Instant.parse("2026-07-22T04:00:00Z").toEpochMilli()
        ) as OneTimeFocusScheduleResult.Success
        val todosBeforeConfirm = repository.observeAllTodos().first()
        val note = repository.addQuickNote("午餐28元；明天提交报告；专注20分钟；记录纪念日")
        val analysis = assistantAnalysis(QuickNoteAssistantFingerprint.fromContent(note.content))

        var failureMessage: String? = null
        try {
            repository.confirmQuickNoteAssistant(note.id, analysis)
        } catch (error: IllegalStateException) {
            failureMessage = error.message
        }

        assertTrue(failureMessage?.contains("冲突") == true)
        assertEquals(todosBeforeConfirm, repository.observeAllTodos().first())
        assertTrue(database.ledgerDao().observeLedgerEntries().first().isEmpty())
        assertTrue(repository.observeAllHabits().first().isEmpty())
        assertTrue(repository.observeAllAnniversaries().first().isEmpty())
        assertTrue(database.quickNoteDao().getConversions(note.id).isEmpty())
        assertNull(database.quickNoteDao().getById(note.id)?.aiAssistantFingerprint)

        val blockingPlan = (planRepository.loadPlans() as PlanLoadResult.Success)
            .plans.single { it.id == blockingResult.planId }
        assertTrue(
            planRepository.delete(
                blockingPlan.id,
                blockingPlan.updatedAtEpochMillis
            ) is PlanWriteResult.Success
        )

        val retried = repository.confirmQuickNoteAssistant(note.id, analysis)

        assertTrue(retried.wasCreated)
        assertNotNull(repository.getTodoById(retried.focusSchedules.single().todoId)?.associatedFocusPlanId)
        assertEquals(1, database.quickNoteDao().getConversions(note.id).size)
    }

    @Test
    fun AI助理重复确认不重复写入也不重建用户已删除目标() = runBlocking {
        val note = repository.addQuickNote("午餐28元；明天提交报告；专注20分钟；记录纪念日")
        val analysis = assistantAnalysis(QuickNoteAssistantFingerprint.fromContent(note.content))
        val first = repository.confirmQuickNoteAssistant(note.id, analysis)
        repository.deleteTodo(first.todoIds.single())

        val repeated = repository.confirmQuickNoteAssistant(note.id, analysis)

        assertFalse(repeated.wasCreated)
        assertEquals(first, repeated.copy(wasCreated = true))
        assertNull(repository.getTodoById(first.todoIds.single()))
        assertEquals(1, database.ledgerDao().observeLedgerEntries().first().size)
        assertEquals(1, repository.observeAllHabits().first().size)
        assertEquals(1, repository.observeAllAnniversaries().first().size)
        assertEquals(1, database.quickNoteDao().getConversions(note.id).size)
    }

    @Test
    fun AI助理拒绝确认正文已变化的旧分析() = runBlocking {
        val note = repository.addQuickNote("明天提交报告")
        val stale = assistantAnalysis(QuickNoteAssistantFingerprint.fromContent(note.content))
        repository.updateQuickNote(note.copy(content = "后天提交报告"))

        var rejected = false
        try {
            repository.confirmQuickNoteAssistant(note.id, stale)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(repository.observeAllTodos().first().isEmpty())
        assertNull(database.quickNoteDao().getById(note.id)?.aiAssistantFingerprint)
    }

    private fun assistantAnalysis(fingerprint: String): QuickNoteAssistantAnalysis =
        QuickNoteAssistantAnalysis(
            fingerprint = fingerprint,
            ledgerEntries = listOf(
                LedgerEntryDraft(
                    title = "午餐",
                    amountFen = 2_800L,
                    direction = LedgerDirection.EXPENSE,
                    category = LedgerCategory.FOOD,
                    occurredAtEpochMillis = 1_000L,
                    isEstimated = false,
                    aiConfidence = 0.95f,
                    emotion = "刚需",
                    necessity = "need"
                )
            ),
            todoItems = listOf(
                QuickNoteAssistantTodoDraft(
                    title = "提交报告",
                    dueAtEpochMillis = Instant.parse("2026-07-22T06:00:00Z").toEpochMilli(),
                    scheduledStartEpochMillis = Instant.parse("2026-07-22T05:30:00Z").toEpochMilli(),
                    durationMinutes = 30,
                    priority = 2,
                    recurrenceType = TodoRecurrenceType.NONE,
                    recurrenceDaysMask = 0,
                    reminderMinutesBefore = listOf(10)
                )
            ),
            habits = listOf(
                QuickNoteAssistantHabitDraft(
                    name = "晨读",
                    frequencyType = HabitFrequencyType.DAILY,
                    weekdaysMask = ALL_WEEKDAYS_MASK,
                    reminderMinutesOfDay = listOf(450)
                )
            ),
            focusSessions = listOf(
                QuickNoteAssistantFocusDraft(
                    title = "阅读论文",
                    startAtEpochMillis = Instant.parse("2026-07-22T05:00:00Z").toEpochMilli(),
                    durationMinutes = 20
                )
            ),
            anniversaries = listOf(
                QuickNoteAssistantAnniversaryDraft(
                    title = "重要时刻",
                    targetAtEpochMillis = Instant.parse("2026-08-20T01:00:00Z").toEpochMilli(),
                    type = AnniversaryType.COUNTDOWN,
                    repeatRule = AnniversaryRepeatRule.YEARLY,
                    zoneId = "Asia/Shanghai",
                    reminderMinutesBefore = listOf(1_440)
                )
            ),
            advice = listOf("建议先提交报告。"),
            warnings = emptyList()
        )
}
