package com.example.controlfree.todo.habit.runtime

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HabitReminderPreferencesInstrumentedTest {
    @Test
    fun defaultReminderIsEnabledAtEightPm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = HabitReminderPreferences(context)
        preferences.setEnabled(true)
        preferences.setReminderMinute(HabitReminderSettings.DEFAULT_REMINDER_MINUTE)

        val settings = preferences.read()

        assertTrue(settings.enabled)
        assertEquals(HabitReminderSettings.DEFAULT_REMINDER_MINUTE, settings.reminderMinute)
    }

    @Test
    fun reminderSettingsPersistAndStayWithinDayRange() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = HabitReminderPreferences(context)

        preferences.setEnabled(false)
        preferences.setReminderMinute(Int.MAX_VALUE)
        assertFalse(preferences.read().enabled)
        assertEquals(HabitReminderSettings.MINUTES_PER_DAY - 1, preferences.read().reminderMinute)

        preferences.setEnabled(true)
        preferences.setReminderMinute(-1)
        assertTrue(preferences.read().enabled)
        assertEquals(0, preferences.read().reminderMinute)
    }

    @Test
    fun reminderReceiverIsRegisteredAndNotExported() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getReceiverInfo(
            ComponentName(context, HabitReminderReceiver::class.java),
            0
        )

        assertFalse(info.exported)
    }
}
