package com.example.controlfree.todo.anniversary.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class AnniversaryAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtEpochMillis: Long) {
        val manager = alarmManager ?: return
        val safeTrigger = triggerAtEpochMillis.coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MILLIS)
        val operation = refreshPendingIntent()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, operation)
            }
        } catch (_: RuntimeException) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, operation)
            }
        }
    }

    fun cancel() {
        val manager = alarmManager ?: return
        runCatching { manager.cancel(refreshPendingIntent()) }
    }

    private fun refreshPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            Intent(appContext, AnniversaryRefreshReceiver::class.java).apply {
                action = AnniversaryRefreshReceiver.ACTION_REFRESH
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private companion object {
        const val REQUEST_CODE = 7461
        const val MIN_DELAY_MILLIS = 1_000L
    }
}
