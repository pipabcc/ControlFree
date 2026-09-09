package com.example.controlfree.todo.habit.runtime

import android.content.Context
import com.example.controlfree.todo.TodoRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.first

class HabitReminderRuntimeCoordinator(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val appContext = context.applicationContext
    private val preferences = HabitReminderPreferences(appContext)
    private val repository = TodoRepository.getInstance(appContext)
    private val scheduler = HabitReminderAlarmScheduler(appContext, clock)
    private val notifications = HabitReminderNotificationPublisher(appContext)

    suspend fun reconcile(deliverReminder: Boolean) {
        val settings = preferences.read()
        notifications.ensureChannel()
        if (!settings.enabled) {
            notifications.cancel()
            scheduler.cancel()
            return
        }

        val now = Instant.now(clock)
        val today = now.atZone(clock.zone).toLocalDate()
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val pendingHabits = HabitReminderPlanner.pendingHabits(
            habits = repository.observeActiveHabits().first(),
            records = repository.observeHabitRecords(
                startDate = weekStart.toString(),
                endDate = today.toString()
            ).first(),
            date = today
        )
        if (deliverReminder) {
            notifications.publish(pendingHabits, now)
        } else {
            notifications.cancel()
        }
        scheduler.scheduleNext(settings)
    }
}
