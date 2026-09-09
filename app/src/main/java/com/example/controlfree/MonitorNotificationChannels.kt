package com.example.controlfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 统一创建监督服务和锁定恢复通知频道。
 *
 * 两类通知的用途不同，必须使用独立频道，避免用户降低普通监督通知的重要级别后，
 * 备用锁定通知也随之失效。
 */
object MonitorNotificationChannels {
    // 沿用 v6 频道 ID，保留用户已有的启用/禁用选择；锁定恢复通知使用新的独立频道。
    const val SERVICE_CHANNEL_ID = "control_free_service_channel_v2"
    const val LOCK_RECOVERY_CHANNEL_ID = "control_free_lock_recovery_v1"
    const val SCHEDULE_EVENT_CHANNEL_ID = "control_free_schedule_events_v1"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val appName = context.getString(R.string.app_name)
        val serviceChannel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            "${appName}监督服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示监督阶段和剩余时间，保障监督服务持续运行"
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val lockRecoveryChannel = NotificationChannel(
            LOCK_RECOVERY_CHANNEL_ID,
            "${appName}锁定恢复",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "悬浮锁层不可用时提示返回锁定界面"
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val scheduleEventChannel = NotificationChannel(
            SCHEDULE_EVENT_CHANNEL_ID,
            "${appName}预约任务",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "提示预约任务未能开启或需要重新处理运行权限"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(
            listOf(serviceChannel, lockRecoveryChannel, scheduleEventChannel)
        )
    }
}
