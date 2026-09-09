package com.example.controlfree

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 以单调时钟安排监督阶段边界。Handler 只负责前台逐秒刷新；真正的阶段结算
 * 由该唤醒闹钟兜底，因此设备进入深度休眠也不会丢失熄屏锁定时长。
 */
internal class MonitorPhaseAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(triggerAtElapsedMillis: Long): Boolean {
        val manager = alarmManager ?: return false
        val safeTrigger = triggerAtElapsedMillis.coerceAtLeast(
            SystemClock.elapsedRealtime() + MIN_TRIGGER_DELAY_MILLIS
        )
        return try {
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            ) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    safeTrigger,
                    operation()
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    safeTrigger,
                    operation()
                )
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel() {
        try {
            alarmManager?.cancel(operation())
        } catch (_: RuntimeException) {
            // 后续屏幕广播、前台心跳或服务恢复仍会按单调快照结算。
        }
    }

    private fun operation(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, MonitorPhaseBoundaryReceiver::class.java).apply {
            action = MonitorService.ACTION_RECONCILE_MONITOR_PHASE
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_CODE = 4_103
        const val MIN_TRIGGER_DELAY_MILLIS = 50L
    }
}

class MonitorPhaseBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MonitorService.ACTION_RECONCILE_MONITOR_PHASE) return
        val appContext = context.applicationContext
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_RECONCILE_MONITOR_PHASE
                }
            )
        } catch (_: RuntimeException) {
            // 熄屏深睡时这是唯一的结算入口，且闹钟是一次性的。启动失败若不重排，
            // 锁定阶段将再也不会被唤醒结算，表现为"熄屏也不倒计时"。
            try {
                MonitorPhaseAlarmScheduler(appContext).schedule(
                    SystemClock.elapsedRealtime() + BOUNDARY_RETRY_DELAY_MILLIS
                )
            } catch (_: RuntimeException) {
                // 屏幕广播与开机恢复入口仍是最后兜底。
            }
        }
    }

    private companion object {
        const val BOUNDARY_RETRY_DELAY_MILLIS = 60_000L
    }
}
