package com.example.controlfree.supervision.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.history.GLOBAL_RUNTIME_SLOT
import com.example.controlfree.supervision.history.SupervisionSessionDescriptor
import com.example.controlfree.supervision.history.SupervisionSessionEndReason
import com.example.controlfree.supervision.history.SupervisionSessionKind
import com.example.controlfree.supervision.history.SupervisionRecoveryStatus
import com.example.controlfree.supervision.history.appRuntimeSlot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupervisionHistoryDatabaseTest {
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: SupervisionHistoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = SupervisionHistoryRepository.createForTest(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 相同身份恢复沿用会话而新身份原子替换旧会话() = runBlocking {
        val first = repository.startOrReplaceGlobal(manual("manual:first"), 1_000L)
        val recovered = repository.startOrReplaceGlobal(manual("manual:first"), 2_000L)
        val replacement = repository.startOrReplaceGlobal(manual("manual:second"), 3_000L)
        val records = repository.observeRecent().first()

        assertEquals(first.sessionId, recovered.sessionId)
        assertNotEquals(first.sessionId, replacement.sessionId)
        val closed = records.first { it.sessionId == first.sessionId }
        assertEquals(3_000L, closed.endedAtEpochMillis)
        assertEquals(SupervisionSessionEndReason.REPLACED, closed.endReason)
        assertTrue(records.first { it.sessionId == replacement.sessionId }.isActive)
    }

    @Test
    fun 多个App会话独立开启并只结束被移除的规则() = runBlocking {
        val video = app("video", "example.video", "occurrence:video")
        val game = app("game", "example.game", "occurrence:game")
        repository.reconcileApps(listOf(video, game), 1_000L)

        repository.reconcileApps(listOf(game), 5_000L)

        val records = repository.observeRecent().first()
        val videoRecord = records.first { it.planId == "video" }
        val gameRecord = records.first { it.planId == "game" }
        assertEquals(SupervisionSessionEndReason.COMPLETED, videoRecord.endReason)
        assertEquals(5_000L, videoRecord.endedAtEpochMillis)
        assertTrue(gameRecord.isActive)
    }

    @Test
    fun 范围查询包含相交的结束会话与当前活动会话() = runBlocking {
        repository.startOrReplaceGlobal(manual("manual:first"), 1_000L)
        assertTrue(repository.closeGlobal(SupervisionSessionEndReason.CANCELLED, 4_000L))
        repository.startOrReplaceGlobal(manual("manual:second"), 8_000L)

        val records = repository.getOverlapping(3_000L, 10_000L, activeEndEpochMillis = 9_000L)

        assertEquals(2, records.size)
        assertFalse(records.first().isActive)
        assertTrue(records.last().isActive)
    }

    @Test
    fun 响应式范围查询不会被较新的千条历史截断() = runBlocking {
        val dao = database.supervisionHistoryDao()
        dao.insert(session("old", 100L, 200L))
        repeat(1_001) { index ->
            val start = 10_000L + index
            dao.insert(session("new-$index", start, start + 1L))
        }

        val records = dao.observeOverlapping(
            startEpochMillis = 0L,
            endExclusiveEpochMillis = 1_000L
        ).first()

        assertEquals(listOf("old"), records.map(SupervisionSessionEntity::sessionId))
    }

    @Test
    fun 同一次设备重启会去重并关联活动监督及恢复结果() = runBlocking {
        val active = repository.startOrReplaceGlobal(manual("manual:boot"), 1_000L)

        repository.recordDeviceBoot(
            bootCount = 12,
            occurredAtEpochMillis = 2_000L,
            receivedAtEpochMillis = 3_000L,
            recoveryExpected = true
        )
        repository.recordDeviceBoot(
            bootCount = 12,
            occurredAtEpochMillis = 2_100L,
            receivedAtEpochMillis = 3_100L,
            recoveryExpected = true
        )
        assertTrue(
            repository.markDeviceBootRecoveryStatus(
                bootCount = 12,
                status = SupervisionRecoveryStatus.RESTORED,
                nowEpochMillis = 4_000L
            )
        )

        val events = repository.observeRecentEvents().first()
        assertEquals(1, events.size)
        assertEquals(active.sessionId, events.single().sessionId)
        assertEquals(SupervisionRecoveryStatus.RESTORED, events.single().recoveryStatus)
    }

    private fun manual(identity: String) = SupervisionSessionDescriptor(
        identityKey = identity,
        runtimeSlot = GLOBAL_RUNTIME_SLOT,
        kind = SupervisionSessionKind.MANUAL_GLOBAL,
        displayName = "即时专注",
        planId = null,
        packageName = null,
        usageMinutes = 30,
        lockMinutes = 5
    )

    private fun app(planId: String, packageName: String, identity: String) =
        SupervisionSessionDescriptor(
            identityKey = identity,
            runtimeSlot = appRuntimeSlot(planId),
            kind = SupervisionSessionKind.APP,
            displayName = planId,
            planId = planId,
            packageName = packageName,
            usageMinutes = 30,
            lockMinutes = 5
        )

    private fun session(id: String, startedAt: Long, endedAt: Long) = SupervisionSessionEntity(
        sessionId = id,
        identityKey = "identity:$id",
        runtimeSlot = null,
        sessionKind = SupervisionSessionKind.MANUAL_FOCUS.storedValue,
        displayName = id,
        planId = null,
        packageName = null,
        usageMinutes = 30,
        lockMinutes = 5,
        startedAtEpochMillis = startedAt,
        endedAtEpochMillis = endedAt,
        endReason = SupervisionSessionEndReason.COMPLETED.storedValue,
        updatedAtEpochMillis = endedAt
    )
}
