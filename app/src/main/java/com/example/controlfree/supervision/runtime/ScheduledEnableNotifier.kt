package com.example.controlfree.supervision.runtime

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.controlfree.MainActivity
import com.example.controlfree.MonitorNotificationChannels
import com.example.controlfree.R
import com.example.controlfree.supervision.persistence.ScheduledEnableFailure
import com.example.controlfree.supervision.persistence.ScheduledEnableFailureReason

internal class ScheduledEnableNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun notifyActivationFailures(failures: List<ScheduledEnableFailure>) {
        if (failures.isEmpty()) return
        val lines = failures.map(::failureText)
        val summary = if (lines.size == 1) lines.single() else "${lines.size} 个预约任务未能开启"
        val style = NotificationCompat.InboxStyle().setSummaryText("请进入应用检查任务设置")
        lines.take(MAX_FAILURE_LINES).forEach(style::addLine)
        publish(
            notificationId = FAILURE_NOTIFICATION_ID,
            title = "预约任务未能开启",
            text = summary,
            style = style
        )
    }

    fun notifyRuntimePrerequisitesMissing(planNames: List<String>) {
        if (planNames.isEmpty()) return
        val names = planNames.distinct().joinToString("、").take(MAX_NOTIFICATION_TEXT_LENGTH)
        publish(
            notificationId = PERMISSION_NOTIFICATION_ID,
            title = "预约任务需要运行权限",
            text = "$names 已开启，但当前权限不足，处理后系统会自动重试。"
        )
    }

    private fun publish(
        notificationId: Int,
        title: String,
        text: String,
        style: NotificationCompat.Style? = null
    ) {
        try {
            MonitorNotificationChannels.ensureCreated(appContext)
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            val contentIntent = PendingIntent.getActivity(
                appContext,
                notificationId,
                Intent(appContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(
                appContext,
                MonitorNotificationChannels.SCHEDULE_EVENT_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_lock_screen)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            style?.let(builder::setStyle)
            manager.notify(notificationId, builder.build())
        } catch (_: RuntimeException) {
            // 下次进入任务页仍能从卡片状态确认任务是否已启用。
        }
    }

    private fun failureText(failure: ScheduledEnableFailure): String {
        val name = failure.planName?.takeIf(String::isNotBlank) ?: "任务 ${failure.planId.take(8)}"
        val reason = when (failure.reason) {
            ScheduledEnableFailureReason.NOT_FOUND -> "任务已不存在"
            ScheduledEnableFailureReason.CORRUPT_DATA -> "任务数据异常"
            ScheduledEnableFailureReason.ALREADY_ENABLED -> "任务已经开启"
            ScheduledEnableFailureReason.EXPIRED -> "预约时间已经结束"
            ScheduledEnableFailureReason.VERSION_EXHAUSTED -> "任务版本异常"
            ScheduledEnableFailureReason.CONFLICT -> "与其他已开启任务冲突"
        }
        return "$name：$reason"
    }

    private companion object {
        const val FAILURE_NOTIFICATION_ID = 9_421
        const val PERMISSION_NOTIFICATION_ID = 9_422
        const val MAX_FAILURE_LINES = 5
        const val MAX_NOTIFICATION_TEXT_LENGTH = 80
    }
}
