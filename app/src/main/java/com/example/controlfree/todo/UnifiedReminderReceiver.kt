package com.example.controlfree.todo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.controlfree.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UnifiedReminderReceiver : BroadcastReceiver() {
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_TRIGGER_REMINDER) {
            val itemType = intent.getStringExtra("item_type") ?: ""
            val itemId = intent.getStringExtra("item_id") ?: ""
            val title = intent.getStringExtra("title") ?: "提醒"
            val content = intent.getStringExtra("content") ?: "您有一个提醒"
            val channelTypeName = intent.getStringExtra("channel_type") ?: ReminderChannel.BOTH.name
            val channelType = try {
                ReminderChannel.valueOf(channelTypeName)
            } catch (_: Exception) {
                ReminderChannel.BOTH
            }
            val habitReminderType = intent.getStringExtra("habit_reminder_type") ?: ""

            if (channelType == ReminderChannel.NOTIFICATION || channelType == ReminderChannel.BOTH) {
                sendNotification(context, itemId, title, content)
            }

            if (channelType == ReminderChannel.CARD || channelType == ReminderChannel.BOTH) {
                val shownOverlay = ReminderAlertOverlay.show(context, title, content, itemId)
                if (!shownOverlay) {
                    sendFullScreenActivityReminder(context, itemId, title, content, itemType)
                }
            }

            if (itemType == "HABIT" && habitReminderType == "INTERVAL") {
                val pendingResult = goAsync()
                receiverScope.launch {
                    try {
                        val repository = TodoRepository.getInstance(context.applicationContext)
                        val habit = repository.getHabitById(itemId)
                        if (habit != null) {
                            val scheduler = UnifiedReminderAlarmScheduler(context.applicationContext)
                            scheduler.scheduleHabitReminders(habit)
                        }
                    } catch (_: Exception) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        } else if (action == ACTION_DISMISS_NOTIFICATION) {
            val itemId = intent.getStringExtra("item_id") ?: ""
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(itemId.hashCode())
        }
    }

    private fun sendNotification(context: Context, itemId: String, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "unified_reminder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "应用任务提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "下拉展示应用各项任务的自定义提醒"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(itemId.hashCode(), notification)
    }

    private fun sendFullScreenActivityReminder(context: Context, itemId: String, title: String, content: String, itemType: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "unified_reminder_alert_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "任务到达提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "当任务到达设定时间时发出高优先级通知及全屏卡片弹窗"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val alertIntent = Intent(context, ReminderAlertActivity::class.java).apply {
            putExtra("title", title)
            putExtra("content", content)
            putExtra("item_id", itemId)
            putExtra("item_type", itemType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            itemId.hashCode() + 10,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "知道了", PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + 20,
                Intent(context, UnifiedReminderReceiver::class.java).apply {
                    action = ACTION_DISMISS_NOTIFICATION
                    putExtra("item_id", itemId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        notificationManager.notify(itemId.hashCode(), builder.build())
    }

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.example.controlfree.action.TRIGGER_REMINDER"
        const val ACTION_DISMISS_NOTIFICATION = "com.example.controlfree.action.DISMISS_NOTIFICATION"
    }
}
