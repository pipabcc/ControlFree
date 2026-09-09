package com.example.controlfree.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoRepositoryAnniversaryDatabaseTest {
    private val now = Instant.parse("2026-07-22T04:00:00Z")
    private lateinit var database: ControlFreeDatabase
    private lateinit var repository: TodoRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ControlFreeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = TodoRepository.createForTest(database, Clock.fixed(now, ZoneOffset.UTC))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 错过一次性倒数日后重复刷新只落库并庆祝一次() = runBlocking {
        val target = Instant.parse("2026-07-21T09:30:00Z")
        repository.saveAnniversary(
            AnniversaryItemEntity(
                id = "missed-anniversary",
                title = "项目上线",
                targetDateEpochMillis = target.toEpochMilli(),
                isLunar = false,
                type = AnniversaryType.COUNTDOWN.storedValue,
                repeatRule = AnniversaryRepeatRule.NONE.storedValue,
                isPinnedTop = false,
                showOnWidget = false,
                createdAtEpochMillis = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli(),
                sourceYear = 2026,
                sourceMonth = 7,
                sourceDay = 21,
                sourceHour = 9,
                sourceMinute = 30,
                sourceSecond = 0,
                zoneId = ZoneOffset.UTC.id
            )
        )

        assertEquals(1, repository.reconcileDueAnniversaryOccurrences(now.toEpochMilli()))
        assertEquals(0, repository.reconcileDueAnniversaryOccurrences(now.plusSeconds(60).toEpochMilli()))

        val occurrences = repository.observeAnniversaryOccurrences(
            target.minusSeconds(1).toEpochMilli(),
            now.plusSeconds(120).toEpochMilli()
        ).first()
        val celebrations = repository.observePendingCelebrations().first()
        val achievements = repository.observeAllAchievements().first()
        assertEquals(1, occurrences.size)
        assertEquals(1, celebrations.size)
        assertEquals(CelebrationType.ANNIVERSARY_REACHED.storedValue, celebrations.single().celebrationType)
        assertEquals(1, achievements.size)
        assertEquals(AchievementType.ANNIVERSARY_REACHED.storedValue, achievements.single().achievementType)
        assertEquals("重要时刻达成", achievements.single().title)
    }

    @Test
    fun 删除时刻后可撤销且不会覆盖同编号新记录() = runBlocking {
        val anniversary = anniversary("undo-anniversary", "原时刻")
        val saved = repository.saveAnniversary(anniversary)
        val snapshot = requireNotNull(repository.deleteAnniversaryForUndo(saved.id))
        assertNull(repository.getAnniversaryById(saved.id))

        assertTrue(repository.restoreDeletedAnniversary(snapshot))
        assertEquals(saved, repository.getAnniversaryById(saved.id))

        val secondSnapshot = requireNotNull(repository.deleteAnniversaryForUndo(saved.id))
        val replacement = repository.saveAnniversary(saved.copy(title = "新时刻"))
        assertFalse(repository.restoreDeletedAnniversary(secondSnapshot))
        assertEquals(replacement, repository.getAnniversaryById(saved.id))
    }

    private fun anniversary(id: String, title: String) = AnniversaryItemEntity(
        id = id,
        title = title,
        targetDateEpochMillis = now.plusSeconds(86_400L).toEpochMilli(),
        isLunar = false,
        type = AnniversaryType.COUNTDOWN.storedValue,
        repeatRule = AnniversaryRepeatRule.NONE.storedValue,
        isPinnedTop = false,
        showOnWidget = false,
        createdAtEpochMillis = now.toEpochMilli(),
        sourceYear = 2026,
        sourceMonth = 7,
        sourceDay = 23,
        zoneId = ZoneOffset.UTC.id
    )
}
