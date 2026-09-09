package com.example.controlfree.todo

import android.content.Context
import com.example.controlfree.supervision.persistence.ControlFreeDatabase
import java.time.Clock
import kotlinx.coroutines.flow.Flow

/** 只管理新 TimeBlock 页的独立 Event，不参与旧日程统计。 */
class TimeBlockEventRepository private constructor(
    private val dao: TimeBlockEventDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun observeAll(): Flow<List<TimeBlockEventEntity>> = dao.observeAll()

    fun observeInRange(
        startEpochMillis: Long,
        endExclusiveEpochMillis: Long
    ): Flow<List<TimeBlockEventEntity>> {
        require(startEpochMillis >= 0L)
        require(endExclusiveEpochMillis > startEpochMillis)
        return dao.observeInRange(startEpochMillis, endExclusiveEpochMillis)
    }

    suspend fun getById(id: String): TimeBlockEventEntity? =
        dao.getById(id.requireIdentifier())

    suspend fun save(event: TimeBlockEventEntity): TimeBlockEventEntity {
        val now = clock.millis()
        val existing = dao.getById(event.id)
        val normalized = event.copy(
            title = event.title.trim(),
            description = event.description?.trim()?.takeIf(String::isNotEmpty),
            project = event.project.trim(),
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: event.createdAtEpochMillis,
            updatedAtEpochMillis = maxOf(now, existing?.updatedAtEpochMillis?.plus(1L) ?: now)
        )
        dao.upsert(normalized)
        return normalized
    }

    suspend fun toggleCompletion(id: String, completed: Boolean): Boolean {
        val normalizedId = id.requireIdentifier()
        val now = clock.millis()
        return dao.updateCompletion(
            id = normalizedId,
            isCompleted = completed,
            completedAt = now.takeIf { completed },
            updatedAt = now
        ) == 1
    }

    suspend fun delete(id: String): TimeBlockEventEntity? {
        val normalizedId = id.requireIdentifier()
        val event = dao.getById(normalizedId) ?: return null
        return event.takeIf { dao.delete(normalizedId) == 1 }
    }

    private fun String.requireIdentifier(): String = trim().also {
        require(it.isNotEmpty()) { "日程编号不能为空" }
    }

    companion object {
        @Volatile
        private var instance: TimeBlockEventRepository? = null

        fun getInstance(context: Context): TimeBlockEventRepository =
            instance ?: synchronized(this) {
                instance ?: TimeBlockEventRepository(
                    ControlFreeDatabase.getInstance(context.applicationContext).timeBlockEventDao()
                ).also { instance = it }
            }
    }
}
