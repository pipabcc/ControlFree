package com.example.controlfree

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

internal class MonitorPauseAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun canScheduleReliableDeadline(): Boolean {
        val manager = alarmManager ?: return false
        return try {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        } catch (_: RuntimeException) {
            false
        }
    }

    fun schedule(resumeAtEpochMillis: Long): Boolean {
        if (resumeAtEpochMillis <= System.currentTimeMillis()) return false
        val manager = alarmManager ?: return false
        return try {
            val operation = resumePendingIntent()
            if (!canScheduleReliableDeadline()) return false
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                resumeAtEpochMillis,
                operation
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel() {
        try {
            alarmManager?.cancel(resumePendingIntent())
        } catch (_: RuntimeException) {
            // 前台服务的一秒计时仍会恢复暂停；取消失败不改变监督状态。
        }
    }

    private fun resumePendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, MonitorPauseReceiver::class.java).apply {
            action = MonitorService.ACTION_RESUME_PAUSED_MONITOR
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_CODE = 4_321
    }
}

class MonitorPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MonitorService.ACTION_RESUME_PAUSED_MONITOR) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_RESUME_PAUSED_MONITOR
                }
            )
        } catch (_: RuntimeException) {
            // BootReceiver、前台服务恢复和监督协调器仍会再次读取持久化暂停状态。
        }
    }
}
