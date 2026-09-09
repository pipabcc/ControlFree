package com.example.controlfree.todo.anniversary.runtime

import android.content.Context
import com.example.controlfree.todo.AnniversaryItemEntity
import com.example.controlfree.todo.TodoRepository
import com.example.controlfree.todo.anniversary.widget.AnniversaryWidgetProvider
import com.example.controlfree.ui.todo.anniversary.AndroidIcuLunarCalendar
import com.example.controlfree.ui.todo.anniversary.AnniversaryOccurrenceResolver
import com.example.controlfree.ui.todo.anniversary.AnniversaryType
import com.example.controlfree.ui.todo.anniversary.toSpec
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnniversaryRuntimeCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val repository = TodoRepository.getInstance(appContext)
    private val resolver = AnniversaryOccurrenceResolver(AndroidIcuLunarCalendar())
    private val scheduler = AnniversaryAlarmScheduler(appContext)
    private val notifications = AnniversaryNotificationPublisher(appContext, resolver)

    suspend fun refresh(now: Instant = Instant.now()) = refreshMutex.withLock {
        val items = repository.observeAllAnniversaries().first()
        repository.reconcileDueAnniversaryOccurrences(now.toEpochMilli())
        notifications.sync(items, now)

        // 2. 检测最新到达的时刻，发出高优先级系统通知提醒
        val pendingCelebrations = repository.observePendingCelebrations().first()
        val reachedAnniversaries = pendingCelebrations.filter {
            it.celebrationType == com.example.controlfree.todo.CelebrationType.ANNIVERSARY_REACHED.storedValue
        }
        val sp = appContext.getSharedPreferences("anniversary_alerts", Context.MODE_PRIVATE)
        val shownAlertIds = sp.getStringSet("shown_alert_ids", emptySet()).orEmpty().toMutableSet()
        val newAlerts = reachedAnniversaries.filter { it.id !in shownAlertIds }
        newAlerts.forEach { celebration ->
            val anniversary = items.firstOrNull { it.id == celebration.subjectId }
            if (anniversary != null) {
                notifications.showAlertNotification(anniversary, celebration.id)
                shownAlertIds.add(celebration.id)
            }
        }
        sp.edit().putStringSet("shown_alert_ids", shownAlertIds).apply()

        AnniversaryWidgetProvider.updateAll(appContext)
        scheduleNext(items, now)
    }

    private fun scheduleNext(items: List<AnniversaryItemEntity>, now: Instant) {
        val nextOccurrence = items.asSequence()
            .mapNotNull { item ->
                runCatching {
                    val spec = item.toSpec(ZoneId.systemDefault())
                    if (spec.type != AnniversaryType.COUNTDOWN) return@runCatching null
                    resolver.resolve(spec, now.plusMillis(1)).instant
                        .takeIf { it.isAfter(now) }
                }.getOrNull()
            }
            .minOrNull()
        // 常驻通知的文本剩余时长依赖周期刷新（厂商可能不渲染系统计时器），
        // 存在常驻项时至少每小时唤醒一次同步文本。
        val notificationRefresh = if (items.any(AnniversaryItemEntity::showOnLockScreen)) {
            now.plusSeconds(NOTIFICATION_TEXT_REFRESH_SECONDS)
        } else {
            null
        }
        val next = listOfNotNull(nextOccurrence, notificationRefresh).minOrNull()
        if (next == null) scheduler.cancel() else scheduler.schedule(next.toEpochMilli())
    }

    private companion object {
        // ViewModel 操作与系统广播可能同时触发刷新，串行化通知和闹钟快照。
        val refreshMutex = Mutex()
        const val NOTIFICATION_TEXT_REFRESH_SECONDS = 3_600L
    }
}
