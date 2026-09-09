package com.example.controlfree.todo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class UnifiedReminderAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun scheduleTodoReminders(todo: TodoItemEntity) {
        cancelTodoReminders(todo.id, todo.remindersJson.toItemReminders().map { it.minutesBefore })
        val startMillis = todo.scheduledStartEpochMillis ?: return
        if (todo.isCompleted) return

        val reminders = todo.remindersJson.toItemReminders()
        reminders.forEach { reminder ->
            val triggerAt = startMillis - (reminder.minutesBefore * 60_000L)
            if (triggerAt > System.currentTimeMillis()) {
                val intent = Intent(context, UnifiedReminderReceiver::class.java).apply {
                    action = UnifiedReminderReceiver.ACTION_TRIGGER_REMINDER
                    putExtra("item_type", "TODO")
                    putExtra("item_id", todo.id)
                    putExtra("title", "待办提醒")
                    putExtra("content", "您的待办【${todo.title}】即将开始")
                    putExtra("channel_type", reminder.channel.name)
                }
                val requestCode = getTodoRequestCode(todo.id, reminder.minutesBefore)
                setAlarm(triggerAt, intent, requestCode)
            }
        }
    }

    fun cancelTodoReminders(todoId: String, minutesBeforeList: List<Int>) {
        minutesBeforeList.forEach { minutes ->
            cancelAlarm(getTodoRequestCode(todoId, minutes))
        }
    }

    fun scheduleAnniversaryReminders(anniversary: AnniversaryItemEntity) {
        cancelAnniversaryReminders(anniversary.id, anniversary.remindersJson.toItemReminders().map { it.minutesBefore })
        val targetMillis = anniversary.targetDateEpochMillis

        val reminders = anniversary.remindersJson.toItemReminders()
        reminders.forEach { reminder ->
            val triggerAt = targetMillis - (reminder.minutesBefore * 60_000L)
            if (triggerAt > System.currentTimeMillis()) {
                val intent = Intent(context, UnifiedReminderReceiver::class.java).apply {
                    action = UnifiedReminderReceiver.ACTION_TRIGGER_REMINDER
                    putExtra("item_type", "ANNIVERSARY")
                    putExtra("item_id", anniversary.id)
                    putExtra("title", "时刻提醒")
                    putExtra("content", "您的时刻【${anniversary.title}】即将到达")
                    putExtra("channel_type", reminder.channel.name)
                }
                val requestCode = getAnniversaryRequestCode(anniversary.id, reminder.minutesBefore)
                setAlarm(triggerAt, intent, requestCode)
            }
        }
    }

    fun cancelAnniversaryReminders(anniversaryId: String, minutesBeforeList: List<Int>) {
        minutesBeforeList.forEach { minutes ->
            cancelAlarm(getAnniversaryRequestCode(anniversaryId, minutes))
        }
    }

    fun scheduleHabitReminders(habit: HabitItemEntity, hasCompletedToday: Boolean = false) {
        val config = habit.remindersJson.toHabitReminderConfig()
        config.timedReminders.forEach { r ->
            cancelAlarm(getHabitTimedRequestCode(habit.id, r.hour, r.minute))
        }
        cancelAlarm(getHabitIntervalRequestCode(habit.id))

        if (habit.isArchived) return

        val now = LocalDateTime.now(ZoneId.systemDefault())
        config.timedReminders.forEach { r ->
            var triggerTime = LocalDateTime.of(LocalDate.now(), LocalTime.of(r.hour, r.minute))
            if (triggerTime.isBefore(now)) {
                triggerTime = triggerTime.plusDays(1)
            }
            val triggerAt = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val intent = Intent(context, UnifiedReminderReceiver::class.java).apply {
                action = UnifiedReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra("item_type", "HABIT")
                putExtra("habit_reminder_type", "TIMED")
                putExtra("item_id", habit.id)
                putExtra("title", "习惯提醒")
                putExtra("content", "不要忘记今天的习惯打卡：【${habit.name}】")
                putExtra("channel_type", r.channel.name)
            }
            setAlarm(triggerAt, intent, getHabitTimedRequestCode(habit.id, r.hour, r.minute))
        }

        val ir = config.intervalReminder
        if (ir != null && !hasCompletedToday) {
            val currentHour = now.hour
            val triggerTime = when {
                currentHour < ir.startHour -> {
                    LocalDateTime.of(LocalDate.now(), LocalTime.of(ir.startHour, 0))
                }
                currentHour >= ir.endHour -> {
                    LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(ir.startHour, 0))
                }
                else -> {
                    now.plusMinutes(ir.intervalMinutes.toLong())
                }
            }
            val triggerAt = triggerTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val intent = Intent(context, UnifiedReminderReceiver::class.java).apply {
                action = UnifiedReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra("item_type", "HABIT")
                putExtra("habit_reminder_type", "INTERVAL")
                putExtra("item_id", habit.id)
                putExtra("title", "习惯循环提醒")
                putExtra("content", "记得去完成习惯打卡哦：【${habit.name}】")
                putExtra("channel_type", ir.channel.name)
            }
            setAlarm(triggerAt, intent, getHabitIntervalRequestCode(habit.id))
        }
    }

    fun cancelAllHabitAlarms(habit: HabitItemEntity) {
        val config = habit.remindersJson.toHabitReminderConfig()
        config.timedReminders.forEach { r ->
            cancelAlarm(getHabitTimedRequestCode(habit.id, r.hour, r.minute))
        }
        cancelAlarm(getHabitIntervalRequestCode(habit.id))
    }

    private fun setAlarm(triggerAtMillis: Long, intent: Intent, requestCode: Int) {
        val manager = alarmManager ?: return
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: Exception) {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val manager = alarmManager ?: return
        val intent = Intent(context, UnifiedReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            manager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    companion object {
        fun getTodoRequestCode(id: String, minutesBefore: Int): Int =
            ("todo_" + id + "_" + minutesBefore).hashCode()

        fun getAnniversaryRequestCode(id: String, minutesBefore: Int): Int =
            ("anniversary_" + id + "_" + minutesBefore).hashCode()

        fun getHabitTimedRequestCode(id: String, hour: Int, minute: Int): Int =
            ("habit_timed_" + id + "_" + hour + "_" + minute).hashCode()

        fun getHabitIntervalRequestCode(id: String): Int =
            ("habit_interval_" + id).hashCode()
    }
}
