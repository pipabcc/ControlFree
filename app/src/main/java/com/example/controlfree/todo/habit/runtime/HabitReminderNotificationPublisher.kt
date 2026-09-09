package com.example.controlfree.todo.habit.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.controlfree.AppShortcutDestination
import com.example.controlfree.AppShortcutRoute
import com.example.controlfree.MainActivity
import com.example.controlfree.R
import com.example.controlfree.ui.todo.TodoSubTab
import java.time.Instant

class HabitReminderNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    @SuppressLint("MissingPermission")
    fun publish(pendingHabits: List<PendingHabitReminder>, now: Instant) {
        ensureChannel()
        if (pendingHabits.isEmpty() || !canPostNotifications()) {
            cancel()
            return
        }

        val contentIntent = PendingIntent.getActivity(
            appContext,
            OPEN_HABITS_REQUEST_CODE,
            Intent(appContext, MainActivity::class.java).apply {
                action = ACTION_OPEN_HABITS
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(
                    AppShortcutRoute.EXTRA_DESTINATION,
                    AppShortcutDestination.TODO.name
                )
                putExtra(TodoSubTab.EXTRA_NAVIGATION_TARGET, TodoSubTab.HABIT.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val first = pendingHabits.first()
        val title = if (pendingHabits.size == 1) {
            "该打卡了：${first.habit.name}"
        } else {
            "今日还有 ${pendingHabits.size} 个习惯待打卡"
        }
        val contentText = if (pendingHabits.size == 1) {
            progressText(first)
        } else {
            pendingHabits.take(2).joinToString("、") { it.habit.name }
        }
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)
            .setSummaryText("点击进入清单习惯页")
        pendingHabits.take(MAX_VISIBLE_HABITS).forEach { pending ->
            style.addLine("${pending.habit.name} · ${progressText(pending)}")
        }
        if (pendingHabits.size > MAX_VISIBLE_HABITS) {
            style.addLine("另有 ${pendingHabits.size - MAX_VISIBLE_HABITS} 个习惯")
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_habit)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(now.toEpochMilli())
            .setShowWhen(true)
            .setNumber(pendingHabits.size)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel() {
        runCatching { notificationManager.cancel(NOTIFICATION_ID) }
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "习惯提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "每天提醒尚未达标的习惯"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) && notificationManager.areNotificationsEnabled()

    private fun progressText(pending: PendingHabitReminder): String =
        "已完成 ${pending.completionCount}/${pending.habit.targetCountPerDay.coerceAtLeast(1)}"

    companion object {
        const val CHANNEL_ID = "controlfree_habit_reminders_v1"
        const val ACTION_OPEN_HABITS = "com.example.controlfree.action.OPEN_HABITS"
        private const val NOTIFICATION_ID = 8411
        private const val OPEN_HABITS_REQUEST_CODE = 8412
        private const val MAX_VISIBLE_HABITS = 5
    }
}
