package com.example.controlfree.todo.habit.runtime

import android.app.AlarmManager
import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitReminderReceiverTest {
    @Test
    fun `闹钟和系统恢复广播均被接收`() {
        assertTrue(HabitReminderReceiver.isSupportedAction(HabitReminderReceiver.ACTION_DELIVER_REMINDER))
        assertTrue(HabitReminderReceiver.isSupportedAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(HabitReminderReceiver.isSupportedAction(Intent.ACTION_USER_UNLOCKED))
        assertTrue(HabitReminderReceiver.isSupportedAction(Intent.ACTION_DATE_CHANGED))
        assertTrue(HabitReminderReceiver.isSupportedAction(Intent.ACTION_TIME_CHANGED))
        assertTrue(HabitReminderReceiver.isSupportedAction(Intent.ACTION_TIMEZONE_CHANGED))
        assertTrue(
            HabitReminderReceiver.isSupportedAction(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
            )
        )
    }

    @Test
    fun `无关广播不会触发提醒`() {
        assertFalse(HabitReminderReceiver.isSupportedAction(null))
        assertFalse(HabitReminderReceiver.isSupportedAction(Intent.ACTION_SCREEN_ON))
        assertFalse(
            HabitReminderReceiver.isSupportedAction("com.example.controlfree.UNKNOWN")
        )
    }
}
