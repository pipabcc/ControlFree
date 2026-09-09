package com.example.controlfree.todo.habit.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Clock
import java.time.ZonedDateTime

class HabitReminderAlarmScheduler(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun scheduleNext(settings: HabitReminderSettings) {
        if (!settings.enabled) {
            cancel()
            return
        }
        val manager = alarmManager ?: return
        val triggerAt = HabitReminderPlanner.nextTrigger(
            now = ZonedDateTime.now(clock),
            reminderMinute = settings.reminderMinute
        ).toEpochMilli()
        val operation = reminderPendingIntent()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (_: RuntimeException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        }
    }

    fun cancel() {
        val manager = alarmManager ?: return
        runCatching { manager.cancel(reminderPendingIntent()) }
    }

    private fun reminderPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, HabitReminderReceiver::class.java).apply {
            action = HabitReminderReceiver.ACTION_DELIVER_REMINDER
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_CODE = 8410
    }
}
